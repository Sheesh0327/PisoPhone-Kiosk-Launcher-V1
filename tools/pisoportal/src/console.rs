//! The owner's console: one friendly page for the router, in place of LuCI. Who is connected and how much they use, the rental phones, the
//! money, and the health of the internet and the coin box. It runs inside this program (nothing more to install), signs in with the
//! router's own root password (checked by `ubus call session login`, which is what LuCI does) and keeps its history in memory only.
//!
//! Routes: `/` the page; `/api/login`, `/api/logout`; `/api/overview`, `/api/clients`, `/api/phones` (needs a session); `/api/kick` (POST).
use crate::boxlink::{with_port, BoxLink};
use crate::core::Core;
use crate::http::{bind_retry, read_request, respond, respond_bytes, Incoming, Request, BRAND_LOGO, BRAND_MASCOT};
use crate::nds::ClientInfo;
use crate::pricing::{parse_tier_list, Plan, Pricing, MAX_KBPS, MAX_PRORATA_MIN};
use crate::util::{b64_encode, jget, json_esc, now_secs, random_hex, sha256_hex, valid_mac};
use std::collections::{HashMap, VecDeque};
use std::io::Read;
use std::net::TcpStream;
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::Duration;

const UI: &str = include_str!("console.html");
/// The console page. The tab that shows the coin box's own page is named "Coin box" on a PisoWiFi-only router and "Rental phones"
/// otherwise; its id (`ph`) and the page inside do not change.
fn page_html(product: &str) -> String {
    UI.replace("@PHTAB@", if product == "wifi" { "Coin box" } else { "Rental phones" })
}

const UI_JS: &str = include_str!("console-ui.js");
const VERIFY_JS: &str = include_str!("console-verify.js");
const SESSION_IDLE: u64 = 30 * 60;
const MAX_SESSIONS: usize = 16;
const LOGIN_FAILS: u32 = 5;
const LOCK_SECS: u64 = 60;
const SAMPLE_EVERY: u64 = 5;
const SAMPLES_KEPT: usize = 180; // 15 minutes of the internet line, in memory only

// 'unsafe-eval' is for one thing only: running a newer page script from the website AFTER its signature was checked against the owner
// key (src/console.html). Anything unsigned is never run.
const CSP: &str =
    "Content-Security-Policy: default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; \
img-src 'self' data:; connect-src 'self' https://pisophone.pages.dev; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n";

/// The console page's policy: the same, and it may show the coin box's page (passed through by boxview.rs one port up) in a frame.
fn page_csp(req: &Request, console_port: u16) -> String {
    match req.header("host").and_then(crate::boxview::host_of) {
        Some(h) => CSP.replace(
            "; frame-ancestors 'none'",
            &format!("; frame-src http://{}:{}; frame-ancestors 'none'", h, console_port.saturating_add(1)),
        ),
        None => CSP.to_string(),
    }
}

#[derive(Clone, Copy)]
struct Sample {
    t: u64,
    rx: u64,
    tx: u64,
}

pub struct Console {
    pub(crate) core: Arc<Core>,
    sessions: Mutex<HashMap<String, u64>>,
    fails: Mutex<HashMap<String, (u32, u64)>>,
    wan: Mutex<VecDeque<Sample>>,
    prev: Mutex<HashMap<String, (u64, u64, u64)>>,
}

// ---- reading the router (pure parts first: they are tested) -----------------------------------------------------------------------

/// The interface of the default route in /proc/net/route text.
pub fn default_route_if(text: &str) -> Option<String> {
    text.lines().skip(1).find_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        (f.len() > 2 && f[1] == "00000000").then(|| f[0].to_string())
    })
}

/// (received, sent) bytes of an interface in /proc/net/dev text.
pub fn netdev_bytes(text: &str, iface: &str) -> Option<(u64, u64)> {
    text.lines().find_map(|l| {
        let (name, rest) = l.split_once(':')?;
        if name.trim() != iface {
            return None;
        }
        let f: Vec<&str> = rest.split_whitespace().collect();
        Some((f.first()?.parse().ok()?, f.get(8)?.parse().ok()?))
    })
}

/// mac -> host name from dnsmasq's lease file ("expiry mac ip name id").
pub fn parse_leases(text: &str) -> HashMap<String, String> {
    text.lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split_whitespace().collect();
            (f.len() >= 4 && f[3] != "*").then(|| (f[1].to_lowercase(), f[3].to_string()))
        })
        .collect()
}

/// Seconds east of UTC from an OpenWrt TZ string such as "PST-8" (POSIX counts the other way), 0 when unknown.
pub fn tz_offset_secs(tz: &str) -> i64 {
    let mut t = tz.trim();
    if t.starts_with('<') {
        t = t.split_once('>').map(|(_, r)| r).unwrap_or("");
    }
    let Some(start) = t.find(|c: char| c.is_ascii_digit() || c == '+' || c == '-') else { return 0 };
    let tail = &t[start..];
    let (sign, num) = match tail.strip_prefix('-') {
        Some(n) => (1, n),
        None => (-1, tail.strip_prefix('+').unwrap_or(tail)),
    };
    let digits: String = num.chars().take_while(|c| c.is_ascii_digit()).collect();
    sign * digits.parse::<i64>().unwrap_or(0) * 3600
}

