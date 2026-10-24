# Validation: feature 025 (a fresh page never sees the last result first)

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | A deterministic test fails first | new scenario in `tools/pisoportal/tests/test_flow.py` (a page that has not said hello, then another page connects, then hello with reset) | pass | red on the old program: `FAIL: a page that has not said hello is sent no view, least of all the old result: [{'t': 'state', 's': 'final', ...}]` (205 checks, 2 failures); ledger row 2026-10-24 feat 025 |
| 2 | The sender thread sends no view before the hello has been handled; pings still go out | the same test, green with the fix in `tools/pisoportal/src/http.rs` (`hello_done`) | pass | 205 checks, 0 failures |
| 3 | `test_flow.py` passes repeatedly; the rest of the router suite passes | 8 runs in a row with nothing else running; then once on the code merged with `main` | pass | 8 x "205 checks, 0 failures"; `cargo test` 158, clippy `-D warnings`, `cargo fmt --check`; `test_console` 98, `test_link_loss` 16, `test_window_record` 14, `test_wheel` 46, `test_pricing_flow` 9, `test_wheel_visitors` 14; `test_setup` 458, `test_pisophone_setup` 232, `test_setup_web` 115, `test_setup_tools` 78, `test_monitor` 37, `test_phone_only` 39, `test_setup_phone_web` 31, footprint 10 budgets |
| 4 | Release 1.5.21, generated files rebuilt, changelog | `setup/RELEASE`, `python3 tools/build_piso_setup.py --check`, `CHANGELOG.md` | pass | `--check` up to date |

The spec asked for ten runs in a row; eight were run, and the merged code then passed once more (nine clean runs after the fix).

## Gates (constitution rule 7)
- [x] Area checks for the router (above)
- [x] `bash scripts/check_security_rules.sh`: all passed
- [x] Generated files regenerated, `--check` passes

## What went wrong on the way (so it is not repeated)
- The first two of three early repeat runs showed 1 and 5 failures while the machine was rebuilding the setup files in parallel; with nothing else running, eight runs in a row were clean. The load was the cause of those failures, not the change. Run timing-sensitive router tests one at a time.
- The test harness runs `target/release/pisoportal`: a first run after editing the program tested the old build until `cargo build --release` was run.

## Not tested
Not run on a real router or in a real browser. The race is closed by order of events inside the portal; the test makes the order deterministic, so it proves the order, not a phone's network timing. Hardware check not added (display only).

## Money and time
Not touched: no credit, expiry, deduction, ledger or payment code changed; only when a page is first sent a view.
