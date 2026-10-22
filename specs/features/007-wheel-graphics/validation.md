# Validation: the wheel looks like a real spinning-wheel game (feature 007)

Router 1.5.15 (the same release as 006). Checked on a computer only; nothing was tried on a router or on a customer's phone (hardware check RS34, wording updated). Run 2026-10-22 on `feat/007-wheel-graphics`.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Layout: exactly `weight` slices per row, equal widths, wins spread, big prizes apart, deterministic | `tests/test_wheel_layout.py` (runs the pure functions of `wheel.html` under node) | pass | 17 checks, 0 failures |
| 2 | Browser: 20 slices (13 LOSE, 5 x2, 1 x3, 1 x5), odds panel equals the router's numbers, worked example follows the bet, the pointer ends on a slice of the router's result | `tests/test_wheel_graphics.py` | pass | 48 checks, 0 failures (12 spins in reduced motion and up to 14 with animation; every stop matched the result) |
| 3 | Honesty: stop inside the middle 80% of a slice of the result's row; a win and a loss take the same time | node test (all slices x 40 random values: 0 outside) and the browser test (12 spins: 0 edge stops; win and loss within 0.8 s of each other; wheel transition 4.2 s constant) | pass | as above |
| 4 | Unpaid visitor (006): wheel, odds, explanation shown, Spin disabled | `test_wheel_browser.py` | pass | 47 checks, 0 failures |
| 5 | Reduced motion and no script | `test_wheel_graphics.py` (reduced motion: no flicks, no celebration; script off: the odds table is in the page) | pass | as above |
| 6 | Table cap: a new or edited wheel above 24 slots is refused with the reason; a saved larger table still loads and is drawn as 24 proportional slices | `cargo test` (`wheel.rs`, `wheel_run.rs`), `test_wheel.py` (console refusal), `test_wheel_layout.py` (100 slots as 24) | pass | 137 unit tests; 43 checks |
| 7 | Extras: sound silent until pressed; celebration on a win only; last 5 spins kept in the browser, nothing breaks with storage blocked; page size, contrast, no console errors, screenshot | `test_wheel_graphics.py`; `router/tests/test_footprint.py` (page 19 KB of 30 KB); contrast measured in the page (every label at least 4.5:1) | pass | screenshot `specs/features/007-wheel-graphics/evidence/wheel-360x740.png`; squeezed page 17.9 KB |
| 8 | Existing tests green; fmt, clippy, tests; release number, CHANGELOG, manual, RS34 | see Gates | pass | `setup/RELEASE` stays 1.5.15 (006's) |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: `cargo fmt --check` clean; `cargo clippy --locked --release --all-targets -- -D warnings` clean; `cargo test --locked --release` 137 passed; `test_flow.py` 202; `test_console.py` 90; `test_pricing_flow.py` 9; `test_link_loss.py` 16; `test_wheel.py` 43; `test_wheel_visitors.py` 14; `test_wheel_layout.py` 17; `test_wheel_browser.py` 47; `test_wheel_graphics.py` 48; `test_browser.py` 53; `test_console_loader.py` 8; `test_console_prices.py` 13; `test_console_frame.py` 6; `test_game_browser.py` 14; `test_send.py` 24; `router/tests`: `test_setup.py` 288, `test_pisophone_setup.py` 167, `test_setup_web.py` 127, `test_setup_tools.py` 88; `test_footprint.py` 10 budgets, 0 failures. All 0 failures.
- [x] `bash scripts/check_security_rules.sh`: all passed; `python3 tools/build_piso_setup.py --check`: up to date; `python3 scripts/check_product_strip.py`: clean
- [x] Python: no Python tool changed; new tests follow their neighbours

## Tests seen failing on purpose
Four mutations, each turned the new tests red and was then undone: celebration also on a loss (`FAIL: the celebration plays on a win (26 bits) and not on a loss (26)`), a sound context made at load (`FAIL: no sound context is made until the speaker button is pressed`), a stop near a slice edge (`FAIL: and always inside the middle 80% of that slice`), prizes placed side by side (`FAIL: the two big prizes are not side by side: [10, 12]`). My first harness for this piped the build into `tail -0`, which killed the build, so three mutations first looked "not caught"; redone with a proper build.

## Found on the way
`tests/test_wheel_browser.py` (feature 005) was never listed in `.github/workflows/quality.yml`, so CI had not been running it; added with the new test.

## Not tested
Nothing on a real router or phone (RS34). The look on other phones' browsers (only headless Chromium at 360 x 740). The router programs for the router CPUs are built by CI; the program will be about 12 KB larger than today's 1026 KB (the page is about 10 KB bigger), well inside the 1100 KB budget; CI's footprint test checks it.

## Money and time
Not touched. No change to `wheel_spin.rs`, the limits, the journal, the ledger or the file lock; the router still decides every result and still refuses a spin from a device without paid time. The draw (`Table::draw`) is unchanged; the page only chooses which slice of the result's row the pointer stops on.
