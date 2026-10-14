//! Plans and prices. Tiers are (pesos, minutes); the best combination of tiers is used for any amount
//! (unbounded knapsack), e.g. Endurance 17 pesos = 10 + 5 + 1 + 1 = 8 h + 3 h + 30 min.
//!
//! The prices are a `Pricing` value: the settings file gives the starting point, the owner changes them live in the console
//! (kept in DATA_DIR/settings.txt) and every payment keeps the `Pricing` it started with.
use crate::config::{override_clear, override_get, override_set, Config};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Plan {
    Hyper,
    Endurance,
}

impl Plan {
    pub fn parse(s: &str) -> Option<Plan> {
        match s {
            "hyper" => Some(Plan::Hyper),
            "endurance" => Some(Plan::Endurance),
            _ => None,
        }
    }
    pub fn as_str(&self) -> &'static str {
        match self {
            Plan::Hyper => "hyper",
            Plan::Endurance => "endurance",
        }
    }
}

/// The limits of what the console accepts (a typo must not sell a year for a peso or a peso for nothing).
pub const MAX_TIERS: usize = 8;
pub const MAX_TIER_PESOS: u32 = 1000;
pub const MAX_TIER_MINUTES: u32 = 7 * 24 * 60;
pub const MAX_PRORATA_MIN: u32 = 120;
pub const MAX_KBPS: u32 = 1_000_000;

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Pricing {
    /// HyperSpeed price list lines (the pro-rata minutes per peso are separate: `hyper_prorata_min`)
    pub hyper_tiers: Vec<(u32, u32)>,
    /// minutes one extra peso adds to a HyperSpeed payment (0: none)
    pub hyper_prorata_min: u32,
    pub endurance_tiers: Vec<(u32, u32)>,
    /// Endurance speed caps in kbit/s (0: no cap)
    pub endurance_down: u32,
    pub endurance_up: u32,
}

/// "5:30 10:60" -> [(5, 30), (10, 60)]. Anything that is not a list of pesos:minutes pairs is an error that says what is wrong.
pub fn parse_tier_list(s: &str) -> Result<Vec<(u32, u32)>, String> {
    let mut out: Vec<(u32, u32)> = Vec::new();
    for t in s.split_whitespace() {
        let Some((p, m)) = t.split_once(':') else { return Err(format!("\"{}\" is not pesos:minutes", t)) };
        let (Ok(p), Ok(m)) = (p.parse::<u32>(), m.parse::<u32>()) else { return Err(format!("\"{}\" is not pesos:minutes", t)) };
        if p == 0 || p > MAX_TIER_PESOS {
            return Err(format!("pesos must be 1 to {}", MAX_TIER_PESOS));
        }
        if m == 0 || m > MAX_TIER_MINUTES {
            return Err(format!("minutes must be 1 to {}", MAX_TIER_MINUTES));
        }
        if out.iter().any(|(q, _)| *q == p) {
            return Err(format!("\u{20b1}{} is listed twice", p));
        }
        out.push((p, m));
    }
    if out.len() > MAX_TIERS {
        return Err(format!("at most {} lines", MAX_TIERS));
    }
    out.sort();
    Ok(out)
}

pub fn format_tier_list(t: &[(u32, u32)]) -> String {
    t.iter().map(|(p, m)| format!("{}:{}", p, m)).collect::<Vec<_>>().join(" ")
}

impl Pricing {
    /// The prices of the settings file (or the built-in ones).
    pub fn from_config(cfg: &Config) -> Pricing {
        Pricing {
            hyper_tiers: cfg.hyper_tiers.clone(),
            hyper_prorata_min: cfg.hyper_prorata_min,
            endurance_tiers: cfg.endurance_tiers.clone(),
            endurance_down: cfg.endurance_down,
            endurance_up: cfg.endurance_up,
        }
    }

    /// The settings file, then what the owner changed in the console (an unreadable console value is ignored).
    pub fn load(cfg: &Config) -> Pricing {
        let mut p = Pricing::from_config(cfg);
        let get = |k: &str| override_get(&cfg.data_dir, k);
        if let Ok(t) = parse_tier_list(&get("HYPER_TIERS")) {
            if !t.is_empty() {
                p.hyper_tiers = t;
            }
        }
        if let Ok(t) = parse_tier_list(&get("ENDURANCE_TIERS")) {
            if !t.is_empty() {
                p.endurance_tiers = t;
            }
        }
        if let Ok(v) = get("HYPER_PRORATA_MIN").parse::<u32>() {
            p.hyper_prorata_min = v.min(MAX_PRORATA_MIN);
        }
        if let Ok(v) = get("ENDURANCE_DOWN_KBPS").parse::<u32>() {
            p.endurance_down = v.min(MAX_KBPS);
        }
        if let Ok(v) = get("ENDURANCE_UP_KBPS").parse::<u32>() {
            p.endurance_up = v.min(MAX_KBPS);
        }
        p
    }

