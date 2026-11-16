# Standalone PisoWiFi ESP32 Firmware (`esp32_wifi_firmware`) & Gateway Protocol Extraction

## Goal

Create a dedicated, self-contained ESP32 firmware in `esp32_wifi_firmware/` whose sole responsibility is reading physical coin pulses and communicating with the OpenWrt Rust listener (`pisoportal`) over the gateway protocol, with **zero trace of PisoPhone** (`phone`, `kiosk`, `pisophone`, `license`, device slots, ADB/WebSocket pairing, or browser dashboard UI).

---

## Key Design Decisions & `/grill-me` Edge-Case Resolutions

1. **Self-Contained Firmware Directory (`esp32_wifi_firmware/`)**:
   - Built independently with its own `platformio.ini`, `.clang-format`, `include/`, `src/`, and `host_tests/` (no cross-directory `#include` into `esp32_firmware/`).
   - Replaces the `#ifdef PISO_PRODUCT_WIFI` build target for PisoWiFi CI/OTA (`esp32-c3-dev-wifi`, `esp32dev-dev-wifi`) while leaving `esp32_firmware/src/`, `esp32_firmware/include/`, `esp32_firmware/platformio.ini`, and `esp32_firmware/envs/` **100% untouched** so existing full TechNet PisoHub (`esp32-c3-dev`, `esp32dev-dev`) and PisoPhone-only (`esp32-c3-dev-phone`, `esp32dev-dev-phone`) builds are completely unaffected.
2. **Extracted Gateway Protocol + Shared Test Vectors (`protocol/`)**:
   - Preserves 100% wire compatibility with `tools/pisoportal/src/boxlink.rs` and `setup/src/50-coin-box.sh` (`gw1` HTTP challenge/HMAC request signing, `gw1r` response signing, and `gw1ev` UDP fast-path coin event packets).
   - Extends `protocol/gen_vectors.py` to generate `protocol/fixtures/gateway_v1.json`, verified by C++ host tests (`esp32_wifi_firmware/host_tests/gateway_protocol_test.cpp` and `esp32_firmware/host_tests/gateway_auth_test.cpp`).
3. **Headless Daemon + Console Upgrade Promotion Banner**:
   - No embedded coin-box configuration dashboard UI, no browser session cookies (`AdminSession`), no `OriginCheck` CSRF layer, and no `SuperAdmin` QR/Vault challenge-response.
   - Authenticates router management calls strictly via HTTP Basic Auth (`admin:<password>`) + `LoginThrottle`.
   - `GET /api/status` returns `200 OK` JSON (`{"ok":true,"firmware":"..."}`) for router health checks (`piso-setup verify` and `piso-setup box-update`), while `GET /` serves an HTML **Contact TechNet Solutions** upgrade promotion banner (`200 OK`, synced to the `admin.hub` theme) for the router console tab so standalone PisoWiFi operators are invited to upgrade to full TechNet PisoHub without any phone-slot UI or state.
