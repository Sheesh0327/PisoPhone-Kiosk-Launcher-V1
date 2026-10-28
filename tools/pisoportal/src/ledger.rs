//! The revenue ledger: time,plan,pesos,minutes,kind,hash. The kind says how the pesos came in: new, topup, switch (coins that bought
//! time at once), vbuy (coins that bought a voucher code: income the moment the coins are counted, so it is reconciled with the
//! box like any coin; redeeming the code later writes nothing) and voucher (older releases only: a printed slip booked when it
//! was redeemed; sold for cash elsewhere, so the box never counted it). Each line ends with a short hash over the previous line's hash
//! and its own text, so an edited, removed or inserted line is detectable. The first line chains from "0".
//! Monthly segments (AO-02): the first row written in a new (UTC) month moves the file to revenue-YYYY-MM.csv and starts the new file
//! with one `carry` row: `time,<last hash of the old file>,<running coin total>,0,carry,<hash>`. It chains from that hash, so a file
//! verifies on its own (the cheap hourly check reads one month) and `verify_all` follows the whole chain across the segments.
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
    append_with(path, now, plan, pesos, minutes, kind, &Budget::default())
}

/// The size budget (`LEDGER_MAX_KB`, `LEDGER_KEEP_MONTHS`).
#[derive(Debug, Clone, PartialEq)]
pub struct Budget {
    pub max_kb: u64,
    pub keep_months: u64,
}

impl Default for Budget {
    fn default() -> Self {
        Budget { max_kb: 1024, keep_months: 3 }
    }
}

fn append_with(path: &str, now: u64, plan: &str, pesos: u32, minutes: u32, kind: &str, budget: &Budget) -> std::io::Result<()> {
    if let Some(dir) = std::path::Path::new(path).parent() {
        fs::create_dir_all(dir)?;
    }
    // money first: a roll that fails for any reason never stops the sale row from being written
    let _ = recover_roll(path);
    let rolled = roll_if_new_month(path, now).unwrap_or(false);
    let row = append_row(path, now, plan, pesos, minutes, kind);
    // the sale is on flash (or has failed) before anything is deleted; a prune that fails changes nothing about the sale
    if rolled {
        match prune(path, budget) {
            Ok(r) if !r.reached => eprintln!("ledger: could not bring the revenue files inside {} KB ({} months deleted)", budget.max_kb, r.deleted.len()),
            Err(e) => eprintln!("ledger: pruning old months failed: {}", e),
            Ok(_) => {}
        }
    }
    row
}

