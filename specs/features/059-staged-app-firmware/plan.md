# Plan: 059 staged rollout and a way back for phone-app and box-firmware updates

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none for app/firmware.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| tools/pisoportal/src/update.rs | the router's `bucket` and `evaluate` (lines 90 to 112) | 268 | 0.8k |
| protocol/gen_vectors.py, protocol/README.md | how shared vectors are made and read | ~150 | read ranges only |
| esp32_firmware/src/OtaRollback.cpp | the box's own rollback (read-only context) | ~120 | 1.5k |
| esp32_firmware (OTA check code: find with `grep -rn "firmware.json" esp32_firmware/src`) | where the box decides to update | to measure | ranges only |
| setup/src/91-box-update.sh | the router's box update (state, retry, verify after reboot) | ~140 | 2.5k |
| scripts/publish_firmware_ota.py, scripts/tests/test_publish_firmware.py | firmware feed writer and its test | to measure | ranges only |
| .github/workflows/firmware-images.yml | signing run, rollout input (lines 160 to 230) | ~240 | 2k |
| app/src/main/java/com/pisophone/kiosk/security/KioskUpdateManager.kt | `parseRemoteVersion`, `autoUpdate`, `runUpdate` | ~330 | 2.5k |
| .github/workflows/build-apk.yml | where `app.json` is written (line 456) | ~480 | ranges only |
| scripts/verify_release.py | rollout report and stuck-stage warning | ~400 | ranges only |
| router/piso_monitor.sh | the one-time owner message | ~250 | ranges only |
| docs/RELEASE.md | stage and widen, in words | 117 | 2k |

Never opened: `website/update/*.bin|*.apk|*.sh`, `setup/piso-setup*.sh` (generated, `CLAUDE.md` section 4).

## Task groups
Small, ordered, each finished and checkable on its own. Test first.

- [ ] 1. **Shared bucket vectors.** `protocol/gen_bucket_vectors.py` writes `protocol/fixtures/rollout_bucket_v1.json` (ids x expected bucket, produced from the Rust function's definition); a Rust test reads it. Red first: the Rust test fails until the file exists and matches. (red result logged: yes)
- [ ] 2. **Firmware: honour `rollout`.** C++ bucket function + the update decision in the box (dashboard OTA and the manifest the router pushes); host test reads the vectors; missing field is 100; malformed is 0. (firmware: needs a higher `PISO_FW_VERSION`; test: `esp32_firmware/host_tests`; red result logged: yes)
- [ ] 3. **Firmware publishing.** `publish_firmware_ota.py` gains `--rollout` (default 10), `firmware-images.yml` gains the input and refuses to lower or reset a widened value on a plain push; `verify_release.py` reports it. (test: `scripts/tests/test_publish_firmware.py`; red result logged: yes)
- [ ] 4. **Router: see a failed box update.** `91-box-update.sh` verifies the new version after the box reboots (5 minutes), records `failed`, stops retrying that version, and `piso_monitor.sh` sends one message. (setup change: raises `setup/RELEASE`, router change goes through `beta`; test: `router/tests/` with the fake box; red result logged: yes)
- [ ] 5. **App: honour `rollout`.** Kotlin bucket function (same vectors), `parseRemoteVersion` reads the field, `autoUpdate` respects it, the owner's tap does not. (app: higher `versionCode`; test: `KioskUpdateManager` unit tests; red result logged: yes; cannot run Gradle here unless the Android SDK is present: say so in the validation)
- [ ] 6. **App publishing.** `build-apk.yml` writes the chosen rollout into `app.json`; a separate approved workflow run widens it; `verify_release.py` warns on a stuck stage. (test: `scripts/tests/` for the writer; CI is the first real run)
- [ ] 7. **Phone rollback (only if the owner says yes, decision 4).** Keep the previous APK; if the new build has crashed N times within its first hour, reinstall the previous one as device owner and report it on the next heartbeat. Hardware check first on a real device-owner phone.
- [ ] 8. **Documents and gates.** `docs/RELEASE.md`, `docs/ARCHITECTURE.md` section 11, `CHANGELOG.md`, one `RS` check; run the area gates in `CONTRIBUTING.md` and `bash scripts/check_security_rules.sh`.

## Release
Firmware: higher `PISO_FW_VERSION`; the signing run needs the owner's approval in `production-signing`. App: higher `versionCode`; published by `build-apk.yml`. Router: `setup/RELEASE` raised for group 4, through `beta`, decode the program inside the signed file (or let the guard of feature 060 do it). Nothing is released without the owner. Update `specs/RELEASE_STATE.md` and `specs/STATUS.md` when a release goes out.
