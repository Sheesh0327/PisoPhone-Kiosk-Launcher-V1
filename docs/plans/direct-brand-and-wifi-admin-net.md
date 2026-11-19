# Implementation Plan & Handoff: Direct Branding Upload + Marquee Glow + PisoWiFi Admin Network

**Branch:** `feat/direct-brand-and-wifi-admin-net` (branched from `main` at `5fcbe34`)  
**Target Release Bump:** `setup/RELEASE` `1.5.46` → `1.5.47` (single bump when both workstreams are verified)

---

## 1. Current Progress Summary

### Workstream A — Direct Branding Upload & Portal Title Marquee Glow (Implemented, Needs Full CI Verification)
All code and test updates for Workstream A are **implemented and committed on this branch**:
1. **Direct PNG/JPEG upload in Console → Branding**:
   - Removed the owner-signed `.brand` pack requirement for Console uploads (`tools/pisoportal/src/{brandpack.rs,brandpage.rs,console.rs,console.html,console-ui.js}`).
   - Users can upload their own logo and welcome picture directly (with client-side resize/compress `<= 64 KB`), while legacy `piso-setup brand-pack` / `/api/brand/pack` remains intact.
2. **Portal Title Marquee + Subtle Glowing Blink**:
   - Updated shop name / default `TechNet` title in `tools/pisoportal/src/{base.css,brand_page.css,page.html}` to animate right-to-left (marquee) with a subtle glowing blink effect.
3. **Updated Docs & Tests**:
   - `docs/UI_DESIGN.md`, `scripts/README.md`, `router/tests/test_footprint.py`
   - `tools/pisoportal/tests/{golden/page_states.json,test_console.py,test_console_brand.py,test_console_layout.py}`
4. **Remaining for Workstream A**:
   - Run the full CI verification suite (alongside Workstream B) to confirm merge-readiness to `main`.

### Workstream B — PisoWiFi-Only Hidden Admin Network (`PisoAdmin`) + QR Code (Not Yet Implemented)
All codebase research and exact constraint verification for Workstream B are complete. Zero files for Workstream B have been modified yet.

---

## 2. Workstream B: Verified Architecture & Exact Constraints

### 2.1 Network, Firewall, and Isolation Design
- **When `SHOP_PRODUCT=wifi` (`wifi_only`)**:
  - Replace the omitted `PisoKiosk` rental-phone Wi-Fi with an isolated, hidden WPA2 **Admin Network**:
    - SSID: `PisoAdmin-<4-hex-id>` (`ADMIN_BASE="PisoAdmin"`, `ADMIN_SSID_NAME` in `/etc/piso-setup.conf`)
    - Radios: 2.4 GHz + 5 GHz (`wireless.admin_$r`, `hidden='1'`, `encryption='psk2'`, `isolate='0'`)
    - Password: 12-char generated or custom `ADMIN_PASS` (env override `ADMIN_PASSWORD`)
    - Bridge & IP: `br-admin` (`network.admin_dev`, `network.admin`), `ADMIN_IP` default `10.0.20.1/24`, DHCP pool `dhcp.admin`.
  - When switched back to `both`: cleanly emit `delete network.admin_dev`, `delete network.admin`, `delete dhcp.admin`, and `delete firewall.admin*`, and `conf_del ADMIN_PASS`.
- **Console & BoxView Binding (`setup/src/40-files.sh`, `tools/pisoportal/src/{boxview.rs,console.rs}`)**:
  - `write_coinslot_conf()` sets `CONSOLE_BIND='$LAN_IP'` (`10.0.0.1`). `pisoportal` binds Console on `CONSOLE_BIND:CONSOLE_PORT` (`80` or `8081`) and BoxView on `CONSOLE_BIND:(CONSOLE_PORT + 1)` (`81` or `8082`).
  - When an admin device on `PisoAdmin` (`10.0.20.x` on `br-admin`) opens `http://admin.hub`, `dnsmasq` returns `10.0.0.1` (local router delivery → fw4 `input_admin`).
