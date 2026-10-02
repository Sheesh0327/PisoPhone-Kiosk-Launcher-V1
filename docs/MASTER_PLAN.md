# PisoPhone: production readiness master plan

Scope: the whole repository at `main` (`00e5f43`): Android launcher (`app/`), ESP32 firmware (`esp32_firmware/`), OpenWrt/openNDS
coin-slot integration (`opennds/`), provisioning website (`website/`), scripts, CI and docs.

This plan is written for an implementing model or engineer. Each work item has **Why**, **Do**, **Remove**, **Done when**.
Work items are grouped into phases; finish a phase before starting the next unless marked *parallel*.

---

## 0. Decisions this plan is built on

Confirmed by the owner:

| # | Decision |
|---|---|
| D1 | Replace the shared secret `PISOPHONE_HMAC_MASTER_KEY` with per-box keys set at pairing. Licenses are signed with an offline private key. Old boxes get a migration path. |
| D2 | Two network layouts must work. **Layout A** (today): the box and rental phones sit on the upstream modem LAN; the openNDS router is a neighbour (its WAN is on that LAN). **Layout B**: the box and phones sit behind the openNDS router, separated from the customer's home network. |
| D3 | Two businesses: own sites now, selling boxes to other operators later. Anything an operator touches must be safe to give to a stranger. |
| D4 | Binaries are distributed through **GitHub Releases**, not committed to git. |
| Standing | Slot licensing only. Revenue resets after 5 minutes or on logout. Changing plan forfeits remaining time. Customer data wipe is out of scope. `main` is production; `claude/phase-1-execution-xjcooe` is the single feature/debug branch. The implementer never commits a built `firmware.bin`; the owner builds with PlatformIO. |

Assumed defaults (no answer was given; each one is a cheap change if the owner disagrees):

| # | Default | Alternative |
|---|---|---|
| A1 | Keep **Arduino-ESP32 (core 3.x, ESP-IDF 5 based)**. Replace the synchronous `WebServer` and the hand-written WebSocket code with ESP-IDF's `esp_http_server`, which is built into the core and supports WebSockets. | Full port to pure ESP-IDF: more work, little gain for this product. |
| A2 | **Secure Boot V2 + flash encryption (release mode)** only on units that are **sold**. Own-site units stay in development mode so they can be reflashed over USB. | Burn eFuses on every unit. |
| A3 | **No cloud backend in phases 1–4.** Phase 5 adds an optional, minimal fleet heartbeat (Cloudflare Worker + D1). Cloudflare is already used for the website. | Build it now, or never. |
| A4 | **Moderate Android refactor:** split classes along their seams and remove `runBlocking`. Use a hand-written `AppContainer` for dependency injection, not Hilt. | Hilt, or a full clean-architecture rewrite. |
| A5 | **Rewrite git history once** to drop committed APKs. The owner runs it; it is a coordinated force-push. | Keep the history and only stop adding binaries. |

---

## Progress log

| Item | Status | Notes |
|---|---|---|
| S1 | done | `erase_flash` removed; `esp32-c3-factory-erase` env added |
| S9 (flag) | partly | no-op flash-encryption flag removed; provisioning doc still to write |
| S10 | done | `Money.h`, integer centavos, one-time NVS migration from the old float key (`earn_c`) |
| S2 | done (firmware + script) | Signed `PISOLIC1.<MAC>.<slots>.<sig>` token (issued/expiry/serial fields left out: not needed for lifetime slot licenses). `scripts/generate_license.py keygen/issue`. Until `LicensePubKey.h` holds a real key the box still accepts the deprecated shared-secret keys, so existing boxes keep working; **owner must run `keygen`, rebuild and flash to close the hole**. Old-key branch is removed in S4. |
| S8 | done (firmware, phone, website field) | Box generates unique setup-AP and admin passwords on first boot / factory reset (`CredGen.h`), prints them on serial until the admin password is changed; no-Wi-Fi box opens its setup AP at once; deployed boxes keep their password. Built-in super-admin password `superadmin123` removed (no login until the signed credentials are installed). Phone has no factory PIN; PIN comes from the box. A first-run PIN wizard is still to do. |
| P5 | partly | `scripts/check_security_rules.sh` + CI job: private keys, master-secret allow-list, factory passwords, `setInsecure`, `runBlocking` ratchet (max 10). Android lint security checks and branch protection still to do. |
| S3 | partly (per-box secret) | Each box has its own random secret (NVS `shared_secret`); the phone stores it from provisioning (`secret` extra). Wire format unchanged. **Deviations from the plan:** one secret per box instead of ECDH pairing per phone, no AES-GCM/sequence counter yet, phone stores the secret in app-private prefs (Keystore wrapping with P3). See `docs/KEY_MIGRATION.md`. |
| S4 | done (box-wide legacy mode) | Upgraded boxes stay on the old key until the operator clicks "Switch to this box's own key", then re-provisions phones; the switch is permanent. Old-key license acceptance already ends once a license public key is built in (S2). Setup intents on the phone are now PIN-only (the secret no longer authorizes anything). |
| S5 | done (firmware, script, dashboard) | `FwManifest.h`, `OtaSecurity.cpp`, `/api/ota/manifest`, `scripts/sign_firmware.py`; see `docs/FIRMWARE_SIGNING.md`. **Deviation:** the box does not pull from GitHub itself; the browser still downloads and uploads, but the box now verifies a signed manifest + streamed hash, so the browser is no longer trusted. Reuses the license key. Rollback needs a custom bootloader (S9). Releases hosting stays P1. |
| R1 | mostly already present; gaps closed | Box already kept coins in a durable flash queue retried until acknowledged, and the phone already credits via one Room transaction keyed on a unique `tx_id`. Added: tx ids from a flash boot counter + sequence + random salt (`TxId.h`, no clock dependence), a redelivery-storm test on the phone, and host tests. |
| R4 | done (static files) | CSS/JS (91 KB) moved to `esp32_firmware/web/`, served gzip (21 KB) from flash with long-lived versioned caching (`WebAssetServer.cpp`, generated `WebAssets.h` via `scripts/embed_web.py`, CI `--check`). Per-box values go through `window.PISO_CFG`/`PISO_OTA`. The main page was already streamed from flash (no heap copy); the 25 KB super-admin tab, which was copied into a `String`, is now streamed too. **Deviation:** the generated header is committed instead of an PlatformIO `extra_scripts` step, so the build does not depend on Python hooks. HTML page templates were not moved or minified. |
| R3 | partly | `WebSocketsUdp.cpp` split into WebSocket / `Discovery.cpp` / `SerialCli.cpp`. **Not done, on purpose:** replacing `WebServer` and the hand-written WebSocket server with `esp_http_server`, and moving every handler to ArduinoJson. It touches all ~40 routes and the phone connection, cannot be compiled or hardware-tested here, and a mistake would stop coins reaching phones. Do it as its own change on the hardware bench (plan T2) with the openNDS e2e suite as the contract. |
| R5 | done | The unconditional 24 h reboot is gone. `HealthPolicy.h` restarts only after free memory or the largest free block stays low for 5 min, or after 7 days of uptime in the 03:00-05:00 quiet hour (UTC+8; 14 days if no clock), and only with no coin session open and no payment held only in RAM. A heap line is logged every 15 min. Every restart records its cause (`reset.cause` in `/api/diagnostics`). |
| P1 | done (pipeline; owner steps remain) | Repository is private, so the owner chose a separate public releases repo. `build-apk.yml` publishes each build there (stable / dev pre-releases, 20 newest dev kept) and `app.json` carries `url`+`sha256`; without `RELEASES_TOKEN` it falls back to committing the APK. Updater hardened (mandatory checksum, signing-certificate check, HTTPS-only). `KEEP_PAGES_APK=false` stops committing APKs once all phones support `url`. Firmware stays on Pages (browser CORS). Owner: create the repo + token (`docs/RELEASES.md`), later `docs/HISTORY_REWRITE.md` (P2). |

