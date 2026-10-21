//! The roll: one line per customer device with paid time (the file format of the earlier shell portal, so the data of an
//! installed router carries over): code,rate_down,rate_up,quota_down,quota_up,time_limit_min,first_punched,mac,pauses_used,
//! paused_at,remaining_at_pause,plan,wid,pesos,wmin,wpesos,wfinal. Expiry is always first_punched + time_limit * 60.
//! Written atomically (temporary file in the same directory, then rename); the caller holds the process-wide lock.
use crate::pricing::Plan;
use crate::util::random_hex;
use std::fs;

/// How long a pause may last: time still unused after that is lost.
pub const PAUSE_MAX_SECS: u64 = 86_400;

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
    /// Paused time is kept for PAUSE_MAX_SECS, then it is lost.
    pub fn left(&self, now: u64) -> u64 {
        let cap = self.tl * 60;
        let left = if self.pa != 0 {
            if now > self.pa + PAUSE_MAX_SECS {
                0
            } else {
                self.rp
            }
        } else {
            self.end().saturating_sub(now)
        };
        left.min(cap)
    }

    pub fn paused(&self) -> bool {
        self.pa != 0
    }

    pub fn plan(&self) -> Plan {
        Plan::parse(&self.plan).unwrap_or(Plan::Hyper)
    }
}

/// The sessions on file. Read as bytes and decoded leniently: one damaged byte used to make the whole roll read as empty, and the
/// next payment then rewrote it with one line, ending every other customer's session.
pub fn load(path: &str) -> Vec<Entry> {
    fs::read(path).map(|b| String::from_utf8_lossy(&b).lines().filter_map(Entry::parse).collect()).unwrap_or_default()
}

