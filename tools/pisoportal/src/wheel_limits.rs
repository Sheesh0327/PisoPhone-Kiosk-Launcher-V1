//! What one device may do with the wheel in a day: how many spins, how long between spins, how many net minutes it may win. The router
//! counts, not the page. A spin is reserved before it is made (so a power cut can only cost a customer a spin, never give one extra) and
//! given back if it is refused. The day is the router's local day (`game::day_index`); counts are kept in DATA_DIR/wheel-days.txt and the
//! file holds only today.
#![allow(dead_code)]
use crate::wheel::Limits;
use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Clone, Copy, Debug, PartialEq)]
struct Rec {
    day: u64,
    spins: u32,
    won: u32,
    last_at: u64,
}

#[derive(Debug, PartialEq)]
pub enum LimitError {
    /// today's spins are used up
    NoSpinsLeft,
    /// seconds still to wait
    Wait(u64),
    Io,
}

/// What `reserve` hands back: the net minutes this device may still win today, and what is needed to give the spin back.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Ticket {
    pub win_room: u32,
    prev: Option<Rec>,
}

pub struct Days {
    path: String,
    inner: Mutex<HashMap<String, Rec>>,
}

impl Days {
    pub fn new(data_dir: &str) -> Days {
        let path = format!("{}/wheel-days.txt", data_dir);
        let mut recs = HashMap::new();
        for line in std::fs::read_to_string(&path).unwrap_or_default().lines() {
            let f: Vec<&str> = line.split_whitespace().collect();
            if let [key, day, spins, won, last_at] = f[..] {
                if let (Ok(day), Ok(spins), Ok(won), Ok(last_at)) = (day.parse(), spins.parse(), won.parse(), last_at.parse()) {
                    recs.insert(key.to_string(), Rec { day, spins, won, last_at });
                }
            }
        }
        Days { path, inner: Mutex::new(recs) }
    }

    /// Spins still allowed today.
    pub fn spins_left(&self, key: &str, day: u64, limits: &Limits) -> u32 {
        let used = self.inner.lock().unwrap().get(key).filter(|r| r.day == day).map_or(0, |r| r.spins);
        limits.spins_per_day.saturating_sub(used)
    }

    /// Seconds to wait before the next spin (0 = may spin). A clock that went back never makes the wait longer than the setting.
    pub fn wait_left(&self, key: &str, now: u64, limits: &Limits) -> u64 {
        match self.inner.lock().unwrap().get(key) {
            Some(r) if r.last_at > 0 => limits.wait_secs.saturating_sub(now.saturating_sub(r.last_at)),
            _ => 0,
        }
    }

    /// Net minutes this device may still win today.
    pub fn win_room(&self, key: &str, day: u64, limits: &Limits) -> u32 {
        let won = self.inner.lock().unwrap().get(key).filter(|r| r.day == day).map_or(0, |r| r.won);
        limits.win_cap_per_day.saturating_sub(won)
    }

    /// Take one spin for this device, or say why not. Written to disk before it returns.
    pub fn reserve(&self, key: &str, day: u64, now: u64, limits: &Limits) -> Result<Ticket, LimitError> {
        let mut recs = self.inner.lock().unwrap();
        let prev = recs.get(key).copied();
        let today = prev.filter(|r| r.day == day).unwrap_or(Rec { day, spins: 0, won: 0, last_at: prev.map_or(0, |r| r.last_at) });
        if today.spins >= limits.spins_per_day {
            return Err(LimitError::NoSpinsLeft);
        }
        let wait = if today.last_at > 0 { limits.wait_secs.saturating_sub(now.saturating_sub(today.last_at)) } else { 0 };
        if wait > 0 {
            return Err(LimitError::Wait(wait));
        }
        let win_room = limits.win_cap_per_day.saturating_sub(today.won);
        recs.insert(key.to_string(), Rec { spins: today.spins + 1, last_at: now, ..today });
        if self.persist(&mut recs, day).is_err() {
            match prev {
                Some(p) => recs.insert(key.to_string(), p),
                None => recs.remove(key),
            };
            return Err(LimitError::Io);
        }
        Ok(Ticket { win_room, prev })
    }

    /// The spin was made: add what it won (a loss adds nothing).
    pub fn settle(&self, key: &str, day: u64, delta_min: i64) {
        let mut recs = self.inner.lock().unwrap();
        if let Some(r) = recs.get_mut(key).filter(|r| r.day == day) {
            r.won = r.won.saturating_add(delta_min.clamp(0, u32::MAX as i64) as u32);
            let _ = self.persist(&mut recs, day);
        }
    }

    /// The spin was refused or failed: give it back, with the wait it had started.
    pub fn release(&self, key: &str, day: u64, ticket: &Ticket) {
        let mut recs = self.inner.lock().unwrap();
        if let Some(r) = recs.get_mut(key).filter(|r| r.day == day) {
            r.spins = r.spins.saturating_sub(1);
            r.last_at = ticket.prev.map_or(0, |p| p.last_at);
            let _ = self.persist(&mut recs, day);
        }
    }

