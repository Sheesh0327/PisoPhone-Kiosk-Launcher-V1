# Validation: 058 optional smooth-line packages (SQM) and the console setting

Status 2026-10-31: **built and tested on a computer; nothing has run on a router.** Criteria 11, 12, 13 and 17 are the owner's bench test (RS71) and group 0 / 9 of the plan. Not released, not through `beta`.

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | openNDS first, others optional | `router/tests/test_optional.py`: with openNDS failing, `install_packages` stops with "could not install opennds" and tries no optional package; with only optional packages failing the setup carries on and writes each failure down; the flow installs the required packages first (the test was shown to fail when the install works) | pass (fake package layer) | `test_optional.py` 26 checks, 0 failures |
| 2 | Image type | `router/tests/test_pkgmgr.py` (official, fork, custom; `test_optional.py` for what each is offered) | pass (fake commands and container images only) | `test_pkgmgr.py` 56, `test_optional.py` 21 |
| 3 | Scan, priority order, cake missing gives fq_codel, fake opkg and fake apk | `test_optional.py`, `test_sqm.py` (fq_codel without cake) | pass | 21 and 37 checks, 0 failures |
| 4 | All-or-nothing core | `test_optional.py` ("a core install that fails is taken out again") | pass | as above |
| 5 | Status file read by status and the console | `test_optional.py`, `test_console_sqm.py` (packages.txt and sqm.txt) | pass | 21 and 61 checks |
| 6 | Settings card rejects bad values, saves nothing, shows what SQM will limit to | `test_console_sqm.py` (14 bad values, one bad among good, switching on without speeds), `test_console_sqm_card.py` (Chromium, live line) | pass | 61 and 29 checks. The spec says 1 to 10000 Mbps; the code takes **0.1** to 10000 (spec line 6 vs the answered range) |
| 7 | Apply writes the sqm config for the real WAN device at the margin; off removes it | `test_sqm.py` (eth1, pppoe-wan, 95% of 50/20 is 47500/19000, decimals, off) | pass with a fake `uci`. `tc qdisc show` on a router: **not run** | 37 checks |
| 8 | Per-plan caps untouched | `test_sqm.py`: every uci change of the sqm commands names `sqm` only; no uci line for openNDS, firewall, network, DHCP, Wi-Fi, flow offloading or the rate-check window in the two source pieces | pass (static and fake-uci). A comparison of two installer dry runs was dropped: the dry run never reaches the optional step | 37 checks |
| 9 | No money path touched | `git diff origin/main` on `pricing.rs roll.rs ledger.rs voucher.rs reconcile.rs fair.rs nds.rs send.rs`: empty. `core.rs`: one added read-only getter `window_open()`. `cargo test` and `test_flow.py` | pass | `cargo test` 199 passed; `test_flow.py` 216 checks, 0 failures (it failed 5 once earlier and passed on 4 reruns: the flake in `STATUS.md`, not proven to be that one) |
| 10 | Install now: never by an automatic update; refused while a coin window is open or a customer is online | `test_console_sqm.py` (BUSY with a customer online, RUNNING, NOT_POSSIBLE, INSTALLED, fixed arguments), unit tests in `sqmconsole.rs`; the shell side (`sqm install`) refuses with BUSY on customers online (`test_sqm.py`) | pass with fakes. The coin-window refusal is the **portal's** (`window_open`): a unit test covers the rule, no end-to-end test opens a real window | 61 checks, 6 unit tests |
| 11 | Reboot brings shaping back | bench | **not run** (RS71 e) | |
| 12 | Bench with openNDS caps, ping under load (stop point SQ-12) | bench | **not run** (RS71 c) | |
| 13 | Measured flash and RAM cost | bench | **not run**; `docs/ROUTERS.md` says it will be written there | |
| 14 | Speed test, no customers online | `test_sqm_test.py` (fake curl, 4 streams each way, 250 MB cap, SQM stopped and started, saves no settings), `test_console_speedtest.py`, `test_console_sqm_card.py` (boxes filled in Chromium) | pass with fakes | 30, 34, 29 checks |
| 15 | Refused while a customer is online or a coin window is open | `test_sqm_test.py` (BUSY, nothing started, no file written; a customer connecting mid-test stops it), `test_console_speedtest.py` (409 BUSY, button off with the reason) | pass with fakes. Coin window: unit test only, as in 10 | |
| 16 | CPU limit, failure, one a minute | `test_sqm_test.py` (cpu_limited at 90%+, no host, a dead lock does not block), `test_console_speedtest.py` (TOO_SOON, interrupted file), card test (warning, boxes not filled, reason shown) | pass with fakes | |
| 17 | Bench: router's number against a phone's | bench | **not run** (RS71 d). Cloudflare `__down` / `__up` endpoints and `curl -T -` uploads were **never reached from here** | |
| 18 | Documents | `docs/ROUTERS.md`, `setup/README.md`, `CHANGELOG.md`, RS71 | pass (written). The website manual was not changed | `check_links.py` 0 dead links |

## Gates (constitution rule 7)
- [x] Router: `test_setup` 479, `test_pisophone_setup` 260, `test_setup_web` 165, `test_setup_tools` 87, `test_setup_phone_web` 47, `test_phone_only` 51, `test_install` 49, `test_optional` 21, `test_sqm` 37, `test_sqm_test` 30, `test_pkgmgr` 56, `test_package_flags` 9, `test_footprint` 12 budgets: all 0 failures.
- [x] Portal: `cargo fmt --check`, `cargo clippy --release` (0 warnings), `cargo test` 199; `test_flow` 216, `test_console` 110, `test_console_sqm` 61, `test_console_speedtest` 34, `test_console_sqm_card` 29, `test_console_prices` 13, `test_console_loader` 8, `test_console_frame` 7, `test_money_panel` 69, `test_versions_panel` 13, `test_pricing_flow` 9, `test_link_loss` 16; `test_console_layout` 247 (on the final console script) and `test_console_brand` 75 (both re-run on the final console script).
- [x] `bash scripts/check_security_rules.sh` passes; `python3 tools/build_piso_setup.py --check` passes.
- [x] Python: `ruff check` and `mypy` are clean on the new router and portal tests (the `open().write()` calls were rewritten to `pathlib`). `pytest` is not used by these tests (they are scripts).
- Not run here: `shellcheck`, `busybox` (the BusyBox-only decoding check of `test_setup.py` is skipped), the ESP32/Android builds (not touched).

## Not tested
Any router. The real hand-over to `piso-setup`, the Cloudflare speed servers, `curl -T -` uploads, the cake option strings and `layer_cake.qos` (first guesses), real CPU numbers from `/proc/stat`, `tc` on a real WAN, the footprint of the packages, a reboot, SQM next to openNDS's own shaping (the stop point). Colour contrast of the new card was not measured. The Filipino/English split does not apply (console only). Hardware check: **RS71** (and RS70 for feature 057).

## Decisions made on the way, for the owner to confirm
Installed-command budget 175 KB (173 used); console script budget 33 KB and page check 31,000 bytes; `piso-setup sqm test` instead of `sqm-test`; irqbalance installed but not switched on; Install now hidden when the setup recorded that trying again cannot help; a processor-limited result is shown but not put in the boxes; the feature was renumbered 058 because `main` uses 056 for the voice message (answered by the owner 2026-10-31).

## Money and time
Not touched: no credit, expiry, deduction, ledger or payment-queue code changed. The only change in `core.rs` is a read-only getter used to refuse a speed test or an install while a coin window is open. SQM shapes the router's internet link as a whole and does not change the per-plan caps (checked by the tests of criterion 8, not on a router).
