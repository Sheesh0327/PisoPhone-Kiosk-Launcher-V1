//! The revenue ledger against the coin box's own count. The box's "lifetime" coin counter starts again at 0 whenever its
//! revenue is collected (the super admin's vault reset), so the ledger is compared with what the box counted since the
//! last restart of that counter this router saw: DATA_DIR/reconcile.state holds "<box_base> <ledger_base> <last_box>".
//! The box counts the rental phones' coins too, so it may be ahead of the ledger, never behind it.
use crate::boxlink::BoxLink;
use crate::config::Config;
use crate::ledger;
use crate::util::jget_u64;
use std::fs;
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Base {
    pub box_base: u64,
    pub ledger_base: u64,
    pub last_box: u64,
}

#[derive(Debug, PartialEq)]
pub enum Outcome {
    /// pesos in the ledger and counted by the box, since the base
    Ok {
        ledger: u64,
        counted: u64,
    },
    Mismatch {
        ledger: u64,
        counted: u64,
    },
    /// the first line of the ledger whose hash does not follow
    BadLedger(usize),
    /// the box did not answer (or is too old to report its count); the ledger total
    NoBox(u64),
}

pub fn state_path(cfg: &Config) -> String {
    format!("{}/reconcile.state", cfg.data_dir)
}

pub fn load(path: &str) -> Option<Base> {
    let t = fs::read_to_string(path).ok()?;
    let n: Vec<u64> = t.split_whitespace().filter_map(|v| v.parse().ok()).collect();
    (n.len() == 3).then(|| Base { box_base: n[0], ledger_base: n[1], last_box: n[2] })
}

fn save(path: &str, b: &Base) {
    let _ = crate::util::write_atomic(path, format!("{} {} {}\n", b.box_base, b.ledger_base, b.last_box).as_bytes());
}

/// The base after the box was seen at `counted` with the ledger at `ledger` (pure, so it is unit-tested), and why it
/// moved, if it did.
pub fn next_base(prev: Option<Base>, counted: u64, ledger: u64) -> (Base, Option<&'static str>) {
    let here = Base { box_base: counted, ledger_base: ledger, last_box: counted };
    match prev {
        // the first check: the whole history when it adds up, otherwise the counter restarted before this router compared
        None if ledger <= counted => (Base { box_base: 0, ledger_base: 0, last_box: counted }, None),
        None => (here, Some("the box's count restarted before this router began comparing: compared from now on")),
        Some(b) if counted < b.last_box => (here, Some("the box's count restarted (its revenue was collected): compared from now on")),
        Some(b) => (Base { last_box: counted, ..b }, None),
    }
}

/// One reconciliation; the stored base moves when the box's counter restarted (or when `rebase` is asked for).
pub fn run(cfg: &Config, rebase: bool) -> (Outcome, Option<&'static str>) {
    let ledger = match ledger::verify(&cfg.revenue_path()) {
        Ok((_, pesos)) => pesos,
        Err(line) => return (Outcome::BadLedger(line), None),
    };
    let st = BoxLink::new(&cfg.gw_box, &cfg.gw_key).call(&"f".repeat(32), "status", "").unwrap_or_default();
    let Some(counted) = jget_u64(&st, "lifetime_wifi_pulses").or_else(|| jget_u64(&st, "lifetime_pulses")) else {
        return (Outcome::NoBox(ledger), None);
    };
    let path = state_path(cfg);
    let (base, note) = if rebase {
        (Base { box_base: counted, ledger_base: ledger, last_box: counted }, Some("compared from now on (rebase)"))
    } else {
        next_base(load(&path), counted, ledger)
    };
    save(&path, &base);
    let (l, c) = (ledger.saturating_sub(base.ledger_base), counted.saturating_sub(base.box_base));
    if l > c {
        (Outcome::Mismatch { ledger: l, counted: c }, note)
    } else {
        (Outcome::Ok { ledger: l, counted: c }, note)
    }
}

/// Which count the coin box reported: its Wi-Fi coins on their own (current firmware), or every coin it took, rental phones
/// included (older firmware).
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CountKind {
    WifiOnly,
    AllCoins,
}

/// Everything the console panel needs, read once: nothing here is written back.
pub struct Look {
    /// the first line of the ledger whose hash does not follow
    pub bad_line: Option<usize>,
    /// pesos in the whole ledger
    pub ledger_total: u64,
    /// the box's lifetime count, if it answered with one
    pub box_count: Option<(CountKind, u64)>,
    /// the base the hourly check last saved
    pub base: Option<Base>,
}

