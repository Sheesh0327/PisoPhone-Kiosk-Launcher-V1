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
}

impl Nds {
    fn run(&self, args: &[&str]) -> String {
        for attempt in 0..4 {
            let out = Command::new(&self.ndsctl).args(args).output();
            let text = match out {
                Ok(o) => format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr)),
                Err(e) => return format!("Failed: {}", e),
            };
            if text.contains("locked") && attempt < 3 {
                std::thread::sleep(std::time::Duration::from_millis(500)); // openNDS is busy with another request
                continue;
            }
            return text;
        }
        String::new()
    }

    /// "Authenticated" | "Preauthenticated" | ""
    pub fn state(&self, mac: &str) -> String {
        let out = self.run(&["json", mac]);
        jget(&out, "state")
    }

    pub fn auth(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64) -> bool {
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

    pub fn deauth(&self, mac: &str) {
        self.run(&["deauth", mac]);
    }

    /// Grant (or re-grant: a plain auth of an authenticated client is ignored, so it is deauthenticated first), then check
    /// what openNDS really did, not what it answered.
    pub fn grant(&self, mac: &str, minutes: u64, down: u32, up: u32, qdown: u64, qup: u64) -> bool {
        if self.state(mac) == "Authenticated" {
            self.deauth(mac);
        }
        if !self.auth(mac, minutes, down, up, qdown, qup) {
            return false;
        }
        self.state(mac) == "Authenticated"
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
}
