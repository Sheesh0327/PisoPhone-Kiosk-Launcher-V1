# Validation: spinning wheel (feature 005)

Router 1.5.14, released to 100% on 2026-10-22. Validated again on `beta` on 2026-10-22 (commands and last lines below). Checked on a computer only; nothing was tried on a router, a phone or a customer's device (hardware check RS34).

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Pure logic: exact average return, refusal above 100%, bet rules, seeded draw | `cargo test` (`wheel.rs`, 9 tests) | pass | included in `cargo test --release`: 131 passed |
| 2 | Time: a spin changes the session by exactly the stated amount; same id changes nothing; power cut at both points; paused and expired refused | `cargo test` (`wheel_spin.rs`, 10 tests; `wheel_run.rs`, 10 tests); `tests/test_wheel.py` (a restart between two asks of one spin id) | pass | `test_wheel.py`: 42 checks, 0 failures |
| 3 | Limits: 10 spins a day, the 10 s wait, reset at local midnight | `cargo test` (`wheel_limits.rs`, 8 tests; `wheel_run.rs` limits and cap tests); browser test shows the wait on the button | pass | `test_wheel_browser.py`: 39 checks, 0 failures |
| 4 | End to end: a paying customer opens the wheel, sees the odds, spins, the page's time left matches the router's | `tests/test_wheel.py`, `tests/test_wheel_browser.py` | pass | page time left equals the roll within one minute (rounding down); double tap = one spin; nothing spins by itself |
| 5 | Old routers keep the mini-game and have the wheel off after update; a new install starts off; the switch (behind the gate) turns it on and off at once | `config.rs` test (default off, no setup script writes `WHEEL=`); `test_wheel.py` (mini-game served while off, 404 while on, back when off) | pass | as listed |
| 6 | Revenue ledger and box reconciliation unchanged | existing tests untouched: `test_flow.py` 202 checks, `test_console.py` 90, `test_pricing_flow.py` 9, `test_link_loss.py` 16; `test_wheel.py` compares `revenue.csv` before and after spins | pass | 0 failures in each |
| 7 | Responsibility gate: no phrase / wrong phrase / lower case / extra spaces / old version refused; exact phrase writes the record; `WHEEL=on` without a record leaves it off and logs why; off clears the record, next turn on asks again; customer pages say "off"; browser test of the dialog | `wheel_gate.rs` (6 tests), `wheel_run.rs`, `test_wheel.py`, `test_wheel_browser.py` | pass | dialog has only Cancel and Turn on; five wrong phrases keep the button disabled; breaking that check on purpose turned the browser test red (5 failures) |
| 8 | `cargo fmt --check`, `clippy`, `cargo test`, `test_flow.py`, router setup tests, `check_security_rules.sh`, `build_piso_setup.py --check`; `setup/RELEASE` raised once; hardware check added | see Gates | pass | `setup/RELEASE` 1.5.13 to 1.5.14; RS34 added |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` (router: `cargo fmt --check`, `cargo clippy --release --all-targets -- -D warnings`, `cargo test --release`, `tests/test_flow.py`, `test_console.py`, `test_pricing_flow.py`, `test_link_loss.py`, `test_wheel.py`, browser tests `test_browser.py`, `test_console_loader.py`, `test_console_prices.py`, `test_console_frame.py`, `test_game_browser.py`, `test_send.py`, `test_wheel_browser.py`; `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`)
- [x] `bash scripts/check_security_rules.sh`: all passed. `python3 scripts/check_product_strip.py`: no trace of the other product
- [x] Python: no Python tool was changed; the new tests are plain scripts like their neighbours
- [x] Generated files regenerated, `python3 tools/build_piso_setup.py --check` passes (the router programs in `tools/pisoportal/bin/` are rebuilt by CI after the push, as for every router change)

## Not tested
- Nothing on a real router, a real coin box or a real customer device. Hardware check **RS34** in `docs/REAL_WORLD_TESTING.md` lists what to try.
- The router build for the router's CPUs (aarch64, armv7, mips, mipsel): CI's router-program workflow builds them; I only built for this computer.
- The wheel's look on many different phones' browsers: tried in headless Chromium at a phone size only. The animation uses CSS and an offline page, nothing from the internet.
- A legal review of the warning text: **not done** (roadmap 8.3). Nobody should switch the wheel on in a shop before it is.
- A power cut at the exact moment between the journal and the session write on a real router's flash: tested by simulating both points (`wheel_spin.rs`), not by pulling a plug.

## Money and time
- Customer time: a spin writes its decided result to a journal first (write, flush, rename, flush the folder), then changes the session's bought minutes once under the router's file lock, then marks the journal done. A repeated spin id is answered from the journal and never charged twice, also after a restart (`test_wheel.py`). A spin cut short is finished or voided at the next start and in maintenance, never applied twice. A day's spin is reserved before it is made and given back if refused.
- Coins and the revenue ledger (`revenue.csv`), the box protocol and reconciliation: not touched. `test_wheel.py` checks `revenue.csv` is identical after spins.
- Rule 2 (signed software): not touched; the router update is signed and published by the owner's usual process.

## Evidence (run on `beta`, 2026-10-22)
```
cargo fmt --check                                   (clean)
cargo clippy --locked --release --all-targets -- -D warnings   Finished (no warnings)
cargo test --locked --release                       test result: ok. 131 passed; 0 failed
tests/test_wheel.py                                 42 checks, 0 failures
tests/test_wheel_browser.py                         39 checks, 0 failures   (run 3 times)
tests/test_flow.py                                  202 checks, 0 failures
tests/test_console.py 90 / test_pricing_flow.py 9 / test_link_loss.py 16 / test_browser.py 53 / test_console_loader.py 8 /
test_console_prices.py 13 / test_console_frame.py 6 / test_game_browser.py 14 / test_send.py 24      all 0 failures
router/tests: test_setup.py 288, test_pisophone_setup.py 167, test_setup_web.py 127, test_setup_tools.py 88   0 failures (before the merge)
bash scripts/check_security_rules.sh                security rules: all passed
python3 tools/build_piso_setup.py --check           up to date
python3 scripts/check_product_strip.py              phone: no trace of the other product / wifi: no trace of the other product
python3 scripts/verify_release.py (on main, after the 100% publish)   0 FAIL, 0 WARN, 12 PASS; router 1.5.14 rollout 100%
CI: PR #98 13 of 13 checks green; Code quality #591 on the merge commit green; Router program #136 green (all CPU families)
```
Notes: `ruff` is not a repository gate for `tools/pisoportal/tests` (the constitution scopes it to `scripts/`, `setup/` and `router/tests`); I still cleaned the two new test files (imports, `Path.write_text`), the only finding left is the shebang without the executable bit, the same as every neighbouring test. One browser test run failed once while stray processes from a crashed earlier run were still holding the test's ports; it passed three times after they were gone.

## Open (not failures of a criterion)
- Hardware check **RS34** (not run): the owner tries the wheel on a router.
- **Legal review of the warning text** (roadmap 8.3): owed before anyone switches the wheel on.
- Later: remove the old mini-game code (owner answer 3).
