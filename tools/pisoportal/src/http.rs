//! HTTP and WebSocket. The splash page is built once at start and served from memory; the page opens one WebSocket and
//! everything else (Insert Coin, coins, Done, the result) travels over it. A device is the MAC address the router itself
//! sees behind its connection (ARP), never what the page says.
use crate::core::{log, Core};
use crate::pricing::{fmt_min, tiers, Plan};
use crate::util::{b64_decode, b64_encode, jget, json_esc, query_get, sha256_hex, url_decode, valid_mac};
use std::collections::HashMap;
use std::fs;
use std::hash::Hash;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
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
        .replace("%CSS%", include_str!("base.css"))
        .replace("%TIERS_H%", &tier_rows(core, Plan::Hyper))
        .replace("%TIERS_E%", &tier_rows(core, Plan::Endurance))
        .replace("%E_DOWN%", &(cfg.endurance_down / 1000).to_string())
        .replace("%E_UP%", &(cfg.endurance_up / 1000).to_string())
        .replace("%FAIR_GB%", &(cfg.fair_kb / 1024 / 1024).to_string())
        .replace("%FIRST%", &cfg.first_wait.to_string())
}

/// The status page a connected guest sees (openNDS sends them here from its own status address): the same look
/// with a live countdown, fed by the same WebSocket.
pub fn build_status_page(_core: &Core) -> String {
    include_str!("status.html").replace("%CSS%", include_str!("base.css"))
}

// ---- requests -------------------------------------------------------------------------------------------------------------------
/// The most a request head may take, in bytes and in time: a slow or endless sender cannot hold a connection or the memory.
const MAX_HEAD: usize = 8 * 1024;
const HEAD_TIME: Duration = Duration::from_secs(10);
/// Headers added to every answer (the page is never framed, sniffed or given a referrer).
const SAFE_HEADERS: &str = "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n";

/// The TechNet PisoWifi logo (fixed branding, not a setting): served from memory like the pages.
const BRAND_LOGO: &[u8] = include_bytes!("brand.png");

struct Request {
    method: String,
    path: String,
    query: String,
    headers: Vec<(String, String)>,
}

impl Request {
    fn header(&self, n: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == n).map(|(_, v)| v.as_str())
    }
}

/// What the client sent: the bytes that came after the request head first (a WebSocket client may send its first frame
/// without waiting for the answer), then the socket.
struct Incoming {
    s: TcpStream,
    pre: Vec<u8>,
    at: usize,
}

impl Read for Incoming {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.at < self.pre.len() {
            let n = out.len().min(self.pre.len() - self.at);
            out[..n].copy_from_slice(&self.pre[self.at..self.at + n]);
            self.at += n;
            return Ok(n);
        }
        self.s.read(out)
    }
}

/// One request head, bounded in size and time. None for anything malformed, too big or too slow.
fn read_request(mut s: TcpStream, limit: Duration) -> Option<(Request, Incoming)> {
    let started = Instant::now();
    let mut buf: Vec<u8> = Vec::with_capacity(1024);
    let mut chunk = [0u8; 1024];
    let end = loop {
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break i;
        }
        if buf.len() > MAX_HEAD {
            return None;
        }
        let left = limit.checked_sub(started.elapsed()).filter(|d| !d.is_zero())?;
        s.set_read_timeout(Some(left)).ok()?;
        match s.read(&mut chunk) {
            Ok(0) => return None,
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    };
    let head = String::from_utf8_lossy(&buf[..end]).into_owned();
    let mut lines = head.split("\r\n");
    let mut first = lines.next()?.split(' ');
    let (method, target) = (first.next()?.to_string(), first.next()?);
    let headers =
        lines.filter_map(|l| l.split_once(':')).take(64).map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_string())).collect();
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let req = Request { method, path: path.to_string(), query: query.to_string(), headers };
    Some((req, Incoming { s, pre: buf[end + 4..].to_vec(), at: 0 }))
}

