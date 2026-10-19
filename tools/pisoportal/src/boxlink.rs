//! The coin box (ESP32): signed HTTP calls (docs/api/gateway-coinslot.md) and its signed UDP coin events.
use crate::util::{hmac_hex, jget};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[derive(Clone)]
pub struct BoxLink {
    pub addr: String,
    pub key: String,
    /// The box has signed an answer at least once (see `check_answer`); remembered in a file so a restart keeps it.
    signed_seen: Arc<AtomicBool>,
    marker: Option<String>,
}

/// One plain HTTP GET (the box speaks plain HTTP on the kiosk LAN): the body, whatever the status (errors carry JSON).
pub fn http_get(addr: &str, path: &str, timeout: Duration) -> Result<String, String> {
    let sock = addr.to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("no address")?;
    let mut s = TcpStream::connect_timeout(&sock, timeout).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(timeout)).ok();
    s.set_write_timeout(Some(timeout)).ok();
    s.set_nodelay(true).ok();
    let host = addr.rsplit_once(':').map(|(h, _)| h).unwrap_or(addr);
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

/// The box's address with a port: piso-setup writes a bare IP ("10.0.0.10"), which connect() cannot use as it is.
pub fn with_port(addr: &str) -> String {
    let a = addr.trim();
    if a.parse::<SocketAddr>().is_ok() {
        return a.to_string();
    }
    match a.parse::<IpAddr>() {
        Ok(IpAddr::V6(ip)) => format!("[{}]:80", ip),
        Ok(IpAddr::V4(ip)) => format!("{}:80", ip),
        Err(_) if a.rsplit_once(':').is_some_and(|(_, p)| p.parse::<u16>().is_ok()) => a.to_string(),
        Err(_) => format!("{}:80", a),
    }
}

impl BoxLink {
    pub fn new(addr: &str, key: &str) -> BoxLink {
        BoxLink { addr: with_port(addr), key: key.to_string(), signed_seen: Arc::new(AtomicBool::new(false)), marker: None }
    }

    /// Remember in `path` that the box signs its answers. From then on an unsigned answer is refused: a box that once signed
    /// and now does not is someone else answering in its place.
    pub fn remember_in(mut self, path: &str) -> BoxLink {
        if std::path::Path::new(path).exists() {
            self.signed_seen.store(true, Ordering::SeqCst);
        }
        self.marker = Some(path.to_string());
        self
    }

    /// Does this box sign its answers (so far)?
    pub fn signs_answers(&self) -> bool {
        self.signed_seen.load(Ordering::SeqCst)
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
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let body = http_get(
            &self.addr,
            &format!("/api/gateway/{}?session={}&nonce={}&sig={}&ts={}{}", action, sid, nonce, sig, now, extra),
            Duration::from_secs(4),
        )?;
        self.check_answer(action, sid, &nonce, &body)?;
        Ok(body)
    }

    /// A successful answer must carry the box's signature over its own numbers and this request's nonce ("rsig", the box
    /// firmware's GatewayAuth.h). Without it, anyone who could pose as the box on the network could report coins that were
    /// never inserted. A box that has never signed (older firmware) is still served, until its first signed answer: from then
    /// on an unsigned one is refused. Error answers carry no authority (they only stop a payment), so they are not checked.
    fn check_answer(&self, action: &str, sid: &str, nonce: &str, body: &str) -> Result<(), String> {
        if !Self::is_success(body) {
            return Ok(());
        }
        let got = jget(body, "rsig");
        if got.is_empty() {
            return if self.signed_seen.load(Ordering::SeqCst) { Err("UNSIGNED_ANSWER".to_string()) } else { Ok(()) };
        }
        let facts = if action == "ack" {
            jget(body, "acknowledged_pulses")
        } else {
            ["state", "armed_remaining", "pulses", "lifetime_pulses", "slot_free", "ready_in_ms", "minutes_per_coin"]
                .iter()
                .map(|k| jget(body, k))
                .collect::<Vec<_>>()
                .join(":")
        };
        let want = hmac_hex(self.key.as_bytes(), format!("gw1r:{}:{}:{}:{}", action, sid, nonce, facts).as_bytes());
        if !same_text(&got, &want) {
            return Err("BAD_ANSWER_SIGNATURE".to_string());
        }
        if !self.signed_seen.swap(true, Ordering::SeqCst) {
            if let Some(path) = &self.marker {
                let _ = std::fs::write(path, "the coin box signs its answers\n");
            }
        }
        Ok(())
    }

    pub fn is_success(body: &str) -> bool {
        jget(body, "success") == "true"
    }
}

