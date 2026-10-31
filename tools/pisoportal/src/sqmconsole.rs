//! The console's smooth-line (SQM) settings (feature 056, group 4): what the Settings page is told about SQM, which values it may save, and the hand-over to
//! `piso-setup sqm apply`. The router's speeds and margin are kept in DATA_DIR/settings.txt next to the other console settings; `piso-setup` reads them from
//! there and writes DATA_DIR/sqm.txt ("key|value" lines) to say what it did. The portal never touches the router's SQM files itself.
use crate::core::Core;
use crate::util::{jget, json_esc};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

const DEFAULT_PERCENT: u32 = 95;

/// A value the page sent, checked: the settings.txt lines to keep.
pub struct Change(pub Vec<(&'static str, String)>);

/// (running, another apply is wanted when this one ends): only one `piso-setup sqm apply` runs at a time.
static APPLY: Mutex<(bool, bool)> = Mutex::new((false, false));

/// A line speed in Mbps: 0.1 to 10000 with at most one decimal place, kept in its plain form ("050.0" is "50").
fn speed(v: &str) -> Option<String> {
    let ok = !v.is_empty()
        && v.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        && !v.starts_with('.')
        && !v.ends_with('.')
        && v.matches('.').count() <= 1
        && v.split_once('.').is_none_or(|(_, d)| d.len() == 1);
    let tenths = (v.parse::<f64>().ok().filter(|_| ok)? * 10.0).round() as u64;
    let (whole, tenth) = (tenths / 10, tenths % 10);
    (1..=100_000).contains(&tenths).then(|| if tenth == 0 { whole.to_string() } else { format!("{}.{}", whole, tenth) })
}

/// The margin: a whole number from 50 to 100.
fn percent(v: &str) -> Option<u32> {
    v.bytes().all(|b| b.is_ascii_digit()).then(|| v.parse::<u32>().ok()).flatten().filter(|p| (50..=100).contains(p))
}

fn switch(v: &str) -> Option<bool> {
    match v.to_ascii_lowercase().as_str() {
        "on" | "1" | "true" => Some(true),
        "off" | "0" | "false" => Some(false),
        _ => None,
    }
}

fn bad(code: &str, why: &str) -> Result<Option<Change>, (String, String)> {
    Err((code.to_string(), why.to_string()))
}

/// Reads the SQM fields of a settings request. Ok(None): the request has none. Ok(Some): all of them are good, and switching on has both speeds.
/// Err((code, why)): one is not, and nothing may be saved.
pub fn parse(dd: &str, body: &str) -> Result<Option<Change>, (String, String)> {
    let (on, down, up, pc) = (jget(body, "sqm_on"), jget(body, "sqm_down"), jget(body, "sqm_up"), jget(body, "sqm_percent"));
    if on.is_empty() && down.is_empty() && up.is_empty() && pc.is_empty() {
        return Ok(None);
    }
    let mut out = Vec::new();
    let (mut now_on, mut now_down, mut now_up) = (
        crate::config::override_get(dd, "SQM_ON") == "1",
        crate::config::override_get(dd, "SQM_DOWN_MBPS"),
        crate::config::override_get(dd, "SQM_UP_MBPS"),
    );
    if !on.is_empty() {
        let Some(v) = switch(&on) else { return bad("BAD_SQM_SWITCH", "the smooth line switch must be on or off") };
        now_on = v;
        out.push(("SQM_ON", if v { "1" } else { "0" }.to_string()));
    }
    for (label, key, val, slot) in [("download", "SQM_DOWN_MBPS", &down, &mut now_down), ("upload", "SQM_UP_MBPS", &up, &mut now_up)] {
        if val.is_empty() {
            continue;
        }
        let Some(v) = speed(val) else {
            return bad("BAD_SQM_SPEED", &format!("the {} speed must be a number from 0.1 to 10000 Mbps, with at most one decimal", label));
        };
        *slot = v.clone();
        out.push((key, v));
    }
    if !pc.is_empty() {
        let Some(p) = percent(&pc) else { return bad("BAD_SQM_PERCENT", "the margin must be a whole number from 50 to 100 percent") };
        out.push(("SQM_PERCENT", p.to_string()));
    }
    if now_on && (now_down.is_empty() || now_up.is_empty()) {
        return bad("SQM_NEEDS_SPEEDS", "type both the download and the upload speed of the line before switching the smooth line on");
    }
    Ok(Some(Change(out)))
}

/// Keeps the checked values. False when one could not be written.
pub fn save(dd: &str, change: &Change) -> bool {
    change.0.iter().all(|(k, v)| crate::config::override_set(dd, k, v))
}

/// Has `piso-setup sqm apply` run, from its own thread (it takes seconds and the request must not wait). The command and its arguments are fixed: nothing the
/// page sent is in them. A change that arrives while one runs is applied once more, after it.
pub fn apply_in_background(bin: &str) {
    {
        let mut s = APPLY.lock().unwrap();
        if s.0 {
            s.1 = true;
            return;
        }
        s.0 = true;
    }
    let bin = bin.to_string();
    let started = std::thread::Builder::new().spawn(move || loop {
        match Command::new(&bin).args(["sqm", "apply"]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status() {
            Ok(st) if st.success() => {}
            Ok(st) => eprintln!("coinslot: piso-setup sqm apply ended with {}", st),
            Err(e) => eprintln!("coinslot: piso-setup sqm apply could not start: {}", e),
        }
        let mut s = APPLY.lock().unwrap();
        if s.1 {
            s.1 = false;
            continue;
        }
        s.0 = false;
        break;
    });
    if started.is_err() {
        *APPLY.lock().unwrap() = (false, false);
    }
}

fn status_value(text: &str, key: &str) -> String {
    text.lines()
        .filter_map(|l| l.split_once('|'))
        .filter(|(k, _)| *k == key)
        .map(|(_, v)| v.trim().to_string())
        .next_back()
        .unwrap_or_default()
}

/// The block the Settings page draws: whether SQM can work on this router and why not, what the owner typed, what the router did with it, and the
/// speed test (its state, its result and whether it may start now).
pub fn json(core: &Core) -> String {
    let dd = &core.cfg.data_dir;
    let text = std::fs::read_to_string(format!("{}/sqm.txt", dd)).unwrap_or_default();
    let available = status_value(&text, "installed") == "1";
    let mut reason = status_value(&text, "reason");
    if reason.is_empty() && !available {
        reason = if text.is_empty() {
            "The router has not reported on the smooth line (SQM) yet.".to_string()
        } else {
            "The smooth line (SQM) is not installed on this router.".to_string()
        };
    }
    let kbps = |k: &str| status_value(&text, k).parse::<u64>().unwrap_or(0);
    let pc =
        crate::config::override_get(dd, "SQM_PERCENT").parse::<u32>().ok().filter(|p| (50..=100).contains(p)).unwrap_or(DEFAULT_PERCENT);
    // (read before format!: a guard held inside its arguments would still be held when install_json and speedtest_json lock the same flags)
    let (applying, installing) = (APPLY.lock().unwrap().0, *INSTALLING.lock().unwrap());
    format!(
        "{{\"available\":{},\"reason\":\"{}\",\"on\":{},\"down\":\"{}\",\"up\":\"{}\",\"percent\":{},\"qdisc\":\"{}\",\"active\":{},\"limit_down\":{},\"limit_up\":{},\"applying\":{},\"installing\":{},\"install\":{},\"speedtest\":{}}}",
        available,
        json_esc(&reason),
        crate::config::override_get(dd, "SQM_ON") == "1",
        json_esc(&crate::config::override_get(dd, "SQM_DOWN_MBPS")),
        json_esc(&crate::config::override_get(dd, "SQM_UP_MBPS")),
        pc,
        json_esc(&status_value(&text, "qdisc")),
        status_value(&text, "active") == "1",
        kbps("limit_down_kbps"),
        kbps("limit_up_kbps"),
        applying,
        installing,
        install_json(core, available),
        speedtest_json(core, available)
    )
}

// ---- "Test my line" (group 5c): the portal only starts `piso-setup sqm test` and reads the file it leaves; it never measures anything itself -----------

/// (a test is running, when the last one was started, why the last one ended without a result).
struct TestRun {
    running: bool,
    last_start: Option<Instant>,
    note: String,
}

static TEST: Mutex<TestRun> = Mutex::new(TestRun { running: false, last_start: None, note: String::new() });

/// Why a test may not start now: (code, words for the owner). Nothing here depends on what the page sent.
fn blocked(
    available: bool,
    online: usize,
    window_open: bool,
    running: bool,
    since_last: Option<u64>,
    gap: u64,
) -> Option<(&'static str, String)> {
    if running {
        return Some(("RUNNING", "A speed test is running. It takes about a minute.".to_string()));
    }
    if !available {
        return Some(("NO_SQM", "The smooth line (SQM) is not installed on this router, so there is nothing to set yet.".to_string()));
    }
    if online > 0 {
        return Some((
            "BUSY",
            "Customers are online right now, and the test would slow them down. Test when the shop is quiet.".to_string(),
        ));
    }
    if window_open {
        return Some(("BUSY", "A customer is paying right now. Test again in a moment.".to_string()));
    }
    match since_last {
        Some(s) if s < gap => Some(("TOO_SOON", format!("Please wait {} seconds before testing again.", gap - s))),
        _ => None,
    }
}

fn why_blocked(core: &Core, available: bool) -> Option<(&'static str, String)> {
    let run = TEST.lock().unwrap();
    let (running, since) = (run.running, run.last_start.map(|t| t.elapsed().as_secs()));
    drop(run);
    // (ndsctl is asked only when nothing else already says no)
    let online = if available && !running { core.nds.clients().iter().filter(|c| c.1 == "Authenticated").count() } else { 0 };
    blocked(available, online, core.window_open(), running, since, core.cfg.speedtest_gap)
}

fn number(v: &str) -> String {
    v.parse::<f64>().map(|n| format!("{:.1}", n)).unwrap_or_else(|_| "null".to_string())
}

fn speedtest_json(core: &Core, available: bool) -> String {
    let file = std::fs::read_to_string(&core.cfg.speedtest_file).unwrap_or_default();
    let (running, note) = {
        let r = TEST.lock().unwrap();
        (r.running, r.note.clone())
    };
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let time = jget(&file, "time").parse::<u64>().unwrap_or(0);
    let mut state = jget(&file, "state");
    let mut reason = jget(&file, "reason");
    if running {
        state = "running".to_string();
    } else if file.is_empty() {
        state = if note.is_empty() { "idle" } else { "failed" }.to_string();
        reason = note;
    } else if state == "running" {
        // a test that was killed leaves this behind; no test of ours is running
        state = "failed".to_string();
        reason = "The last test was interrupted.".to_string();
    } else if state != "done" && state != "failed" {
        state = "idle".to_string();
    }
    let usable = !running && state == "done";
    let (down, up) =
        if usable { (number(&jget(&file, "down")), number(&jget(&file, "up"))) } else { ("null".to_string(), "null".to_string()) };
    let age = if time > 0 && !running && state != "idle" { now.saturating_sub(time).to_string() } else { "null".to_string() };
    let block = why_blocked(core, available);
    format!(
        "{{\"state\":\"{}\",\"down\":{},\"up\":{},\"host\":\"{}\",\"cpu\":{},\"cpu_limited\":{},\"age\":{},\"reason\":\"{}\",\"can_start\":{},\"blocked\":\"{}\"}}",
        state,
        down,
        up,
        json_esc(&jget(&file, "host")),
        jget(&file, "cpu").parse::<u32>().unwrap_or(0),
        usable && jget(&file, "cpu_limited") == "true",
        age,
        json_esc(&reason),
        block.is_none(),
        json_esc(&block.map(|b| b.1).unwrap_or_default())
    )
}

/// Starts a test from its own thread. Err((code, words)): it may not start now, and nothing was started.
pub fn start_speedtest(core: &Core) -> Result<(), (String, String)> {
    let available =
        status_value(&std::fs::read_to_string(format!("{}/sqm.txt", core.cfg.data_dir)).unwrap_or_default(), "installed") == "1";
    if let Some((code, why)) = why_blocked(core, available) {
        return Err((code.to_string(), why));
    }
    {
        let mut run = TEST.lock().unwrap();
        if run.running {
            return Err(("RUNNING".to_string(), "A speed test is running. It takes about a minute.".to_string()));
        }
        *run = TestRun { running: true, last_start: Some(Instant::now()), note: String::new() };
    }
    std::fs::remove_file(&core.cfg.speedtest_file).ok(); // (an earlier result must not pass for this one if the start fails)
    let (bin, file) = (core.cfg.setup_bin.clone(), core.cfg.speedtest_file.clone());
    let started = std::thread::Builder::new().spawn(move || {
        let note = match Command::new(&bin).args(["sqm", "test"]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status()
        {
            Ok(st) if st.code() == Some(3) => "Customers are online, so the test did not start.".to_string(),
            Ok(st) if !st.success() && !std::path::Path::new(&file).exists() => format!("The router could not run the test ({}).", st),
            Ok(_) => String::new(),
            Err(e) => format!("The router could not run the test ({}).", e),
        };
        let mut run = TEST.lock().unwrap();
        run.running = false;
        run.note = note;
    });
    if started.is_err() {
        *TEST.lock().unwrap() = TestRun { running: false, last_start: None, note: "The router could not start the test.".to_string() };
        return Err(("NO_THREAD".to_string(), "The router could not start the test.".to_string()));
    }
    Ok(())
}

// ---- "Install now" (group 5): the optional packages, once, on a router that is already running --------------------------------------------------------

static INSTALLING: Mutex<bool> = Mutex::new(false);

/// What the setup recorded about the shaper package (etc/coinslot.d/packages.txt: "pkg|<name>|<state>|<reason>"): (state, reason).
fn package_note(dd: &str) -> Option<(String, String)> {
    let text = std::fs::read_to_string(format!("{}/packages.txt", dd)).ok()?;
    text.lines().find_map(|l| {
        let p: Vec<&str> = l.splitn(4, '|').collect();
        (p.len() == 4 && p[0] == "pkg" && p[1] == "sqm-scripts").then(|| (p[2].to_string(), p[3].to_string()))
    })
}

/// Trying again helps with a failed install, no internet or too little flash. It does not help when the package list has no such package, there is no
/// package manager, or the image does not fit.
fn retry_cannot_help(state: &str, reason: &str) -> bool {
    state == "skipped"
        && ["not in the package feed", "no package manager", "does not fit", "custom image"].iter().any(|k| reason.contains(k))
}

/// Why Install now may not start: (code, words for the owner).
fn install_blocked(core: &Core, available: bool) -> Option<(&'static str, String)> {
    if available {
        return Some(("INSTALLED", "The smooth line is already installed.".to_string()));
    }
    if *INSTALLING.lock().unwrap() || TEST.lock().unwrap().running {
        return Some(("RUNNING", "The router is busy with the smooth line right now. It takes about a minute.".to_string()));
    }
    if let Some((state, reason)) = package_note(&core.cfg.data_dir) {
        if retry_cannot_help(&state, &reason) {
            return Some(("NOT_POSSIBLE", format!("Trying again would not help: {}.", reason)));
        }
    }
    if core.window_open() || core.nds.clients().iter().any(|c| c.1 == "Authenticated") {
        return Some((
            "BUSY",
            "Customers are online or paying right now, and installing slows the router for a minute. Try when the shop is quiet."
                .to_string(),
        ));
    }
    None
}

fn install_json(core: &Core, available: bool) -> String {
    let block = install_blocked(core, available);
    format!("{{\"can\":{},\"why\":\"{}\"}}", block.is_none(), json_esc(&block.map(|b| b.1).unwrap_or_default()))
}

/// Has `piso-setup sqm install` run from its own thread (it downloads and installs, which takes a while). Fixed arguments, nothing from the page.
pub fn start_install(core: &Core) -> Result<(), (String, String)> {
    let available =
        status_value(&std::fs::read_to_string(format!("{}/sqm.txt", core.cfg.data_dir)).unwrap_or_default(), "installed") == "1";
    if let Some((code, why)) = install_blocked(core, available) {
        return Err((code.to_string(), why));
    }
    {
        let mut running = INSTALLING.lock().unwrap();
        if *running {
            return Err(("RUNNING".to_string(), "The router is busy with the smooth line right now. It takes about a minute.".to_string()));
        }
        *running = true;
    }
    let bin = core.cfg.setup_bin.clone();
    let started = std::thread::Builder::new().spawn(move || {
        match Command::new(&bin).args(["sqm", "install"]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).status() {
            Ok(st) if st.success() => {}
            Ok(st) => eprintln!("coinslot: piso-setup sqm install ended with {}", st),
            Err(e) => eprintln!("coinslot: piso-setup sqm install could not start: {}", e),
        }
        *INSTALLING.lock().unwrap() = false;
    });
    if started.is_err() {
        *INSTALLING.lock().unwrap() = false;
        return Err(("NO_THREAD".to_string(), "The router could not start the install.".to_string()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn speeds_are_plain_numbers_in_range() {
        assert_eq!(speed("50").as_deref(), Some("50"));
        assert_eq!(speed("050.0").as_deref(), Some("50"));
        assert_eq!(speed("87.3").as_deref(), Some("87.3"));
        assert_eq!(speed("0.1").as_deref(), Some("0.1"));
        assert_eq!(speed("10000").as_deref(), Some("10000"));
        for b in ["", "0", "0.0", "10000.1", "10001", "5.55", ".5", "50.", "1e3", "-1", "a", "1.2.3", " 5", "5 "] {
            assert!(speed(b).is_none(), "{:?} must be refused", b);
        }
    }

    #[test]
    fn the_margin_is_a_whole_number_from_50_to_100() {
        assert_eq!((percent("50"), percent("95"), percent("100")), (Some(50), Some(95), Some(100)));
        for b in ["", "49", "101", "95.5", "-5", "abc", "+95"] {
            assert!(percent(b).is_none(), "{:?} must be refused", b);
        }
    }

    #[test]
    fn a_request_without_sqm_fields_is_not_ours_and_a_bad_one_is_refused_whole() {
        let dir = std::env::temp_dir().join(format!("sqmconsole-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dd = dir.to_str().unwrap();
        assert!(matches!(parse(dd, "{\"voucher_choice\":\"off\"}"), Ok(None)));
        assert_eq!(parse(dd, "{\"sqm_down\":\"50\",\"sqm_up\":\"x\"}").err().map(|e| e.0), Some("BAD_SQM_SPEED".to_string()));
        assert_eq!(parse(dd, "{\"sqm_on\":\"on\"}").err().map(|e| e.0), Some("SQM_NEEDS_SPEEDS".to_string()));
        let ok = parse(dd, "{\"sqm_on\":\"on\",\"sqm_down\":\"50\",\"sqm_up\":\"20\"}").unwrap().unwrap();
        assert_eq!(ok.0, vec![("SQM_ON", "1".to_string()), ("SQM_DOWN_MBPS", "50".to_string()), ("SQM_UP_MBPS", "20".to_string())]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_test_starts_only_on_a_quiet_router_one_at_a_time_and_not_too_often() {
        let why = |a, o, w, r, s| blocked(a, o, w, r, s, 60).map(|b| b.0);
        assert_eq!(why(true, 0, false, false, None), None);
        assert_eq!(why(true, 0, false, false, Some(61)), None);
        assert_eq!(why(true, 0, false, true, None), Some("RUNNING"));
        assert_eq!(why(false, 0, false, false, None), Some("NO_SQM"));
        assert_eq!(why(true, 1, false, false, None), Some("BUSY"));
        assert_eq!(why(true, 0, true, false, None), Some("BUSY"), "a coin window or payment in progress is as busy as a customer online");
        assert_eq!(why(true, 0, false, false, Some(59)), Some("TOO_SOON"));
        assert_eq!(
            blocked(true, 0, false, false, Some(20), 60).map(|b| b.1),
            Some("Please wait 40 seconds before testing again.".to_string())
        );
    }

    #[test]
    fn a_number_from_the_result_file_is_shown_with_one_decimal_or_not_at_all() {
        assert_eq!((number("87.4"), number("312.5"), number("88")), ("87.4".to_string(), "312.5".to_string(), "88.0".to_string()));
        assert_eq!((number("null"), number("")), ("null".to_string(), "null".to_string()));
    }

    #[test]
    fn trying_the_install_again_helps_only_when_something_can_change() {
        assert!(retry_cannot_help("skipped", "not in the package feed"));
        assert!(retry_cannot_help("skipped", "no package manager on this router"));
        assert!(retry_cannot_help("skipped", "the package manager's trial install (dry run) failed: it does not fit this image"));
        assert!(!retry_cannot_help("failed", "the install did not finish, so it was taken out again"));
        assert!(!retry_cannot_help("skipped", "only 1500 KB of flash is free, and this step needs 2048 KB"));
        assert!(!retry_cannot_help("installed", "added by this setup"));
    }
}
