//! The responsibility gate for the spinning wheel. The wheel cannot be on until the owner has read the warning in the console and typed
//! the exact phrase; the router keeps a record of that, and a setting alone ("WHEEL=on" typed into a file) is not enough. The record is
//! cleared when the wheel is turned off and when the warning text changes (WARNING_VERSION), so the owner confirms again each time.
#![allow(dead_code)]

/// Raise this whenever the warning text in the console changes: every older confirmation stops counting.
pub const WARNING_VERSION: u32 = 1;
/// What the owner must type, exactly as written.
pub const PHRASE: &str = "I TAKE RESPONSIBILITY";

#[derive(Debug, PartialEq)]
pub enum GateError {
    WrongPhrase,
    OldWarning,
    Io,
}

impl GateError {
    pub fn message(&self) -> &'static str {
        match self {
            GateError::WrongPhrase => "Type the confirmation exactly as shown, capital letters included.",
            GateError::OldWarning => "The warning has changed. Reload the page, read it again and confirm.",
            GateError::Io => "The router could not save your confirmation, so the wheel stays off.",
        }
    }
}

fn record_path(data_dir: &str) -> String {
    format!("{}/wheel-confirm.txt", data_dir)
}

fn log_path(data_dir: &str) -> String {
    format!("{}/wheel.log", data_dir)
}

/// The wheel log never grows past this (AO-04): at the limit it becomes `wheel.log.1` (the one before it is dropped) and a new one starts.
const LOG_LIMIT: u64 = 256 * 1024;

/// One line in the wheel's own log: time, what happened, detail.
pub(crate) fn log(data_dir: &str, now: u64, what: &str, detail: &str) {
    use std::io::Write;
    let path = log_path(data_dir);
    if std::fs::metadata(&path).map(|m| m.len() >= LOG_LIMIT).unwrap_or(false) {
        let _ = crate::util::rename_durable(&path, &format!("{}.1", path));
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{},{},{}", now, what, detail);
    }
}

