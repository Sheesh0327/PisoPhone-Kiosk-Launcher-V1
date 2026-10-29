# Validation: feature 045, the setup program in a real shop

Run 2026-10-28 on a computer (Linux container, no router, box, phone, Windows or macOS). Router tests run with `< /dev/null`.
Tests of the setup program are plain scripts (`python3 router/tests/test_*.py`), as `CONTRIBUTING.md` says; `pytest -x` cannot collect them (they end with `sys.exit`).

| # | Success criterion (spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | AC1.1 setup log, 0600, no passwords, named on the last page and in the error (SU-18) | `test_setup_web.py` section "feature 045 (SU-18)" | pass (computer) | file mode 0600, addresses first, `***` for typed, generated, PisoPhone-only and saved passwords; unwritable folder does not stop the setup |
| 2 | AC1.2 *Try again* uses the saved password; a refused login names the password needed (SU-09, SU-10) | `test_setup_web.py` `PasswordCore`; `test_pisophone_setup.py` refused-login block | pass (computer) | a retry finishes with the saved password; with none it stops before anything is installed and sends the owner to the Router step; "was not given" / "did not accept" texts |
| 3 | AC1.3 two interfaces in 192.168.1.x stop the page before any ssh; README recommends the hotspot (SU-01) | `test_pisophone_setup.py` address tables (Linux, macOS, Windows); `test_setup_web.py` `ClashCore` | pass (computer) | no probe or login was sent; *Search again* finds the router once the clash is gone; `setup/README.md` "Which network?" |
| 4 | AC1.4 early download and the unplug prompt (SU-02) | `test_pisophone_setup.py` `prefetch_site`, `wait_for_site`, `wait_for_router`, `install()` end to end; `test_setup_web.py` prefetch at Start and banner | pass (computer) | firmware list + every image, app.json, install.sh and the five setup files kept; with the website out of reach the kept copy is used; install() asks to unplug, downloads, asks to plug back and finishes; the banner shows both prompts |
| 5 | AC1.5 router found at the lease's gateway (SU-04) | `test_pisophone_setup.py` `parse_gateways`, `find_router`, e2e at 192.168.8.1 | pass (computer) | found, named "Router found at 192.168.8.1", and moved to 10.0.0.1 |
| 6 | AC1.6 apk / no opkg refused, nothing changed (SU-06a) | `test_pisophone_setup.py` e2e with `@@APK yes`; `test_offline_setup.py` | pass (computer) | no download, no ping, no copy, no setup, no uci change; release and `docs/ROUTERS.md` named |
| 7 | AC1.7 no phone setup page while the router cannot reach the website (SU-15) | `test_setup_web.py` Phones step | pass (computer) | state `nointernet`, no provisioning call; *Check again* then makes the page |
| 8 | AC1.8 `USER_AGENT` on the software list and image; service starts its own copy (SU-17, SU-21) | `test_pisophone_setup.py`, `test_setup_web.py` | pass (computer) | both requests carry the agent; the unit's `ExecStart` is `<state_dir>/pisophone_setup.py` |
| 9 | AC1.9 gates; RS60 added; `--check` passes | see below | pass on a computer; **RS60 not run** | RS60 is in `docs/REAL_WORLD_TESTING.md`, unticked; the owner runs it before this feature merges (D8) |

## Gates (constitution rule 7)
- [x] Area checks: `test_setup` 458, `test_pisophone_setup` 274, `test_setup_web` 141, `test_offline_setup` 100, `test_setup_tools` 78, `test_install` 48, `test_phone_only` 39, `test_setup_phone_web` 31 checks, 0 failures. (`tools/pisoportal` and `setup/src/` are untouched, so `cargo test` and `test_flow.py` were not run.)
- [x] `bash scripts/check_security_rules.sh`: all passed.
- [x] Python: `ruff check setup/pisophone_setup.py`: 46 findings before, 48 after: the two new ones are `BLE001` (`except Exception`) in the Start-time download and the network check, the same pattern as the 25 already in the file. `mypy`: 8 errors before and after (missing `serial` stubs and `make_wifi_poster`). `pytest -x`: not applicable, see above.
- [x] Generated files regenerated: `python3 tools/build_piso_setup.py` then `--check` passes; `git status` shows only `website/pisophone_setup.py` changed among generated files, so no router file changed and `setup/RELEASE` is not raised.

## Not tested
Nothing ran on a real router, coin box, phone, Windows or macOS computer. Specifically not seen: the real `ip`, `ifconfig`, `ipconfig`, `route` and `netstat` output (the parsers use sample tables), a real DHCP gateway on a vendor router, the router's real `wget` fetching `app.json` over https (SU-15), a real cable unplug (the prompt flow is driven by a fake opener), the page in a browser (Playwright is not installed here), a Windows 10 ssh older than 8.4 (SU-14, left `open` until RS60 (b)). SU-01 covers the two-interfaces case only, not "the router address reachable through the Wi-Fi". SU-15 asks the router to fetch `app.json` from the website, not the APK itself. One flaky router test (`the answers went over the connection's input`, stray shell text on stdin) also fails about 1 run in 10 on `origin/main`; see the ledger.
Hardware check added: **RS60** (a to e).

## Money and time
Not touched: no credit, expiry, deduction, payment queue or `revenue.csv`, and no box <-> phone message. The downloads still go through the same signature and sha256 checks; a kept copy of the installer files is used only when the website cannot be reached and is checked against its kept `.sha256` like a fresh one.
