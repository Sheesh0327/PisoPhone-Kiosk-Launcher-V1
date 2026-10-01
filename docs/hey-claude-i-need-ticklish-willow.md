# PisoPhone: make it work smoothly (bugs + bloat)

## Context
Owner wants to find bugs they can't fix and cut bloat. Fixed decisions: keep hardcoded shared secret, keep 5-min/logout revenue reset, keep slot licensing only (drop other licensing). Findings come from code reads (nothing run on a device). ✔ = verified by me in code; others are agent-reported, re-check before fixing. App paths relative to `app/src/main/java/com/pisophone/kiosk/`; firmware paths to `esp32_firmware/src/`.

## Phase 0: Release blockers (do first)
1. ✔ **Published `website/update/firmware.bin` is corrupt** — first byte `EF` not `E9`, 322,176 UTF-8 replacement sequences, SHA256 ≠ `firmware.json`. Every OTA fails. Rebuild, commit as binary, add `.gitattributes` `*.bin binary` (and `*.apk binary`).
2. ✔ **Production partition table can't OTA** — `partitions_encrypted.csv`: `ota_0` 1 MB (firmware ≈1.1 MB), no second OTA slot. Switch to two equal ≥1.6 MB OTA slots (no factory) or use `min_spiffs.csv`.
3. ✔ **Wrong pins on classic ESP32 (`esp32dev`)** — `Config.cpp:14-15` defaults coin=GPIO3 (UART0 RX), LED=GPIO8 (flash pin → crash/boot loop). Owner uses **both** C3 and classic boards → per-board pin defaults via `-D` build flags in `envs/*.ini` (keep C3 values as-is). **Before changing classic defaults, ask owner for actual classic-board wiring** (NVS overrides still win). Reset pin: require release-then-hold after boot. Publish **separate firmware files per chip** (`firmware-esp32c3.bin`, `firmware-esp32.bin`) and per-chip entries in `firmware.json`.
4. ✔ **Lock screen never shows on Android 7.x** — decision: **raise `minSdk` to 26** in `app/build.gradle.kts`; drop now-dead `SDK_INT < 26` branches.

## Phase 1: App bugs that break sessions/money
1. ✔ Arming flag stuck — clear `isArmingInProgress` in `KioskEsp32Coordinator.onSlotLockdown` (:130) and all failure exits of `Esp32ConnectionManager.armSlot`/WS (:495, WS 403/423, catch).
2. ✔ Failed ADD TIME locks paid session — `KioskEsp32Coordinator.onSlotBusy` (:104-108): 2 stays 2, 3→2, 1→0.
3. ✔ Coin acked when credit failed — `onCoinMessageReceived` returns `PaymentResult`; `sendTxAck` only on APPLIED/ALREADY_APPLIED, else drop from `processedTxIds` (`Esp32ConnectionManager.kt:540-543`, WS :627-630).
4. ✔ Admin bypass overwrites balance — `PaymentRepository.adjustSessionTime` (:303) → use `max(remaining, N)`.
5. ✔ `runBlocking` on main — `KioskService.trigger*` (:86-109) call engine directly from Compose clicks; health monitor on `Dispatchers.Main` (`KioskEngine.kt:414,424`). Run on engine's IO scope, post state updates.
6. ✔ Admin adjust forces state 2 / deduct-to-0 forces state 0 without unarming ESP32 (`KioskEngine.kt:54-58, 398`). Use explicit admin flag; preserve armed state 1/3; call unarm when cancelling arm.
7. ✔ Phone IP read once (`KioskStateManager.kt:42`) — refresh on `ConnectivityManager` network callback.
8. Set `paymentTimeout` before `appState` in `onArmSuccess`/`onPaymentApplied` (tick race).
9. Reboot-time heuristic subtracts boot uptime from stale balance; boot count in encrypted prefs unreadable before unlock → false "reboot" after crash (`PaymentRepository.kt:446-497`, `KioskSecurity.kt:67-93`). Store boot count in device-protected prefs; checkpoint deadline more reliably.
10. `armSlot` fast-path discovery runs on main (`Esp32ConnectionManager.kt:423`).
11. Expiry paths other than supervisor don't send customer app home / pause media (`KioskEngine.kt:369-379, 424-434`, `KioskServerCoordinator.kt:95-106`). Centralize "lock" action.
12. TTS failure mutes media forever and leaks `TextToSpeech` (`audio/KioskAudioManager.kt:384-390`).
13. Overlay health check ignores pill; detach listener lacks identity check (`KioskOverlay.kt:104,360`, `FloatingPillOverlay.kt:213`).
14. Add `addPersistentPreferredActivity` for HOME; Wi-Fi lock + battery-optimization exemption for heartbeat/HTTP.

