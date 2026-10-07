//! The heart: one coin window at a time (one customer pays; everybody else is turned away), the box worker that arms the
//! slot and counts coins, the settlement (price the whole window once, record it, grant access once, acknowledge the
//! box), recovery after a restart, and the cooldown for devices that keep opening empty windows.
use crate::boxlink::{parse_event, BoxLink};
use crate::config::Config;
use crate::ledger;
use crate::nds::Nds;
use crate::pricing::{minutes_for, rates, Plan};
use crate::roll::{self, MintError};
use crate::util::{jget, jget_u64, json_esc, now_secs, random_hex};
use std::collections::HashMap;
use std::fs;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

macro_rules! log {
    ($($a:tt)*) => { eprintln!("coinslot: {}", format!($($a)*)) };
}
pub(crate) use log;

#[derive(Clone, Debug, PartialEq)]
pub enum View {
    Idle { left: u64, plan: Option<Plan>, online: bool, busy: u64, cooldown: u64 },
    Starting { plan: Plan },
    Armed { plan: Plan, pulses: u32, minutes: u32, remaining: u64, total: u64 },
    Closing { plan: Plan, pulses: u32, minutes: u32 },
    Final { plan: Plan, pulses: u32, minutes: u32, left: u64, online: bool },
    Empty,
    Error(String),
    Busy(u64),
    Cooldown(u64),
    Mismatch { plan: Plan, left: u64 },
}