/// (pesos today, pesos ever, pesos of the last 7 days oldest first) from the revenue ledger text.
#[allow(dead_code)]
pub fn revenue_stats(text: &str, now: u64, tz: i64) -> (u64, u64, [u64; 7]) {
    let (today, total, week, _, _) = revenue_period_stats(text, now, tz);
    (today, total, week)
}

/// (pesos today, pesos ever, pesos of the last 7 days oldest first, week total, month total) from the revenue ledger text.
pub fn revenue_period_stats(text: &str, now: u64, tz: i64) -> (u64, u64, [u64; 7], u64, u64) {
    let day0 = |t: u64| ((t as i64 + tz).div_euclid(86400)) * 86400 - tz;
    let today = day0(now);
    let (mut total, mut week, mut month_total) = (0u64, [0u64; 7], 0u64);
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 3 {
            continue;
        }
        let (Ok(t), Ok(p)) = (f[0].parse::<u64>(), f[2].parse::<u64>()) else { continue };
        total += p;
        let back = (today - day0(t)) / 86400;
        if (0..7).contains(&back) {
            week[6 - back as usize] += p;
        }
        if (0..30).contains(&back) {
            month_total += p;
        }
    }
    let week_total: u64 = week.iter().sum();
    (week[6], total, week, week_total, month_total)
}

/// One day of the sales report, in pesos by how the money came in: `coin` coins that bought time at once, `vcoin` coins that bought a
/// voucher code (income at the moment the coins were counted), `voucher` printed slips of earlier releases (booked when redeemed).
/// `n` payments in all, `vi` of them voucher codes issued.
#[derive(Debug, PartialEq, Clone)]
pub struct SalesDay {
    pub day: String,
    pub coin: u64,
    pub vcoin: u64,
    pub voucher: u64,
    pub n: u64,
    pub vi: u64,
}

/// The sales report's totals by kind: pesos (as in `SalesDay`) and the number of voucher codes issued.
#[derive(Debug, PartialEq, Clone, Copy, Default)]
pub struct Kinds {
    pub coin: u64,
    pub vcoin: u64,
    pub voucher: u64,
    pub vi: u64,
}

/// Sales per day for the last `days` days (oldest first, days without sales included), from the revenue ledger text:
/// (rows, pesos per plan (hyper, endurance), totals per kind). Redeeming a voucher code writes no row, so it is not here.
pub fn sales_report(text: &str, now: u64, tz: i64, days: u64) -> (Vec<SalesDay>, (u64, u64), Kinds) {
    let days = days.clamp(1, 366);
    let day_no = |t: u64| (t as i64 + tz).div_euclid(86400);
    let today = day_no(now);
    let mut rows: Vec<SalesDay> = (0..days as i64)
        .map(|i| SalesDay { day: crate::util::civil(today - (days as i64 - 1) + i), coin: 0, vcoin: 0, voucher: 0, n: 0, vi: 0 })
        .collect();
    let (mut hyper, mut endurance, mut k) = (0u64, 0u64, Kinds::default());
    for l in text.lines() {
        let f: Vec<&str> = l.split(',').collect();
        if f.len() < 5 {
            continue;
        }
        let (Ok(t), Ok(p)) = (f[0].parse::<u64>(), f[2].parse::<u64>()) else { continue };
        let back = today - day_no(t);
        if back < 0 || back >= days as i64 {
            continue;
        }
        let row = &mut rows[(days as i64 - 1 - back) as usize];
        row.n += 1;
        match f[4] {
            "voucher" => {
                row.voucher += p;
                k.voucher += p;
            }
            "vbuy" => {
                row.vcoin += p;
                row.vi += 1;
                k.vcoin += p;
                k.vi += 1;
            }
            _ => {
                row.coin += p;
                k.coin += p;
            }
        }
        if f[1] == "endurance" {
            endurance += p;
        } else {
            hyper += p;
        }
    }
    (rows, (hyper, endurance), k)
}

fn kbps(prev: Option<(u64, u64)>, now_t: u64, now_b: u64) -> u64 {
    match prev {
        Some((t, b)) if now_t > t && now_b >= b => (now_b - b) * 8 / 1000 / (now_t - t), // bytes -> kbit/s
        _ => 0,
    }
}

fn read(path: &str) -> String {
    std::fs::read_to_string(path).unwrap_or_default()
}

pub(crate) fn conf_value(path: &str, key: &str) -> String {
    read(path)
        .lines()
        .find_map(|l| l.strip_prefix(&format!("{}=", key)).map(|v| v.trim().trim_matches('\'').trim_matches('"').to_string()))
        .unwrap_or_default()
}

/// One plain HTTP GET with Basic auth (the coin box's dashboard API).
fn http_get_auth(addr: &str, path: &str, user: &str, pass: &str) -> Result<String, String> {
    use std::io::Write;
    use std::net::ToSocketAddrs;
    let sock = addr.to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("no address")?;
    let mut s = TcpStream::connect_timeout(&sock, Duration::from_secs(3)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(4))).ok();
    let auth = b64_encode(format!("{}:{}", user, pass).as_bytes());
    write!(s, "GET {} HTTP/1.0\r\nHost: box\r\nAuthorization: Basic {}\r\nConnection: close\r\n\r\n", path, auth)
        .map_err(|e| e.to_string())?;
    let mut buf = Vec::new();
    let _ = s.take(262_144).read_to_end(&mut buf);
    let text = String::from_utf8_lossy(&buf).into_owned();
    let (head, body) = text.split_once("\r\n\r\n").unwrap_or(("", &text));
    if head.starts_with("HTTP/1.0 401") || head.starts_with("HTTP/1.1 401") {
        return Err("PASSWORD".into());
    }
    Ok(body.to_string())
}

