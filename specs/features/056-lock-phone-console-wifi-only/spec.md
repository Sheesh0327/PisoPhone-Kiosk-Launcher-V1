# Feature 056: Lock or disable PisoPhone management console for PisoWiFi-only setups

Status: **approved**   Branch: `feat/056-wifi-only-console-lock`   Area: router, portal
Backlog rows: none   Release: router

## Intent
On a PisoWiFi-only router (`SHOP_PRODUCT=wifi`), the shop operates exclusively as a public guest Wi-Fi hotspot and does not operate rental kiosk phones. Previously (since feature 010), the router console still provided a tab labeled "Coin box" (`data-t="ph"`) that embedded the ESP32's web dashboard through the `boxview.rs` reverse proxy (port 8082). If the box runs standard firmware or dual-product firmware, this allows access to phone slot management, QR time card generation, and kiosk phone configurations within a Wi-Fi-only shop.

This feature locks the PisoPhone management console on PisoWiFi-only installations:
1. In the router console navigation sidebar, the phone management tab is displayed with a lock icon and marked disabled when `SHOP_PRODUCT=wifi`.
2. When the tab is clicked (or navigated to directly via URL `#t-ph`), `#t-ph` displays an informational locked card explaining that PisoPhone rental management is disabled on this PisoWiFi-only installation. It does not load the coin box iframe (`#boxf`).
3. The reverse proxy in `boxview.rs` enforces that when `shop_product == "wifi"`, any attempt to access port 8082 returns `403 Forbidden`.
4. For standard dual deployments (`SHOP_PRODUCT=both`), the "Rental phones" tab and proxy remain fully operational and unchanged.

## Invariants touched
- **Money and time (rule 1)**: Not touched. Payment recording, coin counting, roll files, vouchers, and speed caps are unchanged.
- **Only owner-signed software (rule 2)**: Not touched. Same binary signing and distribution channels.

## Constraints
- Router change: router code modifications (`tools/pisoportal/src/`, `setup/`, `router/`) must go through `beta` before release per `GEMINI.md` section 4.
- Zero breaking changes or behavioral regressions for `SHOP_PRODUCT=both` setups.
- Size budgets: `pisoportal` binary <= 1,200 KB; installer package <= 1,700 KB.
- Generated files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`) are never edited by hand; regenerated via CI/build tools.

## Success criteria
1. **Baseline**: Cargo test, python flow test, and security rules pass prior to changes.
2. **Console Navigation on PisoWiFi (`wifi`)**: When `shop_product == "wifi"`, the router console sidebar (`<nav id="tabs">`) shows the management tab with a lock icon, class `dis`, `aria-disabled="true"`, and label "Rental phones (Locked)".
3. **Console Section on PisoWiFi (`wifi`)**: When screen `ph` is displayed (via click or direct `#t-ph` navigation), the section displays a styled locked card:
   *"PisoPhone rental management is disabled on this PisoWiFi-only installation. To manage rental phones, configure the shop product as 'both' during setup."*
   It does not create or load the iframe (`#boxf`).
4. **Proxy Lockdown (`boxview.rs`)**: When `shop_product == "wifi"`, any HTTP request to the `boxview` reverse proxy (port 8082) receives `403 Forbidden` (`Content-Type: text/plain; charset=utf-8\r\n\r\nPisoPhone management is disabled on PisoWiFi-only routers.\r\n`).
5. **PisoHub (`both`) Unaffected**: When `shop_product == "both"` (or unset):
   - Sidebar contains standard `<button data-t="ph">` labeled "Rental phones" with the standard phone icon.
   - Section `#t-ph` embeds the coin box dashboard inside `<iframe id="boxf">`.
   - `boxview.rs` proxies requests normally for authenticated console sessions.
6. **Automated Verification**:
   - `cargo test` in `tools/pisoportal` passes (including new unit tests for `wifi` console HTML and `boxview` rejection).
   - `python3 tests/test_flow.py` and `python3 tests/test_console.py` pass.
   - `bash scripts/check_security_rules.sh` passes.

## Out of scope
- Modifying ESP32 firmware binaries or flashing profiles.
- Removing coin pulse counting, coin revenue reconciliation, or sales tracking from the PisoWiFi console overview.

## Owner decisions (resolved 2026-11-09)
1. **Sidebar representation for `wifi`**: Display the tab as disabled with a lock icon (`Rental phones (Locked)`), showing an informational card when clicked.
2. **Proxy behavior**: Return `403 Forbidden` on port 8082 for all requests when `shop_product == "wifi"`.
3. **Direct URL Hash (#t-ph)**: Display the clean locked banner stating that rental phone management is disabled on PisoWiFi-only installations.