## 1. Findings summary (what is wrong today)

Severity: **C** = critical (money or security), **H** = high (reliability), **M** = maintainability, **L** = polish.

| Sev | Finding | Where |
|---|---|---|
| C | A single hardcoded secret keys every box↔phone message **and** signs slot licenses. Anyone with the APK, the firmware or this repository can forge licenses and coin credits. `scripts/generate_license.py` ships it as `DEFAULT_SECRET`. | `esp32_firmware/src/Config.cpp` (`MASTER_CRYPTO_SECRET`), `Security.cpp::applySlotToken`, `scripts/generate_license.py`, Android `KioskSecurity` |
| C | `board_upload.erase_flash = yes` applies to **every** environment. Each USB upload wipes NVS: revenue counters, licenses, gateway key and Wi-Fi config. | `esp32_firmware/platformio.ini` `[env]` |
| C | The firmware OTA path checks only the image header chip ID. Any image, from anyone who can reach the endpoint with admin credentials, is accepted. | `WebServerModule.cpp` (`Update.begin/write/end`) |
| C | Factory defaults are the same on every box: setup AP `AdminSetup` / `Admin@123`, admin password `admin`. The phone also has a `DEFAULT_PIN`. | `Config.cpp`, Android `KioskSecurity` |
| C | The phone's HTTP server (port 8080) answers `/status`, `/identify`, `/audit`, `/get_time` and `/state` to anyone on the LAN, so in Layout A any customer device on the modem LAN can read the audit log. Timestamp skew on protected routes is only logged, never enforced. | `app/.../server/KioskHttpServer.kt:83-116`, `:150-155` |
| C | `KioskAdminActionReceiver` is exported and has no permission. Any app on the phone can send `ACTIVATE`, `DEPROVISION` or `CONFIGURE_ESP32`. | `AndroidManifest.xml:72-95` |
| C | `CONFIG_SECURE_FLASH_ENC_ENABLED=1` is passed as a `-D` build flag. That does nothing: flash encryption is an eFuse/bootloader setting. The production environment is therefore falsely labelled "encrypted". | `esp32_firmware/envs/esp32_c3_prod.ini` |
| H | Money is stored as `float` (`totalEarningsLifetime` and related). This causes rounding drift and inconsistent reports. | `Config.cpp:89-92`, `:390`, `:421` |
| H | Phone-side AES-CBC in `KioskSecurity` relies on correct ordering with the HMAC. It also uses `androidx.security:security-crypto` 1.1.0-alpha06, which is deprecated and will not get updates. | `security/KioskSecurity.kt`, `libs.versions.toml` |
| H | `PaymentRepository` makes 10 `runBlocking` calls, which risks ANRs and deadlocks on the main thread. | `repository/PaymentRepository.kt` |
| H | The box reboots itself every 24 h (`DAILY_MAINTENANCE_INTERVAL_MS`), which hides heap leaks. If it fires mid-session it interrupts customers. | `esp32_firmware/src/main.cpp:28`, `:97` |
| H | Large `PROGMEM` pages are copied into heap `String`s, e.g. `String(FPSTR(SUPER_ADMIN_HTML))`. The dashboards total about 146 KB and are not compressed, which fragments the heap on the ESP32-C3. | `SuperAdminManager.cpp:177-183`, `WebServerConfig.cpp`, `WebDashboard*.h` |
| H | JSON responses are built by `String +=` concatenation with no escaping, even though ArduinoJson is already a dependency. | `SuperAdminManager.cpp` and most handlers |
| H | The firmware uses a hand-written WebSocket server, UDP discovery and serial CLI in one 629-line file. | `src/WebSocketsUdp.cpp` |
| M | Every publish commits a 1.8 MB APK. `.git` is 64 MB and growing. | `website/update/`, `build-apk.yml` |
| M | Stale or contradictory docs: two architecture documents, an `overhaul/` folder, a probably stale firmware specification, and README marketing ("enterprise-grade"). | repo root, `overhaul/` |
| M | Oversized Kotlin files: `Esp32ConnectionManager` (771 lines), `KioskAudioManager` (711), `FloatingPill` (624), `KioskEngine` (554), `LauncherScreen` (542), `PaymentRepository` (542), `EmergencyRecoveryDialog` (512). | `app/src/main/java/com/pisophone/kiosk/**` |
| M | Redundant or abandoned libraries: `org.json` duplicates `kotlinx`/Android JSON; `nanohttpd` 2.3.1 has been abandoned since 2016; `material-icons-extended` is huge and only a few icons are used; OkHttp 4.10, Kotlin 2.0.21 and Compose BOM 2024.09 are all outdated. | `libs.versions.toml`, `app/build.gradle.kts:86` |
| M | Broad permissions: `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, `WRITE_SETTINGS`, `SCHEDULE_EXACT_ALARM`. A device owner can do most of this through `DevicePolicyManager`. | `AndroidManifest.xml` |
| M | `yume-chan-bundle.js` (4852 lines) is vendored with no recorded version or source. | `website/js/` |
| L | Logs mix emoji, `Serial.printf` and `diagLog` with no levels. | firmware-wide |

What is already good and must be **kept**:
- The ECDSA P-256 signature scheme for super-admin credentials (`include/CredCrypto.h`), including its host tests. It becomes the template for licenses and OTA.
- The openNDS gateway API: per-router key, nonce + HMAC.
- The openNDS end-to-end test harness (`opennds/tests/`).
- Room, Compose, minify and enforced release signing in CI.
- The firmware host-test setup.

---

## 2. Target architecture

```
                 (offline laptop)
            ┌─────────────────────────┐
            │ Owner signing key (P-256)│  signs: licenses, firmware manifests,
            │ never in git / CI        │         super-admin creds (existing)
            └────────────┬────────────┘
                         │ signed files
          GitHub Releases + website/update/*.json (metadata only)
                         │
     ┌───────────────────┼─────────────────────────┐
     ▼                   ▼                         ▼
 ESP32 box  ◄── per-box session key K_box ──►  Android kiosk phone(s)
  - coin acceptor     (from ECDH pairing)       - lock task, device owner
  - ledger (int cents)                          - Keystore-wrapped K_box
  - esp_http_server + WS                        - single WS client to box
  - signed OTA, signed license                  - HTTP server removed or narrowed
     ▲
     │ gateway API (per-router key, existing)
 OpenWrt + openNDS (coinslot listener) — Layout A or B
```

Principles:
1. **One secret per relationship.** Box↔phone uses `K_box`. Box↔router uses `GW_KEY`, which already exists. Owner→devices trust is
   **public-key only**: devices hold the owner's public key, never a secret that can sign.
2. **One transport between box and phone.** A single WebSocket that the phone opens to the box, authenticated with `K_box`. The phone
   stops running an HTTP server for the box (see S6).
3. **Money is integers.** Centavos are stored as `uint32_t`/`Long` everywhere. Every credit carries a box-generated `tx_id` and is applied
   exactly once.
4. **Config has a schema version.** NVS and Room both carry a version number with explicit migrations.
5. **Binaries live in Releases; git holds source and metadata.**

---

## Phase 1: critical security and data safety (do first)

### S1. Stop wiping NVS on every upload (½ day)
- **Do:** In `esp32_firmware/platformio.ini`, delete `board_upload.erase_flash = yes` from `[env]`. Add one environment,
  `[env:factory-erase]`, that extends dev and sets it, for deliberate wipes. Document it in the firmware README.
- **Done when:** Uploading `esp32-c3-dev` twice keeps the revenue counters and the gateway key.

### S2. Asymmetric license signing (2–3 days)
- **Why:** Forging licenses must require the owner's private key.
- **Do:**
  - Add `include/LicenseCrypto.h`, pure mbedtls in the style of `CredCrypto.h`. It reuses `verifySignature`; move the shared
    helpers into `include/SigCrypto.h` and have both headers include it.
  - License payload, canonical text: `pisophone-license-v1|<chip_id_hex>|<slots>|<issued_unix>|<expires_unix or 0>|<serial>`,
    with an ECDSA P-256 signature in base64. `chip_id` is the eFuse MAC (`ESP.getEfuseMac()`).
  - Compile the owner public key (DER) into firmware next to the existing super-admin public key. The same key may be used:
    the canonical tags keep the purposes separate.
  - Rewrite `scripts/generate_license.py`:
    - It reads the private key from a PEM path given by `--key` or `PISOPHONE_SIGNING_KEY`, and refuses to run without one.
    - It outputs a single token string.
    - Add `scripts/keygen.py`, which creates the key pair once and prints the DER public key as a C array.
  - Host test `host_tests/license_crypto_test.cpp`: a valid token is accepted; wrong chip ID, tampered slots and wrong key are
    all rejected.
- **Remove:** `DEFAULT_SECRET` and `generate_slot_token` from `generate_license.py`. Remove the HMAC branch of `applySlotToken` after the
  migration window (S4).
- **Done when:** A token for box X is rejected by box Y. No private material exists anywhere in the repository; add a CI grep for
  `BEGIN EC PRIVATE KEY` and `PISOPHONE_HMAC_MASTER_KEY`.

### S3. Per-box pairing key `K_box` (4–6 days, firmware and Android)
- **Do:**
  - **Firmware:** On first boot, generate a 32-byte box identity secret using `esp_fill_random` and store it in NVS namespace `sec`.
    Expose `POST /api/pair/start`, which is admin-authenticated. It returns a 6-digit pairing code that is valid for 120 s and shown on
    the dashboard.
  - **Pairing (both sides):**
    1. The phone sends its ephemeral P-256 public key and an HMAC of it keyed with the pairing code.
    2. The box answers with its own ephemeral public key, also with the HMAC.
    3. Each side derives `K_box = HKDF-SHA256(ECDH, salt=box_chip_id, info="pisophone-pair-v1|<phone_device_id>")`.
    4. The box stores `K_box` per phone slot, up to the number of licensed slots.
  - **Android:** Store `K_box` encrypted under an Android Keystore AES-GCM key (non-exportable). Use it for all box messages.
  - **Message format:** Use **AES-256-GCM**. It is in mbedtls and in `javax.crypto`. Each message carries a 12-byte random nonce and
    AAD = `slot|seq`. A monotonically increasing `seq` per direction replaces the current timestamp and `tx_id` heuristics for replay.
    Keep `tx_id` for idempotent payments (R1).
  - **Website provisioning:** The WebADB flow (`website/js/webadb_manager.js`) keeps doing Wi-Fi/IP setup. Pairing moves into the app
    UI (admin PIN → "Pair with box" → enter code), so no secret ever passes through `am broadcast`.
- **Remove:**
  - `MASTER_CRYPTO_SECRET` from `Config.cpp` and every use of it.
  - The SHA-256-of-shared-secret key derivation on both sides.
  - The AES-CBC + separate HMAC code in `Security.cpp` and `KioskSecurity.kt`, once S4 has finished.
- **Done when:**
  - Two boxes have different keys.
  - Capturing traffic from box A and replaying it to phone A is rejected (seq).
  - Phone B, paired to box B, rejects box A's messages.

### S4. Migration for boxes already deployed (1–2 days)
- **Do:**
  - Firmware has a `legacy_mode` flag in NVS, true when no `K_box` exists. In legacy mode it accepts the old shared-secret protocol
    **only** until the first successful pairing; after that the old path is permanently disabled (`legacy_mode=false`, never
    re-enabled except by `factory-erase`).
  - Existing HMAC licenses are converted once on boot:
    1. Verify with the old method.
    2. Record `legacy_slots=N`.
    3. Show "re-license required by <date>" in the dashboard.
    4. The owner issues signed tokens for their own boxes with `generate_license.py`.
  - The Android app keeps the legacy client for one release only, behind `BuildConfig.LEGACY_PROTOCOL`.
- **Done when:** An own-site box updated from current `main` keeps working, pairs, and then refuses legacy messages. Add a test for
  this in the host tests and a robolectric test.

### S5. Signed firmware OTA (2–3 days)
- **Do:**
  - Release CI does **not** build firmware (the owner builds it). Add `scripts/sign_firmware.py <firmware.bin>`, which computes SHA-256
    and signs `pisophone-fw-v1|<version>|<sha256_hex>|<chip>` with the owner key. It writes `firmware.json`:
    `{version, url, sha256, size, chip, sig}`.
  - The owner uploads `firmware.bin` and `firmware.json` to the GitHub Release (P1). `update-firmware-json.yml` only copies the json
    metadata to `website/update/firmware.json`.
  - The OTA handler (upload and pull) streams to `Update`, hashing with `mbedtls_sha256` as it writes. It calls `Update.end(true)` only
    if the hash matches and the signature verifies; otherwise it calls `Update.abort()`. It also refuses `version <= current` unless
    the request carries `allow_downgrade` and super-admin authentication.
  - Pull OTA uses `HTTPClient` with `setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS)`, because GitHub asset URLs redirect, and with
    the core's CA bundle (`esp_crt_bundle_attach`). Never use `setInsecure()`.
  - Turn on rollback: call `esp_ota_mark_app_valid_cancel_rollback()` once Wi-Fi, the coin ISR and the HTTP server are up and the box
    has run for 60 s. Before that, a crash reverts to the old image. This requires `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE`; on
    Arduino it is enabled in the prebuilt bootloader. Verify it on hardware and document the result.
- **Done when:** A tampered byte in `firmware.bin` is rejected, an unsigned image is rejected, and a boot-looping image rolls back.

### S6. Lock down the phone's network surface (2–3 days)
- **Do:**
  - The box↔phone path becomes the S3-authenticated WebSocket. **Remove `KioskHttpServer` and NanoHTTPD** once the box no longer calls
    the phone over HTTP. The phone becomes a client only: it opens the WebSocket to the box and reconnects with backoff.
  - If a phone-side listener is still needed during the transition:
    - Only `/ping` stays public, and it returns `OK` with no data.
    - `/status`, `/identify`, `/audit`, `/get_time`, `/state` and `/crash` require the authenticated envelope.
    - Enforce the timestamp skew and `seq` checks (today they are only logged).
    - Bind to the Wi-Fi interface address, not `0.0.0.0`.
  - Remove `/emergency_adb` from the network surface completely; recovery belongs to the physical PIN flow on the device.
- **Remove:** The `org.nanohttpd:nanohttpd` dependency and `server/KioskHttpServer.kt`, after the WebSocket path ships.
- **Done when:** A port scan of the phone shows nothing open, or only `/ping`. An `nmap` + `curl` check is part of the HIL checklist (T4).

### S7. Lock down the admin broadcast receiver (½–1 day)
- **Do:**
  - Keep `KioskAdminActionReceiver` exported, because `adb shell am broadcast` needs that.
  - Android does not reliably report who sent a broadcast: `getSentFromUid()` only works when the sender opts in, and shell does not.
    So in `onReceive`, require an extra `token` that matches a one-time provisioning token, and drop the intent otherwise. The app
    generates the token, shows it on the admin screen, and the website asks the operator to type it.
  - After `ACTIVATE` succeeds, disable `CONFIGURE_ESP32` and `ACTIVATE` by state check (`isProvisioned`) until a PIN-confirmed
    re-provision.
  - Keep `DEPROVISION` behind the admin PIN plus the token.
- **Done when:** A test app on the same phone cannot trigger any action. Add a robolectric test.

### S8. Unique default credentials (1 day)
- **Do:**
  - On first boot (no NVS), derive the setup AP SSID `PisoPhone-XXXX` from the last 2 MAC bytes. Set a random 10-character AP password
    and a random admin password; print both on the serial console and the dashboard first-run page.
  - Force an admin password change at first login. The phone has no default PIN: the first-run wizard requires one.
  - For units sold to operators (D3), the factory procedure prints a label with the AP password.
- **Remove:** `DEFAULT_SSID`, `DEFAULT_PASS` and `DEFAULT_ADMIN_PW` constants (`Config.cpp`), and `DEFAULT_PIN` (`KioskSecurity`).
- **Done when:** Two fresh boxes have different credentials and no source file contains a usable password.

### S9. Truthful hardware security for sold units (1 day of docs and scripts, plus the per-unit procedure)
- **Do:**
  - Remove the `-D CONFIG_SECURE_FLASH_ENC_ENABLED=1` flag from `envs/esp32_c3_prod.ini`.
  - Write `docs/PROVISIONING_SOLD_UNIT.md`, a step-by-step guide using `espefuse.py`/`espsecure.py`:
    - Generate the Secure Boot V2 signing key; it is a separate key from the owner license key.
    - Enable flash encryption in release mode and Secure Boot V2.
    - Set `DIS_USB_JTAG` and `DIS_DOWNLOAD_MODE` per policy.
    - The steps are **irreversible**, so practise on a sacrificial unit first.
  - Arduino caveat: this needs a custom bootloader built with the matching sdkconfig. Use the `pioarduino` platform with
    `custom_sdkconfig`, or build the bootloader from ESP-IDF once and keep it under `esp32_firmware/bootloader/`. Pick the approach
    proven on a test unit and record it in the doc.
  - Merge `esp32_flash_encryption_guide.md` and `esp32_security_lockdown.md` into that doc, then delete them.
- **Done when:** A sold-unit procedure has been executed on one test board end to end, and reading flash with `esptool read_flash`
  returns ciphertext.

### S10. Money as integers (1 day, plus a migration)
- **Do:** Replace `float totalEarnings*` with `uint32_t totalCentavos*`. Migrate once: read the old float key, round to centavos, write
  the new key `earn_c`, delete the old key. Do the same on Android if any `Double` amounts are persisted (check `Room` entities and
  `/config price` parsing, `KioskHttpServer.kt:271`).
- **Done when:** 10,000 simulated ₱1 coins report exactly ₱10,000.00 (host test).

---

## Phase 2: reliability of the money path

### R1. One idempotent payment ledger (3 days)
- **Do:**
  - **Box side:** A coin event becomes a ledger record `{tx_id (box counter + boot id), slot, centavos, minutes, ts}` appended to an
    NVS ring of N=64. It is marked `acked` only after the phone acknowledges it over the WebSocket. Unacked records are resent on
    reconnect. Revenue counters update when the record is written, not when it is acked.
  - **Phone side:** Use Room table `credits` with `tx_id` as the primary key; insertion is `ON CONFLICT IGNORE`. The time grant happens
    in the same transaction as the insert. Persist the replay set in Room; do not keep it in memory.
  - Cover the existing payment queue (box) and `PaymentRepository` with tests: duplicate delivery, reconnect mid-credit, reboot
    mid-credit.
- **Done when:** In the T3 soak test, pulling Wi-Fi or power at random points never double-credits and never loses a coin.

### R2. Remove `runBlocking` and restructure the Android core (4–6 days)
- **Do:** Change `PaymentRepository`'s 10 `runBlocking` sites into `suspend` functions or `Flow`. Callers on the main thread move to
  `lifecycleScope`, `viewModelScope` or the service scope.
- Split by responsibility:
  - `Esp32ConnectionManager` (771 lines) → `BoxTransport` (WebSocket and reconnect), `BoxProtocol` (envelope and crypto), `BoxDiscovery`
    (merge with `Esp32DiscoveryScanner`), `BoxSession` (state machine exposed as `StateFlow`).
  - `KioskAudioManager` (711) → `SoundPlayer` + `AnnouncementPolicy`.
  - `FloatingPill` (624) and `LauncherScreen` (542) → state holder + stateless composables.
  - `KioskEngine` (554) → `SessionTimer` (pure Kotlin, unit-testable with a fake clock) + `LockTaskController`.
  - `EmergencyRecoveryDialog` (512) → dialog UI + `RecoveryActions`.
- Add `AppContainer` (manual DI), created in `Application.onCreate` and passed to services, receivers and view models.
- **Done when:** No `runBlocking` remains outside tests (CI grep), no file exceeds about 400 lines, and the existing 101 tests pass.

### R3. Firmware HTTP and WebSocket stack (5–7 days)
- **Do:**
  - Replace the Arduino `WebServer` (synchronous, one client at a time) and `WebSocketsUdp.cpp` with **`esp_http_server`**:
    - Use `httpd_ws_*` for the phone WebSocket.
    - Register URI handlers per module (`routes_admin.cpp`, `routes_superadmin.cpp`, `routes_gateway.cpp`, `routes_ota.cpp`).
    - Limit to 4 sockets, with LRU purge on.
  - Move UDP discovery into `Discovery.cpp` and the serial CLI into `SerialCli.cpp`.
  - Build every JSON response with ArduinoJson (`JsonDocument` with a fixed capacity, `serializeJson` into the response) and remove the
    `String +=` builders.
  - Keep the route paths and response shapes unchanged so the dashboard and openNDS listener keep working. Check them against the
    openNDS `fakebox.py` contract.
- **Remove:** `WebSocketsUdp.cpp`, the `<WebServer.h>` includes, and the per-handler `webServer.arg` access, which is replaced by a small
  `Request` helper.
- **Done when:**
  - The openNDS e2e suite passes against a real box (T4).
  - The dashboard and the phone stay connected while 3 browser tabs poll.
  - `ESP.getMinFreeHeap()` stays stable over 72 h.

### R4. Serve the dashboards compressed and from flash (2 days)
- **Do:**
  - Move dashboard sources out of the `WebDashboard*.h` and `SuperAdminTemplate.h` string literals into `esp32_firmware/web/*.html|js|css`.
  - Add a PlatformIO `extra_scripts` pre-build step (`scripts/embed_web.py`) that minifies the sources, gzips them and emits
    `include/generated/web_assets.h` with `const uint8_t[]` arrays, lengths and ETags.
  - Serve with `Content-Encoding: gzip`, `Cache-Control` and `ETag`, sending directly from flash in chunks. **Never copy into `String`.**
- **Remove:** `renderSuperAdminTabHtml`, `renderSuperAdminScripts` and every `String(FPSTR(...))` pattern (`SuperAdminManager.cpp:177-183`,
  `WebServerConfig.cpp`).
- **Done when:** Total dashboard payload is under 40 KB on the wire and free heap during a dashboard load drops by less than 8 KB.

### R5. Replace the daily reboot with health monitoring (1–2 days)
- **Do:**
  - Enable the task watchdog for the main loop and the HTTP task.
  - Every 60 s, record `heap_free`, `heap_min` and `largest_free_block` in the diagnostics ring.
  - Reboot **only** if `largest_free_block` stays below a threshold (for example 16 KB) for 5 minutes, **and** no slot has an active
    session, **and** no ledger record is unacked. As a fallback, a scheduled reboot happens at most weekly, in a configured quiet hour,
    and only when idle.
  - Persist the reboot reason (`esp_reset_reason()` plus the custom reason) and show it on the dashboard.
- **Remove:** The unconditional 24 h `DAILY_MAINTENANCE_INTERVAL_MS` logic in `main.cpp`.
- **Done when:** In the 72 h soak test the box makes no unplanned reboots and heap is flat.

### R6. Config schema and globals (2–3 days)
- **Do:**
  - Move the scattered globals (`totalCoins*`, `vendorRevenueSplitPercent`, Wi-Fi, rates, and so on) into typed structs owned by modules
    (`Ledger`, `SuperAdmin`, `NetConfig`, `Pricing`).
  - Store `cfg_ver` in NVS, with `migrateConfig(from, to)` steps run at boot.
  - Make every NVS write go through a single `Store` module that holds `Preferences` open per namespace. That removes the repeated
    `prefs.begin`/`prefs.end` pairs.
  - Rate-limit counter writes: write on every ledger record, but not on every loop. This protects flash wear.
- **Done when:** A host test runs config migrations from v1 to the current version.

### R7. Logging (1 day, firmware and Android)
- **Firmware:** Add `LOGE/W/I/D(tag, fmt, ...)` macros that write to `Serial` and the diagnostics ring with levels, and set the level at
  build time (prod = `I`). Remove emoji. Never log secrets; the existing super-admin load log is fine.
- **Android:** Use a small `Logger` wrapper. In release builds, rely on R8 to strip `Log.d` and `Log.v`. Narrow `catch (e: Exception)`
  blocks to specific exceptions, or rethrow `CancellationException`. Grep for `catch (e: Exception)` and review each hit.

---

## Phase 3: build, release and repository hygiene (*parallel with Phase 2*)

### P1. GitHub Releases pipeline (2 days)
- **Do:**
  - **`build-apk.yml`:**
    - On a push to `main`, build the release APK and create the GitHub Release `app-v<semver>` with the APK and a `.sha256` asset.
    - On a push to `claude/phase-1-execution-xjcooe`, create a **pre-release** `app-debug-<run>`.
    - Then update **only** `website/update/app.json` on that branch: `{versionCode, versionName, url → release asset, sha256}`. That keeps
      the standing rule (every branch push publishes to its branch's `website/update/`) without committing the binary.
  - **Firmware:** The owner attaches `firmware.bin` and the signed `firmware.json` (S5) to a `fw-v<semver>` release.
    `update-firmware-json.yml` copies `firmware.json` into `website/update/`.
  - **Android updater:**
    - Download the APK and verify its SHA-256 against `app.json`.
    - Before installing, check that the signing certificate digest equals the running app's.
    - Install silently with `PackageInstaller` as device owner.
  - **`versionCode`:** Keep it monotonic. Use `GITHUB_RUN_NUMBER` plus a fixed offset per workflow, so pre-releases and releases can
    never collide; document this in `build-apk.yml`.
- **Remove:** The steps that `git add website/update/app-release.apk`, and the APK file itself (`git rm`).
- **Done when:** A fresh phone on the debug channel updates from a pre-release, and a tampered APK is refused.

### P2. Rewrite history once (owner-run, ½ day, coordinated)
- **Do:** Write `docs/HISTORY_REWRITE.md` with exact commands. The owner runs them:
  1. Run `git filter-repo --path website/update/app-release.apk --invert-paths`, also dropping any `*.apk` and `*.bin` paths.
  2. Force-push `main` and the feature branch.
  3. Have every clone re-clone.
  4. Note that Cloudflare Pages redeploys and open PRs must be recreated.
- **Done when:** `.git` is under 10 MB.

### P3. Dependency modernisation (Android, 2 days)
- Kotlin 2.0.21 → latest 2.x stable, with the Compose compiler plugin aligned. Compose BOM → latest stable. OkHttp 4.10 → 4.12+, or
  the 5.x stable line if available. Room → latest 2.x and its KSP plugin. AGP stays on 9.x.
- Replace `security-crypto` with an Android Keystore AES-GCM key plus Jetpack DataStore. Write a one-time migration that reads the old
  `EncryptedSharedPreferences` and rewrites the values, then deletes the old file.
- Replace `org.json` (both the Android one and the `org.json:json:20231013` dependency) with `kotlinx.serialization`. The protocol
  messages become `@Serializable` data classes shared with the tests.
- Remove `material-icons-extended`. Copy the few icons actually used (`grep -r "Icons\." app/src`) as `ImageVector` files or vector
  drawables into `ui/icons/`.
- Add Gradle dependency locking or a Renovate config so updates arrive as reviewable PRs.

### P4. Permission review (½ day)
| Permission | Action |
|---|---|
| `QUERY_ALL_PACKAGES` | Replace with a `<queries>` block listing the packages the launcher shows. |
| `KILL_BACKGROUND_PROCESSES` | Remove. A device owner with lock-task mode does not need it; check the call sites. |
| `WRITE_SETTINGS` | Replace with `DevicePolicyManager.setSystemSetting` / `setGlobalSetting` (device owner). |
| `SCHEDULE_EXACT_ALARM` | Keep only if the session-end alarm needs it. Otherwise use `setAndAllowWhileIdle` inside the foreground service's timer. |
| `SYSTEM_ALERT_WINDOW` | Keep; the floating pill needs it. |
| `REQUEST_INSTALL_PACKAGES` | Keep; the self-updater needs it. |

### P5. CI gates (1 day)
- Add security greps: no `PISOPHONE_HMAC_MASTER_KEY`, no PEM private keys, no `setInsecure`, no `runBlocking` in `src/main`.
- Add the new host tests from S2, S5, S10, R1 and R6 to `quality.yml`.
- Add Android lint with `abortOnError` for security checks.
- Require PR checks on `main` (branch protection, set by the owner).

---

## Phase 4: router, network topologies and operator experience

### N1. Layout A: box on the modem LAN, router as a neighbour (1–2 days)
- The openNDS router reaches the box over its WAN interface. That works today, provided the box address is stable.
- **Do:**
  - Add box discovery to `coinslot-listener.sh`: when `GW_BOX` is unset or unreachable, listen for the box's existing UDP discovery
    broadcast with `socat` and update the cached IP in `/tmp`.
  - Recommend a DHCP reservation on the modem anyway. Many ISP modems cannot make reservations, so discovery must also work on its own.
  - Document that the firewall needs no change: the WAN→box traffic is router-initiated output.
- **Security note for Layout A:** Customers using the openNDS Wi-Fi are behind the router's NAT, so they cannot reach the box or the
  phones directly. Other devices on the modem LAN can, which is why S3, S6 and S8 matter.

### N2. Layout B: box and phones behind the openNDS router (2 days, docs and a config snippet)
- **Do:** Document and test this layout:
  - Create a separate **business network** (`kiosk` interface/VLAN plus its own SSID, or a wired port) that openNDS does **not** gate.
    openNDS binds only to the customer SSID interface (`gatewayinterface`).
  - Firewall zone `kiosk` → `wan` allowed; `kiosk` ↔ customer zone denied; customer → box allowed **only** for what the portal needs
    (nothing, because the listener on the router talks to the box). The router reaches the box over the `kiosk` zone.
  - If a single SSID is unavoidable, the fallback is `trustedmac` entries for the box and every rental phone. Document that this is
    weaker.
  - Add `opennds/INSTRUCTIONS.md` sections "Layout A" and "Layout B" with exact `uci` commands for B.
- Extend `opennds/tests` with a config check (`uci show` fixtures) for both layouts.

### N3. Router package quality (2–3 days, optional for own sites, required before selling)
- Move `/etc/coinslot.conf` to UCI (`/etc/config/coinslot`) with `uci_load` in the scripts, so LuCI and `uci` tooling work. Keep a
  fallback that reads the old file for one release.
- Build an `.ipk` (`opennds-coinslot`) in CI with the OpenWrt SDK. Include postinst `chmod`, enable the service, and normalise CRLF line
  endings. This replaces the manual copy steps that previously failed on line endings. `INSTRUCTIONS.md` becomes
  `opkg install ./opennds-coinslot_*.ipk` followed by the key step.
- Check the write frequency of `/etc/coinslot.d/` (vouchers, revenue). Router flash wears out: keep hot state in `/tmp` and sync to
  `/etc` on change at most once a minute, plus at shutdown.

### N4. Operator-facing surfaces (for D3, 2–3 days)
- Add the first-run wizard on the box dashboard (S8), the pairing flow (S3) and the license status page (S2).
- Split roles cleanly: the operator uses the admin dashboard; the owner uses super-admin, which is remote-managed and already exists.
  Check that no operator action can reset licenses or change revenue split.
- Website: record the version and source of `yume-chan-bundle.js` in `website/js/VENDORED.md`, or replace it with an npm build step
  that is pinned by lockfile. Keep the WebADB flow for Wi-Fi/IP only.

---

## Phase 5: verification, pilot and fleet visibility

### T1. Tests to add (spread across phases)
- **Firmware host tests:**
  - License verify (S2), OTA manifest verify (S5).
  - Integer money (S10).
  - Ledger idempotency and ring wrap (R1).
  - Config migration (R6).
  - Pairing KDF vectors shared with Android (S3).
- **Android:**
  - `SessionTimer` with a fake clock.
  - Ledger insert-ignore.
  - Pairing KDF vectors (the same JSON fixture as the firmware).
  - Receiver lockdown (S7).
  - Updater signature check (P1).
- **Contract tests:** `protocol/fixtures/*.json`, golden encrypted messages generated once. Both the C++ host tests and the Kotlin
  tests decode them, so the two sides cannot drift.

### T2. Hardware-in-the-loop bench (2 days to build)
- **Hardware:** one box, one phone and a second ESP32 acting as a coin-pulse generator (GPIO to the coin input) that is controllable
  over serial.
- **Script:** `scripts/hil/run.py` drives N coin insertions with random timing and random Wi-Fi drops (toggled through the router API),
  then checks the box ledger, the phone ledger and the openNDS listener report against each other.

### T3. Soak test
- Run 72 h on the bench with traffic every 2 minutes.
- **Pass:** zero ledger mismatches, flat heap, no unplanned reboots, and no stuck WebSocket for more than 30 s.

### T4. Release checklist (`docs/RELEASE_CHECKLIST.md`)
- Host and Android tests are green and the HIL smoke test (30 minutes) passes.
- Port scan the phone (S6).
- OTA tamper test (S5).
- Upgrade from the previous release preserves data (S1, S4, R6).
- Rollback works.

### T5. Pilot
- Run 2 weeks at one own site with the new firmware and app before shipping any sold unit.
- Compare the coin box physical count against the ledger daily.

### F1. Optional fleet heartbeat (A3, 3–4 days)
- Every 15 minutes the box sends an HTTPS POST: `{chip_id, fw, app_versions, uptime, reset_reason, heap_min, ledger_totals, last_error}`,
  signed with a per-box key that is derived at license time.
- A Cloudflare Worker stores it in D1, and a small page on the existing website shows an alert when a box has been silent for more
  than 1 hour.
- It is opt-in per box and off by default for sold units unless the operator agrees.

---

## 6. Removal list (consolidated)

| Remove | When |
|---|---|
| `board_upload.erase_flash = yes` in `[env]` | S1 |
| `-D CONFIG_SECURE_FLASH_ENC_ENABLED=1` in `envs/esp32_c3_prod.ini` | S9 |
| `MASTER_CRYPTO_SECRET`, `DEFAULT_SSID`, `DEFAULT_PASS`, `DEFAULT_ADMIN_PW` (`Config.cpp`) | S3, S8 |
| `DEFAULT_SECRET`, `generate_slot_token` (`scripts/generate_license.py`) | S2 |
| HMAC license branch in `Security.cpp::applySlotToken` | after S4 window |
| AES-CBC + HMAC envelope (`Security.cpp`, `KioskSecurity.kt`) | after S4 window |
| `DEFAULT_PIN` (`KioskSecurity.kt`) | S8 |
| `server/KioskHttpServer.kt` and the `nanohttpd` dependency | S6 |
| `/emergency_adb` network route | S6 |
| `src/WebSocketsUdp.cpp`, `<WebServer.h>` usage | R3 |
| `renderSuperAdminTabHtml`/`renderSuperAdminScripts` and all `String(FPSTR(...))` | R4 |
| `WebDashboard*.h`, `SuperAdminTemplate.h` string literals (moved to `web/`) | R4 |
| Unconditional 24 h reboot in `main.cpp` | R5 |
| `float` earnings variables and `NVS_KEY_TOTAL_EARNINGS` float key (after migration) | S10 |
| `runBlocking` in `PaymentRepository` | R2 |
| `org.json:json`, `androidx.security:security-crypto`, `material-icons-extended` | P3 |
| `QUERY_ALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES`, `WRITE_SETTINGS` | P4 |
| `website/update/app-release.apk` and the CI step that commits it | P1, P2 |
| `ARCHITECTURE_OVERHAUL.md`, `overhaul/`, `esp32_flash_encryption_guide.md`, `esp32_security_lockdown.md`, `github_actions_setup.md` (merged), `ESP32_FIRMWARE_SPECIFICATION.md` (if stale; verify first) | D-docs |

## 7. Documentation set (target)

```
README.md                  what it is, the 3 components, links (no marketing claims)
docs/ARCHITECTURE.md       the diagram in §2, protocols, trust model
docs/SECURITY.md           keys, who holds what, rotation, incident steps
docs/PROTOCOL.md           box↔phone WS messages, ledger semantics, versioning
docs/PROVISIONING.md       own-site setup: box, phone (WebADB + pairing), router Layout A/B
docs/PROVISIONING_SOLD_UNIT.md  eFuse procedure (S9), labels, handover
docs/RELEASE.md            branches, Releases, signing firmware, checklist (T4)
docs/OPERATIONS.md         day-to-day, reports, troubleshooting tables (from opennds/INSTRUCTIONS.md)
opennds/INSTRUCTIONS.md    router install (kept, gains Layout A/B)
```

## 8. Suggested order and effort

| Order | Items | Est. (dev-days) |
|---|---|---|
| 1 | S1, S9 (flag removal + docs), S10, P5 greps | 3 |
| 2 | S2, S5 (shared signing infra), S8 | 6–7 |
| 3 | S3, S4, S6, S7 (protocol switch, one coordinated firmware+app release) | 10–12 |
| 4 | R1, R3, R4, R5 (firmware core) — *parallel:* R2, P1, P3, P4 (Android/CI) | 15–20 |
| 5 | R6, R7, P2, docs consolidation | 5 |
| 6 | N1, N2, T1–T3 | 7–9 |
| 7 | Pilot (T5); then N3, N4, S9 on a real sold unit | 2 weeks calendar + 6 |
| 8 | F1 (optional) | 3–4 |

Total engineering is about 55–70 dev-days, plus the pilot.

## 9. Rules for the implementer

1. Work on `claude/phase-1-execution-xjcooe` and open one PR per row of §8, or smaller. `main` stays releasable.
2. Never commit a built `firmware.bin` or any private key. The owner builds and signs firmware.
3. The protocol switch (step 3) ships firmware and app together. The app must keep the legacy client for exactly one release (S4).
4. Each PR must keep `quality.yml` green, add the tests named in its items, and update the matching doc in §7.
5. Do not change openNDS rates, plans or revenue rules. They are owner decisions already implemented.
6. Ask the owner before anything irreversible: eFuse burning, history rewrite, or deleting a doc whose staleness is not certain.
