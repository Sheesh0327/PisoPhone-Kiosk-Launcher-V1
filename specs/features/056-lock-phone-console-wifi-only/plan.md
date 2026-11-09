# Plan: Feature 056 (Lock or disable PisoPhone management console for PisoWiFi-only setups)

Context manifest (constitution rule 1):
`tools/pisoportal/src/console.rs` | Console HTML generation and tab label logic | 1033 lines | ~3,500 tokens
`tools/pisoportal/src/console.html` | Console layout, sidebar tabs, and iframe/lock card section | 114 lines | ~800 tokens
`tools/pisoportal/src/console-ui.js` | Client-side tab switching and iframe loader | 165 lines | ~950 tokens
`tools/pisoportal/src/boxview.rs` | Reverse proxy for coin box dashboard on port 8082 | 326 lines | ~1,200 tokens
`tools/pisoportal/tests/test_console.py` | Python console integration tests | 240 lines | ~1,100 tokens

Unresolved ledger entries loaded: none.

---

## Task groups

- [x] 1. **Console HTML & navigation rendering (`tools/pisoportal/src/console.rs`, `console.html`)**
  - Unit test in `test_console_html.py` failed first (red logged in ledger), then passed.
  - Rust unit tests in `console.rs` updated for locked button and no-iframe section.
  - Replaced `@PHTAB_BTN@` and `@PH_SECTION@` in `console.html`.

- [x] 2. **Reverse Proxy lockdown (`tools/pisoportal/src/boxview.rs`)**
  - In `boxview.rs` `serve_one`: checks `cfg.shop_product == "wifi"`, returning `403 Forbidden` (`PisoPhone management is disabled on PisoWiFi-only routers.\r\n`).
  - Unit test added in `boxview.rs`.

- [x] 3. **Console UI client handling (`tools/pisoportal/src/console-ui.js`)**
  - Updated `ph()` in `console-ui.js` with `if (!f) return;` so when `#boxf` is not in the DOM on `wifi`, no null dereferences or background requests occur.

- [x] 4. **Integration & Layout Tests (`tools/pisoportal/tests/`)**
  - Updated `test_console_frame.py` to assert "Rental phones (Locked)", verify locked notice card, and confirm no `#boxf` iframe on `wifi`.
  - Executed `tools/pisoportal/tests/test_console_html.py` (PASS).
  - Raised `setup/RELEASE` to 1.5.35 and regenerated setup files via `python tools/build_piso_setup.py` (`--check` passes).

- [x] 5. **Validation & Evidence Collection**
  - Fill `specs/features/056-lock-phone-console-wifi-only/validation.md` (filled).
  - Run security rules: `bash scripts/check_security_rules.sh` (passed).
  - Update `specs/STATUS.md` (done).

---

## Release
- Area: Router (`tools/pisoportal`).
- Raises: `setup/RELEASE` (1.5.34 -> 1.5.35).
- Follows router release process via `beta` branch per `GEMINI.md` section 4.
