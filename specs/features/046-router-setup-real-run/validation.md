# Validation: feature 046, the router installer in a real shop

Run 2026-10-28 on a computer (Linux container; no router, coin box, phone, BusyBox, ShellCheck or Armbian box). Tests run with `< /dev/null`.
Branch `feat/046-router-setup-real-run` from `origin/main` (2591448) plus the epic commit. **The generated files were rebuilt once, at the close-out.**

| # | Success criterion (spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | AC2.1 LAN ports set down then up after the move, only `lanN` (SU-03) | `test_install.py` fake `ip` and a fake `/sys/class/net` | pass (computer) | 4 ports down, then 4 up, no `wan`, `br-lan`, `eth0` or `lan1.1`; a router with no `lanN` ports: no `ip` call. **swconfig is not bounced** (spec edited): it cannot be tested here and a wrong toggle could cut the CPU port |
| 2 | AC2.2 only the board just written is paired (SU-11) | `test_setup.py` (`box_station`, `pair_box`), `test_pisophone_setup.py` (answers line, `read_board_mac`, terminal flow), `test_setup_web.py` (page) | pass (computer) | a stranger joining first is not paired and the log names both MACs; the expected one is paired with a stranger on the air; no expected MAC: first station as before; the MAC is the last answer and empty with *Do not write it* / `--no-flash`; the next unit starts without a MAC |
| 3 | AC2.3 no opkg stops before anything is done (SU-06b) | `test_install.py` (PATH without opkg), `test_setup.py` preflight | pass (computer) | nothing downloaded, moved, restarted or run; message names the supported releases and `docs/ROUTERS.md` |
| 4 | AC2.4 modem range by netmask, later alert (SU-05) | `test_setup.py` (`wan_clash`: 10.0.0.5/24, 10.1.2.3/8, 10.0.30.7/24, 192.168.1.50/24, none; `cmd_wan_clash`; status check), `test_install.py` (10.1.2.3/8 refused, 10.9.9.9/24 allowed), `test_monitor.py` | pass (computer) | refused / allowed as listed; `piso-setup wan-clash` text; one monitor alert a day |
| 5 | AC2.5 dead package sources named (SU-07) | `test_setup.py` | pass (computer) | with a working ping: "the package sources of OpenWrt 21.02.7 do not answer ... docs/ROUTERS.md"; without: the old message |
| 6 | AC2.6 result sent before the access restart (SU-08) | `test_setup.py` stage 2 with a restart that sends HUP to the session | pass (computer); **RS61 (c) decides whether it stays** | shown red on the old code (`FAILED stopped before the end (interrupted)`, no `SETUP COMPLETE`), green on the new; if the real session is never cut the row closes `wont-fix` and the reorder can be removed |
| 7 | AC2.7 release raised, rebuilt, gates | see below | pass (computer) | `setup/RELEASE` 1.5.30; `build_piso_setup.py --check` passes |
| 8 | AC2.8 RS61 added, unticked; owner runs it before merge (D8) | `docs/REAL_WORLD_TESTING.md` | **RS61 not run** | RS17, RS20, RS21, RS36 run with it |

## Gates (constitution rule 7)
- [x] Area checks: `test_setup` 480, `test_pisophone_setup` 239, `test_setup_web` 118, `test_offline_setup` 98, `test_setup_tools` 78, `test_install` 54, `test_phone_only` 39, `test_setup_phone_web` 31, `test_monitor` 40, `test_boxupdate` 28, `test_selfupdate` 60 checks, 0 failures; `test_footprint` 10 budgets, 0 failures.
- [x] `bash scripts/check_security_rules.sh`.
- [x] Router program: `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal` (the program is unchanged; run as `CLAUDE.md` asks): `cargo test` 158 passed, `test_flow.py` 205 checks, 0 failures.
- [x] Python (the program-side change in `setup/pisophone_setup.py`): `ruff check setup/pisophone_setup.py`: one new `BLE001` (`except Exception` around the board-MAC read in the page, so a failed read never stops the setup), the same pattern as the 25 already in the file; `mypy`: 8 errors before and after (missing `serial` stubs and `make_wifi_poster`); `pytest -x` cannot collect these script tests.
- [x] Generated files regenerated, `--check` passes.
- [ ] ShellCheck and BusyBox `ash`: **not available here**; CI runs them. The new shell sticks to the constructs already used in these files (`$(( ))` with `<<` and `|`, `case`, `awk -v`).
- [x] Size: `test_footprint.py`: installer 1647 of 1700 KB, installed command 148 of 150 KB (about 2 KB left). No limit was raised.

## Not tested
Nothing ran on a real router, box, phone or computer. Not seen: BusyBox `ash` and ShellCheck on the new shell; the real `iwinfo ... assoclist` output (a stub gives one MAC per line); whether the 360T6M is DSA (`lan1`..`lan4`) and whether bouncing the ports makes Linux, macOS and the Armbian box renew (RS61 (a)); whether a coin box with the full-system firmware answers the serial hello right after it is written (if it does not, no MAC is sent and the router pairs any box as before: RS61 (b)); whether the SSH and LuCI restart ever cuts the session (RS61 (c)); the monitor on a real router. swconfig switches are not bounced.
Hardware check added: **RS61** (a to d).

## Money and time
Pairing is the only row near money (a box paired to the wrong router credits the wrong shop). The fix only narrows it: a station other than the expected one is not paired; with no expected MAC the behaviour is the one before. The payment queue, coin windows, credit, expiry and `revenue.csv` are not touched (`tools/pisoportal/` and the firmware are unchanged). Checked by the `pair_box` tests above with a wrong station joining first, the expected one with a stranger present, and no expected MAC.