## Phase 2: ESP32 firmware bugs
1. ✔ `client.flush()` discards inbound bytes on arduino-esp32 2.x (`WebSocketsUdp.cpp:54,66,356-394`, `ControllerWebSocket.cpp:120,160`) → random WS disconnects/lost ACKs. Remove.
2. Payment queue never expires; ≥18 stranded records blocks all arming; factory reset doesn't clear `pay_queue` (`PaymentQueueManager.cpp`, `CoinSlotManager.cpp:199`). Add TTL/eviction + admin clear.
3. Relay power-on glitch counted as coin (`CoinSlotManager.cpp:252-256`) → blanking window ~400 ms; debounce check pin level in ISR (`HardwareManager.cpp:142-150`).
4. WS pong refreshes arm TTL → abandoned phone holds slot 120 s (`WebSocketsUdp.cpp:450`).
5. Master clock poisoned by one phone's wrong clock → other phones 403 (`WebSocketsUdp.cpp:345,384`, `DeviceManager.cpp:191`).
6. OTA upload can trip 15 s watchdog; `Update.end` before busy check (`WebServerModule.cpp:104-170`).
7. Daily/heap reboot doesn't flush revenue / pending payments (`main.cpp:47-57`).
8. `DEV_<ip>` fallback sends `/add_time` to invalid host (`WebServerApi.cpp:405`, `DeviceManager.cpp:354`); tx-id collisions when master time = 0.
9. No AP fallback for bad Wi-Fi creds; TX power 8.5 dBm on all boards (`main.cpp:101,176`).

## Phase 3: Kiosk lockdown (decided)
In `security/KioskPolicyManager.kt`:
1. **USB debugging off by default** — `isAdbAllowed` default → `false` (`KioskSecurity.kt:179`); admin can still enable via vault PIN / `ENABLE_ADB` recovery path. Keep WebADB provisioning working (ADB is only disabled after provisioning completes).
2. **Block Wi-Fi changes** — add `DISALLOW_CONFIG_WIFI` for renters; lift it temporarily during admin bypass so admin can still reconfigure.
3. **Remove Settings / Play Store / GMS UI / package installer from `setLockTaskPackages`** (:44-54,140-165). Admin bypass (`AppLauncher.launchSettings`) temporarily re-adds Settings to lock task packages and removes it when bypass ends/locks. Keep GMS core if needed for customer apps (non-launcher background service) — verify on device.
4. **Battery alert nagging** — high-battery alert only fires once per charge cycle (not every ~20 s) and never mutes/strobes during an active session; low-battery alert unchanged (`system/KioskSystemMonitor.kt:194,265-271`).
5. Also (no decision needed): drop `setup_secret/mac/slot` handling from exported `MainActivity` intent unless WebADB flow needs it — WebADB uses `am start ... SETUP_DIRECT` (`webadb_manager.js:756`), so gate it to the 30-min setup window like the receiver; `/ping` on phone must not re-point `esp32Ip` without MAC/HMAC check (`KioskServerCoordinator.kt:47-56`).

Not in scope (owner: "not now"): customer data wipe between rentals — recorded as known risk.