4. **Zero-Exception Product Strip & OTA Signature Transition (Gap #1)**:
   - Uses NVS namespace `"pisowifi_cfg"` (instead of `"kiosk_cfg"`).
   - Uses `OwnerPubKey.h` (`OWNER_PUBKEY_DER` / `OWNER_PUBKEY_LEN`) and ECDSA P-256 / SHA-256 verification over `"pisowifi-fw-v1|<chip>|<version>|<sha256>|<size>"` in `FwManifest.h` (instead of `LicensePubKey.h` and `"pisophone-fw-v1|..."`).
   - `scripts/verify_release.py` accepts the legacy `"pisophone-fw-v1|..."` signature on the currently checked-in `website/update/wifi/firmware.json` (`<= 3.12.6`) until `main` CI re-signs `website/update/wifi/firmware.json` for `3.12.7` with `"pisowifi-fw-v1|..."`.
   - Omits `lifetime_phone_pulses` from `/api/gateway/status` (`tools/pisoportal/src/reconcile.rs` reads `lifetime_wifi_pulses` and falls back to `lifetime_pulses`).
   - `scripts/check_product_strip.py` scans `esp32_wifi_firmware/` with **zero allowlisted exceptions**.
5. **`/api/gateway/ack` Wire Contract & Drain Race Guard (Gap #2)**:
   - Matches `tools/pisoportal/src/core.rs` (`self.box_.call(sid, "ack", "")` — no `pulses` query parameter): `ack` clears the session's stored pulses and returns `acknowledged_pulses`.
   - **Drain Race Fix**: If `/api/gateway/ack` is called for `session` while that `session` is still `ARMED` or `DRAINING`, the box refuses with `409 SLOT_BUSY` so a coin still sliding down the chute during the 5-second drain cannot finish 200ms after `ack` and create an orphaned NVS record. `pisoportal` naturally retries and settles via `recover_recorded()` once `state == "idle"`.
   - **NVS Erase Failure Guard**: If `Preferences::remove()` fails on `ack`, the pulses remain in RAM and the endpoint returns `503 ACK_INCOMPLETE` so RAM and flash never diverge across reboots.
6. **Mid-Window Re-Arm Deadlock Fix & Full Queue Eviction (Gap #3)**:
   - Because `pisoportal` calls `/api/gateway/arm` repeatedly for the same `session` (after the 500ms settle and after *every* coin to extend `idle_wait`), `gatewayArm(session)` **always allows** re-arming if `session` is already the active session or already occupies a slot in `GatewaySessionStore` — preventing a `503 STORAGE_UNAVAILABLE` mid-payment when the first coin fills the 16th slot.
   - When a **new** `session` calls `arm` and all 16 slots are occupied, `GatewaySessionStore` first runs TTL eviction (`>24h`, or `>30m` when `>= 14/16` slots are occupied, never evicting the active/draining session). If all 16 slots remain occupied by fresh (`<30m`) sessions, `arm` returns `503 STORAGE_UNAVAILABLE`.
7. **Deterministic UDP `"end"` & `"ready"` Event Sequencing (Gap #4)**:
   - Emits the UDP `"end"` packet (`2x`) directly from the `CoinSlotManager` drain-completion callback (and flushes any pending `"end"` if `gatewaySetEventTarget` switches to a new `(session, wid)`), eliminating the loop-ordering race where back-to-back sessions could overwrite `evt` before `gatewayEventsLoop()` sent `"end"`.
   - Tracks power-on settle per window so `"ready"` is sent once when the 500ms settle completes (and on the post-settle re-arm), rather than resetting `readySent = false` and firing a spurious `"ready"` packet after every coin re-arm.
8. **Atomic `life_pulses` Persistence, Dual-Clock TTL & Factory-Reset Preservation (Gap #5)**:
   - **Atomic `life_pulses` write**: `GatewaySessionStore::recordPulses(session, count)` writes `life_pulses` to NVS immediately alongside the session slot (not deferred by 5–20s), ensuring a power cut right after a coin is credited by the router never leaves `lifetime_wifi_pulses` behind the router's ledger in `reconcile.rs`.
   - **Dual-clock TTL check**: Tracks both monotonic `firstSeenMs` (`millis()`) and synced wall-clock `createdAtMasterMs` (`getCurrentMasterTimeMs()`, which is `0` until the router sends `&ts >= 1700000000`). Wall-clock elapsed time is only used when both `createdAtMasterMs > 0` and `masterNow > createdAtMasterMs`, preventing an NTP clock jump from treating a pre-sync session as 54 years old and evicting it.
   - **Storage probe recovery**: If an NVS write fails (`storageReady_ = false`) and the slot is later acked or evicted, a 5-second background probe (`probeStorage()`) restores `storageReady_ = true` as soon as NVS accepts writes again.
   - **Factory reset preservation**: Holding the 10s reset button or calling `POST /factory_reset` resets Wi-Fi (`PisoCoinBox` / `PisoCoinBox@Setup`), admin password (`Coinslot@Setup`), gateway key, and GPIO pins in `"pisowifi_cfg"`, while **preserving** `life_pulses` and unacknowledged sessions in `"pay_queue"`.
9. **Independent Versioning (`3.12.7`) & Full TechNet Isolation**:
   - Bump `PISO_FW_VERSION` in `esp32_wifi_firmware/include/FirmwareVersion.h` to `"3.12.7"` (one patch bump above `origin/main`'s `"3.12.6"`), while keeping `esp32_firmware/include/FirmwareVersion.h` untouched at `"3.12.6"`.
   - Scope is strictly limited to `esp32_wifi_firmware/`, `esp32_firmware/host_tests/`, `protocol/`, `scripts/`, `docs/plans/esp32-wifi-firmware.md`, and `.github/workflows/` (no `esp32_firmware/src/`, `esp32_firmware/include/`, `esp32_firmware/envs/`, `setup/`, or router files modified).

---

## Architecture & Protocol Flow

```mermaid
sequenceDiagram
    participant R as OpenWrt Router (pisoportal / boxlink.rs)
    participant G as ESP32 Gateway HTTP & UDP (GatewayCoinslot)
    participant S as GatewaySessionStore (NVS "pay_queue")
    participant C as CoinSlotManager & HardwareManager (ISR)

    R->>G: GET /api/gateway/challenge
    G-->>R: 200 {"nonce":"<32-hex>"}
    Note over R,G: sig = HMAC-SHA256(key, "gw1:arm:<session>:<nonce>")
    R->>G: GET /api/gateway/arm?session=S&nonce=N&sig=HMAC&ts=T&duration=D&wid=W&evport=P
    G->>C: reserveCoinSlot(S, duration_ms) (Inhibit HIGH, 500ms settle)
    G-->>R: 200 {"success":true,"state":"armed",...,"rsig":"HMAC(gw1r:arm:...)"}
    C-->>G: 500ms settle elapsed
    G-->>R: UDP 2x: "gw1ev:S:W:1:ready:0:<sig>\n"
    C->>C: Physical coin pulses (15ms debounce, 280ms train end)
    C->>S: recordPulses(S, pulses) [NVS slot + life_pulses persisted FIRST]
    G-->>R: UDP 2x: "gw1ev:S:W:2:coin:<total_pulses>:<sig>\n"
    R->>G: GET /api/gateway/release?session=S&nonce=N2&sig=HMAC&ts=T
    G->>C: releaseCoinSlot(S) -> DRAINING (Inhibit LOW, 5s chute drain)
    G-->>R: 200 {"success":true,"state":"draining",...,"rsig":"..."}
    C-->>G: 5s drain elapsed -> IDLE (onSessionEnded callback)
    G-->>R: UDP 2x: "gw1ev:S:W:3:end:<total_pulses>:<sig>\n"
    R->>G: GET /api/gateway/ack?session=S&nonce=N3&sig=HMAC&ts=T
    G->>S: acknowledge(S) [Refuses with 409 if still ARMED/DRAINING; erases NVS slot]
    G-->>R: 200 {"success":true,"session":"S","acknowledged_pulses":P,"rsig":"HMAC(gw1r:ack:...)"}
```

---

## Proposed Changes

### 1. Protocol Fixtures (`protocol/`)

#### [MODIFY] `protocol/gen_vectors.py`
- Extend `protocol/gen_vectors.py` to also generate `protocol/fixtures/gateway_v1.json` containing deterministic test vectors for:
  - `gw1` request signatures (`arm`, `status`, `release`, `ack`, `config` key change)
  - `gw1r` status response `rsig` (`gw1r:<action>:<session>:<nonce>:<state>:<armed_remaining>:<pulses>:<lifetime_pulses>:<slot_free>:<ready_in_ms>:<minutes_per_coin>`)
  - `gw1r` ack response `rsig` (`gw1r:ack:<session>:<nonce>:<acknowledged_pulses>`)
  - `gw1ev` UDP event lines (`ready`, `coin`, `end`)

#### [NEW] `protocol/fixtures/gateway_v1.json`
- Generated fixture file checked into `protocol/fixtures/gateway_v1.json` and verified by C++ host tests in both `esp32_wifi_firmware/host_tests/` and `esp32_firmware/host_tests/`.

---

### 2. New Standalone PisoWiFi ESP32 Firmware (`esp32_wifi_firmware/`)

#### [NEW] Build, Formatting & Partition Files
- `esp32_wifi_firmware/platformio.ini`: Standalone PlatformIO configuration targeting `espressif32 @ 7.1.3` with `esp32-c3-dev-wifi` and `esp32dev-dev-wifi` (only `ArduinoJson @ 6.21.6` as `lib_deps`; no WebSockets library).
- `esp32_wifi_firmware/.clang-format`: Identical formatting rules to `esp32_firmware/.clang-format`.
- `esp32_wifi_firmware/envs/esp32_c3_dev.ini` & `esp32_wifi_firmware/envs/esp32dev_dev.ini`: Board definitions (`esp32-c3-devkitm-1` and `esp32dev`) with pin macros (`PISO_PIN_COIN`, `PISO_PIN_LED`, `PISO_PIN_RELAY`, `PISO_PIN_RESET`) and `-DCORE_DEBUG_LEVEL=0`.
- `esp32_wifi_firmware/partitions_4mb.csv` & `esp32_wifi_firmware/partitions_c3_4mb.csv`: Dual-OTA 4MB partition tables.

#### [NEW] Pure-C++ Headers (`esp32_wifi_firmware/include/`)
- `FirmwareVersion.h`: `#define PISO_FW_VERSION "3.12.7"`.
- `OwnerPubKey.h`: `OWNER_PUBKEY_DER` and `OWNER_PUBKEY_LEN` (SPKI ECDSA P-256 public key for OTA verification, named without `License`).
- `FwManifest.h`: ECDSA P-256 manifest verifier and streaming SHA-256 `ImageVerifier` using `"pisowifi-fw-v1|<chip>|<version>|<sha256>|<size>"`.
- `GatewayAuth.h` & `GatewayEvent.h`: Extracted gateway HTTP + UDP protocol headers (with clean comments and zero PisoPhone references).
- `CredCrypto.h`, `PulseGuard.h`, `CoinSettle.h`, `CoinHwApply.h`, `CoinCredit.h`, `PinRules.h`, `SetupGate.h`, `SerialProvision.h`, `OtaCheck.h`, `OtaConfirm.h`, `HealthPolicy.h`, `RestartGuard.h`, `DiagRing.h`, `InputSafety.h` (including `LoginThrottle`), `WifiLink.h`, `CredGen.h`, `MasterClock.h`, `ConfigMigration.h`.

#### [NEW] Runtime Modules (`esp32_wifi_firmware/include/` & `esp32_wifi_firmware/src/`)
- `Config.h` / `Config.cpp`: NVS settings in `"pisowifi_cfg"` (Wi-Fi SSID/password, admin password, `adminPwChanged`, `wifiWaitFirstJoin`, GPIO pins, `minutesPerCoin`, `coinPulseGuard`, `coinPulseMinMs`, `relayActiveLow`, `ledActiveLow`, and master clock).
- `GatewaySessionStore.h` / `GatewaySessionStore.cpp`: Dedicated NVS store in `"pay_queue"` with:
  - 16 session slots (`rec_00`..`rec_15`) + monotonic `life_pulses` counter (`life_pulses` persisted immediately inside `recordPulses()`).
  - `hasSession(session)` so re-arming an active or already-stored session is always allowed even when all 16 slots are occupied.
  - `acknowledge(session)` returning `AckResult` (`Ok`, `Busy`, `StorageError`).
  - Exponential backoff NVS write retries, 5-second `probeStorage()` recovery, dual-clock (`millis()` + synced `masterNow`) TTL eviction (`24h` normal, `30m` when `>= 14/16` slots used, skipping the active/draining session), and preservation across operator factory resets.
- `HardwareManager.h` / `HardwareManager.cpp`: Coin pulse ISR, inhibit pin, relay pin, LED indicator, and 10s physical factory reset button.
- `CoinSlotManager.h` / `CoinSlotManager.cpp`: Single-owner coin slot state machine (`IDLE`, `ARMED`, `DRAINING`) with 500ms power-on settle and 5s chute drain.
- `GatewayCoinslot.h` / `GatewayCoinslot.cpp`: Gateway state coordinator (`arm`, `status`, `release`, `acknowledge`, UDP `ready`/`coin`/`end` events with deterministic `end` emission on session end).
- `WebServerAuth.h` / `WebServerAuth.cpp`: HTTP Basic Auth (`admin:<password>`) + `LoginThrottle` (no browser cookies, no CSRF origin check, no SuperAdmin QR).
- `WebServerModule.h` / `WebServerModule.cpp`: HTTP server implementing `/api/gateway/*`, `/save`, `/reboot`, `/factory_reset`, `/api/relay`, `/api/diagnostics`, `/api/ota/manifest`, `/update`, `/`, and `/api/status`.
- `Diagnostics.h` / `Diagnostics.cpp`, `OtaSecurity.h` / `OtaSecurity.cpp`, `OtaRollback.h` / `OtaRollback.cpp`, `SerialCli.h` / `SerialCli.cpp`, `main.cpp`.

#### [NEW] Host Test Suite (`esp32_wifi_firmware/host_tests/`)
- `esp32_wifi_firmware/host_tests/run.sh`: Compiles and runs all host tests with `g++ -std=c++17 ... -lmbedtls -lmbedcrypto`.
- `gateway_protocol_test.cpp`: Tests `GatewayAuth`, `GatewayEvent`, and verifies every vector in `protocol/fixtures/gateway_v1.json`.
- `session_store_test.cpp`: Tests `GatewaySessionStore` pulse accumulation, immediate `life_pulses` persistence, `ack` refusal while `ARMED`/`DRAINING`, NVS erase failure (`ACK_INCOMPLETE`), re-arming when 16/16 slots are full, dual-clock NTP jump safety, TTL eviction, NVS write retry + storage probe recovery, and factory-reset preservation.
- `fw_manifest_test.cpp`: Tests `FwManifest.h` with `"pisowifi-fw-v1|"` ECDSA P-256 signatures, chip mismatch rejection, and downgrade rejection.
- `coin_pulse_test.cpp`: Tests `PulseGuard`, `CoinSettle`, `CoinHwApply`, `PinRules`, `SetupGate`, `SerialProvision`, and `RestartGuard`.

---

### 3. Preserving `esp32_firmware/` for Existing Full TechNet & PisoPhone Builds

#### [KEEP UNTOUCHED] `esp32_firmware/src/`, `esp32_firmware/include/`, `esp32_firmware/platformio.ini`, `esp32_firmware/envs/`
- Leave `esp32_firmware/include/Product.h`, `esp32_firmware/envs/products.ini`, `esp32_firmware/include/FirmwareVersion.h` (`"3.12.6"`), and all `esp32_firmware/src/` files **100% untouched** so existing full TechNet PisoHub (`esp32-c3-dev`, `esp32dev-dev`) and PisoPhone-only (`esp32-c3-dev-phone`, `esp32dev-dev-phone`) binaries remain byte-for-byte unaffected and do not trigger an unintended `full` OTA release.

#### [MODIFY] `esp32_firmware/host_tests/gateway_auth_test.cpp` & `esp32_firmware/host_tests/gen_protocol_fixture.py`
- Add verification of `protocol/fixtures/gateway_v1.json` so both firmwares test against the shared protocol fixture.

---

### 4. Product Strip Check, Signing Scripts & CI Workflows (`scripts/` & `.github/workflows/`)

#### [MODIFY] `scripts/check_product_strip.py`
- For `"wifi"`, scan every `.h` and `.cpp` file in `esp32_wifi_firmware/include` and `esp32_wifi_firmware/src` against `phone|kiosk|pisophone|rental|licen[cs]e|slotlic|profile|account|\bpair|android|\bapp\b|trackeddevice|deviceslot|deviceconfig|devicetelemetry|devicecap|websocket|discovery|slotnum|slotactiv|slotcloud|superadmin|vault|orphan` with **zero allowlist exceptions** (`"allow": []`).

#### [MODIFY] Firmware Signing, Key & Verification Scripts
- `scripts/sign_firmware.py`: Add `--product {full,phone,wifi}` (default `full`), using `"pisowifi-fw-v1|..."` when `--product wifi` and `"pisophone-fw-v1|..."` otherwise.
- `scripts/publish_firmware_ota.py`: For `--product wifi`, read `esp32_wifi_firmware/include/FirmwareVersion.h` and `esp32_wifi_firmware/include/OwnerPubKey.h`, and pass `--product wifi` to `sign_firmware.py`.
- `scripts/owner_key.py` & `scripts/make_owner_keys.py`: Write/update `esp32_wifi_firmware/include/OwnerPubKey.h` alongside `esp32_firmware/include/LicensePubKey.h`.
- `scripts/verify_release.py`:
  - Verify that `esp32_wifi_firmware/include/OwnerPubKey.h` matches `esp32_firmware/include/LicensePubKey.h` and `tools/pisoportal/owner_key.b64`.
  - Verify `esp32_wifi_firmware/include/FirmwareVersion.h` independently of `esp32_firmware/include/FirmwareVersion.h` and ensure it is bumped whenever `esp32_wifi_firmware/` changes.
  - For `website/update/wifi`, verify `"pisowifi-fw-v1|..."` signatures (while allowing `"pisophone-fw-v1|..."` for legacy `<= 3.12.6` manifests until `main` CI re-signs `website/update/wifi/firmware.json`).
- `scripts/check_security_rules.sh`: Include `esp32_wifi_firmware` in `CODE_DIRS` and source-folder file checks.
- `scripts/tests/test_publish_firmware.py`: Add unit tests for `--product wifi` signing (`pisowifi-fw-v1|...`).

#### [MODIFY] GitHub Actions Workflows (`.github/workflows/`)
- `.github/workflows/firmware-images.yml`: Add `'esp32_wifi_firmware/**'` to push trigger paths and cache key; build `esp32-c3-dev-wifi` and `esp32dev-dev-wifi` in `esp32_wifi_firmware/` while building `full` and `phone` in `esp32_firmware/`.
- `.github/workflows/quality.yml`: Add `'esp32_wifi_firmware/**'` to trigger paths and `firmware_build`/`firmware_tests` change filters; run `clang-format`, `sh esp32_wifi_firmware/host_tests/run.sh`, `OwnerPubKey.h` check, and PlatformIO compile matrix (`esp32-c3-dev-wifi`, `esp32dev-dev-wifi`) on `esp32_wifi_firmware/`.

---

## Verification Plan

1. **Protocol Fixtures & Rust Listener Tests**:
   - `python3 protocol/gen_vectors.py`
   - `cargo test --manifest-path tools/pisoportal/Cargo.toml`
2. **Host C++ Tests (Both Firmwares)**:
   - `sh esp32_wifi_firmware/host_tests/run.sh`
   - `sh esp32_firmware/host_tests/run.sh`
3. **Zero-Exception Product Strip & Security Rules**:
   - `python3 scripts/check_product_strip.py`
   - `sh scripts/check_security_rules.sh`
4. **OTA Publishing & Release Verification**:
   - `python3 scripts/tests/test_publish_firmware.py`
   - `python3 scripts/verify_release.py`
5. **PlatformIO Firmware Builds**:
   - `pio run -d esp32_wifi_firmware -e esp32-c3-dev-wifi -e esp32dev-dev-wifi`
   - `pio run -d esp32_firmware -e esp32-c3-dev -e esp32dev-dev`
