//! Voucher codes: Wi-Fi time a customer paid for with coins and chose to keep as a code ("Get a voucher code" on the coin page),
//! to use now or later (30 days). A code is 10 characters of Crockford base32 (no I, L, O, U: nothing is mistaken for a digit),
//! shown as ABCDE-FGHJK.
//!
//! One paid coin window makes ONE voucher: a batch of one code (`issue`) that remembers the device that paid (`buyer`) and the
//! coin window (`wid`, so a window recovered after a restart never makes a second voucher). Codes are NOT stored: each is derived
//! as HMAC-SHA256(router secret, "v1" + the batch's random salt + its number), so the buyer's page can show the code again (the
//! device's own list) while a copy of the files on the router reveals no usable code. What is stored per code is only a short hash
//! of it and whether it was used (so a code is found, and can be spent once). A void voucher is refused on redemption; nothing is
//! deleted from the books until a used or expired voucher is more than KEEP_DAYS old (`prune`).
//!
//! Batches made by the owner in earlier releases (printed slips: many codes, no buyer, no window) are still read and redeemed
//! exactly as before until they expire or are used; this release makes no new ones.
//!
//! Files in DATA_DIR: voucher.key (the secret, 0600), voucher_batches.txt, voucher_codes.txt. The caller holds the process-wide file lock.
use crate::util::{hmac_hex, random_hex, sha256_hex};
use std::fs;

/// A voucher bought with coins is good for this long.
pub const VALID_DAYS: u64 = 30;
/// A used or expired voucher bought with coins is forgotten this many days after (the sales report counts redeemed ones from it).
const KEEP_DAYS: u64 = 100;
/// Never more than this many vouchers bought with coins are kept (the oldest used or expired go first).
const MAX_KEPT: usize = 20_000;
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

#[derive(Clone, Debug, PartialEq)]
pub struct Batch {
    pub id: String,
    pub salt: String,
    pub count: usize,
    pub minutes: u32,
    pub pesos: u32,
    pub plan: String,
    /// 0 = the slips never expire
    pub expires: u64,
    pub pause: bool,
    pub created: u64,
    pub void: bool,
    pub label: String,
    /// the device (MAC) that paid for it with coins; empty for a batch the owner made in an earlier release
    pub buyer: String,
    /// the coin window that paid for it; empty for an owner-made batch
    pub wid: String,
}

impl Batch {
    /// Bought with coins on the coin page (not an owner-made batch of an earlier release).
    pub fn bought(&self) -> bool {
        !self.wid.is_empty()
    }

    fn line(&self) -> String {
        format!(
            "{},{},{},{},{},{},{},{},{},{},{},{},{}",
            self.id,
            self.salt,
            self.count,
            self.minutes,
            self.pesos,
            self.plan,
            self.expires,
            self.pause as u8,
            self.created,
            self.void as u8,
            self.label,
            self.buyer,
            self.wid
        )
    }

    fn parse(line: &str) -> Option<Batch> {
        // (older lines end after the label: 10 or 11 fields)
        let f: Vec<&str> = line.trim_end().splitn(13, ',').collect();
        if f.len() < 10 {
            return None;
        }
        let n = |i: usize| -> u64 { f[i].parse().unwrap_or(0) };
        Some(Batch {
            id: f[0].to_string(),
            salt: f[1].to_string(),
            count: n(2) as usize,
            minutes: n(3) as u32,
            pesos: n(4) as u32,
            plan: f[5].to_string(),
            expires: n(6),
            pause: n(7) == 1,
            created: n(8),
            void: n(9) == 1,
            label: f.get(10).unwrap_or(&"").to_string(),
            buyer: f.get(11).unwrap_or(&"").to_string(),
            wid: f.get(12).unwrap_or(&"").to_string(),
        })
    }
}

#[derive(Clone, Debug, PartialEq)]
struct CodeRow {
    hash: String,
    batch: String,
    used_at: u64,
    mac: String,
}

