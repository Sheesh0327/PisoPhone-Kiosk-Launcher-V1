# Feature 054: the console says whether the speed limits are really enforced

Status: **draft (owner request 2026-10-30, waiting for approval)**   Branch: `feat/054-speed-limit-status`   Area: router (the portal program, the console page and script)
Backlog rows: none (owner question 2026-10-30: "is the PisoWiFi speed limit accurate?")   Release: router (raises `setup/RELEASE`; goes through `beta`, `CLAUDE.md` section 5)

## Intent
A shop sells the Endurance plan as "slower but cheaper": 5 Mbps down and 2 Mbps up by default (`ENDURANCE_DOWN_KBPS`, `ENDURANCE_UP_KBPS`). The portal only *asks* openNDS for that speed
(`ndsctl auth … <up> <down> …`, `nds.rs:83`); openNDS and the router have to enforce it. Nothing in the project measures whether they do. On the owner's test router the
`tc` command did not even exist, and the installer does not check for it, so the cap can silently do nothing while the shop keeps selling it. This feature adds one line to the console's
Overview that says, from what customers' phones really did, whether the caps look enforced. It changes nothing on the router and nothing a customer sees. Serves rule 1 (the shop's offer matches what is delivered)
and the goal that the owner does not have to be a programmer to find out.

## Invariants touched
- Money and time: **not touched.** Read-only: no `ndsctl auth`, no payment, credit, expiry, deduction or ledger code is called or changed. A wrong verdict can mislead the owner but cannot change what anyone paid or got.
- Only owner-signed software: **kept.** No new download, no new key, no new place that loads code. The router program keeps `sha2`, `hmac` and `p256` only (no new crate).

## Constraints
- Observation only. The check never changes a customer's speed, never restarts openNDS, never edits `/etc/config/opennds`; whether to make caps strict (bursting off) is a separate decision (out of scope).
- What it can see is what the console already reads: the per-customer byte counters from `ndsctl json` (the console turns them into a speed in `clients()`, `console.rs:476`), the plan of each customer from the roll, and the cap in the settings.
  A cap observed over a window is compared with the cap; **bursting is on** (`download_unrestricted_bursting` and `upload_unrestricted_bursting` are `1`, `checkinterval` 15 s, `ratecheckwindow` 2, `setup/src/30-router-settings.sh:249`), so openNDS lets a customer run faster than the cap for a short while and holds the *average*. The rule must not call that "not enforced".
- State lives in the router's memory only (like the Internet line graph); nothing is written to flash; at most 64 customers tracked; the sampler adds no more than one `ndsctl json` every 30 s beyond what already runs.
- The page and the signed console script are released separately: a new page with an older script, and the new script on an older page, both still start (as in feature 053); the old script simply shows no tile.
- Router program size: mipsel stays under 1,200 KB (1,091 KB today), installer under 1,700 KB (1,647 KB today); `router/tests/test_footprint.py` is the gate.
- Plain English in the console (owner terms: "Speed limits: working / looks wrong / not enough use yet / no limit set"); no jargon like tc or ifb on the front page; the detail line may say "ask your installer".

## What changes (proposal; the owner decides in "Owner decisions")
- A new tile on the Overview, **Speed limits**, with one of four states:
  1. **No limit set**: the Endurance caps are 0 (none) in the settings. Neutral colour; nothing to check.
  2. **Not enough use yet**: no Endurance customer has downloaded enough in the last hour to tell (needs at least 2 MB moved in a 60-second stretch after their first 60 seconds online). Neutral. This is the usual state in a quiet shop.
  3. **Working**: at least one Endurance customer moved enough data and their average stayed within 125% of the cap, in both directions that moved enough.
  4. **Looks wrong**: an Endurance customer's average over 5 minutes in a row stayed above 150% of the cap in a direction that moved data steadily. The detail line names how many customers and the highest speed seen, and says "The speed limit may not be working on this router. Ask your installer to check it." (Bursting cannot cause this: a burst is shorter than the 5-minute window.)
- The state is kept for the last hour; a "Working" or "Looks wrong" result stays on the tile for an hour after the last evidence, with the time ("seen 12 minutes ago").
- Overview data gains one field (`limits`) in `/api/overview`; the page draws the tile; the signed script draws it from that field (script version 13).
- A new hardware check **RS65** in `docs/REAL_WORLD_TESTING.md`.