pub fn save(path: &str, entries: &[Entry]) -> std::io::Result<()> {
    // the file and its folder are synced before a payment is reported as recorded
    let text: String = entries.iter().map(|e| format!("{}\n", e.line())).collect();
    crate::util::write_atomic(path, text.as_bytes())
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

/// What a device may use right now. A paused session is RESUMED by this call (paying more coins resumes it; so does the
/// explicit resume). Code that only wants to put a device back online uses `session_active`, which leaves a paused one alone.
/// None when it has no time left. A line that ran out is NOT deleted here (RT-01): a read must not end a paid session just
/// because the clock is not right yet. Only `purge` (two days later) and the next payment (`mint`) remove it.
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

/// Like `session`, but a paused device has no active session (None) and is left paused.
pub fn session_active(path: &str, now: u64, mac: &str) -> Option<Session> {
    let entries = load(path);
    if find(&entries, mac).is_some_and(|e| e.paused() && e.left(now) > 0) {
        return None;
    }
    session(path, now, mac)
}

/// Read-only: seconds left and plan, if any.
pub fn peek(path: &str, now: u64, mac: &str) -> Option<(Plan, u64)> {
    let entries = load(path);
    let e = find(&entries, mac)?;
    let l = e.left(now);
    (l > 0).then(|| (e.plan(), l))
}

/// Read-only: the same with whether the time is paused and how many pauses were used.
#[derive(Debug, Clone, PartialEq)]
pub struct Peeked {
    pub plan: Plan,
    pub left: u64,
    pub paused: bool,
    pub pauses_used: u32,
    /// pesos paid into this session so far
    pub pesos: u32,
    /// when the paused time is lost (0 when not paused)
    pub lost_at: u64,
}

pub fn peek_state(path: &str, now: u64, mac: &str) -> Option<Peeked> {
    let entries = load(path);
    let e = find(&entries, mac)?;
    let l = e.left(now);
    (l > 0).then(|| Peeked {
        plan: e.plan(),
        left: l,
        paused: e.paused(),
        pauses_used: e.pu,
        pesos: e.pesos,
        lost_at: if e.paused() { e.pa + PAUSE_MAX_SECS } else { 0 },
    })
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PauseRules {
    /// pauses allowed per session (0: pausing is off)
    pub max: u32,
    /// seconds that must be left to pause
    pub min_left: u64,
    /// pesos the session must have paid in all (top-ups and vouchers count) before it may pause; 0: any amount
    pub min_pesos: u32,
}

#[derive(Debug, PartialEq)]
pub enum PauseError {
    NoSession,
    Already,
    /// no pauses left (or pausing is off)
    Limit,
    /// less than the minimum is left
    TooLittle,
    /// the session has paid this many pesos too few to be allowed a pause
    Pesos(u32),
    Io,
}

/// Pause the device's session: the time left is kept (`rp`), the clock stops (`pa`). Returns the seconds kept.
pub fn pause(path: &str, now: u64, mac: &str, rules: PauseRules) -> Result<u64, PauseError> {
    let mac = mac.to_lowercase();
    let mut entries = load(path);
    let i = entries.iter().position(|e| e.mac == mac).ok_or(PauseError::NoSession)?;
    let left = entries[i].left(now);
    if left == 0 {
        return Err(PauseError::NoSession);
    }
    if entries[i].paused() {
        return Err(PauseError::Already);
    }
    if rules.max == 0 || entries[i].pu >= rules.max {
        return Err(PauseError::Limit);
    }
    if entries[i].pesos < rules.min_pesos {
        return Err(PauseError::Pesos(rules.min_pesos - entries[i].pesos));
    }
    if left < rules.min_left {
        return Err(PauseError::TooLittle);
    }
    entries[i].pa = now;
    entries[i].rp = left;
    entries[i].pu += 1;
    save(path, &entries).map_err(|_| PauseError::Io)?;
    Ok(left)
}

/// Delete sessions that ran out more than two days ago.
/// The router's clock jumped forward by `delta` seconds: it started with a stale time after a power cut (OpenWrt starts from the
/// time of its last file write) and then reached a time server. Sessions paid or paused since `since` were timed with the stale
/// clock and would lose `delta` seconds at once (a customer who paid 60 minutes before the clock synced lost all of it): their
/// start and pause times move forward with the clock. Returns how many entries moved.
pub fn shift(path: &str, since: u64, until: u64, delta: u64) -> usize {
    let mut entries = load(path);
    let mut moved = 0;
    // `until` is the stale time at which the clock jumped: what was paid or paused after it was timed with the right clock
    for e in entries.iter_mut() {
        let mut m = false;
        if e.fp >= since && e.fp < until {
            e.fp += delta;
            m = true;
        }
        if e.pa != 0 && e.pa >= since && e.pa < until {
            e.pa += delta;
            m = true;
        }
        moved += m as usize;
    }
    if moved > 0 {
        let _ = save(path, &entries);
    }
    moved
}

pub fn purge(path: &str, now: u64) {
    let entries = load(path);
    let keep: Vec<Entry> = entries
        .iter()
        .filter(|e| if e.pa != 0 { now <= e.pa + PAUSE_MAX_SECS + 172_800 } else { e.end() + 172_800 >= now })
        .cloned()
        .collect();
    if keep.len() != entries.len() {
        let _ = save(path, &keep);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_clock_jump_moves_only_the_sessions_timed_with_the_stale_clock() {
        let p = tmp("shift");
        let boot = 1_000_000; // the stale time the router started with
        let old = Entry::parse("c1,0,0,0,0,60,900000,aa:aa:aa:aa:aa:01,0,0,0,hyper,w1,10,60,0,1").unwrap(); // paid before the power cut
        let new = Entry::parse("c2,0,0,0,0,60,1000100,aa:aa:aa:aa:aa:02,0,0,0,hyper,w2,10,60,0,1").unwrap(); // paid after the start
        save(&p, &[old.clone(), new.clone()]).unwrap();
        let jump = 7200; // the router was off for two hours
        assert_eq!(shift(&p, boot, u64::MAX, jump), 1);
        let got = load(&p);
        assert_eq!(got[0].fp, old.fp, "a session from before the cut keeps its times");
        assert_eq!(got[1].fp, new.fp + jump, "a session paid with the stale clock moves with it");
        // after the sync, the customer still has the hour they paid for
        assert_eq!(got[1].left(new.fp + jump + 60), 59 * 60);
    }

    #[test]
    fn rt02_a_session_paid_after_the_jump_is_not_moved() {
        let p = tmp("shift-window");
        let (boot, jump_at, jump) = (1_000_000u64, 1_000_200u64, 7200u64); // stale start, stale time when the clock was set, seconds added
        let stale = Entry::parse("c1,0,0,0,0,60,1000100,aa:aa:aa:aa:aa:01,0,0,0,hyper,w1,10,60,0,1").unwrap(); // paid with the stale clock
        let after = Entry::parse("c2,0,0,0,0,60,1008000,aa:aa:aa:aa:aa:02,0,0,0,hyper,w2,10,60,0,1").unwrap(); // paid with the right clock
        let paused_after = Entry::parse("c3,0,0,0,0,60,1008000,aa:aa:aa:aa:aa:03,1,1008100,1800,hyper,w3,10,60,0,1").unwrap();
        save(&p, &[stale.clone(), after.clone(), paused_after.clone()]).unwrap();
        assert_eq!(shift(&p, boot, jump_at, jump), 1, "only the session timed with the stale clock moves");
        let got = load(&p);
        assert_eq!(got[0].fp, stale.fp + jump);
        assert_eq!(got[1].fp, after.fp, "free time: a payment after the jump must not move forward again");
        assert_eq!((got[2].fp, got[2].pa), (paused_after.fp, paused_after.pa));
    }

    #[test]
    fn rt01_session_never_deletes_a_line_only_purge_does() {
        let p = tmp("no-delete");
        let mac = "aa:bb:cc:00:00:07";
        mint(&p, 100, mac, "w", Plan::Hyper, 5, 30, 0, 0, false).unwrap();
        assert!(session(&p, 100 + 30 * 60 + 1, mac).is_none());
        assert!(session_active(&p, 100 + 30 * 60 + 1, mac).is_none());
        assert_eq!(load(&p).len(), 1, "a read that finds no time left must not delete: a clock that is not yet right would end a paid session");
        purge(&p, 100 + 30 * 60 + 172_800 + 1);
        assert!(load(&p).is_empty());
    }

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
    fn pause_keeps_the_time_and_resume_gives_it_back() {
        let p = tmp("pause");
        let mac = "aa:bb:cc:00:00:03";
        let rules = PauseRules { max: 2, min_left: 120, min_pesos: 0 };
        mint(&p, 1000, mac, "w", Plan::Hyper, 5, 30, 0, 0, false).unwrap(); // 30 min = 1800 s
        assert_eq!(pause(&p, 1600, mac, rules), Ok(1200)); // 1200 s left
        assert_eq!(peek_state(&p, 1700, mac).map(|s| (s.left, s.paused, s.pauses_used)), Some((1200, true, 1)));
        assert_eq!(pause(&p, 1700, mac, rules), Err(PauseError::Already));
        // the clock is stopped for as long as it is paused
        assert_eq!(peek(&p, 1600 + 5000, mac).map(|v| v.1), Some(1200));
        assert!(session_active(&p, 1600 + 5000, mac).is_none(), "a paused device is not put back online by itself");
        assert!(peek_state(&p, 1600 + 5000, mac).unwrap().paused);
        // resume: the same 1200 s from now
        let s = session(&p, 1600 + 5000, mac).unwrap();
        assert_eq!(s.left_secs, 1200);
        assert_eq!(peek_state(&p, 1600 + 5000 + 100, mac).map(|s| (s.left, s.paused)), Some((1100, false)));
        // the second pause is allowed, the third is not (max 2)
        assert!(pause(&p, 7000, mac, rules).is_ok());
        session(&p, 7010, mac).unwrap();
        assert_eq!(pause(&p, 7020, mac, rules), Err(PauseError::Limit));
        // pausing off
        assert_eq!(pause(&p, 7020, mac, PauseRules { max: 0, min_left: 0, min_pesos: 0 }), Err(PauseError::Limit));
    }

    #[test]
    fn pausing_needs_enough_time_left_and_paused_time_is_not_kept_for_ever() {
        let p = tmp("pause2");
        let mac = "aa:bb:cc:00:00:04";
        let rules = PauseRules { max: 5, min_left: 120, min_pesos: 0 };
        mint(&p, 1000, mac, "w", Plan::Hyper, 1, 6, 0, 0, false).unwrap(); // 360 s
        assert_eq!(pause(&p, 1000 + 300, mac, rules), Err(PauseError::TooLittle)); // 60 s left
        assert_eq!(pause(&p, 1000 + 400, mac, rules), Err(PauseError::NoSession)); // ran out
        assert_eq!(pause(&p, 1000, "aa:bb:cc:00:00:99", rules), Err(PauseError::NoSession));
        mint(&p, 2000, mac, "w2", Plan::Hyper, 1, 6, 0, 0, false).unwrap();
        assert!(pause(&p, 2010, mac, rules).is_ok());
        assert!(peek(&p, 2010 + PAUSE_MAX_SECS, mac).is_some());
        assert!(peek(&p, 2010 + PAUSE_MAX_SECS + 1, mac).is_none(), "unused paused time is lost after a day");
        purge(&p, 2010 + PAUSE_MAX_SECS + 172_800 + 1);
        assert!(load(&p).is_empty());
        // paying while paused adds to the kept time
        mint(&p, 3000, mac, "w3", Plan::Hyper, 1, 6, 0, 0, false).unwrap();
        pause(&p, 3010, mac, rules).unwrap();
        assert_eq!(mint(&p, 3500, mac, "w4", Plan::Hyper, 1, 6, 0, 0, false), Ok(Mode::TopUp));
        assert_eq!(peek(&p, 3600, mac).map(|v| v.1), Some(350 + 360));
    }

    #[test]
    fn a_pause_needs_enough_pesos_paid_in_all() {
        let p = tmp("pause-pesos");
        let mac = "aa:bb:cc:00:00:05";
        let rules = PauseRules { max: 1, min_left: 120, min_pesos: 20 };
        mint(&p, 1000, mac, "w", Plan::Hyper, 10, 60, 0, 0, false).unwrap();
        assert_eq!(pause(&p, 1100, mac, rules), Err(PauseError::Pesos(10)), "10 pesos paid: 10 more are missing");
        assert_eq!(peek_state(&p, 1100, mac).map(|s| (s.pesos, s.paused)), Some((10, false)), "and nothing was changed");
        // a top-up adds to the same session, so the total counts
        assert_eq!(mint(&p, 1200, mac, "w2", Plan::Hyper, 10, 60, 0, 0, false), Ok(Mode::TopUp));
        assert!(pause(&p, 1300, mac, rules).is_ok(), "20 pesos in all unlock the pause");
        session(&p, 1400, mac).unwrap();
        assert_eq!(pause(&p, 1500, mac, rules), Err(PauseError::Limit), "and it is once per session");
    }

    #[test]
    fn session_and_purge() {
        let p = tmp("b");
        let mac = "aa:bb:cc:00:00:02";
        mint(&p, 100, mac, "w", Plan::Hyper, 5, 30, 0, 0, false).unwrap();
        let s = session(&p, 160, mac).unwrap();
        assert_eq!((s.minutes, s.left_secs), (29, 30 * 60 - 60));
        assert!(session(&p, 100 + 30 * 60 + 1, mac).is_none()); // ran out (RT-01: the line stays until purge)
        assert_eq!(load(&p).len(), 1);
        purge(&p, 100 + 30 * 60 + 172_800 + 1);
        assert!(load(&p).is_empty());
    }
}