fn respond(stream: &mut TcpStream, status: &str, ctype: &str, body: &str, extra: &str) {
    respond_bytes(stream, status, ctype, body.as_bytes(), extra, true);
}

/// Like `respond`, for a body that is not text; `send_body` false answers a HEAD request (the length is still the real one).
fn respond_bytes(stream: &mut TcpStream, status: &str, ctype: &str, body: &[u8], extra: &str, send_body: bool) {
    let _ = write!(
        stream,
        "HTTP/1.1 {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n{}{}\r\n",
        status,
        ctype,
        body.len(),
        SAFE_HEADERS,
        extra
    );
    if send_body {
        let _ = stream.write_all(body);
    }
}

/// The MAC address the router sees behind an IP address.
pub fn arp_mac(arp_file: &str, ip: &str) -> Option<String> {
    let t = fs::read_to_string(arp_file).ok()?;
    t.lines().skip(1).find_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        (f.len() >= 4 && f[0] == ip && valid_mac(f[3]) && f[3] != "00:00:00:00:00:00").then(|| f[3].to_lowercase())
    })
}

/// Cross-site WebSocket guard: once online, a guest may open pages of any site, and such a page must not drive this
/// device's coin window. Browsers always send Origin with a WebSocket; it must name this very host. (No Origin is a
/// non-browser client; "null" comes from some built-in captive-portal browsers.)
fn same_origin(req: &Request) -> bool {
    let Some(origin) = req.header("origin") else { return true };
    if origin == "null" {
        return true;
    }
    let host = req.header("host").unwrap_or("");
    origin.split_once("://").is_some_and(|(_, h)| h.trim_end_matches('/').eq_ignore_ascii_case(host))
}

// ---- connection limits ------------------------------------------------------------------------------------------------------------
/// Counts what is in use, in total and per key (a client address, a device): one guest cannot take every connection.
struct Limits<K: Eq + Hash + Clone> {
    used: Mutex<(usize, HashMap<K, usize>)>,
    total: usize,
    each: usize,
}

/// One unit of a limit, given back when dropped.
struct Slot<K: Eq + Hash + Clone> {
    lim: Arc<Limits<K>>,
    key: K,
}

impl<K: Eq + Hash + Clone> Limits<K> {
    fn new(total: usize, each: usize) -> Arc<Limits<K>> {
        Arc::new(Limits { used: Mutex::new((0, HashMap::new())), total, each })
    }

    fn take(self: &Arc<Self>, key: K) -> Option<Slot<K>> {
        let mut g = self.used.lock().unwrap();
        let n = g.1.get(&key).copied().unwrap_or(0);
        if g.0 >= self.total || n >= self.each {
            return None;
        }
        g.0 += 1;
        g.1.insert(key.clone(), n + 1);
        Some(Slot { lim: Arc::clone(self), key })
    }
}

impl<K: Eq + Hash + Clone> Drop for Slot<K> {
    fn drop(&mut self) {
        let mut g = self.lim.used.lock().unwrap();
        g.0 -= 1;
        if let Some(n) = g.1.get_mut(&self.key) {
            *n -= 1;
            if *n == 0 {
                g.1.remove(&self.key);
            }
        }
    }
}

/// Connections per client address, and open coin pages per device.
const CONN_PER_IP: usize = 8;
const WS_PER_DEVICE: usize = 3;

// ---- WebSocket frames ---------------------------------------------------------------------------------------------------------------
enum Frame {
    Data(u8, Vec<u8>),
    /// nothing arrived within the read timeout
    Idle,
    Closed,
}