impl Console {
    fn new(core: Arc<Core>) -> Arc<Console> {
        Arc::new(Console {
            core,
            sessions: Mutex::new(HashMap::new()),
            fails: Mutex::new(HashMap::new()),
            wan: Mutex::new(VecDeque::new()),
            prev: Mutex::new(HashMap::new()),
        })
    }

    fn wan_if(&self) -> String {
        default_route_if(&read(&self.core.cfg.route_file)).unwrap_or_default()
    }

    fn sample_wan(&self) {
        let iface = self.wan_if();
        if let Some((rx, tx)) = netdev_bytes(&read(&self.core.cfg.netdev_file), &iface) {
            let mut w = self.wan.lock().unwrap();
            w.push_back(Sample { t: now_secs(), rx, tx });
            while w.len() > SAMPLES_KEPT {
                w.pop_front();
            }
        }
    }

    /// The router's root password, checked the way LuCI does it.
    fn password_ok(&self, password: &str) -> bool {
        if password.is_empty() || password.len() > 128 {
            return false;
        }
        let args = format!("{{\"username\":\"root\",\"password\":\"{}\",\"timeout\":5}}", json_esc(password));
        match Command::new(&self.core.cfg.ubus).args(["call", "session", "login", &args]).output() {
            Ok(o) => String::from_utf8_lossy(&o.stdout).contains("ubus_rpc_session"),
            Err(_) => false,
        }
    }

    fn locked(&self, ip: &str) -> u64 {
        let now = now_secs();
        match self.fails.lock().unwrap().get(ip) {
            Some((n, at)) if *n >= LOGIN_FAILS && now < *at + LOCK_SECS => *at + LOCK_SECS - now,
            _ => 0,
        }
    }

    fn fail(&self, ip: &str) {
        let now = now_secs();
        let mut f = self.fails.lock().unwrap();
        let e = f.entry(ip.to_string()).or_insert((0, now));
        if now > e.1 + LOCK_SECS {
            *e = (0, now);
        }
        e.0 += 1;
        e.1 = now;
        if f.len() > 256 {
            f.retain(|_, (_, at)| now < *at + LOCK_SECS);
        }
    }

    fn new_session(&self) -> String {
        let now = now_secs();
        let mut s = self.sessions.lock().unwrap();
        s.retain(|_, last| now < *last + SESSION_IDLE);
        if s.len() >= MAX_SESSIONS {
            if let Some(oldest) = s.iter().min_by_key(|(_, t)| **t).map(|(k, _)| k.clone()) {
                s.remove(&oldest);
            }
        }
        let token = random_hex(32);
        s.insert(token.clone(), now);
        token
    }

    pub(crate) fn session_ok(&self, req: &Request) -> bool {
        let Some(cookie) = req.header("cookie") else { return false };
        let Some(token) = cookie.split(';').filter_map(|p| p.trim().strip_prefix("piso_console=")).next() else { return false };
        let now = now_secs();
        let mut s = self.sessions.lock().unwrap();
        match s.get_mut(token) {
            Some(last) if now < *last + SESSION_IDLE => {
                *last = now;
                true
            }
            _ => false,
        }
    }

    // ---- the data ------------------------------------------------------------------------------------------------------------

    fn overview(&self) -> String {
        let cfg = &self.core.cfg;
        let now = now_secs();
        let clients = self.core.nds.detail();
        let online = clients.iter().filter(|c| c.state == "Authenticated").count();
        let waiting = clients.len() - online;
        let (rx_kbps, tx_kbps, rx_total, tx_total, hist) = {
            let w = self.wan.lock().unwrap();
            let n = w.len();
            let last = w.back().copied();
            let before = if n >= 2 { Some(w[n - 2]) } else { None };
            let rate = |a: Option<Sample>, b: Option<Sample>, f: fn(&Sample) -> u64| match (a, b) {
                (Some(a), Some(b)) => kbps(Some((b.t, f(&b))), a.t, f(&a)),
                _ => 0,
            };
            let mut hist = String::new();
            let step = (n / 60).max(1);
            for i in (step..n).step_by(step) {
                let (a, b) = (w[i], w[i - step]);
                if !hist.is_empty() {
                    hist.push(',');
                }
                hist.push_str(&format!("[{},{},{}]", a.t, kbps(Some((b.t, b.rx)), a.t, a.rx), kbps(Some((b.t, b.tx)), a.t, a.tx)));
            }
            (
                rate(last, before, |s| s.rx),
                rate(last, before, |s| s.tx),
                last.map(|s| s.rx).unwrap_or(0),
                last.map(|s| s.tx).unwrap_or(0),
                hist,
            )
        };
        let tz = tz_offset_secs(&read("/etc/TZ"));
        let (today, mut total, week, week_total, month_total) =
            revenue_period_stats(&crate::ledger::read_recent(&cfg.revenue_path(), now.saturating_sub(40 * 86400)), now, tz);
        if !crate::ledger::closed_segments(&cfg.revenue_path()).is_empty() {
            total = crate::ledger::verify(&cfg.revenue_path()).map(|v| v.1).unwrap_or(total);
            // the carry row holds the months before
        }
        let link = BoxLink::new(&cfg.gw_box, &cfg.gw_key).remember_in(&format!("{}/box_signed", cfg.data_dir));
        let box_ok = link.challenge().is_some();
        let box_signed = link.signs_answers();
        let (ledger_ok, ledger_lines) = match crate::ledger::verify(&cfg.revenue_path()) {
            Ok((n, _)) => (true, n),
            Err(l) => (false, l),
        };
        let mem = read("/proc/meminfo")
            .lines()
            .find_map(|l| l.strip_prefix("MemAvailable:").map(|v| v.trim().trim_end_matches("kB").trim().to_string()))
            .unwrap_or_default();
        let uptime = read("/proc/uptime").split_whitespace().next().unwrap_or("0").split('.').next().unwrap_or("0").to_string();
        format!(
            "{{\"version\":\"{}\",\"now\":{},\"online\":{},\"waiting\":{},\"wan_if\":\"{}\",\"rx_kbps\":{},\"tx_kbps\":{},\"rx_total\":{},\"tx_total\":{},\"history\":[{}],\
             \"today\":{},\"week_total\":{},\"month_total\":{},\"total\":{},\"week\":[{}],\"box_ok\":{},\"box_signed\":{},\"ledger_ok\":{},\"ledger_lines\":{},\"mem_kb\":{},\"uptime\":{}}}",
            env!("CARGO_PKG_VERSION"),
            now,
            online,
            waiting,
            json_esc(&self.wan_if()),
            rx_kbps,
            tx_kbps,
            rx_total,
            tx_total,
            hist,
            today,
            week_total,
            month_total,
            total,
            week.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(","),
            box_ok,
            box_signed,
            ledger_ok,
            ledger_lines,
            if mem.is_empty() { "0".into() } else { mem },
            uptime
        )
    }

