//! The roll: one line per customer device with paid time (the file format of the earlier shell portal, so the data of an
//! installed router carries over): code,rate_down,rate_up,quota_down,quota_up,time_limit_min,first_punched,mac,pauses_used,
//! paused_at,remaining_at_pause,plan,wid,pesos,wmin,wpesos,wfinal. Expiry is always first_punched + time_limit * 60.
//! Written atomically (temporary file in the same directory, then rename); the caller holds the process-wide lock.
use crate::pricing::Plan;
use crate::util::random_hex;
use std::fs;
use std::io::Write;

#[derive(Clone, Debug, PartialEq)]
pub struct Entry {
    pub code: String,
    pub down: u32,
    pub up: u32,
    pub qdown: u64,
    pub qup: u64,
    pub tl: u64,
    pub fp: u64,
    pub mac: String,
    pub pu: u32,
    pub pa: u64,
    pub rp: u64,
    pub plan: String,
    pub wid: String,
    pub pesos: u32,
    pub wmin: u32,
    pub wp: u32,
    pub wf: u32,
}

impl Entry {
    pub fn parse(line: &str) -> Option<Entry> {
        let f: Vec<&str> = line.trim_end_matches(['\r', '\n']).split(',').collect();
        if f.len() < 12 || f[0].is_empty() {
            return None;
        }
        let n = |i: usize| -> u64 { f.get(i).and_then(|v| v.parse().ok()).unwrap_or(0) };
        let s = |i: usize| -> String { f.get(i).map(|v| v.to_string()).unwrap_or_default() };
        Some(Entry {
            code: s(0),
            down: n(1) as u32,
            up: n(2) as u32,
            qdown: n(3),
            qup: n(4),
            tl: n(5),
            fp: n(6),
            mac: s(7).to_lowercase(),
            pu: n(8) as u32,
            pa: n(9),
            rp: n(10),
            plan: if f.get(11).copied().unwrap_or("") == "endurance" { "endurance".into() } else { "hyper".into() },
            wid: s(12),
            pesos: n(13) as u32,
            wmin: n(14) as u32,
            wp: n(15) as u32,
            wf: if f.len() > 16 { n(16) as u32 } else { 1 },
        })
    }

    pub fn line(&self) -> String {
        format!(
            "{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{}",
            self.code,
            self.down,
            self.up,
            self.qdown,
            self.qup,
            self.tl,
            self.fp,
            self.mac,
            self.pu,
            self.pa,
            self.rp,
            self.plan,
            self.wid,
            self.pesos,
            self.wmin,
            self.wp,
            self.wf
        )
    }

    pub fn end(&self) -> u64 {
        self.fp + self.tl * 60
    }

    /// Seconds of paid time left. A clock that jumped back can never add time: never more than the minutes bought.
    pub fn left(&self, now: u64) -> u64 {
        let cap = self.tl * 60;
        let left = if self.pa != 0 { self.rp } else { self.end().saturating_sub(now) };
        left.min(cap)
    }

    pub fn plan(&self) -> Plan {
        Plan::parse(&self.plan).unwrap_or(Plan::Hyper)
    }
}

pub fn load(path: &str) -> Vec<Entry> {
    fs::read_to_string(path).map(|t| t.lines().filter_map(Entry::parse).collect()).unwrap_or_default()
}

pub fn save(path: &str, entries: &[Entry]) -> std::io::Result<()> {
    if let Some(dir) = std::path::Path::new(path).parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = format!("{}.tmp", path);
    {
        let mut f = fs::File::create(&tmp)?;
        for e in entries {
            writeln!(f, "{}", e.line())?;
        }
        f.sync_all().ok();
    }
    fs::rename(&tmp, path)?;
    // the rename itself must reach the flash before a payment is reported as recorded
    if let Some(dir) = std::path::Path::new(path).parent() {
        if let Ok(d) = fs::File::open(dir) {
            d.sync_all().ok();
        }
    }
    Ok(())
}

pub fn find<'a>(entries: &'a [Entry], mac: &str) -> Option<&'a Entry> {
    let m = mac.to_lowercase();
    entries.iter().find(|e| e.mac == m)
}