impl CodeRow {
    fn line(&self) -> String {
        format!("{},{},{},{}", self.hash, self.batch, self.used_at, self.mac)
    }
    fn parse(line: &str) -> Option<CodeRow> {
        let f: Vec<&str> = line.trim_end().split(',').collect();
        (f.len() >= 3 && !f[0].is_empty()).then(|| CodeRow {
            hash: f[0].to_string(),
            batch: f[1].to_string(),
            used_at: f[2].parse().unwrap_or(0),
            mac: f.get(3).unwrap_or(&"").to_string(),
        })
    }
}

#[derive(Debug, PartialEq)]
pub enum RedeemError {
    Bad,
    Used,
    Expired,
}

fn batches_path(dir: &str) -> String {
    format!("{}/voucher_batches.txt", dir)
}
fn codes_path(dir: &str) -> String {
    format!("{}/voucher_codes.txt", dir)
}

/// The router's voucher secret, if it has one.
fn read_secret(dir: &str) -> Option<String> {
    let s = fs::read_to_string(format!("{}/voucher.key", dir)).ok()?.trim().to_string();
    (s.len() >= 32).then_some(s)
}

/// The router's voucher secret; made on first use, readable by root only. None when it could not be kept on flash: a code derived
/// from a secret that was lost would never work again, so nothing is issued then.
fn secret(dir: &str) -> Option<String> {
    if let Some(s) = read_secret(dir) {
        return Some(s);
    }
    let path = format!("{}/voucher.key", dir);
    // RT-06: a key file that has anything in it is never replaced (a damaged key may still be repaired by the owner; a new one
    // would make every code already issued useless). Only a missing or empty file gets a new key.
    if fs::metadata(&path).map(|m| m.len() > 0).unwrap_or(false) {
        return None;
    }
    let s = random_hex(64);
    let _ = crate::util::write_atomic_private(&path, format!("{}\n", s).as_bytes());
    read_secret(dir)
}

/// The code with this number in a batch: 10 base32 characters from the first 50 bits of the HMAC.
pub fn derive(secret: &str, salt: &str, index: usize) -> String {
    let mac = hmac_hex(secret.as_bytes(), format!("v1{}{}", salt, index).as_bytes());
    let bytes: Vec<u8> = (0..14).step_by(2).filter_map(|i| u8::from_str_radix(&mac[i..i + 2], 16).ok()).collect();
    let mut bits: u64 = 0;
    for b in &bytes {
        bits = (bits << 8) | *b as u64;
    }
    // 7 bytes = 56 bits; the first 50 become ten characters
    (0..10).map(|k| ALPHABET[((bits >> (56 - 5 * (k + 1))) & 31) as usize] as char).collect()
}

/// What a customer typed -> the 10-character code, or None. Case, spaces, dashes are ignored; O reads as 0, I and L as 1.
pub fn normalize(input: &str) -> Option<String> {
    let mut out = String::new();
    for c in input.chars() {
        if c == ' ' || c == '-' {
            continue;
        }
        let c = match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            u => u,
        };
        if !c.is_ascii() || !ALPHABET.contains(&(c as u8)) {
            return None;
        }
        out.push(c);
    }
    (out.len() == 10).then_some(out)
}

pub fn display(code: &str) -> String {
    if code.len() == 10 {
        format!("{}-{}", &code[..5], &code[5..])
    } else {
        code.to_string()
    }
}

fn hash_of(code: &str) -> String {
    sha256_hex(format!("vch1{}", code).as_bytes())[..16].to_string()
}

// Read as bytes and decoded leniently: one damaged byte used to make the whole file read as empty, and the next write then
// replaced every voucher with one line.
fn load_batches(dir: &str) -> Vec<Batch> {
    fs::read(batches_path(dir)).map(|b| String::from_utf8_lossy(&b).lines().filter_map(Batch::parse).collect()).unwrap_or_default()
}

fn load_codes(dir: &str) -> Vec<CodeRow> {
    fs::read(codes_path(dir)).map(|b| String::from_utf8_lossy(&b).lines().filter_map(CodeRow::parse).collect()).unwrap_or_default()
}

fn write_atomic(path: &str, lines: impl Iterator<Item = String>) -> std::io::Result<()> {
    let text: String = lines.map(|l| format!("{}\n", l)).collect();
    crate::util::write_atomic(path, text.as_bytes())
}

