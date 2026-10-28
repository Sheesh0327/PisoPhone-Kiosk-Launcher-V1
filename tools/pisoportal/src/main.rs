//! pisoportal: the PisoPhone portal. openNDS (FAS mode) redirects a new customer here; this program serves the coin page,
//! talks to the coin box, keeps the money records and grants access.
//!
//!   pisoportal [serve]        run (the service)
//!   pisoportal box            does the coin box answer?
//!   pisoportal reconcile [rebase]  the box's own coin count against the revenue ledger (RECONCILE OK|MISMATCH|BADLEDGER|NOBOX);
//!                             "rebase" compares from now on (after the box's revenue was collected)
//!   pisoportal verify         check the ledger's hash chain
//!   pisoportal ledger         months of sale detail kept, months deleted on purpose, the lifetime total
//!   pisoportal report [days]  revenue per day and plan
//!   pisoportal update-check <router.json> <installed version> <router id>   a verified router update from the website? (update.rs)
//!   pisoportal update-verify <router.json> <router-setup.sh>                 is the downloaded file the signed one?
//!   pisoportal selftest       checks that this build works on this machine
mod boxlink;
mod boxview;
mod config;
mod console;
mod core;
mod fair;
mod game;
mod http;
mod ledger;
mod nds;
mod pricing;
mod reconcile;
mod roll;
mod send;
mod update;
mod util;
mod voucher;

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

fn cmd_verify(cfg: &config::Config, all: bool) -> i32 {
    // "verify all" follows the chain across the monthly segments; the plain one reads the current month only
    let r = if all { ledger::verify_all(&cfg.revenue_path()) } else { ledger::verify_torn(&cfg.revenue_path()) };
    match r {
        Ok((n, sum, torn)) => {
            // a last row cut short by a power failure is not a changed ledger (RT-04)
            println!("OK {} {}{}", n, sum, if torn { " torn_tail=true" } else { "" });
            0
        }
        Err(l) => {
            println!("BAD {}", l);
            1
        }
    }
}

fn cmd_ledger(cfg: &config::Config) -> i32 {
    let path = cfg.revenue_path();
    match ledger::describe(&path, ledger::Budget::load(&path).keep_months) {
        Ok(s) => {
            println!("{}", s);
            0
        }
        Err(l) => {
            println!("Sales record: CHECK FAILED at line {} (run: pisoportal verify all)", l);
            1
        }
    }
}

fn cmd_reconcile(cfg: &config::Config, rebase: bool) -> i32 {
    let (out, note) = reconcile::run(cfg, rebase);
    if let Some(n) = note {
        println!("note: {}", n);
    }
    match out {
        reconcile::Outcome::BadLedger(l) => {
            println!("RECONCILE BADLEDGER line {}", l);
            3
        }
        reconcile::Outcome::NoBox(l) => {
            println!("RECONCILE NOBOX (needs firmware 3.2.1 or later) ledger={}", l);
            4
        }
        reconcile::Outcome::Mismatch { ledger, counted } => {
            println!(
                "RECONCILE MISMATCH ledger={} box={} (the router recorded more than the box counted; if the box's revenue was \
                 just collected and this persists: pisoportal reconcile rebase)",
                ledger, counted
            );
            1
        }
        reconcile::Outcome::Ok { ledger, counted } => {
            println!("RECONCILE OK ledger={} box={} (the difference of {} went to rental phones)", ledger, counted, counted - ledger);
            0
        }
    }
}

fn tz_offset_secs() -> i64 {
    let out =
        Command::new("date").arg("+%z").output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
    if out.len() == 5 {
        let sign = if out.starts_with('-') { -1 } else { 1 };
        let h: i64 = out[1..3].parse().unwrap_or(0);
        let m: i64 = out[3..5].parse().unwrap_or(0);
        return sign * (h * 3600 + m * 60);
    }
    0
}

fn cmd_report(cfg: &config::Config, days: i64) -> i32 {
    let off = tz_offset_secs();
    let now = util::now_secs() as i64;
    let text = ledger::read_recent(&cfg.revenue_path(), (now - (days + 1) * 86400).max(0) as u64);
    if text.is_empty() {
        println!("No payments recorded yet.");
        return 0;
    }
    let mut rows: std::collections::BTreeMap<(String, String), (u64, u64)> = std::collections::BTreeMap::new();
    let (mut total, mut vn, mut vp) = (0u64, 0u64, 0u64);
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 5 {
            continue;
        }
        let (Ok(t), Ok(p)) = (f[0].parse::<i64>(), f[2].parse::<u64>()) else { continue };
        if t < now - days * 86400 {
            continue;
        }
        let e = rows.entry((util::civil((t + off).div_euclid(86400)), f[1].to_string())).or_insert((0, 0));
        e.0 += p;
        e.1 += 1;
        total += p;
        if f[4] == "vbuy" {
            vn += 1;
            vp += p;
        }
    }
    for ((day, plan), (p, n)) in rows {
        println!("{}  {:<10}  PHP {:<6}  {} payment(s)", day, plan, p, n);
    }
    println!("Total last {} day(s): PHP {}", days, total);
    if vn > 0 {
        println!("  of which voucher codes customers bought with coins: {} (PHP {}); using a code later adds no income", vn, vp);
    }
    0
}

fn selftest() -> i32 {
    let cfg = config::Config::from_map(Default::default());
    let ok_hmac = util::hmac_hex(b"key", b"The quick brown fox jumps over the lazy dog")
        == "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
    let ok_ws = http::ws_accept("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    let ok_price = pricing::minutes_for(&pricing::Pricing::from_config(&cfg), pricing::Plan::Endurance, 17) == 690;
    println!("hmac-sha256: {}  websocket accept key: {}  price P17 endurance=690 min: {}", ok_hmac, ok_ws, ok_price);
    println!(
        "arch={} endian={} pointer_width={}",
        std::env::consts::ARCH,
        if cfg!(target_endian = "little") { "little" } else { "big" },
        usize::BITS
    );
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
        if core.cfg.console_port != 0 {
            console::start(std::sync::Arc::clone(&core));
        }
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
        "verify" => cmd_verify(&config::Config::load(), args.get(2).map(|a| a.as_str()) == Some("all")),
        "ledger" => cmd_ledger(&config::Config::load()),
        "reconcile" => cmd_reconcile(&config::Config::load(), args.get(2).map(|a| a.as_str()) == Some("rebase")),
        "update-check" => update::cmd_check(&args[2..]),
        "update-verify" => update::cmd_verify(&args[2..]),
        "report" => cmd_report(&config::Config::load(), args.get(2).and_then(|d| d.parse().ok()).unwrap_or(7)),
        _ => {
            eprintln!("usage: pisoportal [serve|box|reconcile [rebase]|verify|ledger|report [days]|update-check|update-verify|selftest|version]");
            2
        }
    };
    std::process::exit(code);
}
