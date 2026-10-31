//! The heart: one coin window at a time (one customer pays; everybody else is turned away), the box worker that arms the
//! slot and counts coins, the settlement (price the whole window once, record it, grant access once, acknowledge the
//! box), recovery after a restart, and the cooldown for devices that keep opening empty windows.
use crate::boxlink::{parse_event, BoxLink};
use crate::config::Config;
use crate::ledger;
use crate::nds::Nds;
use crate::pricing::{minutes_for, rates, Plan, Pricing};
use crate::roll::{self, MintError};
use crate::util::{jget, jget_u64, json_esc, now_secs, random_hex};
use crate::voucher::Mine;
use std::collections::HashMap;
use std::fs;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::time::{Duration, Instant};

macro_rules! log {
    ($($a:tt)*) => { eprintln!("coinslot: {}", format!($($a)*)) };
}
pub(crate) use log;

#[derive(Clone, Debug, PartialEq)]
pub enum View {
    Idle {
        /// guests may send a file to the owner (the Send a file button)
        sd: bool,
        /// pesos the session still has to pay before it may pause (0: it may, or pausing is off)
        pn: u32,
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
            View::Idle { sd, pn, left, plan, online, busy, cooldown, used_mb, slow, pm, pl, pw, vc, vs } => format!(
                "{{\"t\":\"state\",\"s\":\"idle\",\"left\":{},\"plan\":\"{}\",\"online\":{},\"busy\":{},\"cooldown\":{},\"used_mb\":{},\"slow\":{},\"pm\":{},\"pl\":{},\"pw\":{},\"vc\":{},\"sd\":{},\"pn\":{},\"vs\":[{}]}}",
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
                sd,
                pn,
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
    /// the prices this payment started with: the amount on the screen and the time it buys never change under the customer
    pricing: Arc<Pricing>,
    /// the coins buy a voucher code, not time: nothing is connected when the window settles
    voucher: bool,
    state: WState,
    pulses: u32,
    last_seq: u64,
    deadline: Instant,
    total: u64,
    stop: bool,
    box_ended: bool,
    /// the result, as soon as it is known (the device is let on, or the voucher is written): the page is told at once, while the
    /// box is still being acknowledged. The window stays until that is done, so no other payment starts before.
    early: Option<View>,
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
    /// RT-17: a recovery thread is running (set before it starts, so two maintenance rounds never start two)
    recover_running: std::sync::atomic::AtomicBool,
    /// the prices now in force (the settings file's, or what the owner set in the console) and a counter that rises with every change
    pricing: RwLock<Arc<Pricing>>,
    pricing_stamp: AtomicU32,
    /// guests' files for the owner (the print server's inbox)
    pub send: crate::send::Send,
    /// the coin page offers the choice "Use now" / "Get a voucher code" (VOUCHER_CHOICE, changed live from the console)
    vchoice: AtomicBool,
    /// the last wall/monotonic clock reading, and since when sessions may be timed with a stale clock (this program's start, or
    /// the last jump): a forward jump of the wall clock (the router synced it after starting with a stale one) moves the sessions
    /// paid in between (see roll::shift)
    clock: Mutex<(u64, Instant, u64)>,
    /// when this program started (the monotonic time in the clock jump record)
    started: Instant,
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
        let pricing = RwLock::new(Arc::new(Pricing::load(&cfg)));
        let send_on = crate::config::parse_switch(&crate::config::override_get(&cfg.data_dir, "SEND_ENABLED"), cfg.send_enabled);
        let send = crate::send::Send::new(&cfg.send_host, cfg.send_port, &cfg.data_dir, cfg.send_max_mb, cfg.send_per_hour, send_on);
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
            recover_running: std::sync::atomic::AtomicBool::new(false),
            vchoice,
            clock: Mutex::new((now_secs(), Instant::now(), now_secs())),
            started: Instant::now(),
            pricing,
            pricing_stamp: AtomicU32::new(1),
            send,
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

    /// The owner switches file sending for guests on or off (console, Settings). Kept in DATA_DIR/settings.txt; open pages are told at once.
    pub fn set_file_send(&self, on: bool) -> bool {
        let ok = crate::config::override_set(&self.cfg.data_dir, "SEND_ENABLED", if on { "on" } else { "off" });
        if ok {
            self.send.set_switch(on);
            let mut st = self.st.lock().unwrap();
            self.bump(&mut st);
        }
        ok
    }

    /// The prices in force now (a payment keeps the ones it started with).
    pub fn pricing(&self) -> Arc<Pricing> {
        Arc::clone(&self.pricing.read().unwrap())
    }

    /// Rises with every price change: the coin page is built again when it moves.
    pub fn pricing_stamp(&self) -> u64 {
        u64::from(self.pricing_stamp.load(Ordering::Relaxed))
    }

    /// The owner sets new prices (console, Settings). Kept in DATA_DIR/settings.txt; payments already open keep their prices.
    pub fn set_pricing(&self, new: Pricing) -> Result<(), String> {
        new.check()?;
        if !new.save(&self.cfg.data_dir) {
            return Err("could not be saved on the router".into());
        }
        self.adopt_pricing(new);
        Ok(())
    }

    /// The owner goes back to the settings file's prices.
    pub fn reset_pricing(&self) {
        Pricing::forget_changes(&self.cfg.data_dir);
        self.adopt_pricing(Pricing::load(&self.cfg));
    }

    fn adopt_pricing(&self, new: Pricing) {
        *self.pricing.write().unwrap() = Arc::new(new);
        self.pricing_stamp.fetch_add(1, Ordering::Relaxed);
        let mut st = self.st.lock().unwrap();
        self.bump(&mut st);
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
        let pn = if pm > 0 { peeked.as_ref().map(|p| self.cfg.pause_min_pesos.saturating_sub(p.pesos)).unwrap_or(0) } else { 0 };
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
        View::Idle {
            sd: self.send.enabled(),
            pn,
            left,
            plan,
            online: left > 0,
            busy,
            cooldown,
            used_mb,
            slow,
            pm,
            pl,
            pw,
            vc: self.voucher_choice(),
            vs,
        }
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
            View::Final { plan, pulses, minutes, left, online, voucher, .. } => {
                View::Final { plan, pulses, minutes, left, online, voucher }
            }
            other => other,
        }
    }

    fn window_view(&self, w: &Window) -> View {
        let minutes = minutes_for(&w.pricing, w.plan, w.pulses);
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
            WState::Closing => match &w.early {
                Some(v) => self.live(v.clone(), &w.mac, now_secs()),
                None => View::Closing { plan: w.plan, pulses: w.pulses, minutes, voucher: w.voucher },
            },
        }
    }

