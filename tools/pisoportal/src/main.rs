//! pisoportal, step 1: a resident web server for openNDS FAS mode. It only proves the pieces the real portal needs on a
//! router: the redirect from openNDS (fas_secure_enabled 1) arrives, the page is served from memory in microseconds,
//! a WebSocket connects and answers, and a client can be granted with `ndsctl auth`.
//!
//!   pisoportal [--port 2080] [--bind 0.0.0.0] [--selftest]
//!
//! No dependencies: the standard library only (SHA-1 and base64 are the few lines the WebSocket handshake needs).
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::process::Command;
use std::thread;
use std::time::{Duration, Instant};

// ---- SHA-1 and base64 (WebSocket handshake) -------------------------------------------------------------------------------
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

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn b64_encode(data: &[u8]) -> String {
    let mut out = String::new();
    for c in data.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        out.push(B64[(n >> 18) as usize & 63] as char);
        out.push(B64[(n >> 12) as usize & 63] as char);
        out.push(if c.len() > 1 { B64[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if c.len() > 2 { B64[n as usize & 63] as char } else { '=' });
    }
    out
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let (mut acc, mut bits) = (0u32, 0);
    for ch in s.bytes() {
        if ch == b'=' || ch == b'\n' || ch == b'\r' {
            continue;
        }
        let v = B64.iter().position(|&x| x == ch)? as u32;
        acc = acc << 6 | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Some(out)
}

fn ws_accept(key: &str) -> String {
    b64_encode(&sha1(format!("{}258EAFA5-E914-47DA-95CA-C5AB0DC85B11", key.trim()).as_bytes()))
}

// ---- small helpers ----------------------------------------------------------------------------------------------------------
fn url_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                    out.push(v);
                    i += 3;
                    continue;
                }
                out.push(b'%');
                i += 1;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn query_get(query: &str, key: &str) -> Option<String> {
    query.split('&').find_map(|kv| {
        let (k, v) = kv.split_once('=')?;
        if k == key {
            Some(url_decode(v))
        } else {
            None
        }
    })
}

/// The openNDS FAS level 1 query: fas=<base64 of "clientip=.., clientmac=.., gatewayname=.., hid=..., ...">
fn fas_fields(query: &str) -> Vec<(String, String)> {
    let Some(raw) = query_get(query, "fas") else { return vec![] };
    let Some(bytes) = b64_decode(&raw) else { return vec![] };
    String::from_utf8_lossy(&bytes)
        .split(", ")
        .filter_map(|p| p.split_once('=').map(|(k, v)| (k.to_string(), v.to_string())))
        .collect()
}

fn valid_mac(m: &str) -> bool {
    m.len() == 17 && m.split(':').count() == 6 && m.split(':').all(|p| p.len() == 2 && p.bytes().all(|c| c.is_ascii_hexdigit()))
}

/// The MAC address the router itself sees behind an IP address (never trust the page's word for it).
fn arp_mac(ip: &str) -> Option<String> {
    let t = fs::read_to_string(std::env::var("ARP_FILE").unwrap_or_else(|_| "/proc/net/arp".to_string())).ok()?;
    t.lines().skip(1).find_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        if f.len() >= 4 && f[0] == ip && f[3] != "00:00:00:00:00:00" {
            Some(f[3].to_lowercase())
        } else {
            None
        }
    })
}

fn rss_kb() -> u64 {
    fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| s.lines().find(|l| l.starts_with("VmRSS:")).and_then(|l| l.split_whitespace().nth(1).and_then(|v| v.parse().ok())))
        .unwrap_or(0)
}

fn html_esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

