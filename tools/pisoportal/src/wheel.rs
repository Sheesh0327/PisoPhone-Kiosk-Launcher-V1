//! The spinning wheel: customers bet minutes of their paid time; a win multiplies the bet, a loss costs it. The odds are one fixed table,
//! the same for everyone and shown on the page; the result is drawn here, before the page animates, and never depends on who plays or
//! on how they did before. Nothing in this file changes a session yet (that is `spin` below, task 2) and nothing is served yet.
#![allow(dead_code)]

/// One row of the table: `weight` slots that pay `mult` (0 loses the bet; 2 returns it doubled, net gain = the bet; and so on). A `thin` slot is
/// half as likely as a normal one and is drawn half as wide on the wheel (the slice's width is its chance).
#[derive(Clone, Debug, PartialEq)]
pub struct Segment {
    pub mult: u32,
    pub weight: u32,
    pub thin: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Table {
    pub segs: Vec<Segment>,
}

/// Average return as parts per thousand of the bet (1000 = the customer gets back exactly what they bet).
pub const MAX_RTP_PERMILLE: u32 = 1000;
/// The console warns below this.
pub const LOW_RTP_PERMILLE: u32 = 800;
/// The most slots a table the owner saves may have: the page draws one slice per slot, and more cannot be read on a phone.
pub const MAX_SLOTS: u32 = 24;
/// The biggest multiplier a table may have.
pub const MAX_MULT: u32 = 10;

impl Table {
    /// 20 slots: 12 lose, 6 x2, 1 x3 and a thin x5 (the jackpot, half a slot: 1 chance in 39). Win chance 38.5%, average return 89.7%.
    pub fn default_table() -> Table {
        Table {
            segs: [(0, 12, false), (2, 6, false), (3, 1, false), (5, 1, true)]
                .iter()
                .map(|&(mult, weight, thin)| Segment { mult, weight, thin })
                .collect(),
        }
    }

    /// The number of slots (one slice each on the wheel).
    pub fn total_weight(&self) -> u32 {
        self.segs.iter().map(|s| s.weight).sum()
    }

    /// The size of a normal slot in units of chance: 2 when the table has thin slots (a thin slot is 1), otherwise 1 (so a table without
    /// thin slots draws exactly as it always did).
    pub fn unit(&self) -> u32 {
        if self.segs.iter().any(|s| s.thin) {
            2
        } else {
            1
        }
    }

    /// Units of chance of one slot of this row.
    pub fn slot_size(&self, s: &Segment) -> u32 {
        if s.thin {
            1
        } else {
            self.unit()
        }
    }

    /// Units of chance of the whole row.
    pub fn row_size(&self, s: &Segment) -> u32 {
        s.weight * self.slot_size(s)
    }

    /// Units of chance in all: a row's chance is its size over this.
    pub fn total_size(&self) -> u32 {
        self.segs.iter().map(|s| self.row_size(s)).sum()
    }

    /// Average return in parts per thousand, rounded to the nearest (for showing; the 100% rule below compares exactly).
    pub fn rtp_permille(&self) -> u32 {
        let total = self.total_size() as u64;
        if total == 0 {
            return 0;
        }
        ((self.segs.iter().map(|s| self.row_size(s) as u64 * s.mult as u64).sum::<u64>() * 1000 + total / 2) / total) as u32
    }

    /// Chance of a win in parts per thousand, rounded to the nearest.
    pub fn win_permille(&self) -> u32 {
        let total = self.total_size() as u64;
        if total == 0 {
            return 0;
        }
        ((self.segs.iter().filter(|s| s.mult > 0).map(|s| self.row_size(s) as u64).sum::<u64>() * 1000 + total / 2) / total) as u32
    }

    /// The router refuses a table that favours the customer, an empty one, or one with a multiplier it does not allow.
    pub fn validate(&self) -> Result<(), String> {
        if self.total_weight() > MAX_SLOTS {
            return Err(format!("the wheel may have at most {} slots (one slice each, so the wheel stays readable)", MAX_SLOTS));
        }
        self.validate_saved()
    }

