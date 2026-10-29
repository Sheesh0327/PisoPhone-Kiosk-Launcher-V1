# Plan: feature 045, the setup program in a real shop

Context manifest (constitution rule 1; line numbers from `origin/main` at `2591448`, re-check with `grep -n` before editing):

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `setup/pisophone_setup.py` | constants, `USER_AGENT` | 78-107 | 500 |
| `setup/pisophone_setup.py` | `find_router`, ssh, `use_saved_password`, `save_router_password` | 1723-1916, 1851-1870 | 2,800 |
| `setup/pisophone_setup.py` | `parse_router_feeds`, `build_offline_bundle`, `install()` | 1934-1975, 2113-2190, 2335-2440 | 4,500 |
| `setup/pisophone_setup.py` | `fetch_image`, firmware list, cache dir | 2814-2870, 3247-3252 | 1,500 |
| `setup/pisophone_setup.py` | `Core`, `Session` (`log`, `plog`, search, retry, install, phones) | 4416-5010 | 7,500 |
| `setup/pisophone_setup.py` | `install_service` | 5503-5534 | 500 |
| `router/tests/test_setup_web.py` | fake `Core` (line 202), page tests | read ranges only | 2,000 |
| `router/tests/test_pisophone_setup.py` | unit tests of `find_router`, `install`, `fetch_image` | read ranges only | 2,000 |
| `setup/README.md` | "Which network?" | 1-115 | 2,000 |
| `docs/REAL_WORLD_TESTING.md` | RS60 (append) | tail | 300 |

Unresolved ledger entries loaded: none for the setup program (the last unresolved-looking rows are other areas). Baseline (2026-10-28): `test_setup` 458,
`test_pisophone_setup` 232, `test_setup_web` 115, `test_offline_setup` 98, `test_setup_tools` 78, `test_install` 48, `test_phone_only` 39, `test_setup_phone_web` 31, 0 failures.

## Task groups
Test first for each: see it fail, log it in `ledger/negative-constraints.md` (table form), fix in the source, one commit per group, no push.
Run the router tests in the background and read the last lines only.

- [x] 1. SU-18 the setup log file (`Session.log`, `plog`, a start-of-run address/route dump, mask the unit's passwords, 0600, link from banner and last page; test: fake-core run writes the file, mode, no password; red result logged: yes)
- [x] 2. SU-09 + SU-10 *Try again* uses the saved router password; a refused login gets its own message (`Core.__init__` ~4426, `do_search`/`saved_login`, `install()` ~2436; test: the fake `Core` models a router whose password changes after the first run, and a refusing fake ssh; red result logged: yes)
- [x] 3. SU-06a refuse an apk / no-`opkg` router before any change (`parse_router_feeds`, `install()` `fall_back`; test: `@@APK yes` stops with nothing changed; red result logged: yes)
- [x] 4. SU-17 + SU-21 `USER_AGENT` on the coin box list and image; `install_service` copies the program to `/var/lib/pisophone-setup/` (tests: the request carries it; `install_service` with a temp state dir; red result logged: yes)
- [x] 5. SU-04 find the router at the cable's DHCP gateway too (`find_router` takes an injectable lease/gateway reader for Windows, Linux, macOS; test: 192.168.8.1 found, named and moved to 10.0.0.1; red result logged: yes)
- [x] 6. SU-01 detect two 192.168.1.x networks and stop with the hotspot advice; README "Which network?" (test: fake address tables for the three systems; red result logged: yes)
- [x] 7. SU-02 download early at Start, then the website check and the unplug-the-cable prompt before the router packages (test: page asks when the opener fails, continues after; red result logged: yes)
- [x] 8. SU-15 the Phones step waits for the router to reach the app's address (test: page test with a router that has no internet; red result logged: yes)
- [x] 9. Close-out: add RS60 to `docs/REAL_WORLD_TESTING.md` (unticked), `CHANGELOG.md`, `setup/README.md`; rebuild with `python3 tools/build_piso_setup.py`, `--check`, `git status` shows no router file changed; run all gates; fill `validation.md`; set rows to `fixed` in `specs/spec_04_findings.json`; update `specs/STATUS.md`.
- [ ] 10. (only if the owner's RS60 (b) fails) SU-14 first-page warning for Windows 10 OpenSSH 8.1 with the fix.

Order: SU-18 first (the saved log helps every bench run), then the confirmed rows, then the reported ones (SU-01, SU-02, SU-15), as in spec 04.

## Release
None for routers, firmware or the app. The website's copy (`website/pisophone_setup.py` and the files `tools/build_piso_setup.py` writes) is regenerated, never edited by hand;
`--check` must pass. If `git status` shows a changed router file, stop and ask the owner (`setup/RELEASE` would need raising). No push to `beta`, no pull request, no merge: the feature waits for the owner's RS60 bench run (D8).
