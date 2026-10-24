# Plan: each device's type on its lock and home screens (feature 024)

Context manifest (constitution rule 1). Unresolved ledger entries loaded: none relevant.

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `esp32_firmware/include/PhoneProfiles.h` | profiles: names, rates, slot assignment | 1 to 40, 93 to 125 | 900 |
| `esp32_firmware/web/portal-modals-phones.js` | the profiles settings page | 237 to 270 | 700 |
| `esp32_firmware/include/ShopBranding.h` | the branding push, limits | 1 to 20, 125 to 165 | 900 |
| `app/.../util/ShopBranding.kt` | what the phone stores (title, name) | 30 to 60 | 450 |
| `protocol/gen_vectors.py`, `esp32_firmware/host_tests/protocol_contract_test.cpp` | the contract (to read in group 1) | by `grep -n` | tbd |

## Task groups
Test first. The protocol contract changes on both sides in one change.
- [x] 1. **Read the push path.** Where the branding push is built per phone (`DeviceNetwork.cpp`, `ShopBranding.h`), how the app parses it
  (`KioskHttpServer.kt`, `ShopBranding.kt`), and the exact size budget. Decide the parameter name (proposal `label`). Update this
  plan with the line ranges. No code.
- [x] 2. **Pure rules and the contract, test first.** `DeviceLabel.h` on the box (cleaning, the three presets, the fallback "PisoPhone",
  fit in the push) with `device_label_test.cpp`; a vector in `protocol/gen_vectors.py` and the contract test; the same rules
  in Kotlin with a unit test. Red first (no label exists). (red result logged: no)
- [x] 3. **Box: presets and the per-phone push.** The profiles page gets three preset buttons and shows the label per slot
  (`portal-modals-phones.js`; regenerate `WebAssets.h` with `scripts/embed_web.py`); the branding push for a phone carries its
  slot's label. A test proves no rate changes.
- [x] 4. **App: store and show.** `ShopBranding.kt` stores the label; the lock screen and the home screen header show label, else
  the box-wide title, else "PisoPhone". Old-box and old-app tests.
- [x] 5. Checks (clang-format, host tests, protocol vectors, `check_security_rules.sh`, ktlint, detekt, CI), `RS49`, `CHANGELOG.md`,
  `validation.md`, STATUS.

## Release
Firmware (raise `PISO_FW_VERSION`; with feature 022 pending this is the next number after it) and the app (new build). The router
is not touched. The firmware and the app work in either order (old/new compatibility is a success criterion).
