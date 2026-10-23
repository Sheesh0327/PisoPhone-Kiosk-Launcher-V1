# Handoff: feature 011 (offline setup + coin box network name), 2026-10-23

Branch `feat/011-offline-setup`, pushed. Last code commit: `4da5b9e` (group 2). Stopped at the chat limit (constitution rule 5), not at a problem.

## Done
- Group 0 ticked (owner's go given). Group 1: baseline, all 7 setup/router test files + security rules + `--check` pass; counts in `validation.md` (Playwright could be pip-installed, so the browser files ran: web 139, tools 87, phone_web 44).
- Group 2 (criterion 3): `rotate_box_wifi [force]` in `setup/src/50-coin-box.sh` now changes nothing when the box refuses (no stored `BOX_WIFI_PASS_NEW` kept, returns 1, new message); `piso-setup rotate-box-wifi` calls `rotate_box_wifi force` and no longer clears `BOX_WIFI_ROTATED` first (fact B7). `setup/RELEASE` is now **1.5.18** (raised once; do not raise again). New tests in `router/tests/test_setup.py` (Box stub has `refuse_wifi`); red result logged in `ledger/negative-constraints.md`. test_setup 388/0, test_install 39/0, test_phone_only 39/0, test_footprint 0 failures, security rules pass, generated files regenerated, `--check` ok.

## Decisions / notes
- `rotate_box_wifi` returning 1 on refusal is safe: the script has no `set -e` and callers (`stage2`, `cmd_pair`) do not test it. Group 3's `own_box_wifi_name` should use that return value.
- The after-switch failure message already said "Power-cycle it and run: piso-setup status"; only a test was added.
- No spec edit was needed so far.

## Open / next
- **Next: group 3** (criteria 2, 6): `own_box_wifi_name` in `50-coin-box.sh` + `box-wifi-name` in the command list and dispatch of `98-setup-flow.sh` (list is the `status | pair | ...` line; dispatch near `rotate-box-wifi`). Check the name (`PisoCoinBox-<id>` from `router_id`: letters, digits, dash, max 32 bytes) before sending, because the box validates nothing. Set `BOX_SSID_NAME`, re-run `resolve_wifi_names` so `BOX_SSID` follows, then `rotate_box_wifi force`; on failure restore `BOX_SSID_NAME`. Say "off its network up to four minutes, coins kept", ask unless `--yes`/not a terminal. Stop with the reason when `BOX_MAC` is empty or `box_up` fails; "already has its own name" when `BOX_SSID_NAME` is not `PisoCoinBox`. Touch no `kiosk_*`, `KIOSK_SSID_NAME` or `KIOSK_PASS`.
- Then groups 4 to 12 in order, as in `plan.md`. Group 12 must fill `validation.md` fully (it currently holds only the baseline section).
- If `test_footprint.py` fails after the new `21-local-packages.sh`: stop and ask the owner; do not raise the budget.
- `shellcheck` is not installed here; criterion 15 needs it or must be recorded as not run.

## Slow tests
`python3 router/tests/test_setup.py > out.txt 2>&1` takes a couple of minutes; redirect to a file and read the last lines (piping through grep hides output until it ends). Browser files need `pip install playwright` (Chromium is already present). Do not run `cargo test` or `test_flow.py` (owner's instruction).
