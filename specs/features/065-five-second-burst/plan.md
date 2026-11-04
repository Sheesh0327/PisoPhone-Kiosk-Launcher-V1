# Plan: feature 065, a 5-second bursting window

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none for the installer's openNDS settings.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `setup/src/30-router-settings.sh` (lines 230 to 260) | `portal_uci`: openNDS checkinterval setting | read by range | 400 |
| `setup/RELEASE` | router release version | 1 | 10 |
| `router/tests/test_setup.py` | tests of generated router settings | read by range | 500 |
| `docs/REAL_WORLD_TESTING.md` | real-world testing sheet (RS73) | read by range | 300 |

## Task groups
- [ ] 1. Test first: update `router/tests/test_setup.py` to expect `checkinterval='5'` and not `checkinterval='15'`; verify it fails red against current generated installer. (files: `router/tests/test_setup.py`; test: `python router/tests/test_setup.py`; red result logged: yes)
- [ ] 2. Update `setup/src/30-router-settings.sh` (`checkinterval='5'`), bump `setup/RELEASE` to `1.5.37`, and regenerate setup files via `python tools/build_piso_setup.py`. (files: `setup/src/30-router-settings.sh`, `setup/RELEASE`, generated files)
- [ ] 3. Run all router and setup test suites to verify green. (tests: `test_setup.py`, `test_sqm.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_monitor.py`, `test_selfupdate.py`, `test_footprint.py`, `check_links.py`, `build_piso_setup.py --check`)
- [ ] 4. Documentation & tracking: write `validation.md`, add `RS73` to `docs/REAL_WORLD_TESTING.md`, update `CHANGELOG.md` and `specs/STATUS.md`.

## Release
Router release 1.5.37. The portal program does not change; the signed router setup files carry the updated openNDS `checkinterval='5'`.