// ---- the page (built once, kept in memory) ---------------------------------------------------------------------------------
fn page(fields: &[(String, String)], peer_ip: &str, peer_mac: &str, took_us: u128) -> String {
    let mut rows = String::new();
    for (k, v) in fields {
        let shown = if k == "hid" || k == "originurl" { "(hidden)".to_string() } else { v.clone() };
        rows.push_str(&format!("<tr><td>{}</td><td>{}</td></tr>", html_esc(k), html_esc(&shown)));
    }
    format!(
        r#"<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>pisoportal step 1</title>
<style>body{{font:16px system-ui;margin:16px;background:#0f1715;color:#e8f0ec}}td{{padding:2px 8px;border-bottom:1px solid #243630}}button{{font:inherit;padding:12px;margin:6px 0;width:100%}}pre{{white-space:pre-wrap}}</style></head><body>
<h2>pisoportal &middot; step 1</h2>
<p>This page came from the resident Rust program (served in {took_us} &micro;s, program memory {rss} kB).</p>
<table><tr><td>seen as</td><td>{ip} / {mac}</td></tr>{rows}</table>
<button id="ws">Test WebSocket (5 round trips)</button>
<button id="g">Grant this phone 5 minutes (test)</button>
<pre id="out"></pre>
<script>
var out=document.getElementById("out");function log(t){{out.textContent+=t+"\n"}}
document.getElementById("ws").onclick=function(){{
 var t0=performance.now(),s=new WebSocket("ws://"+location.host+"/ws"),n=0,sent=0,rt=[];
 s.onopen=function(){{log("websocket open after "+Math.round(performance.now()-t0)+" ms");sent=performance.now();s.send("ping")}};
 s.onmessage=function(m){{rt.push(Math.round((performance.now()-sent)*10)/10);n++;if(n<5){{sent=performance.now();s.send("ping")}}else{{log("round trips (ms): "+rt.join(", "));s.close()}}}};
 s.onerror=function(){{log("websocket FAILED")}}}};
document.getElementById("g").onclick=function(){{
 var t0=performance.now();fetch("/grant").then(function(r){{return r.text()}}).then(function(t){{log("grant: "+t+" ("+Math.round(performance.now()-t0)+" ms in the browser)")}}).catch(function(e){{log("grant failed: "+e)}})}};
</script></body></html>"#,
        took_us = took_us,
        rss = rss_kb(),
        ip = html_esc(peer_ip),
        mac = html_esc(peer_mac),
        rows = rows
    )
}

// ---- HTTP -------------------------------------------------------------------------------------------------------------------
struct Request {
    path: String,
    query: String,
    headers: Vec<(String, String)>,
}

fn read_request(stream: &TcpStream) -> Option<Request> {
    let mut r = BufReader::new(stream.try_clone().ok()?);
    let mut line = String::new();
    r.read_line(&mut line).ok()?;
    let target = line.split_whitespace().nth(1)?.to_string();
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
    let (path, query) = target.split_once('?').map(|(p, q)| (p.to_string(), q.to_string())).unwrap_or((target, String::new()));
    Some(Request { path, query, headers })
}

fn respond(stream: &mut TcpStream, status: &str, ctype: &str, body: &str, extra: &str) {
    let _ = write!(
        stream,
        "HTTP/1.1 {status}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n{extra}\r\n{body}",
        body.len()
    );
}

// ---- WebSocket (text frames only; enough for the real portal's small JSON messages) ---------------------------------------------
fn ws_read_frame(s: &mut TcpStream) -> Option<(u8, Vec<u8>)> {
    let mut h = [0u8; 2];
    s.read_exact(&mut h).ok()?;
    let opcode = h[0] & 0x0f;
    let masked = h[1] & 0x80 != 0;
    let mut len = (h[1] & 0x7f) as usize;
    if len == 126 {
        let mut b = [0u8; 2];
        s.read_exact(&mut b).ok()?;
        len = u16::from_be_bytes(b) as usize;
    } else if len == 127 {
        let mut b = [0u8; 8];
        s.read_exact(&mut b).ok()?;
        len = u64::from_be_bytes(b) as usize;
    }
    if len > 65536 {
        return None;
    }
    let mut mask = [0u8; 4];
    if masked {
        s.read_exact(&mut mask).ok()?;
    }
    let mut data = vec![0u8; len];
    s.read_exact(&mut data).ok()?;
    if masked {
        for (i, b) in data.iter_mut().enumerate() {
            *b ^= mask[i % 4];
        }
    }
    Some((opcode, data))
}

fn ws_write(s: &mut TcpStream, opcode: u8, data: &[u8]) -> bool {
    let mut f = vec![0x80 | opcode];
    if data.len() < 126 {
        f.push(data.len() as u8);
    } else {
        f.push(126);
        f.extend_from_slice(&(data.len() as u16).to_be_bytes());
    }
    f.extend_from_slice(data);
    s.write_all(&f).is_ok()
}

fn ws_session(mut s: TcpStream, key: &str) {
    let _ = write!(s, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n", ws_accept(key));
    let _ = s.set_read_timeout(Some(Duration::from_secs(120)));
    while let Some((op, data)) = ws_read_frame(&mut s) {
        match op {
            1 => {
                let t = Instant::now();
                let reply = format!("pong {} bytes, rss {} kB", data.len(), rss_kb());
                let _ = t;
                if !ws_write(&mut s, 1, reply.as_bytes()) {
                    break;
                }
            }
            9 => {
                if !ws_write(&mut s, 10, &data) {
                    break;
                }
            }
            8 => {
                let _ = ws_write(&mut s, 8, &[]);
                break;
            }
            _ => {}
        }
    }
}

// ---- the grant: what the real portal will do once a customer has paid -------------------------------------------------------------
fn grant(mac: &str, minutes: u32) -> String {
    let ndsctl = std::env::var("NDSCTL").unwrap_or_else(|_| "ndsctl".to_string());
    let t = Instant::now();
    let out = Command::new(&ndsctl).args(["auth", mac, &minutes.to_string(), "0", "0", "0", "0", "pisoportal-step1"]).output();
    let ms = t.elapsed().as_secs_f64() * 1000.0;
    match out {
        Ok(o) => format!(
            "{} in {:.0} ms: {}{}",
            if o.status.success() { "ok" } else { "FAILED" },
            ms,
            String::from_utf8_lossy(&o.stdout).trim(),
            String::from_utf8_lossy(&o.stderr).trim()
        ),
        Err(e) => format!("FAILED to run {}: {}", ndsctl, e),
    }
}

fn handle(mut stream: TcpStream, peer: SocketAddr) {
    let t0 = Instant::now();
    let Some(req) = read_request(&stream) else { return };
    let ip = peer.ip().to_string();
    let hdr = |n: &str| req.headers.iter().find(|(k, _)| k == n).map(|(_, v)| v.clone());
    match req.path.as_str() {
        "/ping" => respond(&mut stream, "200 OK", "text/plain", "ok", ""),
        "/ws" => match hdr("sec-websocket-key") {
            Some(k) => ws_session(stream, &k),
            None => respond(&mut stream, "400 Bad Request", "text/plain", "websocket only", ""),
        },
        "/grant" => {
            // the MAC is what the router sees behind this connection, never what the page says
            match arp_mac(&ip).filter(|m| valid_mac(m)) {
                Some(mac) => {
                    let r = grant(&mac, 5);
                    eprintln!("grant {} -> {}", mac, r);
                    respond(&mut stream, "200 OK", "text/plain", &r, "Access-Control-Allow-Origin: *\r\n");
                }
                None => respond(&mut stream, "404 Not Found", "text/plain", "no MAC address known for this connection", ""),
            }
        }
        _ => {
            // any other address is the login page (openNDS redirects here with ?fas=...)
            let fields = fas_fields(&req.query);
            let mac = arp_mac(&ip).unwrap_or_else(|| "unknown".to_string());
            let body = page(&fields, &ip, &mac, t0.elapsed().as_micros());
            respond(&mut stream, "200 OK", "text/html; charset=utf-8", &body, "");
            eprintln!("page for {} ({}) in {} us, fas fields: {}", ip, mac, t0.elapsed().as_micros(), fields.len());
        }
    }
}

fn selftest() -> bool {
    let sha_ok = sha1(b"abc").iter().map(|b| format!("{:02x}", b)).collect::<String>() == "a9993e364706816aba3e25717850c26c9cd0d89d";
    let ws_ok = ws_accept("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    let b64_ok = b64_decode("Y2xpZW50aXA9MS4yLjMuNA==").map(|v| v == b"clientip=1.2.3.4").unwrap_or(false);
    let fas_ok = fas_fields("fas=Y2xpZW50aXA9MS4yLjMuNCwgY2xpZW50bWFjPWFhOmJiOmNjOmRkOmVlOmZm") == vec![("clientip".into(), "1.2.3.4".into()), ("clientmac".into(), "aa:bb:cc:dd:ee:ff".into())];
    println!("sha1: {}  websocket accept key: {}  base64: {}  fas query: {}", sha_ok, ws_ok, b64_ok, fas_ok);
    println!("arch={} endian={} pointer_width={} rss_kb={}", std::env::consts::ARCH, if cfg!(target_endian = "little") { "little" } else { "big" }, usize::BITS, rss_kb());
    sha_ok && ws_ok && b64_ok && fas_ok
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--selftest") {
        std::process::exit(if selftest() { 0 } else { 1 });
    }
    let arg = |name: &str, default: &str| args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned().unwrap_or_else(|| default.to_string());
    let addr = format!("{}:{}", arg("--bind", "0.0.0.0"), arg("--port", "2080"));
    let listener = TcpListener::bind(&addr).unwrap_or_else(|e| {
        eprintln!("cannot listen on {}: {}", addr, e);
        std::process::exit(1)
    });
    eprintln!("pisoportal step 1 listening on {} (rss {} kB)", addr, rss_kb());
    for stream in listener.incoming().flatten() {
        let Ok(peer) = stream.peer_addr() else { continue };
        let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
        let _ = stream.set_nodelay(true);
        thread::Builder::new().stack_size(64 * 1024).spawn(move || handle(stream, peer)).ok();
    }
}