- **Firewall Zone `admin` (`setup/src/30-router-settings.sh`)**:
  - `zone 'admin'`: `input='REJECT'`, `output='ACCEPT'`, `forward='REJECT'`.
  - Permits:
    - WAN internet (`admin -> wan`)
    - DHCP (`67/udp`) & DNS (`53/udp+tcp`)
    - Hub Console + BoxView (`admin_console`: `src='admin'`, `proto='tcp'`, `dest_port='$(console_port) $(($(console_port) + 1))'`, `target='ACCEPT'`)
    - Coin Box (`admin_box`: `src='admin'`, `dest='lan'`, `dest_ip='$BOX_IP'`, `proto='tcp'`, `target='ACCEPT'`)
  - Blocks: SSH (`22`), LuCI (`8080/8443`, or `80/443` when console is off on `8081`), and `guest <-> admin`.
- **`router/piso_isolation.sh`**:
  - Confirmed `br-admin` is completely untouched by `piso_isolation.sh` (only operates on `br-guest` and `br-lan`).

### 2.2 Critical `router/tests/test_setup.py` Invariants
1. **`BOTHCONF` → `wifi` switch test (`test_setup.py:1695-1700`)**:
   - Asserts **exact equality** after `ASSUME_YES=1; resolve_wifi_names; confirm_product; apply_product; write_coinslot_conf k1 aa` with `SHOP_PRODUCT=wifi`:
     `sorted(after.splitlines()) == sorted([l for l in BOTHCONF.splitlines() if not l.startswith("KIOSK_PASS=")] + ["SHOP_PRODUCT='wifi'"])`.
   - **`resolve_wifi_names()`** MUST ONLY compute `ADMIN_SSID` in memory (check `ADMIN_SSID_NAME` first; if empty, reuse 4-hex suffix from `KIOSK_SSID_NAME` or `BOX_SSID_NAME`, else `router_id` → `$ADMIN_BASE-$_id`) and MUST NOT write `$CONF`.
   - **`apply_product()`** MUST ONLY call `store_product`, then `if wifi_only; then conf_del KIOSK_PASS; else conf_del ADMIN_PASS; fi`.
2. **`remember_wifi_names` (`test_setup.py:1162`)**:
   - Expects `KIOSK_SSID_NAME` and `BOX_SSID_NAME` on `both`. Add `if wifi_only; then conf_set ADMIN_SSID_NAME "$ADMIN_SSID"; fi` so `both` is untouched and `wifi` persists `ADMIN_SSID_NAME` during `stage1`.
3. **`cmd_update` (`test_setup.py:1250`)**:
   - On `! wifi_only`, asserts every batch line starts with existing prefixes. ONLY emit `admin_firewall_uci` in `cmd_update` (and `console_apply`) `if wifi_only`.
4. **`agree()` with `SHOP_PRODUCT=wifi` (`test_setup.py:1634-1636`)**:
   - Check `ag_is_admin` (`ag_every_iface admin_ key "$(conf_get ADMIN_PASS)"`, `ssid "$ADMIN_SSID"`, `br-admin` `inet $ADMIN_IP/`) when `wifi_only`.
   - Check `ADMIN_PASS` in `ag_is_summary()` ONLY when `wifi_only && [ -n "$(conf_get ADMIN_PASS)" ]`.
   - Update `test_setup.py:1634` (`AG_PRE`/`agree()` for `wifi`) to provide `admin_radio0`/`admin_radio1`/`br-admin` and add failure assertions for mismatched `ADMIN_PASS`, `ADMIN_SSID`, and missing `br-admin`.
5. **Summary / Handout / Review (`test_setup.py:1642-1648`)**:
   - On `wifi`, output MUST NOT contain `"Kiosk Wi-Fi"`, `"Rental-phone Wi-Fi"`, `"rental phone"` (case-insensitive), or `"PisoKiosk"` after `<html`.
   - Use `"Admin Wi-Fi:"` in `60-summary.sh`, `"Admin Wi-Fi (hidden)"` in `95-owner-tools.sh`, and `"Admin Wi-Fi"` / `"Admin Wi-Fi password"` in `98-setup-flow.sh`.
6. **`check_all` (`test_setup.py:1821-1831`)**:
   - `w_ck` compares fail counts `RC1 == RC2 + 1`. Adding `br-admin` & `$ADMIN_SSID` checks to `check_all()` preserves `RC1 == RC2 + 1` (verify explicitly).