    fn clients(&self) -> String {
        let cfg = &self.core.cfg;
        let now = now_secs();
        let names = parse_leases(&read(&cfg.dhcp_leases));
        let list: Vec<ClientInfo> = self.core.nds.detail();
        let mut prev = self.prev.lock().unwrap();
        let mut rows: Vec<(u64, String)> = Vec::new();
        for c in &list {
            let (dl_kbps, ul_kbps) = match prev.get(&c.mac) {
                Some((t, d, u)) if now > *t && c.dl_kb >= *d && c.ul_kb >= *u => {
                    ((c.dl_kb - d) * 8 / (now - t), (c.ul_kb - u) * 8 / (now - t))
                }
                _ => (0, 0),
            };
            let (plan, left, paused) = match crate::roll::peek_state(&cfg.roll_path(), now, &c.mac) {
                Some(p) => (p.plan.as_str().to_string(), p.left, p.paused),
                None => (String::new(), 0, false),
            };
            rows.push((
                dl_kbps + ul_kbps,
                format!(
                    "{{\"mac\":\"{}\",\"ip\":\"{}\",\"name\":\"{}\",\"state\":\"{}\",\"plan\":\"{}\",\"left\":{},\"dl_kb\":{},\"ul_kb\":{},\"dl_kbps\":{},\"ul_kbps\":{},\"duration\":{},\"paused\":{}}}",
                    json_esc(&c.mac),
                    json_esc(&c.ip),
                    json_esc(names.get(&c.mac).map(|s| s.as_str()).unwrap_or("")),
                    json_esc(&c.state),
                    plan,
                    left,
                    c.dl_kb,
                    c.ul_kb,
                    dl_kbps,
                    ul_kbps,
                    c.duration,
                    paused
                ),
            ));
        }
        prev.clear();
        for c in &list {
            prev.insert(c.mac.clone(), (now, c.dl_kb, c.ul_kb));
        }
        rows.sort_by_key(|r| std::cmp::Reverse(r.0));
        format!("{{\"clients\":[{}]}}", rows.into_iter().map(|r| r.1).collect::<Vec<_>>().join(","))
    }

    fn phones(&self) -> String {
        let cfg = &self.core.cfg;
        let pass = conf_value(&cfg.setup_conf, "BOX_ADMIN_PASS");
        if pass.is_empty() {
            return "{\"error\":\"NO_PASSWORD\"}".into();
        }
        match http_get_auth(&with_port(&cfg.gw_box), "/api/status", "admin", &pass) {
            Ok(body) if body.trim_start().starts_with(['{', '[']) => body,
            Ok(_) => "{\"error\":\"BAD_ANSWER\"}".into(),
            Err(e) if e == "PASSWORD" => "{\"error\":\"PASSWORD\"}".into(),
            Err(_) => "{\"error\":\"BOX_DOWN\"}".into(),
        }
    }
}

// ---- the server ---------------------------------------------------------------------------------------------------------------------

/// The owner's console settings (voucher choice at payment, mini-game minutes a day).
fn settings_json(core: &Core) -> String {
    format!(
        "{{\"voucher_choice\":{},\"game_minutes\":{},\"file_send\":{},\"file_send_key\":{}}}",
        core.voucher_choice(),
        core.games.limit_secs() / 60,
        core.send.switch_on(),
        core.send.has_key()
    )
}

