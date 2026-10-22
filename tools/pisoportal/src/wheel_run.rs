//! The spinning wheel as the portal serves it: what `/wheel/state` and `/wheel/spin` answer, and the owner's wheel settings (switch,
//! table, limits). The decisions are made in `wheel` (odds, bets), `wheel_limits` (spins a day, the wait, the win cap), `wheel_spin` (the
//! one change to the customer's time, journalled) and `wheel_gate` (the owner's confirmation). This file only puts them in order:
//! off means off, the day's spin is reserved first, the file lock is held while the session changes, a refused spin is given back.
#![allow(dead_code)]
use crate::wheel::{self, Limits, Table};
use crate::wheel_gate;
use crate::wheel_limits::{Days, LimitError};
use crate::wheel_spin::{self, SpinError};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, RwLock};

pub struct Wheel {
    data_dir: String,
    roll_path: String,
    /// the switch in the settings; the wheel is on only when the owner's confirmation is on record too
    setting: AtomicBool,
    table: RwLock<Table>,
    limits: RwLock<Limits>,
    days: Days,
}

/// An answer for the portal: HTTP status and a JSON body.
pub type Reply = (u16, String);

fn err(code: u16, name: &str, why: &str) -> Reply {
    (code, format!("{{\"error\":\"{}\",\"why\":\"{}\"}}", name, crate::util::json_esc(why)))
}

fn setting_names() -> [&'static str; 5] {
    ["WHEEL_MAX_BET", "WHEEL_FLOOR", "WHEEL_SPINS_PER_DAY", "WHEEL_WAIT", "WHEEL_WIN_CAP"]
}

impl Wheel {
    /// `file_setting`: what the configuration file says (WHEEL); the console's own setting wins over it.
    pub fn new(data_dir: &str, roll_path: &str, file_setting: bool) -> Wheel {
        let mine = crate::config::override_get(data_dir, "WHEEL");
        let setting = crate::config::parse_switch(&mine, file_setting);
        let table = match Table::parse(&crate::config::override_get(data_dir, "WHEEL_TABLE")) {
            Ok(t) if t.validate_saved().is_ok() => t,
            _ => Table::default_table(),
        };
        let mut limits = Limits::default_limits();
        let num = |key: &str| crate::config::override_get(data_dir, key).parse::<u32>().ok();
        let [max_bet, floor, spins, wait, cap] = setting_names().map(num);
        limits.max_bet = max_bet.unwrap_or(limits.max_bet);
        limits.floor = floor.unwrap_or(limits.floor);
        limits.spins_per_day = spins.unwrap_or(limits.spins_per_day);
        limits.wait_secs = wait.map_or(limits.wait_secs, u64::from);
        limits.win_cap_per_day = cap.unwrap_or(limits.win_cap_per_day);
        let w = Wheel {
            data_dir: data_dir.into(),
            roll_path: roll_path.into(),
            setting: AtomicBool::new(setting),
            table: RwLock::new(table),
            limits: RwLock::new(limits),
            days: Days::new(data_dir),
        };
        if setting && !wheel_gate::confirmed(data_dir) {
            wheel_gate::log(
                data_dir,
                crate::util::now_secs(),
                "refused",
                "the setting says on but the owner's confirmation is not on record: the wheel stays off",
            );
        }
        w
    }

    /// On only when the setting says so AND the owner's confirmation for the current warning is on record.
    pub fn enabled(&self) -> bool {
        wheel_gate::enabled(&self.data_dir, self.setting.load(Ordering::Relaxed))
    }

    /// The switch as set (it may be on while the confirmation is missing: the wheel is then off).
    pub fn switch_on(&self) -> bool {
        self.setting.load(Ordering::Relaxed)
    }

    pub fn table(&self) -> Table {
        self.table.read().unwrap().clone()
    }

    pub fn limits(&self) -> Limits {
        self.limits.read().unwrap().clone()
    }

    /// Owner turns the wheel on in the console: refused unless the phrase and the warning version are right. Writes the record, then the switch.
    pub fn turn_on(&self, now: u64, phrase: &str, version: u32, session: &str) -> Result<(), String> {
        wheel_gate::turn_on(&self.data_dir, now, phrase, version, session).map_err(|e| e.message().to_string())?;
        if !crate::config::override_set(&self.data_dir, "WHEEL", "on") {
            wheel_gate::turn_off(&self.data_dir, now);
            return Err(wheel_gate::GateError::Io.message().to_string());
        }
        self.setting.store(true, Ordering::Relaxed);
        Ok(())
    }