### 2.3 Portal Console Budgets & Policy Constraints (`tools/pisoportal/`)
- **Footprint Budgets (`router/tests/test_footprint.py` & `test_console.py:100`)**:
  - `console.html`: **30,191 B** used / **30,720 B** max (**529 B left**); rendered `/` must be `< 31,000` B. Keep `console.html` addition to `<div id="admw" class="hide"></div>` inside `<section id="t-set">` + minimal CSS (`<= ~230 B`).
  - `console-ui.js`: **33,116 B** used / **34,816 B** max (**1,700 B left**). Keep new JS concise (`< 1,300 B`).
- **Forbidden Strings (`test_console.py:311`)**:
  - `"Print"` (capital P) MUST NOT appear in rendered `/` HTML → use `"Open QR card"` for link to `/admin-wifi/card`.
  - `"@media print"` MUST NOT appear in `console-ui.js` → keep `@media print` only inside Rust's `/admin-wifi/card` HTML response.
- **`GET /api/settings` (`test_console.py:230`)**:
  - Must remain exact `{voucher_choice, file_send, file_send_key, ledger_max_kb, ledger_keep_months}`. Expose Admin Wi-Fi separately on `GET /api/admin-wifi` (auth-guarded like all `/api/*`).
- **`/admin-wifi/card` Security Headers**:
  - Gate `GET|HEAD /admin-wifi/card` with `c.session_ok(&req)` (`401 Unauthorized` otherwise).
  - Set `CSP: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`.
  - Escape all dynamic values with `html_esc` and never put credentials in URLs.
- **Layout Tests (`test_console_layout.py`)**:
  - Runs `console-ui.js` against `golden/console_1_5_32.html` (no `#admw`), so guard DOM access with `if($('admw'))` and use `dataset.sig` to avoid resetting Show/Hide state on the 4s poll.
  - Wrap QR SVGs in white-backed containers (`class="qr"` with `background:#fff;padding:8px;border-radius:8px;display:inline-block`) with ZERO text inside `.qr` so dark-theme contrast checks (`>= 4.5:1`) pass.
  - Bump `PISO_UI_VERSION = 17` in `console-ui.js`.

---

## 3. Step-by-Step Implementation Plan

### Step 1: Router Setup Scripts (`setup/src/*.sh`)
- `00-header.sh`: Add `ADMIN_BASE="PisoAdmin"`, `ADMIN_SSID="$ADMIN_BASE"`, `ADMIN_IP` (`10.0.20.1`), document `PisoAdmin-xxxx` and `ADMIN_IP`/`ADMIN_PASSWORD` overrides.
- `10-settings.sh`: `resolve_wifi_names()` sets `ADMIN_SSID` in memory; `remember_wifi_names()` adds `if wifi_only; then conf_set ADMIN_SSID_NAME "$ADMIN_SSID"; fi`.
- `20-preflight.sh`: `wan_clash()` checks `if wifi_only; then set -- "$LAN_IP" "$GUEST_IP" "$ADMIN_IP"; else set -- "$LAN_IP" "$GUEST_IP"; fi` and `preflight_network()` handles `"$ADMIN_IP"` clash cleanly.
- `30-router-settings.sh`: Add `admin_pass()` / update `uci_batch()` to take `$4` = `ADMIN_PASS`; extract `admin_firewall_uci()` (zone `admin`, `admin_wan`, `admin_dhcp`, `admin_dns_tcp`, `admin_console`, `admin_box`); on `wifi_only` emit `network.admin_dev`/`network.admin`/`dhcp.admin`, `wireless.admin_$r` (`hidden='1'`, `encryption='psk2'`, `isolate='0'`), and `admin_firewall_uci`; on `! wifi_only` emit `delete network.admin_dev`, `delete network.admin`, `delete dhcp.admin`, and `delete firewall.admin*`.
- `60-summary.sh`: When `wifi_only`, print `Admin Wi-Fi:           $ADMIN_SSID (HIDDEN)   password: $(conf_get ADMIN_PASS)    (your devices only: opens http://admin.hub and the coin box; blocked from guests)`.
- `70-agreement.sh`: Add `ag_is_admin()` and check when `wifi_only`; check `ADMIN_PASS` in `ag_is_summary()` when `wifi_only && [ -n "$(conf_get ADMIN_PASS)" ]`.
- `75-checks-stage2.sh`: In `check_all()` when `wifi_only`, check `br-admin` IP `$ADMIN_IP` and `$ADMIN_SSID` broadcasting.
- `80-commands.sh`: `cmd_wifi_name` rejects `$ADMIN_SSID` and `$ADMIN_BASE`; `cmd_update` appends `if wifi_only; then admin_firewall_uci; fi` into `/tmp/piso-setup.uci`.
- `95-owner-tools.sh`: `write_handout()` adds `Admin Wi-Fi (hidden)` row with `${ADMIN_SSID}` and `$(html_esc "$(conf_get ADMIN_PASS)")` when `wifi_only`, and uses `"on the hidden Admin Wi-Fi or a LAN cable"`.
- `97-console.sh`: `console_apply()` runs `{ router_access_uci; if wifi_only; then admin_firewall_uci; fi; }`, commits `uhttpd`, and `if wifi_only; then uci commit firewall 2>/dev/null && /etc/init.d/firewall reload > /dev/null 2>&1; fi`.
- `98-setup-flow.sh`:
  - `choose_names`: reject `$ADMIN_SSID` and `$ADMIN_BASE` as public Wi-Fi name.
  - `choose_passwords`: `if wifi_only; then ask_password ADMIN_PASS "Admin Wi-Fi password (for your phone/laptop to manage the hub)" 8 63 "$ADMIN_PASSWORD"; else ask_password KIOSK_PASS ...; fi`.
  - `review_choices`: show `Admin Wi-Fi` and `Admin Wi-Fi password` when `wifi_only`.
  - `apply_product`: `store_product; if wifi_only; then conf_del KIOSK_PASS; else conf_del ADMIN_PASS; fi`.
  - `admin_pass`: `admin_pass() { wifi_only || return 0; secret ADMIN_PASS 12; }`.
  - `stage1`: `ADMIN_PASS=$(admin_pass)` and pass `"$ADMIN_PASS"` to `uci_batch` (with `<admin password>` on `--dry-run`) and `apply_batch`.

