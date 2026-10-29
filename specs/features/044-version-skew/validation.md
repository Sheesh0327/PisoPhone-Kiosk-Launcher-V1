# Validation: feature 044, show the update state

Branch `feat/044-version-skew`, on top of `feat/036-site-status-panel` (not merged). Cloud container (x86_64 Linux, Rust 1.97, Python 3, Chromium, `shellcheck-py` 0.11.0 installed with pip).

## Before state (task group 0, 2026-10-29, no code changed by this feature)
| test | result |
|---|---|
| `router/tests/test_selfupdate.py` | 60 checks, 0 failures |
| `router/tests/test_boxupdate.py` | 28 checks, 0 failed |
| `router/tests/test_monitor.py` | 37 checks, 0 failures |
| `cargo test` (includes feature 036) | 139 passed |
| portal tests, setup tests, `test_footprint` | as recorded in `specs/features/036-site-status-panel/validation.md` (this branch starts from that tip) |
| `shellcheck -s sh setup/piso-setup.sh.in router/piso_monitor.sh router/piso_isolation.sh setup/install.sh` | clean |
| `cargo clippy --locked --release --all-targets -- -D warnings` | clean |
| `cargo fmt --check` | fails only on `http.rs` and `main.rs`, which already failed at `7b093b6` (before any 036 or 044 change); not touched here |

Found while preparing: CI also runs `cargo fmt --check` and `cargo clippy -D warnings`, which feature 036 had not run. `reconcile.rs` failed `fmt`; fixed on the 036 branch (whitespace only) and merged here. From now on these two are part of every Rust gate.

## After (all groups, 2026-10-29)
| test | before | after |
|---|---|---|
| `router/tests/test_selfupdate.py` | 60 | **71**, 0 failures (11 new `044`) |
| `router/tests/test_boxupdate.py` | 28 | **47**, 0 failed (19 new `044`) |
| `router/tests/test_monitor.py` | 37 | **48**, 0 failures (11 new `044`) |
| `cargo test` | 139 | **141** passed (2 new `f044_*`) |
| `tests/test_versions_panel.py` (new, in `quality.yml`) | - | **13 checks, 0 failures** (overview JSON, no secret, 6 browser states: light, dark, 360 px) |
| `test_money_panel` / `test_console` / `test_browser` / the other portal tests | 69 / 110 / 65 / ... | same counts, 0 failures |
| `test_flow.py` | 216 | 216, 0 failures (see the flake below) |
| the seven setup test files | 449 / 222 / 139 / 87 / 41 / 39 / 44 | same, 0 failures (`test_setup_web` needs `CHROME` set) |
| `test_footprint.py` | 8 budgets | 8 budgets, 0 failures |
| `shellcheck -s sh setup/piso-setup.sh.in router/piso_monitor.sh router/piso_isolation.sh setup/install.sh` | clean | clean |
| `cargo clippy --locked --release --all-targets -- -D warnings` | clean | clean |
| `cargo fmt --check` | `http.rs`, `main.rs` only | `http.rs`, `main.rs` only (both already failed at `7b093b6`; not touched) |
| `bash scripts/check_security_rules.sh` | all passed | all passed |
| `python3 tools/build_piso_setup.py --check` | - | up to date after `setup/RELEASE` 1.5.27 -> 1.5.28 |
| program, x86_64 release | 881,096 bytes | 885,032 bytes (+3,936), no warnings |
| `setup/piso-setup.sh` | 1,674,531 bytes | 1,681,079 bytes = 1641.7 KB of 1700 KB (the shell grew; the CI-built portal programs inside it are not rebuilt here and grow by about +4 KB each, so about 1646 KB) |

Red results (each logged in `ledger/negative-constraints.md` before the fix): group 1, 9 failures (71 checks); group 2, 17 failed (47 checks); group 3, 5 failures (48 checks); group 4, 2 Rust tests; group 5, 6 browser failures (13 checks).

