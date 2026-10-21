//! Applying one spin to a customer's paid time. The caller holds the router's file lock; this changes the minutes bought (`tl`) of the
//! session by exactly the net result, once. Before the session changes, the spin is written to a journal file (decided result, the minutes
//! before and after); after it, the file is marked done. A repeated request with the same spin id returns the same result and changes
//! nothing; a spin cut short by a power failure is finished or voided the next time the id (or `recover`) is seen, never applied twice.
#![allow(dead_code)]
use crate::roll;
use crate::wheel::{check_bet, delta_minutes, BetError, Limits, Table};

#[derive(Clone, Debug, PartialEq)]
pub struct SpinOutcome {
    pub id: String,
    pub bet: u32,
    pub segment: usize,
    pub mult: u32,
    /// net minutes added (+) or taken (-) from the session
    pub delta_min: i64,
    /// the win was cut to what the daily cap still allowed
    pub capped: bool,
    pub left_min_after: u64,
    /// the same id was seen before: nothing changed this time
    pub repeated: bool,
}

#[derive(Debug, PartialEq)]
pub enum SpinError {
    BadId,
    NoSession,
    Paused,
    Bet(BetError),
    /// a spin with this id was cut short and the session has changed since: nothing was applied, nothing was charged
    Voided,
    Io,
}

pub fn valid_id(id: &str) -> bool {
    (8..=32).contains(&id.len()) && id.chars().all(|c| c.is_ascii_hexdigit())
}

fn journal_dir(data_dir: &str) -> String {
    format!("{}/wheel", data_dir)
}

fn journal_path(data_dir: &str, id: &str) -> String {
    format!("{}/{}.spin", journal_dir(data_dir), id)
}

struct Journal {
    state: String,
    mac: String,
    bet: u32,
    segment: usize,
    mult: u32,
    delta: i64,
    capped: bool,
    before: u64,
    after: u64,
    left_after: u64,
}

impl Journal {
    fn render(&self, at: u64) -> String {
        format!(
            "v=1\nstate={}\nmac={}\nbet={}\nsegment={}\nmult={}\ndelta={}\ncapped={}\nbefore={}\nafter={}\nleft_after={}\nat={}\n",
            self.state,
            self.mac,
            self.bet,
            self.segment,
            self.mult,
            self.delta,
            self.capped as u8,
            self.before,
            self.after,
            self.left_after,
            at
        )
    }

    fn parse(text: &str) -> Option<Journal> {
        let get = |key: &str| text.lines().find_map(|l| l.strip_prefix(key)?.strip_prefix('=')).map(str::to_string);
        if get("v")? != "1" {
            return None;
        }
        Some(Journal {
            state: get("state")?,
            mac: get("mac")?,
            bet: get("bet")?.parse().ok()?,
            segment: get("segment")?.parse().ok()?,
            mult: get("mult")?.parse().ok()?,
            delta: get("delta")?.parse().ok()?,
            capped: get("capped")? == "1",
            before: get("before")?.parse().ok()?,
            after: get("after")?.parse().ok()?,
            left_after: get("left_after")?.parse().ok()?,
        })
    }

    fn outcome(&self, id: &str, repeated: bool) -> SpinOutcome {
        SpinOutcome {
            id: id.to_string(),
            bet: self.bet,
            segment: self.segment,
            mult: self.mult,
            delta_min: self.delta,
            capped: self.capped,
            left_min_after: self.left_after,
            repeated,
        }
    }
}

fn write_journal(data_dir: &str, id: &str, j: &Journal, at: u64) -> Result<(), SpinError> {
    std::fs::create_dir_all(journal_dir(data_dir)).map_err(|_| SpinError::Io)?;
    crate::util::write_atomic(&journal_path(data_dir, id), j.render(at).as_bytes()).map_err(|_| SpinError::Io)
}

fn read_journal(data_dir: &str, id: &str) -> Option<Journal> {
    Journal::parse(&std::fs::read_to_string(journal_path(data_dir, id)).ok()?)
}

fn log_spin(data_dir: &str, now: u64, id: &str, j: &Journal) {
    crate::wheel_gate::log(data_dir, now, "spin", &format!("{},{},{},{},{},{}", j.mac, id, j.bet, j.mult, j.delta, j.left_after));
}

