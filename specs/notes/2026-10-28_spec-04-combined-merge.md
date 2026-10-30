# Combined merge of features 045, 046 and 047 (spec 04), prepared 2026-10-28

Branch `merge/spec-03-032-033-034` (pushed), built in a separate worktree from `origin/main` at `2591448` (router 1.5.23, firmware 3.12.4) by merging, in order,
`feat/045-setup-program-real-run`, `feat/046-router-setup-real-run`, `feat/047-box-wifi-retry` with `--no-ff`. **Nothing is merged into `main` or `beta`, no pull request is
open, and nothing is released.** Per the owner's rule D8 each feature waits for its bench run (RS60, RS61, RS62) before this goes to `main`.

## What is in it
- 045: the setup program (website copy `website/pisophone_setup.py`). 046: router release **1.5.30** (`setup/RELEASE`, generated `setup/piso-setup*.sh`, `website/setup/*`, `website/install.sh`). 047: firmware **3.12.5** (`FirmwareVersion.h`).
- Rows: SU-18, 09, 10, 06a, 01, 02, 04, 15, 17, 21, 03, 11, 06b, 05, 07, 08, 16 are `fixed` in `specs/spec_04_findings.json`; **SU-14 stays `open`** (decided by RS60 (b) on a Windows 10 computer).

## Conflicts and how they were resolved
Each branch started from `main` plus the epic commit, so they met in the same places:
- `setup/pisophone_setup.py`: 045's `Core.router_reaches_site` and 046's `Core.board_mac` side by side (kept both). 047's changes merged without a conflict.
- `router/tests/test_pisophone_setup.py`: 045's SU-18 test block and 046's SU-11 block (kept both). `router/tests/test_setup_web.py`: both `FakeCore` additions kept; 046's page test was rewritten to use 045's `walk_to_install` helper.
- `CHANGELOG.md`, `docs/REAL_WORLD_TESTING.md` (RS60, RS61, RS62 all kept), `specs/STATUS.md` (three rows), `ledger/negative-constraints.md` (all rows): kept both sides.
- `setup/README.md`: 047's new "could not join the Wi-Fi" row above 045's "Anything else" row (045's text with the setup-log sentence wins).
- `specs/spec_04_findings.json`: merged by id (a row is `fixed` if any side says so).
- Generated files (`website/pisophone_setup.py`, `setup/piso-setup*.sh`, `website/setup/*`, `website/install.sh`): never merged by hand; rebuilt with `python3 tools/build_piso_setup.py` after each merge (`--check` passes).

## Checks on the combined tree (2026-10-28, computer only)
- Router tests (`< /dev/null`): `test_setup` 480, `test_pisophone_setup` 281, `test_setup_web` 144, `test_offline_setup` 100, `test_setup_tools` 78, `test_install` 54, `test_phone_only` 51, `test_setup_phone_web` 34, `test_monitor` 40, `test_boxupdate` 28, `test_selfupdate` 60 checks, 0 failures; `test_footprint` 10 budgets, 0 failures (installed command 148 of 150 KB).
- `bash scripts/check_security_rules.sh`: all passed. `python3 tools/build_piso_setup.py --check`: up to date.
- Firmware host tests: 38 of 46 built and passed; 8 need mbedtls (not installed here) and were not built. **The ESP32 build was not made (no PlatformIO).**
- `python3 scripts/verify_release.py`: **0 FAIL, 1 WARN, 12 PASS**. The WARN is expected: `router.json` still says 1.5.23 while `setup/RELEASE` is 1.5.30; `publish-router.yml` signs the newer one after the merge.
- `ruff check setup/pisophone_setup.py`: 51 findings against 46 on `main` (5 more: 4 `BLE001` `except Exception` and 1 `S110` `try/except/pass`, the same patterns the file already has 25 and 5 of; my scratch copy of `main` showed 47 because of a non-executable-file artifact); `mypy`: 8 errors before and after.
- Not run: `cargo test` and `test_flow.py` (`tools/pisoportal` is identical to `main`), ShellCheck and BusyBox `ash` (not installed), anything on a real router, board, phone or computer.

## To do before this goes to `main` (CLAUDE.md section 5)
1. The owner runs **RS60, RS61 and RS62** (with RS17, RS20, RS21, RS36) and the results are written into the three `validation.md` files; SU-08 is closed `wont-fix` if RS61 (c) shows the session is never cut, and SU-14 is decided by RS60 (b).
2. **Code quality must be green on the exact commit**: it runs only on pull requests into `beta` or `main` and on pushes to them, so a pull request from this branch is needed (the owner asks for it). It runs ShellCheck, BusyBox `ash`, the ESP32 build and the 8 mbedtls host tests, none of which ran here.
3. Merge into `main` in a separate worktree; take `main`'s version of `website/update`, `website/flash` (this change alters the router, so the regenerated router files stay); `python3 scripts/verify_release.py` must say 0 FAIL; push. Then CI commits "router release 1.5.30 signed and published (0%)", and `firmware-images.yml` builds and signs firmware 3.12.5 after the owner approves in `production-signing` (`docs/RELEASE.md`).
4. Raising the router to 100% is a separate step (`publish-router.yml` on `main` with rollout 100, then `verify_release.py` again). Update `specs/RELEASE_STATE.md` after each release.
