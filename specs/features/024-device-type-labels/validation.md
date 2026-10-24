# Validation: feature 024 (each device shows its own type on lock and home screens)

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Label rules (cleaning, presets, fit in push) proven on the box, red first | `esp32_firmware/host_tests/device_label_test.cpp` | pass | red first (header did not exist, ledger row); 38 host tests with sanitizers green; CI "Firmware format and host tests" green on 4d03ea9 |
| 2 | A label never changes a rate (display only) | host test in `device_label_test.cpp` | pass | same run |
| 3 | The phone stores, clears and cuts the label; title order label > shop title > PisoPhone; a label-only push is not a config update; an old box leaves it alone | `ShopBrandingUnitTest`, `KioskHttpServerUnitTest` | pass | CI "Build and Deploy APK" run 532 compiled the app and ran the unit tests green on 4d03ea9 (first compile of this code) |
| 4 | Presets page: three one-tap buttons plus custom names | `portal-modals-phones.js` -> `WebAssetsPhones.h` by `scripts/embed_web.py` | pass (build) / not tried on a box | firmware builds for all four environments green |
| 5 | PisoWiFi-only image strips the phone-only header | `scripts/check_product_strip.py` | pass | CI green |
| 6 | Version 3.12.3, changelog, hardware check RS49 | `FirmwareVersion.h`, `CHANGELOG.md`, `docs/REAL_WORLD_TESTING.md` | pass | in the diff |

## Gates
- [x] clang-format, ktlint 1.5.0, detekt 1.23.8, `check_security_rules.sh`: green in CI on 4d03ea9
- [x] Code quality green on 4d03ea9 (the only later commit is the bot's "publish dev APK build 532", which touches `website/update` only)

## Not tested
Not run on a real box or phone: hardware check RS49 is unticked. Screens were not looked at (no emulator).

## Money and time
Not touched: no credit, expiry, deduction or payment code. A profile's rate is never changed by its label (tested).