/// The owner confirmed in the console: the phrase and the warning version they saw must both be right. Writes the record.
pub fn turn_on(data_dir: &str, now: u64, phrase: &str, version: u32, session: &str) -> Result<(), GateError> {
    if version != WARNING_VERSION {
        return Err(GateError::OldWarning);
    }
    if phrase != PHRASE {
        return Err(GateError::WrongPhrase);
    }
    // the session label only goes into the record: keep it to one short safe line
    let session: String = session.chars().filter(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.')).take(40).collect();
    let text = format!("v=1\nversion={}\nat={}\nsession={}\n", WARNING_VERSION, now, session);
    crate::util::write_atomic(&record_path(data_dir), text.as_bytes()).map_err(|_| GateError::Io)?;
    log(data_dir, now, "on", &format!("warning version {}, session {}", WARNING_VERSION, session));
    Ok(())
}

/// Turning the wheel off needs no confirmation and clears the record.
pub fn turn_off(data_dir: &str, now: u64) {
    let _ = std::fs::remove_file(record_path(data_dir));
    log(data_dir, now, "off", "");
}

/// Is there a valid record for the current warning?
pub fn confirmed(data_dir: &str) -> bool {
    confirmed_at(data_dir).is_some()
}

/// When the owner confirmed (unix seconds), if the record is valid.
pub fn confirmed_at(data_dir: &str) -> Option<u64> {
    let text = std::fs::read_to_string(record_path(data_dir)).ok()?;
    let get = |key: &str| text.lines().find_map(|l| l.strip_prefix(key)?.strip_prefix('=')).map(str::to_string);
    if get("v")? != "1" || get("version")?.parse::<u32>().ok()? != WARNING_VERSION {
        return None;
    }
    get("at")?.parse::<u64>().ok()
}

/// The wheel is on only when the setting says so AND the owner's confirmation is on record.
pub fn enabled(data_dir: &str, setting_on: bool) -> bool {
    setting_on && confirmed(data_dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-wheelgate-{}-{}", name, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    #[test]
    fn rt_wrong_or_missing_phrase_is_refused_and_nothing_is_written() {
        let d = dir("phrase");
        for bad in ["", "i take responsibility", "I TAKE RESPONSIBILITY ", " I TAKE RESPONSIBILITY", "I TAKE  RESPONSIBILITY", "OK"] {
            assert_eq!(turn_on(&d, 100, bad, WARNING_VERSION, "s1"), Err(GateError::WrongPhrase), "{:?}", bad);
        }
        assert!(!confirmed(&d));
        assert!(!std::path::Path::new(&record_path(&d)).exists());
        assert!(!enabled(&d, true));
    }

    #[test]
    fn rt_an_old_warning_version_is_refused() {
        let d = dir("version");
        assert_eq!(turn_on(&d, 100, PHRASE, WARNING_VERSION - 1, "s1"), Err(GateError::OldWarning));
        assert_eq!(turn_on(&d, 100, PHRASE, WARNING_VERSION + 1, "s1"), Err(GateError::OldWarning));
        assert!(!confirmed(&d));
    }

    #[test]
    fn rt_the_exact_phrase_with_the_current_version_writes_the_record() {
        let d = dir("ok");
        assert_eq!(turn_on(&d, 1234, PHRASE, WARNING_VERSION, "console-session-9"), Ok(()));
        assert!(confirmed(&d));
        assert_eq!(confirmed_at(&d), Some(1234));
        assert!(enabled(&d, true));
        // the setting is still needed too
        assert!(!enabled(&d, false));
        let rec = std::fs::read_to_string(record_path(&d)).unwrap();
        assert!(rec.contains("version=1") && rec.contains("at=1234") && rec.contains("session=console-session-9"), "{}", rec);
        let log = std::fs::read_to_string(log_path(&d)).unwrap();
        assert!(log.contains("1234,on,"), "{}", log);
    }

    #[test]
    fn rt_a_setting_without_a_record_leaves_the_wheel_off() {
        let d = dir("handedit");
        // the owner (or an update, or the setup program) wrote WHEEL=on by hand; no confirmation was ever recorded
        assert!(!enabled(&d, true));
        assert!(!confirmed(&d));
        assert_eq!(confirmed_at(&d), None);
    }

    #[test]
    fn rt_turning_it_off_clears_the_record_so_the_next_time_asks_again() {
        let d = dir("off");
        turn_on(&d, 10, PHRASE, WARNING_VERSION, "s").unwrap();
        turn_off(&d, 20);
        assert!(!confirmed(&d));
        assert!(!enabled(&d, true));
        let log = std::fs::read_to_string(log_path(&d)).unwrap();
        assert!(log.contains("20,off"), "{}", log);
        // off again with no record is harmless
        turn_off(&d, 30);
    }

    #[test]
    fn rt_a_record_of_an_older_warning_or_a_damaged_one_does_not_count() {
        let d = dir("old");
        std::fs::write(record_path(&d), "v=1\nversion=0\nat=5\nsession=x\n").unwrap();
        assert!(!confirmed(&d));
        std::fs::write(record_path(&d), "garbage").unwrap();
        assert!(!confirmed(&d));
        std::fs::write(record_path(&d), "v=1\nversion=1\nat=notanumber\nsession=x\n").unwrap();
        assert!(!confirmed(&d));
        assert!(!enabled(&d, true));
    }

    #[test]
    fn ao04_the_wheel_log_is_capped_and_keeps_one_older_copy() {
        let d = dir("ao04");
        for i in 0..20_000u64 {
            log(&d, 1_700_000_000 + i, "spin", "aa:bb:cc:00:00:01,0123456789abcdef,5,2,5,120");
        }
        let (cur, old) = (std::fs::metadata(log_path(&d)).unwrap().len(), std::fs::metadata(format!("{}.1", log_path(&d))).unwrap().len());
        assert!(cur <= LOG_LIMIT + 200 && old <= LOG_LIMIT + 200, "the log passed its limit: {} and {} bytes", cur, old);
        assert!(cur > 0 && old >= LOG_LIMIT, "the older half is kept: {} and {}", cur, old);
        assert!(!std::path::Path::new(&format!("{}.2", log_path(&d))).exists(), "only one older copy");
        let text = std::fs::read_to_string(log_path(&d)).unwrap();
        assert!(text.lines().last().unwrap().starts_with("1700019999,spin,"), "the newest line is in the current file");
    }
}
