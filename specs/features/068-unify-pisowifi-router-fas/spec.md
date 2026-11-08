# Feature 068: Unify PisoWiFi FAS Back to Router (`pisoportal`) + Native Multi-Box Support

Status: **in progress**   Branch: `feat/068-unify-pisowifi-router-fas`   Area: router, firmware, pisoportal, setup
Release: router, firmware

## Intent

Feature 067 offloaded openNDS Forward Authentication Service (FAS) and the operator console onto the ESP32-C3 coin box (`10.0.0.10`) for `wifi_only` setups to save ~80 KB of router flash. However, this offload created severe architectural drawbacks:
1. **Split-brain state & complexity:** The router and coin box maintained duplicate epoch tracking (`epoch.conf`), CGI callbacks (`/www/cgi-bin/piso-nds`), and complex failover logic (`failover_fas()`).
2. **Divergent code paths:** `wifi_only` and `both` router setups branched across setup scripts, firewall rules, and packaging, requiring a separate `setup/piso-setup-wifi.sh` installer bundle.
3. **Firmware bloat:** ESP32 firmware had to maintain `WifiFasServer`, `WifiConsoleServer`, `MultiBoxCoordinator`, and `BoxPersona` HTTP endpoints, reducing simplicity and testability.

**Feature 068** unifies all router installations (`wifi` and `both`) back onto the standard router architecture:
- The OpenWrt router (`10.0.0.1` / `10.0.10.1`) always runs `/usr/bin/pisoportal`, serving both the Guest Captive Portal (`10.0.10.1:2080`) and the Operator Console (`admin.hub` -> `10.0.0.1:80`), calling `ndsctl` directly via `nds.rs`.
- Every coin box (`10.0.0.10`..`10.0.0.13`, `[wifi_only]` or `[full]`) runs the standard `GatewayCoinslot` + `WebServerGateway` + `WebDashboard` firmware as a hardware coin acceptor / phone kiosk slave over HMAC-signed `BoxLink`.
- Native multi-box support is retained and streamlined: `/etc/coinslot.d/boxes.conf` and `piso-setup pair-add` live on the router, while Rust `pisoportal` coordinates multi-box coin windows, persona UI display, per-box reconciliation, and console switching.

---

## Confirmed Design Decisions (D1–D15)

