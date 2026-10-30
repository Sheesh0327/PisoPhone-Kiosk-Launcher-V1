# Validation: feature 053, the console layout

Run on 2026-10-30 on a computer (Chromium 1243 under Playwright, the real portal program built from this branch, a fake coin box and a fake openNDS). Nothing was run on a real router or phone.
Pictures: `evidence/before/` (48, group 0) and `evidence/after/` (52: the same, plus the phone's menu panel), 1280, 768 and 390 px, light and dark.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | At 1280 px a left sidebar with the three groups; every screen one click; current screen marked | `test_console_layout.py`: group headings, order of the seven items, one section shown and one item with `aria-current=page` after each click, the top bar names the screen | pass | `247 checks, 0 failures` (the 1280 px block) |
| 2 | At 390 px no sideways scrolling on any screen (sign-in included); the menu opens and closes by choice, outside tap and Esc; every button 40 px or more | same test at 390 and 768 px: `scrollWidth - clientWidth <= 0` on sign-in and all seven screens (Sales at 7, 30 and 90 days, Branding too); the panel opened, closed three ways; menu items, Disconnect, Cancel, Remove, Save, Publish, CSV and the period list measured | pass | same run; the red run (11 failures in group 3, 8 in group 1, 2 in group 2) is in `ledger/negative-constraints.md` |
| 3 | Keyboard: sensible Tab order, visible focus, Esc closes and returns focus to the menu button | same test: Tab from the menu button does not walk into the closed panel; opening puts focus on the current item; Esc returns focus to `#mn` (and to the Disconnect button after the dialog) | pass for order and Esc; **visible focus not measured** (the page's existing `:focus-visible` outline rule is unchanged and applies to the new items) | same run |
| 4 | Contrast 4.5 : 1 or better in light and dark, measured by a script over the real page | same test, `CONTRAST` script over every text element: sign-in, seven screens, phone panel, light and dark, 1280 and 390, and under the mouse on table rows | pass | same run; it found three real faults on the way (bar label on the bar colour 2.7, muted grey on a hovered row 4.4, a legend square excluded as a picture) |
| 5 | Earlier console tests pass with only selector edits | `test_console` 110, `test_console_frame` 7, `test_console_prices` 13, `test_money_panel` 69, `test_versions_panel` 13, `test_console_loader` 8, `test_console_brand` 75, `cargo test` 193, `scripts/tests/test_console_ui.py` 13: all 0 failures | pass | edits to older tests: one phone check in `test_console_brand.py` opens the menu first; the page-size guard in `test_console.py` 24,000 to 30,000; one Rust assertion in `console.rs` names the tab button with its icon; no assertion weakened |
| 6 | The new page runs with the signed v11 script; the v12 script runs on the 1.5.32 page | layout test serves the v11 script (`tests/golden/console_ui_v11.js`) to the new page and the 1.5.32 page (`tests/golden/console_1_5_32.html`) to the v12 script: every screen opens, sidebar mark and title follow (old script), tiles and money render (new script), no script error | pass | same run (the website's signed copy is v11, not v10 as the spec first said) |
| 7 | Sign-in and session behaviour unchanged | `test_console.py` (wrong password, no cookie, wrong origin, missing `X-Piso`, lock-out after five) | pass | `110 checks, 0 failures` |
| 8 | No request leaves the router except the signed-script check | layout test: every `https` host except `pisophone.pages.dev/console/` aborted, all seven screens opened, requests listed | pass | same run |
| 9 | Size: page-size guard and program budget hold, numbers recorded | page 21,832 to 28,726 bytes (guard 30,000); script 26,625 to 28,375; native x86_64 build 966,784 to 975,680 bytes (+8,896, measured on a clean build of `main` and of this branch); `router/tests/test_footprint.py` now also watches both files (12 budgets, 0 failures) | pass on a computer; **mipsel and installer sizes are estimates** | mipsel program 1,117,028 B (1,091 KB) + 8,896 = about 1,125,900 B (about 1,099 KB of 1,200 KB); installer 1,686,330 B (1,647 KB) + about 11,900 (base64) = about 1,698,200 B (about 1,658 KB of 1,700 KB). The real numbers come from CI's build on `beta` |
| 10 | Before and after pictures at 1280, 768 and 390 px, light and dark, shown to the owner | `evidence/before/`, `evidence/after/` | pass | 48 + 52 pictures; I looked at the Overview, Customers, Sales, Vouchers, Settings, Branding, sign-in and the phone menu |
| 11 | Real router and real phone | hardware check **RS64** in `docs/REAL_WORLD_TESTING.md` | **not run** | owner's |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: `router/tests/test_monitor.py` 60, `test_setup` 480, `test_pisophone_setup` 259, `test_setup_web` 165, `test_setup_tools` 87 (all 0 failures); `python3 tests/test_flow.py` in `tools/pisoportal` exit 0, also against a build of this branch; `python3 tools/build_piso_setup.py --check` up to date (no generated file changed)
- [x] `bash scripts/check_security_rules.sh`: all passed; `python3 scripts/check_links.py`: 0 dead links
- [x] Python: `ruff check` on the new test is clean; the older touched files have the same findings as on `main` (`test_console.py` 29); `mypy --ignore-missing-imports`: the new test is clean, the older two have the same 9 findings as on `main`. `pytest` is not installed here and these tests are scripts that print `N checks, M failures`, not pytest files
- [x] Generated files: none touched (`tools/pisoportal/bin/*` and the setup files are rebuilt by CI on `beta`)

## Beta rebuild (2026-10-30, after the layout was pushed to `beta`)
- CI built all five router programs on `beta` (`router-program.yml`), then "ci: rebuilt the setup files around the router programs" (`3b98f5b`).
- The mipsel program inside the committed `setup/piso-setup.sh` was decoded and compared with `tools/pisoportal/bin/pisoportal-mipsel`: identical (1,117,028 bytes, sha256 `616e37c9e22b4d4b...`). It contains the new console (`id="side"`, `id="mn"`, `PISO_UI_VERSION = 12`, no version 11 string, the dialog margin rule).
- Real sizes replace the estimates in criterion 9: **mipsel program 1,117,028 bytes (1,091 KB), unchanged** (the binary rounds up to a page boundary, so the +8.9 KB of text did not change its size; x86_64 grew from 1,110,896 to 1,119,088); **installers**: mipsel 1,686,330 bytes (1,647 KB), x86_64 1,689,115 (1,650 KB), both under the 1,700 KB budget.
- Code quality on `beta`: run 706 failed `cargo fmt --check` (a test I had not formatted); run 707 failed one layout check on the CI machine's wider font (the confirmation dialog took the whole phone width); both fixed and logged in the ledger. The final run is on the head that is merged.

## Not tested
- **Everything on a real router, phone and browser** (RS64). The pictures come from desktop Chromium; iPhone Safari and Android Chrome have not seen the page.
- **Visible keyboard focus** on the new items (not measured, see criterion 3).
- **The mipsel program and the installer sizes** (estimates above). CI's build on `beta` is the first real measure; if the installer lands above 1,700 KB `test_footprint` will fail there.
- **The Overview money panel and update line** were left as they are (plain boxes); their text and ids are tested by the money and versions panel tests.
- No release was made: `setup/RELEASE` is not raised, nothing went through `beta`.

## Money and time
Not touched. No payment, credit, expiry, deduction, ledger or payment-queue code changed; only `console.html`, `console-ui.js` (display and menu), tests, docs. The money panel's numbers are read from the same calls and are tested unchanged (`test_money_panel` 69 checks, `test_console` sales and overview checks).
