//! Settings: /etc/coinslot.conf (KEY='value' lines, written by piso-setup) with the environment on top (tests, one-offs).
use std::collections::HashMap;
use std::fs;

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    pub port: u16,
    pub admin_port: u16,
    pub event_port: u16,
    pub event_bind: String,
    pub gw_box: String,
    pub gw_key: String,
    pub data_dir: String,
    pub ndsctl: String,
    pub arp_file: String,
    pub first_wait: u64,
    pub idle_wait: u64,
    pub max_window: u64,
    pub poll_ms: u64,
    pub drain_secs: u64,
    pub hyper_tiers: Vec<(u32, u32)>,
    pub hyper_prorata_min: u32,
    pub endurance_tiers: Vec<(u32, u32)>,
    pub endurance_down: u32,
    pub endurance_up: u32,
    pub fair_kb: u64,
    pub fair_down: u32,
    pub fair_up: u32,
    pub fair_throttle_min: u64,
    pub fair_full_min: u64,
    pub pause_max: u32,
    /// pesos a session must have paid in all to be allowed a pause (PAUSE_MIN_PESOS, default 20; 0 = anyone with time left)
    pub pause_min_pesos: u32,
    pub pause_min_left: u64,
    pub pause_gap: u64,
    /// a paused device stays online at this speed, in kbit/s (PAUSE_KBPS, default 1: nothing usable, but its page and its buttons keep working)
    pub pause_kbps: u32,
    /// the most a paused device may move during one pause, in kB (PAUSE_QUOTA_KB, default 2048 = 2 MB, 0 = no limit): openNDS takes the device off when it is used up
    pub pause_quota_kb: u64,
    /// the coin page offers "Use now" or "Get a voucher code" (VOUCHER_CHOICE, default on; the owner can switch it in the console)
    pub voucher_choice: bool,
    /// minutes of mini-game a device gets per day (GAME_MINUTES, default 10, 0 = no game; the owner can change it in the console)
    pub game_minutes: u32,
    /// the same in seconds, for tests only (GAME_LIMIT_SECONDS); wins over the minutes when set
    pub game_seconds: Option<u32>,
    /// where guests' files go: the print server's inbox (SEND_HOST, SEND_PORT); on only when DATA_DIR/send.key exists
    pub send_host: String,
    pub send_port: u16,
    /// the largest file a guest may send, in MB (SEND_MAX_MB, default 10), and how many files one device may send an hour (SEND_PER_HOUR, default 5)
    pub send_max_mb: u64,
    pub send_per_hour: usize,
    /// guests may send files (SEND_ENABLED, default on; the owner can switch it in the console). Still needs the key file.
    pub send_enabled: bool,
    pub empty_limit: usize,
    pub empty_window: u64,
    pub empty_cooldown: u64,
    pub busy_retry: u64,
    pub max_clients: usize,
    pub fair_interval: u64,
    pub reconcile_interval: u64,
    pub console_bind: String,
    pub console_port: u16,
    pub ubus: String,
    pub setup_conf: String,
    pub dhcp_leases: String,
    pub route_file: String,
    pub netdev_file: String,
}

fn parse_tiers(s: &str) -> Vec<(u32, u32)> {
    s.split_whitespace()
        .filter_map(|t| {
            let (p, m) = t.split_once(':')?;
            Some((p.parse().ok()?, m.parse().ok()?))
        })
        .collect()
}

/// An on/off setting: on, 1, yes, true / off, 0, no, false (any case); anything else is `default`.
pub fn parse_switch(v: &str, default: bool) -> bool {
    match v.trim().to_ascii_lowercase().as_str() {
        "on" | "1" | "yes" | "true" => true,
        "off" | "0" | "no" | "false" => false,
        _ => default,
    }
}

/// Settings the owner changes in the console are kept in DATA_DIR/settings.txt (KEY=value lines) and win over /etc/coinslot.conf, so
/// a change needs no restart and survives `piso-setup` writing the conf file again. `piso-setup voucher-choice` removes the line.
pub fn override_get(data_dir: &str, key: &str) -> String {
    let text = fs::read_to_string(format!("{}/settings.txt", data_dir)).unwrap_or_default();
    text.lines()
        .filter_map(|l| l.split_once('='))
        .filter(|(k, _)| k.trim() == key)
        .map(|(_, v)| v.trim().to_string())
        .next_back()
        .unwrap_or_default()
}

/// Keep one console setting (the other lines stay). False when it could not be written.
pub fn override_set(data_dir: &str, key: &str, value: &str) -> bool {
    let path = format!("{}/settings.txt", data_dir);
    let old = fs::read_to_string(&path).unwrap_or_default();
    let mut text: String =
        old.lines().filter(|l| l.split_once('=').map(|(k, _)| k.trim() != key).unwrap_or(false)).map(|l| format!("{}\n", l)).collect();
    text.push_str(&format!("{}={}\n", key, value));
    let tmp = format!("{}.tmp", path);
    let _ = fs::create_dir_all(data_dir);
    fs::write(&tmp, text).and_then(|_| fs::rename(&tmp, &path)).is_ok()
}