/// The prices in force, for the console's Settings page (tiers as [pesos, minutes]; speeds in kbit/s, 0 = no cap).
fn pricing_json(core: &Core) -> String {
    let pr = core.pricing();
    let list = |t: &[(u32, u32)]| t.iter().map(|(p, m)| format!("[{},{}]", p, m)).collect::<Vec<_>>().join(",");
    format!(
        "{{\"ok\":true,\"hyper\":[{}],\"prorata\":{},\"endurance\":[{}],\"down\":{},\"up\":{},\"changed\":{}}}",
        list(&pr.hyper_tiers),
        pr.hyper_prorata_min,
        list(&pr.endurance_tiers),
        pr.endurance_down,
        pr.endurance_up,
        *pr != Pricing::from_config(&core.cfg)
    )
}

fn body_of(req: &Request, conn: &mut Incoming) -> String {
    let len: usize = req.header("content-length").and_then(|v| v.parse().ok()).unwrap_or(0).min(4096);
    let mut buf = vec![0u8; len];
    let mut got = 0;
    while got < len {
        match conn.read(&mut buf[got..]) {
            Ok(0) | Err(_) => break,
            Ok(n) => got += n,
        }
    }
    String::from_utf8_lossy(&buf[..got]).into_owned()
}

fn same_origin(req: &Request) -> bool {
    match (req.header("origin"), req.header("host")) {
        (Some(o), Some(h)) => o.trim_start_matches("http://") == h,
        (None, _) => true,
        _ => false,
    }
}

fn json(conn: &mut Incoming, code: &str, body: &str, extra: &str) {
    respond(&mut conn.s, code, "application/json", body, &format!("Cache-Control: no-store\r\n{}{}", CSP, extra));
}

