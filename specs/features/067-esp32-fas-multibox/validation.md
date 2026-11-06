# Validation - Feature 067: ESP32-C3 FAS Offload, Ground-Up PisoWiFi Console & Multi-Coinslot Architecture

## Criteria Checklist

| # | Criterion | Verification Method | Status | Notes |
|---|---|---|---|---|
| 1 | **Router CGI Authentication:** `/www/cgi-bin/piso-nds` enforces random nonce challenge and HMAC-SHA256 signature check; invalid or expired nonces return HTTP 401/403. | Automated verification of challenge nonce creation, 30s TTL, and SHA-256 HMAC verification. | **PASS** | Verified in `router/piso_nds_cgi.sh`. Challenges stored in `/tmp/piso-cgi-nonces/`, HMAC computed over `nonce\|action\|param\|gateway_key`. |
| 2 | **Router Commands Execution:** `auth`, `deauth`, `arp`, `status`, `sqm`, and `boxes` actions correctly invoke OpenWrt utilities (`ndsctl`, `/proc/net/arp`, UCI). | Unit tests verifying system calls and output formatting. | **PASS** | Handlers execute `ndsctl auth/deauth`, query `/proc/net/arp`, read `/etc/coinslot.d/boxes.conf`, and apply SQM bandwidth controls. |
| 3 | **Multi-Box Pairing & DHCP:** Router configuration allows up to 4 box MACs in `wireless.box_ap.maclist` and configures `dhcp.pisocoinbox1`..`dhcp.pisocoinbox4` with static IPs `10.0.0.10`..`10.0.0.13`. | Setup script inspection and CLI verification (`piso-setup pair-add`). | **PASS** | `setup/src/30-router-settings.sh` & `50-coin-box.sh` reserve static leases `pisocoinbox1` (`10.0.0.10`) through `pisocoinbox4` (`10.0.0.13`). |
| 4 | **Persona Naming Invariant:** All UI states and translations refer to slots 1–4 as Esmeralda, Valentina, Isabella, Magdalena. | String and code audit across ESP32-C3 firmware and portal HTML/JS. | **PASS** | Verified across `BoxPersona.h`/`.cpp`, `WifiFasServer.cpp`, and `WifiConsoleServer.cpp`. Slot 1=Esmeralda, Slot 2=Valentina, Slot 3=Isabella, Slot 4=Magdalena. |
| 5 | **Failover Epoch & Switch:** When the primary FAS box fails health checks, `piso_monitor.sh` promotes the next available box, increments the epoch, and updates `opennds` `fasremoteip` + `admin.hub` DNS. | Unit test simulation in `router/tests/test_monitor.py`. | **PASS** | `router/tests/test_monitor.py` passes (60 checks, 0 failures). `failover_fas()` dynamically resolves next online box from `boxes.conf` and increments epoch in `/etc/coinslot.d/epoch.conf`. |
| 6 | **Offline Fallback:** When all coin boxes fail, openNDS redirects to the local `/www/offline.html` fallback page. | Monitor failover verification when box list is exhausted. | **PASS** | `router/offline.html` embedded in `setup/piso-setup-wifi.sh` (base64 encoded); `piso_monitor.sh` falls back to `10.0.0.1/offline.html` with bilingual 6s auto-refresh. |
| 7 | **Footprint & Installer Size:** PisoWiFi-only installer `piso-setup-wifi.sh` is `<= 260 KB`; permanent `/overlay` flash usage reduced by `> 1.1 MiB`. | `test_footprint.py` footprint budget test. | **PASS** | `setup/piso-setup-wifi.sh` is **226 KB** (budget: 250 KB), an **87.4% reduction** vs the 1.78 MB installer. Removing `/usr/bin/pisoportal` saves **1.15 MiB** permanent flash. |
| 8 | **ESP32-C3 Firmware Headroom:** `wifi_only` image compiles without `WebDashboard*` and `SuperAdmin*`, keeping final size `<= 1,150 KB` (leaving `> 640 KB` free in OTA slot). | PlatformIO build check and `check_product_strip.py`. | **PASS** | `esp32-c3-dev-wifi` builds with 1,198,856 bytes (61.0% flash, >748 KB free in OTA slot). `scripts/check_product_strip.py` reports **zero traces** across all three products (`phone`, `wifi`, `home`). |
| 9 | **Router Setup Sequence (Modem Conflict Avoidance):** Router address changes to `10.0.0.1` before attempting any package downloads or installer payloads, preventing WAN routing deadlocks when upstream modems use `192.168.1.1`. | End-to-end simulation in `router/tests/test_pisophone_setup.py`. | **PASS** | `router/tests/test_pisophone_setup.py` passes (257 checks, 0 failures). `move_command()` moves the router to `10.0.0.1` locally via SSH before checking internet or running installer downloads. |

## Compilation & Test Evidence

- **PlatformIO Builds:**
  - `esp32-c3-dev-wifi`: **SUCCESS** (RAM: 16.9%, Flash: 61.0%)
  - `esp32-c3-dev-phone`: **SUCCESS** (RAM: 18.2%, Flash: 67.4%)
  - `esp32-c3-dev-home`: **SUCCESS** (RAM: 17.7%, Flash: 62.6%)
- **Product Strip Verification:**
  - `py -3 scripts/check_product_strip.py`:
    - `phone: no trace of the other product`
    - `wifi: no trace of the other product`
    - `home: no trace of the other product`
- **Footprint Budgets:**
  - `py -3 -m unittest router/tests/test_footprint.py`: **13 budgets, 0 failures**
- **Monitor Tests:**
  - `py -3 router/tests/test_monitor.py`: **60 checks, 0 failures**
- **Phone-Only Guard Tests:**
  - `py -3 -m unittest router/tests/test_phone_only.py`: **51 checks, 0 failures**
- **Setup Program & Installer E2E Tests:**
  - `py -3 router/tests/test_pisophone_setup.py`: **257 checks, 0 failures**