**A flaky check that is not from this feature:** `tests/test_flow.py` fails about one run in three at `and the page is told what is still needed: {}` (1 or 5 failures in my runs; the five-failure run was not saved). The base commit before 036 and 044 (`7b093b6`, built in a separate worktree) fails the same check, 1 run in 3. Not fixed here; it should get its own row.

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | both scripts record the state, every state tested | `test_selfupdate`, `test_boxupdate` | pass | router: unknown (website), current, available (`check`), waiting (customers), off, rollout, refused (wrong key), failed and undone, current after a good install. Box: current, newer, available, waiting (customers, phone with time, box still checking), off, unknown (box silent, no website information, wrong chip, no password), three failed uploads then "not tried again", tampered file, refused by the box, did not come back, current after a good install |
| 2 | decisions, Telegram texts, send-once unchanged | existing assertions of both files; diff proof | pass | all earlier checks (60 and 28) pass unchanged; stripping the recording lines from `90-updates.sh` and `91-box-update.sh` gives back the original files byte for byte (checked with a script for each, after the second one's first attempt had a flaw in my strip order, not in the file). Two lines are reworded for the record: the password guard became a block and the not-newer branch gained an if/elif/else of recordings |
| 3 | record written only on a change; `since` survives | `test_selfupdate`, `test_boxupdate` | pass | a second `auto` run a second later leaves the line identical, including `since`. I did not count conf writes directly: the function compares with the old value and writes only if different (`[ "$_ro" = "$_rn" ] \|\| conf_set`). `UPDATE_LAST_CHECK` is still written every hourly run, as before |
| 4 | monitor: rows 3 and 4 after 24 h, row 5 at once to `alerts.txt`, never rows 1, 2, 6, 7, 8 | `test_monitor` | pass | waiting for less than a day: no alert; over a day: one alert with the command, not repeated; automatic updates off over a day: alert; failure: `alerts.txt` line, no second Telegram message; rollout, newer, unknown, available, current: nothing; recovery then a new failure alerts again; no setup conf: nothing. Spec wording for Telegram on failures was edited in group 3 |
| 5 | `versions` in `/api/overview`, the line, no secret | `test_versions_panel`, cargo | pass | JSON equals the records; `boxpw-secret` and the gateway key are not in the overview; an unknown state is null; the line was checked up to date (light), waiting under a day (neutral), over two days (dark, gold, command), a failure (360 px, gold, fits), not checked 5 hours ago (dark, 360 px), nothing recorded |
| 6 | waiting router and box still sell | portal tests unchanged; `git diff --stat` | pass | no change to `core.rs`, `ledger.rs`, `pricing.rs`, `voucher.rs`, `boxlink.rs`, `reconcile.rs`, `esp32_firmware/`, `app/` since the 036 tip (0 files) |
| 7 | security rules; no secret in JSON or `alerts.txt` | `check_security_rules.sh`; `test_monitor`, `test_versions_panel` | pass | no `-k`; fixture password and key checked absent from the overview, `alerts.txt` and Telegram |
| 8 | release, regenerated files, footprint, RS59 | `build_piso_setup.py --check`, `test_footprint`, `docs/REAL_WORLD_TESTING.md` | pass | `setup/RELEASE` 1.5.28; RS59 added (also in session 3 next to RS58); CHANGELOG entry |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`, including `cargo clippy -D warnings` and `cargo fmt --check` (clean for every file this feature touched)
- [x] `bash scripts/check_security_rules.sh`
- [x] ShellCheck on the assembled setup file and the monitor
- [x] Generated files regenerated, `--check` passes
- [ ] Python: `ruff` / `mypy` / `pytest` not run on the new test file (plain scripts, like their neighbours)

## Not tested
- **Nothing ran on a real router or coin box.** The tests run the real setup file against a fake website and a fake box (`test_selfupdate`, `test_boxupdate`) and the real monitor against a fake Telegram. RS59 is the hardware check.
- BusyBox `ash` is not installed here: the new shell (`case`, `cut`, `sed`, `tr '[:lower:]' '[:upper:]'`) was run under the container's `sh` and is ShellCheck-clean for POSIX sh.
- The hourly cron timing and a restart between runs (`since` surviving a reboot) were not run; `since` is stored in the setup conf (flash), not in RAM.
- The 24-hour grace is proven with fixture timestamps, not by waiting a day.
- A router running a release older than this one does not write the record until it has updated; until then the console says "not checked yet" for it.
- The phone apps are not covered (needs a box <-> phone message change).
- The portal programs in `tools/pisoportal/bin/` and the installer's copies of them are built by CI after the merge.

## Money and time
Not touched. The update scripts' decisions are unchanged (criterion 2); the monitor and the console only read the recorded state and write `alerts.txt`; no ledger, window, price or coin path file is in the diff (criterion 6).