fn append_row(path: &str, now: u64, plan: &str, pesos: u32, minutes: u32, kind: &str) -> std::io::Result<()> {
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

fn month_of(t: u64) -> String {
    crate::util::civil((t / 86400) as i64)[..7].to_string()
}

fn segment_path(path: &str, month: &str) -> String {
    let p = std::path::Path::new(path);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or("revenue");
    let ext = p.extension().and_then(|s| s.to_str()).unwrap_or("csv");
    p.with_file_name(format!("{}-{}.{}", stem, month, ext)).to_string_lossy().into_owned()
}

/// A roll a power cut stopped half-way: the new file is complete (`.new`) but not yet in place, or it was never finished. Only the
/// portal calls this (from `append`); readers use `effective` and change nothing.
fn recover_roll(path: &str) -> std::io::Result<()> {
    let tmp = format!("{}.new", path);
    if !std::path::Path::new(&tmp).exists() {
        return Ok(());
    }
    if std::path::Path::new(path).exists() {
        fs::remove_file(&tmp) // cut before the old file was moved: the roll starts again
    } else {
        crate::util::rename_durable(&tmp, path) // cut after the old file was moved: finish it
    }
}

/// The file to read now: the ledger, or its finished replacement while a roll is half-way.
fn effective(path: &str) -> String {
    let tmp = format!("{}.new", path);
    if !std::path::Path::new(path).exists() && std::path::Path::new(&tmp).exists() {
        tmp
    } else {
        path.to_string()
    }
}

/// The first row of a new month moves the old file to its segment and starts a new one with a `carry` row (never losing a row:
/// the new file is written and flushed first, then two renames; a cut between them is finished by `recover_roll`).
fn roll_if_new_month(path: &str, now: u64) -> std::io::Result<bool> {
    let Some(last_t) = last_row_time(path) else { return Ok(false) };
    let (old_m, new_m) = (month_of(last_t), month_of(now));
    if new_m <= old_m {
        return Ok(false); // same month, or the clock went back: nothing to roll
    }
    let seg = segment_path(path, &old_m);
    if std::path::Path::new(&seg).exists() {
        return Ok(false); // a segment of that month is already there: never overwrite it
    }
    let Ok(sc) = scan(path) else { return Ok(false) }; // a changed ledger is left alone for the alert
    let rl = format!("{},{},{},0,carry", now, sc.last, sc.sum);
    let tmp = format!("{}.new", path);
    let _ = fs::remove_file(&tmp);
    let mut f = fs::File::create(&tmp)?;
    f.write_all(format!("{},{}\n", rl, chain(&sc.last, &rl)).as_bytes())?;
    f.sync_all()?;
    drop(f);
    crate::util::rename_durable(path, &seg)?;
    crate::util::rename_durable(&tmp, path)?;
    Ok(true)
}

fn last_row_time(path: &str) -> Option<u64> {
    let mut f = fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    f.seek(SeekFrom::Start(len.saturating_sub(512))).ok()?;
    let mut tail = Vec::new();
    f.read_to_end(&mut tail).ok()?;
    String::from_utf8_lossy(&tail)
        .lines()
        .rev()
        .find(|l| l.split(',').nth(5).is_some_and(hash_ok))
        .and_then(|l| l.split(',').next()?.parse().ok())
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
/// written on a new line of its own and chains from the last whole row. Reads the one current file, line by line.
pub fn verify_torn(path: &str) -> Result<(usize, u64, bool), usize> {
    scan(&effective(path)).map(|s| (s.n, s.sum, s.torn))
}

struct Scan {
    n: usize,
    sum: u64,
    torn: bool,
    /// The hash a leading `carry` row chains from (the previous segment's last hash).
    start_prev: Option<String>,
    /// The `pesos` of that carry row: the coin total of everything before this file.
    carry: Option<u64>,
    /// The hash of the last whole row ("0" for an empty file).
    last: String,
}

/// One pass over a file, a line at a time (no whole-file read, AO-03).
fn scan(path: &str) -> Result<Scan, usize> {
    let Ok(file) = fs::File::open(path) else {
        return Ok(Scan { n: 0, sum: 0, torn: false, start_prev: None, carry: None, last: "0".into() });
    };
    let mut r = std::io::BufReader::new(file);
    let mut s = Scan { n: 0, sum: 0, torn: false, start_prev: None, carry: None, last: "0".into() };
    let (mut first, mut buf) = (true, Vec::new());
    loop {
        buf.clear();
        if std::io::BufRead::read_until(&mut r, b'\n', &mut buf).unwrap_or(0) == 0 {
            break;
        }
        if buf.last() != Some(&b'\n') {
            s.torn = true; // the last line has no newline: a power failure cut it
            break;
        }
        s.n += 1;
        let text = String::from_utf8_lossy(&buf);
        let l = text.trim_end_matches(['\n', '\r']);
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 6 || !hash_ok(f[f.len() - 1]) {
            continue;
        }
        if first && f.get(4) == Some(&"carry") {
            s.start_prev = Some(f[1].to_string());
            s.carry = f[2].parse().ok();
            s.last = f[1].to_string();
        }
        first = false;
        let (t, h) = (f[..f.len() - 1].join(","), f[f.len() - 1]);
        if chain(&s.last, &t) != h {
            return Err(s.n);
        }
        s.last = h.to_string();
        // pesos from coins only: the box counts coins, so what the box did not count (a printed slip of an older release, sold for
        // cash elsewhere: kind "voucher") is not part of its reconciliation. A voucher bought with coins ("vbuy") is.
        if f.get(4) != Some(&"voucher") {
            s.sum += f[2].parse::<u64>().unwrap_or(0);
        }
    }
    Ok(s)
}

/// The closed monthly segments next to the ledger, oldest first.
pub fn closed_segments(path: &str) -> Vec<String> {
    let p = std::path::Path::new(path);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or("revenue");
    let ext = p.extension().and_then(|s| s.to_str()).unwrap_or("csv");
    let Ok(dir) = fs::read_dir(p.parent().unwrap_or(std::path::Path::new("."))) else { return Vec::new() };
    let mut v: Vec<String> = dir
        .filter_map(|e| e.ok())
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let mid = name.strip_prefix(&format!("{}-", stem))?.strip_suffix(&format!(".{}", ext))?;
            let ok = mid.len() == 7 && mid.as_bytes()[4] == b'-' && mid.bytes().enumerate().all(|(i, c)| i == 4 || c.is_ascii_digit());
            ok.then(|| e.path().to_string_lossy().into_owned())
        })
        .collect();
    v.sort();
    v
}

