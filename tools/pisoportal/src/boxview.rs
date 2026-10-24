//! The coin box's own admin page, shown inside the console's "Rental phones" tab.
//!
//! The box serves that page itself (its dashboard, with its own settings, tools and vendor screens). So that the owner has one admin
//! page and one sign-in, the router passes it through on a second port next to the console (console port + 1): the same paths as on
//! the box, so the page works unchanged. Only a browser with a console session gets anything; the router adds the box's admin
//! password (kept in the router's settings) and nothing else of the router's is given to the box. The box stays unreachable for guests
//! and, since router 1.5.10, for the phones. Bytes are copied through; nothing is changed or stored.
use crate::boxlink::with_port;
use crate::console::{conf_value, Console};
use crate::http::{bind_retry, read_request, respond, Incoming, Request};
use crate::util::b64_encode;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;

/// The largest body a browser may send through (the box's firmware upload is about 1.5 MB).
const MAX_UPLOAD: usize = 4 * 1024 * 1024;
/// The box answers slowly while it saves or restarts.
const BOX_WAIT: Duration = Duration::from_secs(30);

/// Request headers that go on to the box (everything else, the console's cookie included, stays here).
const PASS_ON: [&str; 9] = [
    "accept",
    "accept-language",
    "cache-control",
    "content-type",
    "content-length",
    "if-modified-since",
    "if-none-match",
    "range",
    "user-agent",
];
/// Answer headers that are not given back: they belong to the connection, or would stop the console from showing the page.
const DROP_ANSWER: [&str; 6] = ["connection", "keep-alive", "proxy-authenticate", "content-security-policy", "x-frame-options", "upgrade"];

/// The host part of a Host header ("10.0.0.1:8082" -> "10.0.0.1"), only when it is an ordinary host name or address.
pub(crate) fn host_of(host_header: &str) -> Option<String> {
    let h = host_header.trim();
    let name = if let Some(rest) = h.strip_prefix('[') { rest.split_once(']')?.0 } else { h.rsplit_once(':').map_or(h, |(n, _)| n) };
    let ok = !name.is_empty() && name.len() <= 253 && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == ':');
    ok.then(|| name.to_string())
}

/// Headers added to what the box answers: the page may be shown by the console (same host, console port) and by nobody else.
pub(crate) fn frame_policy(host: &str, console_port: u16) -> String {
    format!(
        "Content-Security-Policy: frame-ancestors http://{}:{}\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n",
        host, console_port
    )
}

/// The request head that goes to the box: the wanted headers, the box's own address as Origin and Referer (the box refuses a page of
/// another address), and the admin login. `cookie` is the browser's cookies without the console's.
pub(crate) fn box_request_head(req: &Request, box_host: &str, auth: &str, cookie: &str) -> String {
    let target = if req.query.is_empty() { req.path.clone() } else { format!("{}?{}", req.path, req.query) };
    let mut head =
        format!("{} {} HTTP/1.1\r\nHost: {}\r\nAuthorization: Basic {}\r\nConnection: close\r\n", req.method, target, box_host, auth);
    for (k, v) in &req.headers {
        if PASS_ON.contains(&k.as_str()) && !v.contains(['\r', '\n']) {
            head.push_str(&format!("{}: {}\r\n", k, v));
        }
    }
    if req.header("origin").is_some() {
        head.push_str(&format!("Origin: http://{}\r\n", box_host));
    }
    if let Some(r) = req.header("referer") {
        if let Some(rest) = r.strip_prefix("http://").and_then(|x| x.split_once('/')) {
            head.push_str(&format!("Referer: http://{}/{}\r\n", box_host, rest.1.replace(['\r', '\n'], "")));
        }
    }
    if !cookie.is_empty() {
        head.push_str(&format!("Cookie: {}\r\n", cookie));
    }
    head.push_str("\r\n");
    head
}

/// The browser's cookies without the console's own session cookie.
pub(crate) fn cookies_for_box(cookie_header: &str) -> String {
    cookie_header.split(';').map(str::trim).filter(|p| !p.is_empty() && !p.starts_with("piso_console=")).collect::<Vec<_>>().join("; ")
}

/// The box's answer head, ready to give on: its status line and headers except the ones that are dropped, plus `extra`.
pub(crate) fn answer_head(raw: &str, extra: &str) -> Option<String> {
    let mut lines = raw.split("\r\n");
    let status = lines.next().filter(|l| l.starts_with("HTTP/1."))?;
    let mut out = format!("{}\r\n", status);
    for l in lines {
        let Some((k, _)) = l.split_once(':') else { continue };
        if !DROP_ANSWER.contains(&k.trim().to_ascii_lowercase().as_str()) {
            out.push_str(l);
            out.push_str("\r\n");
        }
    }
    out.push_str("Connection: close\r\n");
    out.push_str(extra);
    out.push_str("\r\n");
    Some(out)
}