/// What `issue` made (or found again).
#[derive(Debug, Clone)]
pub struct Issued {
    pub batch: Batch,
    /// shown as ABCDE-FGHJK
    pub code: String,
    /// false: this coin window had a voucher already (a window recovered after a restart)
    pub created: bool,
}

/// The voucher a paid coin window buys: one code worth exactly `minutes` on `plan`, valid VALID_DAYS days, for the device `mac`.
/// The same window (`wid`) never makes a second one: asked again, it returns the first (created = false).
pub fn issue(dir: &str, now: u64, mac: &str, wid: &str, plan: &str, minutes: u32, pesos: u32) -> Result<Issued, &'static str> {
    if wid.is_empty() || minutes == 0 {
        return Err("INVALID");
    }
    if plan != "hyper" && plan != "endurance" {
        return Err("PLAN");
    }
    let mut batches = load_batches(dir);
    let mut rows = load_codes(dir);
    let sec = secret(dir).ok_or("IO")?;
    if let Some(b) = batches.iter().find(|b| b.wid == wid) {
        return Ok(Issued { code: display(&derive(&sec, &b.salt, 0)), batch: b.clone(), created: false });
    }
    forget_old(&mut batches, &mut rows, now);
    let batch = Batch {
        id: random_hex(6),
        salt: random_hex(32),
        count: 1,
        minutes,
        pesos,
        plan: plan.to_string(),
        expires: now + VALID_DAYS * 86_400,
        pause: true,
        created: now,
        void: false,
        label: String::new(),
        buyer: mac.to_lowercase(),
        wid: wid.to_string(),
    };
    let code = derive(&sec, &batch.salt, 0);
    let h = hash_of(&code);
    if rows.iter().any(|r| r.hash == h) {
        return Err("COLLISION"); // never in practice: 50 bits and a few thousand codes
    }
    rows.push(CodeRow { hash: h, batch: batch.id.clone(), used_at: 0, mac: String::new() });
    batches.push(batch.clone());
    fs::create_dir_all(dir).map_err(|_| "IO")?;
    // the code row first: a crash between the two files leaves a row no batch owns (refused as an unknown code), never a voucher without one
    write_atomic(&codes_path(dir), rows.iter().map(|r| r.line())).map_err(|_| "IO")?;
    write_atomic(&batches_path(dir), batches.iter().map(|b| b.line())).map_err(|_| "IO")?;
    Ok(Issued { code: display(&code), batch, created: true })
}

/// Drop vouchers bought with coins that were used, expired or voided more than KEEP_DAYS ago (and, past MAX_KEPT, the oldest of
/// them), with their code rows. Owner-made batches are never dropped. True when something was dropped.
fn forget_old(batches: &mut Vec<Batch>, rows: &mut Vec<CodeRow>, now: u64) -> bool {
    let keep = KEEP_DAYS * 86_400;
    let used_at = |rows: &[CodeRow], id: &str| rows.iter().find(|r| r.batch == id).map(|r| r.used_at).unwrap_or(0);
    let mut gone: std::collections::HashSet<String> = batches
        .iter()
        .filter(|b| {
            let used = used_at(rows, &b.id);
            b.bought() && ((used > 0 && used + keep < now) || b.expires + keep < now || (b.void && b.created + keep < now))
        })
        .map(|b| b.id.clone())
        .collect();
    let kept = batches.iter().filter(|b| b.bought() && !gone.contains(&b.id)).count();
    if kept > MAX_KEPT {
        // (oldest first, among those that are finished with)
        let mut done: Vec<&Batch> = batches
            .iter()
            .filter(|b| b.bought() && !gone.contains(&b.id) && (used_at(rows, &b.id) > 0 || b.expires < now || b.void))
            .collect();
        done.sort_by_key(|b| b.created);
        for b in done.into_iter().take(kept - MAX_KEPT) {
            gone.insert(b.id.clone());
        }
    }
    if gone.is_empty() {
        return false;
    }
    batches.retain(|b| !gone.contains(&b.id));
    rows.retain(|r| !gone.contains(&r.batch));
    true
}

