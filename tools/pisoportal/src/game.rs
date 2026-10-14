//! The mini-game: a small game the router itself serves (nothing comes from the internet, so it works before anyone has paid), played in
//! a browser tab and limited per device per day. The router keeps the count: the game page asks `/game/tick` every few seconds and each
//! answer adds the real time that passed, never more than TICK_CAP_MS (a page that stops asking is not charged for time it did not use,
//! and one that asks fast is not charged more than the clock says). A tick that is not part of a running session (no page opened it a
//! moment ago) adds nothing.
//!
//! This is a soft limit on purpose: a person who saves the page can play it offline. The limit is for the child at the counter, not a
//! lock. The day is the router's local day (/etc/TZ). Counts are kept in DATA_DIR/game.txt, written about once a minute of play per
//! device (the flash does not wear), and a day's file holds only that day.
use std::collections::HashMap;
use std::fs;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// The most real time one tick may add (the page asks every 5 s).
const TICK_CAP_MS: u64 = 20_000;
/// A session whose last tick or start is older than this is over: the next tick starts a new one and adds nothing.
const SESSION_IDLE_MS: u64 = 60_000;
/// The most the owner can allow, in minutes a day.
pub const MAX_MINUTES: u32 = 120;

struct Day {
    day: u64,
    used_ms: u64,
}

#[derive(Default)]
struct Inner {
    days: HashMap<String, Day>,
    last: HashMap<String, Instant>,
    /// seconds already on flash per device (the file is rewritten when a minute of play is added)
    saved: HashMap<String, u64>,
}

pub struct Games {
    /// seconds of play a device gets per day; 0 = the game is off
    limit: AtomicU32,
    path: String,
    inner: Mutex<Inner>,
    tz: Mutex<(u64, i64)>,
}

/// The router's local day number (days since 1970 in its own time zone).
pub fn day_index(now_secs: u64, tz_offset: i64) -> u64 {
    (now_secs as i64 + tz_offset).div_euclid(86400).max(0) as u64
}

impl Games {
    pub fn new(data_dir: &str, limit_secs: u32) -> Games {
        let path = format!("{}/game.txt", data_dir);
        let mut inner = Inner::default();
        for line in fs::read_to_string(&path).unwrap_or_default().lines() {
            let f: Vec<&str> = line.split_whitespace().collect();
            if let [key, day, secs] = f[..] {
                if let (Ok(day), Ok(secs)) = (day.parse::<u64>(), secs.parse::<u64>()) {
                    inner.days.insert(key.to_string(), Day { day, used_ms: secs * 1000 });
                    inner.saved.insert(key.to_string(), secs);
                }
            }
        }
        Games { limit: AtomicU32::new(limit_secs), path, inner: Mutex::new(inner), tz: Mutex::new((0, 0)) }
    }

    pub fn limit_secs(&self) -> u32 {
        self.limit.load(Ordering::Relaxed)
    }

    pub fn enabled(&self) -> bool {
        self.limit_secs() > 0
    }

    pub fn set_limit_secs(&self, secs: u32) {
        self.limit.store(secs, Ordering::Relaxed);
    }

    /// Today's day number here (the time zone file is read at most every five minutes).
    fn today(&self, now_secs: u64) -> u64 {
        let mut g = self.tz.lock().unwrap();
        if g.0 == 0 || now_secs.saturating_sub(g.0) > 300 {
            *g = (now_secs.max(1), crate::console::tz_offset_secs(&fs::read_to_string("/etc/TZ").unwrap_or_default()));
        }
        day_index(now_secs, g.1)
    }

    fn left_on(&self, inner: &Inner, key: &str, day: u64) -> u32 {
        let limit = self.limit_secs() as u64;
        let used = inner.days.get(key).filter(|d| d.day == day).map(|d| d.used_ms / 1000).unwrap_or(0);
        limit.saturating_sub(used) as u32
    }