fn ws_read_frame(s: &mut Incoming) -> Frame {
    let mut h = [0u8; 2];
    match s.read_exact(&mut h) {
        Ok(()) => {}
        Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::TimedOut => return Frame::Idle,
        Err(_) => return Frame::Closed,
    }
    let opcode = h[0] & 0x0f;
    // RFC 6455 5.1: a client always masks; an unmasked frame ends the connection
    if h[1] & 0x80 == 0 {
        return Frame::Closed;
    }
    let mut rd = |buf: &mut [u8]| s.read_exact(buf).is_ok();
    let len: u64 = match h[1] & 0x7f {
        126 => {
            let mut b = [0u8; 2];
            if !rd(&mut b) {
                return Frame::Closed;
            }
            u16::from_be_bytes(b) as u64
        }
        127 => {
            let mut b = [0u8; 8];
            if !rd(&mut b) {
                return Frame::Closed;
            }
            u64::from_be_bytes(b)
        }
        n => n as u64,
    };
    // checked before any conversion: on the router (32-bit) a huge length must not wrap into a small one
    if len > 8192 {
        return Frame::Closed;
    }
    let mut mask = [0u8; 4];
    if !rd(&mut mask) {
        return Frame::Closed;
    }
    let mut data = vec![0u8; len as usize];
    if !rd(&mut data) {
        return Frame::Closed;
    }
    for (i, b) in data.iter_mut().enumerate() {
        *b ^= mask[i % 4];
    }
    Frame::Data(opcode, data)
}

fn ws_frame(opcode: u8, data: &[u8]) -> Vec<u8> {
    let mut f = Vec::with_capacity(data.len() + 10);
    f.push(0x80 | opcode);
    match data.len() {
        n if n < 126 => f.push(n as u8),
        n if n <= 0xffff => {
            f.push(126);
            f.extend_from_slice(&(n as u16).to_be_bytes());
        }
        n => {
            f.push(127);
            f.extend_from_slice(&(n as u64).to_be_bytes());
        }
    }
    f.extend_from_slice(data);
    f
}

fn ws_send(w: &Mutex<TcpStream>, opcode: u8, data: &[u8]) -> bool {
    w.lock().map(|mut s| s.write_all(&ws_frame(opcode, data)).is_ok()).unwrap_or(false)
}

/// How often the server pings a quiet page, and how long a page may stay silent (a browser answers every ping by itself;
/// silence means the phone left).
const PING_EVERY: Duration = Duration::from_secs(20);
const SILENT_MAX: Duration = Duration::from_secs(65);
/// A page reconnects by itself; no connection is kept forever.
const SESSION_MAX: Duration = Duration::from_secs(900);

