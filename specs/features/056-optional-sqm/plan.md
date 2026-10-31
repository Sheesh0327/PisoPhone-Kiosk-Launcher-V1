# Plan: 056 optional smooth-line packages (SQM) and the console setting

**Depends on feature 057** (`specs/features/057-package-manager-layer/`): groups 1 to 3 call the layer's functions, never `opkg` or `apk` by name. Build and validate 057 first.

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none for router/setup (ledger read: 86 lines, all resolved or older).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| setup/src/20-preflight.sh | `install_packages` (opennds required; add the optional step after it, lines 73 to 86) | 87 | 1.9k |
| setup/src/15-flash.sh | free-flash check (2048 KB level, lines 55 to 65) | 67 | 1.2k |
| setup/src/60-summary.sh | end-of-setup summary (list skipped/installed) | 39 | 0.9k |
| setup/src/80-commands.sh | `piso-setup` commands: add `sqm` commands; extend `status` | 264 | 5k |
| setup/src/30-router-settings.sh | openNDS/firewall uci (unchanged, test only) | 269 | 5k |
| tools/pisoportal/src/console.rs | `/api/settings` (lines 538 to 560, 735 to 780): add the SQM fields | 1033 | read ranges only |
| tools/pisoportal/src/console.html, console-ui.js | Settings page card | 183 + 163 | read ranges only |
| tools/pisoportal/src/config.rs | `override_set` keys SQM_DOWN_MBPS, SQM_UP_MBPS, SQM_PERCENT, SQM_ON | 302 | read ranges only |
| router/tests/test_setup.py | the installer checks (fake opkg), the per-plan caps checks | 1631 | grep ranges only |
| docs/ROUTERS.md, docs/REAL_WORLD_TESTING.md, setup/README.md, CHANGELOG.md | documents | 67 / (grep) | read ranges only |

Never opened: `setup/piso-setup*.sh`, `website/*`, `tools/pisoportal/bin/*` (generated; `CLAUDE.md` section 4).

## Task groups
Small, ordered, each finished and checkable on its own. Test first; a red result is logged in `ledger/negative-constraints.md`.

- [ ] 0. **Bench facts first (no code).** Also: from the router, `curl -sS -o /dev/null -w '%{http_code} %{speed_download}\\n' 'https://speed.cloudflare.com/__down?bytes=10000000'` and a `__up` POST, to confirm the endpoints answer and what they accept (the build session could not reach them). On the 360T6M: `opkg list-installed | grep -E 'sqm|cake|ifb|tc-|irqbalance'`, `tc qdisc show`, which opkg names exist
  (`opkg info sqm-scripts kmod-sched-cake kmod-ifb tc-full tc-tiny irqbalance`, or `apk search -e`; and `uname -r`, `ls /lib/modules/$(uname -r) | grep -E 'sch_cake|ifb'`, `zcat /proc/config.gz | grep -E 'NET_SCH_CAKE|IFB'`), the flash each costs, and whether openNDS applies its caps with `tc` on an `ifb`
  (read `ndsctl status` and `/usr/lib/opennds`). Write the facts into `specs/features/056-optional-sqm/bench-facts.md`. **Stop for the owner if the openNDS
  shaper and SQM would share a device** (SQ-12). (files: none in the repo; test: n/a; red result: n/a)
- [x] 1. **Optional-package step in the installer** (`setup/src/13-optional.sh`, tests in `router/tests/test_optional.py`). `install_optional_packages` after `install_packages`: flash guard (2048 KB), `opkg info` scan, priority order,
  all-or-nothing core, status file, log lines. openNDS loop untouched. (files: `setup/src/20-preflight.sh`, `setup/src/15-flash.sh`; test: `router/tests/test_setup.py` with a fake
  `opkg` and a fake `apk`: feed with everything / only openNDS / no cake / ifb fails / flash 1500 KB / opennds fails / module already in the kernel / dry run refuses (kernel mismatch) / no package manager with openNDS present; red result logged: yes)
- [x] 2. **Report it.** Summary lines and `piso-setup status` show each optional package as installed / skipped (reason). (files: `setup/src/60-summary.sh`,
  `setup/src/80-commands.sh`; test: `test_setup.py`; red: yes)
- [x] 3. **`piso-setup sqm` commands (root, on the router)** (`setup/src/14-sqm.sh`, tests in `router/tests/test_sqm.py`; the shell side refuses on customers online; the coin-window check is the portal's, in group 5c): `sqm install` (the button's job: runs group 1's step; refuses with `BUSY` while a coin window is
  open or a payment is recording, reusing the checks the update uses), `sqm apply` (reads the saved speeds, finds the WAN device with
  `ifstatus wan | jsonfilter -e '@.l3_device'`, writes `/etc/config/sqm` at the chosen margin, enables and restarts sqm, or removes the shaping when off),
  `sqm status` (writes the status file the portal reads). (files: new `setup/src/` piece or `80-commands.sh`; test: `router/tests/test_setup_tools.py`
  with a fake uci/ifstatus: 95% of 50/20 gives 47500/19000 kbit/s, PPPoE device, off removes; red: yes)