    /// Seconds of play left today for this device, or -1 when the game is off (this is what the coin page shows).
    pub fn left(&self, key: &str, now_secs: u64) -> i64 {
        if !self.enabled() {
            return -1;
        }
        let day = self.today(now_secs);
        i64::from(self.left_on(&self.inner.lock().unwrap(), key, day))
    }

    /// A game round starts (or comes back after a pause): counting runs from now. Returns the seconds left.
    pub fn begin(&self, key: &str, now_secs: u64) -> u32 {
        self.begin_at(key, self.today(now_secs), Instant::now())
    }

    /// The page asks: the real time since its last tick or start is added (up to the cap). Returns the seconds left.
    pub fn tick(&self, key: &str, now_secs: u64) -> u32 {
        self.tick_at(key, self.today(now_secs), Instant::now())
    }

    pub fn begin_at(&self, key: &str, day: u64, now: Instant) -> u32 {
        let mut inner = self.inner.lock().unwrap();
        inner.last.insert(key.to_string(), now);
        self.left_on(&inner, key, day)
    }

    pub fn tick_at(&self, key: &str, day: u64, now: Instant) -> u32 {
        let limit_ms = self.limit_secs() as u64 * 1000;
        let mut inner = self.inner.lock().unwrap();
        let credit = match inner.last.get(key) {
            Some(&last) if now.duration_since(last) <= Duration::from_millis(SESSION_IDLE_MS) => {
                (now.duration_since(last).as_millis() as u64).min(TICK_CAP_MS)
            }
            _ => 0,
        };
        inner.last.insert(key.to_string(), now);
        if credit > 0 && limit_ms > 0 {
            let d = inner.days.entry(key.to_string()).or_insert(Day { day, used_ms: 0 });
            if d.day != day {
                *d = Day { day, used_ms: 0 };
            }
            d.used_ms = (d.used_ms + credit).min(limit_ms);
            let secs = d.used_ms / 1000;
            let done = d.used_ms >= limit_ms;
            let saved = inner.saved.get(key).copied().unwrap_or(0);
            if secs / 60 != saved / 60 || (done && secs != saved) {
                inner.saved.insert(key.to_string(), secs);
                self.persist(&inner, day);
            }
        }
        self.left_on(&inner, key, day)
    }

    /// Rewrite the file with today's counts only (a failure only loses the count of a restart: the game still works).
    fn persist(&self, inner: &Inner, day: u64) {
        let text: String =
            inner.days.iter().filter(|(_, d)| d.day == day).map(|(k, d)| format!("{} {} {}\n", k, d.day, d.used_ms / 1000)).collect();
        let tmp = format!("{}.tmp", self.path);
        if let Some(dir) = std::path::Path::new(&self.path).parent() {
            let _ = fs::create_dir_all(dir);
        }
        let _ = fs::write(&tmp, text).and_then(|_| fs::rename(&tmp, &self.path));
    }
}