/// Housekeeping (the maintenance thread): `forget_old` on the files.
pub fn prune(dir: &str, now: u64) {
    let mut batches = load_batches(dir);
    let mut rows = load_codes(dir);
    if forget_old(&mut batches, &mut rows, now) {
        let _ = write_atomic(&codes_path(dir), rows.iter().map(|r| r.line()));
        let _ = write_atomic(&batches_path(dir), batches.iter().map(|b| b.line()));
    }
}

/// The router's clock jumped forward by `delta` seconds (see `roll::shift`): vouchers made with the stale clock (created in
/// `since <= created < until`) move forward with it, with their 30 days, and so does the time a code was used when that time
/// is in the window. A voucher made after the jump is not touched. Returns how many vouchers moved. The caller holds the file lock.
pub fn shift(dir: &str, since: u64, until: u64, delta: u64) -> usize {
    let in_window = |t: u64| t >= since && t < until;
    let mut batches = load_batches(dir);
    let mut rows = load_codes(dir);
    let mut moved = 0;
    for b in batches.iter_mut().filter(|b| in_window(b.created)) {
        b.created += delta;
        if b.expires > 0 {
            b.expires += delta;
        }
        moved += 1;
    }
    let mut rows_moved = false;
    for r in rows.iter_mut().filter(|r| r.used_at > 0 && in_window(r.used_at)) {
        r.used_at += delta;
        rows_moved = true;
    }
    if rows_moved {
        let _ = write_atomic(&codes_path(dir), rows.iter().map(|r| r.line()));
    }
    if moved > 0 {
        let _ = write_atomic(&batches_path(dir), batches.iter().map(|b| b.line()));
    }
    moved
}

/// A voucher this device bought and has not used: what its page lists, so a closed page does not lose the money.
#[derive(Debug, Clone, PartialEq)]
pub struct Mine {
    /// shown as ABCDE-FGHJK
    pub code: String,
    pub minutes: u32,
    pub plan: String,
    pub expires: u64,
}

/// The unused, unexpired, not void vouchers a device paid for, newest first (at most 10). Read only.
pub fn for_device(dir: &str, now: u64, mac: &str) -> Vec<Mine> {
    let mac = mac.to_lowercase();
    let mut mine: Vec<Batch> =
        load_batches(dir).into_iter().filter(|b| b.bought() && b.buyer == mac && !b.void && b.expires > now).collect();
    if mine.is_empty() {
        return vec![];
    }
    let Some(sec) = read_secret(dir) else { return vec![] };
    let rows = load_codes(dir);
    mine.sort_by_key(|b| std::cmp::Reverse(b.created));
    mine.into_iter()
        .filter_map(|b| {
            let code = derive(&sec, &b.salt, 0);
            let h = hash_of(&code);
            rows.iter().find(|r| r.hash == h).filter(|r| r.used_at == 0)?;
            Some(Mine { code: display(&code), minutes: b.minutes, plan: b.plan, expires: b.expires })
        })
        .take(10)
        .collect()
}

/// (vouchers bought with coins that were redeemed since `since`, vouchers bought with coins that wait to be used now).
pub fn counts(dir: &str, since: u64, now: u64) -> (u64, u64) {
    let rows = load_codes(dir);
    let (mut redeemed, mut waiting) = (0, 0);
    for b in load_batches(dir).iter().filter(|b| b.bought()) {
        let used = rows.iter().find(|r| r.batch == b.id).map(|r| r.used_at).unwrap_or(0);
        if used > 0 && used >= since {
            redeemed += 1;
        } else if used == 0 && !b.void && b.expires > now {
            waiting += 1;
        }
    }
    (redeemed, waiting)
}

/// Every voucher batch with how many of its codes were used (the owner's list in the console).
pub fn list(dir: &str) -> Vec<(Batch, usize)> {
    let rows = load_codes(dir);
    load_batches(dir)
        .into_iter()
        .map(|b| {
            let used = rows.iter().filter(|r| r.batch == b.id && r.used_at > 0).count();
            (b, used)
        })
        .collect()
}

