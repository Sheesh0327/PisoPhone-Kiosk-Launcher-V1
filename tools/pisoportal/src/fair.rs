//! Fair use (HyperSpeed only): after the limit of traffic the connection is slowed for a few minutes, then released for a
//! few minutes, and so on until the session ends. Endurance is already speed-capped and is never touched.
use crate::core::{log, Core, FairState};
use crate::pricing::Plan;
use crate::roll;
use crate::util::now_secs;
use std::sync::Arc;

pub fn tick(core: &Arc<Core>) {
    let cfg = &core.cfg;
    let now = now_secs();
    for (mac, state, kb) in core.nds.clients() {
        if state != "Authenticated" {
            continue;
        }
        let Some(s) = ({
            let _g = core.files.lock().unwrap();
            roll::session(&cfg.roll_path(), now, &mac)
        }) else {
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
}

pub fn run(core: Arc<Core>) {
    loop {
        std::thread::sleep(std::time::Duration::from_secs(core.cfg.fair_interval));
        tick(&core);
        core.maintenance();
    }
}
