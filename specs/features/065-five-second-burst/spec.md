# Feature 065: a 5-second bursting window for the speed caps

Status: **in progress**   Branch: `feat/065-five-second-burst`   Area: setup (the router installer's openNDS settings)
Backlog rows: follow-up to feature 055 (`specs/features/055-shorter-burst/spec.md` owner decision note)   Release: router (raises `setup/RELEASE` to 1.5.37)

## Intent
Feature 055 shortened the openNDS rate-check window from 30 seconds (`ratecheckwindow='2'`, `checkinterval='15'`) to 15 seconds (`ratecheckwindow='1'`, `checkinterval='15'`). With a 15-second check interval, an Endurance user (5 Mbps down / 2 Mbps up) on a fast WAN link can still consume their entire 15-second byte allowance (~9.375 MB) in under 2 seconds and experience up to ~13 seconds of dropped packets before the next check window starts, while a paused session (1 kbps) refreshes its ~1.875 KB allowance every 15 seconds.

Reducing `opennds.@opennds[0].checkinterval` from `'15'` to `'5'` (with `ratecheckwindow='1'`) shortens the burst and evaluation window to **5 seconds**:
- Endurance (5 Mbps) bursts at most ~3.125 MB per 5-second window, eliminating long 10–13 second TCP stalls while keeping snappy page loads.
- Fair-use throttled HyperSpeed (2 Mbps down / 1 Mbps up) and Paused (1 kbps, ~625 bytes per 5s) sessions are enforced over 5-second windows.

## Invariants touched
- Money and time: **not touched.** No payment, credit, expiry, ledger, or coin-queue logic is modified; only the openNDS `checkinterval` UCI setting written by the router installer changes.
- Only owner-signed software: **kept.** Ships inside the signed router setup script like Feature 055.

## Constraints
- One UCI setting changes in `setup/src/30-router-settings.sh` (`portal_uci`, line 251): `opennds.@opennds[0].checkinterval` from `'15'` to `'5'`. `ratecheckwindow` stays `'1'`; `download_unrestricted_bursting` and `upload_unrestricted_bursting` stay `'1'`.
- Generated installer files are rebuilt, never edited by hand: `python3 tools/build_piso_setup.py` (`setup/piso-setup*.sh`, `website/update/*`); `python3 tools/build_piso_setup.py --check` must pass.
- Because `setup/src/` changes what routers install, raise `setup/RELEASE` from `1.5.36` to `1.5.37`.
- Existing routers receive `checkinterval='5'` when they run `piso-setup update` (`setup/src/80-commands.sh:237` runs `portal_uci`, sees the `uci changes opennds` diff, commits it, and restarts `opennds`).
- No change to the `pisoportal` Rust binary, router console, captive portal page, ESP32 firmware, or Android app.

## What a visitor without the condition sees
- An Endurance customer or throttled HyperSpeed customer: web pages still burst quickly (~3.1 MB per 5s at 5 Mbps), and sustained downloads or streams settle into the speed cap within 5 seconds without long freeze gaps.
- A Paused customer (1 kbps): limited to ~625 bytes per 5-second window while keeping local captive portal polling (`10.0.0.1:2080`) instant.
- An unthrottled HyperSpeed customer (0 = uncapped): nothing changes.
- A PisoPhone-only or PisoHome setup (no router portal): not affected.

## Success criteria
1. The installer writes `ratecheckwindow='1'`, `checkinterval='5'`, and both bursting switches `'1'`: the assertion in `router/tests/test_setup.py` fails first against the old installer (`checkinterval='15'`) and passes after rebuilding.
2. An update on a router with the old value (`checkinterval='15'`) updates it to `'5'` via `cmd_update` (`setup/src/80-commands.sh:237`).
3. `python3 tools/build_piso_setup.py --check` passes; all router and setup test suites (`test_setup.py`, `test_sqm.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_monitor.py`, `test_selfupdate.py`, `test_footprint.py`) pass; `bash scripts/check_security_rules.sh` and `python3 scripts/check_links.py` pass.
4. Hardware **RS73** (superseding RS65's 15s window check on the router): after `piso-setup update`, `ndsctl status | grep -i 'rate check'` shows `Rate Check Window: 1 check intervals (5s)` and `Check Interval: 5s`, and an Endurance download settles near 5 Mbps within ~5 seconds.
5. Money and time not touched: `git diff` touches only `setup/src/30-router-settings.sh`, generated setup scripts, `setup/RELEASE`, `router/tests/test_setup.py`, and documentation/spec files.

## Out of scope
- Turning bursting off (`download_unrestricted_bursting='0'`), changing plan speed caps (5 Mbps / 2 Mbps), changing SQM/CAKE WAN shaping (`setup/src/14-sqm.sh`), or modifying `pisoportal`.

## Owner decisions
None open: the owner requested reducing the burst duration to 5 seconds (`checkinterval='5'`, `ratecheckwindow='1'`).
