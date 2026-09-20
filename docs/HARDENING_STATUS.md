# PisoPhone Hardening Status & System Baseline

This document tracks the current stage of the bounded hardening process, verified baseline metrics, environment parameters, build gates, known risks, and the immediate next step.

Future hardening stages MUST consult this file and `docs/HARDENING_RULES.md` as the authoritative system status.

---

## 1. System Baseline Specification

| Property | Value / Specification |
| :--- | :--- |
| **Source Commit Baseline** | `9915da3` (Quick Adjust Time repair commit) |
| **Android Target SDK** | `compileSdk = 36` (minorApiLevel = 1), `targetSdk = 36`, `minSdk = 24` (Android 7.0+) |
| **Supported Phone Targets** | Android 7.0 through Android 15/16; ARM64 (`arm64-v8a`) & ARMv7 (`armeabi-v7a`) |
| **Android Application ID** | `com.pisophone.kiosk` (`versionCode = 1`, `versionName = "1.0"`) |
| **Installed Firmware Version** | Version `3.0.301` (Build `301`, Release Date `2026-09-15`) |
| **Hardware Controller Platforms** | • **ESP32 Classic** (`esp32dev`): Xtensa Dual-Core 32-bit LX6 @ 240MHz, 520KB SRAM, 4MB Flash.<br>• **ESP32-C3** (`esp32-c3-devkitm-1`): RISC-V 32-bit Single-Core @ 160MHz, 400KB SRAM, 4MB Flash. |
| **Flash Partition Schemes** | • Development: `min_spiffs.csv` (1.9MB app partition).<br>• Production: `partitions_encrypted.csv` (Flash Encryption enabled, DIO mode). |
| **Room Database Version** | Version 5 (schema exported to `app/schemas/com.pisophone.kiosk.db.AppDatabase/5.json`) |

---

## 2. Dependency Versions (Pinned)

### 2.1 Android Build System & Libraries
- **Gradle**: 9.3.1
- **AGP (Android Gradle Plugin)**: `9.1.1`
- **Kotlin**: `2.0.21`
- **KSP**: `2.0.21-1.0.28`
- **Jetpack Compose BOM**: `2024.09.00`
- **Room Database**: `2.7.0` (`room-runtime`, `room-ktx`, `room-compiler`)
- **AndroidX Security Crypto**: `1.1.0-alpha06`
- **OkHttp**: `4.10.0`
- **NanoHTTPD**: `2.3.1` (Kiosk HTTP Server on port 8080)
- **Robolectric**: `4.16.1`
- **Roborazzi**: `1.59.0`
- **Secrets Gradle Plugin**: `2.0.1`

### 2.2 ESP32 Firmware Toolchain & Libraries
- **PlatformIO Core**: `6.2.0`
- **Platform**: `espressif32 @ 7.1.3`
- **Arduino Core**: `framework-arduinoespressif32 @ 4.20017.260907+sha.dcc1105b` (Arduino 2.0.0 / IDF v4.4.7)
- **Toolchains**:
  - RISC-V (ESP32-C3): `toolchain-riscv32-esp @ 8.4.0+2021r2-patch5`
  - Xtensa (ESP32 Classic): `toolchain-xtensa-esp32 @ 8.4.0`
- **ESP Libraries**:
  - `bblanchon/ArduinoJson @ 6.21.3` (locked / resolved `6.21.6`)
  - `HTTPClient`, `WiFi`, `WiFiClientSecure`, `Preferences`, `WebServer`, `ESPmDNS`, `Update`

---

## 3. Recoverable Baseline Artifacts & Checksums

The following artifacts represent the verified operational baseline. Arbitrary local rebuilds MUST NOT be labeled as field-tested without explicit verification. Recoverable copies are preserved in `artifacts_baseline/`.

| Artifact File | Size | SHA-256 Checksum | Notes |
| :--- | :--- | :--- | :--- |
| `website/update/app-release.apk` | 23.4 MB | `2b864845a762b200af568ef76f7afc717966b6e83cfed9d2805a45a3e5064a93` | Field baseline release APK |
| `website/app-release.apk` | 23.4 MB | `2b864845a762b200af568ef76f7afc717966b6e83cfed9d2805a45a3e5064a93` | Root mirror of release APK |
| `website/update/firmware.bin` | 1.13 MB | `6a6117477de7441835dd9e6ffbd7590eab532d4a135c24c939660776841edced` | Field baseline firmware binary |
| `.build-outputs/app-debug.apk` | 74.5 MB | `6bcc7897c2076419210b243501f9ccb01c377894737c1dce673dfba8d49c9d5e` | Initial debug build artifact |

---

## 4. Release Signing Requirements & Identity Preservation

- **Release Signing Identity**: The production release signing keystore is managed exclusively via GitHub Actions secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
- **Identity Preservation**: Under no circumstances should the release signing configuration replace, alter, or rotate the production key alias (`KEY_ALIAS`). The debug keystore fallback (`debug.keystore.base64`) is provided strictly for non-production development workflows and MUST NOT overwrite the production signing identity.
- **Prohibited Actions**: Hardcoding production keystore secrets or passwords into `.gradle.kts` or source files is strictly prohibited.

