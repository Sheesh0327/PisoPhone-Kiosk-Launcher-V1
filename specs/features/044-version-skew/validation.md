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

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | both update scripts record the state, every state tested | test_selfupdate, test_boxupdate | not run | |
| 2 | existing assertions, Telegram texts and decisions unchanged | same files; `git diff` of the scripts | not run | |
| 3 | persistent record written only on a change of state | counted in a test | not run | |
| 4 | the monitor alerts: rows 3 and 4 after 24 h, row 5 at once, never rows 1, 2, 6, 7, 8 | test_monitor | not run | |
| 5 | `/api/overview` `versions`, the front-page line, no secret | new `test_versions_panel.py`, browser | not run | |
| 6 | waiting router and box still sell | portal tests unchanged; `git diff --stat` | not run | |
| 7 | security rules; no secret in JSON or `alerts.txt` | `check_security_rules.sh`, tests | not run | |
| 8 | release raised, files regenerated, footprint, RS59 | `build_piso_setup.py --check`, `test_footprint` | not run | |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md` (including `cargo fmt --check` on touched files and `cargo clippy -D warnings`)
- [ ] `bash scripts/check_security_rules.sh`
- [ ] ShellCheck on the assembled setup file and the monitor
- [ ] Generated files regenerated, `--check` passes

## Not tested
Filled in at the end.

## Money and time
Filled in at the end. Planned: not touched.