/// Forget one console setting (the other lines stay).
pub fn override_clear(data_dir: &str, key: &str) -> bool {
    let path = format!("{}/settings.txt", data_dir);
    let Ok(old) = fs::read_to_string(&path) else { return true };
    let text: String =
        old.lines().filter(|l| l.split_once('=').map(|(k, _)| k.trim() != key).unwrap_or(false)).map(|l| format!("{}\n", l)).collect();
    let tmp = format!("{}.tmp", path);
    fs::write(&tmp, text).and_then(|_| fs::rename(&tmp, &path)).is_ok()
}

fn read_file_settings(path: &str) -> HashMap<String, String> {
    let mut m = HashMap::new();
    let Ok(text) = fs::read_to_string(path) else { return m };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            let v = v.trim().trim_matches('\'').trim_matches('"');
            m.insert(k.trim().to_string(), v.to_string());
        }
    }
    m
}

impl Config {
    pub fn load() -> Config {
        let path = std::env::var("PISOPORTAL_CONF").unwrap_or_else(|_| "/etc/coinslot.conf".to_string());
        Config::from_map(read_file_settings(&path))
    }

    pub fn from_map(file: HashMap<String, String>) -> Config {
        let get = |k: &str, d: &str| -> String {
            std::env::var(k).ok().filter(|v| !v.is_empty()).or_else(|| file.get(k).cloned()).unwrap_or_else(|| d.to_string())
        };
        let num = |k: &str, d: u64| -> u64 { get(k, "").parse().unwrap_or(d) };
        let port = |k: &str, d: u16| -> u16 { get(k, "").parse().unwrap_or(d) };
        let hyper = parse_tiers(&get("HYPER_TIERS", "5:30 10:60 20:120"));
        let fair_kb = match get("FAIR_USE_KB", "").parse::<u64>() {
            Ok(v) => v,
            Err(_) => num("FAIR_USE_GB", 5) * 1024 * 1024,
        };
        Config {
            bind: get("PORTAL_BIND", "0.0.0.0"),
            port: port("PORTAL_PORT", 2080),
            admin_port: port("ADMIN_PORT", 8099),
            event_port: port("EVENT_PORT", 8101),
            event_bind: get("EVENT_BIND", "0.0.0.0"),
            gw_box: get("GW_BOX", "10.0.0.10"),
            gw_key: get("GW_KEY", ""),
            data_dir: get("DATA_DIR", "/etc/coinslot.d"),
            ndsctl: get("NDSCTL", "ndsctl"),
            arp_file: get("ARP_FILE", "/proc/net/arp"),
            first_wait: num("COIN_FIRST_WAIT_SECONDS", 30),
            idle_wait: num("COIN_IDLE_WAIT_SECONDS", 15),
            max_window: num("COIN_MAX_SECONDS", 115),
            poll_ms: num("COIN_POLL_MS", 1000),
            drain_secs: num("COIN_DRAIN_SECONDS", 30),
            hyper_tiers: hyper,
            hyper_prorata_min: num("HYPER_PRORATA_MIN", 6) as u32,
            endurance_tiers: parse_tiers(&get("ENDURANCE_TIERS", "1:15 5:180 10:480 20:1440")),
            endurance_down: num("ENDURANCE_DOWN_KBPS", 5000) as u32,
            endurance_up: num("ENDURANCE_UP_KBPS", 2000) as u32,
            fair_kb,
            fair_down: num("FAIR_THROTTLE_DOWN_KBPS", 2000) as u32,
            fair_up: num("FAIR_THROTTLE_UP_KBPS", 1000) as u32,
            fair_throttle_min: num("FAIR_THROTTLE_MINUTES", 5),
            fair_full_min: num("FAIR_FULL_MINUTES", 2),
            pause_max: num("PAUSE_MAX", 1).min(20) as u32,
            pause_min_pesos: num("PAUSE_MIN_PESOS", 20).min(1000) as u32,
            pause_min_left: num("PAUSE_MIN_LEFT_SECONDS", 120),
            pause_gap: num("PAUSE_GAP_SECONDS", 60),
            pause_kbps: (num("PAUSE_KBPS", 1) as u32).clamp(1, 1000),
            pause_quota_kb: num("PAUSE_QUOTA_KB", 2048).min(1_000_000),
            voucher_choice: parse_switch(&get("VOUCHER_CHOICE", ""), true),
            game_minutes: (num("GAME_MINUTES", 10) as u32).min(crate::game::MAX_MINUTES),
            game_seconds: get("GAME_LIMIT_SECONDS", "").parse().ok(),
            send_host: get("SEND_HOST", "10.0.0.95"),
            send_port: port("SEND_PORT", 80),
            send_max_mb: num("SEND_MAX_MB", 10).clamp(1, 50),
            send_per_hour: num("SEND_PER_HOUR", 5).clamp(1, 100) as usize,
            send_enabled: parse_switch(&get("SEND_ENABLED", ""), true),
            empty_limit: num("EMPTY_LIMIT", 2) as usize,
            empty_window: num("EMPTY_WINDOW", 300),
            empty_cooldown: num("EMPTY_COOLDOWN", 120),
            busy_retry: num("BUSY_RETRY", 15),
            max_clients: num("MAX_CLIENTS", 24) as usize,
            fair_interval: num("FAIR_INTERVAL_SECONDS", 60),
            reconcile_interval: num("RECONCILE_INTERVAL_SECONDS", 600),
            console_bind: get("CONSOLE_BIND", "0.0.0.0"),
            console_port: port("CONSOLE_PORT", 8081),
            ubus: get("UBUS", "ubus"),
            setup_conf: get("PISO_SETUP_CONF", "/etc/piso-setup.conf"),
            dhcp_leases: get("DHCP_LEASES", "/tmp/dhcp.leases"),
            route_file: get("ROUTE_FILE", "/proc/net/route"),
            netdev_file: get("NETDEV_FILE", "/proc/net/dev"),
        }
    }

