//! The spinning wheel: customers bet minutes of their paid time; a win multiplies the bet, a loss costs it. The odds are one fixed table,
//! the same for everyone and shown on the page; the result is drawn here, before the page animates, and never depends on who plays or
//! on how they did before. Nothing in this file changes a session yet (that is `spin` below, task 2) and nothing is served yet.
#![allow(dead_code)]

/// One slot of the wheel: `mult` 0 loses the bet; 2 returns it doubled (net gain = the bet); and so on.
#[derive(Clone, Debug, PartialEq)]
pub struct Segment {
    pub mult: u32,
    pub weight: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Table {
    pub segs: Vec<Segment>,
}

/// Average return as parts per thousand of the bet (1000 = the customer gets back exactly what they bet).
pub const MAX_RTP_PERMILLE: u32 = 1000;
/// The console warns below this.
pub const LOW_RTP_PERMILLE: u32 = 800;
/// The biggest multiplier a table may have.
pub const MAX_MULT: u32 = 10;

impl Table {
    /// 20 slots: 13 lose, 5 x2, 1 x3, 1 x5. Win chance 35%, average return 90%.
    pub fn default_table() -> Table {
        Table { segs: [(0, 13), (2, 5), (3, 1), (5, 1)].iter().map(|&(mult, weight)| Segment { mult, weight }).collect() }
    }

    pub fn total_weight(&self) -> u32 {
        self.segs.iter().map(|s| s.weight).sum()
    }

    /// Average return in parts per thousand, rounded down.
    pub fn rtp_permille(&self) -> u32 {
        let total = self.total_weight() as u64;
        if total == 0 {
            return 0;
        }
        (self.segs.iter().map(|s| s.weight as u64 * s.mult as u64).sum::<u64>() * 1000 / total) as u32
    }

    /// Chance of a win in parts per thousand.
    pub fn win_permille(&self) -> u32 {
        let total = self.total_weight() as u64;
        if total == 0 {
            return 0;
        }
        (self.segs.iter().filter(|s| s.mult > 0).map(|s| s.weight as u64).sum::<u64>() * 1000 / total) as u32
    }

    /// The router refuses a table that favours the customer, an empty one, or one with a multiplier it does not allow.
    pub fn validate(&self) -> Result<(), String> {
        if self.segs.is_empty() || self.total_weight() == 0 {
            return Err("the wheel needs at least one slot".into());
        }
        if self.segs.iter().any(|s| s.mult == 1) {
            return Err("a multiplier of 1 would give the bet back: use 0 (lose) or 2 and more".into());
        }
        if self.segs.iter().any(|s| s.mult > MAX_MULT) {
            return Err(format!("a multiplier may be at most {}", MAX_MULT));
        }
        if self.total_weight() > 1000 {
            return Err("the wheel may have at most 1000 slots".into());
        }
        if self.rtp_permille() > MAX_RTP_PERMILLE {
            return Err(format!(
                "this wheel returns {}.{}% of the bets on average, more than 100%: customers would win over time",
                self.rtp_permille() / 10,
                self.rtp_permille() % 10
            ));
        }
        Ok(())
    }

    /// "0x13 2x5 3x1 5x1": multiplier x weight, spaces between.
    pub fn parse(text: &str) -> Result<Table, String> {
        let mut segs = Vec::new();
        for part in text.split_whitespace() {
            let (m, w) = part.split_once('x').ok_or_else(|| format!("\"{}\" is not multiplier x slots (for example 2x5)", part))?;
            let (mult, weight) = (
                m.parse::<u32>().map_err(|_| format!("\"{}\" is not a number", m))?,
                w.parse::<u32>().map_err(|_| format!("\"{}\" is not a number", w))?,
            );
            segs.push(Segment { mult, weight });
        }
        if segs.is_empty() {
            return Err("the wheel needs at least one slot".into());
        }
        Ok(Table { segs })
    }

    pub fn to_text(&self) -> String {
        self.segs.iter().map(|s| format!("{}x{}", s.mult, s.weight)).collect::<Vec<_>>().join(" ")
    }