- [x] 4. **Portal settings.** (done on a computer 2026-10-31: `tools/pisoportal/src/sqmconsole.rs`, `tests/test_console_sqm.py` 46 checks, 3 unit tests; the hand-over is `piso-setup sqm apply` through `PISO_SETUP_BIN`; the card in the console is group 5.) `GET/POST /api/settings` carry `sqm` (available, reason, on, down, up, percent, qdisc, limit_down, limit_up). Validation as in criterion 5, saved by `override_set`,
  then the portal hands over to `piso-setup sqm apply` (not in the request thread). A bad value saves nothing. (files: `console.rs`, `config.rs`; test: `cargo test` and
  `tests/test_flow.py`; red: yes)
- [x] 5. **Console card.** (done on a computer 2026-10-31, with Install now; `POST /api/sqm/install`; `tests/test_console_sqm_card.py` 29 checks in Chromium; console script version 13; budgets raised, see the ledger) The Settings page card: fields, live "will limit to" line, switch, greyed-out reason + Install now where it can work; English text in
  plain words. (files: `console.html`, `console-ui.js`; test: the console tests that exist for the Settings page, a phone-width and light/dark check; red: yes)
- [x] 5b. **The speed test (root script).** (done on a computer 2026-10-31: `setup/src/14-sqm.sh`, `router/tests/test_sqm_test.py`, 30 checks; the command is `piso-setup sqm test`, as the other `sqm` commands; the host list is `SQM_TEST_HOSTS`, one entry so far, Cloudflare, unverified; the cake and `-T -` upload behaviour of real `curl` is unverified until group 0; the footprint of `setup/piso-setup.sh.in` is now 173 of 175 KB.) `piso-setup sqm test`: refuses with `BUSY` when a customer is online or a coin window is open; stops the SQM service if it is running and always restarts it (trap, plus a 90 second limit);
  runs the parallel `curl` downloads and uploads against the host list with a data cap; reads CPU use from `/proc/stat`; writes `/tmp/piso-speedtest.json` (state, down, up, host, cpu_limited, time). (files: new piece in `setup/src/`;
  test: `router/tests/test_setup_tools.py` or a new test file with a fake `curl`, fake `ndsctl`, fake `sqm` init, a fake `/proc/stat`; red result logged: yes)
- [x] 5c. **Portal.** (done on a computer 2026-10-31: `sqmconsole.rs`, `tests/test_console_speedtest.py` 34 checks, 5 unit tests; the file is `SPEEDTEST_FILE`, default `/tmp/piso-speedtest.json`; the gap is `SPEEDTEST_GAP_SECONDS`, default 60; the card and the button are groups 5 and 5d.) `POST /api/speedtest` starts it (refused within a minute of the last one, or while busy), `GET /api/settings` carries the speed test status (state, results, age, why it is greyed out). (files: `console.rs`;
  test: `cargo test`, `tests/test_flow.py`; red: yes)
- [x] 5d. **Console.** (done together with group 5, same card and test) The **Test my line** button, the progress text, the prefilled boxes with "measured / SQM will use", the CPU warning, the greyed-out reason. (files: `console.html`, `console-ui.js`; test: the console tests; red: yes)
- [x] 6. **Nothing else moved.** (done on a computer 2026-10-31 in `router/tests/test_sqm.py`, 37 checks: every uci change of the sqm commands names `sqm` only, and the two source pieces have no uci line for openNDS, firewall, network, DHCP, Wi-Fi or the speed caps. A first try in `test_setup.py` comparing two dry runs was dropped: the dry run never reaches the optional step, so it proved nothing. The no-money-file check is evidence in `validation.md`, not a test, because it needs git history.) Add the checks of criteria 7 and 8 (openNDS/firewall uci identical, flow offloading 0; grep that no money-path file is in the diff). (files: `test_setup.py`; red: not applicable, guard test)
- [x] 7. **Documents.** (done 2026-10-31: `docs/ROUTERS.md`, `setup/README.md`, `CHANGELOG.md`, RS71; the website manual was not touched: it has no Settings chapter to extend, and a site change is the owner's) `docs/ROUTERS.md`, `setup/README.md`, `CHANGELOG.md`, RS71 in `docs/REAL_WORLD_TESTING.md`. (no code)
- [x] 8. **Gates.** (run 2026-10-31, results in `validation.md`) `bash scripts/check_security_rules.sh`; in `tools/pisoportal`: `cargo test`, `python3 tests/test_flow.py`; `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`,
  `test_setup_web.py`, `test_setup_tools.py`; for the setup program `test_setup_phone_web.py`, `test_phone_only.py`; `python3 tools/build_piso_setup.py --check`; ruff/mypy on touched Python.
- [ ] 9. **Bench test on the 360T6M** (criteria 6, 10, 11, 12) and fill `validation.md`. Stop for the owner if SQM and openNDS clash.

## Release
- Raise `setup/RELEASE` (1.5.34 now). Regenerate with `python3 tools/build_piso_setup.py` (never by hand).
- Router path (`CLAUDE.md` section 5): bring `main` into `beta`, push, wait for CI's two commits ("ci: built the router program", "ci: rebuilt the setup files around
  the router programs"), merge into `main` as the section says, **decode the program inside the signed file and check it has the new SQM text before anyone raises the release**,
  then `verify_release.py` 0 FAIL. Raising to 100% is a separate step for the owner.
- No firmware or app release. Update `specs/RELEASE_STATE.md` and `specs/STATUS.md` when released.
