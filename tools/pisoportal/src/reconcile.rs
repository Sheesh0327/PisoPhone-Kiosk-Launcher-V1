//! The revenue ledger against the coin box's own count. The box's "lifetime" coin counter starts again at 0 whenever its
//! revenue is collected (the super admin's vault reset), so the ledger is compared with what the box counted since the
//! last restart of that counter this router saw: DATA_DIR/reconcile.state holds "<box_base> <ledger_base> <last_box>".
//! The box counts the rental phones' coins too, so it may be ahead of the ledger, never behind it.
use crate::boxlink::BoxLink;
use crate::config::Config;
use crate::ledger;
use crate::util::jget_u64;
use std::fs;

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
    let tmp = format!("{}.tmp", path);
    if fs::write(&tmp, format!("{} {} {}\n", b.box_base, b.ledger_base, b.last_box)).is_ok() {
        let _ = fs::rename(&tmp, path);
    }
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
    let Some(counted) = jget_u64(&st, "lifetime_pulses") else { return (Outcome::NoBox(ledger), None) };
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

#[cfg(test)]
mod tests {
    use super::*;

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