fn handle(c: &Console, req: Request, mut conn: Incoming) {
    let ip = conn.s.peer_addr().map(|a| a.ip().to_string()).unwrap_or_default();
    let head_only = req.method == "HEAD";
    // a request's body is always read, whatever the answer: closing a connection with unread bytes resets it and the client sees an error
    let body = if req.method == "POST" { body_of(&req, &mut conn) } else { String::new() };
    match (req.method.as_str(), req.path.as_str()) {
        ("GET" | "HEAD", "/") => {
            let page = page_html(&c.core.cfg.shop_product).replace("@OWNER_KEY@", &crate::update::owner_key());
            respond_bytes(
                &mut conn.s,
                "200 OK",
                "text/html; charset=utf-8",
                page.as_bytes(),
                &format!("Cache-Control: no-store\r\n{}", page_csp(&req, c.core.cfg.console_port)),
                !head_only,
            )
        }
        ("GET" | "HEAD", "/ui.js") => {
            respond_bytes(&mut conn.s, "200 OK", "application/javascript", UI_JS.as_bytes(), "Cache-Control: no-store\r\n", !head_only)
        }
        ("GET" | "HEAD", "/verify.js") => {
            respond_bytes(&mut conn.s, "200 OK", "application/javascript", VERIFY_JS.as_bytes(), "Cache-Control: no-cache\r\n", !head_only)
        }
        ("GET" | "HEAD", "/brand.png") => {
            respond_bytes(&mut conn.s, "200 OK", "image/png", BRAND_LOGO, "Cache-Control: public, max-age=86400\r\n", !head_only)
        }
        ("GET" | "HEAD", "/mascot.png") => {
            respond_bytes(&mut conn.s, "200 OK", "image/png", BRAND_MASCOT, "Cache-Control: public, max-age=86400\r\n", !head_only)
        }
        ("POST", "/api/login") => {
            if req.header("x-piso") != Some("1") || !same_origin(&req) {
                return json(&mut conn, "403 Forbidden", "{\"error\":\"FORBIDDEN\"}", "");
            }
            let wait = c.locked(&ip);
            if wait > 0 {
                return json(&mut conn, "429 Too Many Requests", &format!("{{\"error\":\"LOCKED\",\"retry\":{}}}", wait), "");
            }
            if c.password_ok(&crate::util::jget(&body, "password").replace("\\\"", "\"").replace("\\\\", "\\")) {
                let token = c.new_session();
                json(
                    &mut conn,
                    "200 OK",
                    "{\"ok\":true}",
                    &format!("Set-Cookie: piso_console={}; Path=/; HttpOnly; SameSite=Strict\r\n", token),
                );
            } else {
                c.fail(&ip);
                std::thread::sleep(Duration::from_millis(400)); // slows guessing
                json(&mut conn, "401 Unauthorized", "{\"error\":\"PASSWORD\"}", "");
            }
        }
        (m, path) if path.starts_with("/api/") => {
            if !c.session_ok(&req) {
                return json(&mut conn, "401 Unauthorized", "{\"error\":\"LOGIN\"}", "");
            }
            if m == "POST" && (req.header("x-piso") != Some("1") || !same_origin(&req)) {
                return json(&mut conn, "403 Forbidden", "{\"error\":\"FORBIDDEN\"}", "");
            }
            match (m, path) {
                ("GET", "/api/overview") => json(&mut conn, "200 OK", &c.overview(), ""),
                ("GET", "/api/clients") => json(&mut conn, "200 OK", &c.clients(), ""),
                ("GET", "/api/phones") => json(&mut conn, "200 OK", &c.phones(), ""),
                ("POST", "/api/logout") => {
                    if let Some(t) =
                        req.header("cookie").and_then(|k| k.split(';').filter_map(|p| p.trim().strip_prefix("piso_console=")).next())
                    {
                        c.sessions.lock().unwrap().remove(t);
                    }
                    json(&mut conn, "200 OK", "{\"ok\":true}", "Set-Cookie: piso_console=; Path=/; Max-Age=0\r\n");
                }
                ("GET", "/api/sales") => {
                    let days: u64 = crate::util::query_get(&req.query, "days").and_then(|d| d.parse().ok()).unwrap_or(30);
                    let (now, tz) = (now_secs(), tz_offset_secs(&read("/etc/TZ")));
                    let (rows, (h, e), k) = sales_report(
                        &crate::ledger::read_recent(&c.core.cfg.revenue_path(), now.saturating_sub((days + 2) * 86400)),
                        now,
                        tz,
                        days,
                    );
                    let list: Vec<String> = rows
                        .iter()
                        .map(|r| {
                            format!(
                                "{{\"d\":\"{}\",\"coin\":{},\"vcoin\":{},\"voucher\":{},\"n\":{},\"vi\":{}}}",
                                r.day, r.coin, r.vcoin, r.voucher, r.n, r.vi
                            )
                        })
                        .collect();
                    // vouchers redeemed in the period (spending one is time use: no revenue row, so they come from the voucher records)
                    let first_day = (((now as i64 + tz).div_euclid(86400) - (days.clamp(1, 366) as i64 - 1)) * 86400 - tz).max(0) as u64;
                    let (redeemed, waiting) = c.core.voucher_counts(first_day);
                    json(
                        &mut conn,
                        "200 OK",
                        &format!(
                            "{{\"days\":[{}],\"plans\":{{\"hyper\":{},\"endurance\":{}}},\"kinds\":{{\"coin\":{},\"vcoin\":{},\"voucher\":{},\"vi\":{}}},\"vouchers\":{{\"redeemed\":{},\"waiting\":{}}}}}",
                            list.join(","),
                            h,
                            e,
                            k.coin,
                            k.vcoin,
                            k.voucher,
                            k.vi,
                            redeemed,
                            waiting
                        ),
                        "",
                    )
                }
                ("GET", "/api/vouchers") => json(&mut conn, "200 OK", &c.core.vouchers_json(), ""),
                ("GET", "/api/wheel") => json(&mut conn, "200 OK", &c.core.wheel.console_json(), ""),
                ("POST", "/api/wheel") => {
                    // the table and the limits are checked first; the switch comes last, and turning on needs the phrase and the warning version
                    let w = &c.core.wheel;
                    let bad = |conn: &mut Incoming, code: &str, name: &str, why: &str| {
                        json(conn, code, &format!("{{\"error\":\"{}\",\"why\":\"{}\"}}", name, crate::util::json_esc(why)), "")
                    };
                    let has = |k: &str| body.contains(&format!("\"{}\"", k));
                    if has("table") {
                        if let Err(e) = w.set_table(&jget(&body, "table")) {
                            return bad(&mut conn, "400 Bad Request", "BAD_TABLE", &e);
                        }
                    }
                    if ["max_bet", "floor", "spins_per_day", "wait", "win_cap"].iter().any(|k| has(k)) {
                        let mut l = w.limits();
                        let set = |key: &str, to: &mut u64| -> Result<(), String> {
                            if has(key) {
                                *to = jget(&body, key).parse::<u64>().map_err(|_| format!("{} is not a number", key))?;
                            }
                            Ok(())
                        };
                        let (mut mb, mut fl, mut sp, mut wt, mut wc) =
                            (l.max_bet as u64, l.floor as u64, l.spins_per_day as u64, l.wait_secs, l.win_cap_per_day as u64);
                        let parsed = set("max_bet", &mut mb)
                            .and_then(|_| set("floor", &mut fl))
                            .and_then(|_| set("spins_per_day", &mut sp))
                            .and_then(|_| set("wait", &mut wt))
                            .and_then(|_| set("win_cap", &mut wc));
                        if let Err(e) = parsed {
                            return bad(&mut conn, "400 Bad Request", "BAD_LIMITS", &e);
                        }
                        let small = |v: u64| u32::try_from(v).unwrap_or(u32::MAX);
                        (l.max_bet, l.floor, l.spins_per_day, l.wait_secs, l.win_cap_per_day) =
                            (small(mb), small(fl), small(sp), wt, small(wc));
                        if let Err(e) = w.set_limits(l) {
                            return bad(&mut conn, "400 Bad Request", "BAD_LIMITS", &e);
                        }
                    }
                    if jget(&body, "off") == "1" {
                        if !w.turn_off(now_secs()) {
                            return bad(&mut conn, "500 Internal Server Error", "IO", "The router could not save the setting.");
                        }
                    } else if jget(&body, "on") == "1" {
                        let label: String = sha256_hex(req.header("cookie").unwrap_or("").as_bytes()).chars().take(8).collect();
                        let version = jget(&body, "version").parse::<u32>().unwrap_or(0);
                        if let Err(why) = w.turn_on(now_secs(), &jget(&body, "phrase"), version, &label) {
                            return bad(&mut conn, "403 Forbidden", "CONFIRM", &why);
                        }
                    }
                    c.core.wheel_changed();
                    let mut out = w.console_json();
                    out.insert_str(1, "\"ok\":true,");
                    json(&mut conn, "200 OK", &out, "")
                }
                ("GET", "/api/settings") => json(&mut conn, "200 OK", &settings_json(&c.core), ""),
                ("POST", "/api/settings") => {
                    // (only the settings named in the body change)
                    let (vc, gm, fs) = (jget(&body, "voucher_choice"), jget(&body, "game_minutes"), jget(&body, "file_send"));
                    if vc.is_empty() && gm.is_empty() && fs.is_empty() {
                        return json(&mut conn, "400 Bad Request", "{\"error\":\"NOTHING\"}", "");
                    }
                    if !gm.is_empty() {
                        match gm.parse::<u32>() {
                            Ok(m) if m <= crate::game::MAX_MINUTES => {
                                if !c.core.set_game_minutes(m) {
                                    return json(&mut conn, "500 Internal Server Error", "{\"error\":\"IO\"}", "");
                                }
                            }
                            _ => {
                                let why = format!("the mini-game minutes must be a number from 0 to {}", crate::game::MAX_MINUTES);
                                return json(
                                    &mut conn,
                                    "400 Bad Request",
                                    &format!("{{\"error\":\"BAD_MINUTES\",\"why\":\"{}\"}}", why),
                                    "",
                                );
                            }
                        }
                    }
                    if !fs.is_empty() && !c.core.set_file_send(crate::config::parse_switch(&fs, true)) {
                        return json(&mut conn, "500 Internal Server Error", "{\"error\":\"IO\"}", "");
                    }
                    if !vc.is_empty() && !c.core.set_voucher_choice(crate::config::parse_switch(&vc, true)) {
                        return json(&mut conn, "500 Internal Server Error", "{\"error\":\"IO\"}", "");
                    }
                    let mut out = settings_json(&c.core);
                    out.insert_str(1, "\"ok\":true,");
                    json(&mut conn, "200 OK", &out, "")
                }
                ("GET", "/api/pricing") => json(&mut conn, "200 OK", &pricing_json(&c.core), ""),
                ("POST", "/api/pricing") => {
                    // (a full form, or only the fields named; "reset" goes back to the settings file's prices)
                    if jget(&body, "reset") == "1" {
                        c.core.reset_pricing();
                        return json(&mut conn, "200 OK", &pricing_json(&c.core), "");
                    }
                    let mut pr = (*c.core.pricing()).clone();
                    let has = |k: &str| body.contains(&format!("\"{}\"", k));
                    let bad = |conn: &mut Incoming, why: String| {
                        json(
                            conn,
                            "400 Bad Request",
                            &format!("{{\"error\":\"BAD_PRICES\",\"why\":\"{}\"}}", crate::util::json_esc(&why)),
                            "",
                        )
                    };
                    for (key, plan) in [("hyper", Plan::Hyper), ("endurance", Plan::Endurance)] {
                        if !has(key) {
                            continue;
                        }
                        match parse_tier_list(&jget(&body, key)) {
                            Ok(t) if plan == Plan::Hyper => pr.hyper_tiers = t,
                            Ok(t) => pr.endurance_tiers = t,
                            Err(e) => return bad(&mut conn, e),
                        }
                    }
                    for (key, max) in [("prorata", MAX_PRORATA_MIN), ("down", MAX_KBPS), ("up", MAX_KBPS)] {
                        if !has(key) {
                            continue;
                        }
                        let Ok(v) = jget(&body, key).parse::<u32>() else { return bad(&mut conn, format!("{} is not a number", key)) };
                        if v > max {
                            return bad(&mut conn, format!("{} is too large (the most is {})", key, max));
                        }
                        match key {
                            "prorata" => pr.hyper_prorata_min = v,
                            "down" => pr.endurance_down = v,
                            _ => pr.endurance_up = v,
                        }
                    }
                    match c.core.set_pricing(pr) {
                        Ok(()) => json(&mut conn, "200 OK", &pricing_json(&c.core), ""),
                        Err(e) => bad(&mut conn, e),
                    }
                }
                ("POST", "/api/vouchers/void") => {
                    if c.core.voucher_void(&jget(&body, "batch")) {
                        json(&mut conn, "200 OK", "{\"ok\":true}", "")
                    } else {
                        json(&mut conn, "404 Not Found", "{\"error\":\"NO_BATCH\"}", "")
                    }
                }
                ("POST", "/api/kick") => {
                    let mac = jget(&body, "mac").to_lowercase();
                    if !valid_mac(&mac) {
                        return json(&mut conn, "400 Bad Request", "{\"error\":\"BAD_MAC\"}", "");
                    }
                    c.core.nds.deauth(&mac);
                    json(&mut conn, "200 OK", "{\"ok\":true}", "");
                }
                _ => json(&mut conn, "404 Not Found", "{\"error\":\"NOT_FOUND\"}", ""),
            }
        }
        _ => respond(&mut conn.s, "404 Not Found", "text/plain", "not found", ""),
    }
}

