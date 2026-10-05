//! The coin box (ESP32): signed HTTP calls (docs/api/gateway-coinslot.md) and its signed UDP coin events.
use crate::util::{hmac_hex, jget};
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Clone)]
pub struct BoxLink {
    pub addr: String,
    pub key: String,
}

/// One plain HTTP GET (the box speaks plain HTTP on the kiosk LAN): the body, whatever the status (errors carry JSON).
pub fn http_get(addr: &str, path: &str, timeout: Duration) -> Result<String, String> {
    let sock = addr.to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("no address")?;
    let mut s = TcpStream::connect_timeout(&sock, timeout).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(timeout)).ok();
    s.set_write_timeout(Some(timeout)).ok();
    s.set_nodelay(true).ok();
    let host = addr.split(':').next().unwrap_or(addr);
    write!(s, "GET {} HTTP/1.0\r\nHost: {}\r\nConnection: close\r\n\r\n", path, host).map_err(|e| e.to_string())?;
    let mut buf = Vec::new();
    let mut chunk = [0u8; 2048];
    loop {
        match s.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if buf.len() > 65536 {
                    break;
                }
            }
            Err(e) if !buf.is_empty() && (e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut) => break,
            Err(e) => return Err(e.to_string()),
        }
    }
    let text = String::from_utf8_lossy(&buf).into_owned();
    Ok(match text.split_once("\r\n\r\n") {
        Some((_, body)) => body.to_string(),
        None => text,
    })
}

impl BoxLink {
    pub fn new(addr: &str, key: &str) -> BoxLink {
        BoxLink { addr: addr.to_string(), key: key.to_string() }
    }

    pub fn challenge(&self) -> Option<String> {
        let body = http_get(&self.addr, "/api/gateway/challenge", Duration::from_secs(3)).ok()?;
        let n = jget(&body, "nonce");
        (!n.is_empty()).then_some(n)
    }

    /// A signed call: fetch a one-time nonce, sign "gw1:<action>:<sid>:<nonce>", send. The body of the box's answer
    /// (JSON), or an error text when the box could not be reached at all.
    pub fn call(&self, sid: &str, action: &str, extra: &str) -> Result<String, String> {
        let nonce = self.challenge().ok_or_else(|| "NO_NONCE".to_string())?;
        let sig = hmac_hex(self.key.as_bytes(), format!("gw1:{}:{}:{}", action, sid, nonce).as_bytes());
        http_get(&self.addr, &format!("/api/gateway/{}?session={}&nonce={}&sig={}{}", action, sid, nonce, sig, extra), Duration::from_secs(4))
    }

    pub fn is_success(body: &str) -> bool {
        jget(body, "success") == "true"
    }
}

#[derive(Debug, PartialEq)]
pub struct Event {
    pub sid: String,
    pub wid: String,
    pub seq: u64,
    pub kind: String,
    pub pulses: u32,
}

/// "gw1ev:<session>:<wid>:<seq>:<type>:<pulses>:<sig>", signed over everything before the last colon. Fixed fields are
/// read from the right so a session containing ':' stays whole. None when malformed or the signature is wrong.
pub fn parse_event(line: &str, key: &str) -> Option<Event> {
    let line = line.trim();
    let rest = line.strip_prefix("gw1ev:")?;
    let (body_tail, sig) = rest.rsplit_once(':')?;
    if sig.is_empty() || !sig.bytes().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let (r, pulses) = body_tail.rsplit_once(':')?;
    let (r, kind) = r.rsplit_once(':')?;
    let (r, seq) = r.rsplit_once(':')?;
    let (sid, wid) = r.rsplit_once(':')?;
    if !matches!(kind, "ready" | "coin" | "end") || sid.is_empty() || wid.is_empty() || !wid.bytes().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    if hmac_hex(key.as_bytes(), format!("gw1ev:{}", body_tail).as_bytes()) != sig {
        return None;
    }
    Some(Event { sid: sid.to_string(), wid: wid.to_string(), seq: seq.parse().ok()?, kind: kind.to_string(), pulses: pulses.parse().ok()? })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn events_are_verified_and_parsed_from_the_right() {
        let key = "test-gateway-key-123456";
        let body = "gw1ev:abc123:def456:7:coin:12";
        let line = format!("{}:{}", body, hmac_hex(key.as_bytes(), body.as_bytes()));
        assert_eq!(parse_event(&line, key), Some(Event { sid: "abc123".into(), wid: "def456".into(), seq: 7, kind: "coin".into(), pulses: 12 }));
        assert_eq!(parse_event(&line, "other key 0123456789"), None);
        assert_eq!(parse_event("gw1ev:abc:def:1:coin:1:zz", key), None);
        assert_eq!(parse_event("junk", key), None);
        let colon = "gw1ev:a:b:c:def456:8:end:3";
        let l2 = format!("{}:{}", colon, hmac_hex(key.as_bytes(), colon.as_bytes()));
        assert_eq!(parse_event(&l2, key).unwrap().sid, "a:b:c");
    }
}
