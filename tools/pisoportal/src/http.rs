//! HTTP and WebSocket. The splash page is built once at start and served from memory; the page opens one WebSocket and
//! everything else (Insert Coin, coins, Done, the result) travels over it. A device is the MAC address the router itself
//! sees behind its connection (ARP), never what the page says.
use crate::core::{log, Core};
use crate::pricing::{fmt_min, tiers, Plan};
use crate::util::{b64_decode, b64_encode, jget, json_esc, query_get, sha256_hex, valid_mac};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

// ---- SHA-1 (only for the WebSocket handshake) -----------------------------------------------------------------------------
fn sha1(data: &[u8]) -> [u8; 20] {
    let mut h: [u32; 5] = [0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0];
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((data.len() as u64) * 8).to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 80];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[i * 4], chunk[i * 4 + 1], chunk[i * 4 + 2], chunk[i * 4 + 3]]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let [mut a, mut b, mut c, mut d, mut e] = h;
        for (i, wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | (!b & d), 0x5A827999),
                20..=39 => (b ^ c ^ d, 0x6ED9EBA1),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1BBCDC),
                _ => (b ^ c ^ d, 0xCA62C1D6),
            };
            let t = a.rotate_left(5).wrapping_add(f).wrapping_add(e).wrapping_add(k).wrapping_add(*wi);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = t;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }
    let mut out = [0u8; 20];
    for (i, v) in h.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&v.to_be_bytes());
    }
    out
}

pub fn ws_accept(key: &str) -> String {
    b64_encode(&sha1(format!("{}258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key.trim()).as_bytes()))
}

// ---- the page, built once -------------------------------------------------------------------------------------------------------
fn tier_rows(core: &Core, plan: Plan) -> String {
    let mut rows = String::new();
    let mut t: Vec<(u32, u32)> = tiers(&core.cfg, plan).to_vec();
    t.sort();
    // the pro-rata 1-peso tier of HyperSpeed is not a price list line
    if plan == Plan::Hyper {
        t.retain(|(p, _)| *p > 1);
    }
    for (p, m) in t {
        rows.push_str(&format!("<div><span>&#8369;{}</span><span>{}</span></div>", p, fmt_min(m)));
    }
    rows
}

pub fn build_page(core: &Core) -> String {
    let cfg = &core.cfg;
    include_str!("page.html")
        .replace("%NAME%", &cfg.gateway_name.replace('&', "&amp;").replace('<', "&lt;").replace('"', "&quot;"))
        .replace("%TIERS_H%", &tier_rows(core, Plan::Hyper))
        .replace("%TIERS_E%", &tier_rows(core, Plan::Endurance))
        .replace("%E_DOWN%", &(cfg.endurance_down / 1000).to_string())
        .replace("%E_UP%", &(cfg.endurance_up / 1000).to_string())
        .replace("%FAIR_GB%", &(cfg.fair_kb / 1024 / 1024).to_string())
        .replace("%FIRST%", &cfg.first_wait.to_string())
        .replace("%IDLE%", &cfg.idle_wait.to_string())
}

// ---- requests -------------------------------------------------------------------------------------------------------------------
struct Request {
    path: String,
    query: String,
    headers: Vec<(String, String)>,
}

impl Request {
    fn header(&self, n: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == n).map(|(_, v)| v.as_str())
    }
}

fn read_request(stream: &TcpStream) -> Option<Request> {
    let mut r = BufReader::new(stream.try_clone().ok()?);
    let mut line = String::new();
    r.read_line(&mut line).ok()?;
    let mut parts = line.split_whitespace();
    if parts.next()? != "GET" {
        return None;
    }
    let target = parts.next()?.to_string();
    let mut headers = Vec::new();
    loop {
        let mut h = String::new();
        if r.read_line(&mut h).ok()? == 0 || h.trim().is_empty() {
            break;
        }
        if let Some((k, v)) = h.trim().split_once(':') {
            headers.push((k.trim().to_lowercase(), v.trim().to_string()));
        }
        if headers.len() > 64 {
            return None;
        }
    }
    let (path, query) = target.split_once('?').map(|(p, q)| (p.to_string(), q.to_string())).unwrap_or((target.clone(), String::new()));
    Some(Request { path, query, headers })
}

fn respond(stream: &mut TcpStream, status: &str, ctype: &str, body: &str, extra: &str) {
    let _ = write!(
        stream,
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n{}\r\n{}",
        status,
        ctype,
        body.len(),
        extra,
        body
    );
}

/// The MAC address the router sees behind an IP address.
pub fn arp_mac(arp_file: &str, ip: &str) -> Option<String> {
    let t = fs::read_to_string(arp_file).ok()?;
    t.lines().skip(1).find_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        (f.len() >= 4 && f[0] == ip && valid_mac(f[3]) && f[3] != "00:00:00:00:00:00").then(|| f[3].to_lowercase())
    })
}