## Phase 4: Bloat removal (safe)
- Untrack `esp32_firmware/.pio`, `scripts/__pycache__`; delete `metadata.json`, `.env.example`, `esp32_firmware/data/*`, `enable_security_hooks.py`.
- Gradle: drop zxing, Roborazzi, Firebase BOM, secrets plugin, androidTest deps, unused lifecycle/compose libs, ~20 dead catalog entries.
- Dead Kotlin (zero refs): custom-keystore secret fns, `getBatteryDiagnostics`, `getApkUpdateUrl/set…`, `KioskDataCleaner`, `isSystemPackageWhitelisted`, `addTimeFromMaster`, single-ref helpers, unused colors; duplicate `ACTION_*` strings; `isProvisioned()` (never true).
- JS: dead `serverLicenseData`, `SETUP_DIRECT`, ~170 lines of unused methods in `webadb_manager.js`.
- Firmware: ~125 lines uncalled fns, write-only `is_licensed`.
- With owner OK: inline `isAppAllowedToRun()` (+ `appState 4`), legacy pre-Room migration, no-op `setSharedSecret`, CAMERA perm.

## Phase 5: Build/CI/installer (release pipeline is currently broken)
1. ✔ **Every dev commit deletes `website/update/app-release.apk`** (seen in last 8 commits: D by owner, A by bot). `.gitignore:25 *.apk`; `!website/update/` doesn't un-ignore. Fix: `!website/update/*.apk` (and `!*.bin`), or move binaries to GitHub Releases. Bot re-add commits are `[skip ci]` → Cloudflare Pages may never deploy them (verify in Pages settings).
2. ✔ `update-firmware-json.yml:32-33` writes multi-line commit msg to `$GITHUB_ENV` → job fails; use heredoc form. Regenerate correct sha256/version once `firmware.bin` is rebuilt.
3. `build-apk.yml`: add `concurrency` group, widen path filter (`gradle/**`, `*.gradle.kts`, `gradle.properties`), add `testDebugUnitTest` step, fail loudly when keystore env missing.
4. versionCode auto-increment (e.g. from `GITHUB_RUN_NUMBER`); app updater compares version + skips identical; validate download is an APK (not Pages' HTML fallback).
5. Fix failing unit tests: `KioskHttpServerUnitTest:150` (stale ts now accepted — decide reject vs test update), `Esp32DiscoveryScannerUnitTest:38` (uses no-op `setSharedSecret`); pin Robolectric SDK via `robolectric.properties`.
6. Installer (`website/js/webadb_manager.js`, `index.html`):
   - "already device owner" / multiple users misreported as "account logged in" (:719) — distinguish messages, allow re-provision.
   - `pm install` failure reported as success if old package exists (:661-677, :850) — surface real error (signature mismatch etc.).
   - Shell injection via URL params (`index.html:341-345`, js :749-752, unquoted `slot`) — validate MAC/slot/name format, single-quote escape.
   - Deprovision redirect `http://null/` (`index.html:452`); ADB auth no timeout (:298); MIUI/Samsung guidance.
7. Firmware OTA: one `firmware.bin` for two chip families — publish per-chip files; embed FW version and compare + check sha256 in dashboard; add `_headers` CORS for `firmware.*` if needed.

## Execution order
One commit per phase (small, reviewable): Phase 5.1–5.2 (stop APK deletion + CI) → Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 bloat → remaining Phase 5. Pause and ask owner before: classic-ESP32 pin values, stale-`ts` reject-vs-accept in `/add_time`, and any Phase 4 "with owner OK" item.

## Verification
- `./gradlew testDebugUnitTest assembleRelease` after each phase; new unit tests for `onSlotBusy`/`onSlotLockdown` transitions, ack-on-success-only, bypass keeps balance.
- `pio run` for each env; check image size vs partitions; verify OTA with a correctly committed `firmware.bin` (magic `E9`, sha matches json).
- Device: Android 7 overlay; arm busy in state 2 keeps session; failed credit not acked; Wi-Fi settings bypass keeps balance; expiry stops media; OTA on encrypted build.
