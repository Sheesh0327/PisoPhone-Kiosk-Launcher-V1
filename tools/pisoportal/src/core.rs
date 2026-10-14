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
use crate::voucher::Mine;
use std::collections::HashMap;
use std::fs;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

macro_rules! log {
    ($($a:tt)*) => { eprintln!("coinslot: {}", format!($($a)*)) };
}
pub(crate) use log;

#[derive(Clone, Debug, PartialEq)]
pub enum View {
    Idle {
        left: u64,
        plan: Option<Plan>,
        online: bool,
        busy: u64,
        cooldown: u64,
        used_mb: u64,
        slow: u64,
        pm: u32,
        pl: u32,
        pw: u64,
        /// the coin page offers "Use now" or "Get a voucher code"
        vc: bool,
        /// the vouchers this device paid for and has not used
        vs: Vec<Mine>,
    },
    /// the clock is stopped: `by` is when the unused time is lost
    Paused {
        plan: Plan,
        left: u64,
        pm: u32,
        pl: u32,
        by: u64,
    },
    Starting {
        plan: Plan,
        voucher: bool,
    },
    Armed {
        plan: Plan,
        pulses: u32,
        minutes: u32,
        remaining: u64,
        total: u64,
        voucher: bool,
    },
    Closing {
        plan: Plan,
        pulses: u32,
        minutes: u32,
        voucher: bool,
    },
    Final {
        plan: Plan,
        pulses: u32,
        minutes: u32,
        left: u64,
        online: bool,
        /// a window paid for a voucher code: (the code as ABCDE-FGHJK, when it expires). Nothing was connected.
        voucher: Option<(String, u64)>,
    },
    Empty,
    Error(String),
    Busy(u64),
    Cooldown(u64),
    Mismatch {
        plan: Plan,
        left: u64,
    },
}

/// ,"v":true on the views of a window that is paying for a voucher code
fn vflag(voucher: bool) -> &'static str {
    if voucher {
        ",\"v\":true"
    } else {
        ""
    }
}