    /// Owner turns the wheel off: at once, no confirmation, the record is cleared.
    pub fn turn_off(&self, now: u64) -> bool {
        self.setting.store(false, Ordering::Relaxed);
        wheel_gate::turn_off(&self.data_dir, now);
        crate::config::override_set(&self.data_dir, "WHEEL", "off")
    }

    /// Owner changes the table. Refused (and kept as it was) when the average return is above 100% or a number is not allowed.
    pub fn set_table(&self, text: &str) -> Result<(), String> {
        let t = Table::parse(text)?;
        t.validate()?;
        if !crate::config::override_set(&self.data_dir, "WHEEL_TABLE", &t.to_text()) {
            return Err("the router could not save the table".into());
        }
        *self.table.write().unwrap() = t;
        Ok(())
    }

    /// Owner changes the limits. Each is checked; none is kept unless all are.
    pub fn set_limits(&self, l: Limits) -> Result<(), String> {
        if !(1..=120).contains(&l.max_bet) {
            return Err("the most to bet must be from 1 to 120 minutes".into());
        }
        if l.floor > 600 || l.spins_per_day == 0 || l.spins_per_day > 1000 || l.wait_secs > 3600 || l.win_cap_per_day > 100_000 {
            return Err("one of the limits is out of range".into());
        }
        let vals = [l.max_bet, l.floor, l.spins_per_day, l.wait_secs as u32, l.win_cap_per_day];
        for (k, v) in setting_names().iter().zip(vals) {
            if !crate::config::override_set(&self.data_dir, k, &v.to_string()) {
                return Err("the router could not save the limits".into());
            }
        }
        *self.limits.write().unwrap() = l;
        Ok(())
    }

    /// What the owner's console shows next to the switch.
    pub fn console_json(&self) -> String {
        let l = self.limits();
        let t = self.table();
        let (spins, bet, net) = self.stats();
        format!(
            "{{\"spins\":{},\"bet_min\":{},\"net_min\":{},\"on\":{},\"switch\":{},\"since\":{},\"warning_version\":{},\"phrase\":\"{}\",\"table\":\"{}\",\"rtp\":{},\"win\":{},\"max_bet\":{},\"floor\":{},\"spins_per_day\":{},\"wait\":{},\"win_cap\":{},\"low_rtp\":{}}}",
            spins,
            bet,
            net,
            self.enabled(),
            self.switch_on(),
            wheel_gate::confirmed_at(&self.data_dir).map_or("null".to_string(), |a| a.to_string()),
            wheel_gate::WARNING_VERSION,
            wheel_gate::PHRASE,
            t.to_text(),
            t.rtp_permille(),
            t.win_permille(),
            l.max_bet,
            l.floor,
            l.spins_per_day,
            l.wait_secs,
            l.win_cap_per_day,
            t.rtp_permille() < wheel::LOW_RTP_PERMILLE
        )
    }

    /// From the wheel log: how many spins were made, the minutes bet, and the customers' net result in minutes (a win is positive).
    pub fn stats(&self) -> (u64, u64, i64) {
        let text = std::fs::read_to_string(format!("{}/wheel.log", self.data_dir)).unwrap_or_default();
        let (mut spins, mut bet, mut net) = (0u64, 0u64, 0i64);
        for f in text.lines().map(|l| l.split(',').collect::<Vec<_>>()).filter(|f| f.len() >= 7 && f[1] == "spin") {
            spins += 1;
            bet += f[4].parse::<u64>().unwrap_or(0);
            net += f[6].parse::<i64>().unwrap_or(0);
        }
        (spins, bet, net)
    }

    /// The most this customer may bet right now (0 = cannot spin).
    fn most_bet(l: &Limits, left_min: u64) -> u32 {
        let by_half = left_min / 2;
        let by_floor = left_min.saturating_sub(l.floor as u64);
        (l.max_bet as u64).min(by_half).min(by_floor) as u32
    }