fn same_site_post(req: &Request) -> bool {
    match (req.header("origin"), req.header("host")) {
        (Some(o), Some(h)) => o.trim_start_matches("http://") == h,
        (None, _) => true,
        _ => false,
    }
}

fn refuse(conn: &mut Incoming, status: &str, text: &str) {
    respond(&mut conn.s, status, "text/plain; charset=utf-8", text, "Cache-Control: no-store\r\n");
}

fn serve_one(c: &Console, req: Request, mut conn: Incoming) {
    let cfg = &c.core.cfg;
    let console_port = cfg.console_port;
    // a request's body is not left unread on an early answer: closing with unread bytes resets the connection
    let len: usize = req.header("content-length").and_then(|v| v.parse().ok()).unwrap_or(0);
    if !c.session_ok(&req) {
        return refuse(
            &mut conn,
            "401 Unauthorized",
            "Sign in to the console first (the same address, the console's port), then open Rental phones.",
        );
    }
    if req.method != "GET" && req.method != "HEAD" && !same_site_post(&req) {
        return refuse(&mut conn, "403 Forbidden", "refused: this request came from another page");
    }
    if len > MAX_UPLOAD {
        return refuse(&mut conn, "413 Payload Too Large", "too large");
    }
    let Some(host) = req.header("host").and_then(host_of) else { return refuse(&mut conn, "400 Bad Request", "bad request") };
    let pass = conf_value(&cfg.setup_conf, "BOX_ADMIN_PASS");
    if pass.is_empty() {
        return refuse(
            &mut conn,
            "503 Service Unavailable",
            "The router does not know the coin box's admin password (piso-setup summary shows it). Open the box page directly.",
        );
    }
    let box_addr = with_port(&cfg.gw_box);
    let box_host = box_addr.strip_suffix(":80").unwrap_or(&box_addr).to_string();
    let Some(sock) = box_addr.to_socket_addrs().ok().and_then(|mut a| a.next()) else {
        return refuse(&mut conn, "502 Bad Gateway", "the coin box has no usable address");
    };
    let Ok(mut up) = TcpStream::connect_timeout(&sock, Duration::from_secs(3)) else {
        return refuse(&mut conn, "502 Bad Gateway", "The coin box does not answer. Check that it is on and joined to its Wi-Fi.");
    };
    up.set_read_timeout(Some(BOX_WAIT)).ok();
    up.set_write_timeout(Some(BOX_WAIT)).ok();
    let auth = b64_encode(format!("admin:{}", pass).as_bytes());
    let cookie = cookies_for_box(req.header("cookie").unwrap_or(""));
    if up.write_all(box_request_head(&req, &box_host, &auth, &cookie).as_bytes()).is_err() {
        return refuse(&mut conn, "502 Bad Gateway", "the coin box did not take the request");
    }
    // the body, as far as the browser said it would be
    let mut left = len;
    let mut buf = [0u8; 8192];
    while left > 0 {
        let n = match conn.read(&mut buf[..left.min(8192)]) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        if up.write_all(&buf[..n]).is_err() {
            return refuse(&mut conn, "502 Bad Gateway", "the coin box did not take the request");
        }
        left -= n;
    }
    // the answer: its head (changed), then the bytes as they come until the box closes
    let mut raw: Vec<u8> = Vec::with_capacity(2048);
    let split = loop {
        if let Some(i) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
            break Some(i);
        }
        if raw.len() > 16 * 1024 {
            break None;
        }
        match up.read(&mut buf) {
            Ok(0) | Err(_) => break None,
            Ok(n) => raw.extend_from_slice(&buf[..n]),
        }
    };
    let Some(i) = split else { return refuse(&mut conn, "502 Bad Gateway", "the coin box gave no usable answer") };
    let head = String::from_utf8_lossy(&raw[..i]).into_owned();
    let Some(out) = answer_head(&head, &frame_policy(&host, console_port)) else {
        return refuse(&mut conn, "502 Bad Gateway", "the coin box gave no usable answer");
    };
    if conn.s.write_all(out.as_bytes()).is_err() || conn.s.write_all(&raw[i + 4..]).is_err() {
        return;
    }
    loop {
        match up.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if conn.s.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        }
    }
}