impl View {
    pub fn to_json(&self) -> String {
        match self {
            View::Idle { left, plan, online, busy, cooldown, used_mb, slow, pm, pl, pw, vc, vs } => format!(
                "{{\"t\":\"state\",\"s\":\"idle\",\"left\":{},\"plan\":\"{}\",\"online\":{},\"busy\":{},\"cooldown\":{},\"used_mb\":{},\"slow\":{},\"pm\":{},\"pl\":{},\"pw\":{},\"vc\":{},\"vs\":[{}]}}",
                left,
                plan.map(|p| p.as_str()).unwrap_or(""),
                online,
                busy,
                cooldown,
                used_mb,
                slow,
                pm,
                pl,
                pw,
                vc,
                vs.iter()
                    .map(|m| format!("{{\"c\":\"{}\",\"m\":{},\"p\":\"{}\",\"x\":{}}}", json_esc(&m.code), m.minutes, json_esc(&m.plan), m.expires))
                    .collect::<Vec<_>>()
                    .join(",")
            ),
            View::Paused { plan, left, pm, pl, by } => format!(
                "{{\"t\":\"state\",\"s\":\"paused\",\"plan\":\"{}\",\"left\":{},\"pm\":{},\"pl\":{},\"by\":{}}}",
                plan.as_str(),
                left,
                pm,
                pl,
                by
            ),
            View::Starting { plan, voucher } => {
                format!("{{\"t\":\"state\",\"s\":\"starting\",\"plan\":\"{}\"{}}}", plan.as_str(), vflag(*voucher))
            }
            View::Armed { plan, pulses, minutes, remaining, total, voucher } => format!(
                "{{\"t\":\"state\",\"s\":\"armed\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{},\"remaining\":{},\"total\":{}{}}}",
                plan.as_str(),
                pulses,
                minutes,
                remaining,
                total,
                vflag(*voucher)
            ),
            View::Closing { plan, pulses, minutes, voucher } => {
                format!(
                    "{{\"t\":\"state\",\"s\":\"closing\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{}{}}}",
                    plan.as_str(),
                    pulses,
                    minutes,
                    vflag(*voucher)
                )
            }
            View::Final { plan, pulses, minutes, left, online, voucher } => format!(
                "{{\"t\":\"state\",\"s\":\"final\",\"plan\":\"{}\",\"pulses\":{},\"minutes\":{},\"left\":{},\"online\":{}{}}}",
                plan.as_str(),
                pulses,
                minutes,
                left,
                online,
                match voucher {
                    Some((code, exp)) => format!(",\"vcode\":\"{}\",\"vexp\":{}", json_esc(code), exp),
                    None => String::new(),
                }
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
    /// the coins buy a voucher code, not time: nothing is connected when the window settles
    voucher: bool,
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
    /// when each device last resumed (a pause is refused for a minute after)
    last_resume: HashMap<String, u64>,
    /// voucher tries that failed: (count, no tries until); and the times of failures from everybody
    redeem_fails: HashMap<String, (u32, u64)>,
    redeem_recent: Vec<u64>,
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
    /// the coin page offers the choice "Use now" / "Get a voucher code" (VOUCHER_CHOICE, changed live from the console)
    vchoice: AtomicBool,
}

/// More pesos than one coin window can physically take (about two minutes of coins): an answer above this is not trusted.
pub const MAX_PULSES: u32 = 5000;

/// After openNDS refused a paid device: more tries, 2 s apart.
const GRANT_RETRIES: u32 = 15;

impl Core {
    pub fn new(cfg: Config) -> Arc<Core> {
        let box_ = BoxLink::new(&cfg.gw_box, &cfg.gw_key).remember_in(&format!("{}/box_signed", cfg.data_dir));
        let nds = Nds { ndsctl: cfg.ndsctl.clone() };
        let vchoice =
            AtomicBool::new(crate::config::parse_switch(&crate::config::override_get(&cfg.data_dir, "VOUCHER_CHOICE"), cfg.voucher_choice));
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
                last_resume: HashMap::new(),
                redeem_fails: HashMap::new(),
                redeem_recent: Vec::new(),
                fair: HashMap::new(),
            }),
            cv: Condvar::new(),
            files: Mutex::new(()),
            recovering: Mutex::new(()),
            vchoice,
        })
    }

    /// Is the voucher choice offered to customers?
    pub fn voucher_choice(&self) -> bool {
        self.vchoice.load(Ordering::Relaxed)
    }

    /// The owner switches the voucher choice (console, Settings). Kept in DATA_DIR/settings.txt; open pages are told at once.
    pub fn set_voucher_choice(&self, on: bool) -> bool {
        let ok = crate::config::override_set(&self.cfg.data_dir, "VOUCHER_CHOICE", if on { "on" } else { "off" });
        if ok {
            self.vchoice.store(on, Ordering::Relaxed);
            let mut st = self.st.lock().unwrap();
            self.bump(&mut st);
        }
        ok
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
                    let v = v.clone();
                    drop(st);
                    return self.live(v, mac, now);
                }
            }
        }
        let peeked = roll::peek_state(&self.cfg.roll_path(), now, mac);
        let pm = self.cfg.pause_max;
        let pl = pm.saturating_sub(peeked.as_ref().map(|p| p.pauses_used).unwrap_or(0));
        if let Some(p) = peeked.as_ref().filter(|p| p.paused) {
            return View::Paused { plan: p.plan, left: p.left, pm, pl, by: p.lost_at };
        }
        let (plan, left) = match peeked {
            Some(p) => (Some(p.plan), p.left),
            None => (None, 0),
        };
        let st = self.st.lock().unwrap();
        let pw = st.last_resume.get(mac).map(|t| (t + self.cfg.pause_gap).saturating_sub(now)).unwrap_or(0);
        let busy = if st.window.as_ref().map(|w| w.mac != mac).unwrap_or(false) { self.cfg.busy_retry } else { 0 };
        let cooldown = st.cooldown.get(mac).map(|u| u.saturating_sub(now)).unwrap_or(0);
        // HyperSpeed only: data used this session, and the seconds a fair-use slowdown still lasts (0 = full speed)
        let (used_mb, slow) = match st.fair.get(mac) {
            Some(f) if plan == Some(Plan::Hyper) && left > 0 => {
                (f.used_kb / 1024, if f.throttled { (f.since + self.cfg.fair_throttle_min * 60).saturating_sub(now) } else { 0 })
            }
            _ => (0, 0),
        };
        // (the vouchers this device paid for are listed here: a page that was closed before the code was written down loses nothing)
        let vs = crate::voucher::for_device(&self.cfg.data_dir, now, mac);
        View::Idle { left, plan, online: left > 0, busy, cooldown, used_mb, slow, pm, pl, pw, vc: self.voucher_choice(), vs }
    }

    /// A stored result is shown again when a page reloads: the time it names is read from the roll now, never the amount it
    /// had when the window closed (a reload a minute later must not show the whole purchase again).
    fn live(&self, v: View, mac: &str, now: u64) -> View {
        match v {
            // (a window that bought a voucher code connected nothing: its result stays as it was)
            View::Final { plan, pulses, minutes, online, voucher: None, .. } => {
                let left = roll::peek(&self.cfg.roll_path(), now, mac).map(|(_, l)| l).unwrap_or(0);
                View::Final { plan, pulses, minutes, left, online: online && left > 0, voucher: None }
            }
            other => other,
        }
    }

    fn window_view(&self, w: &Window) -> View {
        let minutes = minutes_for(&self.cfg, w.plan, w.pulses);
        match w.state {
            WState::Starting => View::Starting { plan: w.plan, voucher: w.voucher },
            WState::Armed => View::Armed {
                plan: w.plan,
                pulses: w.pulses,
                minutes,
                remaining: w.deadline.saturating_duration_since(Instant::now()).as_secs(),
                total: w.total,
                voucher: w.voucher,
            },
            WState::Closing => View::Closing { plan: w.plan, pulses: w.pulses, minutes, voucher: w.voucher },
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
        // (a paused device is left offline: only its own Resume puts it back)
        let Some(s) = roll::session_active(&self.cfg.roll_path(), now, mac) else { return false };
        let cur = self.nds.state(mac);
        if cur == "Authenticated" {
            return true;
        }
        let ok = self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup);
        log!("reconnect {} {} min {}", mac, s.minutes, if ok { "granted" } else { "REFUSED by openNDS" });
        ok
    }

    // ---- pause and resume --------------------------------------------------------------------------------------------------------
    /// Stop the clock of a device's paid time and take it off the internet. Err: why not (the page shows it).
    pub fn pause(&self, mac: &str) -> Result<(), (&'static str, u64)> {
        let now = now_secs();
        let wait = self.st.lock().unwrap().last_resume.get(mac).map(|t| (t + self.cfg.pause_gap).saturating_sub(now)).unwrap_or(0);
        if wait > 0 {
            return Err(("wait", wait));
        }
        if self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac == mac) {
            return Err(("paying", 0)); // not in the middle of putting coins in
        }
        let rules = roll::PauseRules { max: self.cfg.pause_max, min_left: self.cfg.pause_min_left };
        let r = {
            let _g = self.files.lock().unwrap();
            roll::pause(&self.cfg.roll_path(), now, mac, rules)
        };
        match r {
            Ok(left) => {
                self.nds.deauth(mac);
                log!("pause {} with {} s left", mac, left);
                let mut st = self.st.lock().unwrap();
                st.results.remove(mac);
                self.bump(&mut st);
                Ok(())
            }
            Err(roll::PauseError::NoSession) => Err(("none", 0)),
            Err(roll::PauseError::Already) => Err(("already", 0)),
            Err(roll::PauseError::Limit) => Err(("limit", 0)),
            Err(roll::PauseError::TooLittle) => Err(("little", self.cfg.pause_min_left)),
            Err(roll::PauseError::Io) => Err(("io", 0)),
        }
    }

    /// Start the clock again and put the device back on the internet.
    pub fn resume(self: &Arc<Self>, mac: &str) -> Result<(), (&'static str, u64)> {
        let now = now_secs();
        let s = {
            let _g = self.files.lock().unwrap();
            if !roll::peek_state(&self.cfg.roll_path(), now, mac).is_some_and(|p| p.paused) {
                return Err(("none", 0));
            }
            roll::session(&self.cfg.roll_path(), now, mac)
        };
        let Some(s) = s else { return Err(("none", 0)) };
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
        log!("resume {} with {} s left: {}", mac, s.left_secs, if online { "granted" } else { "REFUSED by openNDS, trying again" });
        {
            let mut st = self.st.lock().unwrap();
            st.last_resume.insert(mac.to_string(), now);
            st.results.remove(mac);
            self.bump(&mut st);
        }
        if !online {
            let (core, m) = (Arc::clone(self), mac.to_string());
            let _ = std::thread::Builder::new().stack_size(96 * 1024).spawn(move || core.keep_granting(&m));
        }
        Ok(())
    }

    // ---- voucher codes -----------------------------------------------------------------------------------------------------------
    /// Spend a voucher code for this device: Ok(minutes added) or Err(why, seconds to wait).
    pub fn redeem(&self, mac: &str, code: &str) -> Result<u32, (&'static str, u64)> {
        let now = now_secs();
        {
            let mut st = self.st.lock().unwrap();
            st.redeem_recent.retain(|t| now.saturating_sub(*t) < 60);
            if let Some((_, until)) = st.redeem_fails.get(mac) {
                if *until > now {
                    return Err(("wait", *until - now));
                }
            }
            if st.redeem_recent.len() >= 30 {
                return Err(("wait", 30)); // someone is guessing: everybody waits a little
            }
        }
        let dir = self.cfg.data_dir.clone();
        let result = (|| {
            let _g = self.files.lock().unwrap();
            let (batch, hash) = crate::voucher::lookup(&dir, now, code).map_err(|e| match e {
                crate::voucher::RedeemError::Bad => ("bad", 0),
                crate::voucher::RedeemError::Used => ("used", 0),
                crate::voucher::RedeemError::Expired => ("exp", 0),
            })?;
            let plan = Plan::parse(&batch.plan).unwrap_or(Plan::Hyper);
            let (down, up) = rates(&self.cfg, plan);
            let wid = format!("v{}", hash);
            match roll::mint(&self.cfg.roll_path(), now, mac, &wid, plan, 0, batch.minutes, up, down, false) {
                Ok(mode) => {
                    // A voucher bought with coins was income when its coins were counted (kind "vbuy"): spending it is time use, no
                    // revenue row. A printed slip of an earlier release (no coin window) was sold for cash elsewhere and is booked
                    // here, as it always was.
                    if mode != roll::Mode::Dup && !batch.bought() {
                        if let Err(e) = ledger::append(&self.cfg.revenue_path(), now, plan.as_str(), batch.pesos, batch.minutes, "voucher")
                        {
                            log!("could not write the revenue ledger: {}", e);
                        }
                    }
                }
                Err(MintError::Mismatch { .. }) => return Err(("plan", 0)),
                Err(MintError::Io) => return Err(("io", 0)),
            }
            crate::voucher::mark_used(&dir, &hash, now, mac);
            Ok((batch.minutes, plan))
        })();
        match result {
            Ok((minutes, plan)) => {
                let s = {
                    let _g = self.files.lock().unwrap();
                    roll::session(&self.cfg.roll_path(), now_secs(), mac)
                };
                if let Some(s) = s {
                    let ok = self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup);
                    log!("voucher {} +{} min on {}: {}", mac, minutes, plan.as_str(), if ok { "granted" } else { "REFUSED by openNDS" });
                }
                let mut st = self.st.lock().unwrap();
                st.redeem_fails.remove(mac);
                st.results.remove(mac);
                self.bump(&mut st);
                Ok(minutes)
            }
            Err((why, w)) => {
                if matches!(why, "bad" | "used" | "exp") {
                    // five wrong tries lock the device for a minute, then it doubles up to 15 minutes
                    let mut st = self.st.lock().unwrap();
                    st.redeem_recent.push(now);
                    let e = st.redeem_fails.entry(mac.to_string()).or_insert((0, 0));
                    e.0 += 1;
                    let wait = if e.0 >= 5 { (60u64 << (e.0 - 5).min(4)).min(900) } else { 0 };
                    if wait > 0 {
                        e.1 = now + wait;
                    }
                    log!("voucher refused for {}: {} (try {})", mac, why, e.0);
                    return Err((why, if wait > 0 { wait } else { w }));
                }
                Err((why, w))
            }
        }
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

    /// Open a coin window for a device. Returns what the device should see now. `voucher`: the customer chose "Get a voucher code"
    /// (honoured only while the owner leaves that choice on): the coins buy a code, nothing is connected.
    pub fn start(self: &Arc<Self>, mac: &str, plan: Plan, forfeit: bool, voucher: bool) -> View {
        let voucher = voucher && self.voucher_choice();
        let cd = self.cooldown_left(mac);
        if cd > 0 {
            log!("start refused for {}: cooldown {}s after empty windows", mac, cd);
            return View::Cooldown(cd);
        }
        // (a voucher is spent later, where a device with time on the other plan is asked then: no question now)
        if let Some((p, left)) = roll::peek(&self.cfg.roll_path(), now_secs(), mac).filter(|_| !voucher) {
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
            voucher,
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
        log!("window {} opened for {} plan {}{}", &sid[..8], mac, plan.as_str(), if voucher { " (pays for a voucher code)" } else { "" });
        let (core, worker_sid) = (Arc::clone(self), sid.clone());
        if std::thread::Builder::new().stack_size(128 * 1024).spawn(move || core.run_window(&worker_sid)).is_err() {
            // without its worker the window would hold the coin slot for everybody, for ever
            log!("window {} could not start (no thread)", &sid[..8]);
            let mut st = self.st.lock().unwrap();
            st.window = None;
            self.bump(&mut st);
            return View::Error("NO_THREAD".into());
        }
        View::Starting { plan, voucher }
    }

    /// This device has a coin window open now.
    pub fn window_open_for(&self, mac: &str) -> bool {
        self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac == mac)
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
        let Some((wid, mac, plan, forfeit, voucher)) = ({
            let st = self.st.lock().unwrap();
            st.window.as_ref().filter(|w| w.sid == sid).map(|w| (w.wid.clone(), w.mac.clone(), w.plan, w.forfeit, w.voucher))
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
        let why: &str;
        loop {
            let seen = self.cur(sid).map(|c| c.0).unwrap_or(last);
            let wait = next_poll.saturating_duration_since(Instant::now()).min(deadline.saturating_duration_since(Instant::now()));
            self.wait_activity(sid, wait, seen);
            let Some((mut pulses, stop, ended, _)) = self.cur(sid) else { return };
            if stop {
                why = "the customer tapped Done";
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
                self.note_open(sid, &mac, plan, &wid, forfeit, voucher);
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
            if Instant::now() >= deadline {
                why = if last > 0 { "no more coins came" } else { "no coin came" };
                break;
            }
            if !boxstate_armed {
                why = "the box ended the window";
                break;
            }
        }
        log!(
            "window {} closing: {} ({} pesos, open {} s)",
            &sid[..8],
            why,
            last.max(self.cur(sid).map(|c| c.0).unwrap_or(0)),
            t_arm.elapsed().as_secs()
        );
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
                self.note_open(sid, &mac, plan, &wid, forfeit, voucher);
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
        let view =
            if voucher { self.settle_voucher(sid, &mac, plan, &wid, pulses) } else { self.settle(sid, &mac, plan, &wid, forfeit, pulses) };
        // (a voucher window connects nothing, so there is nothing for the grant retries to finish)
        let refused = matches!(view, View::Final { online: false, voucher: None, .. });
        self.finish_window(sid, view, Some(true));
        if refused {
            // paid but openNDS did not let the device on yet: keep trying for a while, so the page turns to "online" by itself
            // (before, only a reload of the page tried again)
            let (core, m) = (Arc::clone(self), mac.clone());
            let _ = std::thread::Builder::new().stack_size(96 * 1024).spawn(move || core.keep_granting(&m));
        }
    }

    /// After a refused grant: try again every 2 s for half a minute; when the device is online, its page is told.
    fn keep_granting(&self, mac: &str) {
        for i in 1..=GRANT_RETRIES {
            std::thread::sleep(Duration::from_secs(2));
            if self.reconnect(mac) {
                log!("{} is online after {} more tries", mac, i);
                self.mark_online(mac);
                return;
            }
        }
        log!("{} is still not online after {} more tries (the next visit of its page tries again)", mac, GRANT_RETRIES);
    }

    /// The stored result of a window that was paid while openNDS refused the grant now says online (if it is still there).
    fn mark_online(&self, mac: &str) {
        let mut st = self.st.lock().unwrap();
        let changed = match st.results.get_mut(mac) {
            Some((_, View::Final { online, .. })) if !*online => {
                *online = true;
                true
            }
            _ => false,
        };
        if changed {
            self.bump(&mut st);
        }
    }

    // ---- recording, granting, acknowledging --------------------------------------------------------------------------------------
    fn open_file(&self, sid: &str) -> String {
        format!("{}/{}", self.cfg.open_dir(), sid)
    }

    /// The first coin leaves a record on flash, kept until the box is acknowledged: if the router restarts mid-window,
    /// recovery credits the coins. Nothing is granted yet.
    fn note_open(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool, voucher: bool) {
        let _ = fs::create_dir_all(self.cfg.open_dir());
        // (records of earlier releases have four fields: no voucher)
        let _ = fs::write(self.open_file(sid), format!("{} {} {} {} {}\n", mac, plan.as_str(), wid, forfeit as u8, voucher as u8));
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
        // the customer is let on first, the box is acknowledged after: the window is on the roll already, so the order is safe
        let s = {
            let _g = self.files.lock().unwrap();
            roll::session(&self.cfg.roll_path(), now_secs(), mac)
        };
        let Some(s) = s else {
            self.ack_and_clear(sid);
            return View::Error("NO_SESSION".into());
        };
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
        let ack_ok = self.ack_and_clear(sid);
        log!("timing {} settled pulses={} min={} left={} min online={} acked={}", &sid[..8], pulses, minutes, s.minutes, online, ack_ok);
        View::Final { plan: s.plan, pulses, minutes, left: s.left_secs, online, voucher: None }
    }

    /// A window that paid for a voucher code: price it once, make the one voucher (and its revenue row, as a direct purchase
    /// would), acknowledge the box. The device is NOT connected and no time is granted.
    fn settle_voucher(&self, sid: &str, mac: &str, plan: Plan, wid: &str, pulses: u32) -> View {
        let minutes = minutes_for(&self.cfg, plan, pulses);
        let now = now_secs();
        let v = match self.record_voucher(sid, mac, plan, wid, pulses, minutes, now) {
            Ok(v) => v,
            Err(e) => return e,
        };
        let ack_ok = self.ack_and_clear(sid);
        log!("timing {} settled pulses={} min={} voucher issued acked={}", &sid[..8], pulses, minutes, ack_ok);
        View::Final { plan, pulses, minutes, left: 0, online: false, voucher: Some(v) }
    }

    /// Make the voucher of a closed window, once (the same window id never makes two), and book its coins in the revenue ledger
    /// like any coin payment, so the box's own count reconciles with it. Returns (the code, when it expires).
    #[allow(clippy::too_many_arguments)]
    fn record_voucher(
        &self,
        sid: &str,
        mac: &str,
        plan: Plan,
        wid: &str,
        pulses: u32,
        minutes: u32,
        now: u64,
    ) -> Result<(String, u64), View> {
        let _g = self.files.lock().unwrap();
        match crate::voucher::issue(&self.cfg.data_dir, now, mac, wid, plan.as_str(), minutes, pulses) {
            Ok(v) => {
                if v.created {
                    if let Err(e) = ledger::append(&self.cfg.revenue_path(), now, plan.as_str(), pulses, minutes, "vbuy") {
                        log!("could not write the revenue ledger: {}", e);
                    }
                }
                let _ = fs::write(format!("{}.rec", self.open_file(sid)), "recorded\n");
                Ok((v.code, v.batch.expires))
            }
            Err(e) => {
                log!("window {} not recorded: the voucher could not be made ({})", &sid[..8], e);
                Err(View::Error("ROLL_WRITE".into()))
            }
        }
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
            let voucher = f.get(4) == Some(&"1");
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
                if voucher {
                    match self.record_voucher(&sid, mac, plan, wid, pulses, minutes, now_secs()) {
                        Ok(_) => log!("recover: window {} made its voucher ({} coins, {} min) after a restart", &sid[..8], pulses, minutes),
                        Err(_) => continue,
                    }
                } else {
                    match self.record(&sid, mac, plan, wid, forfeit, pulses, minutes, now_secs()) {
                        Ok(()) => log!("recover: window {} credited ({} coins, {} min) after a restart", &sid[..8], pulses, minutes),
                        Err(_) => continue,
                    }
                    let cur = self.nds.state(mac);
                    if !cur.is_empty() && cur != "Authenticated" {
                        let _ = self.reconnect(mac);
                    }
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
            crate::voucher::prune(&self.cfg.data_dir, now_secs());
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

impl Core {
    /// The vouchers (no codes): when each was made, what it is worth, how many of its codes were used. A voucher a customer paid
    /// for with coins is a batch of one ("bought": true); a batch of an earlier release (printed slips) has many.
    pub fn vouchers_json(&self) -> String {
        let _g = self.files.lock().unwrap();
        let rows: Vec<String> = crate::voucher::list(&self.cfg.data_dir)
            .into_iter()
            .map(|(b, used)| {
                format!(
                    "{{\"id\":\"{}\",\"label\":\"{}\",\"count\":{},\"used\":{},\"minutes\":{},\"pesos\":{},\"plan\":\"{}\",\"expires\":{},\"created\":{},\"void\":{},\"bought\":{}}}",
                    b.id,
                    json_esc(&b.label),
                    b.count,
                    used,
                    b.minutes,
                    b.pesos,
                    b.plan,
                    b.expires,
                    b.created,
                    b.void,
                    b.bought()
                )
            })
            .collect();
        format!("{{\"batches\":[{}]}}", rows.join(","))
    }

    /// (vouchers bought with coins and redeemed since `since`, vouchers bought with coins that wait to be used): for the sales report.
    pub fn voucher_counts(&self, since: u64) -> (u64, u64) {
        crate::voucher::counts(&self.cfg.data_dir, since, now_secs())
    }

    /// The owner cancels the unused codes of a voucher (the console).
    pub fn voucher_void(&self, batch: &str) -> bool {
        let _g = self.files.lock().unwrap();
        crate::voucher::void_batch(&self.cfg.data_dir, batch)
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