impl View {
    pub fn to_json(&self) -> String {
        match self {
            View::Idle { left, plan, online, busy, cooldown } => format!(
                "{{\"t\":\"state\",\"s\":\"idle\",\"left\":{},\"plan\":\"{}\",\"online\":{},\"busy\":{},\"cooldown\":{}}}",
                left,
                plan.map(|p| p.as_str()).unwrap_or(""),
                online,
                busy,
                cooldown
            ),
            View::Starting { plan } => format!("{{\"t\":\"state\",\"s\":\"starting\",\"plan\":\"{}\"}}", plan.as_str()),
            View::Armed { plan, pulses, minutes, remaining, total } => format!(
                "{{\"t\":\"state\",\"s\":\"armed\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{},\"remaining\":{},\"total\":{}}}",
                plan.as_str(),
                pulses,
                minutes,
                remaining,
                total
            ),
            View::Closing { plan, pulses, minutes } => {
                format!(
                    "{{\"t\":\"state\",\"s\":\"closing\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{}}}",
                    plan.as_str(),
                    pulses,
                    minutes
                )
            }
            View::Final { plan, pulses, minutes, left, online } => format!(
                "{{\"t\":\"state\",\"s\":\"final\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{},\"left\":{},\"online\":{}}}",
                plan.as_str(),
                pulses,
                minutes,
                left,
                online
            ),
            View::Empty => "{\"t\":\"state\",\"s\":\"empty\"}".to_string(),
            View::Error(e) => format!("{{\"t\":\"state\",\"s\":\"error\",\"e\":\"{}\"}}", json_esc(e)),
            View::Busy(r) => format!("{{\"t\":\"state\",\"s\":\"busy\",\"retry\":{}}}", r),
            View::Cooldown(r) => format!("{{\"t\":\"state\",\"s\":\"cooldown\",\"retry\":{}}}", r),
            View::Mismatch { plan, left } => {
                format!("{{\"t\":\"state\",\"s\":\"mismatch\",\"plan\":\"{}\",\"left\":{}}}", plan.as_str(), left)
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum WState {
    Starting,
    Armed,
    Closing,
}

struct Window {
    sid: String,
    wid: String,
    mac: String,
    plan: Plan,
    forfeit: bool,
    state: WState,
    pulses: u32,
    last_seq: u64,
    deadline: Instant,
    total: u64,
    stop: bool,
    box_ended: bool,
}

#[derive(Default)]
pub struct FairState {
    pub used_kb: u64,
    pub offset_kb: u64,
    pub throttled: bool,
    pub since: u64,
}

pub struct State {
    window: Option<Window>,
    results: HashMap<String, (u64, View)>,
    version: u64,
    empties: HashMap<String, Vec<u64>>,
    cooldown: HashMap<String, u64>,
    pub fair: HashMap<String, FairState>,
}

pub struct Core {
    pub cfg: Config,
    pub box_: BoxLink,
    pub nds: Nds,
    pub st: Mutex<State>,
    cv: Condvar,
    /// serialises the roll, the ledger and the open-window records
    pub files: Mutex<()>,
    /// one recovery at a time (the maintenance thread and a customer's start can both ask for one)
    recovering: Mutex<()>,
}

/// More pesos than one coin window can physically take (about two minutes of coins): an answer above this is not trusted.
pub const MAX_PULSES: u32 = 5000;

impl Core {
    pub fn new(cfg: Config) -> Arc<Core> {
        let box_ = BoxLink::new(&cfg.gw_box, &cfg.gw_key);
        let nds = Nds { ndsctl: cfg.ndsctl.clone() };
        Arc::new(Core {
            cfg,
            box_,
            nds,
            st: Mutex::new(State {
                window: None,
                results: HashMap::new(),
                version: 1,
                empties: HashMap::new(),
                cooldown: HashMap::new(),
                fair: HashMap::new(),
            }),
            cv: Condvar::new(),
            files: Mutex::new(()),
            recovering: Mutex::new(()),
        })
    }

    fn bump(&self, st: &mut State) {
        st.version += 1;
        self.cv.notify_all();
    }

    pub fn version(&self) -> u64 {
        self.st.lock().unwrap().version
    }

    /// Wait until something changed since `last` (or the timeout): the current version.
    pub fn wait_version(&self, last: u64, timeout: Duration) -> u64 {
        let g = self.st.lock().unwrap();
        let (g, _) = self.cv.wait_timeout_while(g, timeout, |s| s.version == last).unwrap();
        g.version
    }

    // ---- what a device sees -------------------------------------------------------------------------------------------------
    pub fn view_for(&self, mac: &str) -> View {
        let now = now_secs();
        {
            let st = self.st.lock().unwrap();
            if let Some(w) = &st.window {
                if w.mac == mac {
                    return self.window_view(w);
                }
            }
            if let Some((at, v)) = st.results.get(mac) {
                if now.saturating_sub(*at) < 120 {
                    return v.clone();
                }
            }
        }
        let (plan, left) = match roll::peek(&self.cfg.roll_path(), now, mac) {
            Some((p, l)) => (Some(p), l),
            None => (None, 0),
        };
        let st = self.st.lock().unwrap();
        let busy = if st.window.as_ref().map(|w| w.mac != mac).unwrap_or(false) { self.cfg.busy_retry } else { 0 };
        let cooldown = st.cooldown.get(mac).map(|u| u.saturating_sub(now)).unwrap_or(0);
        View::Idle { left, plan, online: left > 0, busy, cooldown }
    }

    fn window_view(&self, w: &Window) -> View {
        let minutes = minutes_for(&self.cfg, w.plan, w.pulses);
        match w.state {
            WState::Starting => View::Starting { plan: w.plan },
            WState::Armed => View::Armed {
                plan: w.plan,
                pulses: w.pulses,
                minutes,
                remaining: w.deadline.saturating_duration_since(Instant::now()).as_secs(),
                total: w.total,
            },
            WState::Closing => View::Closing { plan: w.plan, pulses: w.pulses, minutes },
        }
    }

    /// The page asks for a fresh start (Try again, Add more time): forget the result of its last window.
    pub fn clear_result(&self, mac: &str) {
        let mut st = self.st.lock().unwrap();
        if st.results.remove(mac).is_some() {
            self.bump(&mut st);
        }
    }

    /// A device that is not online but has paid time on the roll (after a power cut or a router restart openNDS forgot
    /// its sessions) is put back online. Returns true when it is online afterwards.
    pub fn reconnect(&self, mac: &str) -> bool {
        let now = now_secs();
        let _g = self.files.lock().unwrap();
        let Some(s) = roll::session(&self.cfg.roll_path(), now, mac) else { return false };
        let cur = self.nds.state(mac);
        if cur == "Authenticated" {
            return true;
        }
        let ok = self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup);
        log!("reconnect {} {} min {}", mac, s.minutes, if ok { "granted" } else { "REFUSED by openNDS" });
        ok
    }

    // ---- the coin window ------------------------------------------------------------------------------------------------------
    pub fn cooldown_left(&self, mac: &str) -> u64 {
        let now = now_secs();
        self.st.lock().unwrap().cooldown.get(mac).map(|u| u.saturating_sub(now)).unwrap_or(0)
    }

    fn note_empty(&self, mac: &str) {
        let now = now_secs();
        let mut st = self.st.lock().unwrap();
        let list = st.empties.entry(mac.to_string()).or_default();
        list.push(now);
        list.retain(|t| now.saturating_sub(*t) <= self.cfg.empty_window);
        let n = list.len();
        if n >= self.cfg.empty_limit {
            st.cooldown.insert(mac.to_string(), now + self.cfg.empty_cooldown);
            log!("alert grief: {} opened {} empty coin windows in {}s", mac, n, self.cfg.empty_window);
        }
    }

    fn clear_empty(&self, mac: &str) {
        let mut st = self.st.lock().unwrap();
        st.empties.remove(mac);
        st.cooldown.remove(mac);
    }

    /// Open a coin window for a device. Returns what the device should see now.
    pub fn start(self: &Arc<Self>, mac: &str, plan: Plan, forfeit: bool) -> View {
        let cd = self.cooldown_left(mac);
        if cd > 0 {
            log!("start refused for {}: cooldown {}s after empty windows", mac, cd);
            return View::Cooldown(cd);
        }
        if let Some((p, left)) = roll::peek(&self.cfg.roll_path(), now_secs(), mac) {
            if p != plan && !forfeit {
                return View::Mismatch { plan: p, left };
            }
        }
        if self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac != mac) {
            return View::Busy(self.cfg.busy_retry);
        }
        // Coins of an earlier window that are recorded but not yet acknowledged on the box must be settled first.
        self.recover();
        if self.unacked() > 0 {
            return View::Error("ACK_PENDING".into());
        }
        let mut st = self.st.lock().unwrap();
        if let Some(w) = &st.window {
            return if w.mac == mac { self.window_view(w) } else { View::Busy(self.cfg.busy_retry) };
        }
        st.results.remove(mac);
        let sid = random_hex(32);
        let wid = random_hex(32);
        st.window = Some(Window {
            sid: sid.clone(),
            wid,
            mac: mac.to_string(),
            plan,
            forfeit,
            state: WState::Starting,
            pulses: 0,
            last_seq: 0,
            deadline: Instant::now() + Duration::from_secs(self.cfg.first_wait),
            total: self.cfg.first_wait,
            stop: false,
            box_ended: false,
        });
        self.bump(&mut st);
        drop(st);
        log!("window {} opened for {} plan {}", &sid[..8], mac, plan.as_str());
        let (core, worker_sid) = (Arc::clone(self), sid.clone());
        if std::thread::Builder::new().stack_size(128 * 1024).spawn(move || core.run_window(&worker_sid)).is_err() {
            // without its worker the window would hold the coin slot for everybody, for ever
            log!("window {} could not start (no thread)", &sid[..8]);
            let mut st = self.st.lock().unwrap();
            st.window = None;
            self.bump(&mut st);
            return View::Error("NO_THREAD".into());
        }
        View::Starting { plan }
    }

    /// The customer is done (or cancels): close the window now.
    pub fn finish(&self, mac: &str) {
        let mut st = self.st.lock().unwrap();
        if let Some(w) = st.window.as_mut() {
            if w.mac == mac {
                w.stop = true;
                self.cv.notify_all();
            }
        }
    }

    /// A signed coin event from the box.
    pub fn on_event_line(&self, line: &str) {
        let Some(ev) = parse_event(line, &self.cfg.gw_key) else { return };
        if ev.pulses > MAX_PULSES {
            log!("coin event ignored: {} pesos is not possible in one window", ev.pulses);
            return;
        }
        let mut st = self.st.lock().unwrap();
        let Some(w) = st.window.as_mut() else { return };
        if w.sid != ev.sid || w.wid != ev.wid || ev.seq <= w.last_seq {
            return; // another window, or a repeat (the box sends every line twice)
        }
        w.last_seq = ev.seq;
        if ev.pulses > w.pulses {
            w.pulses = ev.pulses;
        }
        if ev.kind == "end" {
            w.box_ended = true;
        }
        self.bump(&mut st);
    }

    /// The box's count for the live window (it only ever grows); the count in use afterwards.
    fn set_pulses(&self, sid: &str, pulses: u32) -> u32 {
        let mut st = self.st.lock().unwrap();
        if pulses > MAX_PULSES {
            log!("window {} ignored a count of {} pesos from the box (not possible in one window)", &sid[..8], pulses);
            return st.window.as_ref().filter(|w| w.sid == sid).map(|w| w.pulses).unwrap_or(0);
        }
        let mut cur = pulses;
        if let Some(w) = st.window.as_mut().filter(|w| w.sid == sid) {
            if pulses > w.pulses {
                w.pulses = pulses;
                self.bump(&mut st);
            } else {
                cur = w.pulses;
            }
        }
        cur
    }

    fn cur(&self, sid: &str) -> Option<(u32, bool, bool, bool)> {
        let st = self.st.lock().unwrap();
        st.window.as_ref().filter(|w| w.sid == sid).map(|w| (w.pulses, w.stop, w.box_ended, w.state == WState::Armed))
    }

    fn set_state(&self, sid: &str, f: impl FnOnce(&mut Window)) {
        let mut st = self.st.lock().unwrap();
        if let Some(w) = st.window.as_mut().filter(|w| w.sid == sid) {
            f(w);
            self.bump(&mut st);
        }
    }

    /// Wait for a coin event, a stop request, or the timeout.
    fn wait_activity(&self, sid: &str, dur: Duration, seen: u32) {
        let g = self.st.lock().unwrap();
        let _ = self
            .cv
            .wait_timeout_while(g, dur, |s| match s.window.as_ref().filter(|w| w.sid == sid) {
                Some(w) => !w.stop && !w.box_ended && w.pulses == seen,
                None => false,
            })
            .unwrap();
    }

    /// Wait until the box reports the end of the window (its "end" event) or the timeout. A stop request does not cut
    /// this wait short: while closing, the box must be given time.
    fn wait_end(&self, sid: &str, dur: Duration) {
        let g = self.st.lock().unwrap();
        let _ = self.cv.wait_timeout_while(g, dur, |s| s.window.as_ref().filter(|w| w.sid == sid).is_some_and(|w| !w.box_ended)).unwrap();
    }

    fn evx(&self, wid: &str) -> String {
        if self.cfg.event_port != 0 {
            format!("&wid={}&evport={}", wid, self.cfg.event_port)
        } else {
            String::new()
        }
    }

    fn finish_window(&self, sid: &str, view: View, noted: Option<bool>) {
        let mac = {
            let mut st = self.st.lock().unwrap();
            let mac = st.window.as_ref().filter(|w| w.sid == sid).map(|w| w.mac.clone());
            if let Some(m) = &mac {
                st.window = None;
                st.results.insert(m.clone(), (now_secs(), view));
                self.bump(&mut st);
            }
            mac
        };
        if let (Some(m), Some(paid)) = (mac, noted) {
            if paid {
                self.clear_empty(&m);
            } else {
                self.note_empty(&m);
            }
        }
    }

    fn run_window(self: &Arc<Self>, sid: &str) {
        let Some((wid, mac, plan, forfeit)) = ({
            let st = self.st.lock().unwrap();
            st.window.as_ref().filter(|w| w.sid == sid).map(|w| (w.wid.clone(), w.mac.clone(), w.plan, w.forfeit))
        }) else {
            return;
        };
        let cfg = &self.cfg;
        let evx = self.evx(&wid);
        let t_arm = Instant::now();
        let ans = self.box_.call(sid, "arm", &format!("&duration={}{}", cfg.first_wait + 3, evx));
        let ans = match ans {
            Ok(b) if BoxLink::is_success(&b) => b,
            Ok(b) => {
                let e = jget(&b, "error");
                log!("window {} could not arm: {}", &sid[..8], if e.is_empty() { "NO_ANSWER" } else { &e });
                self.finish_window(sid, View::Error(if e.is_empty() { "NO_ANSWER".into() } else { e }), None);
                return;
            }
            Err(e) => {
                log!("window {} could not arm: {}", &sid[..8], e);
                self.finish_window(sid, View::Error("NO_ANSWER".into()), None);
                return;
            }
        };
        let events = !evx.is_empty() && jget(&ans, "events") == "true";
        let ready = jget_u64(&ans, "ready_in_ms").unwrap_or(0);
        if ready > 5000 {
            let _ = self.box_.call(sid, "release", "");
            self.finish_window(sid, View::Error("BAD_SETTLE_TIME".into()), None);
            return;
        }
        if ready > 0 {
            // the acceptor ignores pulses while it settles after power-on: the countdown starts after that
            std::thread::sleep(Duration::from_millis(ready));
            let _ = self.box_.call(sid, "arm", &format!("&duration={}{}", cfg.first_wait + 3, evx));
        }
        let cap = Instant::now() + Duration::from_secs(cfg.max_window);
        let mut deadline = Instant::now() + Duration::from_secs(cfg.first_wait);
        let first = jget_u64(&ans, "pulses").unwrap_or(0) as u32;
        self.set_state(sid, |w| {
            w.state = WState::Armed;
            w.deadline = deadline;
            w.total = cfg.first_wait;
            if first > w.pulses {
                w.pulses = first;
            }
        });
        log!("timing {} armed events={} in {} ms", &sid[..8], events, t_arm.elapsed().as_millis());
        let mut last = first;
        let mut noted = false;
        let poll = Duration::from_millis(if events { cfg.poll_ms } else { 250 });
        let mut next_poll = Instant::now() + poll;
        let mut boxstate_armed = true;
        loop {
            let seen = self.cur(sid).map(|c| c.0).unwrap_or(last);
            let wait = next_poll.saturating_duration_since(Instant::now()).min(deadline.saturating_duration_since(Instant::now()));
            self.wait_activity(sid, wait, seen);
            let Some((mut pulses, stop, ended, _)) = self.cur(sid) else { return };
            if stop {
                break;
            }
            if Instant::now() >= next_poll {
                next_poll = Instant::now() + poll;
                if let Ok(st) = self.box_.call(sid, "status", "") {
                    if BoxLink::is_success(&st) {
                        pulses = self.set_pulses(sid, jget_u64(&st, "pulses").unwrap_or(0) as u32);
                        boxstate_armed = jget(&st, "state") == "armed";
                    }
                } // a missed poll must not end the window early
            }
            if ended {
                boxstate_armed = false;
            }
            if pulses > 0 && !noted {
                noted = true;
                self.note_open(sid, &mac, plan, &wid, forfeit);
                log!("timing {} first coin pulses={} at={} ms", &sid[..8], pulses, t_arm.elapsed().as_millis());
            }
            if pulses > last {
                last = pulses;
                deadline = (Instant::now() + Duration::from_secs(cfg.idle_wait)).min(cap);
                let total = cfg.idle_wait;
                self.set_state(sid, |w| {
                    w.deadline = deadline;
                    w.total = total;
                });
                let rem = deadline.saturating_duration_since(Instant::now()).as_secs();
                let _ = self.box_.call(sid, "arm", &format!("&duration={}{}", rem + 3, evx));
            }
            if Instant::now() >= deadline || !boxstate_armed {
                break;
            }
        }
        // closing: release the acceptor (a lost packet must not leave it powered), then count the coins still in flight
        // until the box says it is idle (or sends "end"); one status call per 300 ms at most
        self.set_state(sid, |w| w.state = WState::Closing);
        for _ in 0..3 {
            if self.box_.call(sid, "release", "").map(|b| BoxLink::is_success(&b)).unwrap_or(false) {
                break;
            }
            std::thread::sleep(Duration::from_millis(300));
        }
        let drain_end = Instant::now() + Duration::from_secs(cfg.drain_secs);
        loop {
            let ended = self.cur(sid).is_some_and(|c| c.2);
            let mut idle = false;
            if !ended {
                if let Ok(st) = self.box_.call(sid, "status", "") {
                    if BoxLink::is_success(&st) {
                        self.set_pulses(sid, jget_u64(&st, "pulses").unwrap_or(0) as u32);
                        idle = jget(&st, "state") == "idle";
                    }
                }
            }
            // a coin counted only now must still survive a restart
            if !noted && self.cur(sid).is_some_and(|c| c.0 > 0) {
                noted = true;
                self.note_open(sid, &mac, plan, &wid, forfeit);
            }
            if ended || idle || Instant::now() >= drain_end {
                break;
            }
            self.wait_end(sid, Duration::from_millis(300));
        }
        let pulses = self.cur(sid).map(|c| c.0).unwrap_or(last);
        if pulses == 0 {
            log!("window {} closed with no coins", &sid[..8]);
            self.finish_window(sid, View::Empty, Some(false));
            return;
        }
        let view = self.settle(sid, &mac, plan, &wid, forfeit, pulses);
        self.finish_window(sid, view, Some(true));
    }

    // ---- recording, granting, acknowledging --------------------------------------------------------------------------------------
    fn open_file(&self, sid: &str) -> String {
        format!("{}/{}", self.cfg.open_dir(), sid)
    }

    /// The first coin leaves a record on flash, kept until the box is acknowledged: if the router restarts mid-window,
    /// recovery credits the coins. Nothing is granted yet.
    fn note_open(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool) {
        let _ = fs::create_dir_all(self.cfg.open_dir());
        let _ = fs::write(self.open_file(sid), format!("{} {} {} {}\n", mac, plan.as_str(), wid, forfeit as u8));
    }

    fn ack(&self, sid: &str) -> bool {
        self.box_.call(sid, "ack", "").map(|b| BoxLink::is_success(&b)).unwrap_or(false)
    }

    /// Price the window once, record it, grant access once, acknowledge the box.
    fn settle(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool, pulses: u32) -> View {
        let minutes = minutes_for(&self.cfg, plan, pulses);
        let now = now_secs();
        if let Err(e) = self.record(sid, mac, plan, wid, forfeit, pulses, minutes, now) {
            return e;
        }
        let ack_ok = self.ack_and_clear(sid);
        let s = {
            let _g = self.files.lock().unwrap();
            roll::session(&self.cfg.roll_path(), now_secs(), mac)
        };
        let Some(s) = s else { return View::Error("NO_SESSION".into()) };
        let mut online = false;
        for attempt in 0..3 {
            if self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup) {
                online = true;
                break;
            }
            if attempt < 2 {
                std::thread::sleep(Duration::from_secs(1));
            }
        }
        if !online {
            log!("window {} recorded but openNDS refused the grant for {}", &sid[..8], mac);
        }
        log!("timing {} settled pulses={} min={} left={} min online={} acked={}", &sid[..8], pulses, minutes, s.minutes, online, ack_ok);
        View::Final { plan: s.plan, pulses, minutes, left: s.left_secs, online }
    }

    /// Record a closed window on the roll and the ledger (once: the same window id never credits twice).
    #[allow(clippy::too_many_arguments)]
    fn record(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool, pulses: u32, minutes: u32, now: u64) -> Result<(), View> {
        let (down, up) = rates(&self.cfg, plan);
        let _g = self.files.lock().unwrap();
        match roll::mint(&self.cfg.roll_path(), now, mac, wid, plan, pulses, minutes, up, down, forfeit) {
            Ok(mode) => {
                if mode != roll::Mode::Dup {
                    if let Err(e) = ledger::append(&self.cfg.revenue_path(), now, plan.as_str(), pulses, minutes, mode.as_str()) {
                        log!("could not write the revenue ledger: {}", e);
                    }
                }
                let _ = fs::write(format!("{}.rec", self.open_file(sid)), "recorded\n");
                Ok(())
            }
            Err(MintError::Mismatch { plan: p, left }) => {
                log!("window {} not recorded: {} still has time on {}", &sid[..8], mac, p.as_str());
                Err(View::Mismatch { plan: p, left })
            }
            Err(MintError::Io) => {
                log!("window {} not recorded: the roll could not be written", &sid[..8]);
                Err(View::Error("ROLL_WRITE".into()))
            }
        }
    }

    fn ack_and_clear(&self, sid: &str) -> bool {
        for i in 0..3 {
            if self.ack(sid) {
                let _ = fs::remove_file(self.open_file(sid));
                let _ = fs::remove_file(format!("{}.rec", self.open_file(sid)));
                return true;
            }
            if i < 2 {
                std::thread::sleep(Duration::from_secs(1));
            }
        }
        false
    }

    // ---- recovery ---------------------------------------------------------------------------------------------------------------
    /// The windows with a record on flash: "<sid>" (opened) and "<sid>.rec" (recorded, not yet acknowledged).
    fn open_sids(&self) -> Vec<String> {
        let Ok(rd) = fs::read_dir(self.cfg.open_dir()) else { return vec![] };
        let mut sids: Vec<String> = rd
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .map(|n| n.strip_suffix(".rec").map(|s| s.to_string()).unwrap_or(n))
            .filter(|n| crate::util::valid_sid(n))
            .collect();
        sids.sort();
        sids.dedup();
        sids
    }

    fn is_live(&self, sid: &str) -> bool {
        self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.sid == sid)
    }