    pub fn roll_path(&self) -> String {
        format!("{}/vouchers.txt", self.data_dir)
    }
    pub fn revenue_path(&self) -> String {
        format!("{}/revenue.csv", self.data_dir)
    }
    pub fn open_dir(&self) -> String {
        format!("{}/open", self.data_dir)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn on_and_off_settings() {
        assert!(parse_switch("", true) && !parse_switch("", false), "not set: the default");
        for on in ["on", "ON", "1", "yes", "True", " on "] {
            assert!(parse_switch(on, false), "{}", on);
        }
        for off in ["off", "OFF", "0", "no", "False"] {
            assert!(!parse_switch(off, true), "{}", off);
        }
        assert!(parse_switch("maybe", true) && !parse_switch("maybe", false), "unreadable: the default");
    }

    #[test]
    fn console_settings_are_kept_in_the_data_folder() {
        let d = std::env::temp_dir().join(format!("pisoportal-config-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        let dir = d.to_string_lossy().into_owned();
        assert_eq!(override_get(&dir, "VOUCHER_CHOICE"), "", "nothing yet");
        assert!(override_set(&dir, "VOUCHER_CHOICE", "off"));
        assert_eq!(override_get(&dir, "VOUCHER_CHOICE"), "off");
        assert!(override_set(&dir, "OTHER", "x") && override_set(&dir, "VOUCHER_CHOICE", "on"));
        assert_eq!((override_get(&dir, "VOUCHER_CHOICE").as_str(), override_get(&dir, "OTHER").as_str()), ("on", "x"));
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn a_pause_is_a_perk_for_paying_20_pesos_once() {
        let c = Config::from_map(HashMap::new());
        assert_eq!((c.pause_max, c.pause_min_pesos), (1, 20), "defaults: one pause, for a session that paid 20 pesos or more");
        let mut m = HashMap::new();
        m.insert("PAUSE_MAX".to_string(), "3".to_string());
        m.insert("PAUSE_MIN_PESOS".to_string(), "0".to_string());
        let c = Config::from_map(m);
        assert_eq!((c.pause_max, c.pause_min_pesos), (3, 0), "the owner can allow more pauses and any amount");
    }

    #[test]
    fn a_paused_device_crawls_and_has_a_small_data_allowance() {
        let c = Config::from_map(HashMap::new());
        assert_eq!((c.pause_kbps, c.pause_quota_kb), (1, 2048), "defaults: 1 kbit/s and 2 MB a pause");
        let mut m = HashMap::new();
        m.insert("PAUSE_KBPS".to_string(), "0".to_string());
        m.insert("PAUSE_QUOTA_KB".to_string(), "0".to_string());
        let c = Config::from_map(m);
        assert_eq!((c.pause_kbps, c.pause_quota_kb), (1, 0), "never a 0 kbit/s (that would mean no limit); a 0 quota means no data limit");
    }

    #[test]
    fn voucher_choice_is_on_unless_switched_off() {
        let mut m = HashMap::new();
        assert!(Config::from_map(m.clone()).voucher_choice, "default: on");
        m.insert("VOUCHER_CHOICE".to_string(), "off".to_string());
        assert!(!Config::from_map(m.clone()).voucher_choice);
        m.insert("VOUCHER_CHOICE".to_string(), "on".to_string());
        assert!(Config::from_map(m).voucher_choice);
    }
}