/// Equal text, compared without stopping at the first difference.
fn same_text(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
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
    fn the_box_address_from_piso_setup_has_no_port() {
        // piso-setup writes GW_BOX='10.0.0.10': without a port every call to the box failed before it was sent
        assert_eq!(BoxLink::new("10.0.0.10", "k").addr, "10.0.0.10:80");
        assert!(with_port("10.0.0.10").to_socket_addrs().is_ok());
        assert_eq!(with_port(" 10.0.0.10 "), "10.0.0.10:80");
        assert_eq!(with_port("127.0.0.1:18283"), "127.0.0.1:18283");
        assert_eq!(with_port("box.lan"), "box.lan:80");
        assert_eq!(with_port("box.lan:8080"), "box.lan:8080");
        assert_eq!(with_port("fe80::1"), "[fe80::1]:80");
        assert_eq!(with_port("[fe80::1]:81"), "[fe80::1]:81");
    }

    const KEY: &str = "test-gateway-key-123456";
    const NONCE: &str = "0123456789abcdef0123456789abcdef";

    fn status_body(rsig: &str, pulses: u32) -> String {
        format!(
            "{{\"success\":true,\"session\":\"abc123\",\"state\":\"armed\",\"armed_remaining\":0,\"pulses\":{},\"minutes_per_coin\":6,\"ready_in_ms\":0,\"slot_free\":true,\"events\":true,\"lifetime_pulses\":1003{}}}",
            pulses, rsig
        )
    }

    #[test]
    fn the_boxs_answers_are_checked_against_its_signature() {
        // the same vectors as the firmware's gateway_auth_test.cpp
        let good = ",\"rsig\":\"d157e75cb7dede9c1d465137ab7bd9fad325d1df9001bab95ad4da0b0a484441\"";
        let b = BoxLink::new("10.0.0.10", KEY);
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body(good, 3)), Ok(()));
        assert!(b.signs_answers());
        // a changed number, another request's nonce, or another session: refused
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body(good, 30)), Err("BAD_ANSWER_SIGNATURE".into()));
        assert_eq!(
            b.check_answer("status", "abc123", "f123456789abcdef0123456789abcdef", &status_body(good, 3)),
            Err("BAD_ANSWER_SIGNATURE".into())
        );
        assert_eq!(b.check_answer("status", "abc124", NONCE, &status_body(good, 3)), Err("BAD_ANSWER_SIGNATURE".into()));
        assert_eq!(b.check_answer("release", "abc123", NONCE, &status_body(good, 3)), Err("BAD_ANSWER_SIGNATURE".into()));
        // the box signed once, so an unsigned answer is no longer accepted
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body("", 3)), Err("UNSIGNED_ANSWER".into()));
        // another key
        let other = BoxLink::new("10.0.0.10", "another-gateway-key-0123");
        assert_eq!(other.check_answer("status", "abc123", NONCE, &status_body(good, 3)), Err("BAD_ANSWER_SIGNATURE".into()));
    }

    #[test]
    fn an_older_box_that_never_signed_is_still_served_until_its_first_signed_answer() {
        let b = BoxLink::new("10.0.0.10", KEY);
        assert!(!b.signs_answers());
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body("", 3)), Ok(()));
        assert!(!b.signs_answers());
        // a forged signature is refused even before the box has ever signed
        let forged = ",\"rsig\":\"00000000000000000000000000000000000000000000000000000000000000ff\"";
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body(forged, 3)), Err("BAD_ANSWER_SIGNATURE".into()));
        // error answers carry no authority and are never checked
        assert_eq!(b.check_answer("arm", "abc123", NONCE, "{\"success\":false,\"error\":\"SLOT_BUSY\"}"), Ok(()));
        // the ack answer is signed over its own number
        let ack = "{\"success\":true,\"session\":\"abc123\",\"acknowledged_pulses\":3,\"rsig\":\"19a2eb098dccbd7a39adcb42e3d5cc9361d19e23769345d443dc16cde21fc692\"}";
        assert_eq!(b.check_answer("ack", "abc123", NONCE, ack), Ok(()));
        assert!(b.signs_answers());
        assert_eq!(
            b.check_answer("ack", "abc123", NONCE, &ack.replace("\"acknowledged_pulses\":3", "\"acknowledged_pulses\":4")),
            Err("BAD_ANSWER_SIGNATURE".into())
        );
    }

    #[test]
    fn that_the_box_signs_survives_a_restart() {
        let dir = std::env::temp_dir().join(format!("boxlink-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let marker = dir.join("box_signed").to_string_lossy().into_owned();
        let _ = std::fs::remove_file(&marker);
        let good = ",\"rsig\":\"d157e75cb7dede9c1d465137ab7bd9fad325d1df9001bab95ad4da0b0a484441\"";
        let b = BoxLink::new("10.0.0.10", KEY).remember_in(&marker);
        assert!(!b.signs_answers());
        assert_eq!(b.check_answer("status", "abc123", NONCE, &status_body(good, 3)), Ok(()));
        let again = BoxLink::new("10.0.0.10", KEY).remember_in(&marker);
        assert!(again.signs_answers());
        assert_eq!(again.check_answer("status", "abc123", NONCE, &status_body("", 3)), Err("UNSIGNED_ANSWER".into()));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn events_are_verified_and_parsed_from_the_right() {
        let key = "test-gateway-key-123456";
        let body = "gw1ev:abc123:def456:7:coin:12";
        let line = format!("{}:{}", body, hmac_hex(key.as_bytes(), body.as_bytes()));
        assert_eq!(
            parse_event(&line, key),
            Some(Event { sid: "abc123".into(), wid: "def456".into(), seq: 7, kind: "coin".into(), pulses: 12 })
        );
        assert_eq!(parse_event(&line, "other key 0123456789"), None);
        assert_eq!(parse_event("gw1ev:abc:def:1:coin:1:zz", key), None);
        assert_eq!(parse_event("junk", key), None);
        let colon = "gw1ev:a:b:c:def456:8:end:3";
        let l2 = format!("{}:{}", colon, hmac_hex(key.as_bytes(), colon.as_bytes()));
        assert_eq!(parse_event(&l2, key).unwrap().sid, "a:b:c");
    }
}
