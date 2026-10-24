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

/// The one way the program replaces a file that matters: write `path.tmp`, fsync it, rename it over `path`, fsync the folder.
/// If a step before the rename fails, the error is returned and the old file is untouched. (The folder fsync comes after the
/// rename, which cannot be undone, so it is best effort: some flash file systems refuse it.) The folder is made when missing.
pub fn write_atomic(path: &str, bytes: &[u8]) -> std::io::Result<()> {
    write_atomic_mode(path, bytes, None)
}

/// `write_atomic` for a secret: the file is made readable by its owner only (on Unix) before anything is written to it.
pub fn write_atomic_private(path: &str, bytes: &[u8]) -> std::io::Result<()> {
    write_atomic_mode(path, bytes, Some(0o600))
}

#[cfg_attr(not(unix), allow(unused_variables))]
fn write_atomic_mode(path: &str, bytes: &[u8], mode: Option<u32>) -> std::io::Result<()> {
    use std::io::Write;
    let dir = std::path::Path::new(path).parent().filter(|d| !d.as_os_str().is_empty());
    if let Some(d) = dir {
        fs::create_dir_all(d)?;
    }
    let tmp = format!("{}.tmp", path);
    let result = (|| {
        let mut opts = fs::OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        if let Some(m) = mode {
            std::os::unix::fs::OpenOptionsExt::mode(&mut opts, m);
        }
        let mut f = opts.open(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&tmp, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
        return result;
    }
    if let Some(d) = dir {
        if let Ok(d) = fs::File::open(d) {
            let _ = d.sync_all();
        }
    }
    Ok(())
}

/// Moves a file to a new name and flushes the folder, so the move survives a power cut (the ledger roll uses it twice).
pub fn rename_durable(from: &str, to: &str) -> std::io::Result<()> {
    fs::rename(from, to)?;
    if let Some(d) = std::path::Path::new(to).parent().filter(|d| !d.as_os_str().is_empty()) {
        if let Ok(d) = fs::File::open(d) {
            let _ = d.sync_all();
        }
    }
    Ok(())
}

/// Random lowercase hex (from the kernel's random source).
pub fn random_hex(n: usize) -> String {
    let mut buf = vec![0u8; n.div_ceil(2)];
    let read = fs::File::open("/dev/urandom").and_then(|mut f| std::io::Read::read_exact(&mut f, &mut buf));
    if read.is_err() {
        // never on Linux; still never all zeros
        let t = now_ms() ^ (std::process::id() as u64) << 40;
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

fn hex_val(c: u8) -> Option<u8> {
    (c as char).to_digit(16).map(|v| v as u8)
}

/// Works on bytes: anything a customer's browser sends must decode without panicking (a panic aborts the portal).
pub fn url_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                if let (Some(h), Some(l)) = (hex_val(b[i + 1]), hex_val(b[i + 2])) {
                    out.push(h << 4 | l);
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

/// "YYYY-MM-DD" of a day number counted from 1970-01-01.
pub fn civil(days: i64) -> String {
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    format!("{:04}-{:02}-{:02}", if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tdir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("pisoportal-util-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn rt05_write_atomic_replaces_the_file_and_leaves_no_temporary() {
        let d = tdir("wa");
        let p = d.join("f.txt").to_string_lossy().into_owned();
        write_atomic(&p, b"one\n").unwrap();
        write_atomic(&p, b"two\n").unwrap();
        assert_eq!(fs::read_to_string(&p).unwrap(), "two\n");
        assert!(!std::path::Path::new(&format!("{}.tmp", p)).exists());
    }

    #[test]
    fn rt05_write_atomic_failure_leaves_the_old_file_in_place() {
        let d = tdir("wa-fail");
        let p = d.join("f.txt").to_string_lossy().into_owned();
        fs::write(&p, "old\n").unwrap();
        // the temporary name is taken by a folder: the temporary file cannot be made, so the step fails
        fs::create_dir(format!("{}.tmp", p)).unwrap();
        assert!(write_atomic(&p, b"new\n").is_err(), "a failed step is reported, not swallowed");
        assert_eq!(fs::read_to_string(&p).unwrap(), "old\n", "and the old file is still there");
    }

    #[test]
    fn rt14_no_other_file_renames_a_file_itself() {
        // every durable write goes through util::write_atomic (file fsync, rename, folder fsync)
        let files: [(&str, &str); 10] = [
            ("config.rs", include_str!("config.rs")),
            ("game.rs", include_str!("game.rs")),
            ("ledger.rs", include_str!("ledger.rs")),
            ("reconcile.rs", include_str!("reconcile.rs")),
            ("roll.rs", include_str!("roll.rs")),
            ("voucher.rs", include_str!("voucher.rs")),
            ("core.rs", include_str!("core.rs")),
            ("send.rs", include_str!("send.rs")),
            ("update.rs", include_str!("update.rs")),
            ("boxlink.rs", include_str!("boxlink.rs")),
        ];
        for (name, text) in files {
            // (the test sources themselves are cut off: only code before the tests module counts)
            let code = text.split("#[cfg(test)]").next().unwrap();
            assert!(!code.contains("fs::rename"), "{} renames a file itself: use util::write_atomic", name);
        }
        assert!(
            !include_str!("core.rs").split("#[cfg(test)]").next().unwrap().contains("fs::write(format!(\"{}.rec\""),
            "RT-05: the voucher marker is written with write_synced"
        );
    }

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
        // a '%' followed by multi-byte characters (once a crash: the hex digits were sliced out of the str)
        assert_eq!(url_decode("%aé"), "%aé");
        assert_eq!(url_decode("%é"), "%é");
        assert_eq!(url_decode("%e2%82%b1 50%"), "₱ 50%");
        assert_eq!(query_get("x=1&fas=ab%2Bc", "fas").unwrap(), "ab+c");
        assert!(valid_mac("aa:bb:cc:dd:ee:ff") && !valid_mac("aa:bb:cc:dd:ee") && !valid_mac("zz:bb:cc:dd:ee:ff"));
        assert!(valid_sid(&"a1".repeat(16)) && !valid_sid("abc"));
    }
}