// ---- WebSocket frames ---------------------------------------------------------------------------------------------------------------
enum Frame {
    Data(u8, Vec<u8>),
    /// nothing arrived within the read timeout (a quiet page)
    Idle,
    Closed,
}

fn ws_read_frame(s: &mut TcpStream) -> Frame {
    let mut h = [0u8; 2];
    match s.read_exact(&mut h) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => return Frame::Idle,
        Err(_) => return Frame::Closed,
    }
    let opcode = h[0] & 0x0f;
    let masked = h[1] & 0x80 != 0;
    let mut len = (h[1] & 0x7f) as usize;
    let mut rd = |buf: &mut [u8]| s.read_exact(buf).is_ok();
    if len == 126 {
        let mut b = [0u8; 2];
        if !rd(&mut b) {
            return Frame::Closed;
        }
        len = u16::from_be_bytes(b) as usize;
    } else if len == 127 {
        let mut b = [0u8; 8];
        if !rd(&mut b) {
            return Frame::Closed;
        }
        len = u64::from_be_bytes(b) as usize;
    }
    if len > 8192 {
        return Frame::Closed;
    }
    let mut mask = [0u8; 4];
    if masked && !rd(&mut mask) {
        return Frame::Closed;
    }
    let mut data = vec![0u8; len];
    if !rd(&mut data) {
        return Frame::Closed;
    }
    if masked {
        for (i, b) in data.iter_mut().enumerate() {
            *b ^= mask[i % 4];
        }
    }
    Frame::Data(opcode, data)
}

fn ws_send(w: &Mutex<TcpStream>, opcode: u8, data: &[u8]) -> bool {
    let mut f = vec![0x80 | opcode];
    if data.len() < 126 {
        f.push(data.len() as u8);
    } else {
        f.push(126);
        f.extend_from_slice(&(data.len() as u16).to_be_bytes());
    }
    f.extend_from_slice(data);
    w.lock().map(|mut s| s.write_all(&f).is_ok()).unwrap_or(false)
}

static WS_COUNT: AtomicUsize = AtomicUsize::new(0);

fn ws_session(core: Arc<Core>, mut stream: TcpStream, key: &str, mac: String) {
    let _ = write!(
        stream,
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n",
        ws_accept(key)
    );
    let _ = stream.set_read_timeout(Some(Duration::from_secs(60)));
    let Ok(wclone) = stream.try_clone() else { return };
    let writer = Arc::new(Mutex::new(wclone));
    let closed = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let started = Instant::now();

    // the writer: pushes this device's view whenever something changed
    let (c2, w2, cl2, m2) = (Arc::clone(&core), Arc::clone(&writer), Arc::clone(&closed), mac.clone());
    let wt = std::thread::Builder::new().stack_size(96 * 1024).spawn(move || {
        let mut last_ver = 0;
        let mut last_sent = String::new();
        while !cl2.load(Ordering::Relaxed) {
            let v = c2.wait_version(last_ver, Duration::from_secs(20));
            last_ver = v;
            if cl2.load(Ordering::Relaxed) {
                break;
            }
            let json = c2.view_for(&m2).to_json();
            if json != last_sent {
                if !ws_send(&w2, 1, json.as_bytes()) {
                    break;
                }
                last_sent = json;
            } else if !ws_send(&w2, 9, b"k") {
                break; // an idle ping: keeps the connection (and a dead phone's absence) visible
            }
        }
    });

    while !closed.load(Ordering::Relaxed) && started.elapsed() < Duration::from_secs(900) {
        let (op, data) = match ws_read_frame(&mut stream) {
            Frame::Data(op, data) => (op, data),
            Frame::Idle => continue,
            Frame::Closed => break,
        };
        match op {
            1 => {
                let text = String::from_utf8_lossy(&data).into_owned();
                match jget(&text, "t").as_str() {
                    "hello" => {
                        let cont = b64_decode(&crate::util::url_decode(&jget(&text, "fas")))
                            .map(|b| String::from_utf8_lossy(&b).into_owned())
                            .and_then(|s| s.split(", ").find_map(|p| p.strip_prefix("originurl=").map(|v| v.to_string())))
                            .filter(|u| u.starts_with("http://") || u.starts_with("https://"))
                            .unwrap_or_default();
                        ws_send(&writer, 1, format!("{{\"t\":\"info\",\"cont\":\"{}\"}}", json_esc(&cont)).as_bytes());
                        if jget(&text, "reset") == "true" {
                            core.clear_result(&mac);
                        }
                        core.reconnect(&mac);
                        ws_send(&writer, 1, core.view_for(&mac).to_json().as_bytes());
                    }
                    "start" => {
                        if let Some(plan) = Plan::parse(&jget(&text, "plan")) {
                            let v = core.start(&mac, plan, jget(&text, "forfeit") == "true");
                            ws_send(&writer, 1, v.to_json().as_bytes());
                        }
                    }
                    "done" => core.finish(&mac),
                    "ping" => {
                        ws_send(&writer, 1, b"{\"t\":\"pong\"}");
                    }
                    _ => {}
                }
            }
            9 => {
                ws_send(&writer, 10, &data);
            }
            8 => {
                ws_send(&writer, 8, &[]);
                break;
            }
            _ => {}
        }
    }
    closed.store(true, Ordering::Relaxed);
    let _ = stream.shutdown(Shutdown::Both);
    drop(wt);
}

