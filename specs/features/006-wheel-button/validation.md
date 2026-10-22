# Validation: the wheel button for everyone (feature 006)

Router 1.5.15. Checked on a computer only; nothing was tried on a router or a customer's phone (hardware check RS34, wording updated).

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | With the wheel on and nothing paid, idle carries `wh: true`; the final view carries it too; with the wheel off both say false | `cargo test` (`core.rs`: `rt_with_the_wheel_on_every_device...`, `rt_with_the_wheel_off...`); `tests/test_wheel_visitors.py` over the WebSocket including a real coin payment | pass | 134 unit tests; 14 checks, 0 failures |
| 2 | `/wheel/state` for a device with no time: 200, odds, `can_spin: false`, `max_bet` 0; wheel off: 404; `/wheel/spin` with no time still refused and charges nothing | `cargo test` (`wheel_run.rs`, 11 tests); `test_wheel_visitors.py` | pass | state `reason` NO_SESSION; spin 403; `wheel.log` has no spin line |
| 3 | Browser: unpaid visitor sees the button, opens the wheel, sees the odds and "Buy Wi-Fi time first", Spin disabled; the "You are online" screen shows the button after a real payment | `tests/test_wheel_browser.py` | pass | 47 checks, 0 failures; screenshot of the unpaid coin page checked by eye (button under the voucher box) |
| 4 | The mini-game is hidden while the wheel is on and back when off | `test_wheel_visitors.py`, `test_game_browser.py` | pass | 14 and 14 checks |
| 5 | The console tells the owner what visitors see | `test_wheel_browser.py` (console text check) | pass | in the same 47 checks |
| 6 | Existing tests green; fmt, clippy, tests; release number, CHANGELOG, manual, RS34 | see Gates | pass | `setup/RELEASE` 1.5.15 |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: `cargo fmt --check` clean; `cargo clippy --locked --release --all-targets -- -D warnings` clean; `cargo test --locked --release`: 134 passed; `test_flow.py` 202 checks (after the page-size fix below); `test_console.py` 90; `test_wheel.py` 42; `test_wheel_visitors.py` 14; `test_wheel_browser.py` 47; `test_browser.py` 53; `test_game_browser.py` 14. The other router tests (`test_pricing_flow`, `test_link_loss`, `test_console_loader`, `test_console_prices`, `test_console_frame`, `test_send`, `router/tests/*`) were green on the first run of this branch before the page-size fix and are run again by CI on the pull request.
- [x] `bash scripts/check_security_rules.sh`: all passed; `python3 tools/build_piso_setup.py --check`: up to date
- [x] Python: no Python tool changed; new test follows its neighbours

## Found on the way
`test_flow.py` failed its page-size check (27,554 bytes against a 27,500 budget): a second button for the "You are online" screen made the coin page too big. Fixed by using one shared button (smaller than before), not by raising the budget. Logged in the ledger.

## Not tested
Nothing on a real router or phone (RS34). The router build for the router CPUs is done by CI. Other phones' browsers.

## Money and time
Not touched. `wheel_spin.rs`, the limits, the journal, the ledger and the file lock are unchanged; the router still refuses a spin from a device without paid time (`test_wheel_visitors.py` checks nothing is charged or logged).