## What a visitor without the condition sees
- Not signed in: nothing (the console shows only the sign-in card, unchanged).
- A shop where both Endurance caps are 0 (no cap): the tile says "No limit set" (state 1) and never "Looks wrong".
- A shop with PisoPhone only (no router portal, so no console): not applicable, nothing changes.
- An older page or script (releases before this one): no tile; the rest of the Overview is exactly as it is.
- HyperSpeed customers (no cap by design): never counted, never warned about.
- A customer who is paused (1 kbit/s crawl) or in a fair-use slow-down: not counted for Endurance verdicts (their cap is a different number).

## Success criteria
1. The verdict is a pure function of a list of samples and the caps, and unit tests (Rust) cover every state and the edges: no cap; below the data threshold; within 125%; above 150% for 4 minutes (still "working") and for 5 minutes (looks wrong); a short burst of 10 times the cap for 30 seconds (still "working"); a customer who left; the first 60 seconds ignored; paused and fair-use customers ignored; HyperSpeed customers ignored; a counter that goes backwards (a customer who reconnected) resets, never a negative speed.
2. A browser test with the fake openNDS (customers with chosen byte counters over time, a fast-forwarded clock) shows each of the four tiles on the Overview at 1280 and 390 px, light and dark, with contrast 4.5 : 1 or better, and no sideways scrolling.
3. `/api/overview` without a session still answers 401; the new field is present only after sign-in; the fields that exist today keep their names and meaning (`test_console.py` unchanged apart from the new field).
4. Compatibility: the new page with the earlier script (v12) starts, shows every screen and no tile; the new script (v13) on the page of release 1.5.33 starts and shows every screen and no tile (the tile's place does not exist there).
5. No write: with the fake `ndsctl` logging every command, a run of the whole check issues only `json` (read) commands, never `auth`, `deauth` or `block`.
6. Resources: at most 64 customers tracked; memory use bounded; nothing written under `DATA_DIR` by this feature (a test lists the data directory before and after).
7. Program size and footprint budgets hold (`router/tests/test_footprint.py`), real numbers from CI on `beta` recorded in `validation.md`.
8. `cargo fmt --check`, `cargo clippy --release --all-targets -- -D warnings`, `cargo test`, the router and setup tests and `bash scripts/check_security_rules.sh` pass; the new browser test also passes with the wide DejaVu font forced (both failures of feature 053 on CI came from skipping these two local checks).
9. Hardware **RS65** on the 360T6M (owner's): (a) with an Endurance phone downloading a 100 MB file for 6 minutes the tile reaches "Working" and the speed is near the cap; (b) on a router where `tc` is missing or the cap is not applied, the tile reaches "Looks wrong" (if your router does enforce the cap this half cannot be seen, and the unit and browser tests are the evidence); (c) no customer's speed or session changed because of the tile. Not run here.

## Out of scope
- Making caps strict, switching bursting off, installing `tc`, `kmod-ifb` or `sqm-scripts`, or any change to `/etc/config/opennds` (a later feature, after the owner has the evidence this one produces).
- Gaming Priority, SQM or CAKE, per-game rules, a total-bandwidth limit for the whole shop.
- Alerting by Telegram when the state is "Looks wrong" (see owner decision 3).
- HyperSpeed's fair-use slow-down accuracy (own feature 004-era code; same observation method could be added later).
- Measuring the speed the shop's internet connection provides.

## Owner decisions
1. **Thresholds.** My proposal: "Working" up to 125% of the cap; "Looks wrong" above 150% for 5 minutes in a row (the bursting window is about 30 seconds, so 5 minutes leaves a wide margin); at least 2 MB in a 60-second stretch to count. Agree, or stricter (for example 120% and 3 minutes)?
2. **One tile or per-customer marks.** My proposal: one tile on the Overview only. Do you also want a small "over the limit" chip on the Customers screen next to a customer who is over?
3. **Telegram alert.** The router monitor already sends alerts through Telegram (one a day at most). Should "Looks wrong" send one too, or only show on the console? My proposal: console only in this feature; the alert can follow once the rule has proved itself on a real router.
4. **Static facts on the tile's detail line.** Should the detail line also say whether the `tc` command exists on the router (a file check, no commands run) and that bursting is on? My proposal: yes, one short line ("Bursting is on: short fast bursts are normal"); the `tc` file check only when the state is "Looks wrong".
5. **Group 0 (needs your router).** Before I build, I need three outputs from the router that sells PisoWiFi, to see which fields real openNDS gives: `ndsctl json` (one client), `ndsctl status`, `tc -s qdisc show` (or "not found"). I will not guess the field names.
