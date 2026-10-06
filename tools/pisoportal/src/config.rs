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
    pub gateway_name: String,
    pub first_wait: u64,
    pub idle_wait: u64,
    pub max_window: u64,
    pub poll_ms: u64,
    pub drain_secs: u64,
    pub hyper_tiers: Vec<(u32, u32)>,
    pub endurance_tiers: Vec<(u32, u32)>,
    pub endurance_down: u32,
    pub endurance_up: u32,
    pub fair_kb: u64,
    pub fair_down: u32,
    pub fair_up: u32,
    pub fair_throttle_min: u64,
    pub fair_full_min: u64,
    pub empty_limit: usize,
    pub empty_window: u64,
    pub empty_cooldown: u64,
    pub busy_retry: u64,
    pub max_clients: usize,
    pub fair_interval: u64,
}

fn parse_tiers(s: &str) -> Vec<(u32, u32)> {
    s.split_whitespace()
        .filter_map(|t| {
            let (p, m) = t.split_once(':')?;
            Some((p.parse().ok()?, m.parse().ok()?))
        })
        .collect()
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
        let mut hyper = parse_tiers(&get("HYPER_TIERS", "5:30 10:60 20:120"));
        let prorata = num("HYPER_PRORATA_MIN", 6) as u32;
        if prorata > 0 {
            hyper.push((1, prorata));
        }
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
            gateway_name: get("GATEWAY_NAME", "PisoWiFi"),
            first_wait: num("COIN_FIRST_WAIT_SECONDS", 30),
            idle_wait: num("COIN_IDLE_WAIT_SECONDS", 15),
            max_window: num("COIN_MAX_SECONDS", 115),
            poll_ms: num("COIN_POLL_MS", 1000),
            drain_secs: num("COIN_DRAIN_SECONDS", 30),
            hyper_tiers: hyper,
            endurance_tiers: parse_tiers(&get("ENDURANCE_TIERS", "1:15 5:180 10:480 20:1440")),
            endurance_down: num("ENDURANCE_DOWN_KBPS", 5000) as u32,
            endurance_up: num("ENDURANCE_UP_KBPS", 2000) as u32,
            fair_kb,
            fair_down: num("FAIR_THROTTLE_DOWN_KBPS", 2000) as u32,
            fair_up: num("FAIR_THROTTLE_UP_KBPS", 1000) as u32,
            fair_throttle_min: num("FAIR_THROTTLE_MINUTES", 5),
            fair_full_min: num("FAIR_FULL_MINUTES", 2),
            empty_limit: num("EMPTY_LIMIT", 2) as usize,
            empty_window: num("EMPTY_WINDOW", 300),
            empty_cooldown: num("EMPTY_COOLDOWN", 120),
            busy_retry: num("BUSY_RETRY", 15),
            max_clients: num("MAX_CLIENTS", 24) as usize,
            fair_interval: num("FAIR_INTERVAL_SECONDS", 60),
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