    /// Rewrite the file with today's counts only; older days are dropped from memory too.
    fn persist(&self, recs: &mut HashMap<String, Rec>, day: u64) -> std::io::Result<()> {
        recs.retain(|_, r| r.day == day);
        let text: String = recs.iter().map(|(k, r)| format!("{} {} {} {} {}\n", k, r.day, r.spins, r.won, r.last_at)).collect();
        crate::util::write_atomic(&self.path, text.as_bytes())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const K: &str = "aa:bb:cc:00:00:01";

    fn dir(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-wheeldays-{}-{}", name, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    fn limits() -> Limits {
        Limits { max_bet: 10, floor: 5, spins_per_day: 3, wait_secs: 10, win_cap_per_day: 20 }
    }

    #[test]
    fn spins_are_limited_per_day_and_the_next_day_is_fresh() {
        let d = Days::new(&dir("perday"));
        let l = limits();
        for i in 0..3 {
            d.reserve(K, 100, 1000 + i * 20, &l).unwrap();
        }
        assert_eq!(d.spins_left(K, 100, &l), 0);
        assert_eq!(d.reserve(K, 100, 2000, &l).unwrap_err(), LimitError::NoSpinsLeft);
        assert_eq!(d.spins_left(K, 101, &l), 3, "a new day starts fresh");
        d.reserve(K, 101, 2000, &l).unwrap();
    }

    #[test]
    fn the_wait_between_spins_counts_down_and_is_per_device() {
        let d = Days::new(&dir("wait"));
        let l = limits();
        d.reserve(K, 100, 1000, &l).unwrap();
        assert_eq!(d.reserve(K, 100, 1004, &l).unwrap_err(), LimitError::Wait(6));
        assert_eq!(d.wait_left(K, 1004, &l), 6);
        assert_eq!(d.wait_left("other", 1004, &l), 0, "another device does not wait");
        d.reserve("other", 100, 1004, &l).unwrap();
        d.reserve(K, 100, 1010, &l).unwrap();
        assert_eq!(d.spins_left(K, 100, &l), 1);
    }

    #[test]
    fn a_clock_that_went_back_never_makes_the_wait_longer_than_the_setting() {
        let d = Days::new(&dir("clock"));
        let l = limits();
        d.reserve(K, 100, 5000, &l).unwrap();
        assert_eq!(d.wait_left(K, 100, &l), 10);
    }

    #[test]
    fn winnings_use_up_the_daily_cap_and_losses_do_not_give_room_back() {
        let d = Days::new(&dir("cap"));
        let l = limits();
        assert_eq!(d.win_room(K, 100, &l), 20);
        let t = d.reserve(K, 100, 1000, &l).unwrap();
        assert_eq!(t.win_room, 20);
        d.settle(K, 100, 15);
        assert_eq!(d.win_room(K, 100, &l), 5);
        d.reserve(K, 100, 1020, &l).unwrap();
        d.settle(K, 100, -8);
        assert_eq!(d.win_room(K, 100, &l), 5, "a loss does not make room");
        d.reserve(K, 100, 1040, &l).unwrap();
        d.settle(K, 100, 99);
        assert_eq!(d.win_room(K, 100, &l), 0, "never below zero");
        assert_eq!(d.win_room(K, 101, &l), 20, "a new day starts fresh");
    }

    #[test]
    fn a_refused_spin_is_given_back_with_its_wait() {
        let d = Days::new(&dir("release"));
        let l = limits();
        let t = d.reserve(K, 100, 1000, &l).unwrap();
        assert_eq!(d.wait_left(K, 1001, &l), 9);
        d.release(K, 100, &t);
        assert_eq!(d.spins_left(K, 100, &l), 3);
        assert_eq!(d.wait_left(K, 1001, &l), 0);
        // and the one before it keeps its wait
        let first = d.reserve(K, 100, 2000, &l).unwrap();
        let second = d.reserve(K, 100, 2010, &l).unwrap();
        d.release(K, 100, &second);
        assert_eq!(d.spins_left(K, 100, &l), 2);
        assert_eq!(d.wait_left(K, 2004, &l), 6, "the first spin's wait is back");
        let _ = first;
    }

    #[test]
    fn the_count_survives_a_restart_but_only_for_today() {
        let dd = dir("restart");
        let l = limits();
        {
            let d = Days::new(&dd);
            d.reserve(K, 100, 1000, &l).unwrap();
            d.settle(K, 100, 12);
        }
        let again = Days::new(&dd);
        assert_eq!(again.spins_left(K, 100, &l), 2);
        assert_eq!(again.win_room(K, 100, &l), 8);
        assert_eq!(again.wait_left(K, 1003, &l), 7, "the wait also survives a restart");
        assert_eq!(again.spins_left(K, 101, &l), 3);
        again.reserve(K, 101, 90_000, &l).unwrap();
        let third = Days::new(&dd);
        assert_eq!(third.spins_left(K, 100, &l), 3, "yesterday's counts are dropped once a new day is written");
    }

    #[test]
    fn a_spin_that_cannot_be_written_is_refused() {
        // a regular file where the folder should be: nothing can be written under it, whoever runs the test
        let dd = dir("unwritable");
        std::fs::write(format!("{}/notadir", dd), "x").unwrap();
        let d = Days::new(&format!("{}/notadir", dd));
        assert_eq!(d.reserve(K, 100, 1000, &limits()).unwrap_err(), LimitError::Io);
        assert_eq!(d.spins_left(K, 100, &limits()), 3, "and nothing was counted");
    }

    #[test]
    fn a_garbled_file_counts_as_empty() {
        let dd = dir("garbled");
        std::fs::write(format!("{}/wheel-days.txt", dd), "nonsense\n1 2\nkey x y z w\n").unwrap();
        let d = Days::new(&dd);
        assert_eq!(d.spins_left(K, 100, &limits()), 3);
    }
}
