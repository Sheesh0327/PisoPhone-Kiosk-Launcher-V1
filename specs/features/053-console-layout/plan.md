# Plan: feature 053, the console layout

Context manifest (constitution rule 1): unresolved ledger entries loaded: none relevant to the console.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/console.html` | the page: CSS, sign-in, seven screens, loader | 147 (21.8 KB, long lines) | read by line range only |
| `tools/pisoportal/src/console-ui.js` | the script: tabs, refresh, forms (v11) | 159 (26.6 KB) | read by line range only |
| `tools/pisoportal/src/console.rs` | serves the page, `@PHTAB@`, `@OWNER_KEY@` | 1027 | grep only |
| `tools/pisoportal/tests/test_console*.py`, `test_money_panel.py`, `test_versions_panel.py` | name `#tabs`, `data-t` | 328 (test_console) | selectors only |
| `scripts/sign_console_ui.py`, `scripts/tests/test_console_ui.py` | the signed script and its tests | small | unchanged |

## Task groups
Small, ordered, each finished and checkable on its own. Test first. The owner says "go" for each group.

- [ ] 0. Record today's console: screenshots of the seven screens at 1280, 768 and 390 px, light and dark, into `evidence/before/`; list every selector the tests use for the tab bar (files: none changed; test: none; red result logged: n/a)
- [ ] 1. The shell: tokens, sidebar with groups, top bar, phone panel, `aria-current`, Esc and focus return; script v12 handles the panel (files: `console.html`, `console-ui.js`; test: new `test_console_layout.py` (widths, groups, panel, keyboard, no sideways scroll); red result logged: yes)
- [ ] 2. Overview, Customers and Sales screens: card title rows, tiles with icons, tables with sticky header, empty states, 40 px buttons (files: `console.html`; test: layout test per screen, existing money panel and versions panel tests; red result logged: yes)
- [ ] 3. Vouchers, Settings (with Prices), Rental phones frame and Branding screen fitted into the new shell width (files: `console.html`; test: `test_console_brand` (390 px, DejaVu), `test_console_prices`, `test_console_frame`; red result logged: yes)
- [ ] 4. Sign-in card, dialogs, footer; contrast script for light and dark; no-outside-request test; compatibility tests (new page + signed v10 script, v12 script + 1.5.32 page) (files: `console.html`, tests; red result logged: yes)
- [ ] 5. Selector edits in the older console tests only where the old tab bar was named; page-size guard and program size measured and recorded (files: tests; red result logged: n/a)
- [ ] 6. Validate (`/sdd-validate`): fill `validation.md`, screenshots after, owner's manual page, `CHANGELOG.md`, `docs/REAL_WORLD_TESTING.md` RS64, `specs/STATUS.md`

## Release
Router release: raise `setup/RELEASE` (1.5.33), `console-ui.js` version 12 (built in). A router change goes through `beta`: bring `main` into `beta`, push, wait for CI's two commits, merge to `main`, decode the program inside a signed file and check it has the new page (`CLAUDE.md` section 5). Signing the new script for the website feed is optional and the owner's step (`scripts/sign_console_ui.py`). The generated programs and setup files are never edited by hand. Signed at 0%; the owner raises it after RS64.
