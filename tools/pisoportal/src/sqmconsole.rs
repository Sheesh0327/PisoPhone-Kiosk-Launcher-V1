//! The console's smooth-line (SQM) settings (feature 056, group 4): what the Settings page is told about SQM, which values it may save, and the hand-over to
//! `piso-setup sqm apply`. The router's speeds and margin are kept in DATA_DIR/settings.txt next to the other console settings; `piso-setup` reads them from
//! there and writes DATA_DIR/sqm.txt ("key|value" lines) to say what it did. The portal never touches the router's SQM files itself.
use crate::util::{jget, json_esc};
use std::process::{Command, Stdio};
use std::sync::Mutex;

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

/// The block the Settings page draws: whether SQM can work on this router and why not, what the owner typed, and what the router did with it.
pub fn json(dd: &str) -> String {
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
    format!(
        "{{\"available\":{},\"reason\":\"{}\",\"on\":{},\"down\":\"{}\",\"up\":\"{}\",\"percent\":{},\"qdisc\":\"{}\",\"active\":{},\"limit_down\":{},\"limit_up\":{},\"applying\":{}}}",
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
        APPLY.lock().unwrap().0
    )
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
}