/// The whole chain across the closed segments and the current file: every file verifies, and each file's carry row names the last
/// hash and the coin total of the one before it. Ok((lines, pesos, torn tail)) or Err(1) when the files do not follow each other.
pub fn verify_all(path: &str) -> Result<(usize, u64, bool), usize> {
    let mut files = closed_segments(path);
    let cur = effective(path);
    if std::path::Path::new(&cur).exists() {
        files.push(cur);
    }
    let (mut prev, mut lines, mut torn): (Option<(String, u64)>, usize, bool) = (None, 0, false);
    for f in &files {
        let s = scan(f)?;
        match (&prev, &s.start_prev) {
            (Some((h, total)), Some(p)) if p == h && s.carry == Some(*total) => {}
            (None, _) => {}
            _ => return Err(1),
        }
        lines += s.n;
        torn = s.torn;
        prev = Some((s.last.clone(), s.sum));
    }
    Ok((lines, prev.map(|p| p.1).unwrap_or(0), torn))
}

/// The sales rows (carry rows left out) of the newest files, going back until a file reaches `since` (epoch seconds): what the
/// reports need, without reading a year of segments.
pub fn read_recent(path: &str, since: u64) -> String {
    let mut files = closed_segments(path);
    files.push(effective(path));
    let mut parts: Vec<String> = Vec::new();
    for f in files.iter().rev() {
        let Ok(text) = fs::read_to_string(f) else { continue };
        let rows: String = text.lines().filter(|l| l.split(',').nth(4) != Some("carry")).map(|l| format!("{}\n", l)).collect();
        let first_t = rows.lines().next().and_then(|l| l.split(',').next()?.parse::<u64>().ok()).unwrap_or(0);
        parts.push(rows);
        if first_t > 0 && first_t < since {
            break;
        }
    }
    parts.reverse();
    parts.concat()
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
    let (mut moved, mut first) = (0, true);
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 6 || !hash_ok(f[f.len() - 1]) {
            lines.push(l.to_string());
            continue;
        }
        if first && f[4] == "carry" {
            prev = f[1].to_string(); // a rolled file chains from the previous segment's last hash
        }
        first = false;
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
/// Which closed segments to delete to bring all ledger files inside `max_kb` (0 = no limit), oldest first, and whether that
/// reaches the budget. Deletes nothing.
pub fn prune_plan(path: &str, max_kb: u64) -> (Vec<String>, bool) {
    if max_kb == 0 {
        return (Vec::new(), true);
    }
    let len = |p: &str| fs::metadata(p).map(|m| m.len()).unwrap_or(0);
    let budget = max_kb * 1024;
    let segs = closed_segments(path);
    let mut total = len(&effective(path)) + segs.iter().map(|s| len(s)).sum::<u64>();
    let mut del = Vec::new();
    for s in segs {
        if total <= budget {
            break;
        }
        total -= len(&s);
        del.push(s);
    }
    (del, total <= budget)
}

/// One line of `revenue-pruned.txt`: a month deleted on purpose. `pesos` is the running coin total at the end of that month (what the
/// next file's carry row states) and `hash` its last row's hash.
#[derive(Debug, Clone, PartialEq)]
pub struct Pruned {
    pub month: String,
    pub rows: u64,
    pub pesos: u64,
    pub hash: String,
}

const PRUNED_MAX_LINES: usize = 240;

pub fn pruned_path(path: &str) -> String {
    let p = std::path::Path::new(path);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or("revenue");
    p.with_file_name(format!("{}-pruned.txt", stem)).to_string_lossy().into_owned()
}

/// The record of deleted months, oldest first. A missing file or a damaged line is skipped (it is a few KB at most: one line a month).
pub fn pruned_read(path: &str) -> Vec<Pruned> {
    let text = fs::read_to_string(pruned_path(path)).unwrap_or_default();
    text.lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split(',').collect();
            if f.len() != 4 || !hash_ok(f[3]) {
                return None;
            }
            Some(Pruned { month: f[0].to_string(), rows: f[1].parse().ok()?, pesos: f[2].parse().ok()?, hash: f[3].to_string() })
        })
        .collect()
}

fn pruned_line(p: &Pruned) -> String {
    format!("{},{},{},{}\n", p.month, p.rows, p.pesos, p.hash)
}