/// Starts the pass-through on the console's address, one port above the console.
pub fn start(console: Arc<Console>) {
    let cfg = &console.core.cfg;
    let addr = format!("{}:{}", cfg.console_bind, cfg.console_port.saturating_add(1));
    std::thread::spawn(move || {
        let listener = bind_retry(&addr, "box page");
        let limits = crate::http::console_limits();
        for stream in listener.incoming().flatten() {
            // over the limit (in all, or from one address): turned away before a thread is started
            let Some(slot) = stream.peer_addr().ok().and_then(|a| limits.take(a.ip())) else { continue };
            let c = Arc::clone(&console);
            std::thread::spawn(move || {
                let _slot = slot;
                if let Some((req, conn)) = read_request(stream, Duration::from_secs(5)) {
                    serve_one(&c, req, conn);
                }
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(method: &str, path: &str, query: &str, headers: &[(&str, &str)]) -> Request {
        Request {
            method: method.into(),
            path: path.into(),
            query: query.into(),
            headers: headers.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
        }
    }

    #[test]
    fn the_host_is_taken_from_the_host_header_only_when_it_is_plain() {
        assert_eq!(host_of("10.0.0.1:8082").as_deref(), Some("10.0.0.1"));
        assert_eq!(host_of("router.lan").as_deref(), Some("router.lan"));
        assert_eq!(host_of("[fe80::1]:8082").as_deref(), Some("fe80::1"));
        assert_eq!(host_of("a b"), None);
        assert_eq!(host_of("evil.com;x"), None);
        assert_eq!(host_of(""), None);
    }

    #[test]
    fn the_console_cookie_is_not_given_to_the_box() {
        assert_eq!(cookies_for_box("piso_console=abc; box=1; x=2"), "box=1; x=2");
        assert_eq!(cookies_for_box("piso_console=abc"), "");
    }

    #[test]
    fn the_request_to_the_box_has_its_login_and_its_own_address() {
        let r = req(
            "POST",
            "/save",
            "a=1",
            &[
                ("host", "10.0.0.1:8082"),
                ("origin", "http://10.0.0.1:8082"),
                ("referer", "http://10.0.0.1:8082/x?y"),
                ("content-type", "application/x-www-form-urlencoded"),
                ("content-length", "5"),
                ("cookie", "piso_console=s"),
                ("x-secret", "no"),
            ],
        );
        let h = box_request_head(&r, "10.0.0.10", "QUJD", "");
        assert!(h.starts_with("POST /save?a=1 HTTP/1.1\r\nHost: 10.0.0.10\r\nAuthorization: Basic QUJD\r\nConnection: close\r\n"));
        assert!(h.contains("Origin: http://10.0.0.10\r\n") && h.contains("Referer: http://10.0.0.10/x?y\r\n"));
        assert!(h.contains("content-type: application/x-www-form-urlencoded\r\n") && h.contains("content-length: 5\r\n"));
        assert!(!h.contains("piso_console") && !h.contains("x-secret") && !h.contains("10.0.0.1:8082"), "{}", h);
        assert!(h.ends_with("\r\n\r\n"));
    }

    #[test]
    fn a_header_with_a_line_break_in_it_is_not_passed() {
        let r = req("GET", "/", "", &[("accept", "a\r\nX-Evil: 1")]);
        assert!(!box_request_head(&r, "10.0.0.10", "QUJD", "").contains("X-Evil"));
    }

    #[test]
    fn the_answer_loses_the_headers_that_would_stop_the_framing() {
        let raw = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'\r\nSet-Cookie: a=b; Path=/\r\nConnection: keep-alive\r\nTransfer-Encoding: chunked";
        let out = answer_head(raw, &frame_policy("10.0.0.1", 8081)).unwrap();
        assert!(
            out.starts_with("HTTP/1.1 200 OK\r\n")
                && out.contains("Content-Type: text/html\r\n")
                && out.contains("Set-Cookie: a=b; Path=/\r\n")
        );
        assert!(out.contains("Transfer-Encoding: chunked\r\n"), "the body is passed as it comes, so its framing stays");
        assert!(!out.contains("DENY") && !out.contains("default-src") && !out.contains("keep-alive"));
        assert!(out.contains("Content-Security-Policy: frame-ancestors http://10.0.0.1:8081\r\n") && out.ends_with("\r\n\r\n"));
        assert!(answer_head("garbage", "").is_none());
    }

    #[test]
    fn a_post_from_another_page_is_refused() {
        assert!(same_site_post(&req("POST", "/", "", &[("host", "r:8082"), ("origin", "http://r:8082")])));
        assert!(!same_site_post(&req("POST", "/", "", &[("host", "r:8082"), ("origin", "http://evil.test")])));
        assert!(same_site_post(&req("POST", "/", "", &[("host", "r:8082")])));
    }
}