fn new_code(entries: &[Entry]) -> String {
    loop {
        let r: String = random_hex(16).bytes().filter(|c| c.is_ascii_alphanumeric()).take(8).map(|c| c as char).collect();
        if r.len() == 8 {
            let c = format!("{}-{}", &r[..4], &r[4..]);
            if !entries.iter().any(|e| e.code == c) {
                return c;
            }
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum Mode {
    New,
    TopUp,
    Switch,
    /// the same window was recorded before: nothing changes
    Dup,
}

impl Mode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Mode::New => "new",
            Mode::TopUp => "topup",
            Mode::Switch => "switch",
            Mode::Dup => "dup",
        }
    }
}

#[derive(Debug, PartialEq)]
pub enum MintError {
    /// the device has live time on the other plan and did not agree to give it up
    Mismatch {
        plan: Plan,
        left: u64,
    },
    Io,
}

/// Record a verified, closed coin window: `pulses` and `minutes` are the window's whole total. The same window id is never
/// credited twice. Returns the mode (for the ledger).
#[allow(clippy::too_many_arguments)]
pub fn mint(
    path: &str,
    now: u64,
    mac: &str,
    wid: &str,
    plan: Plan,
    pulses: u32,
    minutes: u32,
    up: u32,
    down: u32,
    forfeit: bool,
) -> Result<Mode, MintError> {
    let mac = mac.to_lowercase();
    let mut entries = load(path);
    let mut mode = Mode::New;
    if let Some(i) = entries.iter().position(|e| e.mac == mac) {
        let e = entries[i].clone();
        if !wid.is_empty() && e.wid == wid && e.wf == 1 {
            return Ok(Mode::Dup);
        }
        if e.left(now) > 0 {
            if e.plan() != plan {
                if !forfeit {
                    return Err(MintError::Mismatch { plan: e.plan(), left: e.left(now) });
                }
                entries.remove(i);
                mode = Mode::Switch;
            } else {
                mode = Mode::TopUp;
            }
        } else {
            entries.remove(i); // ran out: this payment starts a new session
        }
    }
    if mode == Mode::TopUp {
        let e = entries.iter_mut().find(|e| e.mac == mac).expect("found above");
        e.tl += minutes as u64;
        e.pesos += pulses;
        e.wid = wid.to_string();
        if e.pa != 0 {
            e.rp += minutes as u64 * 60;
        }
        e.wmin = minutes;
        e.wp = pulses;
        e.wf = 1;
    } else {
        let code = new_code(&entries);
        entries.push(Entry {
            code,
            down,
            up,
            qdown: 0,
            qup: 0,
            tl: minutes as u64,
            fp: now,
            mac: mac.clone(),
            pu: 0,
            pa: 0,
            rp: 0,
            plan: plan.as_str().to_string(),
            wid: wid.to_string(),
            pesos: pulses,
            wmin: minutes,
            wp: pulses,
            wf: 1,
        });
    }
    save(path, &entries).map_err(|_| MintError::Io)?;
    Ok(mode)
}

/// What a device may use right now (a session paused by an earlier version is resumed). None when it has no time left;
/// a line that ran out is deleted.
#[derive(Debug, Clone)]
pub struct Session {
    pub minutes: u64,
    pub left_secs: u64,
    pub up: u32,
    pub down: u32,
    pub qup: u64,
    pub qdown: u64,
    pub plan: Plan,
}

pub fn session(path: &str, now: u64, mac: &str) -> Option<Session> {
    let mac = mac.to_lowercase();
    let mut entries = load(path);
    let i = entries.iter().position(|e| e.mac == mac)?;
    if entries[i].left(now) == 0 {
        entries.remove(i);
        let _ = save(path, &entries);
        return None;
    }
    if entries[i].pa != 0 {
        entries[i].fp += now.saturating_sub(entries[i].pa);
        entries[i].pa = 0;
        entries[i].rp = 0;
        let _ = save(path, &entries);
    }
    let e = &entries[i];
    let left = e.left(now);
    Some(Session { minutes: left.div_ceil(60), left_secs: left, up: e.up, down: e.down, qup: e.qup, qdown: e.qdown, plan: e.plan() })
}

