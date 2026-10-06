//! Plans and prices. Tiers are (pesos, minutes); the best combination of tiers is used for any amount
//! (unbounded knapsack), e.g. Endurance 17 pesos = 10 + 5 + 1 + 1 = 8 h + 3 h + 30 min.
use crate::config::Config;

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

pub fn tiers(cfg: &Config, plan: Plan) -> &[(u32, u32)] {
    match plan {
        Plan::Hyper => &cfg.hyper_tiers,
        Plan::Endurance => &cfg.endurance_tiers,
    }
}

/// The most minutes obtainable for that many pesos.
pub fn minutes_for(cfg: &Config, plan: Plan, pesos: u32) -> u32 {
    let t = tiers(cfg, plan);
    // the table grows with the amount: never for an amount no coin window can hold (see core::MAX_PULSES)
    let n = pesos.min(crate::core::MAX_PULSES) as usize;
    let mut best = vec![0u32; n + 1];
    for x in 1..=n {
        for &(c, m) in t {
            let c = c as usize;
            if c > 0 && c <= x && best[x - c].saturating_add(m) > best[x] {
                best[x] = best[x - c].saturating_add(m);
            }
        }
    }
    best[n]
}

/// (down, up) in kbit/s for a plan; 0 = no cap.
pub fn rates(cfg: &Config, plan: Plan) -> (u32, u32) {
    match plan {
        Plan::Hyper => (0, 0),
        Plan::Endurance => (cfg.endurance_down, cfg.endurance_up),
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

    #[test]
    fn best_combination_of_tiers() {
        let cfg = Config::from_map(HashMap::new());
        assert_eq!(minutes_for(&cfg, Plan::Endurance, 17), 690); // 10 + 5 + 1 + 1 = 480 + 180 + 15 + 15
        assert_eq!(minutes_for(&cfg, Plan::Endurance, 2), 30);
        assert_eq!(minutes_for(&cfg, Plan::Hyper, 5), 30);
        assert_eq!(minutes_for(&cfg, Plan::Hyper, 3), 18); // pro rata 6 min a peso
        assert_eq!(minutes_for(&cfg, Plan::Hyper, 11), 66);
        assert_eq!(minutes_for(&cfg, Plan::Hyper, 0), 0);
        assert_eq!(fmt_min(690), "11 hrs 30 min");
        assert_eq!(fmt_min(60), "1 hr");
        assert_eq!(fmt_min(30), "30 min");
    }
}
