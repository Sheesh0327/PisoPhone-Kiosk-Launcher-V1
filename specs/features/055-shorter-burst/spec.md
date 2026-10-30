# Feature 055: a shorter bursting window for the speed caps

Status: **approved by the owner 2026-10-30 ("go, build the installer change directly on main"); applied directly on main like feature 052 (an installer setting only; main carries the correctly built programs)**   Branch: `feat/055-shorter-burst`   Area: setup (the router installer's openNDS settings)
Backlog rows: none (owner's speed test 2026-10-30)   Release: router (raises `setup/RELEASE`; goes through `beta`, `CLAUDE.md` section 5)

## Intent
With bursting on, openNDS lets a customer run at full speed until their average over the rate window goes above the cap. The window is 2 checks of 15 seconds, so about 30 seconds.
The owner's first Ookla test on an Endurance phone showed 50 Mbps against a 5 Mbps cap and the second 3 to 4 Mbps: the cap works, but a fresh session bursts for half a minute.
The owner chose to shorten the window to about 15 seconds. Serves rule 1 only in the sense that what is sold (Endurance is slower) matches what the customer first sees.

## Invariants touched
- Money and time: **not touched.** No payment, credit, expiry, ledger or queue code; only an openNDS setting that is written by the installer.
- Only owner-signed software: **kept.** The change ships inside the signed setup file like every installer change; no new download or key.

## Constraints
- One value changes: `opennds.@opennds[0].ratecheckwindow` from `'2'` to `'1'` in `setup/src/30-router-settings.sh` (line 252). `checkinterval` stays `'15'`; both bursting switches stay `'1'` (bursting is not turned off: pages and small downloads stay quick).
- Generated files are rebuilt, never edited by hand: `python3 tools/build_piso_setup.py` (`setup/piso-setup*.sh`, `website/setup/*`); `--check` must pass. A change under `setup/src/` changes what routers install: raise `setup/RELEASE` (1.5.34).
- Existing routers get the new value only when they update (the installer's settings are applied again at install and at update; the plan checks that the update path does it, `setup/src/80-commands.sh:235`). No router is touched remotely.
- No change to the portal program, the console, the page, the firmware or the app.
- Restarting openNDS ends the connections of customers online for a moment; the update already does that for other settings. Nothing new is asked of the shop, but the plan says so in the release notes.

## What a visitor without the condition sees
- A customer: nothing changes in the page or the payment; only the speed pattern. A short burst now lasts about 15 seconds instead of about 30.
- A HyperSpeed customer (no cap): nothing changes.
- A router that has not updated: the old window (30 seconds) until it does.
- A PisoPhone-only shop (no router portal): not affected.

## Success criteria
1. The installer writes `ratecheckwindow='1'`, `checkinterval='15'` and both bursting switches `'1'`: a test on the generated settings batch (`router/tests/test_setup.py`) fails first with the old value and passes after.
2. An update on a router that has the old value changes it to `'1'` (the settings test with a fake `uci` that starts from `'2'`; or the plan says why the existing update test already shows it).
3. `python3 tools/build_piso_setup.py --check` passes; the router and setup tests (`test_setup`, `test_pisophone_setup`, `test_setup_web`, `test_setup_tools`, `test_monitor`, `test_selfupdate`, `test_footprint`) pass; `bash scripts/check_security_rules.sh` passes.
4. Hardware **RS65** (owner's, on the 360T6M): after the update, `ndsctl status` shows "Rate Check Window: 1 check intervals (15s)" and openNDS runs; an Endurance phone's first speed test is lower than before (a 10 to 20 second test may still show some burst: say so in the result), and a long download settles near the cap within about 15 seconds. Not run here.
5. Money and time not touched: `git diff main` shows only `setup/src/30-router-settings.sh`, the generated setup files, `setup/RELEASE`, tests and documents.

## Out of scope
- Turning bursting off, changing the caps (5 and 2 Mbps), `checkinterval`, installing `tc`, `sqm-scripts` or any package, and the status line on the console (feature 054, closed).
- Per-customer or per-plan different windows.

## Owner decisions
None open: the owner chose "about 15 seconds" (`ratecheckwindow` 1). If RS65 shows a speed test still bursts too much, the next step is a 5-second check interval or no bursting, as a separate change.
