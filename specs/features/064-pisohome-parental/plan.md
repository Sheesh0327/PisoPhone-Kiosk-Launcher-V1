# Plan: Feature 064 — PisoHome (`PisoKids`)

Context manifest (constitution rule 1):

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `esp32_firmware/include/Product.h` | Product compile-time flags (`PISO_PRODUCT_HOME`, `PISO_HAS_SHOP_EXTRAS`) | 32 | 250 |
| `esp32_firmware/envs/products.ini` | PlatformIO `[home_only]` build filter stripping commercial files | 52 | 400 |
| `scripts/check_product_strip.py` | Verifies binary isolation for `phone`, `wifi`, and `home` | 263 | 1,450 |
| `scripts/embed_web.py` | Packs `esp32_firmware/web/*` into `WebAssets*.h` | 163 | 1,100 |
| `esp32_firmware/include/WebDashboardTemplate.h` | HTML template for the box dashboard (`#if PISO_IS_HOME` 3-tab Family UI) | 190 | 1,350 |
| `esp32_firmware/web/portal-core-phones.js` | Phone dashboard JS (clickable `chrome://flags` link + voice/setup helpers) | 623 | 1,500 |
| `website/index.html` | Phone setup page (QR + Phone-to-Phone WebADB USB-C OTG + clickable `chrome://flags` link) | 392 | 1,500 |
| `website/js/provisioning.js` | QR & USB provisioning payload builder (`product=home` support) | 140 | 950 |
| `website/js/webadb_manager.js` | WebUSB ADB installer (updated for Android Chrome phone-to-phone OTG & clickable HTTP allow link) | 307 | 1,450 |
| `app/build.gradle.kts` | Android build configuration & `IS_HOME_PRODUCT` flag | 189 | 1,350 |
| `app/src/main/java/com/pisophone/kiosk/ui/theme/Tokens.kt` | Design tokens (`TechNet` + warm `PisoKidsTheme` quest & gold tokens) | 47 | 350 |
| `app/src/main/java/com/pisophone/kiosk/service/SessionRules.kt` | Pure session state machine transitions (`afterPause`) | 114 | 850 |
| `app/src/main/java/com/pisophone/kiosk/repository/PaymentRepository.kt` | Idempotent credit & `pauseRunningClock()` / `heldSeconds` | 596 | 1,500 |
| `app/src/main/java/com/pisophone/kiosk/server/KioskHttpServer.kt` | Phone HTTP server (+ `/parent` Tailscale 3-Tab Family Console) | 413 | 1,500 |
| `app/src/main/java/com/pisophone/kiosk/overlay/ui/BlockScreen.kt` | Lock screen UI + delegation to `KidsQuestLockScreen` in Home mode | 479 | 1,500 |
| `app/src/main/java/com/pisophone/kiosk/overlay/ui/FloatingPill.kt` | Expanded pill panel with all 6 controls + **Pause / Save Time** | 320 | 1,400 |

Unresolved ledger entries loaded: none.

## Task groups

- [x] 0. Feature spec, plan, and validation docs (files: `specs/features/064-pisohome-parental/{spec.md,plan.md,validation.md}`)
- [x] 1. Firmware pure rules (`ChoreBoard.h`, `CurfewRules.h`, `PiggyGoal`) + host tests (files: `esp32_firmware/include/{ChoreBoard.h,CurfewRules.h}`, `esp32_firmware/host_tests/{chore_board_test.cpp,curfew_rules_test.cpp}`; test: `esp32_firmware/host_tests/run.sh`; red result logged: yes)
- [x] 2. Stripped-down `PISO_PRODUCT_HOME` firmware, 3-tab family dashboard & product strip gate (files: `esp32_firmware/include/Product.h`, `esp32_firmware/envs/products.ini`, `scripts/check_product_strip.py`, `esp32_firmware/include/WebServerChores.h`, `esp32_firmware/src/WebServerChores.cpp`, `esp32_firmware/include/WebDashboardHome.h`, `esp32_firmware/src/WebServerModule.cpp`, `esp32_firmware/src/WebServerConfig.cpp`, `esp32_firmware/src/WebServerCoinslot.cpp`, `esp32_firmware/src/DeviceManager.cpp`, `esp32_firmware/web/*`; test: `python3 scripts/embed_web.py --check`, `python3 scripts/check_product_strip.py`; red result logged: yes)
- [x] 3. Phone-to-Phone QR & WebADB USB-C provisioning + clickable HTTP flag link (files: `website/js/provisioning.js`, `website/js/webadb_manager.js`, `website/index.html`, `esp32_firmware/web/portal-core-phones.js`, `scripts/tests/test_provisioning.js`; test: `node scripts/tests/test_provisioning.js`, `python3 scripts/embed_web.py`; red result logged: yes)
- [x] 4. Android pure parental rules, Tailscale policy & session pause (files: `app/src/main/java/com/pisophone/kiosk/util/ParentalRules.kt`, `app/src/main/java/com/pisophone/kiosk/security/TailscalePolicy.kt`, `app/src/main/java/com/pisophone/kiosk/service/SessionRules.kt`, `app/src/main/java/com/pisophone/kiosk/repository/PaymentRepository.kt`, `app/src/test/java/com/pisophone/kiosk/util/ParentalRulesTest.kt`, `TailscalePolicyTest.kt`, `SessionRulesTest.kt`, `PaymentRepositoryTest.kt`; test: `./gradlew :app:testDebugUnitTest`; red result logged: yes)
- [x] 5. Android engine, curfew auto-pause & identical Tailscale `/parent` dashboard (files: `app/build.gradle.kts`, `app/src/main/java/com/pisophone/kiosk/util/KioskSettings.kt`, `app/src/main/java/com/pisophone/kiosk/service/KioskSessionSupervisor.kt`, `app/src/main/java/com/pisophone/kiosk/service/KioskEngine.kt`, `app/src/main/java/com/pisophone/kiosk/server/KioskHttpServer.kt`, `KioskHttpServerUnitTest.kt`; test: `./gradlew :app:testDebugUnitTest`; red result logged: yes)
- [x] 6. `PisoKids` Dual-Hero lock screen hub & floating pill overhaul (files: `app/src/main/java/com/pisophone/kiosk/ui/theme/Tokens.kt`, `app/src/main/java/com/pisophone/kiosk/overlay/ui/KidsQuestLockScreen.kt`, `app/src/main/java/com/pisophone/kiosk/overlay/ui/BlockScreen.kt`, `app/src/main/java/com/pisophone/kiosk/overlay/ui/FloatingPill.kt`; test: `./gradlew :app:compileDebugUnitTestKotlin`; red result logged: yes)
- [x] 7. Comprehensive validation & walkthrough (test: all suites, `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py`; files: `specs/features/064-pisohome-parental/validation.md`, walkthrough artifact)

## Release

- App: stable build with `IS_HOME_PRODUCT` build capability.
- Firmware: `PISO_FW_VERSION` unchanged for commercial builds; `PISO_PRODUCT_HOME` introduced for family home builds.
- Web assets: regenerated via `python3 scripts/embed_web.py`.
