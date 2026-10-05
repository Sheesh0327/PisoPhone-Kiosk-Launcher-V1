//! Probe for the router: not the coin-slot manager. It answers three questions:
//!   1. does a static Rust binary run on this router at all           (coinslot-probe --selftest)
//!   2. how fast is the router's CPU at signing requests               (coinslot-probe --bench)
//!   3. how fast can a resident Rust server answer on this router      (coinslot-probe 18099, then time wget/curl)
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::time::{Duration, Instant};

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

fn sign(key: &[u8], msg: &[u8]) -> String {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("any key length");
    mac.update(msg);
    hex(&mac.finalize().into_bytes())
}

fn selftest() -> bool {
    // the same reference vector the router scripts check themselves against
    let ok = sign(b"key", b"The quick brown fox jumps over the lazy dog")
        == "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
    println!("hmac-sha256 vector: {}", if ok { "ok" } else { "WRONG" });
    println!(
        "arch={} endian={} pointer_width={} os={}",
        std::env::consts::ARCH,
        if cfg!(target_endian = "little") { "little" } else { "big" },
        usize::BITS,
        std::env::consts::OS
    );
    ok
}

fn bench() {
    let n = 20_000u32;
    let start = Instant::now();
    let mut last = String::new();
    for i in 0..n {
        last = sign(b"test-gateway-key-123456", format!("gw1:arm:{}:{}", "a".repeat(32), i).as_bytes());
    }
    let us = start.elapsed().as_micros() as f64 / n as f64;
    println!("{} HMAC-SHA256 signatures: {:.1} microseconds each (last {})", n, us, &last[..8]);
}

fn serve(port: u16) {
    let l = TcpListener::bind(("127.0.0.1", port)).expect("cannot bind the port");
    println!("listening on 127.0.0.1:{} (press Ctrl-C to stop)", port);
    for s in l.incoming() {
        let mut s = match s {
            Ok(s) => s,
            Err(_) => continue,
        };
        let _ = s.set_read_timeout(Some(Duration::from_secs(2)));
        let mut line = String::new();
        // one short request line is all that is read: bounded input
        let _ = BufReader::new((&s).take(1024)).read_line(&mut line);
        let path = line.split_whitespace().nth(1).unwrap_or("/");
        let body = format!("{{\"ok\":true,\"path\":\"{}\",\"sig\":\"{}\"}}", path.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '/').take(64).collect::<String>(), &sign(b"key", line.as_bytes())[..16]);
        let _ = write!(s, "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}", body.len(), body);
    }
}

fn main() {
    let arg = std::env::args().nth(1).unwrap_or_default();
    match arg.as_str() {
        "--selftest" => std::process::exit(if selftest() { 0 } else { 1 }),
        "--bench" => bench(),
        p => match p.parse::<u16>() {
            Ok(port) => serve(port),
            Err(_) => {
                eprintln!("usage: coinslot-probe --selftest | --bench | <port>");
                std::process::exit(2);
            }
        },
    }
}