fn handle(core: Arc<Core>, page: Arc<(String, String)>, mut stream: TcpStream, peer: SocketAddr) {
    let Some(req) = read_request(&stream) else { return };
    let ip = peer.ip().to_string();
    match req.path.as_str() {
        "/ping" => respond(&mut stream, "200 OK", "text/plain", "ok", ""),
        "/ws" => {
            let Some(key) = req.header("sec-websocket-key").map(|k| k.to_string()) else {
                return respond(&mut stream, "400 Bad Request", "text/plain", "websocket only", "");
            };
            let Some(mac) = arp_mac(&core.cfg.arp_file, &ip) else {
                return respond(&mut stream, "403 Forbidden", "text/plain", "unknown device", "");
            };
            if WS_COUNT.fetch_add(1, Ordering::SeqCst) >= core.cfg.max_clients {
                WS_COUNT.fetch_sub(1, Ordering::SeqCst);
                return respond(&mut stream, "503 Service Unavailable", "text/plain", "busy", "Retry-After: 5\r\n");
            }
            ws_session(core, stream, &key, mac);
            WS_COUNT.fetch_sub(1, Ordering::SeqCst);
        }
        _ => {
            let etag = format!("\"{}\"", page.1);
            if req.header("if-none-match") == Some(etag.as_str()) {
                return respond(
                    &mut stream,
                    "304 Not Modified",
                    "text/html; charset=utf-8",
                    "",
                    &format!("ETag: {}\r\nCache-Control: no-cache\r\n", etag),
                );
            }
            respond(&mut stream, "200 OK", "text/html; charset=utf-8", &page.0, &format!("ETag: {}\r\nCache-Control: no-cache\r\n", etag));
        }
    }
}

pub fn serve(core: Arc<Core>) {
    let page = Arc::new({
        let p = build_page(&core);
        let tag = sha256_hex(p.as_bytes())[..16].to_string();
        (p, tag)
    });
    let addr = format!("{}:{}", core.cfg.bind, core.cfg.port);
    let listener = TcpListener::bind(&addr).unwrap_or_else(|e| {
        eprintln!("cannot listen on {}: {}", addr, e);
        std::process::exit(1)
    });
    log!("portal listening on {} ({} bytes of page)", addr, page.0.len());
    for stream in listener.incoming().flatten() {
        let Ok(peer) = stream.peer_addr() else { continue };
        let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
        let _ = stream.set_nodelay(true);
        let (c, p) = (Arc::clone(&core), Arc::clone(&page));
        std::thread::Builder::new().stack_size(96 * 1024).spawn(move || handle(c, p, stream, peer)).ok();
    }
}

// ---- the local admin interface (piso-setup test-coin and friends); localhost only -----------------------------------------------
pub fn serve_admin(core: Arc<Core>) {
    let addr = format!("127.0.0.1:{}", core.cfg.admin_port);
    let listener = match TcpListener::bind(&addr) {
        Ok(l) => l,
        Err(e) => {
            log!("admin interface: cannot listen on {}: {}", addr, e);
            return;
        }
    };
    for mut stream in listener.incoming().flatten() {
        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
        let Some(req) = read_request(&stream) else { continue };
        let mac = query_get(&req.query, "mac").unwrap_or_default().to_lowercase();
        let ok_mac = valid_mac(&mac);
        let body = match req.path.as_str() {
            "/admin/start" if ok_mac => match Plan::parse(&query_get(&req.query, "plan").unwrap_or_default()) {
                Some(p) => core.start(&mac, p, query_get(&req.query, "forfeit").as_deref() == Some("1")).to_json(),
                None => "{\"error\":\"INVALID_PLAN\"}".into(),
            },
            "/admin/status" if ok_mac => core.view_for(&mac).to_json(),
            "/admin/finish" if ok_mac => {
                core.finish(&mac);
                core.view_for(&mac).to_json()
            }
            "/admin/info" => format!("{{\"ok\":true,\"box\":\"{}\",\"port\":{}}}", json_esc(&core.cfg.gw_box), core.cfg.port),
            _ => "{\"error\":\"NOT_FOUND\"}".into(),
        };
        respond(&mut stream, "200 OK", "application/json", &body, "");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn websocket_accept_key_and_sha1() {
        assert_eq!(ws_accept("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
        assert_eq!(crate::util::hex(&sha1(b"abc")), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }
}