    /// Every price list line of a plan, including the pro-rata peso of HyperSpeed.
    pub fn table(&self, plan: Plan) -> Vec<(u32, u32)> {
        match plan {
            Plan::Hyper => {
                let mut t = self.hyper_tiers.clone();
                if self.hyper_prorata_min > 0 && !t.iter().any(|(p, _)| *p == 1) {
                    t.push((1, self.hyper_prorata_min));
                }
                t
            }
            Plan::Endurance => self.endurance_tiers.clone(),
        }
    }

    /// Is this a price list a customer can use? Every coin has to count (a peso that buys nothing is a lost peso), and some line must exist.
    pub fn check(&self) -> Result<(), String> {
        for (plan, name) in [(Plan::Hyper, "HyperSpeed"), (Plan::Endurance, "Endurance")] {
            let t = self.table(plan);
            // (the customer page lists these lines: a plan with only pro-rata pesos would show an empty price list)
            if if plan == Plan::Hyper { self.hyper_tiers.is_empty() } else { t.is_empty() } {
                return Err(format!("{} needs at least one price line", name));
            }
            if !t.iter().any(|(p, _)| *p == 1) {
                return Err(format!("{} needs a 1-peso line (or minutes per extra peso), so every coin counts", name));
            }
        }
        if self.endurance_down > MAX_KBPS || self.endurance_up > MAX_KBPS || self.hyper_prorata_min > MAX_PRORATA_MIN {
            return Err("a number is out of range".into());
        }
        Ok(())
    }

    /// Keep these prices in DATA_DIR/settings.txt. False when they could not be written.
    pub fn save(&self, data_dir: &str) -> bool {
        override_set(data_dir, "HYPER_TIERS", &format_tier_list(&self.hyper_tiers))
            && override_set(data_dir, "HYPER_PRORATA_MIN", &self.hyper_prorata_min.to_string())
            && override_set(data_dir, "ENDURANCE_TIERS", &format_tier_list(&self.endurance_tiers))
            && override_set(data_dir, "ENDURANCE_DOWN_KBPS", &self.endurance_down.to_string())
            && override_set(data_dir, "ENDURANCE_UP_KBPS", &self.endurance_up.to_string())
    }

    /// Forget what the owner changed: the settings file's prices apply again.
    pub fn forget_changes(data_dir: &str) {
        for k in ["HYPER_TIERS", "HYPER_PRORATA_MIN", "ENDURANCE_TIERS", "ENDURANCE_DOWN_KBPS", "ENDURANCE_UP_KBPS"] {
            override_clear(data_dir, k);
        }
    }
}

/// The most minutes obtainable for that many pesos.
pub fn minutes_for(pr: &Pricing, plan: Plan, pesos: u32) -> u32 {
    let t = pr.table(plan);
    // the table grows with the amount: never for an amount no coin window can hold (see core::MAX_PULSES)
    let n = pesos.min(crate::core::MAX_PULSES) as usize;
    let mut best = vec![0u32; n + 1];
    for x in 1..=n {
        for &(c, m) in &t {
            let c = c as usize;
            if c > 0 && c <= x && best[x - c].saturating_add(m) > best[x] {
                best[x] = best[x - c].saturating_add(m);
            }
        }
    }
    best[n]
}

/// (down, up) in kbit/s for a plan; 0 = no cap.
pub fn rates(pr: &Pricing, plan: Plan) -> (u32, u32) {
    match plan {
        Plan::Hyper => (0, 0),
        Plan::Endurance => (pr.endurance_down, pr.endurance_up),
    }
}