/// Add one deleted month to the record and flush it. Past 240 lines the oldest are folded into one `earlier` line (their rows added
/// up, the running total and hash of the newest of them), written whole and atomically.
fn pruned_append(path: &str, p: &Pruned) -> std::io::Result<()> {
    let rec = pruned_path(path);
    let mut all = pruned_read(path);
    if all.len() < PRUNED_MAX_LINES {
        let mut row = pruned_line(p);
        if !ends_with_newline(&rec) {
            row.insert(0, '\n'); // a line cut by a power failure: the new one starts on its own line
        }
        let mut f = fs::OpenOptions::new().create(true).append(true).open(&rec)?;
        f.write_all(row.as_bytes())?;
        return f.sync_all();
    }
    all.push(p.clone());
    let fold = all.len() - (PRUNED_MAX_LINES - 1);
    let rest = all.split_off(fold);
    let last = all.last().cloned().unwrap_or_else(|| p.clone());
    let summary = Pruned { month: "earlier".into(), rows: all.iter().map(|x| x.rows).sum(), pesos: last.pesos, hash: last.hash };
    let text: String = std::iter::once(&summary).chain(rest.iter()).map(pruned_line).collect();
    crate::util::write_atomic(&rec, text.as_bytes())
}

/// What a prune did: the segments it deleted, and whether the files are now inside the budget.
#[derive(Debug, PartialEq)]
pub struct PruneReport {
    pub deleted: Vec<String>,
    pub reached: bool,
}

/// `(last hash, running total)` named by the carry row that starts a file, if it starts with one. Reads one line.
fn first_carry(path: &str) -> Option<(String, u64)> {
    let mut line = String::new();
    std::io::BufRead::read_line(&mut std::io::BufReader::new(fs::File::open(path).ok()?), &mut line).ok()?;
    let f: Vec<&str> = line.trim_end().split(',').collect();
    (f.len() == 6 && f[4] == "carry").then(|| Some((f[1].to_string(), f[2].parse().ok()?)))?
}