    /// Records of windows that were recorded but never acknowledged (the box did not answer), outside the live window.
    fn unacked(&self) -> usize {
        let live = self.st.lock().unwrap().window.as_ref().map(|w| w.sid.clone());
        self.open_sids()
            .iter()
            .filter(|s| Some(*s) != live.as_ref())
            .filter(|s| fs::metadata(format!("{}.rec", self.open_file(s))).is_ok())
            .count()
    }

    /// Windows that were open when the router stopped, and windows whose acknowledgement failed: the coins the box still
    /// holds are credited once (a recorded window is only acknowledged), then acknowledged.
    pub fn recover(&self) {
        let _one = self.recovering.lock().unwrap();
        for sid in self.open_sids() {
            // checked for each record: a window may have opened since the list was read
            if self.is_live(&sid) {
                continue;
            }
            let rec = fs::metadata(format!("{}.rec", self.open_file(&sid))).is_ok();
            if rec {
                if self.ack(&sid) {
                    let _ = fs::remove_file(self.open_file(&sid));
                    let _ = fs::remove_file(format!("{}.rec", self.open_file(&sid)));
                }
                continue;
            }
            let Ok(text) = fs::read_to_string(self.open_file(&sid)) else { continue };
            let f: Vec<&str> = text.split_whitespace().collect();
            let (Some(mac), Some(plan), Some(wid)) = (f.first(), f.get(1).and_then(|p| Plan::parse(p)), f.get(2)) else {
                let _ = fs::remove_file(self.open_file(&sid));
                continue;
            };
            let forfeit = f.get(3) == Some(&"1");
            let st = match self.box_.call(&sid, "status", "") {
                Ok(b) if BoxLink::is_success(&b) => b,
                _ => {
                    let old = fs::metadata(self.open_file(&sid))
                        .and_then(|m| m.modified())
                        .ok()
                        .and_then(|t| t.elapsed().ok())
                        .map(|d| d.as_secs() > 86_400)
                        .unwrap_or(false);
                    if old {
                        log!("recover: dropped {} (the box never answered for a day)", &sid[..8]);
                        let _ = fs::remove_file(self.open_file(&sid));
                    }
                    continue;
                }
            };
            if jget(&st, "state") == "armed" {
                continue;
            }
            let pulses = jget_u64(&st, "pulses").unwrap_or(0) as u32;
            if pulses > MAX_PULSES {
                log!("recover: window {} left as it is: the box reports {} pesos", &sid[..8], pulses);
                continue;
            }
            if pulses > 0 {
                let minutes = minutes_for(&self.cfg, plan, pulses);
                match self.record(&sid, mac, plan, wid, forfeit, pulses, minutes, now_secs()) {
                    Ok(()) => log!("recover: window {} credited ({} coins, {} min) after a restart", &sid[..8], pulses, minutes),
                    Err(_) => continue,
                }
                let cur = self.nds.state(mac);
                if !cur.is_empty() && cur != "Authenticated" {
                    let _ = self.reconnect(mac);
                }
            }
            if self.ack(&sid) {
                let _ = fs::remove_file(self.open_file(&sid));
                let _ = fs::remove_file(format!("{}.rec", self.open_file(&sid)));
            }
        }
    }

