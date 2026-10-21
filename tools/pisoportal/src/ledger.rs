//! The revenue ledger: time,plan,pesos,minutes,kind,hash. The kind says how the pesos came in: new, topup, switch (coins that bought
//! time at once), vbuy (coins that bought a voucher code: income the moment the coins are counted, so it is reconciled with the
//! box like any coin; redeeming the code later writes nothing) and voucher (older releases only: a printed slip booked when it
//! was redeemed; sold for cash elsewhere, so the box never counted it). Each line ends with a short hash over the previous line's hash
//! and its own text, so an edited, removed or inserted line is detectable. The first line chains from "0".
use crate::util::sha256_hex;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};

pub fn chain(prev: &str, line: &str) -> String {
    sha256_hex(format!("{}|{}", prev, line).as_bytes())[..12].to_string()
}

/// A row's hash is always 12 lower-case hex characters. A line whose last field is shorter was cut by a power failure.
fn hash_ok(h: &str) -> bool {
    h.len() == 12 && h.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

pub fn append(path: &str, now: u64, plan: &str, pesos: u32, minutes: u32, kind: &str) -> std::io::Result<()> {
    if let Some(dir) = std::path::Path::new(path).parent() {
        fs::create_dir_all(dir)?;
    }
    let prev = last_hash(path).unwrap_or("0".into());
    let rl = format!("{},{},{},{},{}", now, plan, pesos, minutes, kind);
    // the whole row is built first and written with one call (RT-04)
    let mut row = format!("{},{}\n", rl, chain(&prev, &rl));
    // a last row cut short by a power failure has no newline: the new row must not run into it
    if !ends_with_newline(path) {
        row.insert(0, '\n');
    }
    let mut f = fs::OpenOptions::new().create(true).append(true).open(path)?;
    f.write_all(row.as_bytes())?;
    f.sync_all().ok();
    Ok(())
}

/// True for a missing or empty file and for one whose last byte is a newline.
fn ends_with_newline(path: &str) -> bool {
    let Ok(mut f) = fs::File::open(path) else { return true };
    let Ok(len) = f.metadata().map(|m| m.len()) else { return true };
    if len == 0 {
        return true;
    }
    let mut b = [0u8; 1];
    f.seek(SeekFrom::Start(len - 1)).and_then(|_| f.read_exact(&mut b)).map(|_| b[0] == b'\n').unwrap_or(true)
}

/// The hash of the last row that has a whole hash, read from the end of the file (the ledger only grows; a payment must not read
/// all of it). A fragment cut by a power failure has no whole hash and is passed over.
fn last_hash(path: &str) -> Option<String> {
    let mut f = fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    let from = len.saturating_sub(512);
    f.seek(SeekFrom::Start(from)).ok()?;
    let mut tail = Vec::new();
    f.read_to_end(&mut tail).ok()?;
    let text = String::from_utf8_lossy(&tail);
    text.lines().rev().filter_map(|l| l.split(',').nth(5).map(|h| h.to_string())).find(|h| hash_ok(h))
}

/// Ok((lines, pesos)) or Err(first bad line number). Lines without a hash (older versions) are skipped.
pub fn verify(path: &str) -> Result<(usize, u64), usize> {
    verify_torn(path).map(|(n, sum, _)| (n, sum))
}

/// Like `verify`, and whether the last line was torn: a last line with no newline is skipped (a power failure cut the write) and
/// reported, never taken for a changed ledger. A line cut inside its hash is skipped wherever it is: the row after it was
/// written on a new line of its own and chains from the last whole row.
pub fn verify_torn(path: &str) -> Result<(usize, u64, bool), usize> {
    let Ok(text) = fs::read_to_string(path) else { return Ok((0, 0, false)) };
    let torn = !text.is_empty() && !text.ends_with('\n');
    let (mut prev, mut sum, mut n) = ("0".to_string(), 0u64, 0usize);
    let total = text.lines().count();
    for (i, l) in text.lines().enumerate() {
        if torn && i + 1 == total {
            break;
        }
        n = i + 1;
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 6 || !hash_ok(f[f.len() - 1]) {
            continue;
        }
        let (t, h) = (f[..f.len() - 1].join(","), f[f.len() - 1]);
        if chain(&prev, &t) != h {
            return Err(i + 1);
        }
        prev = h.to_string();
        // pesos from coins only: the box counts coins, so what the box did not count (a printed slip of an older release, sold for
        // cash elsewhere: kind "voucher") is not part of its reconciliation. A voucher bought with coins ("vbuy") is.
        if f.get(4) != Some(&"voucher") {
            sum += f[2].parse::<u64>().unwrap_or(0);
        }
    }
    Ok((n, sum, torn))
}

/// Move timestamps of entries recorded in `since <= time < until` forward by `delta` seconds when the router clock jumps forward
/// (NTP sync): rows written after the jump (`until` is the stale time at which it happened) were timed with the right clock and
/// stay. Recomputes the hash chain from the modified entries forward and saves atomically.
pub fn shift(path: &str, since: u64, until: u64, delta: u64) -> usize {
    let Ok(text) = fs::read_to_string(path) else { return 0 };
    if text.is_empty() {
        return 0;
    }
    let mut lines: Vec<String> = Vec::new();
    let mut prev = "0".to_string();
    let mut moved = 0;
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 6 || !hash_ok(f[f.len() - 1]) {
            lines.push(l.to_string());
            continue;
        }
        let mut ts = f[0].parse::<u64>().unwrap_or(0);
        if ts >= since && ts < until {
            ts += delta;
            moved += 1;
        }
        let rl = format!("{},{},{},{},{}", ts, f[1], f[2], f[3], f[4]);
        let h = chain(&prev, &rl);
        prev = h.clone();
        lines.push(format!("{},{}", rl, h));
    }
    if moved > 0 {
        let out: String = lines.iter().map(|l| format!("{}\n", l)).collect();
        if crate::util::write_atomic(path, out.as_bytes()).is_err() {
            return 0;
        }
    }
    moved
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

    #[test]
    fn coins_that_bought_a_voucher_are_reconciled_and_old_slip_rows_are_not() {
        let d = std::env::temp_dir().join(format!("pisoportal-ledger-v-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let p = d.join("revenue.csv").to_string_lossy().into_owned();
        append(&p, 100, "hyper", 5, 30, "new").unwrap();
        append(&p, 150, "hyper", 10, 60, "vbuy").unwrap(); // coins that bought a voucher: counted by the box, so reconciled
        append(&p, 200, "endurance", 20, 1440, "voucher").unwrap(); // a slip of an older release: not counted by the box
        append(&p, 300, "hyper", 3, 18, "topup").unwrap();
        assert_eq!(verify(&p), Ok((4, 18)));
    }

    fn ledger_with_two_rows(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-ledger-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let p = d.join("revenue.csv").to_string_lossy().into_owned();
        append(&p, 100, "hyper", 5, 30, "new").unwrap();
        append(&p, 200, "endurance", 10, 60, "topup").unwrap();
        p
    }

    #[test]
    fn rt04_a_torn_last_row_is_reported_as_torn_not_as_a_changed_ledger() {
        let p = ledger_with_two_rows("torn-verify");
        // the power went in the middle of the third row: no newline, and the hash is cut short
        let mut f = fs::OpenOptions::new().append(true).open(&p).unwrap();
        write!(f, "300,hyper,3,18,new,ab").unwrap();
        drop(f);
        assert_eq!(verify_torn(&p), Ok((2, 15, true)), "the two whole rows count, the torn one is skipped and reported");
        assert_eq!(verify(&p), Ok((2, 15)));
    }

    #[test]
    fn rt04_a_whole_row_missing_only_its_newline_is_also_torn() {
        let p = ledger_with_two_rows("torn-whole");
        let text = fs::read_to_string(&p).unwrap();
        fs::write(&p, text.trim_end_matches('\n')).unwrap(); // the last "\n" never reached the flash
        assert_eq!(verify_torn(&p), Ok((1, 5, true)));
    }

    #[test]
    fn rt04_the_next_row_after_a_torn_tail_starts_on_its_own_line_and_the_chain_holds() {
        let p = ledger_with_two_rows("torn-append");
        let mut f = fs::OpenOptions::new().append(true).open(&p).unwrap();
        write!(f, "300,hyper,3,18,new,ab").unwrap();
        drop(f);
        append(&p, 400, "hyper", 1, 6, "new").unwrap();
        let text = fs::read_to_string(&p).unwrap();
        assert!(text.ends_with('\n'));
        let last = text.lines().last().unwrap();
        assert!(last.starts_with("400,hyper,1,6,new,") && last.split(',').count() == 6, "the new row is a row of its own: {}", last);
        assert_eq!(verify(&p), Ok((4, 16)), "no false BADLEDGER: the torn fragment is skipped, the new row chains from the last whole row");
        assert_eq!(verify_torn(&p).map(|v| v.2), Ok(false), "and the tail is whole again");
    }

    #[test]
    fn rt04_a_row_is_written_whole_with_one_write() {
        // the row is built first and appended in one write_all (a tear can then only cut the end, never leave two halves)
        let code = include_str!("ledger.rs").split("#[cfg(test)]").next().unwrap();
        assert!(!code.contains("writeln!(f, \"{},{}\""), "RT-04: the ledger row is built whole, then written once");
    }

    #[test]
    fn rt02_a_payment_after_the_jump_is_not_moved() {
        let p = ledger_with_two_rows("window"); // rows at 100 and 200
        append(&p, 5000, "hyper", 1, 6, "new").unwrap(); // paid after the clock was set right
        // the clock jumped at stale time 250 by 4000 s: only rows in [100, 250) were timed with the stale clock
        assert_eq!(shift(&p, 100, 250, 4000), 2);
        let t = fs::read_to_string(&p).unwrap();
        let rows: Vec<&str> = t.lines().collect();
        assert!(rows[0].starts_with("4100,") && rows[1].starts_with("4200,") && rows[2].starts_with("5000,"), "{:?}", rows);
        assert_eq!(verify(&p), Ok((3, 16)));
    }

    #[test]
    fn shift_updates_stale_timestamps_and_preserves_valid_chain() {
        let d = std::env::temp_dir().join(format!("pisoportal-ledger-shift-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let p = d.join("revenue.csv").to_string_lossy().into_owned();
        append(&p, 100, "hyper", 5, 30, "new").unwrap();
        append(&p, 200, "endurance", 10, 60, "topup").unwrap();
        append(&p, 250, "hyper", 1, 6, "new").unwrap();

        assert_eq!(verify(&p), Ok((3, 16)));

        // Shift entries since 180 forward by 1000 seconds
        let moved = shift(&p, 180, u64::MAX, 1000);
        assert_eq!(moved, 2);

        // Verification must still pass on the re-chained ledger
        assert_eq!(verify(&p), Ok((3, 16)));

        // Verify timestamps
        let content = fs::read_to_string(&p).unwrap();
        let rows: Vec<&str> = content.lines().collect();
        assert!(rows[0].starts_with("100,hyper,5,30,new"));
        assert!(rows[1].starts_with("1200,endurance,10,60,topup"));
        assert!(rows[2].starts_with("1250,hyper,1,6,new"));
    }
}