    /// `/wheel/state`: the table, the limits and where this customer stands. Needs paid time on this device.
    pub fn state(&self, files: &Mutex<()>, mac: &str, day: u64, now: u64) -> Reply {
        if !self.enabled() {
            return err(404, "OFF", "the wheel is off");
        }
        let (l, t) = (self.limits(), self.table());
        let (left_min, paused) = {
            let _g = files.lock().unwrap();
            match crate::roll::load(&self.roll_path).into_iter().find(|e| e.mac == mac) {
                Some(e) if e.left(now) > 0 => (e.left(now) / 60, e.paused()),
                _ => (0, false),
            }
        };
        // a device with no paid time still gets the table and the odds (anyone may read them); only spinning needs time
        let spins_left = self.days.spins_left(mac, day, &l);
        let max_bet = if paused || left_min == 0 { 0 } else { Self::most_bet(&l, left_min) };
        let reason = if left_min == 0 {
            "NO_SESSION"
        } else if paused {
            "PAUSED"
        } else if spins_left == 0 {
            "NO_SPINS"
        } else if max_bet == 0 {
            "TOO_LITTLE"
        } else {
            ""
        };
        let segs: Vec<String> = t.segs.iter().map(|s| format!("{{\"mult\":{},\"weight\":{}}}", s.mult, s.weight)).collect();
        let json = format!(
            "{{\"ok\":true,\"segments\":[{}],\"total\":{},\"rtp\":{},\"win\":{},\"left_min\":{},\"paused\":{},\"can_spin\":{},\"reason\":\"{}\",\"max_bet\":{},\"limit_max_bet\":{},\"floor\":{},\"spins_left\":{},\"wait\":{},\"win_room\":{},\"max_slices\":{},\"spins_per_day\":{},\"wait_secs\":{},\"win_cap\":{}}}",
            segs.join(","),
            t.total_weight(),
            t.rtp_permille(),
            t.win_permille(),
            left_min,
            paused,
            reason.is_empty(),
            reason,
            max_bet,
            l.max_bet,
            l.floor,
            spins_left,
            self.days.wait_left(mac, now, &l),
            self.days.win_room(mac, day, &l),
            wheel::MAX_SLOTS,
            l.spins_per_day,
            l.wait_secs,
            l.win_cap_per_day
        );
        (200, json)
    }

    /// `/wheel/spin`: one spin with this id. `rand` is a random number from the router's random source.
    #[allow(clippy::too_many_arguments)]
    pub fn spin(&self, files: &Mutex<()>, mac: &str, id: &str, bet: u32, day: u64, now: u64, rand: u64) -> Reply {
        if !self.enabled() {
            return err(404, "OFF", "the wheel is off");
        }
        if !wheel_spin::valid_id(id) {
            return err(400, "BAD_ID", "The spin could not be started. Reload the page.");
        }
        let (l, t) = (self.limits(), self.table());
        let _g = files.lock().unwrap();
        // a retry of a spin that was already made gets its first answer, whatever the day's limits say now
        // (a new spin is reserved first: a power cut can cost a customer a spin, never give one extra)
        let ticket = if wheel_spin::seen(&self.data_dir, id) {
            None
        } else {
            match self.days.reserve(mac, day, now, &l) {
                Ok(t) => Some(t),
                Err(LimitError::NoSpinsLeft) => return err(429, "NO_SPINS", "No spins left today. Come back tomorrow."),
                Err(LimitError::Wait(s)) => {
                    return (429, format!("{{\"error\":\"WAIT\",\"why\":\"Wait a few seconds before the next spin.\",\"wait\":{}}}", s))
                }
                Err(LimitError::Io) => return err(500, "IO", "The router could not note the spin. Nothing was charged."),
            }
        };
        let win_room = ticket.as_ref().map_or(0, |t| t.win_room);
        match wheel_spin::spin(&self.roll_path, &self.data_dir, now, mac, id, bet, &t, &l, rand, win_room) {
            Ok(o) => {
                if ticket.is_some() {
                    self.days.settle(mac, day, o.delta_min);
                }
                (
                    200,
                    format!(
                        "{{\"ok\":true,\"id\":\"{}\",\"bet\":{},\"segment\":{},\"mult\":{},\"delta\":{},\"capped\":{},\"left_min\":{},\"repeated\":{},\"spins_left\":{}}}",
                        o.id,
                        o.bet,
                        o.segment,
                        o.mult,
                        o.delta_min,
                        o.capped,
                        o.left_min_after,
                        o.repeated,
                        self.days.spins_left(mac, day, &l)
                    ),
                )
            }
            Err(e) => {
                if let Some(tk) = &ticket {
                    self.days.release(mac, day, tk);
                }
                match e {
                    SpinError::BadId => err(400, "BAD_ID", "The spin could not be started. Reload the page."),
                    SpinError::NoSession => err(403, "NO_SESSION", "Buy Wi-Fi time first, then the wheel opens."),
                    SpinError::Paused => err(403, "PAUSED", "Your time is paused. Resume it to spin."),
                    SpinError::Bet(b) => err(400, "BAD_BET", bet_words(&b)),
                    SpinError::Voided => err(409, "VOIDED", "That spin did not finish and nothing was charged. Spin again."),
                    SpinError::Io => err(500, "IO", "The router could not save the spin. Nothing was charged."),
                }
            }
        }
    }
}