### Step 2: Pure-Rust QR Encoder & Console Admin Wi-Fi (`tools/pisoportal/src/`)
- **Create `tools/pisoportal/src/qr.rs`**:
  - Pure-Rust UTF-8 Byte-mode QR encoder, Versions 1–10, Error Correction M, zero new crates (`Cargo.toml` unchanged).
  - Export `qr_svg(text, module, quiet)` and `wifi_qr_text(ssid, password, hidden)` (escaping `\`, `;`, `,`, `:`, `"` in SSID/password per `WIFI:T:WPA;S:...;P:...;H:true;;`).
  - Add Rust unit tests (`version_bounds`, escaping, SVG structure).
  - Declare `mod qr;` in `tools/pisoportal/src/main.rs`.
- **Edit `tools/pisoportal/src/console.rs`**:
  - Add `html_esc`, `admin_wifi_info(cfg)` (enabled iff `cfg.shop_product == "wifi"`, reading `ADMIN_PASS`, `ADMIN_SSID_NAME`, `KIOSK_SSID_NAME`, `BOX_SSID_NAME` from `cfg.setup_conf` via `conf_value()`; fallback `"PisoAdmin-<suffix>"` or `"PisoAdmin"`).
  - Console URL: `"http://admin.hub/"` when `cfg.console_port == 80 || cfg.console_port == 0`, else `format!("http://admin.hub:{}/", cfg.console_port)`.
  - Wire `GET /api/admin-wifi` (JSON) and `GET|HEAD /admin-wifi/card` (printable HTML with white-backed Wi-Fi QR + Console URL QR, SSID, password, hidden/WPA2 note, `@media print`, strict CSP + `X-Frame-Options: DENY`). Both gated by `c.session_ok(&req)`.
  - Add Rust unit tests.
- **Edit `tools/pisoportal/src/console.html`**:
  - Add `<div id="admw" class="hide"></div>` inside `<section id="t-set">` + minimal `.qr` CSS (keep `< 30,720` B and rendered `/` `< 31,000` B; zero `"Print"`).
- **Edit `tools/pisoportal/src/console-ui.js`**:
  - Bump `PISO_UI_VERSION = 17`.
  - In `refresh()` when `tab === 'set'` and `$('admw')` exists, fetch `/api/admin-wifi` and render the Admin Network card (using `dataset.sig` so Show/Hide state isn't reset on 4s polls): SSID + Copy, Show/Hide + Copy password, Console URL, two `.qr` SVGs, `"Open QR card"` link (`/admin-wifi/card`, `target="_blank"`). Keep `< 34,816` B and zero `"@media print"`.

### Step 3: Setup Wizard (`setup/pisophone_setup.py`) & Manual (`website/manual.html`)
- **Read first if needed**: check `wifi_card_html` / `pisophone-wifi-card` in `setup/pisophone_setup.py` (earlier lines around `1770-4500`) before editing.
- **Edit `setup/pisophone_setup.py`** (stdlib-only Python):
  - Add `ADMIN_SSID = "PisoAdmin"` and `ADMIN_PASSWORD` (`"Admin Wi-Fi password (for your phone/laptop to manage the hub)"`, 8..63, len 12).
  - Update `name_problem`, `check_answers`, `collect_answers`, `answers()`, `review()`, `parse_summary()`.
  - Add `admin_card_html(...)` writing `pisophone-admin-card-<stamp>.html` (mode `0o600`) on `wifi` when `ADMIN_PASSWORD` is present (with QR for `WIFI:T:WPA;S:...;P:...;H:true;;` and QR for `http://admin.hub/`).
  - Update Web UI: add `#adminPw` inside `#pwOwn`, toggle in `paint2()` (`#adminPw` shown iff `S.mode === 'wifi'`), update `settings_problems()`, `Session.reset_unit()`.
- **Sync byte-identical copy**: `cp setup/pisophone_setup.py website/pisophone_setup.py`.
- **Edit `website/manual.html`**:
  - Document Workstream A direct logo/picture upload in `#branding` (no `.brand` pack required for Console uploads).
  - Document Workstream B `PisoWiFi only` hidden `PisoAdmin` Admin Network + QR card.

### Step 4: Release Bump, Bundle Build, Tests, Screenshots & Full Verification
1. Bump `setup/RELEASE` once: `1.5.46` → `1.5.47`.
2. Rebuild setup bundles: `python3 tools/build_piso_setup.py && python3 tools/build_piso_setup.py --check`.
3. Update & add tests:
   - `router/tests/test_setup.py` (update `agree()` `wifi` mocks + failure cases; verify `BOTHCONF`, `remember_wifi_names`, `cmd_update`, `w_ck`, summary/handout/review assertions).
   - `router/tests/test_pisophone_setup.py` & `router/tests/test_setup_web.py` (`ADMIN_PASSWORD`, `admin_card_html`, `#adminPw`, `paint2()`, `parse_summary()`).
   - `tools/pisoportal/tests/test_console.py` & `test_console_layout.py` (including `zxing-cpp` QR decode verification on `/admin-wifi/card`, Console `#admw` SVGs, and `admin_card_html`).
4. Run **all** CI & security checks:
   - `cargo fmt --check`
   - `cargo clippy --locked --release --all-targets -- -D warnings`
   - `cargo build --locked --release` and `cargo build --locked --release --features test-owner-key`
   - `cargo test --locked --release`
   - `sh scripts/check_security_rules.sh`
   - `python3 scripts/check_vendored.py`
   - `python3 scripts/tests/test_check_links.py && python3 scripts/check_links.py`
   - `python3 router/tests/test_footprint.py`
   - `shellcheck` on `setup/src/*.sh` and `setup/piso-setup.sh`
   - All `router/tests/test_*.py`, `tools/pisoportal/tests/test_*.py`, `scripts/tests/test_*.py` (run with `PISOPORTAL=/tmp/piso-target/release/pisoportal PISO_TEST_TARGET_DIR=/tmp/piso-target uv run --no-project --with playwright --with cryptography --with zxing-cpp --with pillow python3 <script.py>` where Playwright/Pillow/zxing-cpp/cryptography are needed).
5. Capture screenshots of Console Settings `#admw` (`SHOP_PRODUCT=wifi`, desktop 1280 light/dark + mobile 390), `/admin-wifi/card`, and `pisophone-admin-card.html`, inspect via `view_file` for visual glitches, and re-verify Workstream A screenshots (`console_branding_tab.png`, `portal_default_technet.png`, `portal_branded_classic.png`, `portal_branded_banner.png`, `portal_branded_poster.png`).
6. Commit final changes cleanly on `feat/direct-brand-and-wifi-admin-net`.
