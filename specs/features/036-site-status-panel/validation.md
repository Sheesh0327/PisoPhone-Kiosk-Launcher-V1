# Validation: feature 036, the money agreement panel (Part A)

Branch `feat/036-site-status-panel`, base `7b093b6`. Cloud container (x86_64 Linux, Rust 1.97, Python 3, headless Chromium, Python `playwright` installed with pip so the browser checks run).

## Before state (task group 0, 2026-10-29, no code changed)
| test | result |
|---|---|
| `cd tools/pisoportal && cargo test` | 125 passed, 0 failed, 0 ignored |
| `python3 tests/test_flow.py` | 216 checks, 0 failures |
| `tests/test_console.py` | 110 / 0 |
| `tests/test_browser.py` (`CHROME=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome`) | 65 / 0 |
| `test_console_frame` / `_loader` / `_prices` | 7 / 8 / 13, all 0 failures |
| `test_pricing_flow` / `test_link_loss` / `test_send` / `test_window_record` | 9 / 16 / 24 / 14, all 0 failures |
| `router/tests/test_setup.py` | 449 / 0 |
| `test_pisophone_setup.py` | 222 / 0 |
| `test_setup_web.py` | 139 / 0 (24 browser checks run because playwright is present) |
| `test_setup_tools.py` | 87 / 0 |
| `test_install.py` | 41 / 0 |
| `test_phone_only.py` / `test_setup_phone_web.py` | 39 / 0 and 44 / 0 |
| `test_footprint.py` | 8 budgets, 0 failures |
| `bash scripts/check_security_rules.sh` | all passed |
| `cargo build --release` program (x86_64) | 876,936 bytes (same as feature 034's after) |
| `setup/piso-setup.sh` | 1,674,531 bytes = 1635.3 KB of 1700 KB |

Differences from feature 041's recorded numbers are explained by later commits, not by this feature: `test_setup.py` 442 -> 449 (feature 035 added 7), `test_setup_web` and `test_setup_tools` count the browser checks here, `test_setup_phone_web` 31 -> 44 with the browser.
Not run in the baseline: `test_soak.py` (10 minutes; this feature does not touch the ledger write path), ShellCheck and BusyBox `ash` (not installed).

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | state function, rows 1 to 8, tests first | `cargo test f036` | not run | |
| 2 | `/api/overview` `money` object, each state | `tests/test_console.py` | not run | |
| 3 | front-page line, light and dark, phone width | `tests/test_browser.py` | not run | |
| 4 | no write to `reconcile.state`; at most one box call a minute | test_console + fakebox counter | not run | |
| 5 | `pisoportal reconcile` output and exit codes unchanged | existing `test_flow.py` checks | not run | |
| 6 | a counter restart is a note, then row 1 | cargo + test_console | not run | |
| 7 | kind of count decides row 2 or 3, not the gap | cargo | not run | |
| 8 | no payment code touched; gates pass | `git diff --stat`; all gates | not run | |
| 9 | release raised, files regenerated, RS58 added | `build_piso_setup.py --check` | not run | |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md`
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Generated files regenerated, `--check` passes

## Not tested
Filled in at the end.

## Money and time
Filled in at the end. Planned: not touched; read-only (criteria 4 and 8).