    /// The rules a table saved by an earlier release must still meet: the cap on slots was 1000 then (the page draws 24 proportional slices).
    pub fn validate_saved(&self) -> Result<(), String> {
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
        // exact: units of return against units of chance (the rounded permille could hide 100.1%)
        let back: u64 = self.segs.iter().map(|s| self.row_size(s) as u64 * s.mult as u64).sum();
        if back * 1000 > self.total_size() as u64 * MAX_RTP_PERMILLE as u64 {
            return Err(format!(
                "this wheel returns {}.{}% of the bets on average, more than 100%: customers would win over time",
                self.rtp_permille() / 10,
                self.rtp_permille() % 10
            ));
        }
        Ok(())
    }

    /// "0x12 2x6 3x1 5x1t": multiplier x slots, spaces between; a `t` after the slots makes them thin.
    pub fn parse(text: &str) -> Result<Table, String> {
        let mut segs = Vec::new();
        for part in text.split_whitespace() {
            let (m, w) = part.split_once('x').ok_or_else(|| format!("\"{}\" is not multiplier x slots (for example 2x5)", part))?;
            let (w, thin) = match w.strip_suffix('t') {
                Some(n) => (n, true),
                None => (w, false),
            };
            let (mult, weight) = (
                m.parse::<u32>().map_err(|_| format!("\"{}\" is not a number", m))?,
                w.parse::<u32>().map_err(|_| format!("\"{}\" is not a number", w))?,
            );
            segs.push(Segment { mult, weight, thin });
        }
        if segs.is_empty() {
            return Err("the wheel needs at least one slot".into());
        }
        Ok(Table { segs })
    }

    pub fn to_text(&self) -> String {
        self.segs.iter().map(|s| format!("{}x{}{}", s.mult, s.weight, if s.thin { "t" } else { "" })).collect::<Vec<_>>().join(" ")
    }