- **D1 (Multi-Box Architecture):** Retain `/etc/coinslot.d/boxes.conf` and `piso-setup pair-add` on the router; add native multi-box support directly into Rust `pisoportal`.
- **D2 (ESP32 `[wifi_only]` Firmware):** Delete `WifiFasServer.{h,cpp}` and `WifiConsoleServer.{h,cpp}`; restore `WebDashboardHtml.cpp` and `WebDashboardComponents.cpp` on `[wifi_only]` so `[wifi_only]` and `[full]` share the same gateway + admin dashboard architecture.
- **D3 (ESP32 `[phone_only]` Firmware):** Restore `PISO_HAS_GATEWAY=0` on `[phone_only]` (exclude `GatewayCoinslot.cpp` and `WebServerGateway.cpp`), returning `[phone_only]` to a standalone PisoPhone build.
- **D4 (Router Setup Bundle):** Remove `setup/piso-setup-wifi.sh` (and `.sha256`). All router setups use standard per-CPU-architecture bundles (`piso-setup.sh`, `piso-setup-mips.sh`, etc.) containing `/usr/bin/pisoportal`.
- **D5 (Router Setup & `10.0.0.1` Move-First):** Keep the `10.0.0.1` LAN IP move-first sequence and staging/arch-detection fixes in `setup/install.sh`, `setup/src/20-preflight.sh`, and `setup/pisophone_setup.py`.
- **D6 (Coin-Box Admin Password):** Unify coin-box password setup in `setup/src/98-setup-flow.sh`: generate an independent 16-char `BOX_ADMIN_PASS_NEW` (unless explicitly supplied) for both `wifi` and `both`.
- **D7 (Per-Box Coin Window Concurrency):** Allow 1 active coin window per paired box (up to 4 in parallel across slots 1–4). Customers at different boxes can insert coins simultaneously; only customers picking the same busy box wait.
- **D8 (Captive Portal Persona UI):** Always display the coin box persona name on `page.html` (even when only 1 box is paired, showing **Esmeralda**), and render a box selector when 2–4 boxes are paired. Show the chosen box name during active coin countdown.
- **D9 (Dead-Code & Split-Brain Removal):** Delete `router/piso_nds_cgi.sh`, `router/offline.html`, `epoch.conf`, `piso-setup box-role`, `failover_fas()` in `router/piso_monitor.sh`, `MultiBoxCoordinator.{h,cpp}`, and `BoxPersona.{h,cpp}`.
- **D10 (Multi-Box Revenue Reconciliation):** Track per-box counter baselines in `reconcile.state` inside `reconcile.rs` and compare the router's revenue ledger against the sum of counted coins across all paired boxes. Emptying one box's vault advances `ledger_base` by that box's collected amount and resets only that box's baseline.
- **D11 (Operator Console Multi-Box View):** Add a box switcher (Esmeralda / Valentina / Isabella / Magdalena) and pairing helper inside `#phnote` on the `#t-ph` tab via `console-ui.js` + `boxview.rs` without increasing `console.html` byte size.
- **D12 (Captive Portal Default Selection):** When 1 box is paired, Esmeralda is auto-selected; when 2+ boxes are paired, require the customer to tap which box they are standing at before **Insert Coins** arms a box so they never arm the wrong physical box across the room.
- **D13 (Offline Box Handling in Reconciliation):** Cache each box's last known counter in `reconcile.state` and report `NoBox` / `BOX_DOWN` in `reconcile::run` if any enabled box is unreachable, while `reconcile::peek` uses the cached counter of an offline box so console totals stay accurate without false `MISMATCH` alerts.
- **D14 (Multi-Box Provisioning & Unpairing Commands):** `piso-setup pair-add` provisions every added box with the router's stored `BOX_ADMIN_PASS` + `GW_KEY` + rotated `BOX_SSID`/`BOX_WIFI_PASS_NEW` (and restores `box_ap` so all boxes rejoin); also add `piso-setup pair-remove <slot>` and `piso-setup pair-list`.
- **D15 (Per-Box Health Monitoring, Maintenance & Vault Totals):** Monitor and report each paired box individually in `piso_monitor.sh` and `/api/overview`; apply `box-update`, `rotate-box-wifi`, and `kiosk-wifi` across all enabled boxes in `boxes.conf`; keep `revenue.csv`'s 6-column hash-chained format unchanged and show per-box vault totals via `reconcile.rs` in `piso-setup reconcile` and the Console Money card.

---

## Architectural Invariants

1. **Router Is Always FAS and Console Gateway:**
   - Guests on `10.0.10.0/24` only ever connect to the router (`10.0.10.1:2080`). Guests are never routed or bridged directly to `10.0.0.10..13:2080`.
   - `admin.hub` always resolves to `$LAN_IP` (`10.0.0.1`).
   - OpenNDS calls `ndsctl` locally on the router. No CGI callbacks or remote FAS redirection to coin boxes.
2. **Boxes are Slaves Over HMAC `BoxLink`:**
   - Coin boxes expose standard `/api/gateway/*` endpoints protected by `GW_KEY`.
   - All monetary pulse queries and window arming use HMAC-signed requests.
3. **Fixed Slot Personas:**
   - Slot 1 = `Esmeralda` (`10.0.0.10`)
   - Slot 2 = `Valentina` (`10.0.0.11`)
   - Slot 3 = `Isabella` (`10.0.0.12`)
   - Slot 4 = `Magdalena` (`10.0.0.13`)
4. **Footprint Budgets Preserved:**
   - `page.html` budget: 28 KB (current 26,245 B).
   - `console.html` budget: 30 KB (current 30,272 B).
   - `console-ui.js` budget: 33 KB (current 32,072 B).
   - `piso-setup.sh.in` budget: 195 KB.