/// Mark every unused code of a batch as not valid any more.
pub fn void_batch(dir: &str, batch_id: &str) -> bool {
    let mut batches = load_batches(dir);
    let Some(b) = batches.iter_mut().find(|b| b.id == batch_id) else { return false };
    b.void = true;
    write_atomic(&batches_path(dir), batches.iter().map(|b| b.line())).is_ok()
}

/// Is this code good right now? Returns its batch and the code's hash (used by `mark_used`). Nothing changes.
pub fn lookup(dir: &str, now: u64, code: &str) -> Result<(Batch, String), RedeemError> {
    let code = normalize(code).ok_or(RedeemError::Bad)?;
    let h = hash_of(&code);
    let row = load_codes(dir).into_iter().find(|r| r.hash == h).ok_or(RedeemError::Bad)?;
    let batch = load_batches(dir).into_iter().find(|b| b.id == row.batch).ok_or(RedeemError::Bad)?;
    if batch.void {
        return Err(RedeemError::Bad);
    }
    if row.used_at > 0 {
        return Err(RedeemError::Used);
    }
    if batch.expires > 0 && now > batch.expires {
        return Err(RedeemError::Expired);
    }
    Ok((batch, h))
}

/// A code marked spent whose time could not be added: unused again.
pub fn mark_unused(dir: &str, hash: &str) -> bool {
    let mut rows = load_codes(dir);
    let Some(r) = rows.iter_mut().find(|r| r.hash == hash) else { return false };
    r.used_at = 0;
    r.mac.clear();
    write_atomic(&codes_path(dir), rows.iter().map(|r| r.line())).is_ok()
}

