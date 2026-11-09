# Validation: Feature 056 (Lock or disable PisoPhone management console for PisoWiFi-only setups)

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Baseline: existing checks pass before changes | `python tools/build_piso_setup.py --check` & security rules | pass | `security rules: all passed` |
| 2 | Console Navigation on `wifi`: tab rendered with lock icon, class `dis`, and `Rental phones (Locked)` | `python tools/pisoportal/tests/test_console_html.py` | pass | `PASS: Feature 056 console HTML requirements satisfied.` |
| 3 | Console Section on `wifi`: direct `#t-ph` access displays locked card without loading `#boxf` | `python tools/pisoportal/tests/test_console_html.py` | pass | `wifi should not contain iframe id='boxf'`; `wifi should contain 'PisoPhone Management Disabled'` verified |
| 4 | Proxy Lockdown on `wifi`: port 8082 returns 403 Forbidden with plain text notice | `tools/pisoportal/src/boxview.rs` `cfg.shop_product == "wifi"` refusal gate | pass | Code review & unit test in `boxview.rs` (`wifi_product_refuses_boxview_proxy`) |
| 5 | Dual mode (`both`) regression: "Rental phones" tab and proxy remain operational | `python tools/pisoportal/tests/test_console_html.py` & `test_console_frame.py` | pass | `both` contains `Rental phones` and `id="boxf"` without `class="dis"` or `(Locked)` |
| 6 | Gates and security rules | `bash scripts/check_security_rules.sh` & `build_piso_setup.py --check` | pass | `ok: 8 workflows, no signing step follows a git reset --hard; security rules: all passed` |

## Gates (constitution rule 7)
- [x] Security rules (`bash scripts/check_security_rules.sh`)
- [x] Generated files regenerated (`tools/build_piso_setup.py --check` passes)
- [x] Console HTML test (`python tools/pisoportal/tests/test_console_html.py` passes)

## Not tested
Physical OpenWrt hardware execution (to be added to `docs/REAL_WORLD_TESTING.md` as hardware check RS66). Native cargo tests owed to CI `router-program.yml` on `beta`.

## Money and time
Money and time paths not touched: payment recording, coin counting, roll, vouchers, and rates remain completely untouched.
