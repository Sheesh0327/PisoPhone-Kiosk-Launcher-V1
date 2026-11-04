# Feature 064: PisoHome (`PisoKids`) — Parental Screen-Time, Chore-Reward, Tailscale Remote & Family UI/UX Overhaul

Status: **approved**   Branch: `main`   Area: firmware, app, website
Release: app, firmware

## 1. Intent

Provide a dedicated family product variant, **PisoHome (`PisoKids`)**, stripped of all commercial shop features (PisoWiFi router gateway, prepaid QR time cards, shop branding, 1v1 arena, guest app data wiping, commercial revenue vault and collector splits). PisoHome transforms the ESP32 coin box into a home **Alkansya (Piggy Bank)** and chore reward center, and equips the child's phone with a gamified **Dual-Hero 'Time + Chores' Lock Screen Hub** (`PisoKidsTheme`). Parents can manage screen time, set Bedtime/Study curfews, approve chores, and speak to their kids from home or remotely anywhere via **Tailscale Always-On VPN**. Furthermore, phone-to-phone pairing is streamlined via QR code or **Android WebADB over USB-C OTG** with a direct clickable and auto-copy link for `chrome://flags/#unsafely-treat-insecure-origin-as-secure`.

## 2. Invariants Touched

- **Money and time are never wrong:**
  - Physical coins inserted into the ESP32 coin box follow existing `PulseGuard` and `PaymentQueueManager` guarantees (deduplicated, stored in NVS, signed box-to-phone crediting with unique `txId`).
  - Chore bonus time uses generated `tx-chore-<choreId>-<timestamp>` transaction IDs with `amount = 0.0` so physical coin revenue and bonus time are strictly separated.
  - Session pausing banks remaining seconds into `heldSeconds` (`sessionExpiryDeadlineMs == 0L && sessionTimeRemaining > 0`) inside a Room transaction without altering the schema or losing milliseconds.
  - Clock monotonicity is strictly enforced; time cannot be artificially inflated.
- **Only owner-signed software is installed:**
  - Phone app and ESP32 firmware updates require existing ECDSA / OTA signatures.
  - Remote Tailscale `/parent` access is protected by the Parent PIN and restricted to the Tailscale CGNAT subnet (`100.64.0.0/10`) or local loopback.

## 3. Constraints

- **Single Product Strip Enforcement:** `scripts/check_product_strip.py` must pass for all targets (`phone`, `wifi`, and `home`).
- **Never edit generated files by hand:** `esp32_firmware/include/WebAssets*.h` is generated strictly by `python3 scripts/embed_web.py`.
- **Zero Room Migration:** `heldSeconds` is already a column in `PaidSessionState`; no database version bump or schema changes are permitted.
- **Network Interface Isolation:** Local ESP32 coin-box traffic binds strictly to `wlan*`, while Tailscale traffic operates on `tun*`/`tailscale*` (`100.64.0.0/10`), preventing VPN routing interference with local coin insertion.
- **Android Device Owner Security:** Device Owner status blocks uninstallation and settings bypass; Tailscale is locked via `setAlwaysOnVpnPackage` without lockdown mode (`lockdownEnabled = false`) to enable split-tunneling.

## 4. Success Criteria

