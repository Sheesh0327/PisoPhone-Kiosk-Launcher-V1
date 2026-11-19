//! openNDS through ndsctl: grant (auth), deauth, state, client counters.
use crate::util::jget;
use std::process::Command;

/// One client as `ndsctl json` lists it (kB are this session's counters).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ClientInfo {
    pub mac: String,
    pub ip: String,
    pub state: String,
    pub dl_kb: u64,
    pub ul_kb: u64,
    pub duration: u64,
}

/// The clients in the text `ndsctl json` printed (one "key": value per line, a block per client; the last key of a block closes it).
pub fn parse_clients(text: &str) -> Vec<ClientInfo> {
    let mut cur = ClientInfo::default();
    let mut v = Vec::new();
    for line in text.lines() {
        let l = line.trim();
        let val = |k: &str| -> Option<String> {
            let p = format!("\"{}\":", k);
            let i = l.find(&p)?;
            Some(l[i + p.len()..].trim().trim_start_matches('"').split('"').next()?.trim_end_matches(',').to_string())
        };
        if let Some(m) = val("mac") {
            if !cur.mac.is_empty() && cur.mac != m {
                cur = ClientInfo::default();
            }
            cur.mac = m.to_lowercase();
        }
        if let Some(x) = val("ip") {
            cur.ip = x;
        }
        if let Some(x) = val("state") {
            cur.state = x;
        }
        if let Some(x) = val("duration") {
            cur.duration = x.parse().unwrap_or(0);
        }
        if let Some(x) = val("download_this_session") {
            cur.dl_kb = x.parse().unwrap_or(0);
        }
        if let Some(x) = val("upload_this_session") {
            cur.ul_kb = x.parse().unwrap_or(0);
            if !cur.mac.is_empty() {
                v.push(std::mem::take(&mut cur));
            }
        }
    }
    v
}

pub struct Nds {
    pub ndsctl: String,
    lock: std::sync::Mutex<()>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EnsureResult {
    AlreadyOnline,
    Granted,
    Refused,
}

impl Nds {
    pub fn new(ndsctl: String) -> Self {
        Nds { ndsctl, lock: std::sync::Mutex::new(()) }
    }