/// Read-only: seconds left and plan, if any.
pub fn peek(path: &str, now: u64, mac: &str) -> Option<(Plan, u64)> {
    let entries = load(path);
    let e = find(&entries, mac)?;
    let l = e.left(now);
    (l > 0).then(|| (e.plan(), l))
}

/// Delete sessions that ran out more than two days ago.
pub fn purge(path: &str, now: u64) {
    let entries = load(path);
    let keep: Vec<Entry> = entries.iter().filter(|e| e.pa != 0 || e.end() + 172_800 >= now).cloned().collect();
    if keep.len() != entries.len() {
        let _ = save(path, &keep);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-roll-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.join("vouchers.txt").to_string_lossy().into_owned()
    }

    #[test]
    fn new_topup_dup_switch_and_expiry() {
        let p = tmp("a");
        let mac = "aa:bb:cc:00:00:01";
        assert_eq!(mint(&p, 1000, mac, "w1", Plan::Endurance, 17, 690, 2000, 5000, false), Ok(Mode::New));
        let e = find(&load(&p), mac).unwrap().clone();
        assert_eq!((e.tl, e.pesos, e.wf, e.fp), (690, 17, 1, 1000));
        assert_eq!(mint(&p, 1010, mac, "w1", Plan::Endurance, 17, 690, 2000, 5000, false), Ok(Mode::Dup)); // never twice
        assert_eq!(find(&load(&p), mac).unwrap().tl, 690);
        assert_eq!(mint(&p, 1020, mac, "w2", Plan::Endurance, 2, 30, 2000, 5000, false), Ok(Mode::TopUp));
        assert_eq!(find(&load(&p), mac).unwrap().tl, 720);
        assert_eq!(
            mint(&p, 1030, mac, "w3", Plan::Hyper, 5, 30, 0, 0, false),
            Err(MintError::Mismatch { plan: Plan::Endurance, left: 720 * 60 - 30 })
        );
        assert_eq!(mint(&p, 1030, mac, "w3", Plan::Hyper, 5, 30, 0, 0, true), Ok(Mode::Switch));
        let e = find(&load(&p), mac).unwrap().clone();
        assert_eq!((e.plan.as_str(), e.tl, e.pesos), ("hyper", 30, 5));
        // it ran out: a new payment starts a new session
        assert_eq!(mint(&p, 1030 + 30 * 60 + 1, mac, "w4", Plan::Endurance, 1, 15, 2000, 5000, false), Ok(Mode::New));
        assert_eq!(find(&load(&p), mac).unwrap().plan, "endurance");
    }

    #[test]
    fn a_clock_that_jumps_back_cannot_add_time() {
        let mut e = Entry::parse("ab12-cd34,0,0,0,0,10,5000,aa:bb:cc:00:00:01,0,0,0,hyper,w,1,10,1,1").unwrap();
        assert_eq!(e.left(5000 + 60), 540);
        assert_eq!(e.left(1000), 600); // the clock went back: at most what was bought
        e.pa = 0;
        assert_eq!(e.left(5000 + 700), 0);
    }

    #[test]
    fn session_and_purge() {
        let p = tmp("b");
        let mac = "aa:bb:cc:00:00:02";
        mint(&p, 100, mac, "w", Plan::Hyper, 5, 30, 0, 0, false).unwrap();
        let s = session(&p, 160, mac).unwrap();
        assert_eq!((s.minutes, s.left_secs), (29, 30 * 60 - 60));
        assert!(session(&p, 100 + 30 * 60 + 1, mac).is_none()); // ran out: deleted
        assert!(load(&p).is_empty());
        mint(&p, 100, mac, "w2", Plan::Hyper, 5, 30, 0, 0, false).unwrap();
        purge(&p, 100 + 30 * 60 + 172_800 + 1);
        assert!(load(&p).is_empty());
    }
}