---

## 5. Current Hardening Stage & Verification Gates

### Current Stage
**Stage 4: Coin Slot Management & Hardware Lifecycle Hardening** (COMPLETED).

### Executed Verification Gates
- [x] **Android Unit Tests**: Passed (`gradle :app:testDebugUnitTest`). 79 tests executed across payment accounting, schema v5 migration, duplicate idempotency, conflict detection, negative deductions, and two-phone match transfer evaluation.
- [x] **Native C++ Contract, Payment Queue & Coin Slot State Tests**: Passed (`test_protocol_contract.cpp`, `test_payment_queue.cpp`, and `test_coin_slot_state.cpp` compiled with `g++ -O2 -std=c++17`). All invariant test suites passing.
- [x] **Android Build**: Passed (`compile_applet` / assembleDebug).
- [x] **ESP32 Firmware Compilation**: Passed (`pio run -d esp32_firmware`). Flash: 68.2%, RAM: 16.7%.
- [x] **Single-Owner State Machine**: Main loop (`processCoinSlotSession`) is the exclusive owner of coin session state transitions; network/busy queries are pure queries and do not silently mutate state or reset buffers.
- [x] **Explicit State Lifecycle**: Managed explicitly through `IDLE`, `RESERVED_ARMING`, `ARMED`, `DRAINING`, and `FAULT_MAINTENANCE`.
- [x] **Unified Drain Path**: All termination reasons (`DISCONNECT`, `TTL_EXPIRED`, `MAX_DURATION`, `STORAGE_UNAVAILABLE`, `MAINTENANCE`, `CANCELED`, `DONE`) route through `initiateSessionRelease` and `finalizeSessionRelease`, ensuring in-flight pulses are harvested and delivered to the original recipient.
- [x] **Startup Pulse Suppression & Relay Lock**: 3000ms one-time startup pulse suppression window with physical relay held in high-Z/OFF state until complete.
- [x] **Rollover-Safe Time Arithmetic**: Implemented `(int32_t)((uint32_t)now - (uint32_t)target) >= 0` across all timing intervals, timeouts, and guards.
- [x] **Clean ISR Boundary**: ISR (`universalCoinIsr`) restricted exclusively to debounce timing, counter increment, and timestamp capture without allocations, flash I/O, or logging.

---

## 6. Smoke Checklist

### Executed Baseline Checks (Automated / In-Container)
- [x] **Protocol Specification & Test Vectors**: Defined in `docs/PROTOCOL.md` and `test_vectors/protocol_test_vectors.json`.
- [x] **Pure Encode/Verify Helpers**: `ProtocolFraming.h` and `KioskProtocol.kt`.
- [x] **WebSocket Envelope Exposure**: Outer HMAC verification before payload decryption.
- [x] **Room Database Migration**: MIGRATION_4_5 verified with full backward compatibility and zero data loss.
- [x] **Quick Adjust Time Logic**: Positive addition and signed negative subtraction with idempotent ACKs.
- [x] **Payment Queue Persistence**: Retain counted pulses in-memory, dispatch only durably written records.
- [x] **Match Transfer Outcomes**: Two separate linked outcomes reporting partial completion.
- [x] **Dual Architecture Compilation**: ESP32 dual-core and ESP32-C3 single-core compilation clean.

### Hardware Validation Checklist (Pending Physical Bench Execution)
- [ ] **Physical Coin Credit**: Insert ₱1/₱5/₱10 coin; verify pulse counting and WebSocket delivery to target slot.
- [ ] **Duplicate Delivery**: Replay same coin transaction ID; confirm single-credit enforcement.
- [ ] **Session Expiry**: Allow timer to reach 0; verify graceful transition to block screen.
- [ ] **Phone & Box Restart**: Reboot phone during active rental; verify monotonic deadline restoration from saved credit. Power cycle ESP32; confirm client automatic reconnection.
- [ ] **Quick Add/Subtract**: Trigger +1m and -1m from web dashboard on active running rental and expired rental; verify UI feedback.
- [ ] **Match Transfer**: Execute 1v1 arena stake transfer; verify balance accuracy on both docks.
- [ ] **Controller Isolation**: Connect virtual controller on Slot 1; verify zero leakage into Slot 2.

---

## 7. Known Risks & Notes
- **Web Server APK Delivery**: Website previously returned HTML instead of binary APK; Cloudflare Pages / `_headers` / `_redirects` configuration must be maintained to serve `application/vnd.android.package-archive`.
- **Flash Encryption**: Production firmware builds require `CONFIG_SECURE_FLASH_ENC_ENABLED=1` and `partitions_encrypted.csv`; development builds must remain unencrypted to allow development iteration.

---

## 8. Immediate Next Step
- **Next Step**: Stage 5 Bounded Hardening — Controller reservation adapters, hardware bench validation of physical coin insertion, match transfers across two physical devices, and recovery across power cycle events.
