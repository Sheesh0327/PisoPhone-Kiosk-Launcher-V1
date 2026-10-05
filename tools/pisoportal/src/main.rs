//! pisoportal: the PisoPhone portal. openNDS (FAS mode) redirects a new customer here; this program serves the coin page,
//! talks to the coin box, keeps the money records and grants access.
//!
//!   pisoportal [serve]        run (the service)
//!   pisoportal box            does the coin box answer?
//!   pisoportal reconcile      the box's own coin count against the revenue ledger (RECONCILE OK|MISMATCH|BADLEDGER|NOBOX)
//!   pisoportal verify         check the ledger's hash chain
//!   pisoportal report [days]  revenue per day and plan
//!   pisoportal selftest       checks that this build works on this machine
mod boxlink;
mod config;
mod core;
mod fair;
mod http;
mod ledger;
mod nds;
mod pricing;
mod roll;
mod util;

use std::process::Command;

fn cmd_box(cfg: &config::Config) -> i32 {
    let b = boxlink::BoxLink::new(&cfg.gw_box, &cfg.gw_key);
    if b.challenge().is_some() {
        println!("box {} answers", cfg.gw_box);
        0
    } else {
        println!("box {} does not answer", cfg.gw_box);
        1
    }
}

fn cmd_verify(cfg: &config::Config) -> i32 {
    match ledger::verify(&cfg.revenue_path()) {
        Ok((n, sum)) => {
            println!("OK {} {}", n, sum);
            0
        }
        Err(l) => {
            println!("BAD {}", l);
            1
        }
    }
}

fn cmd_reconcile(cfg: &config::Config) -> i32 {
    let ledger_pesos = match ledger::verify(&cfg.revenue_path()) {
        Ok((_, sum)) => sum,
        Err(l) => {
            println!("RECONCILE BADLEDGER line {}", l);
            return 3;
        }
    };
    let b = boxlink::BoxLink::new(&cfg.gw_box, &cfg.gw_key);
    let st = b.call(&"f".repeat(32), "status", "").unwrap_or_default();
    let Some(box_pulses) = util::jget_u64(&st, "lifetime_pulses") else {
        println!("RECONCILE NOBOX (needs firmware 3.2.1 or later) ledger={}", ledger_pesos);
        return 4;
    };
    if ledger_pesos > box_pulses {
        println!("RECONCILE MISMATCH ledger={} box={} (ledger is higher than the box counted)", ledger_pesos, box_pulses);
        return 1;
    }
    println!("RECONCILE OK ledger={} box={} (the difference of {} went to rental phones)", ledger_pesos, box_pulses, box_pulses - ledger_pesos);
    0
}

fn tz_offset_secs() -> i64 {
    let out = Command::new("date").arg("+%z").output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
    if out.len() == 5 {
        let sign = if out.starts_with('-') { -1 } else { 1 };
        let h: i64 = out[1..3].parse().unwrap_or(0);
        let m: i64 = out[3..5].parse().unwrap_or(0);
        return sign * (h * 3600 + m * 60);
    }
    0
}

fn civil(days: i64) -> String {
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    format!("{:04}-{:02}-{:02}", if m <= 2 { y + 1 } else { y }, m, d)
}

fn cmd_report(cfg: &config::Config, days: i64) -> i32 {
    let Ok(text) = std::fs::read_to_string(cfg.revenue_path()) else {
        println!("No payments recorded yet.");
        return 0;
    };
    let off = tz_offset_secs();
    let now = util::now_secs() as i64;
    let mut rows: std::collections::BTreeMap<(String, String), (u64, u64)> = std::collections::BTreeMap::new();
    let mut total = 0u64;
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 5 {
            continue;
        }
        let (Ok(t), Ok(p)) = (f[0].parse::<i64>(), f[2].parse::<u64>()) else { continue };
        if t < now - days * 86400 {
            continue;
        }
        let e = rows.entry((civil((t + off).div_euclid(86400)), f[1].to_string())).or_insert((0, 0));
        e.0 += p;
        e.1 += 1;
        total += p;
    }
    for ((day, plan), (p, n)) in rows {
        println!("{}  {:<10}  PHP {:<6}  {} payment(s)", day, plan, p, n);
    }
    println!("Total last {} day(s): PHP {}", days, total);
    0
}

fn selftest() -> i32 {
    let cfg = config::Config::from_map(Default::default());
    let ok_hmac = util::hmac_hex(b"key", b"The quick brown fox jumps over the lazy dog") == "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
    let ok_ws = http::ws_accept("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    let ok_price = pricing::minutes_for(&cfg, pricing::Plan::Endurance, 17) == 690;
    println!("hmac-sha256: {}  websocket accept key: {}  price P17 endurance=690 min: {}", ok_hmac, ok_ws, ok_price);
    println!("arch={} endian={} pointer_width={}", std::env::consts::ARCH, if cfg!(target_endian = "little") { "little" } else { "big" }, usize::BITS);
    if ok_hmac && ok_ws && ok_price {
        0
    } else {
        1
    }
}

fn serve() {
    let cfg = config::Config::load();
    if cfg.gw_key.len() < 16 {
        eprintln!("coinslot: GW_KEY is not set (run piso-setup): the coin box cannot be reached");
    }
    let core = core::Core::new(cfg);
    {
        let c = std::sync::Arc::clone(&core);
        std::thread::spawn(move || core::run_event_listener(c));
        let c = std::sync::Arc::clone(&core);
        std::thread::spawn(move || http::serve_admin(c));
        let c = std::sync::Arc::clone(&core);
        std::thread::spawn(move || {
            c.maintenance();
            fair::run(c)
        });
    }
    http::serve(core);
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(|s| s.as_str()).unwrap_or("serve");
    let code = match cmd {
        "serve" => {
            serve();
            0
        }
        "selftest" | "--selftest" => selftest(),
        "version" | "--version" => {
            println!("pisoportal {}", env!("CARGO_PKG_VERSION"));
            0
        }
        "box" => cmd_box(&config::Config::load()),
        "verify" => cmd_verify(&config::Config::load()),
        "reconcile" => cmd_reconcile(&config::Config::load()),
        "report" => cmd_report(&config::Config::load(), args.get(2).and_then(|d| d.parse().ok()).unwrap_or(7)),
        _ => {
            eprintln!("usage: pisoportal [serve|box|reconcile|verify|report [days]|selftest|version]");
            2
        }
    };
    std::process::exit(code);
}