/// The code is spent (by this device, now).
pub fn mark_used(dir: &str, hash: &str, now: u64, mac: &str) -> bool {
    let mut rows = load_codes(dir);
    let Some(r) = rows.iter_mut().find(|r| r.hash == hash) else { return false };
    r.used_at = now.max(1);
    r.mac = mac.to_string();
    write_atomic(&codes_path(dir), rows.iter().map(|r| r.line())).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("pisoportal-voucher-{}-{}", name, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().into_owned()
    }

    /// A batch the way the owner made them in releases before 1.3.4 (many codes, no buyer, no coin window, the old 11-field line).
    fn legacy(dir: &str, now: u64, minutes: u32, pesos: u32, plan: &str, count: usize, days: u64) -> (Batch, Vec<String>) {
        let sec = secret(dir).unwrap();
        let salt = random_hex(32);
        let id = random_hex(6);
        let codes: Vec<String> = (0..count).map(|i| derive(&sec, &salt, i)).collect();
        let mut rows = load_codes(dir);
        for c in &codes {
            rows.push(CodeRow { hash: hash_of(c), batch: id.clone(), used_at: 0, mac: String::new() });
        }
        write_atomic(&codes_path(dir), rows.iter().map(|r| r.line())).unwrap();
        let expires = if days == 0 { 0 } else { now + days * 86_400 };
        let old_line = format!("{},{},{},{},{},{},{},1,{},0,Old slips", id, salt, count, minutes, pesos, plan, expires, now);
        let mut text = fs::read_to_string(batches_path(dir)).unwrap_or_default();
        text.push_str(&old_line);
        text.push('\n');
        fs::write(batches_path(dir), text).unwrap();
        let b = load_batches(dir).into_iter().find(|b| b.id == id).unwrap();
        (b, codes.iter().map(|c| display(c)).collect())
    }

    #[test]
    fn rt03_a_voucher_made_with_the_stale_clock_moves_with_the_clock() {
        let d = tmp("shift");
        let mac = "aa:bb:cc:00:00:01";
        let (stale_start, jump_at, delta) = (1_000_000u64, 1_000_200u64, 40 * 86_400u64); // the clock was set 40 days forward
        let before = issue(&d, 500_000, mac, "w0", "hyper", 30, 5).unwrap(); // made before the power cut: right clock
        let stale = issue(&d, 1_000_050, mac, "w1", "hyper", 60, 10).unwrap(); // bought during the stale period
        let used = issue(&d, 1_000_060, mac, "w2", "hyper", 15, 3).unwrap();
        let (_, h) = lookup(&d, 1_000_100, &used.code).unwrap();
        assert!(mark_used(&d, &h, 1_000_100, mac));
        let after = issue(&d, 1_000_200 + delta + 10, mac, "w3", "hyper", 30, 5).unwrap(); // bought after the clock was right
        let now = 1_000_200 + delta + 20;
        assert_eq!(lookup(&d, now, &stale.code), Err(RedeemError::Expired), "without the shift the 30 days have 'passed'");
        assert_eq!(shift(&d, stale_start, jump_at, delta), 2, "the two vouchers bought in the stale period move");
        let (b, _) = lookup(&d, now, &stale.code).expect("the voucher is good for its 30 days again");
        assert_eq!((b.created, b.expires), (1_000_050 + delta, 1_000_050 + delta + 30 * 86_400));
        let kept = load_batches(&d);
        let get = |id: &str| kept.iter().find(|b| b.id == id).unwrap().clone();
        assert_eq!(
            (get(&before.batch.id).created, get(&before.batch.id).expires),
            (500_000, before.batch.expires),
            "a voucher from before the cut keeps its times"
        );
        assert_eq!(get(&after.batch.id).created, after.batch.created, "a voucher bought after the jump is not moved");
        assert_eq!(get(&after.batch.id).expires, after.batch.expires);
        // the redeemed one: when it was used moves with the clock too
        let (redeemed, _) = counts(&d, 1_000_100 + delta, now);
        assert_eq!(redeemed, 1);
    }

    #[test]
    fn rt06_an_existing_voucher_key_is_never_overwritten() {
        let d = tmp("key");
        let path = format!("{}/voucher.key", d);
        fs::write(&path, "damaged\n").unwrap(); // not empty, but not a whole key
        assert_eq!(secret(&d), None, "a key that cannot be used is not replaced: codes made from it would be lost");
        assert_eq!(fs::read_to_string(&path).unwrap(), "damaged\n");
        assert_eq!(issue(&d, 1000, "aa:bb:cc:00:00:01", "w", "hyper", 30, 5).unwrap_err(), "IO");
        assert_eq!(fs::read_to_string(&path).unwrap(), "damaged\n");
        // an empty file (the power went before the key was written) is made again
        fs::write(&path, "").unwrap();
        let s = secret(&d).expect("a key is made when there is none");
        assert!(s.len() >= 32);
        assert_eq!(secret(&d).unwrap(), s, "and then it stays");
    }

    #[test]
    fn codes_are_derived_not_stored_and_look_right() {
        let a = derive("secret", "salt", 0);
        assert_eq!(a, derive("secret", "salt", 0));
        assert_ne!(a, derive("secret", "salt", 1));
        assert_ne!(a, derive("secret", "salt2", 0));
        assert_ne!(a, derive("secret2", "salt", 0));
        assert_eq!(a.len(), 10);
        assert!(a.bytes().all(|c| ALPHABET.contains(&c)));
        assert_eq!(display(&a).len(), 11);
        assert_eq!(display(&a).as_bytes()[5], b'-');
    }

    #[test]
    fn what_a_customer_types_is_forgiven() {
        assert_eq!(normalize("abcde-fghjk").as_deref(), Some("ABCDEFGHJK"));
        assert_eq!(normalize(" ab cde fghjk ").as_deref(), Some("ABCDEFGHJK"));
        assert_eq!(normalize("OOIIL-00111").as_deref(), Some("0011100111"));
        assert_eq!(normalize("ABCDE-FGHJ"), None); // too short
        assert_eq!(normalize("ABCDE-FGHJKM"), None); // too long
        assert_eq!(normalize("ABCDE-FGHJU"), None); // U is not in the alphabet
        assert_eq!(normalize("ABCDE-FGHJ!"), None);
        assert_eq!(normalize("ÀBCDE-FGHJK"), None);
    }

    #[test]
    fn a_paid_window_makes_one_voucher_that_is_spent_once() {
        let d = tmp("issue");
        let mac = "AA:BB:CC:00:00:01";
        let v = issue(&d, 1000, mac, "wid1", "hyper", 60, 10).unwrap();
        assert!(v.created);
        assert_eq!((v.batch.minutes, v.batch.pesos, v.batch.count, v.batch.buyer.as_str()), (60, 10, 1, "aa:bb:cc:00:00:01"));
        assert_eq!(v.batch.expires, 1000 + 30 * 86_400, "valid for 30 days");
        assert!(v.batch.bought());
        assert_eq!((v.code.len(), v.code.as_bytes()[5]), (11, b'-'));
        // the same window never makes a second voucher: the same code comes back
        let again = issue(&d, 2000, mac, "wid1", "hyper", 60, 10).unwrap();
        assert!(!again.created);
        assert_eq!(again.code, v.code);
        assert_eq!(list(&d).len(), 1);
        // no plaintext code on flash: only a hash of it and the salt
        let plain = v.code.replace('-', "");
        for f in ["voucher_batches.txt", "voucher_codes.txt", "voucher.key"] {
            assert!(!fs::read_to_string(format!("{}/{}", d, f)).unwrap().contains(&plain), "{} holds the code", f);
        }
        // it can be redeemed, typed any which way, once
        let (b, h) = lookup(&d, 2000, &v.code.to_lowercase().replace('-', " ")).unwrap();
        assert_eq!((b.minutes, b.plan.as_str()), (60, "hyper"));
        assert!(mark_used(&d, &h, 2000, "aa:bb:cc:00:00:09"));
        assert_eq!(lookup(&d, 2001, &v.code), Err(RedeemError::Used));
        assert_eq!(lookup(&d, 2001, "ZZZZZ-ZZZZZ"), Err(RedeemError::Bad));
        // and after the 30 days it is expired
        let w = issue(&d, 1000, mac, "wid2", "endurance", 15, 1).unwrap();
        assert!(lookup(&d, 1000 + 30 * 86_400, &w.code).is_ok());
        assert_eq!(lookup(&d, 1000 + 30 * 86_400 + 1, &w.code), Err(RedeemError::Expired));
        assert_eq!(issue(&d, 1, mac, "", "hyper", 5, 1).unwrap_err(), "INVALID");
        assert_eq!(issue(&d, 1, mac, "w", "gold", 5, 1).unwrap_err(), "PLAN");
        assert_eq!(issue(&d, 1, mac, "w", "hyper", 0, 1).unwrap_err(), "INVALID");
    }

    #[test]
    fn a_device_finds_its_own_unused_vouchers_again() {
        let d = tmp("mine");
        let (a, b) = ("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02");
        let v1 = issue(&d, 1000, a, "w1", "hyper", 30, 5).unwrap();
        let v2 = issue(&d, 1100, a, "w2", "endurance", 180, 5).unwrap();
        let v3 = issue(&d, 1200, b, "w3", "hyper", 60, 10).unwrap();
        let mine = for_device(&d, 2000, a);
        assert_eq!(
            mine.iter().map(|m| m.code.as_str()).collect::<Vec<_>>(),
            vec![v2.code.as_str(), v1.code.as_str()],
            "newest first, only its own"
        );
        assert_eq!((mine[0].minutes, mine[0].plan.as_str(), mine[0].expires), (180, "endurance", 1100 + 30 * 86_400));
        assert_eq!(for_device(&d, 2000, "AA:BB:CC:00:00:02")[0].code, v3.code, "the address is matched in any case");
        assert!(for_device(&d, 2000, "aa:bb:cc:00:00:99").is_empty());
        // a used one, a void one and an expired one are not listed
        let (_, h) = lookup(&d, 2000, &v1.code).unwrap();
        mark_used(&d, &h, 2000, a);
        assert_eq!(for_device(&d, 2000, a).len(), 1);
        assert!(void_batch(&d, &v2.batch.id));
        assert!(for_device(&d, 2000, a).is_empty());
        assert!(for_device(&d, 1200 + 30 * 86_400 + 1, b).is_empty(), "expired");
        // the counts for the sales report
        assert_eq!(counts(&d, 0, 2000), (1, 1)); // v1 redeemed, v3 waiting (v2 is void)
        assert_eq!(counts(&d, 2001, 2002), (0, 1), "redeemed before the period is not counted");
        // the list for the owner keeps the void one, marked
        assert!(list(&d).iter().any(|(bt, used)| bt.id == v2.batch.id && bt.void && *used == 0));
    }

    #[test]
    fn old_owner_made_batches_still_work_and_are_never_forgotten() {
        let d = tmp("legacy");
        let (b, codes) = legacy(&d, 1000, 60, 10, "hyper", 3, 30);
        assert!(!b.bought() && b.buyer.is_empty() && b.label == "Old slips", "{:?}", b);
        let (found, h) = lookup(&d, 2000, &codes[1].to_lowercase()).unwrap();
        assert_eq!((found.minutes, found.pesos, found.count, found.id.as_str()), (60, 10, 3, b.id.as_str()));
        assert!(mark_used(&d, &h, 2000, "aa:bb:cc:00:00:01"));
        assert_eq!(lookup(&d, 2001, &codes[1]), Err(RedeemError::Used));
        assert_eq!(list(&d)[0].1, 1);
        assert_eq!(lookup(&d, 1000 + 30 * 86_400 + 1, &codes[0]), Err(RedeemError::Expired));
        // making a voucher next to it rewrites the file in the new format; the old batch is read back the same
        let v = issue(&d, 3000, "aa:bb:cc:00:00:02", "w1", "hyper", 30, 5).unwrap();
        let text = fs::read_to_string(batches_path(&d)).unwrap();
        assert!(text.lines().all(|l| l.split(',').count() == 13), "{}", text);
        assert_eq!(load_batches(&d)[0], b, "an old batch reads back the same");
        assert!(lookup(&d, 3000, &codes[2]).is_ok() && lookup(&d, 3000, &v.code).is_ok());
        // a very old line with only ten fields (no label) is read too
        let ten = Batch::parse("abc,salt,2,30,5,hyper,0,1,1000,0").unwrap();
        assert_eq!((ten.label.as_str(), ten.buyer.as_str(), ten.wid.as_str(), ten.expires), ("", "", "", 0));
        // forgetting old vouchers never touches them, however old
        prune(&d, 1000 + 400 * 86_400);
        assert!(load_batches(&d).iter().any(|x| x.id == b.id));
    }

    #[test]
    fn used_and_expired_vouchers_are_forgotten_after_a_while() {
        let d = tmp("forget");
        let mac = "aa:bb:cc:00:00:01";
        let v1 = issue(&d, 1000, mac, "w1", "hyper", 30, 5).unwrap();
        let v2 = issue(&d, 1000, mac, "w2", "hyper", 30, 5).unwrap();
        let v3 = issue(&d, 1000, mac, "w3", "hyper", 30, 5).unwrap();
        let (_, h) = lookup(&d, 2000, &v1.code).unwrap();
        mark_used(&d, &h, 2000, mac);
        let day = 86_400;
        prune(&d, 2000 + 99 * day);
        assert_eq!(list(&d).len(), 3, "kept for 100 days after use");
        prune(&d, 2000 + 101 * day);
        let left: Vec<String> = list(&d).into_iter().map(|(b, _)| b.id).collect();
        assert_eq!(left.len(), 2, "the used one is forgotten: {:?}", left);
        assert!(!left.contains(&v1.batch.id));
        assert_eq!(lookup(&d, 2000 + 101 * day, &v2.code), Err(RedeemError::Expired), "still known, expired");
        prune(&d, 1000 + 30 * day + 101 * day);
        assert!(list(&d).is_empty(), "expired for 100 days: gone, with its code row");
        assert!(fs::read_to_string(codes_path(&d)).unwrap().is_empty());
        assert_eq!(lookup(&d, 1, &v3.code), Err(RedeemError::Bad));
    }

    #[test]
    fn nothing_is_issued_when_the_secret_cannot_be_kept() {
        let d = tmp("nosecret");
        // a directory where the key file should be: it can be neither read nor written
        fs::create_dir_all(format!("{}/voucher.key", d)).unwrap();
        assert_eq!(issue(&d, 1000, "aa:bb:cc:00:00:01", "w1", "hyper", 30, 5).unwrap_err(), "IO");
        assert!(list(&d).is_empty());
    }
}
