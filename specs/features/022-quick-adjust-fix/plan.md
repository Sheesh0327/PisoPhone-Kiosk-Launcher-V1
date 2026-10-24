# Plan: quick add / subtract time through the router console (feature 022)

Context manifest (constitution rule 1). Unresolved ledger entries loaded: none relevant.

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/boxview.rs` | the box view: `frame_policy`, `same_site_post`, the refusal | 35 to 75, 95 to 130 | 1,100 |
| `esp32_firmware/include/WebDashboardTemplate.h` | the form (plain post) | 86 to 110 | 400 |
| `esp32_firmware/src/WebServerAuth.cpp` | the box's own guard (unchanged) | 369 to 395 | 300 |
| `tools/pisoportal/tests/test_console.py` | where console/box view tests live | `grep -n box` ranges | tbd |

## Task groups
Test first, red result logged in `ledger/negative-constraints.md`.
- [x] 1. **Red test.** Through the box view: a post with `Origin: null` (what the browser sends under `no-referrer`) is refused; the page
  headers carry `no-referrer`. Keep or add the attack cases (other Origin, `null` from a foreign page, `https://` Origin, no session).
  (red result logged: no)
- [x] 2. **The fix.** `frame_policy` sends `Referrer-Policy: same-origin`; `same_site_post` unchanged (strict). Confirm the proxy still rewrites
  Origin and Referer to the box's own address. (files: `tools/pisoportal/src/boxview.rs`, unit tests in the same file)
- [x] 3. **Green plus the attack cases**, and the whole router suite: `cargo test`, clippy, rustfmt, `test_flow.py`, `test_console.py`,
  `test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, footprint.
- [x] 4. Raise `setup/RELEASE` to 1.5.20, regenerate with `tools/build_piso_setup.py`, `CHANGELOG.md` (router 1.5.20), RS47, `validation.md`
  with the numbers, STATUS.

## Release
Router 1.5.20 (CI builds the programs on `beta`/`main`; `publish-router.yml` signs it at 0%). The firmware and the app are not touched.
