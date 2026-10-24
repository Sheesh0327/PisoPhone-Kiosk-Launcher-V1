# Plan: 015 Router always on

Status: **approved, in progress** (owner's answers: spec 02 section 8). Design notes for group 3: the carry row holds the previous segment's last hash in its `plan` field and the coin total in `pesos`, so one month verifies alone and `verify all` follows the chain; readers use `ledger::read_recent`.

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries to load first: the two 2026-10-24 rows of feature 014 (the fake box `expire` control; AO-11 stranded coins).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/ledger.rs` | `append`, `verify_torn`, `shift`: the revenue file | ~250 | 3,500 |
| `tools/pisoportal/src/reconcile.rs` | compares ledger and box | ~250 | 3,500 |
| `tools/pisoportal/src/wheel_gate.rs` | `log` (AO-04) | ~90 | 1,200 |
| `tools/pisoportal/src/console.rs:795-806`, `boxview.rs:207-211`, `http.rs:394` | listeners (AO-09) | ~60 read | 1,000 |
| `tools/pisoportal/src/core.rs:1364,1535`, `fair.rs:55-75` | `recover`, `maintenance` (RT-17) | ~120 read | 2,000 |
| `router/piso_monitor.sh` | `do_tick` and checks (AO-01, 05, 06) | 188 | 3,000 |
| `router/piso_monitor.init`, `router/pisoportal.init` | the services | 15 + 15 | 400 |
| `router/tests/test_monitor.py` | monitor tests with fakes | - | 3,000 |
| `tools/pisoportal/tests/test_soak.py` | the soak (014), extended with `--bounded` | 300 | 4,500 |
| `setup/src/75-checks-stage2.sh:53`, `80-commands.sh:238`, `90-updates.sh:69`, `60-summary.sh` | monitor start (Q1), Check-up | read by `grep -n` only | - |
| `router/tests/test_footprint.py` | the size budgets | 30 | 500 |

## Task groups (tests first; each one finished, checked and committed on its own)
- [ ] 0. **Make the soak judge growth** (`test_soak.py --bounded`: second-half growth of any file except closed segments fails; a SIGSTOP hang step). Run it: **red** for `revenue.csv` (and `wheel.log` if a spin load is added). Log the red result in `ledger/`.
- [ ] 1. AO-04 `wheel.log` cap (256 KB + `.1`): `cargo test` first (red), then `wheel_gate.rs`.
- [ ] 2. AO-03 streaming `verify` (no `read_to_string`): 100k-row test (time, memory), torn last row still reported.
- [ ] 3. AO-02 monthly segments (**needs D1**): `cargo test` for the roll, chain and totals equal to one big file, a torn row at the roll, a power cut between move and first `carry` row; `verify`, `reconcile`, `report` read the segments; stored last hash/total of closed segments (the incremental part of AO-03); `test_flow.py` end to end and `reconcile` before/after. How credit was checked: the roll never changes a row's text; the totals are compared with one big file.
- [ ] 4. AO-09 listener limits on console and box-view (`http::Limits`): `test_console.py` with 200 idle sockets.
- [ ] 5. RT-17 `recover()` on its own thread: test with the box off (`test_link_loss.py` style); a customer page and `check_clock` are not delayed; recovery still credits exactly once (the soak and `test_flow.py` must still pass).
- [ ] 6. AO-01 (**needs D2**), AO-05, AO-06 (**needs D3**), Q1: monitor `do_tick` pings the portal and restarts it once per 10 minutes after 3 failures; `df /overlay` checks; every alert also goes to `alerts.txt` (last 20 lines); the monitor runs without Telegram (**needs Q1**); console front page and Check-up show `alerts.txt` (`test_console.py`, `test_setup*.py`). Report the installer sizes after this group: it is the one that touches `setup/src/`.
- [ ] 7. Run the extended soak (default and `--quick`) and the whole router gate set; fill `validation.md` with the numbers from 014 beside the new ones (file sizes at start and end, restart count of the hung portal, alerts); raise `setup/RELEASE` to 1.5.19 once (with 016 if it ships together); `python3 tools/build_piso_setup.py` and `--check`; `CHANGELOG.md` in owner terms; add hardware checks to `docs/REAL_WORLD_TESTING.md` if the owner wants them before 019; do not publish, merge or release unless the owner asks.

## Release
Router **1.5.19** (`setup/RELEASE`); regenerated, never hand-edited: `setup/piso-setup*.sh` (+ `.sha256`), `website/setup/*`, `website/install.sh`, `tools/pisoportal/bin/*` (the `router-program` workflow). Installer size budget **1635 of 1700 KB, source 144 of 150 KB** is the main risk: measure after group 6, stop and ask if a budget would be passed.