    /// Housekeeping: settle leftovers, forget old sessions and old results.
    pub fn maintenance(&self) {
        self.recover();
        {
            let _g = self.files.lock().unwrap();
            roll::purge(&self.cfg.roll_path(), now_secs());
        }
        let now = now_secs();
        let paying: std::collections::HashSet<String> =
            roll::load(&self.cfg.roll_path()).into_iter().filter(|e| e.left(now) > 0).map(|e| e.mac).collect();
        let mut st = self.st.lock().unwrap();
        st.results.retain(|_, (at, _)| now.saturating_sub(*at) < 600);
        st.cooldown.retain(|_, until| *until > now);
        let window = self.cfg.empty_window;
        st.empties.retain(|_, v| {
            v.retain(|t| now.saturating_sub(*t) <= window);
            !v.is_empty()
        });
        // fair-use counters only for devices that still have paid time
        st.fair.retain(|mac, _| paying.contains(mac));
    }
}

pub fn run_event_listener(core: Arc<Core>) {
    if core.cfg.event_port == 0 {
        return;
    }
    let addr = format!("{}:{}", core.cfg.event_bind, core.cfg.event_port);
    let mut warned = false;
    let sock = loop {
        match std::net::UdpSocket::bind(&addr) {
            Ok(s) => break s,
            Err(e) => {
                if !warned {
                    log!("coin events: cannot listen on {} yet ({}); trying again every 2 s", addr, e);
                    warned = true;
                }
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    };
    let mut buf = [0u8; 512];
    loop {
        if let Ok((n, _)) = sock.recv_from(&mut buf) {
            if let Ok(line) = std::str::from_utf8(&buf[..n]) {
                core.on_event_line(line);
            }
        }
    }
}