/// What the panel says (rows 1 to 8 of `specs/features/036-site-status-panel/spec.md`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum State {
    Agrees,
    /// the box counted more, and the box counts Wi-Fi coins on their own: coins counted but not yet in the record
    BoxAhead,
    /// the box counted more, and its count includes the rental phones' coins: normal
    BoxAheadPhones,
    /// the record holds more than the box counted: the one real mismatch
    Mismatch,
    BoxSilent,
    BadLedger,
    NoSales,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Money {
    pub state: State,
    /// pesos in the record since the base
    pub ledger: u64,
    /// pesos the box counted since the base, if it answered
    pub counted: Option<u64>,
    /// how far apart the two are (0 when they agree or the box is silent)
    pub diff: u64,
    /// the box's count restarted and the hourly check has not recorded that yet
    pub restarted: bool,
    pub bad_line: Option<usize>,
}

/// One look at the files and the box's `status` answer. Reads only: the saved base is loaded, never written.
pub fn look_from(revenue_path: &str, state_path: &str, status_body: &str) -> Look {
    let (bad_line, ledger_total) = match ledger::verify(revenue_path) {
        Ok((_, pesos)) => (None, pesos),
        Err(line) => (Some(line), 0),
    };
    let box_count = jget_u64(status_body, "lifetime_wifi_pulses")
        .map(|n| (CountKind::WifiOnly, n))
        .or_else(|| jget_u64(status_body, "lifetime_pulses").map(|n| (CountKind::AllCoins, n)));
    Look { bad_line, ledger_total, box_count, base: load(state_path) }
}

/// What the panel keeps between requests: the last answer and when it was made.
pub type Slot = Mutex<Option<(Instant, Money)>>;

/// `f` at most once per `ttl`, however many requests ask (the lock is held while `f` runs, so a second console waits for the first
/// answer instead of calling the box again).
pub fn cached(slot: &Slot, now: Instant, ttl: Duration, f: impl FnOnce() -> Money) -> Money {
    let mut held = slot.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((at, m)) = held.as_ref() {
        if now.saturating_duration_since(*at) < ttl {
            return m.clone();
        }
    }
    let m = f();
    *held = Some((now, m.clone()));
    m
}

static SLOT: Slot = Mutex::new(None);

/// The console panel's money state: at most one `status` call to the box a minute, and nothing written (the hourly check stays
/// the only one that saves the base).
pub fn peek(cfg: &Config) -> Money {
    cached(&SLOT, Instant::now(), Duration::from_secs(60), || {
        let st = BoxLink::new(&cfg.gw_box, &cfg.gw_key).call(&"f".repeat(32), "status", "").unwrap_or_default();
        money(&look_from(&cfg.revenue_path(), &state_path(cfg), &st))
    })
}