    /// Tell the page the result now, before the box is acknowledged (that takes a moment, and the customer is waiting).
    fn publish_early(&self, sid: &str, v: View) {
        let mut st = self.st.lock().unwrap();
        let set = match st.window.as_mut().filter(|w| w.sid == sid) {
            Some(w) => {
                w.early = Some(v);
                true
            }
            None => false,
        };
        if set {
            self.bump(&mut st);
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
        // a clock that was just set right must move the stale sessions first, or a paid one looks expired (RT-01)
        self.check_clock();
        let now = now_secs();
        let _g = self.files.lock().unwrap();
        // (a paused device is left as it is, slowed or off: only its own Resume puts it back at full speed)
        let Some(s) = roll::session_active(&self.cfg.roll_path(), now, mac) else { return false };
        let cur = self.nds.state(mac);
        if cur == "Authenticated" {
            return true;
        }
        let ok = self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup);
        log!("reconnect {} {} min {}", mac, s.minutes, if ok { "granted" } else { "REFUSED by openNDS" });
        ok
    }

    /// Called every tick for a device openNDS lists as not let on. When it has paid time on the roll (openNDS was restarted by an
    /// update or a crash, or a grant was refused and nobody asked again) it is put back online here, so a customer is not left
    /// with a page that says "online" and a phone with no internet until the page happens to be opened again. A device that is in
    /// the middle of paying, or paused, is left alone.
    pub fn heal(&self, mac: &str) {
        if self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac == mac) {
            return;
        }
        if self.reconnect(mac) {
            log!("healed {}: it had paid time but openNDS had not let it on", mac);
        }
    }

    // ---- pause and resume --------------------------------------------------------------------------------------------------------
    /// Stop the clock of a device's paid time and take it off the internet. Err: why not (the page shows it).
    pub fn pause(&self, mac: &str) -> Result<(), (&'static str, u64)> {
        let now = now_secs();
        let wait = self.st.lock().unwrap().last_resume.get(mac).map(|t| (t + self.cfg.pause_gap).saturating_sub(now)).unwrap_or(0);
        if wait > 0 {
            return Err(("wait", wait));
        }
        if self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac == mac && w.early.is_none()) {
            return Err(("paying", 0)); // not in the middle of putting coins in
        }
        let rules = roll::PauseRules { max: self.cfg.pause_max, min_left: self.cfg.pause_min_left, min_pesos: self.cfg.pause_min_pesos };
        let r = {
            let _g = self.files.lock().unwrap();
            roll::pause(&self.cfg.roll_path(), now, mac, rules)
        };
        match r {
            Ok(left) => {
                self.slow_down_paused(mac, now);
                log!("pause {} with {} s left", mac, left);
                let mut st = self.st.lock().unwrap();
                st.results.remove(mac);
                self.bump(&mut st);
                Ok(())
            }
            Err(roll::PauseError::NoSession) => Err(("none", 0)),
            Err(roll::PauseError::Already) => Err(("already", 0)),
            Err(roll::PauseError::Limit) => Err(("limit", 0)),
            Err(roll::PauseError::Pesos(n)) => Err(("pesos", u64::from(n))),
            Err(roll::PauseError::TooLittle) => Err(("little", self.cfg.pause_min_left)),
            Err(roll::PauseError::Io) => Err(("io", 0)),
        }
    }

    /// A paused device is not taken off: it stays on at a crawl (PAUSE_KBPS, 1 kbit/s by default) with a small data allowance for the whole
    /// pause (PAUSE_QUOTA_KB, 2 MB by default: openNDS takes the device off when it is used up, as a pause always did), so its page, its
    /// buttons and its scripts keep working but nobody chats for free while the clock is stopped. It is let on for as long as its paused time is kept (the same moment the
    /// roll forgets that time), so openNDS ends the session by itself when the time is lost. If openNDS refuses, the device is simply off,
    /// as a pause always was.
    fn slow_down_paused(&self, mac: &str, now: u64) {
        let lost_at = {
            let _g = self.files.lock().unwrap();
            roll::peek_state(&self.cfg.roll_path(), now, mac).map(|p| p.lost_at).unwrap_or(0)
        };
        let minutes = (lost_at.saturating_sub(now) / 60).max(1);
        let kbps = self.cfg.pause_kbps;
        let quota = self.cfg.pause_quota_kb;
        if self.nds.grant(mac, minutes, kbps, kbps, quota, quota) {
            log!("pause {}: slowed to {} kbit/s (up to {} kB) for up to {} min", mac, kbps, quota, minutes);
        } else {
            self.nds.deauth(mac);
            log!("pause {}: openNDS refused the slow grant, the device is off", mac);
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
            let (down, up) = rates(&self.pricing(), plan);
            let wid = format!("v{}", hash);
            // The code is marked spent BEFORE the time is added: the other order let a crash or a failed write in between leave
            // the code unused after crediting it, and it could be spent again. A crash now costs at most this one code (the owner
            // sees it used, with this device), never a second credit.
            if !crate::voucher::mark_used(&dir, &hash, now, mac) {
                return Err(("io", 0));
            }
            match roll::mint(&self.cfg.roll_path(), now, mac, &wid, plan, batch.pesos, batch.minutes, up, down, false) {
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
                Err(MintError::Mismatch { .. }) => {
                    crate::voucher::mark_unused(&dir, &hash); // nothing was added: the customer keeps the code
                    return Err(("plan", 0));
                }
                Err(MintError::Io) => {
                    crate::voucher::mark_unused(&dir, &hash);
                    return Err(("io", 0));
                }
            }
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
            pricing: self.pricing(),
            voucher,
            state: WState::Starting,
            pulses: 0,
            last_seq: 0,
            deadline: Instant::now() + Duration::from_secs(self.cfg.first_wait),
            total: self.cfg.first_wait,
            stop: false,
            box_ended: false,
            early: None,
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

    /// Some device has a coin window open (a payment is in progress).
    pub fn window_open(&self) -> bool {
        self.st.lock().unwrap().window.is_some()
    }

    /// This device has a coin window open now (one whose result is known already is over for the customer: only the box is
    /// still being acknowledged).
    pub fn window_open_for(&self, mac: &str) -> bool {
        self.st.lock().unwrap().window.as_ref().is_some_and(|w| w.mac == mac && w.early.is_none())
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
        let Some((wid, mac, plan, forfeit, voucher, pricing)) = ({
            let st = self.st.lock().unwrap();
            st.window
                .as_ref()
                .filter(|w| w.sid == sid)
                .map(|w| (w.wid.clone(), w.mac.clone(), w.plan, w.forfeit, w.voucher, Arc::clone(&w.pricing)))
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
        // AO-11: the record goes to flash now, while the box is armed, not when the first coin is seen. A kill or power cut between
        // the box counting a coin and the router noticing it used to leave that coin on the box with nothing to recover it.
        self.note_open(sid, &mac, plan, &wid, forfeit, voucher);
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
        let t_done = Instant::now();
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
        let t_released = t_done.elapsed().as_millis();
        let drain_end = Instant::now() + Duration::from_secs(cfg.drain_secs);
        let mut drained = false; // the box said it is idle (or sent "end"): every coin it counted is in `pulses`
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
            if ended || idle {
                drained = true;
                break;
            }
            if Instant::now() >= drain_end {
                log!("window {} drain timed out: the box never said it is idle, so it is acknowledged later (recovery)", &sid[..8]);
                break;
            }
            self.wait_end(sid, Duration::from_millis(300));
        }
        let t_drained = t_done.elapsed().as_millis();
        let pulses = self.cur(sid).map(|c| c.0).unwrap_or(last);
        if pulses == 0 {
            if !drained {
                // The box's link was lost: it may have counted coins the router never saw. The window is kept for recovery, which
                // credits whatever the box holds once it answers (it used to be closed as empty, and those coins were never credited).
                if !noted {
                    self.note_open(sid, &mac, plan, &wid, forfeit, voucher);
                }
                log!("window {} closed with no coins seen and no answer from the box: left for recovery", &sid[..8]);
                self.finish_window(sid, View::Empty, None);
                return;
            }
            log!("window {} closed with no coins", &sid[..8]);
            let _ = fs::remove_file(self.open_file(sid)); // nothing to recover: the box held no coin for it
            self.finish_window(sid, View::Empty, Some(false));
            return;
        }
        let view = if voucher {
            self.settle_voucher(sid, &mac, plan, &wid, pulses, &pricing, drained)
        } else {
            self.settle(sid, &mac, plan, &wid, forfeit, pulses, &pricing, drained)
        };
        // (a voucher window connects nothing, so there is nothing for the grant retries to finish)
        let refused = matches!(view, View::Final { online: false, voucher: None, .. });
        log!(
            "timing {} after the end of the window: box released {} ms, coins drained {} ms, settled and acknowledged {} ms",
            &sid[..8],
            t_released,
            t_drained,
            t_done.elapsed().as_millis()
        );
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

    /// A window leaves a record on flash from the moment the box is armed, kept until the box is acknowledged (or the window ends with
    /// no coin at all): if the router restarts mid-window, recovery credits what the box holds. Nothing is granted yet. Written once.
    fn note_open(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool, voucher: bool) {
        if std::path::Path::new(&self.open_file(sid)).exists() {
            return;
        }
        let _ = fs::create_dir_all(self.cfg.open_dir());
        // (records of earlier releases have four fields: no voucher). Flushed to flash: a power cut right after a coin must not lose it.
        if let Err(e) =
            write_synced(&self.open_file(sid), &format!("{} {} {} {} {}\n", mac, plan.as_str(), wid, forfeit as u8, voucher as u8))
        {
            log!("window {} could not be noted on flash: {}", &sid[..8], e);
        }
    }

    fn ack(&self, sid: &str) -> bool {
        self.box_.call(sid, "ack", "").map(|b| BoxLink::is_success(&b)).unwrap_or(false)
    }

    /// Price the window once, record it, grant access once, acknowledge the box.
    #[allow(clippy::too_many_arguments)]
    /// `ack_now` is false when the box never said it is idle: an acknowledgement erases every coin it holds for the window, also
    /// the ones the router has not seen yet, so it is left to recovery (which waits for "idle" and credits what came late).
    fn settle(&self, sid: &str, mac: &str, plan: Plan, wid: &str, forfeit: bool, pulses: u32, pr: &Pricing, ack_now: bool) -> View {
        let minutes = minutes_for(pr, plan, pulses);
        let now = now_secs();
        let t0 = Instant::now();
        if let Err(e) = self.record(sid, mac, plan, wid, forfeit, pulses, minutes, now) {
            return e;
        }
        let t_rec = t0.elapsed().as_millis();
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
        let t_grant = t0.elapsed().as_millis();
        let result = View::Final { plan: s.plan, pulses, minutes, left: s.left_secs, online, voucher: None };
        self.publish_early(sid, result.clone()); // the customer is online: the page says so now, not after the box answers
        let ack_ok = ack_now && self.ack_and_clear(sid);
        log!(
            "timing {} settled pulses={} min={} left={} min online={} acked={} (recorded {} ms, granted {} ms, acknowledged {} ms)",
            &sid[..8],
            pulses,
            minutes,
            s.minutes,
            online,
            ack_ok,
            t_rec,
            t_grant,
            t0.elapsed().as_millis()
        );
        result
    }

    /// A window that paid for a voucher code: price it once, make the one voucher (and its revenue row, as a direct purchase
    /// would), acknowledge the box. The device is NOT connected and no time is granted.
    #[allow(clippy::too_many_arguments)]
    fn settle_voucher(&self, sid: &str, mac: &str, plan: Plan, wid: &str, pulses: u32, pr: &Pricing, ack_now: bool) -> View {
        let minutes = minutes_for(pr, plan, pulses);
        let now = now_secs();
        let v = match self.record_voucher(sid, mac, plan, wid, pulses, minutes, now) {
            Ok(v) => v,
            Err(e) => return e,
        };
        let result = View::Final { plan, pulses, minutes, left: 0, online: false, voucher: Some(v) };
        self.publish_early(sid, result.clone());
        let ack_ok = ack_now && self.ack_and_clear(sid);
        log!("timing {} settled pulses={} min={} voucher issued acked={}", &sid[..8], pulses, minutes, ack_ok);
        result
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
                if let Err(e) = write_synced(&format!("{}.rec", self.open_file(sid)), "recorded\n") {
                    log!("window {} made its voucher, but its marker could not be written: {}", &sid[..8], e);
                }
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
        let (down, up) = rates(&self.pricing(), plan);
        let _g = self.files.lock().unwrap();
        match roll::mint(&self.cfg.roll_path(), now, mac, wid, plan, pulses, minutes, up, down, forfeit) {
            Ok(mode) => {
                if mode != roll::Mode::Dup {
                    if let Err(e) = ledger::append(&self.cfg.revenue_path(), now, plan.as_str(), pulses, minutes, mode.as_str()) {
                        log!("could not write the revenue ledger: {}", e);
                    }
                }
                // the pesos credited, so recovery can tell coins the box counted after the router stopped looking
                if let Err(e) = write_synced(&format!("{}.rec", self.open_file(sid)), &format!("recorded {}\n", pulses)) {
                    log!("window {} recorded, but its marker could not be written: {}", &sid[..8], e);
                }
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
            let rec_text = fs::read_to_string(format!("{}.rec", self.open_file(&sid))).ok();
            if let Some(rec_text) = rec_text {
                self.recover_recorded(&sid, &rec_text);
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
            if jget(&st, "state") != "idle" {
                continue; // still armed or draining: its coins are not all counted yet
            }
            let pulses = jget_u64(&st, "pulses").unwrap_or(0) as u32;
            if pulses > MAX_PULSES {
                log!("recover: window {} left as it is: the box reports {} pesos", &sid[..8], pulses);
                continue;
            }
            if pulses > 0 {
                let minutes = minutes_for(&self.pricing(), plan, pulses);
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

    /// A window that is on the roll but not acknowledged: acknowledged once the box says it is idle. Coins the box counted after the
    /// router stopped looking (its link dropped during the drain) are credited to the same device first: the acknowledgement erases
    /// everything the box holds for the window.
    fn recover_recorded(&self, sid: &str, rec_text: &str) {
        let st = match self.box_.call(sid, "status", "") {
            Ok(b) if BoxLink::is_success(&b) => b,
            _ => return,
        };
        if jget(&st, "state") != "idle" {
            return;
        }
        let recorded = rec_text.split_whitespace().nth(1).and_then(|n| n.parse::<u32>().ok());
        let boxed = jget_u64(&st, "pulses").unwrap_or(0) as u32;
        if let Some(recorded) = recorded {
            let late = boxed.saturating_sub(recorded);
            if late > 0 && boxed <= MAX_PULSES {
                let opened = fs::read_to_string(self.open_file(sid)).unwrap_or_default();
                let f: Vec<&str> = opened.split_whitespace().collect();
                match (f.first(), f.get(1).and_then(|p| Plan::parse(p)), f.get(2), f.get(4) == Some(&"1")) {
                    (Some(mac), Some(plan), Some(wid), false) => {
                        let minutes = minutes_for(&self.pricing(), plan, late);
                        let late_wid = late_wid(wid, boxed);
                        match self.record(sid, mac, plan, &late_wid, false, late, minutes, now_secs()) {
                            Ok(()) => {
                                // the marker now says every coin is credited: a failed acknowledgement below must not credit them twice
                                let _ = write_synced(&format!("{}.rec", self.open_file(sid)), &format!("recorded {}\n", boxed));
                                log!("recover: window {} credited {} late coins ({} min) to {}", &sid[..8], late, minutes, mac);
                                if let Some(s) = {
                                    let _g = self.files.lock().unwrap();
                                    roll::session(&self.cfg.roll_path(), now_secs(), mac)
                                } {
                                    let _ = self.nds.grant(mac, s.minutes, s.down, s.up, s.qdown, s.qup);
                                }
                            }
                            Err(_) => {
                                log!(
                                    "recover: window {} has {} late coins that could not be credited to {}: kept for the owner",
                                    &sid[..8],
                                    late,
                                    mac
                                );
                                return; // not acknowledged: the box keeps them (its orphan list after a day)
                            }
                        }
                    }
                    _ => log!("recover: window {} has {} late coins that cannot be credited (voucher or no record)", &sid[..8], late),
                }
            }
        }
        if self.ack(sid) {
            let _ = fs::remove_file(self.open_file(sid));
            let _ = fs::remove_file(format!("{}.rec", self.open_file(sid)));
        }
    }

    /// Notices a forward jump of the wall clock (more than two minutes beyond the time that really passed) and moves the sessions
    /// that were timed with the stale clock (roll::shift). A backward jump is only logged.
    pub fn check_clock(&self) {
        let mut g = self.clock.lock().unwrap();
        let wall = now_secs();
        let expected = g.0 + g.1.elapsed().as_secs();
        if wall > expected + 120 {
            let delta = wall - expected;
            // only what was timed with the stale clock moves: from the start of the stale period (g.2) up to the stale time at
            // which the clock was set (`expected`). A payment after that was timed with the right clock and must not move again.
            let (since, until) = (g.2, expected);
            let (moved_sessions, moved_ledger, moved_vouchers) = {
                let _f = self.files.lock().unwrap();
                let s = roll::shift(&self.cfg.roll_path(), since, until, delta);
                let l = ledger::shift(&self.cfg.revenue_path(), since, until, delta);
                let v = crate::voucher::shift(&self.cfg.data_dir, since, until, delta);
                // a record for the owner (and for tests); nothing reads it to decide credit
                let rec = format!(
                    "v=1\ndetected_at={}\nsince={}\njump_at_mono={}\ndelta={}\n",
                    wall,
                    since,
                    self.started.elapsed().as_secs(),
                    delta
                );
                if let Err(e) = crate::util::write_atomic(&format!("{}/clockjump", self.cfg.data_dir), rec.as_bytes()) {
                    log!("could not write the clock jump record: {}", e);
                }
                (s, l, v)
            };
            g.2 = wall; // what is paid from now on is timed with the right clock
            log!(
                "the clock jumped forward {} s (it was set after a stale start): {} session(s), {} revenue row(s), {} voucher(s) moved with it",
                delta,
                moved_sessions,
                moved_ledger,
                moved_vouchers
            );
        } else if expected > wall + 120 {
            log!("the clock went back {} s", expected - wall);
        }
        g.0 = wall;
        g.1 = Instant::now();
    }

    /// Housekeeping: settle leftovers, forget old sessions and old results.
    pub fn maintenance(self: &Arc<Self>) {
        self.check_clock();
        // RT-17: settling leftovers asks the coin box, which can take seconds to answer (or never); it runs on its own thread so
        // the clock check, the fair-use tick and the housekeeping below are not held up. One recovery at a time.
        use std::sync::atomic::Ordering;
        if self.recover_running.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire).is_ok() {
            let me = Arc::clone(self);
            let started = std::thread::Builder::new().name("recover".into()).spawn(move || {
                me.recover();
                me.recover_running.store(false, Ordering::Release);
            });
            if started.is_err() {
                self.recover_running.store(false, Ordering::Release);
            }
        }
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

/// The id under which coins the box counted after the router stopped looking are credited. It carries the box's total, so a second
/// late round of the same window (more coins, a different total) is a new payment and not taken for the first one again (RT-07).
fn late_wid(wid: &str, boxed: u32) -> String {
    format!("{}L{}", wid, boxed)
}

/// Write a small record and make sure it is on the flash (the file, then its folder) before saying so.
fn write_synced(path: &str, text: &str) -> std::io::Result<()> {
    use std::io::Write;
    let mut f = fs::File::create(path)?;
    f.write_all(text.as_bytes())?;
    f.sync_all()?;
    if let Some(dir) = std::path::Path::new(path).parent() {
        if let Ok(d) = fs::File::open(dir) {
            let _ = d.sync_all();
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::roll::Entry;

    /// A core on a temporary data folder, with no openNDS (every ndsctl call fails) and a clock that last looked at `last_seen`
    /// (a stale time the router started with) at start `since`.
    fn core_with_stale_clock(name: &str, last_seen: u64, since: u64) -> Arc<Core> {
        let d = std::env::temp_dir().join(format!("pisoportal-core-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        let mut cfg = Config::from_map(std::collections::HashMap::new());
        cfg.data_dir = d.to_string_lossy().into_owned();
        cfg.ndsctl = "/nonexistent/ndsctl".into();
        let core = Core::new(cfg);
        *core.clock.lock().unwrap() = (last_seen, Instant::now(), since);
        core
    }

    fn entry(code: &str, mac: &str, tl: u64, fp: u64) -> Entry {
        Entry::parse(&format!("{},0,0,0,0,{},{},{},0,0,0,hyper,w{},10,{},0,1", code, tl, fp, mac, code, tl)).unwrap()
    }

    const MAC_A: &str = "aa:aa:aa:aa:aa:01";
    const MAC_B: &str = "aa:aa:aa:aa:aa:02";
    const MAC_C: &str = "aa:aa:aa:aa:aa:03";

    #[test]
    fn rt01_rt02_the_fair_use_tick_applies_the_clock_jump_before_it_looks_at_any_session() {
        let now = now_secs();
        let s0 = now - 7200; // the stale time the router started with
                             // the clock was last looked at 100 s after the stale start; now it reads 7200 s later: the jump is about 7100 s
        let core = core_with_stale_clock("tick", s0 + 100, s0);
        let roll_path = core.cfg.roll_path();
        let stale = entry("c1", MAC_A, 60, s0 + 50); // paid during the stale period, 60 minutes
        let after = entry("c2", MAC_B, 60, now); // paid after the clock was right
        let old = entry("c3", MAC_C, 1440, s0 - 1000); // paid before the power cut
        roll::save(&roll_path, &[stale.clone(), after.clone(), old.clone()]).unwrap();

        crate::fair::tick(&core);

        let got = roll::load(&roll_path);
        assert_eq!(got.len(), 3, "RT-01: nothing is deleted");
        assert!(
            got[0].fp + 5 >= now - 60 && got[0].fp <= now,
            "RT-01: the stale session moved with the clock: {} (now {})",
            got[0].fp,
            now
        );
        assert!(got[0].left(now_secs()) > 3400, "and the customer still has their hour: {} s", got[0].left(now_secs()));
        assert_eq!(got[1].fp, after.fp, "RT-02: a payment made after the jump is not moved (free time)");
        assert_eq!(got[2].fp, old.fp, "a session from before the cut keeps its times");
        let rec = fs::read_to_string(format!("{}/clockjump", core.cfg.data_dir)).expect("the jump is recorded for the owner");
        assert!(
            rec.starts_with("v=1\n") && rec.contains("\ndetected_at=") && rec.contains("\nsince=") && rec.contains("\njump_at_mono="),
            "{}",
            rec
        );
        assert!(rec.contains(&format!("\nsince={}\n", s0)) && rec.contains("\ndelta=71"), "{}", rec);
    }

    #[test]
    fn rt01_reconnect_keeps_a_session_that_only_looks_expired_because_the_clock_has_not_been_shifted_yet() {
        let now = now_secs();
        let s0 = now - 7200;
        let core = core_with_stale_clock("reconnect", s0 + 100, s0);
        let roll_path = core.cfg.roll_path();
        roll::save(&roll_path, &[entry("c1", MAC_A, 60, s0 + 50)]).unwrap(); // 60 minutes paid, but 7000 s "ago" by the new clock

        let _ = core.reconnect(MAC_A);

        let got = roll::load(&roll_path);
        assert_eq!(got.len(), 1, "RT-01: the paid session was not deleted as expired before the shift ran");
        assert!(got[0].left(now_secs()) > 3400, "RT-01: and it was shifted: {} s left", got[0].left(now_secs()));
    }

    #[test]
    fn rt03_vouchers_and_ledger_rows_made_with_the_stale_clock_move_with_it() {
        let now = now_secs();
        let s0 = now - 40 * 86_400; // a router that was off for 40 days
        let core = core_with_stale_clock("voucher", s0 + 100, s0);
        let dir = core.cfg.data_dir.clone();
        let v = crate::voucher::issue(&dir, s0 + 50, MAC_A, "w1", "hyper", 60, 10).unwrap();
        ledger::append(&core.cfg.revenue_path(), s0 + 50, "hyper", 10, 60, "vbuy").unwrap();
        assert_eq!(
            crate::voucher::lookup(&dir, now, &v.code),
            Err(crate::voucher::RedeemError::Expired),
            "before the shift it reads as expired"
        );

        core.check_clock();

        assert!(crate::voucher::lookup(&dir, now_secs(), &v.code).is_ok(), "RT-03: the voucher is good for its 30 days after the shift");
        let row = fs::read_to_string(core.cfg.revenue_path()).unwrap();
        let ts: u64 = row.split(',').next().unwrap().parse().unwrap();
        assert!(ts + 5 >= now - 60 && ts <= now, "and its ledger row moved too: {}", row);
        assert!(ledger::verify(&core.cfg.revenue_path()).is_ok());
    }

    #[test]
    fn rt07_a_second_late_round_of_the_same_window_has_its_own_id() {
        assert_eq!(late_wid("w9", 3), "w9L3");
        assert_ne!(late_wid("w9", 3), late_wid("w9", 5), "two late rounds must not look like the same window");
    }

    #[test]
    fn rt17_a_box_that_never_answers_does_not_hold_up_maintenance() {
        // a "box" that accepts the connection and says nothing; two windows are waiting to be settled
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap().to_string();
        let held = std::thread::spawn(move || {
            let mut socks = Vec::new();
            while let Ok((s, _)) = l.accept() {
                socks.push(s);
            }
        });
        let d = std::env::temp_dir().join(format!("pisoportal-rt17-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(d.join("open")).unwrap();
        fs::write(d.join("open/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), format!("{} hyper w1 0 0\n", MAC_A)).unwrap();
        fs::write(d.join("open/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), format!("{} hyper w2 0 0\n", MAC_B)).unwrap();
        let mut cfg = Config::from_map(std::collections::HashMap::new());
        cfg.data_dir = d.to_string_lossy().into_owned();
        cfg.ndsctl = "/nonexistent/ndsctl".into();
        cfg.gw_box = addr;
        cfg.gw_key = "test-gateway-key-123456".into();
        let core = Core::new(cfg);
        let t0 = Instant::now();
        core.maintenance();
        let took = t0.elapsed();
        assert!(took < Duration::from_millis(1500), "maintenance waited {:?} for a box that never answers", took);
        // and a second round while the first recovery is still waiting does not start another one
        let t1 = Instant::now();
        core.maintenance();
        assert!(t1.elapsed() < Duration::from_millis(1500));
        assert!(
            core.recover_running.load(std::sync::atomic::Ordering::Acquire),
            "the first recovery is still waiting on the box, on its own thread"
        );
        drop(held);
    }
}