/// The odds table as plain HTML rows (the page fills them in again from `/wheel/state`; a browser without script still reads the odds).
pub fn odds_rows(t: &Table) -> String {
    let n = t.total_weight().max(1);
    t.segs
        .iter()
        .map(|s| {
            let pct = (s.weight as f64 * 1000.0 / n as f64).round() / 10.0;
            let (label, pays) = if s.mult == 0 {
                ("LOSE".to_string(), "your bet is lost".to_string())
            } else {
                (format!("x{}", s.mult), format!("your bet x{}", s.mult))
            };
            format!("<tr><td>{}</td><td>{}</td><td>{} of {} ({}%)</td></tr>", label, pays, s.weight, n, pct)
        })
        .collect()
}

fn bet_words(b: &wheel::BetError) -> &'static str {
    use wheel::BetError::*;
    match b {
        Zero => "Bet at least 1 minute.",
        TooBig => "That is more than the most you can bet on one spin.",
        MoreThanHalf => "You can bet at most half of your time left.",
        BelowFloor => "A lost spin must leave you some minutes. Bet less.",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::roll::{load, save, Entry};

    const MAC: &str = "aa:bb:cc:00:00:01";
    const ID1: &str = "0123456789abcdef";
    const ID2: &str = "fedcba9876543210";
    const ID3: &str = "00112233445566778899aabbccddeeff";
    const NOW: u64 = 1_000_000;
    const PHRASE: &str = "I TAKE RESPONSIBILITY";

    fn entry(tl: u64, fp: u64) -> Entry {
        Entry::parse(&format!("c1,0,0,0,0,{},{},{},0,0,0,hyper,w1,10,{},0,1", tl, fp, MAC, tl)).unwrap()
    }

    /// a router data folder with one paying customer of 60 minutes, a wheel that is switched off
    fn world(name: &str) -> (Wheel, Mutex<()>, String) {
        let d = std::env::temp_dir().join(format!("pisoportal-wheelrun-{}-{}", name, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let dir = d.to_string_lossy().into_owned();
        let roll = format!("{}/roll.csv", dir);
        // 60 minutes bought, started now; a long plan for the test that goes to the next day
        save(&roll, &[entry(if name == "limits" { 3000 } else { 60 }, NOW)]).unwrap();
        (Wheel::new(&dir, &roll, false), Mutex::new(()), dir)
    }

    fn on(w: &Wheel) {
        w.turn_on(NOW, PHRASE, wheel_gate::WARNING_VERSION, "s1").unwrap();
    }

    fn left_min(dir: &str) -> u64 {
        load(&format!("{}/roll.csv", dir))[0].left(NOW) / 60
    }

    fn field(json: &str, key: &str) -> String {
        crate::util::jget(json, key)
    }

    #[test]
    fn rt_the_wheel_is_off_until_the_owner_confirmed_and_everything_answers_off() {
        let (w, files, dir) = world("off");
        assert!(!w.enabled());
        assert_eq!(w.state(&files, MAC, 5, NOW).0, 404);
        assert_eq!(w.spin(&files, MAC, ID1, 5, 5, NOW, 0).0, 404);
        assert_eq!(left_min(&dir), 60, "an off wheel never touches time");
        // the setting typed into a file is not enough (no record of the owner's confirmation)
        assert!(crate::config::override_set(&dir, "WHEEL", "on"));
        let w2 = Wheel::new(&dir, &format!("{}/roll.csv", dir), false);
        assert!(w2.switch_on() && !w2.enabled());
        assert_eq!(w2.spin(&files, MAC, ID1, 5, 5, NOW, 0).0, 404);
        assert!(std::fs::read_to_string(format!("{}/wheel.log", dir)).unwrap().contains("stays off"));
        // the configuration file's own WHEEL=on is not enough either
        let w3 = Wheel::new(&dir, &format!("{}/roll.csv", dir), true);
        assert!(!w3.enabled());
    }

    #[test]
    fn rt_turning_on_needs_the_exact_phrase_and_off_clears_the_confirmation() {
        let (w, files, dir) = world("gate");
        assert!(w.turn_on(NOW, "i take responsibility", 1, "s1").is_err());
        assert!(w.turn_on(NOW, PHRASE, 0, "s1").is_err());
        assert!(!w.enabled() && !w.switch_on());
        assert!(w.turn_on(NOW, PHRASE, 1, "s1").is_ok());
        assert!(w.enabled());
        assert_eq!(w.state(&files, MAC, 5, NOW).0, 200);
        // a restart keeps it on
        assert!(Wheel::new(&dir, &format!("{}/roll.csv", dir), false).enabled());
        assert!(w.turn_off(NOW + 1));
        assert!(!w.enabled());
        assert_eq!(w.state(&files, MAC, 5, NOW).0, 404);
        // the next turn on asks again: the old switch alone does nothing
        assert!(crate::config::override_set(&dir, "WHEEL", "on"));
        assert!(!Wheel::new(&dir, &format!("{}/roll.csv", dir), false).enabled());
    }

    #[test]
    fn rt_a_spin_changes_the_time_once_and_a_retry_gets_the_first_answer() {
        let (w, files, dir) = world("spin");
        on(&w);
        w.set_table("2x1 0x1").unwrap();
        let (code, body) = w.spin(&files, MAC, ID1, 5, 5, NOW, 0);
        assert_eq!(code, 200, "{}", body);
        assert_eq!((field(&body, "mult").as_str(), field(&body, "delta").as_str(), field(&body, "left_min").as_str()), ("2", "5", "65"));
        assert_eq!(field(&body, "spins_left"), "9");
        assert_eq!(left_min(&dir), 65);
        // the page asks again (double tap, a retry after a lost answer): same answer, no second charge, no second spin used
        let (code, again) = w.spin(&files, MAC, ID1, 9, 5, NOW + 1, 1);
        assert_eq!(code, 200);
        assert_eq!(
            (field(&again, "repeated").as_str(), field(&again, "delta").as_str(), field(&again, "bet").as_str()),
            ("true", "5", "5")
        );
        assert_eq!(field(&again, "spins_left"), "9");
        assert_eq!(left_min(&dir), 65);
    }

    #[test]
    fn rt_the_wait_between_spins_and_the_spins_a_day_are_kept_by_the_router() {
        let (w, files, dir) = world("limits");
        on(&w);
        w.set_table("0x1").unwrap();
        w.set_limits(Limits { max_bet: 10, floor: 5, spins_per_day: 2, wait_secs: 10, win_cap_per_day: 60 }).unwrap();
        assert_eq!(w.spin(&files, MAC, ID1, 1, 5, NOW, 0).0, 200);
        let (code, body) = w.spin(&files, MAC, ID2, 1, 5, NOW + 3, 0);
        assert_eq!((code, field(&body, "error").as_str(), field(&body, "wait").as_str()), (429, "WAIT", "7"));
        assert_eq!(left_min(&dir), 2999, "a refused spin charges nothing");
        assert_eq!(w.spin(&files, MAC, ID2, 1, 5, NOW + 11, 0).0, 200);
        let (code, body) = w.spin(&files, MAC, ID3, 1, 5, NOW + 30, 0);
        assert_eq!((code, field(&body, "error").as_str()), (429, "NO_SPINS"));
        // a retry of a spin already made is still answered
        assert_eq!(field(&w.spin(&files, MAC, ID1, 1, 5, NOW + 31, 0).1, "repeated"), "true");
        // the next day counts again
        assert_eq!(w.spin(&files, MAC, ID3, 1, 6, NOW + 90_000, 0).0, 200);
    }

    #[test]
    fn rt_a_refused_bet_gives_the_spin_and_the_wait_back() {
        let (w, files, dir) = world("bet");
        on(&w);
        for bet in [0, 11, 40] {
            let (code, body) = w.spin(&files, MAC, ID1, bet, 5, NOW, 0);
            assert_eq!((code, field(&body, "error").as_str()), (400, "BAD_BET"), "bet {}", bet);
        }
        assert_eq!(left_min(&dir), 60);
        // nothing was used: a good bet right away is not made to wait and still has all 10 spins
        let (code, body) = w.spin(&files, MAC, ID1, 5, 5, NOW, 0);
        assert_eq!(code, 200, "{}", body);
        assert_eq!(field(&body, "spins_left"), "9");
    }

    #[test]
    fn rt_no_paid_time_or_a_paused_session_cannot_spin_and_the_page_is_told() {
        let (w, files, dir) = world("session");
        on(&w);
        // a device with no paid time still gets the odds (feature 006) but cannot spin, and a spin charges nothing
        let (code, st) = w.state(&files, "aa:bb:cc:00:00:99", 5, NOW);
        assert_eq!(code, 200, "{}", st);
        assert_eq!(
            (
                field(&st, "can_spin").as_str(),
                field(&st, "reason").as_str(),
                field(&st, "max_bet").as_str(),
                field(&st, "left_min").as_str()
            ),
            ("false", "NO_SESSION", "0", "0")
        );
        assert_eq!((field(&st, "rtp").as_str(), field(&st, "total").as_str()), ("900", "20"));
        assert!(st.contains("{\"mult\":5,\"weight\":1}"));
        assert_eq!(w.spin(&files, "aa:bb:cc:00:00:99", ID1, 5, 5, NOW, 0).0, 403);
        assert_eq!(left_min(&dir), 60, "a refused spin charges nothing");
        let roll = format!("{}/roll.csv", dir);
        let mut e = load(&roll);
        e[0].pa = NOW - 10;
        e[0].rp = 3000;
        save(&roll, &e).unwrap();
        let (code, body) = w.spin(&files, MAC, ID1, 5, 5, NOW, 0);
        assert_eq!((code, field(&body, "error").as_str()), (403, "PAUSED"));
        let st = w.state(&files, MAC, 5, NOW).1;
        assert_eq!(
            (field(&st, "max_bet").as_str(), field(&st, "can_spin").as_str(), field(&st, "reason").as_str()),
            ("0", "false", "PAUSED")
        );
    }

    #[test]
    fn rt_the_state_says_can_spin_for_a_paying_device_and_why_not_otherwise() {
        let (w, files, _dir) = world("canspin");
        on(&w);
        w.set_limits(Limits { spins_per_day: 1, wait_secs: 0, ..Limits::default_limits() }).unwrap();
        let st = w.state(&files, MAC, 5, NOW).1;
        assert_eq!((field(&st, "can_spin").as_str(), field(&st, "reason").as_str()), ("true", ""));
        w.set_table("0x1").unwrap();
        assert_eq!(w.spin(&files, MAC, ID1, 5, 5, NOW, 0).0, 200);
        let st = w.state(&files, MAC, 5, NOW).1;
        assert_eq!((field(&st, "can_spin").as_str(), field(&st, "reason").as_str()), ("false", "NO_SPINS"));
    }

    #[test]
    fn rt_the_state_tells_the_page_the_odds_and_what_it_may_bet() {
        let (w, files, _dir) = world("state");
        on(&w);
        let (code, s) = w.state(&files, MAC, 5, NOW);
        assert_eq!(code, 200);
        assert_eq!((field(&s, "rtp").as_str(), field(&s, "win").as_str(), field(&s, "total").as_str()), ("900", "350", "20"));
        // 60 minutes left: bet at most 10 (the limit), not more than half (30), 5 must stay (55)
        assert_eq!((field(&s, "max_bet").as_str(), field(&s, "spins_left").as_str(), field(&s, "wait").as_str()), ("10", "10", "0"));
        assert!(s.contains("{\"mult\":0,\"weight\":13}") && s.contains("{\"mult\":5,\"weight\":1}"));
    }

    #[test]
    fn rt_a_win_is_cut_to_what_the_day_still_allows() {
        let (w, files, _dir) = world("cap");
        on(&w);
        w.set_table("5x1 0x9").unwrap();
        w.set_limits(Limits { max_bet: 10, floor: 5, spins_per_day: 10, wait_secs: 0, win_cap_per_day: 10 }).unwrap();
        let (_, a) = w.spin(&files, MAC, ID1, 5, 5, NOW, 0);
        assert_eq!((field(&a, "delta").as_str(), field(&a, "capped").as_str()), ("10", "true"));
        let (_, b) = w.spin(&files, MAC, ID2, 5, 5, NOW + 1, 0);
        assert_eq!((field(&b, "delta").as_str(), field(&b, "capped").as_str()), ("0", "true"));
    }

    #[test]
    fn rt_the_owner_sees_how_many_spins_and_the_net_minutes_customers_won_or_lost() {
        let (w, files, _dir) = world("stats");
        on(&w);
        w.set_table("2x1 0x1").unwrap();
        w.set_limits(Limits { wait_secs: 0, ..Limits::default_limits() }).unwrap();
        w.spin(&files, MAC, ID1, 5, 5, NOW, 0); // +5
        w.spin(&files, MAC, ID2, 3, 5, NOW + 1, 1); // -3
        w.spin(&files, MAC, ID2, 3, 5, NOW + 2, 1); // a retry is not a second spin
        assert_eq!(w.stats(), (2, 8, 2));
        let j = w.console_json();
        assert_eq!((field(&j, "spins").as_str(), field(&j, "net_min").as_str(), field(&j, "on").as_str()), ("2", "2", "true"));
        assert_eq!((field(&j, "phrase").as_str(), field(&j, "warning_version").as_str()), (PHRASE, "1"));
    }

    #[test]
    fn rt_a_table_above_100_percent_or_bad_limits_are_refused_and_the_old_ones_stay() {
        let (w, _files, dir) = world("settings");
        assert!(w.set_table("2x3 0x1").is_err(), "150% is refused");
        assert!(w.set_table("0x0").is_err());
        assert!(w.set_table("banana").is_err());
        assert_eq!(w.table(), Table::default_table());
        assert!(w.set_limits(Limits { max_bet: 0, ..Limits::default_limits() }).is_err());
        assert!(w.set_limits(Limits { spins_per_day: 0, ..Limits::default_limits() }).is_err());
        assert_eq!(w.limits(), Limits::default_limits());
        // a good change is kept across a restart
        w.set_table("0x3 2x1").unwrap();
        w.set_limits(Limits { max_bet: 7, ..Limits::default_limits() }).unwrap();
        let again = Wheel::new(&dir, &format!("{}/roll.csv", dir), false);
        assert_eq!((again.table().to_text().as_str(), again.limits().max_bet), ("0x3 2x1", 7));
    }

    #[test]
    fn rt_a_saved_table_above_the_cap_still_works_and_the_state_names_the_slice_cap_and_limits() {
        let (w, files, dir) = world("cap24");
        // saved by an earlier release (101 slots): loaded as it is, not replaced by the default
        assert!(crate::config::override_set(&dir, "WHEEL_TABLE", "0x66 2x25 3x6 5x4"));
        let w2 = Wheel::new(&dir, &format!("{}/roll.csv", dir), false);
        assert_eq!(w2.table().total_weight(), 101);
        on(&w2);
        // a new or edited table is refused above 24 and the old one stays
        assert!(w2.set_table("0x16 2x8").is_ok());
        assert!(w2.set_table("0x60 2x30").is_err());
        assert_eq!(w2.table().total_weight(), 24);
        let st = w2.state(&files, MAC, 5, NOW).1;
        assert_eq!((field(&st, "max_slices").as_str(), field(&st, "total").as_str()), ("24", "24"));
        assert_eq!(
            (field(&st, "spins_per_day").as_str(), field(&st, "wait_secs").as_str(), field(&st, "win_cap").as_str()),
            ("10", "10", "60")
        );
        drop(w);
    }

    #[test]
    fn rt_the_odds_table_is_in_the_page_itself_for_a_browser_without_script() {
        let rows = odds_rows(&Table::default_table());
        assert_eq!(rows.matches("<tr>").count(), 4);
        assert!(rows.contains("<td>LOSE</td><td>your bet is lost</td><td>13 of 20 (65%)</td>"), "{}", rows);
        assert!(rows.contains("<td>x5</td><td>your bet x5</td><td>1 of 20 (5%)</td>"), "{}", rows);
        assert!(!rows.contains("%ODDS%"));
    }
}
