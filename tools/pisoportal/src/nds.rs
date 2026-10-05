//! openNDS through ndsctl: grant (auth), deauth, state, client counters.
use crate::util::jget;
use std::process::Command;

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
        let out = self.run(&["auth", mac, &minutes.to_string(), &up.to_string(), &down.to_string(), &qup.to_string(), &qdown.to_string(), "pisoportal"]);
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