pub fn fmt_min(m: u32) -> String {
    if m < 60 {
        return format!("{} min", m);
    }
    let (h, r) = (m / 60, m % 60);
    let u = if h > 1 { "hrs" } else { "hr" };
    if r > 0 {
        format!("{} {} {} min", h, u, r)
    } else {
        format!("{} {}", h, u)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn default_pricing() -> Pricing {
        Pricing::from_config(&Config::from_map(HashMap::new()))
    }

    #[test]
    fn best_combination_of_tiers() {
        let pr = default_pricing();
        assert_eq!(minutes_for(&pr, Plan::Endurance, 17), 690); // 10 + 5 + 1 + 1 = 480 + 180 + 15 + 15
        assert_eq!(minutes_for(&pr, Plan::Endurance, 2), 30);
        assert_eq!(minutes_for(&pr, Plan::Hyper, 5), 30);
        assert_eq!(minutes_for(&pr, Plan::Hyper, 3), 18); // pro rata 6 min a peso
        assert_eq!(minutes_for(&pr, Plan::Hyper, 11), 66);
        assert_eq!(minutes_for(&pr, Plan::Hyper, 0), 0);
        assert_eq!(fmt_min(690), "11 hrs 30 min");
        assert_eq!(fmt_min(60), "1 hr");
        assert_eq!(fmt_min(30), "30 min");
        assert!(pr.check().is_ok(), "the built-in prices are a usable list");
    }

    #[test]
    fn tier_lists_are_read_strictly() {
        assert_eq!(parse_tier_list("10:60 5:30"), Ok(vec![(5, 30), (10, 60)]), "sorted by pesos");
        assert_eq!(parse_tier_list(""), Ok(vec![]));
        for bad in ["5", "5:", ":30", "a:b", "5:30:1", "0:30", "5:0", "5:30 5:40", "2000:30", "5:99999", "-1:30"] {
            assert!(parse_tier_list(bad).is_err(), "{} must be refused", bad);
        }
        assert!(parse_tier_list("1:1 2:1 3:1 4:1 5:1 6:1 7:1 8:1 9:1").is_err(), "at most 8 lines");
        assert_eq!(format_tier_list(&[(5, 30), (10, 60)]), "5:30 10:60");
    }

    #[test]
    fn a_price_list_must_count_every_coin() {
        let mut pr = default_pricing();
        pr.endurance_tiers = vec![(5, 180), (10, 480)];
        assert!(pr.check().unwrap_err().contains("1-peso"), "no 1-peso line: a lone peso would buy nothing");
        pr.endurance_tiers = vec![(1, 15)];
        assert!(pr.check().is_ok());
        pr.hyper_tiers = vec![(5, 30)];
        pr.hyper_prorata_min = 0;
        assert!(pr.check().is_err(), "HyperSpeed without a 1-peso line or pro-rata");
        pr.hyper_prorata_min = 6;
        assert!(pr.check().is_ok(), "the pro-rata minutes are the 1-peso line");
        assert_eq!(minutes_for(&pr, Plan::Hyper, 7), 30 + 12);
        pr.endurance_tiers = vec![];
        assert!(pr.check().is_err(), "a plan with no lines");
    }

    #[test]
    fn changed_prices_are_kept_and_forgotten() {
        let d = std::env::temp_dir().join(format!("pisoportal-pricing-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        let dir = d.to_string_lossy().into_owned();
        let mut m = HashMap::new();
        m.insert("DATA_DIR".to_string(), dir.clone());
        let cfg = Config::from_map(m);
        assert_eq!(Pricing::load(&cfg), Pricing::from_config(&cfg), "nothing changed yet: the settings file's prices");
        let mut pr = Pricing::from_config(&cfg);
        pr.endurance_tiers = vec![(1, 10), (20, 1000)];
        pr.hyper_tiers = vec![(10, 60)];
        pr.hyper_prorata_min = 5;
        pr.endurance_down = 8000;
        pr.endurance_up = 0;
        assert!(pr.save(&dir));
        assert_eq!(Pricing::load(&cfg), pr, "what the owner saved wins");
        assert!(override_set(&dir, "HYPER_TIERS", "garbage"), "an unreadable value...");
        let loaded = Pricing::load(&cfg);
        assert_eq!(loaded.hyper_tiers, Pricing::from_config(&cfg).hyper_tiers, "...is ignored");
        assert_eq!(loaded.endurance_tiers, vec![(1, 10), (20, 1000)]);
        Pricing::forget_changes(&dir);
        assert_eq!(Pricing::load(&cfg), Pricing::from_config(&cfg), "forgotten: back to the settings file");
        let _ = std::fs::remove_dir_all(&d);
    }
}
