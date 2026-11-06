# Implementation Plan - Feature 067: ESP32-C3 FAS Offload, Ground-Up PisoWiFi Console & Multi-Coinslot Architecture

## Phase Breakdown

### Phase 1: Router Signed CGI Endpoint, Multi-Box Setup & Failover Monitor
1. **Router Signed CGI (`router/piso_nds_cgi.sh` -> `/www/cgi-bin/piso-nds`):**
   - Challenge generation: random hex nonce stored in `/tmp/piso-cgi-nonces/` (30-second TTL).
   - Signature check: `sha256sum` over `nonce + body + gateway_key`.
   - Actions:
     - `auth`: runs `ndsctl auth <mac> <mins> <up> <down> <qup> <qdown>`.
     - `deauth`: runs `ndsctl deauth <mac>`.
     - `arp`: parses `/proc/net/arp` for matching IP and returns MAC.
     - `status`: returns `ndsctl json` (clients, uptime, counters).
     - `sqm`: updates SQM cake bandwidth settings and reloads sqm.
     - `boxes`: reads `/etc/coinslot.d/boxes.conf`.
     - `pair-add`: pairs new box MAC, assigns slot 2..4, updates wireless and dhcp.
     - `set-role`: sets box role (`wifi`, `phone`, `hub`).
     - `telegram`: updates `/etc/piso-monitor.conf` and pairs TG bot.
2. **Setup Script Modifications (`setup/src/`):**
   - `setup/src/30-router-settings.sh`:
     - Allow preauthenticated traffic from `guest` to `10.0.0.10..13` on port `2080` TCP.
     - Install uhttpd CGI handler `/www/cgi-bin/piso-nds` and permissions.
     - Configure `dnsmasq` address mapping `/admin.hub/` to active FAS IP.
   - `setup/src/50-coin-box.sh`:
     - Multi-MAC whitelist in `wireless.box_ap.maclist` and static DHCP reservations (`pisocoinbox1` through `pisocoinbox4`).
     - Functions: `pair_box`, `pair_add_box`, `set_box_role`.
   - `setup/src/40-files.sh`:
     - If `SHOP_PRODUCT=wifi` or FAS is offloaded, skip extracting `/usr/bin/pisoportal`.
   - `setup/src/80-commands.sh`:
     - CLI commands: `piso-setup pair-add`, `piso-setup box-role <slot> <role>`.
   - `setup/src/98-setup-flow.sh`:
     - Unify default `BOX_ADMIN_PASS_NEW` with `ROOT_PASS` on `wifi_only` setups.
   - `tools/build_piso_setup.py`:
     - Add build target for `setup/piso-setup-wifi.sh` (excluding binary payload, targeting ~228 KB).
3. **Failover & Snapshot Monitor (`router/piso_monitor.sh` & `router/pisoportal_status.sh`):**
   - Track `ACTIVE_FAS_IP` and `EPOCH` in `/etc/coinslot.d/epoch.conf`.
   - Every 60s:
     - Check health of `http://$ACTIVE_FAS_IP:2080/ping`.
     - Check `ndsctl json` byte usage and report to active FAS box.
     - If active box fails 2 ticks: promote next online FAS-capable box, increment epoch, sync `/etc/coinslot.d/` snapshot, update `opennds` `fasremoteip` + `dnsmasq` `admin.hub`, reload services.
     - If all boxes fail: point openNDS to local static `/www/offline.html`.

### Phase 2: ESP32-C3 Firmware FAS Server & Multi-Box Coin Coordinator
1. Enable `GatewayCoinslot.cpp` and `WebServerGateway.cpp` in `[phone_only]` build environment (`esp32_firmware/envs/products.ini` and `include/Product.h`).
2. Add Persona Naming and slot identification:
   - Slot 1: Esmeralda (`10.0.0.10`)
   - Slot 2: Valentina (`10.0.0.11`)
   - Slot 3: Isabella (`10.0.0.12`)
   - Slot 4: Magdalena (`10.0.0.13`)
3. Implement `WifiFasServer.cpp` (:2080) on ESP32-C3:
   - Embed gzipped `page.html`, `base.css`, `brand_page.css`, `brand_fil.js`, `brand.png`, `mascot.png`.
   - Multi-box station selector on `page.html` with persona names and live status (`Ready`, `In Use`).
   - Stateless `GET /view` and `POST /act` with `Connection: close`.
   - Ephemeral `/ws` for the 60s coin insertion window.
4. Implement `MultiBoxCoinCoordinator.cpp`:
   - If customer selects local box: arm local `GatewayCoinslot`.
   - If customer selects remote box: call remote box `/api/gateway/open`, poll `/api/gateway/status`, handle pulses.
   - Persist sessions, vouchers, and transactions using LittleFS.

### Phase 3: Ground-Up PisoWiFi Operator Console (:80) on `wifi_only`
1. Remove `WebDashboard*`, `SuperAdmin*`, `jsqr.js`, `adminqr.js`, `portal*.css/js` from `[wifi_only]` in `products.ini`.
2. Implement `WifiConsoleServer.cpp` (:80):
   - In-page session login + HTTP Basic Auth for CLI / automated calls.
   - 6 Tabs:
     1. Overview & Cash Boxes (Sales today, 7d, 30d, all-time, per-box vault empty button).
     2. Customers (Live connected & paused devices, +Time, Pause, Kick).
     3. Coin Boxes & Roles (Status, persona name, role selector, Blink LED, Add Coin Box).
     4. Rates & Smooth Line (HyperSpeed, Endurance, Fair Usage 5+ GB, SQM).
     5. Vouchers (Choice toggle, single/batch generator, revoke).
     6. Branding & System (Bilingual texts, mbedtls ECDSA P-256 brand pack verification, pulse guard, Telegram config, OTA updates).

### Phase 4: Verification, Footprint Tests & Validation
1. Run Python test suites (`router/tests/test_footprint.py`, `router/tests/test_setup.py`, `router/tests/test_monitor.py`).
2. Verify binary sizes and footprint budgets.
3. Validate golden records and multi-box failover simulations.
