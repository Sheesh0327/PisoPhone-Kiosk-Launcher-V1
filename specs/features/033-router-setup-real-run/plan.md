# Plan: feature 033, the router installer in a real shop

Context manifest (constitution rule 1; line numbers from `origin/main` at `2591448`, re-check with `grep -n` before editing):

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `setup/install.sh` | one-line installer, `move_lan`, the CPU choice | 80-160 | 1,800 |
| `setup/src/20-preflight.sh` | preflight, WAN clash, `install_packages`, `opkg update` | 1-79 | 1,800 |
| `setup/src/21-local-packages.sh` | packages copied by the computer | 1-55 | 900 |
| `setup/src/50-coin-box.sh` | `pair_box`, `box_station`, wait loop | 1-120 | 2,500 |
| `setup/src/75-checks-stage2.sh` | stage 2 order, state, summary | 40-96 | 900 |
| `setup/src/30-router-settings.sh` | `restart_router_access` | 148-158 | 150 |
| `setup/src/98-setup-flow.sh` | answers read at the start | 1-207 | 3,000 |
| `setup/src/80-commands.sh` | `piso-setup status` | 1-60 | 1,000 |
| `router/piso_monitor.sh` | the monitor's alerts | read ranges only | 1,500 |
| `setup/pisophone_setup.py` | `ANSWER_ORDER`, `setup_command`, the pairing call (one line each) | 96, `setup_command`, `Core.install` | 600 |
| `router/tests/test_install.py`, `test_setup.py`, `test_monitor.py`, `test_pisophone_setup.py`, `test_footprint.py` | tests | read ranges only | 4,000 |
| `docs/ROUTERS.md`, `docs/REAL_WORLD_TESTING.md` | supported list (Q1), RS57 | tail / one section | 800 |

Unresolved ledger entries loaded: none for the router installer. Baseline (2026-10-28): `test_setup` 458, `test_pisophone_setup` 232, `test_setup_web` 115, `test_offline_setup` 98, `test_setup_tools` 78, `test_install` 48, `test_phone_only` 39, `test_setup_phone_web` 31, 0 failures; `check_security_rules.sh` passes.

## Task groups
Test first for each: see it fail, log it in `ledger/negative-constraints.md` (table form), fix in the source under `setup/src/` (never the generated `setup/piso-setup*.sh`), rebuild, one commit per group, no push to `beta`.
Run the router tests with `< /dev/null` in the background and read the last lines only. After each group check `test_footprint.py`.

- [x] 1. SU-06b no opkg stops `install.sh` before anything is done, and preflight says it too (files: `setup/install.sh`, `setup/src/20-preflight.sh`; test: `test_install.py`; red result logged: yes)
- [x] 2. SU-07 a failing `opkg update` with a working ping names the release and `docs/ROUTERS.md` (files: `20-preflight.sh`; test: `test_setup.py` with a failing fake `opkg update`; red result logged: yes)
- [x] 3. SU-05 the modem-range check compares with the netmask and runs again once the WAN is up: alert and `piso-setup status` line (files: `install.sh`, `20-preflight.sh`, `router/piso_monitor.sh`, `80-commands.sh`; tests: WAN 10.0.0.5/24, 10.1.2.3/8, 10.0.30.7/24, none; `test_install.py`, `test_setup.py`, `test_monitor.py`; red result logged: yes)
- [x] 4. SU-11 pair only the board this program just wrote: `BOX_EXPECT_MAC` in the answers line and in `pair_box` (files: `setup/pisophone_setup.py` `ANSWER_ORDER`, `setup_command`, the MAC read from the board's `PISO-BOX` hello after writing; `setup/src/98-setup-flow.sh`, `50-coin-box.sh`; tests: wrong MAC first is not paired, the expected one is, none expected = first one; program sends the MAC; red result logged: yes)
- [x] 5. SU-03 the router bounces its LAN ports after the move, inside the same background job (files: `install.sh` `move_lan`; test: fake `ip`, fake `swconfig`, unknown switch; find out on the 360T6M which one it is, RS57 (a); red result logged: yes; swconfig is not bounced, see the spec)
- [x] 6. SU-08 test first: the restart sends HUP to the session; fix (state and summary first, restart after in the background) only if the test shows the setup is cut, else close `wont-fix` with the RS57 (c) result when the owner has it (files: `75-checks-stage2.sh`, `30-router-settings.sh`; red result logged: yes; the fix is in, RS57 (c) decides whether it stays)
- [ ] 7. Close-out: raise `setup/RELEASE`, rebuild (`python3 tools/build_piso_setup.py`, `--check`), `docs/ROUTERS.md` (Q1), RS57 in `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `setup/README.md` if the owner sees a change; all gates (eight router tests, `cargo test`, `python3 tests/test_flow.py`, ShellCheck, `test_footprint.py`, `check_security_rules.sh`); `validation.md`; rows to `fixed` in `specs/spec_03_findings.json`; `specs/STATUS.md`.

Order: the confirmed rows first (SU-06b, SU-07, SU-05), then the money row SU-11, then SU-03, then SU-08 (uncertain).

## Release
Router: `setup/RELEASE` 1.5.23 -> 1.5.24. Regenerated, never edited: `setup/piso-setup*.sh` (+ `.sha256`), `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`. CI signs and publishes at 0% after the merge; the owner raises it to 100% (`CLAUDE.md` section 5, `docs/RELEASE.md`). No push to `beta`, no pull request and no merge from here: the feature waits for the owner's RS57 bench run (D8).

Generated files (`setup/piso-setup*`, `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`) are rebuilt for the tests after each group but committed once, at the close-out (group 7), so the branch does not carry ten copies of 10 MB; a commit before that has stale generated files.