    /// The slot a random number lands on.
    pub fn draw(&self, r: u64) -> usize {
        let total = self.total_size() as u64;
        if total == 0 {
            return 0;
        }
        let mut at = r % total;
        for (i, s) in self.segs.iter().enumerate() {
            let size = self.row_size(s) as u64;
            if at < size {
                return i;
            }
            at -= size;
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
    fn rt_default_table_is_the_thin_jackpot_wheel_20_slices_jackpot_1_in_39_return_89_7_percent() {
        let t = Table::default_table();
        assert_eq!((t.total_weight(), t.unit(), t.total_size()), (20, 2, 39));
        assert_eq!(t.rtp_permille(), 897);
        assert_eq!(t.win_permille(), 385, "15 of 39 is 38.46%: shown as 38.5%");
        assert!(t.validate().is_ok());
        let sizes: Vec<u32> = t.segs.iter().map(|s| t.row_size(s)).collect();
        assert_eq!(sizes, vec![24, 12, 2, 1], "units of chance per row: 24 + 12 + 2 + 1 of 39");
    }

    #[test]
    fn rt_a_table_without_thin_slots_behaves_exactly_as_before() {
        let t = Table::parse("0x13 2x5 3x1 5x1").unwrap();
        assert_eq!((t.unit(), t.total_size()), (1, 20));
        assert_eq!((t.rtp_permille(), t.win_permille()), (900, 350));
        // the same slot gets the same row for every random number as before (13 lose, then 5 x2, 1 x3, 1 x5)
        let rows: Vec<usize> = (0..20u64).map(|r| t.draw(r)).collect();
        assert_eq!(rows, [vec![0; 13], vec![1; 5], vec![2], vec![3]].concat());
        assert_eq!(t.draw(20), 0, "the draw wraps like it always did");
    }

    #[test]
    fn rt_a_thin_row_has_half_the_chance_of_a_normal_slot() {
        let t = Table::parse("0x12 2x6 3x1 5x1t").unwrap();
        assert!(t.segs[3].thin && !t.segs[0].thin);
        assert_eq!(t, Table::default_table());
        // one full cycle over the 39 units of chance: 24 lose, 12 x2, 2 x3, 1 x5
        let mut counts = [0u32; 4];
        for r in 0..39u64 {
            counts[t.draw(r)] += 1;
        }
        assert_eq!(counts, [24, 12, 2, 1]);
        // two thin slots equal one normal slot
        let a = Table::parse("0x9 2x1t 2x1t").unwrap();
        assert_eq!((a.unit(), a.total_size()), (2, 9 * 2 + 1 + 1));
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
        assert_eq!(t.to_text(), "0x12 2x6 3x1 5x1t");
        assert_eq!(Table::parse(&t.to_text()).unwrap(), t);
        // an older table (no marks) is written back without any
        let old = Table::parse("0x13 2x5 3x1 5x1").unwrap();
        assert_eq!(old.to_text(), "0x13 2x5 3x1 5x1");
        assert!(Table::parse("5x1T").is_err() && Table::parse("5x1tt").is_err() && Table::parse("5xt").is_err());
    }

    #[test]
    fn rt_the_draw_follows_the_table_exactly_over_one_full_cycle() {
        let t = Table::default_table();
        let mut counts = std::collections::HashMap::new();
        for r in 0..39u64 {
            *counts.entry(t.segs[t.draw(r)].mult).or_insert(0u32) += 1;
        }
        assert_eq!(counts.get(&0), Some(&24));
        assert_eq!(counts.get(&2), Some(&12));
        assert_eq!(counts.get(&3), Some(&2));
        assert_eq!(counts.get(&5), Some(&1));
    }

    #[test]
    fn rt_the_draw_over_a_large_run_stays_near_the_published_return() {
        let t = Table::default_table();
        // a simple deterministic spread (a xorshift); the table's return is 89.7%, the jackpot comes up 1 time in 39
        let n = 400_000u64;
        let mut x = 0x9E37_79B9_7F4A_7C15u64;
        let (mut ret, mut jackpots, mut wins) = (0u64, 0u64, 0u64);
        for _ in 0..n {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            let m = t.segs[t.draw(x)].mult as u64;
            ret += m;
            jackpots += (m == 5) as u64;
            wins += (m > 0) as u64;
        }
        let rtp = ret as f64 / n as f64;
        assert!((rtp - 0.897).abs() < 0.02, "return {}", rtp);
        assert!((jackpots as f64 / n as f64 - 1.0 / 39.0).abs() < 0.003, "jackpot chance {}", jackpots as f64 / n as f64);
        assert!((wins as f64 / n as f64 - 15.0 / 39.0).abs() < 0.01, "win chance {}", wins as f64 / n as f64);
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

    #[test]
    fn rt_a_new_table_may_have_at_most_24_slots_but_a_saved_larger_one_is_still_accepted() {
        let ok = Table::parse("0x14 2x9 3x1").unwrap();
        assert_eq!(ok.total_weight(), 24);
        assert!(ok.validate().is_ok(), "24 slots is the most that can be drawn evenly on a phone");
        let big = Table::parse("0x66 2x25 3x6 5x4").unwrap();
        assert_eq!(big.total_weight(), 101);
        let why = big.validate().unwrap_err();
        assert!(why.contains("at most 24"), "{}", why);
        // a table saved before the cap (up to the old 1000) keeps working: the page draws 24 proportional slices
        assert!(big.validate_saved().is_ok());
        assert!(Table::parse("0x1001").unwrap().validate_saved().is_err());
        // and the return rule still applies to a saved table
        assert!(Table::parse("2x30 0x10").unwrap().validate_saved().is_err());
    }

    #[test]
    fn rt_thin_slots_follow_the_same_rules_return_cap_and_slot_cap() {
        // the return is worked out over sizes: a 5x jackpot that is not thin and 1 lose of 3 units: 166%, refused
        assert!(Table::parse("0x1 5x1t").unwrap().validate().is_err(), "0x1 normal (2) + 5x1 thin (1): 5/3 = 166%");
        // thin losses are allowed: 2 thin losses + 1 x2 normal = 4 units, return 2*2/4 = 100%
        let fair = Table::parse("0x2t 2x1").unwrap();
        assert_eq!((fair.total_size(), fair.rtp_permille()), (4, 1000));
        assert!(fair.validate().is_ok());
        // the cap counts slices (slots), not size
        assert!(Table::parse("0x10 2x10t 3x5t").unwrap().validate().is_err(), "25 slots");
        assert!(Table::parse("0x10 2x10t 3x4t").unwrap().validate().is_ok(), "24 slots");
    }

    #[test]
    fn rt_a_table_that_returns_even_a_hair_more_than_100_percent_is_refused() {
        // 1000 of 999 units: 100.1%. The rounded permille says 1001 or 1000 depending on the rounding, the exact comparison must refuse it.
        let t = Table::parse("0x499 2x500").unwrap();
        assert!(t.validate_saved().is_err(), "{}", t.rtp_permille());
        assert!(Table::parse("0x500 2x499").unwrap().validate_saved().is_ok(), "99.9% is fine");
        assert!(Table::parse("0x1 2x1").unwrap().validate_saved().is_ok(), "exactly 100% stays allowed");
    }
}
