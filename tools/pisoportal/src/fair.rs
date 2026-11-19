//! Fair use (HyperSpeed only): after the limit of traffic the connection is slowed for a few minutes, then released for a
//! few minutes, and so on until the session ends. Endurance is already speed-capped and is never touched.
use crate::core::{log, Core, FairState};
use crate::pricing::Plan;
use crate::roll;
use crate::util::now_secs;
use std::sync::Arc;

pub fn tick(core: &Arc<Core>) {
    // before any session is read: a clock that was just set right moves the sessions timed with the stale one first (RT-01)
    core.check_clock();
    let cfg = &core.cfg;
    let now = now_secs();
    let mut authed = std::collections::HashSet::new();
    for (mac, state, kb) in core.nds.clients() {
        if state == "Preauthenticated" {
            core.heal(&mac);
            continue;
        }
        if state != "Authenticated" {
            continue;
        }
        authed.insert(mac.clone());
        let (s, expired) = {
            let _g = core.files.lock().unwrap();
            match roll::session_active(&cfg.roll_path(), now, &mac) {
                Some(s) => (Some(s), false),
                None => (None, roll::is_expired(&cfg.roll_path(), now, &mac)),
            }
        };
        let Some(s) = s else {
            if expired && core.nds.deauth_if_authenticated(&mac) {
                log!("expired session {} deauthenticated", mac);
            }
            continue;
        };
        if s.plan != Plan::Hyper {
            continue;
        }
        let mut fs_ = {
            let mut st = core.st.lock().unwrap();
            let e = st.fair.entry(mac.clone()).or_default();
            FairState { used_kb: e.used_kb, offset_kb: e.offset_kb, throttled: e.throttled, since: e.since }
        };
        if kb < fs_.offset_kb {
            fs_.offset_kb = 0; // the counters restarted
        }
        let total = fs_.used_kb + kb - fs_.offset_kb;
        let mut next = FairState { used_kb: total, offset_kb: kb, throttled: fs_.throttled, since: fs_.since };
        if total >= cfg.fair_kb {
            if !fs_.throttled && now.saturating_sub(fs_.since) >= cfg.fair_full_min * 60 {
                if core.nds.grant(&mac, s.minutes, cfg.fair_down, cfg.fair_up, s.qdown, s.qup) {
                    log!("fair use: {} slowed after {} MB", mac, total / 1024);
                    next = FairState { used_kb: total, offset_kb: 0, throttled: true, since: now };
                }
            } else if fs_.throttled
                && now.saturating_sub(fs_.since) >= cfg.fair_throttle_min * 60
                && core.nds.grant(&mac, s.minutes, s.down, s.up, s.qdown, s.qup)
            {
                log!("fair use: {} back to full speed", mac);
                next = FairState { used_kb: total, offset_kb: 0, throttled: false, since: now };
            }
        }
        core.st.lock().unwrap().fair.insert(mac, next);
    }
    // Also heal active non-paused clients that openNDS removed on expiry so they are not stranded in state ""
    let missing: Vec<String> = {
        let _g = core.files.lock().unwrap();
        roll::load(&cfg.roll_path())
            .into_iter()
            .filter(|e| !e.paused() && e.left(now) > 0 && !authed.contains(&e.mac))
            .map(|e| e.mac)
            .collect()
    };
    for mac in missing {
        core.heal(&mac);
    }
}

pub fn run(core: Arc<Core>) {
    let mut last_reconcile: Option<std::time::Instant> = None;
    loop {
        std::thread::sleep(std::time::Duration::from_secs(core.cfg.fair_interval));
        tick(&core);
        core.maintenance();
        // the box's counter restarts when its revenue is collected: notice it within minutes, monitor or not
        if last_reconcile.is_none_or(|t| t.elapsed().as_secs() >= core.cfg.reconcile_interval) {
            last_reconcile = Some(std::time::Instant::now());
            if let (_, Some(note)) = crate::reconcile::run(&core.cfg, false) {
                log!("reconcile: {}", note);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::util::now_secs;

    #[cfg(unix)]
    #[test]
    fn fair_tick_heals_active_roll_client_missing_from_opennds() {
        use std::os::unix::fs::PermissionsExt;
        let d = std::env::temp_dir().join(format!("pisoportal-fair-missing-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let state_file = d.join("state");
        let log_file = d.join("calls.log");
        std::fs::write(&state_file, "").unwrap(); // evicted / deleted from openNDS client list
        let script = d.join("nds.sh");
        let sh = format!(
            "#!/bin/sh\necho \"$*\" >> \"{log}\"\nSF=\"{sf}\"\ncase \"$1\" in\n  json)\n    if [ -n \"$2\" ]; then printf '{{\"state\":\"%s\"}}\\n' \"$(cat \"$SF\")\"; else printf '{{\"clients\":{{}}}}\\n'; fi\n    ;;\n  auth) printf 'Authenticated' > \"$SF\"; echo \"ok\" ;;\nesac\n",
            log = log_file.display(),
            sf = state_file.display()
        );
        std::fs::write(&script, sh).unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();

        let mut cfg = Config::from_map(std::collections::HashMap::new());
        cfg.data_dir = d.to_string_lossy().into_owned();
        cfg.ndsctl = script.to_string_lossy().into_owned();
        let core = Core::new(cfg);
        let mac = "aa:bb:cc:00:00:99";
        let now = now_secs();
        let e = roll::Entry::parse(&format!("c1,0,0,0,0,60,{},{},0,0,0,hyper,w1,10,60,0,1", now, mac)).unwrap();
        roll::save(&core.cfg.roll_path(), &[e]).unwrap();

        tick(&core);

        let calls = std::fs::read_to_string(&log_file).unwrap();
        assert!(calls.contains(&format!("auth {} ", mac)), "fair::tick must heal paid client deleted from openNDS: {}", calls);
        let _ = std::fs::remove_dir_all(&d);
    }
}
