//! The revenue ledger: time,plan,pesos,minutes,kind,hash. Each line ends with a short hash over the previous line's hash
//! and its own text, so an edited, removed or inserted line is detectable. The first line chains from "0".
use crate::util::sha256_hex;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};

pub fn chain(prev: &str, line: &str) -> String {
    sha256_hex(format!("{}|{}", prev, line).as_bytes())[..12].to_string()
}

pub fn append(path: &str, now: u64, plan: &str, pesos: u32, minutes: u32, kind: &str) -> std::io::Result<()> {
    if let Some(dir) = std::path::Path::new(path).parent() {
        fs::create_dir_all(dir)?;
    }
    let prev = last_line(path).and_then(|l| l.split(',').nth(5).map(|h| h.to_string())).filter(|h| !h.is_empty()).unwrap_or("0".into());
    let rl = format!("{},{},{},{},{}", now, plan, pesos, minutes, kind);
    let mut f = fs::OpenOptions::new().create(true).append(true).open(path)?;
    writeln!(f, "{},{}", rl, chain(&prev, &rl))?;
    f.sync_all().ok();
    Ok(())
}

/// The file's last line, read from its end (the ledger only grows; a payment must not read all of it).
fn last_line(path: &str) -> Option<String> {
    let mut f = fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    let from = len.saturating_sub(512);
    f.seek(SeekFrom::Start(from)).ok()?;
    let mut tail = Vec::new();
    f.read_to_end(&mut tail).ok()?;
    let text = String::from_utf8_lossy(&tail);
    text.lines().rev().find(|l| !l.trim().is_empty()).map(|l| l.to_string())
}

/// Ok((lines, pesos)) or Err(first bad line number). Lines without a hash (older versions) are skipped.
pub fn verify(path: &str) -> Result<(usize, u64), usize> {
    let Ok(text) = fs::read_to_string(path) else { return Ok((0, 0)) };
    let (mut prev, mut sum, mut n) = ("0".to_string(), 0u64, 0usize);
    for (i, l) in text.lines().enumerate() {
        n = i + 1;
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 6 {
            continue;
        }
        let (t, h) = (f[..f.len() - 1].join(","), f[f.len() - 1]);
        if chain(&prev, &t) != h {
            return Err(i + 1);
        }
        prev = h.to_string();
        sum += f[2].parse::<u64>().unwrap_or(0);
    }
    Ok((n, sum))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chain_detects_edits() {
        let d = std::env::temp_dir().join(format!("pisoportal-ledger-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let p = d.join("revenue.csv").to_string_lossy().into_owned();
        append(&p, 100, "hyper", 5, 30, "new").unwrap();
        append(&p, 200, "endurance", 17, 690, "topup").unwrap();
        append(&p, 300, "hyper", 3, 18, "new").unwrap();
        assert_eq!(verify(&p), Ok((3, 25)));
        // a long ledger: the chain still continues from the last line (read from the end of the file)
        for i in 0..200 {
            append(&p, 400 + i, "hyper", 1, 6, "new").unwrap();
        }
        assert_eq!(verify(&p), Ok((203, 225)));
        let t = fs::read_to_string(&p).unwrap().replacen(",5,30,", ",50,30,", 1);
        fs::write(&p, t).unwrap();
        assert_eq!(verify(&p), Err(1));
    }
}