/// The panel's state from one look at the ledger and the box (pure: it never saves the base).
pub fn money(look: &Look) -> Money {
    let (ledger_total, bad_line) = (look.ledger_total, look.bad_line);
    if bad_line.is_some() {
        return Money { state: State::BadLedger, ledger: ledger_total, counted: None, diff: 0, restarted: false, bad_line };
    }
    let Some((kind, counted_total)) = look.box_count else {
        return Money { state: State::BoxSilent, ledger: ledger_total, counted: None, diff: 0, restarted: false, bad_line };
    };
    let (base, note) = next_base(look.base, counted_total, ledger_total);
    let restarted = note.is_some();
    let (l, c) = (ledger_total.saturating_sub(base.ledger_base), counted_total.saturating_sub(base.box_base));
    let (state, diff) = match l.cmp(&c) {
        std::cmp::Ordering::Equal if l == 0 && !restarted => (State::NoSales, 0),
        std::cmp::Ordering::Equal => (State::Agrees, 0),
        std::cmp::Ordering::Less if kind == CountKind::WifiOnly => (State::BoxAhead, c - l),
        std::cmp::Ordering::Less => (State::BoxAheadPhones, c - l),
        std::cmp::Ordering::Greater => (State::Mismatch, l - c),
    };
    Money { state, ledger: l, counted: Some(c), diff, restarted, bad_line }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-f036-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn f036_the_wifi_count_is_read_first_and_the_all_coins_count_is_the_fallback() {
        let d = scratch("kind");
        let (r, s) = (format!("{}/revenue.csv", d), format!("{}/reconcile.state", d));
        let new = look_from(&r, &s, r#"{"success":true,"lifetime_pulses":1310,"lifetime_wifi_pulses":1247,"lifetime_phone_pulses":63}"#);
        assert_eq!(new.box_count, Some((CountKind::WifiOnly, 1247)));
        let old = look_from(&r, &s, r#"{"success":true,"lifetime_pulses":1310}"#);
        assert_eq!(old.box_count, Some((CountKind::AllCoins, 1310)));
        assert_eq!(look_from(&r, &s, "").box_count, None, "an empty answer is a silent box");
        assert_eq!(look_from(&r, &s, r#"{"success":false}"#).box_count, None, "an answer with no count is a silent box");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn f036_the_ledger_total_and_a_changed_line_are_read_and_the_saved_base_is_not_touched() {
        let d = scratch("ledger");
        let (r, s) = (format!("{}/revenue.csv", d), format!("{}/reconcile.state", d));
        for (t, p) in [(100u64, 10u32), (200, 20), (300, 5)] {
            ledger::append(&r, t, "hyper", p, 30, "coin").unwrap();
        }
        fs::write(&s, "5 600 5\n").unwrap();
        let l = look_from(&r, &s, r#"{"lifetime_wifi_pulses":35}"#);
        assert_eq!((l.bad_line, l.ledger_total), (None, 35));
        assert_eq!(l.base, Some(Base { box_base: 5, ledger_base: 600, last_box: 5 }));
        assert_eq!(fs::read_to_string(&s).unwrap(), "5 600 5\n", "looking never writes the base");
        // change the amount on the second row: the chain no longer follows
        let text = fs::read_to_string(&r).unwrap();
        let mut rows: Vec<String> = text.lines().map(|x| x.to_string()).collect();
        let mut f: Vec<String> = rows[1].split(',').map(|x| x.to_string()).collect();
        f[2] = "99".into();
        rows[1] = f.join(",");
        fs::write(&r, rows.join("\n") + "\n").unwrap();
        let bad = look_from(&r, &s, r#"{"lifetime_wifi_pulses":35}"#);
        assert_eq!(bad.bad_line, Some(2));
        let _ = fs::remove_dir_all(&d);
    }

    fn some_money(ledger: u64) -> Money {
        Money { state: State::Agrees, ledger, counted: Some(ledger), diff: 0, restarted: false, bad_line: None }
    }

    #[test]
    fn f036_the_box_is_asked_once_inside_a_minute_however_many_consoles_ask() {
        let slot: Slot = Mutex::new(None);
        let (t0, ttl) = (Instant::now(), Duration::from_secs(60));
        let mut calls = 0;
        let a = cached(&slot, t0, ttl, || { calls += 1; some_money(1) });
        let b = cached(&slot, t0 + Duration::from_secs(59), ttl, || { calls += 1; some_money(2) });
        assert_eq!((calls, a.ledger, b.ledger), (1, 1, 1), "the second ask inside the minute gets the first answer");
        let c = cached(&slot, t0 + Duration::from_secs(61), ttl, || { calls += 1; some_money(3) });
        assert_eq!((calls, c.ledger), (2, 3), "after the minute the box is asked again");
    }

    fn look(ledger: u64, box_count: Option<(CountKind, u64)>, base: Option<Base>) -> Look {
        Look { bad_line: None, ledger_total: ledger, box_count, base }
    }
    const FROM_ZERO: Option<Base> = Some(Base { box_base: 0, ledger_base: 0, last_box: 0 });

    #[test]
    fn f036_row1_the_box_and_the_record_agree() {
        let m = money(&look(1247, Some((CountKind::WifiOnly, 1247)), FROM_ZERO));
        assert_eq!((m.state, m.ledger, m.counted, m.diff, m.restarted), (State::Agrees, 1247, Some(1247), 0, false));
    }

    #[test]
    fn f036_row2_a_box_ahead_that_counts_wifi_coins_on_their_own_is_not_the_phones() {
        let m = money(&look(1247, Some((CountKind::WifiOnly, 1310)), FROM_ZERO));
        assert_eq!((m.state, m.ledger, m.counted, m.diff), (State::BoxAhead, 1247, Some(1310), 63));
    }

    #[test]
    fn f036_row3_a_box_ahead_on_old_firmware_is_the_rental_phones() {
        let m = money(&look(1247, Some((CountKind::AllCoins, 1310)), FROM_ZERO));
        assert_eq!((m.state, m.diff), (State::BoxAheadPhones, 63));
    }

    #[test]
    fn f036_row4_a_box_behind_the_record_is_the_one_real_mismatch() {
        let m = money(&look(1300, Some((CountKind::WifiOnly, 1247)), FROM_ZERO));
        assert_eq!((m.state, m.ledger, m.counted, m.diff), (State::Mismatch, 1300, Some(1247), 53));
        // the same on old firmware: the box includes the phones, so behind is behind
        let m = money(&look(1300, Some((CountKind::AllCoins, 1247)), FROM_ZERO));
        assert_eq!((m.state, m.diff), (State::Mismatch, 53));
    }

    #[test]
    fn f036_row5_a_restarted_box_count_is_a_note_not_a_mismatch() {
        // the box was at 900 when last seen, now 3 (revenue collected); the ledger is at 650
        let base = Some(Base { box_base: 0, ledger_base: 0, last_box: 900 });
        let m = money(&look(650, Some((CountKind::WifiOnly, 3)), base));
        assert!(m.restarted, "the panel says the count restarted");
        assert_eq!((m.state, m.ledger, m.counted, m.diff), (State::Agrees, 0, Some(0), 0), "compared from the restart on");
        // once the hourly check has saved the new base the note is gone
        let saved = Some(Base { box_base: 3, ledger_base: 650, last_box: 3 });
        let m = money(&look(662, Some((CountKind::WifiOnly, 15)), saved));
        assert_eq!((m.state, m.restarted, m.ledger, m.counted), (State::Agrees, false, 12, Some(12)));
    }

    #[test]
    fn f036_row6_a_silent_box_keeps_the_record_in_view() {
        let m = money(&look(1247, None, FROM_ZERO));
        assert_eq!((m.state, m.ledger, m.counted, m.diff), (State::BoxSilent, 1247, None, 0));
    }

    #[test]
    fn f036_row7_a_changed_record_is_named_before_any_comparison() {
        let mut l = look(1247, Some((CountKind::WifiOnly, 1247)), FROM_ZERO);
        l.bad_line = Some(9);
        let m = money(&l);
        assert_eq!((m.state, m.bad_line), (State::BadLedger, Some(9)));
    }

    #[test]
    fn f036_row8_no_sales_and_no_coins_is_said_plainly() {
        let m = money(&look(0, Some((CountKind::WifiOnly, 0)), None));
        assert_eq!((m.state, m.ledger, m.counted), (State::NoSales, 0, Some(0)));
        // coins counted with nothing recorded is not "no sales": it is the box ahead
        let m = money(&look(0, Some((CountKind::WifiOnly, 5)), FROM_ZERO));
        assert_eq!((m.state, m.diff), (State::BoxAhead, 5));
    }

    #[test]
    fn f036_the_kind_of_count_decides_row_2_or_3_never_the_size_of_the_gap() {
        for gap in [1u64, 2, 63, 5000] {
            let w = money(&look(100, Some((CountKind::WifiOnly, 100 + gap)), FROM_ZERO));
            let a = money(&look(100, Some((CountKind::AllCoins, 100 + gap)), FROM_ZERO));
            assert_eq!((w.state, a.state), (State::BoxAhead, State::BoxAheadPhones), "gap {}", gap);
        }
    }

    #[test]
    fn f036_a_first_look_with_no_saved_base_never_cries_wolf() {
        // the ledger is already ahead of a freshly restarted box: compared from now on, not a mismatch
        let m = money(&look(600, Some((CountKind::WifiOnly, 5)), None));
        assert!(m.restarted);
        assert_eq!(m.state, State::Agrees);
    }

    #[test]
    fn the_box_counter_restarting_moves_the_base() {
        // first check, history adds up: everything is compared
        let (b, n) = next_base(None, 900, 600);
        assert_eq!((b, n), (Base { box_base: 0, ledger_base: 0, last_box: 900 }, None));
        // more coins: same base
        let (b, n) = next_base(Some(b), 950, 640);
        assert_eq!((b.box_base, b.ledger_base, b.last_box, n), (0, 0, 950, None));
        // revenue collected: the box restarts at 0 -> compared from here on, not a mismatch for ever
        let (b, n) = next_base(Some(b), 3, 650);
        assert_eq!((b.box_base, b.ledger_base, b.last_box), (3, 650, 3));
        assert!(n.is_some());
        let (b, _) = next_base(Some(b), 20, 662);
        assert_eq!((662 - b.ledger_base, 20 - b.box_base), (12, 17));
        // first check after an earlier collection (the ledger is already ahead): a base, not an alarm
        let (b, n) = next_base(None, 5, 600);
        assert_eq!((b, n.is_some()), (Base { box_base: 5, ledger_base: 600, last_box: 5 }, true));
    }

    #[test]
    fn the_base_is_kept_on_disk() {
        let d = std::env::temp_dir().join(format!("pisoportal-reconcile-{}", std::process::id()));
        let _ = fs::create_dir_all(&d);
        let p = d.join("reconcile.state").to_string_lossy().into_owned();
        assert_eq!(load(&p), None);
        save(&p, &Base { box_base: 1, ledger_base: 2, last_box: 3 });
        assert_eq!(load(&p), Some(Base { box_base: 1, ledger_base: 2, last_box: 3 }));
        let _ = fs::remove_dir_all(&d);
    }
}