---

## Scope & Changes

1. **`specs/`:** Add Feature 068 spec, update `specs/STATUS.md` and `specs/features/067-esp32-fas-multibox/spec.md`.
2. **`esp32_firmware/` & `scripts/`:**
   - Delete `WifiFasServer.{h,cpp}`, `WifiConsoleServer.{h,cpp}`, `MultiBoxCoordinator.{h,cpp}`, `BoxPersona.{h,cpp}`.
   - Restore `PISO_HAS_GATEWAY 0` in `[phone_only]` and restore `WebDashboard*` in `[wifi_only]`.
   - Clean up `main.cpp` and `WebServer*.cpp`.
   - Restore strip rules in `scripts/check_product_strip.py`.
3. **`router/`, `setup/`, `tools/build_piso_setup.py`:**
   - Delete `router/piso_nds_cgi.sh`, `router/offline.html`, `setup/piso-setup-wifi.sh*`.
   - Remove `wifi_only` bundle target from `tools/build_piso_setup.py`.
   - Update `30-router-settings.sh`: keep FAS and `admin.hub` on the router; remove guest rules to `10.0.0.10..13:2080`.
   - Update `40-files.sh`: always install `/usr/bin/pisoportal`; remove `epoch.conf`; initialize `boxes.conf`.
   - Update `50-coin-box.sh`: streamline `pair_add_box()`, add `pair_remove_box()` and `pair_list_boxes()`, update `rotate_box_wifi()` and `push_kiosk_wifi()` to iterate across boxes.
   - Update `80-commands.sh`, `91-box-update.sh`, `95-owner-tools.sh`, `98-setup-flow.sh`: wire `pair-remove`/`pair-list`, remove `box-role`, update `box-update` across boxes, unify `BOX_ADMIN_PASS_NEW`.
   - Update `router/piso_monitor.sh` & `router/pisoportal_status.sh`: monitor each paired box individually, remove `epoch.conf` and `failover_fas`.
4. **`tools/pisoportal/`:**
   - `config.rs`: load paired boxes dynamically from `boxes.conf` with canonical personas.
   - `core.rs` & `http.rs`: support per-box coin windows (up to 4 in parallel, 1 per slot), persona reporting in `View::Idle` / `View::Armed`, box slot selection in WebSocket `start` and `/go`.
   - `page.html` & `brand_fil.js`: station selector `#bx-pick` and active persona display `#l-sub`, preserving all 15 `brandpage.rs` anchors.
   - `reconcile.rs`: per-box counter baselines in `reconcile.state`, per-box vault totals in `Money::json()`, caching offline box counts to prevent false mismatches.
   - `boxview.rs`, `console.rs`, `console-ui.js`: multi-box reachability in `/api/overview`, `/api/boxes` selection endpoint, console switcher pill bar in `#phnote`.

---

## Acceptance Criteria

1. **Single Architecture:** Both `wifi` and `both` router installations install `/usr/bin/pisoportal` and run FAS/Console locally on the router. `piso-setup-wifi.sh` is completely removed.
2. **Clean ESP32 Firmware:** All 067 ESP32 FAS/Console/Coordinator/Persona files are removed. `scripts/check_product_strip.py --source-only` passes cleanly across `phone`, `wifi`, and `home`.
3. **Multi-Box Coin Windows:** Up to 4 customers can insert coins at different boxes simultaneously without blocking each other.
4. **Captive Portal Station Picker:** When 1 box is paired, Esmeralda is auto-selected; when 2–4 boxes are paired, customers must tap their station before inserting coins.
5. **Reconciliation & Vaults:** `reconcile.state` tracks per-box baselines, emptying one box does not disturb other boxes' baselines, and offline boxes do not cause false `MISMATCH` alerts.
6. **Console Switching:** Operator console provides a box switcher on the coin box tab and lists all paired boxes in `/api/overview`.
7. **Footprints & Security:** All size budgets in `router/tests/test_footprint.py` and `scripts/check_security_rules.sh` pass.