fn ws_session(core: Arc<Core>, mut conn: Incoming, key: &str, mac: String) {
    let _ = write!(
        conn.s,
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n",
        ws_accept(key)
    );
    let _ = conn.s.set_read_timeout(Some(SILENT_MAX));
    let _ = conn.s.set_write_timeout(Some(Duration::from_secs(10)));
    let Ok(wclone) = conn.s.try_clone() else { return };
    let writer = Arc::new(Mutex::new(wclone));
    let closed = Arc::new(AtomicBool::new(false));
    let started = Instant::now();

    // the writer: pushes this device's view whenever it changed, and pings a quiet page
    let (c2, w2, cl2, m2) = (Arc::clone(&core), Arc::clone(&writer), Arc::clone(&closed), mac.clone());
    // (from the version now: the first view answers the page's hello, which may first clear an old result)
    let start_ver = core.version();
    let spawned = std::thread::Builder::new().stack_size(96 * 1024).spawn(move || {
        let mut last_ver = start_ver;
        let mut last_sent = String::new();
        let mut last_ping = Instant::now();
        while !cl2.load(Ordering::Relaxed) {
            last_ver = c2.wait_version(last_ver, PING_EVERY);
            if cl2.load(Ordering::Relaxed) {
                break;
            }
            let json = c2.view_for(&m2).to_json();
            if json != last_sent {
                if !ws_send(&w2, 1, json.as_bytes()) {
                    break;
                }
                last_sent = json;
            }
            if last_ping.elapsed() >= PING_EVERY {
                if !ws_send(&w2, 9, b"k") {
                    break;
                }
                last_ping = Instant::now();
            }
        }
        cl2.store(true, Ordering::Relaxed);
    });
    if spawned.is_err() {
        let _ = conn.s.shutdown(Shutdown::Both);
        return;
    }

    while !closed.load(Ordering::Relaxed) && started.elapsed() < SESSION_MAX {
        let (op, data) = match ws_read_frame(&mut conn) {
            Frame::Data(op, data) => (op, data),
            Frame::Idle | Frame::Closed => break,
        };
        match op {
            1 => {
                let text = String::from_utf8_lossy(&data).into_owned();
                match jget(&text, "t").as_str() {
                    "hello" => {
                        let cont = b64_decode(&url_decode(&jget(&text, "fas")))
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
    let _ = conn.s.shutdown(Shutdown::Both);
}

struct Portal {
    core: Arc<Core>,
    /// the page and its ETag
    page: (String, String),
    status: String,
    pages: Arc<Limits<String>>,
}

fn handle(p: Arc<Portal>, stream: TcpStream, peer: SocketAddr) {
    let Some((req, mut conn)) = read_request(stream, HEAD_TIME) else { return };
    let head_only = req.method == "HEAD";
    if req.method != "GET" && !head_only {
        return respond(&mut conn.s, "405 Method Not Allowed", "text/plain", "", "Allow: GET, HEAD\r\n");
    }
    match req.path.as_str() {
        "/ping" => respond(&mut conn.s, "200 OK", "text/plain", if head_only { "" } else { "ok" }, "Cache-Control: no-store\r\n"),
        "/ws" => {
            let Some(key) = req.header("sec-websocket-key").map(|k| k.to_string()) else {
                return respond(&mut conn.s, "400 Bad Request", "text/plain", "websocket only", "");
            };
            if !same_origin(&req) {
                log!("websocket refused: origin {} is not this portal", req.header("origin").unwrap_or(""));
                return respond(&mut conn.s, "403 Forbidden", "text/plain", "wrong origin", "");
            }
            let Some(mac) = arp_mac(&p.core.cfg.arp_file, &peer.ip().to_string()) else {
                return respond(&mut conn.s, "403 Forbidden", "text/plain", "unknown device", "");
            };
            let Some(_page) = p.pages.take(mac.clone()) else {
                return respond(&mut conn.s, "503 Service Unavailable", "text/plain", "busy", "Retry-After: 5\r\n");
            };
            ws_session(Arc::clone(&p.core), conn, &key, mac);
        }
        "/brand.png" => {
            respond_bytes(&mut conn.s, "200 OK", "image/png", BRAND_LOGO, "Cache-Control: public, max-age=86400\r\n", !head_only)
        }
        "/status" => {
            let body = if head_only { "" } else { p.status.as_str() };
            respond(&mut conn.s, "200 OK", "text/html; charset=utf-8", body, "Cache-Control: no-store\r\n");
        }
        _ => {
            let etag = format!("\"{}\"", p.page.1);
            let extra = format!("ETag: {}\r\nCache-Control: no-cache\r\n", etag);
            if req.header("if-none-match") == Some(etag.as_str()) {
                return respond(&mut conn.s, "304 Not Modified", "text/html; charset=utf-8", "", &extra);
            }
            if head_only {
                let _ = write!(
                    conn.s,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n{}{}\r\n",
                    p.page.0.len(),
                    SAFE_HEADERS,
                    extra
                );
                return;
            }
            respond(&mut conn.s, "200 OK", "text/html; charset=utf-8", &p.page.0, &extra);
        }
    }
}

/// Listen, waiting for the address if it is not there yet (at boot the guest network may come up after this program).
pub fn bind_retry(addr: &str, what: &str) -> TcpListener {
    let mut warned = false;
    loop {
        match TcpListener::bind(addr) {
            Ok(l) => {
                if warned {
                    log!("{}: listening on {} now", what, addr);
                }
                return l;
            }
            Err(e) => {
                if !warned {
                    log!("{}: cannot listen on {} yet ({}); trying again every 2 s", what, addr, e);
                    warned = true;
                }
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    }
}

pub fn serve(core: Arc<Core>) {
    let page = build_page(&core);
    let tag = sha256_hex(page.as_bytes())[..16].to_string();
    let max = core.cfg.max_clients.max(1);
    let status = build_status_page(&core);
    let p = Arc::new(Portal { core, page: (page, tag), status, pages: Limits::new(max, WS_PER_DEVICE) });
    let conns: Arc<Limits<IpAddr>> = Limits::new(max * 4, CONN_PER_IP);
    let addr = format!("{}:{}", p.core.cfg.bind, p.core.cfg.port);
    let listener = bind_retry(&addr, "portal");
    log!("portal listening on {} ({} bytes of page)", addr, p.page.0.len());
    for stream in listener.incoming().flatten() {
        let Ok(peer) = stream.peer_addr() else { continue };
        // a client over its share (or everybody at once) is turned away before any thread is started
        let Some(slot) = conns.take(peer.ip()) else { continue };
        let _ = stream.set_nodelay(true);
        let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
        let p = Arc::clone(&p);
        let _ = std::thread::Builder::new().stack_size(96 * 1024).spawn(move || {
            let _slot = slot;
            handle(p, stream, peer)
        });
    }
}

// ---- the local admin interface (piso-setup test-coin and friends); localhost only -----------------------------------------------
pub fn serve_admin(core: Arc<Core>) {
    let addr = format!("127.0.0.1:{}", core.cfg.admin_port);
    let listener = bind_retry(&addr, "admin interface");
    for stream in listener.incoming().flatten() {
        let Some((req, mut conn)) = read_request(stream, Duration::from_secs(5)) else { continue };
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
            "/admin/info" => format!(
                "{{\"ok\":true,\"version\":\"{}\",\"box\":\"{}\",\"port\":{}}}",
                env!("CARGO_PKG_VERSION"),
                json_esc(&core.cfg.gw_box),
                core.cfg.port
            ),
            _ => "{\"error\":\"NOT_FOUND\"}".into(),
        };
        respond(&mut conn.s, "200 OK", "application/json", &body, "Cache-Control: no-store\r\n");
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

    fn pair() -> (TcpStream, TcpStream) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let a = TcpStream::connect(l.local_addr().unwrap()).unwrap();
        let (b, _) = l.accept().unwrap();
        (a, b)
    }

    fn incoming(pre: &[u8], wire: &[u8]) -> (Incoming, TcpStream) {
        let (mut client, server) = pair();
        client.write_all(wire).unwrap();
        server.set_read_timeout(Some(Duration::from_millis(300))).unwrap();
        (Incoming { s: server, pre: pre.to_vec(), at: 0 }, client)
    }

    fn masked(op: u8, len_bytes: &[u8], payload: &[u8]) -> Vec<u8> {
        let mask = [1u8, 2, 3, 4];
        let mut f = vec![0x80 | op];
        f.extend_from_slice(len_bytes);
        f.extend_from_slice(&mask);
        f.extend(payload.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
        f
    }

    #[test]
    fn websocket_frames() {
        let (mut c, _k) = incoming(&[], &masked(1, &[0x80 | 2], b"hi"));
        assert!(matches!(ws_read_frame(&mut c), Frame::Data(1, d) if d == b"hi"));
        // nothing more within the read timeout
        assert!(matches!(ws_read_frame(&mut c), Frame::Idle));
        // a frame that came with the request head is read first
        let (mut c, _k) = incoming(&masked(1, &[0x80 | 1], b"a"), &masked(1, &[0x80 | 1], b"b"));
        assert!(matches!(ws_read_frame(&mut c), Frame::Data(1, d) if d == b"a"));
        assert!(matches!(ws_read_frame(&mut c), Frame::Data(1, d) if d == b"b"));
        // RFC 6455: a client frame without a mask ends the connection
        let (mut c, _k) = incoming(&[], &[0x81, 0x02, b'h', b'i']);
        assert!(matches!(ws_read_frame(&mut c), Frame::Closed));
        // a 64-bit length that would wrap to a small number on a 32-bit router is refused, not truncated
        let mut huge = vec![0x80 | 127];
        huge.extend_from_slice(&((1u64 << 32) + 2).to_be_bytes());
        let (mut c, _k) = incoming(&[], &masked(1, &huge, b"hi"));
        assert!(matches!(ws_read_frame(&mut c), Frame::Closed));
        // 8193 bytes: too big
        let (mut c, _k) = incoming(&[], &masked(1, &[0x80 | 126, 0x20, 0x01], &[]));
        assert!(matches!(ws_read_frame(&mut c), Frame::Closed));
        // the server's own frames: short, 16-bit and 64-bit lengths
        assert_eq!(ws_frame(1, b"ok"), vec![0x81, 2, b'o', b'k']);
        assert_eq!(&ws_frame(1, &[0; 300])[..4], &[0x81, 126, 1, 44]);
        assert_eq!(&ws_frame(1, &vec![0; 70000])[..10], &[0x81, 127, 0, 0, 0, 0, 0, 1, 0x11, 0x70]);
    }

    #[test]
    fn request_heads_are_bounded() {
        let (mut client, server) = pair();
        client.write_all(b"GET /ws?x=1 HTTP/1.1\r\nHost: 10.0.0.1:2080\r\nOrigin: http://10.0.0.1:2080\r\n\r\nEXTRA").unwrap();
        let (req, conn) = read_request(server, Duration::from_secs(2)).unwrap();
        assert_eq!((req.method.as_str(), req.path.as_str(), req.query.as_str()), ("GET", "/ws", "x=1"));
        assert_eq!(req.header("host"), Some("10.0.0.1:2080"));
        assert_eq!(conn.pre, b"EXTRA"); // kept for the WebSocket
        assert!(same_origin(&req));
        // too big
        let (mut client, server) = pair();
        client.write_all(format!("GET / HTTP/1.1\r\nX: {}\r\n", "a".repeat(9000)).as_bytes()).unwrap();
        assert!(read_request(server, Duration::from_secs(2)).is_none());
        // too slow: the head never ends
        let (mut client, server) = pair();
        client.write_all(b"GET / HTTP/1.1\r\n").unwrap();
        let t = Instant::now();
        assert!(read_request(server, Duration::from_millis(300)).is_none());
        assert!(t.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn websocket_origin_must_be_this_portal() {
        let req = |origin: Option<&str>| Request {
            method: "GET".into(),
            path: "/ws".into(),
            query: String::new(),
            headers: [Some(("host".to_string(), "192.168.30.1:2080".to_string())), origin.map(|o| ("origin".to_string(), o.to_string()))]
                .into_iter()
                .flatten()
                .collect(),
        };
        assert!(same_origin(&req(None)));
        assert!(same_origin(&req(Some("null"))));
        assert!(same_origin(&req(Some("http://192.168.30.1:2080"))));
        assert!(!same_origin(&req(Some("http://evil.example"))));
        assert!(!same_origin(&req(Some("http://192.168.30.1:2081"))));
    }

    #[test]
    fn limits_count_per_key_and_in_total() {
        let l: Arc<Limits<u8>> = Limits::new(3, 2);
        let a1 = l.take(1).unwrap();
        let _a2 = l.take(1).unwrap();
        assert!(l.take(1).is_none()); // 2 per key
        let _b1 = l.take(2).unwrap();
        assert!(l.take(3).is_none()); // 3 in total
        drop(a1);
        let _c1 = l.take(3).unwrap(); // a slot given back can be taken again
        let used = l.used.lock().unwrap().0;
        assert_eq!(used, 3);
    }
}
