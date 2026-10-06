//! Small helpers: time, hex, flat-JSON reading (the box and openNDS answer with flat objects), URL and base64 coding.
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

pub fn sha256_hex(data: &[u8]) -> String {
    hex(&Sha256::digest(data))
}

pub fn hmac_hex(key: &[u8], msg: &[u8]) -> String {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("any key length");
    mac.update(msg);
    hex(&mac.finalize().into_bytes())
}

/// Random lowercase hex (from the kernel's random source).
pub fn random_hex(n: usize) -> String {
    let mut buf = vec![0u8; n.div_ceil(2)];
    if let Ok(mut f) = fs::File::open("/dev/urandom") {
        let _ = std::io::Read::read_exact(&mut f, &mut buf);
    } else {
        let t = now_ms();
        for (i, b) in buf.iter_mut().enumerate() {
            *b = (t >> ((i % 8) * 8)) as u8 ^ (i as u8).wrapping_mul(31);
        }
    }
    let mut s = hex(&buf);
    s.truncate(n);
    s
}

/// The value of `key` in a flat JSON object (quoted or not), like the shell's jget: "" when missing.
pub fn jget(body: &str, key: &str) -> String {
    let pat = format!("\"{}\"", key);
    let Some(i) = body.find(&pat) else { return String::new() };
    let rest = body[i + pat.len()..].trim_start();
    let Some(rest) = rest.strip_prefix(':') else { return String::new() };
    let rest = rest.trim_start();
    if let Some(q) = rest.strip_prefix('"') {
        q.split('"').next().unwrap_or("").to_string()
    } else {
        rest.split([',', '}']).next().unwrap_or("").trim().to_string()
    }
}

pub fn jget_u64(body: &str, key: &str) -> Option<u64> {
    jget(body, key).parse().ok()
}

pub fn json_esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o
}

pub fn url_decode(s: &str) -> String {
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

pub fn query_get(query: &str, key: &str) -> Option<String> {
    query.split('&').find_map(|kv| {
        let (k, v) = kv.split_once('=')?;
        (k == key).then(|| url_decode(v))
    })
}

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn b64_encode(data: &[u8]) -> String {
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

pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
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

pub fn valid_mac(m: &str) -> bool {
    m.len() == 17 && m.split(':').count() == 6 && m.split(':').all(|p| p.len() == 2 && p.bytes().all(|c| c.is_ascii_hexdigit()))
}

pub fn valid_sid(s: &str) -> bool {
    s.len() == 32 && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_reader_matches_the_shell_one() {
        let j = r#"{"state":"armed","pulses":3,"plan":"hyper","error":"","retry": 15,"final":false}"#;
        assert_eq!(jget(j, "state"), "armed");
        assert_eq!(jget(j, "pulses"), "3");
        assert_eq!(jget(j, "error"), "");
        assert_eq!(jget(j, "retry"), "15");
        assert_eq!(jget(j, "final"), "false");
        assert_eq!(jget(j, "nokey"), "");
        assert_eq!(jget_u64(j, "pulses"), Some(3));
    }

    #[test]
    fn hmac_reference_vector() {
        assert_eq!(
            hmac_hex(b"key", b"The quick brown fox jumps over the lazy dog"),
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        );
        assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    #[test]
    fn base64_and_urls() {
        assert_eq!(b64_decode("Y2xpZW50aXA9MS4yLjMuNA==").unwrap(), b"clientip=1.2.3.4");
        assert_eq!(b64_encode(b"clientip=1.2.3.4"), "Y2xpZW50aXA9MS4yLjMuNA==");
        assert_eq!(url_decode("a%2Bb%3D%3D+c"), "a+b== c");
        assert_eq!(query_get("x=1&fas=ab%2Bc", "fas").unwrap(), "ab+c");
        assert!(valid_mac("aa:bb:cc:dd:ee:ff") && !valid_mac("aa:bb:cc:dd:ee") && !valid_mac("zz:bb:cc:dd:ee:ff"));
        assert!(valid_sid(&"a1".repeat(16)) && !valid_sid("abc"));
    }
}