pub fn start(core: Arc<Core>) {
    let console = Console::new(core);
    crate::boxview::start(Arc::clone(&console));
    let sampler = Arc::clone(&console);
    std::thread::spawn(move || loop {
        sampler.sample_wan();
        std::thread::sleep(Duration::from_secs(SAMPLE_EVERY));
    });
    std::thread::spawn(move || {
        let cfg = &console.core.cfg;
        let listener = bind_retry(&format!("{}:{}", cfg.console_bind, cfg.console_port), "console");
        let limits = crate::http::console_limits();
        for stream in listener.incoming().flatten() {
            // over the limit (in all, or from one address): turned away before a thread is started
            let Some(slot) = stream.peer_addr().ok().and_then(|a| limits.take(a.ip())) else { continue };
            let c = Arc::clone(&console);
            std::thread::spawn(move || {
                let _slot = slot;
                if let Some((req, conn)) = read_request(stream, Duration::from_secs(5)) {
                    handle(&c, req, conn);
                }
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_coin_box_tab_is_named_for_the_product() {
        let wifi = page_html("wifi");
        assert!(wifi.contains(r#"<button data-t="ph">Coin box</button>"#) && !wifi.contains("Rental phones"));
        let both = page_html("both");
        assert!(both.contains(r#"<button data-t="ph">Rental phones</button>"#) && !both.contains("Coin box</button>"));
        assert!(!wifi.contains("@PHTAB@") && !both.contains("@PHTAB@"));
        assert!(wifi.contains(r#"id="t-ph""#) && wifi.contains(r#"id="boxf""#), "the tab id and the page inside it do not change");
    }

    #[test]
    fn the_internet_line_is_found_and_counted() {
        let route = "Iface\tDestination\tGateway\tFlags\nbr-lan\t0000000A\t00000000\t0001\neth1\t00000000\t0101A8C0\t0003\n";
        assert_eq!(default_route_if(route).as_deref(), Some("eth1"));
        let dev = "Inter-|   Receive\n face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n  eth1: 1000 5 0 0 0 0 0 0 2000 6 0 0 0 0 0 0\n   lo: 1 1 0 0 0 0 0 0 1 1 0 0 0 0 0 0\n";
        assert_eq!(netdev_bytes(dev, "eth1"), Some((1000, 2000)));
        assert_eq!(netdev_bytes(dev, "wan"), None);
        assert_eq!(kbps(Some((10, 1000)), 15, 1000 + 625_000), 1000); // 625000 bytes in 5 s = 1000 kbit/s
        assert_eq!(kbps(None, 15, 5), 0);
    }

    #[test]
    fn names_come_from_the_lease_file() {
        let leases = "1791858393 84:c7:bb:e1:57:60 10.0.0.10 pisocoinbox 01:84\n1791858999 AA:BB:CC:00:00:01 10.0.30.5 * 01:aa\n1791859000 aa:bb:cc:00:00:02 10.0.30.6 Rowels-iPhone 01:aa";
        let n = parse_leases(leases);
        assert_eq!(n.get("84:c7:bb:e1:57:60").map(|s| s.as_str()), Some("pisocoinbox"));
        assert_eq!(n.get("aa:bb:cc:00:00:02").map(|s| s.as_str()), Some("Rowels-iPhone"));
        assert!(!n.contains_key("aa:bb:cc:00:00:01"));
    }

    #[test]
    fn the_sales_report_counts_coins_and_vouchers_per_day() {
        let now = 1_791_900_000u64;
        let day = |back: u64| now - back * 86400;
        let text = format!(
            "{},hyper,5,30,new,x\n{},endurance,10,480,voucher,x\n{},hyper,3,18,topup,x\n{},hyper,1,6,new,x\n{},hyper,10,60,vbuy,x\n{},endurance,2,30,vbuy,x\nbad\n",
            now,
            day(1),
            day(1),
            day(40),
            now,
            day(2)
        );
        let (rows, (h, e), k) = sales_report(&text, now, 0, 7);
        assert_eq!(rows.len(), 7);
        assert_eq!((rows[6].coin, rows[6].vcoin, rows[6].voucher, rows[6].n, rows[6].vi), (5, 10, 0, 2, 1));
        assert_eq!((rows[5].coin, rows[5].vcoin, rows[5].voucher, rows[5].n, rows[5].vi), (3, 0, 10, 2, 0));
        assert_eq!((rows[4].coin, rows[4].vcoin, rows[4].n, rows[4].vi), (0, 2, 1, 1), "a voucher bought with coins is income that day");
        assert_eq!(rows[0].n, 0, "days without sales are listed");
        // the payment from 40 days ago is outside the range; a voucher bought with coins counts for its plan
        assert_eq!((h, e), (5 + 3 + 10, 10 + 2));
        assert_eq!(k, Kinds { coin: 8, vcoin: 12, voucher: 10, vi: 2 });
        assert_eq!(rows[6].day.len(), 10);
        assert!(rows[6].day > rows[5].day);
    }

    #[test]
    fn the_time_zone_and_the_money_of_today() {
        assert_eq!(tz_offset_secs("PST-8"), 8 * 3600);
        assert_eq!(tz_offset_secs("EST5EDT"), -5 * 3600);
        assert_eq!(tz_offset_secs("<+08>-8"), 8 * 3600);
        assert_eq!(tz_offset_secs("UTC"), 0);
        assert_eq!(tz_offset_secs(""), 0);
        let tz = 8 * 3600;
        let now = 1_791_900_000u64; // some moment; build lines relative to it
        let day = |back: u64| now - back * 86400;
        let text = format!(
            "{},hyper,5,30,new,x\n{},endurance,17,690,new,x\n{},hyper,3,18,new,x\n{},hyper,1,6,new,x\nbad line\n",
            now,
            day(1),
            day(3),
            day(40)
        );
        let (today, total, week) = revenue_stats(&text, now, tz);
        assert_eq!((today, total), (5, 26));
        assert_eq!(week, [0, 0, 0, 3, 0, 17, 5]);
        let (today_p, total_p, week_p, week_total, month_total) = revenue_period_stats(&text, now, tz);
        assert_eq!((today_p, total_p, week_p), (today, total, week));
        assert_eq!(week_total, 25);
        assert_eq!(month_total, 25);
    }
}
