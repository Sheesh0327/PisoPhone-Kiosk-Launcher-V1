# Validation: restore firmware 3.11.0 (feature 002)

Branch `feat/002-firmware-3-11-0` (not on `beta`). Status: **all local checks green; CI not run yet.**

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | All eight commits on the branch; tree builds | `cargo test`; `embed_web.py --check`; `build_piso_setup.py --check`; `check_security_rules.sh` | pass (see sweep below) | cargo 87 passed; "up to date" x2; "all passed" |
| 2 | Host tests pass; `clang-format` clean (CI version 23.1.2) | `sh esp32_firmware/host_tests/run.sh`; `clang-format --dry-run --Werror` | pass | 22 suites, 0 failures; 0 errors (29 before `851707c`, as expected) |
| 3 | `check_product_strip.py` passes | `python3 scripts/check_product_strip.py` | pass | owner decision 3(a): the revenue-counter names are allow-listed in the check (69 traces); the check is now a step in Code quality's firmware job |
| 4 | CI builds all firmware environments | push to the feature branch, read Firmware build / Firmware images | **not run** | waits for decision 3 |
| 5 | Router and setup tests pass | six `router/tests` files, `test_flow.py`, `cargo test` | pass | `test_setup` 288, `test_pisophone_setup` 164, `test_setup_web` 106, `test_setup_phone_web` 30, `test_setup_tools` 78, `test_phone_only` 35 checks, 0 failures; `test_flow.py` 202 checks (one timing flake on a first run); cargo 87. ST-1 fixed in this feature: test first (the new test failed with the NameError), then `expected_owner` uses `EXPECTED_FINGERPRINTS["owner"]` and `load_key(..., check_match=False)` |
| 6 | `PISO_FW_VERSION` 3.11.0; `setup/RELEASE` raised once | files | 3.11.0 and 1.5.13 (already raised by feature 001; not raised again) | |
| 7 | Hardware checks for 3.11.0 in `REAL_WORLD_TESTING.md`, not run | file | brought in with the commits | not run |
| 8 | `CHANGELOG.md` in owner terms | file | brought in with the commits | |
| 9 | `embed_web.py --check` and `check_product_strip.py` | see 1 and 3 | partly | |

## Not tested
Nothing has run on a real box. The ESP32 compile has not been done here (CI does it on push). Hardware: USB Wi-Fi setup of a factory board, the three product images, the period ledger across restarts.

## Money and time
The coin-counter hardening changes how revenue totals are kept (`PeriodLedger.h`, NVS keys). Checked by the firmware host tests (period ledger tests among the 22 suites). Credit to phones is not changed by this feature.
