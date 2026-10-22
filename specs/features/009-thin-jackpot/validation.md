# Validation: the x5 prize is a rare, thin jackpot (feature 009)

Router 1.5.16. Checked on a computer only; nothing was tried on a router or on a customer's phone (hardware check RS34, wording updated). Run 2026-10-23 on `feat/009-thin-jackpot`.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | `cargo test`: `t` parses and writes back; chances and return over sizes (default: 15/39 win, 1/39 jackpot, 89.7%); a large seeded draw stays near them, the thin row about half as often as a normal slot; return and cap rules as before; an old table gives exactly the old chances | `cargo test --locked --release` (`wheel.rs`, `wheel_run.rs`) | pass | 141 unit tests, 0 failed |
| 2 | Existing wheel tests green; a table without `t` is the same wheel and odds as before | `test_wheel.py`, `test_wheel_visitors.py`, `test_wheel_browser.py`; unit test `rt_a_table_without_thin_slots_behaves_exactly_as_before` ("13 of 20 (65%)", unit 1) | pass | 46, 14, 50 checks, 0 failures |
| 3 | Layout (node): angles sum to 360, proportional to size, default 19 x 18.46 and 1 x 9.23, wins spread, thin jackpot not next to the x3, a thin slice half a normal one in every table | `test_wheel_layout.py` | pass | 25 checks, 0 failures |
| 4 | Browser: 20 slices, exactly one half-width labelled x5; odds table "1 of 39 (2.6%)"; the stop is inside the middle 80% of a slice of the result's row; win and loss take the same time | `test_wheel_graphics.py` (the stop is read from each slice's own angle, so it covers the thin slice too) | pass | 49 checks, 0 failures |
| 5 | Console: `0x12 2x6 3x1 5x1t` saves and shows 89.7% / 38.5%; a table over 24 slices or above 100% is refused with the reason | `test_wheel_browser.py` (typing, flagging), `test_wheel.py` (router refusals incl. thin tables) | pass | as above |
| 6 | Router setup tests, fmt, clippy, footprint, security rules, `build_piso_setup.py --check`; `setup/RELEASE` raised once; CHANGELOG, manual, RS34 wording | see Gates | pass | `setup/RELEASE` 1.5.15 -> 1.5.16 |

## Gates (constitution rule 7)
- [x] `cargo fmt --check` clean; `cargo clippy --locked --release --all-targets -- -D warnings` clean; `cargo test --locked --release` 141 passed
- [x] `test_flow.py` 202; `test_console.py` 90; `test_pricing_flow.py` 9; `test_link_loss.py` 16; `test_wheel.py` 46; `test_wheel_visitors.py` 14; `test_wheel_layout.py` 25; `test_wheel_browser.py` 50; `test_wheel_graphics.py` 49 (all 0 failures)
- [x] `router/tests/test_setup.py` 288; `test_pisophone_setup.py` 167; `test_setup_web.py` 127; `test_setup_tools.py` 88; `test_footprint.py` 10 budgets (all 0 failures)
- [x] `bash scripts/check_security_rules.sh`: all passed; `python3 tools/build_piso_setup.py --check`: up to date; `python3 scripts/check_product_strip.py`: clean

## Tests seen failing on purpose
Every task group's tests were seen red first (rows in `ledger/negative-constraints.md`). Two mutations at the end, each turned tests red and was undone: a thin slot drawn as likely as a normal one (6 unit tests failed, among them the default-wheel odds and the large-run draw), and the page drawing equal slice widths (`FAIL: 19 slices of 18.46 degrees and one of 9.23`, `FAIL: the half-width slice is the thin one`).

## Found on the way
The return cap (100%) was compared on a rounded permille, so a wheel paying back 100.1% was accepted; it is now compared exactly (unit test `rt_a_table_that_returns_even_a_hair_more_than_100_percent_is_refused`). The win chance shown is rounded to the nearest (38.5%), not down (38.4%).

## Not tested
Nothing on a real router or phone (RS34). The look of a half-width x5 label on small phones (only headless Chromium at 360 x 740 and 390 x 780). The router programs for the router CPUs are built by CI; the footprint test there checks the budgets (programs 1200 KB, installer 1700 KB, wheel page 30 KB; the page is now 19.7 KB).

## Money and time
Not touched. No change to `wheel_spin.rs`, the limits, the spin journal, the ledger or the file lock: the router still decides every result before the wheel moves and still refuses a spin from a device without paid time. Only the draw changed (`Table::draw` picks over units of chance; for a table without a thin row the unit is 1 and the draw is the same as before, test `rt_a_table_without_thin_slots_behaves_exactly_as_before`), and a table saved by an earlier release is loaded with the same chances it always had.