/// A spin left as "intent": if the session already shows the result, mark it done; if it still shows the old minutes, apply it now;
/// if the minutes are neither (the customer bought or lost time since), apply nothing and void it. Returns the state it ended in.
fn resolve(roll_path: &str, data_dir: &str, now: u64, id: &str, mut j: Journal) -> Journal {
    let mut entries = roll::load(roll_path);
    let at = entries.iter().position(|e| e.mac == j.mac).map(|i| (i, entries[i].tl));
    match at {
        Some((_, tl)) if tl == j.after => j.state = "done".into(),
        Some((i, tl)) if tl == j.before => {
            entries[i].tl = j.after;
            j.state = if roll::save(roll_path, &entries).is_ok() { "done".into() } else { "intent".into() };
        }
        _ => j.state = "void".into(),
    }
    if j.state != "intent" {
        let _ = write_journal(data_dir, id, &j, now);
        if j.state == "done" {
            log_spin(data_dir, now, id, &j);
        } else {
            crate::wheel_gate::log(data_dir, now, "void", &format!("{},{}", j.mac, id));
        }
    }
    j
}

/// Apply a spin. `rand` is a random number from the router's random source, used only when this id is new. `win_room` is the net minutes
/// this device may still win today.
#[allow(clippy::too_many_arguments)]
pub fn spin(
    roll_path: &str,
    data_dir: &str,
    now: u64,
    mac: &str,
    id: &str,
    bet: u32,
    table: &Table,
    limits: &Limits,
    rand: u64,
    win_room: u32,
) -> Result<SpinOutcome, SpinError> {
    if !valid_id(id) {
        return Err(SpinError::BadId);
    }
    let id = id.to_lowercase();
    // a spin with this id was already decided: the answer never changes and the session is never charged twice
    if let Some(j) = read_journal(data_dir, &id) {
        let j = if j.state == "intent" { resolve(roll_path, data_dir, now, &id, j) } else { j };
        return match j.state.as_str() {
            "done" => Ok(j.outcome(&id, true)),
            "void" => Err(SpinError::Voided),
            _ => Err(SpinError::Io),
        };
    }
    let mac = mac.to_lowercase();
    let mut entries = roll::load(roll_path);
    let i = entries.iter().position(|e| e.mac == mac).ok_or(SpinError::NoSession)?;
    let left = entries[i].left(now);
    if left == 0 {
        return Err(SpinError::NoSession);
    }
    if entries[i].paused() {
        return Err(SpinError::Paused);
    }
    let left_min = left / 60;
    check_bet(limits, left_min, bet).map_err(SpinError::Bet)?;
    let segment = table.draw(rand);
    let mult = table.segs[segment].mult;
    let raw = delta_minutes(mult, bet);
    let (delta, capped) = if raw > win_room as i64 { (win_room as i64, true) } else { (raw, false) };
    let before = entries[i].tl;
    let after = (before as i64 + delta).max(0) as u64;
    let left_after = ((left as i64 + delta * 60).max(0) as u64) / 60;
    let mut j = Journal { state: "intent".into(), mac, bet, segment, mult, delta, capped, before, after, left_after };
    // 1. the decided result goes to disk first; 2. the session changes; 3. the journal is marked done
    write_journal(data_dir, &id, &j, now)?;
    entries[i].tl = after;
    roll::save(roll_path, &entries).map_err(|_| SpinError::Io)?;
    j.state = "done".into();
    write_journal(data_dir, &id, &j, now)?;
    log_spin(data_dir, now, &id, &j);
    Ok(j.outcome(&id, false))
}