    /// The slot a random number lands on.
    pub fn draw(&self, r: u64) -> usize {
        let total = self.total_weight() as u64;
        if total == 0 {
            return 0;
        }
        let mut at = r % total;
        for (i, s) in self.segs.iter().enumerate() {
            if at < s.weight as u64 {
                return i;
            }
            at -= s.weight as u64;
        }
        0
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Limits {
    /// most minutes one spin may bet
    pub max_bet: u32,
    /// minutes that must be left after a loss
    pub floor: u32,
    pub spins_per_day: u32,
    pub wait_secs: u64,
    /// most net minutes a device may win from the wheel in a day
    pub win_cap_per_day: u32,
}

impl Limits {
    pub fn default_limits() -> Limits {
        Limits { max_bet: 10, floor: 5, spins_per_day: 10, wait_secs: 10, win_cap_per_day: 60 }
    }
}

#[derive(Debug, PartialEq)]
pub enum BetError {
    Zero,
    TooBig,
    MoreThanHalf,
    BelowFloor,
}

/// May `bet` minutes be bet when `left_min` minutes are left?
pub fn check_bet(limits: &Limits, left_min: u64, bet: u32) -> Result<(), BetError> {
    if bet == 0 {
        return Err(BetError::Zero);
    }
    if bet > limits.max_bet {
        return Err(BetError::TooBig);
    }
    if left_min < limits.floor as u64 + bet as u64 {
        return Err(BetError::BelowFloor);
    }
    if bet as u64 * 2 > left_min {
        return Err(BetError::MoreThanHalf);
    }
    Ok(())
}

/// Net change in minutes for a result: a loss is -bet, a win of multiplier m is (m - 1) x bet.
pub fn delta_minutes(mult: u32, bet: u32) -> i64 {
    if mult == 0 {
        -(bet as i64)
    } else {
        (mult as i64 - 1) * bet as i64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rt_default_table_is_20_slots_90_percent_return_35_percent_wins() {
        let t = Table::default_table();
        assert_eq!(t.total_weight(), 20);
        assert_eq!(t.rtp_permille(), 900);
        assert_eq!(t.win_permille(), 350);
        assert!(t.validate().is_ok());
    }

    #[test]
    fn rt_a_table_that_favours_the_customer_is_refused() {
        // 1 lose, 1 x3 of 2: average return 150%
        let t = Table::parse("0x1 3x1").unwrap();
        assert_eq!(t.rtp_permille(), 1500);
        assert!(t.validate().is_err());
        // exactly 100% is allowed (fair): 1 lose, 1 x2
        let fair = Table::parse("0x1 2x1").unwrap();
        assert_eq!(fair.rtp_permille(), 1000);
        assert!(fair.validate().is_ok());
    }

    #[test]
    fn rt_empty_zero_weight_and_too_big_multipliers_are_refused() {
        assert!(Table { segs: vec![] }.validate().is_err());
        assert!(Table::parse("0x0").is_err() || Table::parse("0x0").unwrap().validate().is_err());
        assert!(Table::parse("0x50 11x1").unwrap().validate().is_err());
        assert!(Table::parse("hello").is_err());
        assert!(Table::parse("2x").is_err());
        assert!(Table::parse("").map(|t| t.validate().is_err()).unwrap_or(true));
    }

    #[test]
    fn rt_table_text_round_trips() {
        let t = Table::default_table();
        assert_eq!(t.to_text(), "0x13 2x5 3x1 5x1");
        assert_eq!(Table::parse(&t.to_text()).unwrap(), t);
    }

    #[test]
    fn rt_the_draw_follows_the_table_exactly_over_one_full_cycle() {
        let t = Table::default_table();
        let mut counts = std::collections::HashMap::new();
        for r in 0..20u64 {
            *counts.entry(t.segs[t.draw(r)].mult).or_insert(0u32) += 1;
        }
        assert_eq!(counts.get(&0), Some(&13));
        assert_eq!(counts.get(&2), Some(&5));
        assert_eq!(counts.get(&3), Some(&1));
        assert_eq!(counts.get(&5), Some(&1));
    }

    #[test]
    fn rt_the_draw_over_a_large_run_stays_near_the_published_return() {
        let t = Table::default_table();
        // a simple deterministic spread (a multiplicative hash); the table's return is 90%
        let n = 200_000u64;
        let mut x = 0x9E37_79B9_7F4A_7C15u64;
        let mut ret: u64 = 0;
        for _ in 0..n {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            ret += t.segs[t.draw(x)].mult as u64;
        }
        let rtp = ret as f64 / n as f64;
        assert!((rtp - 0.90).abs() < 0.02, "return {}", rtp);
    }

    #[test]
    fn rt_default_limits_are_the_owners_numbers() {
        let l = Limits::default_limits();
        assert_eq!((l.max_bet, l.floor, l.spins_per_day, l.wait_secs, l.win_cap_per_day), (10, 5, 10, 10, 60));
    }

    #[test]
    fn rt_bet_rules() {
        let l = Limits::default_limits();
        assert_eq!(check_bet(&l, 60, 0), Err(BetError::Zero));
        assert_eq!(check_bet(&l, 60, 11), Err(BetError::TooBig));
        assert_eq!(check_bet(&l, 60, 10), Ok(()));
        // never more than half of the time left
        assert_eq!(check_bet(&l, 15, 8), Err(BetError::MoreThanHalf));
        assert_eq!(check_bet(&l, 16, 8), Ok(()));
        // a loss never leaves less than the floor (5): 9 left, bet 4 leaves 5 (ok); bet 5 would leave 4
        assert_eq!(check_bet(&l, 9, 4), Ok(()));
        assert_eq!(check_bet(&l, 9, 5), Err(BetError::BelowFloor));
        // below the floor even for a tiny bet: 5 left, bet 1 leaves 4
        assert_eq!(check_bet(&l, 5, 1), Err(BetError::BelowFloor));
        assert_eq!(check_bet(&l, 6, 1), Ok(()));
        assert_eq!(check_bet(&l, 0, 1), Err(BetError::BelowFloor));
    }

    #[test]
    fn rt_net_change_of_a_result() {
        assert_eq!(delta_minutes(0, 5), -5);
        assert_eq!(delta_minutes(2, 5), 5);
        assert_eq!(delta_minutes(3, 5), 10);
        assert_eq!(delta_minutes(5, 10), 40);
    }
}