/// Delete the oldest closed months until the files are inside the budget. Each month is written to `revenue-pruned.txt` and flushed
/// first, then deleted, so a power cut at any point leaves a ledger that verifies (a recorded month whose file is still there is
/// finished by the next prune). A segment that does not verify, or that the next file does not follow, is left alone: that is for the
/// alert, not for deletion. Streams each file; reads no file whole (AO-03).
fn prune(path: &str, budget: &Budget) -> std::io::Result<PruneReport> {
    let mut report = PruneReport { deleted: Vec::new(), reached: true };
    let (plan, _) = prune_plan(path, budget.max_kb);
    let all = closed_segments(path);
    for seg in plan {
        let Ok(s) = scan(&seg) else { break };
        let next = all.iter().position(|x| *x == seg).and_then(|i| all.get(i + 1)).cloned().unwrap_or_else(|| effective(path));
        if first_carry(&next) != Some((s.last.clone(), s.sum)) {
            break;
        }
        let month = seg.strip_suffix(".csv").and_then(|x| x.get(x.len().saturating_sub(7)..)).unwrap_or("").to_string();
        if pruned_read(path).last().map(|p| p.month != month).unwrap_or(true) {
            let rows = (s.n - usize::from(s.start_prev.is_some())) as u64;
            pruned_append(path, &Pruned { month, rows, pesos: s.sum, hash: s.last })?;
        }
        fs::remove_file(&seg)?;
        report.deleted.push(seg);
    }
    report.reached = prune_plan(path, budget.max_kb).1;
    Ok(report)
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

    // ---- AO-02 / AO-03: monthly segments -------------------------------------------------------------------------------------
    const DAY: u64 = 86400;
    /// 2026-08-10 12:00 UTC; 31 days later is 2026-09-10, then 2026-10-11: three different months.
    const AUG: u64 = 1_786_363_200;
    fn at(month_offset: u64, n: u64) -> u64 {
        AUG + month_offset * 31 * DAY + n * 60
    }
    fn tmpdir(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-roll-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.join("revenue.csv").to_string_lossy().into_owned()
    }
    /// The same rows into a rolling ledger and into one big file that never rolls (what every release before this one did).
    fn two_ledgers(name: &str, months: u64, per_month: u64) -> (String, String) {
        let (a, b) = (tmpdir(&format!("{}-a", name)), tmpdir(&format!("{}-b", name)));
        for m in 0..months {
            for n in 0..per_month {
                let (t, pesos) = (at(m, n), (n % 5 + 1) as u32);
                append(&a, t, "hyper", pesos, pesos * 6, if n == 0 { "new" } else { "topup" }).unwrap();
                append_row(&b, t, "hyper", pesos, pesos * 6, if n == 0 { "new" } else { "topup" }).unwrap();
            }
        }
        (a, b)
    }
    fn fields(text: &str) -> Vec<String> {
        text.lines().filter(|l| l.split(',').nth(4) != Some("carry")).map(|l| l.rsplit_once(',').unwrap().0.to_string()).collect()
    }

    #[test]
    fn ao02_three_months_roll_into_segments_and_equal_one_big_file() {
        let (a, b) = two_ledgers("three", 3, 40);
        let segs = closed_segments(&a);
        assert_eq!(segs.len(), 2, "two closed months and the current one: {:?}", segs);
        assert!(segs[0].ends_with("revenue-2026-08.csv") && segs[1].ends_with("revenue-2026-09.csv"), "{:?}", segs);
        let (whole_lines, whole_sum, _) = verify_torn(&b).unwrap();
        assert_eq!(whole_lines, 120);
        let (lines, sum, torn) = verify_all(&a).unwrap();
        assert_eq!((sum, torn), (whole_sum, false), "the coin total across the segments is the one big file's total");
        assert_eq!(lines, 120 + 2, "the same rows plus one carry row for each roll");
        // the current file alone verifies (the cheap hourly check) and carries the total of the months before it
        let (cur_lines, cur_sum) = verify(&a).unwrap();
        assert_eq!((cur_lines, cur_sum), (41, whole_sum), "one carry row and this month's 40 rows; the sum starts from the carry");
        // every real row is there, in order, with the same fields (only the hashes differ: the carry rows sit in the chain)
        let all = read_recent(&a, 0);
        assert_eq!(fields(&all), fields(&fs::read_to_string(&b).unwrap()));
        // the carry row names the old file's last hash and its running total
        let first = fs::read_to_string(&a).unwrap();
        let carry: Vec<&str> = first.lines().next().unwrap().split(',').collect();
        let old = fs::read_to_string(&segs[1]).unwrap();
        let old_last = old.lines().last().unwrap().rsplit(',').next().unwrap();
        assert_eq!((carry[4], carry[1]), ("carry", old_last));
        assert_eq!(carry[2].parse::<u64>().unwrap(), verify_all(&segs[1]).unwrap().1);
    }

    #[test]
    fn ao02_a_same_month_or_an_earlier_clock_never_rolls() {
        let a = tmpdir("same");
        append(&a, at(0, 0), "hyper", 5, 30, "new").unwrap();
        append(&a, at(0, 9) + 20 * DAY, "hyper", 1, 6, "topup").unwrap(); // later in the same month
        append(&a, at(0, 1), "hyper", 1, 6, "topup").unwrap(); // the clock went back
        assert!(closed_segments(&a).is_empty());
        assert_eq!(verify(&a), Ok((3, 7)));
    }

    #[test]
    fn ao02_a_torn_row_at_the_roll_stays_in_the_old_segment_and_nothing_is_lost() {
        let a = tmpdir("torn");
        append(&a, at(0, 0), "hyper", 5, 30, "new").unwrap();
        append(&a, at(0, 1), "hyper", 3, 18, "topup").unwrap();
        let mut f = fs::OpenOptions::new().append(true).open(&a).unwrap();
        write!(f, "{},hyper,9,54,top", at(0, 2)).unwrap(); // the power went in the middle of this row
        drop(f);
        append(&a, at(1, 0), "hyper", 2, 12, "new").unwrap();
        let seg = &closed_segments(&a)[0];
        assert_eq!(verify_torn(seg).map(|v| (v.1, v.2)), Ok((8, true)), "the fragment is the old segment's torn tail, not a row");
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(10), "8 pesos before, 2 after: the torn row never counted");
    }

    #[test]
    fn ao02_a_power_cut_between_the_move_and_the_new_file_is_finished_by_the_next_row() {
        let a = tmpdir("cut-after-move");
        append(&a, at(0, 0), "hyper", 5, 30, "new").unwrap();
        append(&a, at(0, 1), "hyper", 3, 18, "topup").unwrap();
        // what the roll had done when the power went: the new file written and flushed, the old one moved, the new one not yet in place
        let (old_last, total) = (last_hash(&a).unwrap(), verify(&a).unwrap().1);
        let rl = format!("{},{},{},0,carry", at(1, 0), old_last, total);
        fs::write(format!("{}.new", a), format!("{},{}\n", rl, chain(&old_last, &rl))).unwrap();
        crate::util::rename_durable(&a, &segment_path(&a, "2026-08")).unwrap();
        assert!(!std::path::Path::new(&a).exists());
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(8), "readers see the finished file while the roll is half done");
        append(&a, at(1, 5), "hyper", 2, 12, "topup").unwrap(); // the next sale finishes the roll, then is written
        assert!(!std::path::Path::new(&format!("{}.new", a)).exists());
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(10));
        assert_eq!(verify(&a), Ok((2, 10)), "the carry row and the new sale");
    }

    #[test]
    fn ao02_a_power_cut_before_the_old_file_moved_starts_the_roll_again() {
        let a = tmpdir("cut-before-move");
        append(&a, at(0, 0), "hyper", 5, 30, "new").unwrap();
        fs::write(format!("{}.new", a), "garbage that never became the new file\n").unwrap();
        append(&a, at(1, 0), "hyper", 2, 12, "new").unwrap();
        assert_eq!(closed_segments(&a).len(), 1);
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(7));
        assert!(!std::path::Path::new(&format!("{}.new", a)).exists());
    }

    #[test]
    fn ao02_an_edited_closed_segment_or_a_missing_one_is_found() {
        let (a, _b) = two_ledgers("tamper", 3, 10);
        let segs = closed_segments(&a);
        let t = fs::read_to_string(&segs[0]).unwrap();
        let first_row_pesos = t.lines().next().unwrap().split(',').nth(2).unwrap().to_string();
        let edited = t.replacen(&format!(",{},", first_row_pesos), ",99,", 1);
        fs::write(&segs[0], edited).unwrap();
        assert_eq!(verify_all(&a), Err(1), "a closed month that was edited");
        fs::write(&segs[0], t).unwrap();
        assert!(verify_all(&a).is_ok());
        fs::remove_file(&segs[1]).unwrap();
        assert_eq!(verify_all(&a), Err(1), "a month removed from the middle breaks the chain");
    }

    #[test]
    fn ao02_the_oldest_months_can_be_removed_by_the_owner() {
        // D1: the owner downloads the oldest months and deletes them; what remains still verifies and keeps the total
        let (a, b) = two_ledgers("oldest", 3, 10);
        fs::remove_file(&closed_segments(&a)[0]).unwrap();
        assert_eq!(verify_all(&a).map(|v| v.1), verify_torn(&b).map(|v| v.1));
    }

    #[test]
    fn ao02_the_clock_jump_shift_keeps_a_rolled_file_valid() {
        let (a, _b) = two_ledgers("shift", 2, 5);
        let before = verify_all(&a).unwrap().1;
        assert!(shift(&a, at(1, 0), u64::MAX, 3600) > 0);
        assert_eq!(verify(&a).map(|v| v.1), Ok(before));
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(before));
    }

    #[test]
    fn ao03_a_hundred_thousand_rows_verify_by_streaming() {
        let a = tmpdir("big");
        let mut text = String::new();
        let mut prev = "0".to_string();
        for i in 0..100_000u64 {
            let rl = format!("{},hyper,{},{},topup", 1_700_000_000 + i, i % 5 + 1, (i % 5 + 1) * 6);
            let h = chain(&prev, &rl);
            text.push_str(&format!("{},{}\n", rl, h));
            prev = h;
        }
        fs::write(&a, &text).unwrap();
        let t0 = std::time::Instant::now();
        let r = verify(&a).unwrap();
        let ms = t0.elapsed().as_millis();
        assert_eq!(r.0, 100_000);
        assert_eq!(r.1, (0..100_000u64).map(|i| i % 5 + 1).sum::<u64>());
        // (this is a debug build; the release build does it in a few tens of milliseconds)
        assert!(ms < 5000, "100,000 rows took {} ms", ms);
        println!("100,000 rows verified in {} ms (debug build)", ms);
    }

    // ---- feature 032: the size budget ------------------------------------------------------------------------------------------
    fn size_of(p: &str) -> u64 {
        fs::metadata(p).map(|m| m.len()).unwrap_or(0)
    }
    fn all_size(a: &str) -> u64 {
        closed_segments(a).iter().map(|s| size_of(s)).sum::<u64>() + size_of(a)
    }

    #[test]
    fn f032_g1_the_oldest_months_are_picked_until_the_budget_is_reached() {
        let (a, _b) = two_ledgers("g1-budget", 6, 100); // about 4.5 KB a month
        let segs = closed_segments(&a);
        let (del, reached) = prune_plan(&a, 12);
        assert!(reached);
        assert!(!del.is_empty() && del.len() < segs.len(), "some but not all: {:?}", del);
        assert_eq!(del, segs[..del.len()].to_vec(), "oldest first, from the front");
        let left: u64 = all_size(&a) - del.iter().map(|s| size_of(s)).sum::<u64>();
        assert!(left <= 12 * 1024, "{} bytes left", left);
        assert_eq!(prune_plan(&a, 12), (del, true), "it deletes nothing: the same answer twice");
    }

    #[test]
    fn f032_g1_a_busy_shop_is_pruned_below_the_months_it_wanted() {
        let (a, _b) = two_ledgers("g1-busy", 6, 100);
        let segs = closed_segments(&a);
        let (del, reached) = prune_plan(&a, 5); // one month alone is about 4.5 KB: only the current file and one more fit
        assert!(reached && segs.len() - del.len() < 3, "fewer than 3 months kept, the budget wins (D2): kept {}", segs.len() - del.len());
    }

    #[test]
    fn f032_g1_zero_means_no_limit() {
        let (a, _b) = two_ledgers("g1-zero", 4, 100);
        assert_eq!(prune_plan(&a, 0), (Vec::new(), true));
    }

    #[test]
    fn f032_g1_inside_the_budget_deletes_nothing() {
        let (a, _b) = two_ledgers("g1-inside", 4, 20);
        assert_eq!(prune_plan(&a, 1024), (Vec::new(), true));
    }

    #[test]
    fn f032_g1_only_a_current_file_has_nothing_to_delete() {
        let a = tmpdir("g1-only");
        for n in 0..100 {
            append(&a, at(0, n), "hyper", 1, 6, "new").unwrap();
        }
        assert_eq!(prune_plan(&a, 1), (Vec::new(), false), "over the budget and nothing to delete: reported, not an error");
    }

    #[test]
    fn f032_g1_a_current_file_over_the_budget_by_itself_deletes_every_closed_segment() {
        let (a, _b) = two_ledgers("g1-cur", 3, 100);
        let segs = closed_segments(&a);
        let (del, reached) = prune_plan(&a, 1); // 1 KB: the current month alone is more
        assert_eq!((del, reached), (segs, false));
        assert!(std::path::Path::new(&a).exists(), "revenue.csv is never in the list");
    }

    // ---- feature 032 group 2: the record of deleted months -----------------------------------------------------------------------
    fn pr(i: u64) -> Pruned {
        Pruned { month: format!("{}-{:02}", 2000 + i / 12, i % 12 + 1), rows: 100 + i, pesos: 1000 * (i + 1), hash: format!("{:012x}", i) }
    }

    #[test]
    fn f032_g2_a_deleted_month_is_written_down_and_read_back() {
        let a = tmpdir("g2-rw");
        assert!(pruned_read(&a).is_empty(), "no file yet: nothing pruned");
        pruned_append(&a, &pr(0)).unwrap();
        pruned_append(&a, &pr(1)).unwrap();
        assert_eq!(pruned_read(&a), vec![pr(0), pr(1)]);
        let text = fs::read_to_string(pruned_path(&a)).unwrap();
        assert!(text.starts_with("2000-01,100,1000,000000000000\n"), "{}", text);
        assert!(pruned_path(&a).ends_with("revenue-pruned.txt"));
        assert!(closed_segments(&a).is_empty(), "the record is not mistaken for a segment");
    }

    #[test]
    fn f032_g2_a_line_cut_by_a_power_failure_is_skipped_and_the_next_starts_on_its_own_line() {
        let a = tmpdir("g2-torn");
        pruned_append(&a, &pr(0)).unwrap();
        let mut f = fs::OpenOptions::new().append(true).open(pruned_path(&a)).unwrap();
        write!(f, "2000-02,10").unwrap();
        drop(f);
        assert_eq!(pruned_read(&a), vec![pr(0)]);
        pruned_append(&a, &pr(2)).unwrap();
        assert_eq!(pruned_read(&a), vec![pr(0), pr(2)]);
    }

    #[test]
    fn f032_g2_thirty_years_stay_at_240_lines_and_the_summary_keeps_the_count_and_total() {
        let a = tmpdir("g2-cap");
        let months = 30 * 12;
        for i in 0..months {
            pruned_append(&a, &pr(i)).unwrap();
        }
        let text = fs::read_to_string(pruned_path(&a)).unwrap();
        assert!(text.lines().count() <= PRUNED_MAX_LINES, "{} lines", text.lines().count());
        assert!(text.len() < 12 * 1024, "{} bytes", text.len());
        let got = pruned_read(&a);
        assert_eq!(got[0].month, "earlier");
        assert_eq!(got.iter().map(|p| p.rows).sum::<u64>(), (0..months).map(|i| 100 + i).sum::<u64>(), "no deleted row is forgotten");
        let kept = got.len() as u64 - 1;
        let last_dropped = months - kept - 1;
        assert_eq!((got[0].pesos, got[0].hash.clone()), (pr(last_dropped).pesos, pr(last_dropped).hash), "total and hash of the newest folded month");
        assert_eq!(*got.last().unwrap(), pr(months - 1));
        assert_eq!(&got[1..], &(months - kept..months).map(pr).collect::<Vec<_>>()[..]);
    }

    // ---- feature 032 group 3: the prune at the month roll ------------------------------------------------------------------------
    fn busy(a: &str, b: &Budget, months: u64, per_month: u64) {
        for m in 0..months {
            for n in 0..per_month {
                let pesos = (n % 5 + 1) as u32;
                append_with(a, at(m, n), "hyper", pesos, pesos * 6, "topup", b).unwrap();
            }
        }
    }

    #[test]
    fn f032_g3_24_months_are_pruned_to_the_budget_and_keep_at_least_three() {
        let a = tmpdir("g3-24");
        let b = Budget { max_kb: 1024, keep_months: 3 };
        busy(&a, &b, 23, 2000);
        append_with(&a, at(23, 0), "hyper", 1, 6, "topup", &b).unwrap(); // the first sale of month 24: the roll, then the prune
        assert!(all_size(&a) <= 1024 * 1024, "{} bytes", all_size(&a));
        assert!(closed_segments(&a).len() >= 3, "{:?}", closed_segments(&a));
        assert!(pruned_read(&a).len() >= 10, "months were really pruned: {}", pruned_read(&a).len());
        assert_eq!(verify(&a).map(|v| v.0), Ok(2), "revenue.csv is the new month: a carry row and the sale");
    }

    #[test]
    fn f032_g3_the_ledger_never_holds_more_than_the_budget_plus_one_month() {
        let a = tmpdir("g3-peak");
        let b = Budget { max_kb: 128, keep_months: 3 };
        let month = 2000 * 45;
        for m in 0..10 {
            busy_month(&a, &b, m, 2000);
            assert!(all_size(&a) <= 128 * 1024 + month + 4096, "month {}: {} bytes", m, all_size(&a));
        }
    }
    fn busy_month(a: &str, b: &Budget, m: u64, per_month: u64) {
        for n in 0..per_month {
            let pesos = (n % 5 + 1) as u32;
            append_with(a, at(m, n), "hyper", pesos, pesos * 6, "topup", b).unwrap();
        }
    }

    #[test]
    fn f032_g3_a_power_cut_between_the_record_and_the_delete_is_finished_by_the_next_roll() {
        let a = tmpdir("g3-cut");
        let b = Budget { max_kb: 10, keep_months: 3 };
        busy(&a, &Budget { max_kb: 0, keep_months: 3 }, 4, 100); // 3 closed months, none pruned
        let oldest = closed_segments(&a)[0].clone();
        let s = scan(&oldest).unwrap();
        // the record is written and flushed, then the power goes: the file is still there
        pruned_append(&a, &Pruned { month: "2026-08".into(), rows: s.n as u64, pesos: s.sum, hash: s.last.clone() }).unwrap();
        assert!(std::path::Path::new(&oldest).exists());
        assert!(verify_all(&a).is_ok(), "a recorded month whose file is still there is no error");
        append_with(&a, at(4, 0), "hyper", 1, 6, "topup", &b).unwrap();
        assert!(!std::path::Path::new(&oldest).exists(), "the next roll finishes the prune");
        let months: Vec<String> = pruned_read(&a).into_iter().map(|p| p.month).collect();
        assert_eq!(months.iter().filter(|m| *m == "2026-08").count(), 1, "the finished prune does not write the month twice: {:?}", months);
        assert!(verify_all(&a).is_ok());
    }

    #[test]
    fn f032_g3_zero_deletes_nothing_and_a_busy_shop_is_pruned_below_three_months() {
        let a = tmpdir("g3-zero");
        busy(&a, &Budget { max_kb: 0, keep_months: 3 }, 6, 200);
        assert_eq!(closed_segments(&a).len(), 5);
        assert!(!std::path::Path::new(&pruned_path(&a)).exists());
        let b = tmpdir("g3-busy");
        let bud = Budget { max_kb: 14, keep_months: 3 }; // about 9 KB a month: 3 months do not fit
        busy(&b, &bud, 6, 200);
        append_with(&b, at(6, 0), "hyper", 1, 6, "topup", &bud).unwrap();
        assert!(closed_segments(&b).len() < 3, "the budget wins over the months wanted (D2): {}", closed_segments(&b).len());
        assert!(all_size(&b) <= 14 * 1024);
    }

    #[test]
    fn f032_g3_a_prune_that_fails_never_stops_the_sale_row() {
        let a = tmpdir("g3-fail");
        let b = Budget { max_kb: 5, keep_months: 3 };
        busy(&a, &Budget { max_kb: 0, keep_months: 3 }, 4, 100);
        fs::create_dir(pruned_path(&a)).unwrap(); // the record cannot be written: a directory sits where the file should be
        let before = verify_all(&a).unwrap().1;
        append_with(&a, at(4, 0), "hyper", 7, 42, "topup", &b).unwrap();
        assert_eq!(closed_segments(&a).len(), 4, "nothing was deleted without a record");
        assert_eq!(verify_all(&a).map(|v| v.1), Ok(before + 7), "and the sale was written");
    }
}