    fn run(&self, args: &[&str]) -> String {
        for attempt in 0..8 {
            let out = Command::new(&self.ndsctl).args(args).output();
            let text = match out {
                Ok(o) => format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr)),
                Err(e) => return format!("Failed: {}", e),
            };
            if text.contains("locked") && attempt < 7 {
                std::thread::sleep(std::time::Duration::from_millis(150)); // openNDS is busy with another request (asked again soon: a customer waits)
                continue;
            }
            return text;
        }
        String::new()
    }

    fn state_unlocked(&self, mac: &str) -> String {
        let out = self.run(&["json", mac]);
        jget(&out, "state")
    }

    fn auth_unlocked(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64) -> bool {
        // ndsctl auth <mac> <session minutes> <upload kbit/s> <download kbit/s> <upload quota kB> <download quota kB> <custom>
        let out = self.run(&[
            "auth",
            mac,
            &minutes.to_string(),
            &up.to_string(),
            &down.to_string(),
            &qup.to_string(),
            &qdown.to_string(),
            "pisoportal",
        ]);
        !out.contains("Failed")
    }

    fn deauth_unlocked(&self, mac: &str) {
        self.run(&["deauth", mac]);
    }

    fn arp_entry(arp: &str, mac: &str) -> Option<(std::net::Ipv4Addr, bool)> {
        arp.lines().skip(1).find_map(|l| {
            let mut cols = l.split_whitespace();
            let ip_str = cols.next()?;
            let _hw = cols.next()?;
            let flags = cols.next()?;
            let mac_str = cols.next()?;
            mac_str.eq_ignore_ascii_case(mac).then(|| ip_str.parse::<std::net::Ipv4Addr>().ok().map(|ip| (ip, flags == "0x2"))).flatten()
        })
    }

    /// Ping an incomplete ARP entry from /proc/net/arp with a 1-byte UDP discard packet and wait briefly for resolution.
    /// Leaves already-valid (0x2) entries alone so a STALE neighbor is not pushed into PROBE right before `libopennds.sh clientaddress`.
    fn wake_arp(mac: &str) {
        let Ok(arp) = std::fs::read_to_string("/proc/net/arp") else { return };
        let Some((ip, valid)) = Self::arp_entry(&arp, mac) else { return };
        if valid {
            return;
        }
        if let Ok(sock) = std::net::UdpSocket::bind("0.0.0.0:0") {
            let _ = sock.send_to(b"\0", (ip, 9));
        }
        for _ in 0..8 {
            std::thread::sleep(std::time::Duration::from_millis(25));
            let Ok(arp_now) = std::fs::read_to_string("/proc/net/arp") else { break };
            if Self::arp_entry(&arp_now, mac).is_some_and(|(_, v)| v) {
                break;
            }
        }
    }

    /// "Authenticated" | "Preauthenticated" | ""
    pub fn state(&self, mac: &str) -> String {
        self.state_unlocked(mac)
    }

    pub fn deauth(&self, mac: &str) {
        let _g = self.lock.lock().unwrap();
        self.deauth_unlocked(mac);
    }

    /// Deauthenticate `mac` only if OpenNDS currently holds an `Authenticated` session for it.
    pub fn deauth_if_authenticated(&self, mac: &str) -> bool {
        let _g = self.lock.lock().unwrap();
        if self.state_unlocked(mac) == "Authenticated" {
            self.deauth_unlocked(mac);
            true
        } else {
            false
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn grant_locked(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64, force_reauth: bool) -> EnsureResult {
        let _g = self.lock.lock().unwrap();
        let cur = self.state_unlocked(mac);
        if cur == "Authenticated" {
            if !force_reauth {
                return EnsureResult::AlreadyOnline;
            }
            self.deauth_unlocked(mac);
        } else if cur.is_empty() {
            Self::wake_arp(mac);
        }
        if !self.auth_unlocked(mac, minutes, down, up, qdown, qup) {
            return EnsureResult::Refused;
        }
        if self.state_unlocked(mac) == "Authenticated" {
            EnsureResult::Granted
        } else {
            EnsureResult::Refused
        }
    }

    /// Grant (or re-grant: a plain auth of an authenticated client is ignored, so it is deauthenticated first), then check
    /// what openNDS really did, not what it answered.
    pub fn grant(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64) -> bool {
        self.grant_locked(mac, minutes, down, up, qdown, qup, true) == EnsureResult::Granted
    }

    /// Grant only if openNDS does not already hold an `Authenticated` session for `mac` (never deauths a live session).
    pub fn ensure_granted(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64) -> EnsureResult {
        self.grant_locked(mac, minutes, down, up, qdown, qup, false)
    }

    /// Every client with its address and counters.
    pub fn detail(&self) -> Vec<ClientInfo> {
        parse_clients(&self.run(&["json"]))
    }

    /// (mac, state, kB moved this session) of every client.
    pub fn clients(&self) -> Vec<(String, String, u64)> {
        let out = self.run(&["json"]);
        let (mut mac, mut st, mut dl) = (String::new(), String::new(), 0u64);
        let mut v = Vec::new();
        for line in out.lines() {
            let l = line.trim();
            let val = |k: &str| -> Option<String> {
                let p = format!("\"{}\":", k);
                let i = l.find(&p)?;
                Some(l[i + p.len()..].trim().trim_start_matches('"').split('"').next()?.trim_end_matches(',').to_string())
            };
            if let Some(m) = val("mac") {
                mac = m;
            }
            if let Some(s) = val("state") {
                st = s;
            }
            if let Some(d) = val("download_this_session") {
                dl = d.parse().unwrap_or(0);
            }
            if let Some(u) = val("upload_this_session") {
                v.push((mac.clone(), st.clone(), dl + u.parse::<u64>().unwrap_or(0)));
            }
        }
        v
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clients_are_read_from_ndsctl_json() {
        let text = r#"{
"client_list_length":2,
"clients":{
"aa:bb:cc:00:00:01":{
"id":1,
"ip":"10.0.30.5",
"mac":"AA:BB:CC:00:00:01",
"state":"Authenticated",
"duration":120,
"download_this_session":2048,
"download_session_avg":1.5,
"upload_this_session":512,
"upload_session_avg":0.4
},
"aa:bb:cc:00:00:02":{
"id":2,
"ip":"10.0.30.6",
"mac":"aa:bb:cc:00:00:02",
"state":"Preauthenticated",
"download_this_session":0,
"upload_this_session":0
}
}
}"#;
        let c = parse_clients(text);
        assert_eq!(c.len(), 2);
        assert_eq!(
            (c[0].mac.as_str(), c[0].ip.as_str(), c[0].state.as_str(), c[0].dl_kb, c[0].ul_kb, c[0].duration),
            ("aa:bb:cc:00:00:01", "10.0.30.5", "Authenticated", 2048, 512, 120)
        );
        assert_eq!((c[1].state.as_str(), c[1].dl_kb), ("Preauthenticated", 0));
    }

    #[cfg(unix)]
    #[test]
    fn nds_grant_mutex_serializes_concurrent_threads() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("pisoportal-nds-lock-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let state_file = dir.join("state");
        std::fs::write(&state_file, "Authenticated").unwrap();
        let script = dir.join("mock_ndsctl.sh");
        let sh = format!(
            "#!/bin/sh\nSF=\"{}\"\ncase \"$1\" in\n  json) printf '{{\"state\":\"%s\"}}\\n' \"$(cat \"$SF\")\" ;;\n  deauth) sleep 0.02; printf 'Preauthenticated' > \"$SF\" ;;\n  auth)\n    cur=$(cat \"$SF\")\n    if [ \"$cur\" != \"Preauthenticated\" ]; then echo \"Failed\"; exit 0; fi\n    sleep 0.02\n    printf 'Authenticated' > \"$SF\"\n    echo \"Client $2 authenticated.\"\n    ;;\nesac\n",
            state_file.display()
        );
        std::fs::write(&script, sh).unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();

        let nds = std::sync::Arc::new(Nds::new(script.to_string_lossy().into_owned()));
        let mut handles = Vec::new();
        for _ in 0..4 {
            let n = std::sync::Arc::clone(&nds);
            handles.push(std::thread::spawn(move || n.grant("aa:bb:cc:00:00:01", 60, 5000, 2000, 0, 0)));
        }
        for h in handles {
            assert!(h.join().unwrap(), "concurrent grant() must be serialized so deauth/auth never interleave");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn arp_entry_parses_valid_and_incomplete_flags() {
        let arp = "\
IP address       HW type     Flags       HW address            Mask     Device
10.0.30.58       0x1         0x2         ee:86:42:7f:6d:48     *        br-guest
10.0.30.102      0x1         0x0         72:1c:e8:d2:b1:f9     *        br-guest
";
        assert_eq!(Nds::arp_entry(arp, "ee:86:42:7f:6d:48"), Some(("10.0.30.58".parse().unwrap(), true)));
        assert_eq!(Nds::arp_entry(arp, "72:1c:e8:d2:b1:f9"), Some(("10.0.30.102".parse().unwrap(), false)));
        assert_eq!(Nds::arp_entry(arp, "aa:bb:cc:dd:ee:ff"), None);
    }

    #[cfg(unix)]
    #[test]
    fn ensure_granted_never_deauths_authenticated_client() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("pisoportal-nds-ensure-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let state_file = dir.join("state");
        let log_file = dir.join("calls.log");
        std::fs::write(&state_file, "Preauthenticated").unwrap();
        let script = dir.join("mock_ndsctl.sh");
        let sh = format!(
            "#!/bin/sh\necho \"$*\" >> \"{log}\"\nSF=\"{sf}\"\ncase \"$1\" in\n  json) printf '{{\"state\":\"%s\"}}\\n' \"$(cat \"$SF\")\" ;;\n  deauth) printf 'Preauthenticated' > \"$SF\"; echo \"Deauthenticated\" ;;\n  auth) sleep 0.04; printf 'Authenticated' > \"$SF\"; echo \"Client $2 authenticated.\" ;;\nesac\n",
            log = log_file.display(),
            sf = state_file.display()
        );
        std::fs::write(&script, sh).unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();

        let nds = std::sync::Arc::new(Nds::new(script.to_string_lossy().into_owned()));
        let n1 = std::sync::Arc::clone(&nds);
        let h1 = std::thread::spawn(move || n1.grant("aa:bb:cc:00:00:01", 60, 5000, 2000, 0, 0));
        std::thread::sleep(std::time::Duration::from_millis(10));
        assert_eq!(nds.ensure_granted("aa:bb:cc:00:00:01", 60, 5000, 2000, 0, 0), EnsureResult::AlreadyOnline);
        assert!(h1.join().unwrap());

        let log = std::fs::read_to_string(&log_file).unwrap();
        assert!(!log.contains("deauth"), "ensure_granted must never deauth an already-authenticated client: {}", log);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