/// Finish or void the spins a power cut left half done. Returns how many were looked at.
pub fn recover(roll_path: &str, data_dir: &str) -> usize {
    let Ok(rd) = std::fs::read_dir(journal_dir(data_dir)) else { return 0 };
    let now = crate::util::now_secs();
    let mut n = 0;
    for f in rd.flatten() {
        let name = f.file_name().to_string_lossy().into_owned();
        let Some(id) = name.strip_suffix(".spin") else { continue };
        if let Some(j) = read_journal(data_dir, id).filter(|j| j.state == "intent") {
            resolve(roll_path, data_dir, now, id, j);
            n += 1;
        }
    }
    n
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::roll::{load, save, Entry};

    const MAC: &str = "aa:bb:cc:00:00:01";
    const ID1: &str = "0123456789abcdef";
    const ID2: &str = "fedcba9876543210";

    fn entry(mac: &str, tl: u64, fp: u64) -> Entry {
        Entry::parse(&format!("c1,0,0,0,0,{},{},{},0,0,0,hyper,w1,10,{},0,1", tl, fp, mac, tl)).unwrap()
    }

    /// a data dir with a roll holding one session of `tl` minutes that started `used_min` minutes ago
    fn world(name: &str, tl: u64, used_min: u64) -> (String, String, u64) {
        let d = std::env::temp_dir().join(format!("pisoportal-wheelspin-{}-{}", name, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let dir = d.to_string_lossy().into_owned();
        let roll = format!("{}/roll.csv", dir);
        let now = 1_000_000u64;
        save(&roll, &[entry(MAC, tl, now - used_min * 60)]).unwrap();
        (dir, roll, now)
    }

    fn left_min(roll: &str, now: u64) -> u64 {
        load(roll)[0].left(now) / 60
    }

    fn lim() -> Limits {
        Limits::default_limits()
    }

    /// a table whose draw 0 is a win of x2 and draw 1 a loss
    fn tbl() -> Table {
        Table::parse("2x1 0x1").unwrap()
    }

    #[test]
    fn rt_a_win_adds_the_net_minutes_once() {
        let (dir, roll, now) = world("win", 60, 0);
        let r = spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60).unwrap();
        assert_eq!((r.mult, r.delta_min, r.left_min_after, r.repeated, r.capped), (2, 5, 65, false, false));
        assert_eq!(left_min(&roll, now), 65);
    }

    #[test]
    fn rt_a_loss_takes_the_bet() {
        let (dir, roll, now) = world("loss", 60, 0);
        let r = spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 1, 60).unwrap();
        assert_eq!((r.mult, r.delta_min, r.left_min_after), (0, -5, 55));
        assert_eq!(left_min(&roll, now), 55);
    }

    #[test]
    fn rt_the_same_spin_id_never_charges_twice() {
        let (dir, roll, now) = world("repeat", 60, 0);
        let first = spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 1, 60).unwrap();
        // the page asks again (a retry, a double tap): even with a different random number and bet the answer is the first one
        let again = spin(&roll, &dir, now + 3, MAC, ID1, 9, &tbl(), &lim(), 0, 60).unwrap();
        assert!(again.repeated && !first.repeated);
        assert_eq!((again.mult, again.delta_min, again.bet, again.left_min_after), (first.mult, first.delta_min, 5, 55));
        assert_eq!(left_min(&roll, now), 55);
    }

    #[test]
    fn rt_a_bad_id_a_missing_or_paused_session_and_bad_bets_are_refused_and_change_nothing() {
        let (dir, roll, now) = world("refuse", 60, 0);
        let before = std::fs::read_to_string(&roll).unwrap();
        assert_eq!(spin(&roll, &dir, now, MAC, "xyz", 5, &tbl(), &lim(), 0, 60), Err(SpinError::BadId));
        assert_eq!(spin(&roll, &dir, now, "aa:bb:cc:00:00:99", ID1, 5, &tbl(), &lim(), 0, 60), Err(SpinError::NoSession));
        assert_eq!(spin(&roll, &dir, now, MAC, ID1, 0, &tbl(), &lim(), 0, 60), Err(SpinError::Bet(BetError::Zero)));
        assert_eq!(spin(&roll, &dir, now, MAC, ID1, 11, &tbl(), &lim(), 0, 60), Err(SpinError::Bet(BetError::TooBig)));
        assert_eq!(std::fs::read_to_string(&roll).unwrap(), before);
        // paused
        let mut e = load(&roll);
        e[0].pa = now - 10;
        e[0].rp = 3000;
        save(&roll, &e).unwrap();
        assert_eq!(spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60), Err(SpinError::Paused));
        // expired: the session ended an hour ago
        let (dir2, roll2, now2) = world("expired", 10, 70);
        assert_eq!(spin(&roll2, &dir2, now2, MAC, ID1, 1, &tbl(), &lim(), 0, 60), Err(SpinError::NoSession));
    }

    #[test]
    fn rt_a_refused_spin_leaves_no_journal_file() {
        let (dir, roll, now) = world("nojournal", 60, 0);
        let _ = spin(&roll, &dir, now, MAC, ID1, 11, &tbl(), &lim(), 0, 60);
        assert!(!std::path::Path::new(&journal_path(&dir, ID1)).exists());
    }

    #[test]
    fn rt_the_daily_win_cap_cuts_a_win() {
        let (dir, roll, now) = world("cap", 60, 0);
        // a x2 win of a 10 minute bet is +10, but only 4 minutes of winning are left today
        let r = spin(&roll, &dir, now, MAC, ID1, 10, &tbl(), &lim(), 0, 4).unwrap();
        assert_eq!((r.delta_min, r.capped, r.left_min_after), (4, true, 64));
        // a loss is never capped
        let r2 = spin(&roll, &dir, now, MAC, ID2, 10, &tbl(), &lim(), 1, 0).unwrap();
        assert_eq!((r2.delta_min, r2.capped), (-10, false));
    }

    #[test]
    fn rt_power_cut_after_the_journal_but_before_the_session_changed_finishes_the_spin_once() {
        let (dir, roll, now) = world("cut1", 60, 0);
        // the router died after writing the intent: simulate by writing the intent file by hand as `spin` would
        let first = spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60).unwrap();
        let path = journal_path(&dir, ID1);
        let text = std::fs::read_to_string(&path).unwrap();
        assert!(text.contains("state=done"), "{}", text);
        // put the world back as the cut left it: session as before, journal still "intent"
        let mut e = load(&roll);
        e[0].tl = 60;
        save(&roll, &e).unwrap();
        std::fs::write(&path, text.replace("state=done", "state=intent")).unwrap();
        assert_eq!(recover(&roll, &dir), 1);
        assert_eq!(left_min(&roll, now), 65, "applied exactly once");
        assert!(std::fs::read_to_string(&path).unwrap().contains("state=done"));
        // asking again changes nothing
        let again = spin(&roll, &dir, now + 1, MAC, ID1, 5, &tbl(), &lim(), 1, 60).unwrap();
        assert!(again.repeated);
        assert_eq!((again.delta_min, left_min(&roll, now)), (first.delta_min, 65));
    }

    #[test]
    fn rt_power_cut_after_the_session_changed_but_before_the_journal_was_marked_done_does_not_apply_it_again() {
        let (dir, roll, now) = world("cut2", 60, 0);
        spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60).unwrap();
        let path = journal_path(&dir, ID1);
        let text = std::fs::read_to_string(&path).unwrap();
        std::fs::write(&path, text.replace("state=done", "state=intent")).unwrap();
        assert_eq!(recover(&roll, &dir), 1);
        assert_eq!(left_min(&roll, now), 65, "not applied a second time");
        assert!(std::fs::read_to_string(&path).unwrap().contains("state=done"));
    }

    #[test]
    fn rt_a_cut_spin_whose_session_changed_meanwhile_is_voided_and_charges_nothing() {
        let (dir, roll, now) = world("void", 60, 0);
        spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60).unwrap();
        let path = journal_path(&dir, ID1);
        let text = std::fs::read_to_string(&path).unwrap();
        std::fs::write(&path, text.replace("state=done", "state=intent")).unwrap();
        // the customer bought more time in the meantime: tl is neither the "before" nor the "after" of the spin
        let mut e = load(&roll);
        e[0].tl = 200;
        save(&roll, &e).unwrap();
        assert_eq!(recover(&roll, &dir), 1);
        assert_eq!(load(&roll)[0].tl, 200, "nothing applied");
        assert!(std::fs::read_to_string(&path).unwrap().contains("state=void"));
        assert_eq!(spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 0, 60), Err(SpinError::Voided));
    }

    #[test]
    fn rt_a_spin_is_written_to_the_wheel_log() {
        let (dir, roll, now) = world("log", 60, 0);
        spin(&roll, &dir, now, MAC, ID1, 5, &tbl(), &lim(), 1, 60).unwrap();
        let log = std::fs::read_to_string(format!("{}/wheel.log", dir)).unwrap();
        assert!(log.contains(&format!("{},spin,{},{},5,0,-5,55", now, MAC, ID1)), "{}", log);
    }
}
