# Validation: feature 036, the money agreement panel (Part A)

Branch `feat/036-site-status-panel`, base `7b093b6` (spec and plan). Run 2026-10-29. Cloud container (x86_64 Linux, Rust 1.97, Python 3, headless Chromium, Python `playwright` installed with pip so the browser checks run).

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

## After (all groups)
| test | before | after |
|---|---|---|
| `cargo test` | 125 passed | **139 passed**, 0 failed (14 new `f036_*`: 10 state, 3 look/cache, 1 json) |
| `cargo build --release` warnings | 0 | 0 |
| `tests/test_money_panel.py` (new, in `quality.yml`) | - | **69 checks, 0 failures** (stable over repeated runs) |
| `test_flow` / `test_console` / `test_browser` | 216 / 110 / 65 | 216 / 110 / 65, 0 failures |
| `test_console_frame` / `_loader` / `_prices` | 7 / 8 / 13 | same |
| `test_pricing_flow` / `test_link_loss` / `test_send` / `test_window_record` | 9 / 16 / 24 / 14 | same |
| the seven setup test files | 449 / 222 / 139 / 87 / 41 / 39 / 44 | same, 0 failures |
| `test_footprint.py` | 8 budgets | 8 budgets, 0 failures |
| `bash scripts/check_security_rules.sh` | all passed | all passed |
| `python3 tools/build_piso_setup.py --check` | - | up to date (after the rebuild for `setup/RELEASE` 1.5.26 -> 1.5.27) |
| program, x86_64 release | 876,936 bytes | 881,096 bytes (+4,160) |
| `setup/piso-setup.sh` | 1,674,531 bytes | 1,674,531 (the portal programs inside it are built by CI after the merge, so it grows by about the same +4 KB then: about 1639 KB of 1700 KB) |

Red results (each logged in `ledger/negative-constraints.md` before the fix): group 1, 10 of 10 state tests failed against a stub; group 2, 3 failed; group 3, `test_money_panel` 40 checks / 9 failures; group 4, 69 checks / 4 failures.

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | state function, rows 1 to 8, tests first | `cargo test f036` | pass | 10 state tests failed first, then pass; `f036_the_kind_of_count_decides_row_2_or_3_never_the_size_of_the_gap` covers gaps 1, 2, 63, 5000 |
| 2 | `/api/overview` `money` object, each state | `tests/test_money_panel.py` | pass | rows 8, 1, 2, 3, 4, 5, 6, 7 each asserted with the fake coin box (`wifi_counter`, `phone_coins`, `status_fail`) and the real hash-chained ledger; `kind` dropped from the spec (the state says it) |
| 3 | front-page line, light and dark, phone width | same file, Playwright/Chromium | pass | row 1 light, row 2 dark (gold, not red), row 4 at 360 px (red, both numbers, the line fits), row 6 dark at 360 px; the line is above `#tiles` |
| 4 | no write to `reconcile.state`; at most one box call a minute | same file | pass | the first look made exactly 1 `status` call; 50 more polls made 0; `reconcile.state` absent after the polls; a saved `0 0 900` stayed byte-identical in the restart case |
| 5 | `pisoportal reconcile` output and exit codes unchanged | `test_flow.py` unchanged + `reconcile run()` untouched | pass | `git diff 7b093b6 -- tools/pisoportal/src/reconcile.rs` removes no line; `RECONCILE OK ledger=1247 ...` printed in the new test |
| 6 | a counter restart is a note; gone once the hourly check records it | cargo `f036_row5...` + test_money_panel | pass | spec wording corrected during group 1 (the panel cannot write the base); the saved-base case asserts `restarted false` |
| 7 | the kind of count decides row 2 or 3, not the gap | cargo | pass | see 1 |
| 8 | no payment code touched | `git diff --stat 7b093b6` | pass | changed in `src/`: `console-ui.js`, `console.html`, `console.rs` (+5 lines), `reconcile.rs` (additions only); `core.rs`, `ledger.rs`, `pricing.rs`, `voucher.rs`, `fair.rs`, `roll.rs`, `boxlink.rs`: no change; no firmware, app or setup source change |
| 9 | release raised, files regenerated, RS58 added | `setup/RELEASE`, `build_piso_setup.py --check`, `docs/REAL_WORLD_TESTING.md` | pass | 1.5.27; RS58 in the file and in session 3 next to RS6; `CHANGELOG.md` entry under Unreleased |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` (router add-on: cargo, flow, console and the other portal tests; setup: the seven files; footprint)
- [x] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check` on `tests/test_money_panel.py` finds 17 (mostly `open()` without `with`, the style of the sibling tests, which have more); `mypy` and `pytest` not run (the tests are plain scripts)
- [x] Generated files regenerated, `--check` passes

## Not tested
- **Nothing ran on a real router or coin box.** RS58 is the hardware check. The fake coin box is the only box tested against: its Wi-Fi and phone counters mirror `WebServerGateway.cpp:100-102`, but a real box was not asked.
- The portal programs in `tools/pisoportal/bin/` and the installer's copy of them are built by CI after the merge; the x86_64 build here is the only one made. No mipsel build, no BusyBox `ash`, no ShellCheck (not installed).
- The cache TTL (60 s) is proven with an injected clock in Rust, not by waiting a minute in a browser.
- A first look with no saved base and a record already ahead of the box shows "agrees" with the restart note (existing `next_base` behaviour, kept so it never cries wolf). A real mismatch shows once the hourly check has saved a base, so up to the first hour on a new router a mismatch is not shown. Owner to know.
- Existing, not changed: at a 360 px screen the console's sign-out control sticks out 12 px (found while checking the line).
- `test_soak.py` (10 minutes) not run; the ledger write path is untouched.

## Money and time
Not touched. The panel reads the sales record and asks the box for the `status` it already answers; it writes no ledger row and no base, credits nothing and prices nothing. Proof: criterion 4 (no write, one call a minute) and criterion 8 (no payment file in the diff). The Wi-Fi coin counters are outside the box's signed facts (spec, finding 4), so the number is display only; `reconcile` already relies on the same field.