/// The key a device is counted under: its MAC, or its address when the router cannot tell the MAC.
pub fn device_key(mac: Option<String>, ip: &str) -> String {
    mac.unwrap_or_else(|| format!("ip-{}", ip.replace(':', "_")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-game-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn the_local_day_follows_the_time_zone() {
        // 2026-10-14 17:00 UTC is already the 15th in Manila (UTC+8) and still the 14th in UTC
        let t = 1_791_999_600; // 2026-10-14T17:00:00Z
        assert_eq!(day_index(t, 8 * 3600), day_index(t, 0) + 1);
        assert_eq!(day_index(t, 0), day_index(t + 3600, 0), "an hour later is the same UTC day");
        assert_eq!(day_index(0, -3600), 0, "never negative");
    }

    #[test]
    fn real_time_is_counted_up_to_the_limit() {
        let g = Games::new(&tmp("count"), 60);
        let t0 = Instant::now();
        assert_eq!(g.begin_at("aa", 100, t0), 60);
        assert_eq!(g.tick_at("aa", 100, t0 + Duration::from_secs(5)), 55);
        assert_eq!(g.tick_at("aa", 100, t0 + Duration::from_secs(10)), 50);
        for i in 3..=20 {
            g.tick_at("aa", 100, t0 + Duration::from_secs(5 * i));
        }
        assert_eq!(g.tick_at("aa", 100, t0 + Duration::from_secs(105)), 0, "the limit is the most there is");
        assert_eq!(g.begin_at("aa", 100, t0 + Duration::from_secs(106)), 0, "and the page is refused afterwards");
    }

    #[test]
    fn a_tick_cannot_add_more_than_the_clock_or_the_cap() {
        let g = Games::new(&tmp("cap"), 600);
        let t0 = Instant::now();
        g.begin_at("aa", 7, t0);
        assert_eq!(g.tick_at("aa", 7, t0 + Duration::from_secs(1)), 599, "asking fast adds only what passed");
        assert_eq!(
            g.tick_at("aa", 7, t0 + Duration::from_secs(1) + Duration::from_millis(100)),
            599,
            "a tenth of a second is not a second"
        );
        // 40 s of silence is inside the session but only 20 s are charged
        assert_eq!(g.tick_at("aa", 7, t0 + Duration::from_secs(41)), 599 - 20);
        // a long silence ends the session: the next tick starts again and adds nothing
        assert_eq!(g.tick_at("aa", 7, t0 + Duration::from_secs(41 + 120)), 579);
        // a tick with no session at all adds nothing
        assert_eq!(g.tick_at("zz", 7, t0), 600);
    }

    #[test]
    fn each_device_has_its_own_minutes_and_a_new_day_starts_fresh() {
        let g = Games::new(&tmp("days"), 30);
        let t0 = Instant::now();
        g.begin_at("aa", 5, t0);
        g.tick_at("aa", 5, t0 + Duration::from_secs(20));
        assert_eq!(g.left_on(&g.inner.lock().unwrap(), "aa", 5), 10);
        assert_eq!(g.left_on(&g.inner.lock().unwrap(), "bb", 5), 30, "another device is untouched");
        assert_eq!(g.left_on(&g.inner.lock().unwrap(), "aa", 6), 30, "the next day is a fresh 30 seconds");
        g.begin_at("aa", 6, t0 + Duration::from_secs(100));
        assert_eq!(g.tick_at("aa", 6, t0 + Duration::from_secs(105)), 25, "and counting on the new day starts from zero");
    }

    #[test]
    fn the_count_survives_a_restart_but_only_for_today() {
        let dir = tmp("persist");
        let g = Games::new(&dir, 600);
        let t0 = Instant::now();
        g.begin_at("aa", 9, t0);
        for i in 1..=14 {
            g.tick_at("aa", 9, t0 + Duration::from_secs(5 * i)); // 70 s of play: past the first written minute
        }
        let text = fs::read_to_string(format!("{}/game.txt", dir)).unwrap();
        assert!(text.starts_with("aa 9 6"), "written when a minute was added: {}", text);
        let again = Games::new(&dir, 600);
        assert_eq!(again.left_on(&again.inner.lock().unwrap(), "aa", 9), 600 - 60, "back after a restart (to the last minute written)");
        assert_eq!(again.left_on(&again.inner.lock().unwrap(), "aa", 10), 600, "a new day does not inherit it");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_game_is_off_with_no_minutes_and_changes_live() {
        let g = Games::new(&tmp("off"), 0);
        assert!(!g.enabled());
        assert_eq!(g.left("aa", 1_800_000_000), -1);
        g.set_limit_secs(120);
        assert!(g.enabled());
        assert_eq!(g.left("aa", 1_800_000_000), 120);
    }

    #[test]
    fn a_device_without_a_known_mac_is_counted_by_its_address() {
        assert_eq!(device_key(Some("aa:bb".into()), "10.0.0.2"), "aa:bb");
        assert_eq!(device_key(None, "fe80::1"), "ip-fe80__1");
    }
}
