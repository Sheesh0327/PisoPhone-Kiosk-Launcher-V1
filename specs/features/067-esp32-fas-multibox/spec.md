# Feature 067: ESP32-C3 FAS Offload, Ground-Up PisoWiFi Console & Multi-Coinslot Architecture

Status: **complete**   Branch: `feat/067-esp32-fas-multibox`   Area: router, firmware, website
Release: router, firmware

## Intent

On OpenWrt routers running PisoWiFi, the compiled Rust binary `/usr/bin/pisoportal` occupies **1,182 KB (1.15 MiB)** of uncompressed `/overlay` flash (MIPS/mipsel/x86_64), which expands to **~2.3 MiB to ~3.9 MiB** of peak flash usage during self-updates (rollback copy + downloaded installer). On 8 MB / 16 MB NOR flash routers, this creates severe flash space pressure.

Furthermore, commercial PisoWiFi and PisoPhone operators increasingly require:
1. **Multi-Coinslot Deployment:** Up to 4 physical coin boxes connected to a single OpenWrt router (e.g. Counter, Outside, Waiting Area, Gaming Kiosks) with individual coin tracking and unified revenue accounting.
2. **Dedicated Roles:** Ability to separate responsibilities across boxes (e.g. Box #1 as PisoWiFi FAS + Console, Box #2 as PisoPhone Kiosk Manager) with automatic OTA switching when roles change.
3. **PisoWiFi-Focused Operator Experience:** A clean, mobile-optimized console replacing the legacy PisoPhone admin page on `wifi_only` builds, with direct collection/vault controls, Telegram alerts, and custom branding.
4. **Resilience & High Availability:** Automatic FAS failover to standby coin boxes if the primary box loses power, and seamless customer recovery without dropping paid internet time.

This feature offloads openNDS Forward Authentication Service (FAS) and the operator console to the ESP32-C3 on PisoWiFi-only and multi-box setups, introduces multi-coinslot support across all builds, and trims the router footprint by **~1.14 MiB (-84% permanent / -87% installer)**.

---

## Invariants & Principles

1. **Money and time integrity:**
   - Every coin pulse detected by any physical coin acceptor (#1–#4) must be credited to either an active session or a voucher code, and recorded in the revenue vault.
   - Remote coin events between boxes and the FAS hub must use authenticated challenge-response over TCP with sequence numbers and acknowledgements. **Zero UDP for money.**
   - Cash in box vaults must be tracked individually per physical box (`RevenueVault`) while rolling up to the unified shop revenue ledger.
2. **Deterministic Box Persona & Naming:**
   - The 4 coin box slots have fixed, classic human names:
     - Slot 1: **Esmeralda** (`10.0.0.10`)
     - Slot 2: **Valentina** (`10.0.0.11`)
     - Slot 3: **Isabella** (`10.0.0.12`)
     - Slot 4: **Magdalena** (`10.0.0.13`)
   - All customer splash UI states, text-to-speech audio, Filipino translations, operator console cards, and Telegram alerts must refer to the box by its persona name (e.g. *"Calling Esmeralda…"* / *"Esmeralda accepted payment"*).
3. **No Split-Brain Failover:**
   - Router maintains an authoritative `epoch` counter. A coin box may only serve the guest captive portal if its local epoch matches the router's current epoch.
   - If the active FAS box drops offline and another FAS-capable box is promoted, the epoch increments. A resurrected box starts in standby mode until state is synchronized.
4. **Safe Router Authentication:**
   - The router exposes a lightweight CGI endpoint `/www/cgi-bin/piso-nds` authenticated via a one-time challenge nonce (`/challenge`) and SHA-256 HMAC calculated with `/etc/coinslot.d/gateway_key`.
   - Replay attacks are blocked; no heavy external crypto libraries (such as `openssl-util`) are required on the router.
5. **Storage Safety:**
   - The ESP32-C3 utilizes LittleFS for writable runtime files (`roll.json`, `vouchers.json`, `ledger.jsonl`, `brand.json`, `rates.json`) on the existing 384 KB partition, ensuring power-cut tolerance and wear leveling without altering the flash partition table.

---

## Scope & Components

### 1. Router Agent & OpenWrt Configuration (`router/`, `setup/src/`)
- `/www/cgi-bin/piso-nds`: Lightweight shell CGI handling signed `auth`, `deauth`, `arp`, `status`, `sqm`, `boxes`, `pair-add`, and `set-role`.
- `/etc/coinslot.d/boxes.conf`: Tracking up to 4 paired boxes (`slot`, `mac`, `ip`, `name`, `role`).
- `setup/src/50-coin-box.sh` & `setup/src/80-commands.sh`:
  - `piso-setup pair-add` command to admit additional box MACs into `wireless.box_ap.maclist` and bind static DHCP leases `10.0.0.10..13`.
  - `piso-setup box-role <1..4> <wifi|phone|hub>`.
- `setup/src/30-router-settings.sh`:
  - Scoped firewall rule allowing guest zone (`br-guest`) access to `10.0.0.10..13:2080` TCP only.
  - DNS mapping for `admin.hub` directing to the active FAS/Console box IP.
- `setup/src/40-files.sh` & `tools/build_piso_setup.py`:
  - Support building and distributing `setup/piso-setup-wifi.sh` (installer without `/usr/bin/pisoportal`, ~228 KB).
  - Automatically skip extracting or delete `/usr/bin/pisoportal` when FAS is offloaded.
- `router/piso_monitor.sh`:
  - 30-60s health check of active FAS box.
  - Automatic promotion and snapshot push to standby FAS box if active box fails 2 consecutive checks.
  - HyperSpeed 5+ GB fair-use byte count reporting.
  - Fallback to local static `/www/offline.html` if all boxes are unreachable.

### 2. ESP32-C3 Firmware (`esp32_firmware/`)
- `[wifi_only]` build optimization: Remove `WebDashboard*`, `SuperAdmin*`, `jsqr.js`, `portal*.css/js` (-125 KB flash).
- `[phone_only]` build enhancement: Compile `GatewayCoinslot.cpp` and `WebServerGateway.cpp` so PisoPhone boxes can accept coins for PisoWiFi.
- `WifiFasServer` (:2080):
  - Captive portal serving gzipped assets from PROGMEM.
  - Stateless HTTP `/view` and `/act` with `Connection: close`.
  - Ephemeral WebSocket `/ws` opened only during the 60-second active coin window.
  - Station picker for multi-box setups with box persona names.
- `MultiBoxCoinCoordinator`:
  - Local GPIO ISR coin pulse reading on local box.
  - Challenge-response authenticated proxying to remote boxes (#2–#4) via `/api/gateway/open`, `/api/gateway/status`, and `/api/gateway/close`.
- `WifiConsoleServer` (:80):
  - Self-contained 6-tab operator console in PROGMEM (`admin.hub`).
  - Cash vaults per box, live customer sessions (+time, pause, kick), pricing, vouchers, brand pack verification (ECDSA P-256), and Telegram alerts config.

---

## Success Criteria

1. **Router Footprint:**
   - Permanent `/overlay` flash usage reduced by `> 1.1 MiB` on PisoWiFi-only routers.
   - Installer size of `piso-setup-wifi.sh` is `<= 260 KB` (down from 1.80 MB).
2. **Multi-Box Pairing:**
   - Router successfully pairs and assigns static leases for up to 4 boxes (`10.0.0.10` to `10.0.0.13`) under `wireless.box_ap`.
3. **Persona Naming & UI:**
   - Customers selecting Box 1 see and hear "Esmeralda", Box 2 "Valentina", Box 3 "Isabella", Box 4 "Magdalena".
   - Localization in English and Filipino properly substitutes persona names.
4. **FAS & Failover Reliability:**
   - When Box #1 is powered down, `piso_monitor.sh` promotes Box #2 within 90 seconds, updates `opennds` `fasremoteip` + `admin.hub` DNS, and syncs session state without dropping active customer time.
5. **OTA Headroom:**
   - `wifi_only` ESP32-C3 binary size remains `<= 1,150 KB` (leaving `> 640 KB` free in the 1,792 KB OTA slot).
6. **Router Installation Order & Modem Conflict Avoidance:**
   - Factory router address changes from `192.168.1.1` to `10.0.0.1` before anything else, preventing WAN routing deadlocks with upstream ISP modems at `192.168.1.1`. Downloads and package updates proceed only after the router is on `10.0.0.1`.