1. **Firmware Pure Rules:** `esp32_firmware/include/ChoreBoard.h` and `CurfewRules.h` compile and pass host unit tests (`chore_board_test.cpp`, `curfew_rules_test.cpp`).
2. **Product Strip & Firmware Isolation:** `PISO_PRODUCT_HOME` builds cleanly under `[home_only]` in `products.ini`; all commercial files (`GatewayCoinslot.cpp`, `WebServerGateway.cpp`, `AccountStorage.cpp`, `WebServerAccounts.cpp`, `ShopBrandingStore.cpp`, `WebServerBranding.cpp`, `SuperAdminManager.cpp`, `SuperAdminSession.cpp`, `RevenueVault.cpp`) are excluded; `scripts/check_product_strip.py` passes 100%.
3. **Firmware 3-Tab Family Dashboard:** The web portal renders the 3-tab layout (`Home`, `Chores & Schedule`, `Settings`) in warm emerald/gold when `PISO_IS_HOME = 1`; curfew rejects coin arming with HTTP `423 CURFEW_ACTIVE`; family phone slots are unlocked without license keys.
4. **Phone-to-Phone Setup & Clickable Flags Link:** `website/index.html`, `website/js/webadb_manager.js`, `website/js/provisioning.js`, and `esp32_firmware/web/portal-core-phones.js` render clickable `<a href="chrome://flags/#unsafely-treat-insecure-origin-as-secure" target="_blank" rel="noopener">` links with 1-tap clipboard copy helpers; phone provisioning passes `product=home` (`home_mode`).
5. **Android Pure Parental Rules & Session Pause:** `ParentalRules.kt`, `TailscalePolicy.kt`, and `SessionRules.kt` (`afterPause`) pass unit tests; `PaymentRepository.pauseRunningClock()` idempotently banks active time into `heldSeconds`.
6. **Android Home Mode & Curfew Auto-Pause:** `KioskSettings.isHomeMode` disables guest reset; `KioskSessionSupervisor` auto-pauses sessions when curfew begins; `KioskEngine` credits `tx-chore-*` bonus time and handles box chore synchronization.
7. **Tailscale Remote Management:** `KioskHttpServer` serves the PIN-authenticated 3-Tab Family Console at `/parent` for clients on `100.64.0.0/10`.
8. **PisoKids Dual-Hero Lock Screen Hub:** `KidsQuestLockScreen` displays personalized header, Piggy Bank savings goal bar, Hero 1 (Resume/Drop Coin/Curfew), Hero 2 (Inline Today's Chores Quest board with "I Did It!"), and Bottom Dock (Always-Allowed Apps pills); Floating Pill provides "Pause & Save Time".
9. **Automated Verification:** All host tests, Android unit tests, link checks, security checks, and release checks pass.

## 5. Out of Scope

- Cloud-hosted SaaS relay or external third-party sync servers (Tailscale peer-to-peer / LAN only).
- Hardware modifications to the ESP32 board or coin acceptor mechanism.
- WebADB over Bluetooth or wireless adb pairing (USB-C OTG cable and QR code provisioning only).

## 6. Owner Decisions

- **D1 — Chore-Reward & Coin Model:** Physical Coins + On-Screen Chore Board. Parents reward chores with physical coins or dashboard chore posts granting screen time or coins.
- **D2 — Product Packaging:** Dedicated firmware build (`PISO_PRODUCT_HOME`) and Android app build flag (`IS_HOME_PRODUCT`).
- **D3 — Kept Features:** Core coin/session engine, quick time adjust (`+Min/-Min`), push-to-talk voice clip, locate/ring phone, per-phone rate profiles, spoken countdown warnings & quiet hours.
- **D4 — Stripped Commercial Features:** Router Gateway / PisoWiFi, prepaid QR cards & player accounts, custom shop branding, 1v1 arena mode, guest app data wiping (`GuestReset`), commercial revenue vault, super-admin QR, collector splits, slot licensing.
- **D5 — Kid's Floating Pill Controls:** Keep all 6 controls (Add Coin, Home, Close App, Clear RAM / Boost, Wi-Fi Refresh, End Session) + Volume/Brightness sliders + add Pause / Save Time.
- **D6 — New Parental Features:** On-screen chore board, bedtime/study curfew, pause/save time button, always-allowed apps while locked.
- **D7 — Piggy Bank & Slots:** Total coins saved counter with "Empty Piggy Bank" reset; unlocked family slots.
- **D8 — Tailscale Remote Management:** Always-On VPN lock (`com.tailscale.ipn`), LAN + Tailscale split-tunnel coexistence, remote parent console at `/parent`.
- **D9 — Visual Identity:** Warm, gamified 'Quest & Piggy-Bank' Family Theme (`PisoKidsTheme`, emerald `#10B981` + gold `#F59E0B` + slate `#F8FAFC`/`#0F172A`).
- **D10 — Kid's Lock Screen:** Dual-Hero 'Time + Chores' Hub (personalized header, piggy goal bar, Hero 1 time/resume/coin/curfew, Hero 2 inline chores quest list, bottom dock always-allowed apps).
- **D11 — Parent Dashboard:** 3-Tab Family Action Feed (`Home`, `Chores & Schedule`, `Settings`) with technical hardware settings tucked in a collapsed drawer.
- **D12 — Product Motivation:** Piggy Bank savings goal tracker, child-personalized copy, identical dashboard on local box and remote Tailscale `/parent`.
- **D13 — Phone-to-Phone Setup:** QR code and Android Chromium WebADB USB-C OTG pairing with `product=home`.
- **D14 — Chore Recurrence:** Daily Repeating by default (auto-resets at midnight) + One-Time quest toggle.
- **D15 — Away-from-Home Behavior:** Resume saved time, always-allowed apps, chores, and Tailscale parent control work anywhere; only physical coin drop requires local box Wi-Fi.
- **D16 — Mobile Chrome Flags Guard:** Clickable `<a href="chrome://flags/#unsafely-treat-insecure-origin-as-secure" target="_blank" rel="noopener">` link plus 1-tap clipboard auto-copy fallback.
- **D17 — Product Choice on Setup Page:** Selectable product mode `home` (`PisoHome` / `PisoKids`) in `setup/pisophone_setup.py` (web setup UI at `http://127.0.0.1:8765` and CLI `--product home`). Flashes `esp32-c3-dev-home` / `esp32dev-dev-home` and provisions home Wi-Fi and parent PIN over USB. Firmware builds and OTA feeds published in `.github/workflows/firmware-images.yml` and `scripts/publish_firmware_ota.py`.
- **D18 — Gradle Product Flavors:** Separated source trees via `product` flavor dimension (`commercial` and `home` flavors in `app/build.gradle.kts`), generating `PisoPhone Launcher` for commercial arcade and `PisoKids` for home parental mode without dead commercial code or goldens leakage.
