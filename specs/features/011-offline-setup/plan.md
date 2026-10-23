# Plan: feature 011, setting up a router in a real shop (no internet needed, no coin box mix-ups)

Status: **approved by the owner (part A 2026-10-22, part B 2026-10-23; merged into one feature 2026-10-23 at the owner's instruction). Ready to execute: 010 is merged and live as router 1.5.17, this branch is rebased on `beta` `b1db87f`, and the references in the spec were refreshed and re-verified against it. Start at group 1 when the owner says go.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py`, never by hand. If the spec does not say something you need, stop and ask the owner.

**Part B first, then part A.** Part B (the coin box network's own name) is small and fixes a problem the owner hit in the field, so it is built and finished first, on a known-green base. Part A (the first setup without the router's internet) follows. Nothing in part A reads or writes what part B changes, so the two do not interleave. `setup/RELEASE` is raised once, in group 2, and carries both parts.

Context manifest (constitution rule 1). Ledger: read `ledger/negative-constraints.md` at the start and append this feature's red results to it. **No open entry bears on this feature** — every row that touches the setup or the installer is marked resolved (checked 2026-10-23). Worth knowing rather than loading: the installer and router-program size budgets were raised twice (to 1200 and 1700 KB) because earlier features grew them, so if group 6's new `21-local-packages.sh` pushes `setup/piso-setup.sh` past its budget, `router/tests/test_footprint.py` is the check that will say so, and the budget is an owner decision, not something to raise by yourself.

Line numbers below are hints true at `beta` `b1db87f` (2026-10-23); the **symbol name is the anchor**, so `grep -n` for it rather than trusting the number (010's merge moved part A's by 16 to 34 lines).

| file | part | purpose | lines | est_tokens |
|---|---|---|---|---|
| `specs/features/011-offline-setup/spec.md` | both | the contract | all | 7,000 |
| `setup/src/50-coin-box.sh` | B | `box_up` (18), `box_wifi_key` (22), `pair_box` (32), `lock_box_network` (95), `rotate_box_wifi` (180) | grep the symbols | 2,500 |
| `setup/src/10-settings.sh` | B | `router_id` (56), `resolve_wifi_names` (68, the `KIOSK_PASS` branch at 71), `box_net_ssid` (81) | 50-85 | 800 |
| `setup/src/80-commands.sh` | B | `cmd_status` (4) | 1-60 | 800 |
| `setup/src/60-summary.sh` | B | the summary | grep, ranges | 500 |
| `setup/pisophone_setup.py` | B | `checkup` (3972) | ranges | 1,200 |
| `esp32_firmware/src/WebServerConfig.cpp` | B | read only, to confirm fact B4: `wifi_ssid` 233-235, `wifi_pass` 237-239, neither validated. **Firmware is not changed.** | 230-258 | 400 |
| `setup/pisophone_setup.py` | A | `class Ssh` (1747, **not** `SshRunner`), `installer_command` (1874), `setup_command` (1884), `run_remote` (1931), `install` (1981), arguments in `main` (5465-5500) | grep the symbols | 3,500 |
| `setup/install.sh` | A | `pick_dir` (37-42, 010's RAM rule), `fetch()` (49), argument parsing (115-121), CPU file choice (135-144), download and sha256 check (149-167) | grep the symbols | 1,200 |
| `setup/src/20-preflight.sh` | A | `preflight`'s ping check (38), `install_packages` (43-56) | 30-57 | 500 |
| `setup/src/98-setup-flow.sh` | both | B: the command list (151) and dispatch (`rotate-box-wifi` at 194). A: `stage1` — `preflight` (116), `install_packages` (135), `apply_batch` (139) | 114-200 | 1,300 |
| `setup/src/96-space-backup.sh` | A | `mem_available_kb` (15), its use (23) | 14-25 | 200 |
| `router/tests/test_setup.py`, `test_install.py`, `test_pisophone_setup.py` | both | existing stubs and helpers (`box_curl`, `uci`, `wifi`, the existing rotate tests) | grep, ranges | 2,500 |
| `.github/workflows/quality.yml` | A | where the setup tests are listed (`test_setup.py` 346, `test_install.py` 350, through `test_phone_only.py` 358) | 340-365 | 600 |

## Task groups
Each group names the spec criteria it proves.

- [x] 0. **Owner gate.** Spec, both sets of owner decisions and this plan approved (part A 2026-10-22, part B 2026-10-23); the two features merged at the owner's instruction (2026-10-23). Done: feature 010 merged into `beta` and live as router 1.5.17; this branch rebased on `beta` `b1db87f`; every reference in the spec refreshed and re-verified. **Left: the owner's go.**
- [x] 1. **Baseline** (criterion 1). Run the area checks the criterion lists; counts in `validation.md`. Four are already measured on this exact base (`test_setup` 379, `test_install` 39, `test_pisophone_setup` 189, `test_phone_only` 39, 0 failures; security rules passed; `--check` up to date) — re-run them to confirm, and run or record-as-skipped the four browser-backed files depending on whether Playwright is installed.

### Part B: the coin box network's own name
- [ ] 2. **Failure before the switch changes nothing, tests first** (criterion 3). `rotate_box_wifi` restores `BOX_WIFI_ROTATED` (and leaves every other setting) when the box refuses; the after-switch message. This fixes `piso-setup rotate-box-wifi` too (fact B7). **Raise `setup/RELEASE` to 1.5.18 here** — once, for both parts. Regenerate; `--check` passes.
- [ ] 3. **The command, tests first** (criteria 2, 6). `own_box_wifi_name` and `piso-setup box-wifi-name`: the checks before it starts, the question or `--yes`, the new name from `router_id` **checked before it is sent** (fact B4: the box validates nothing), `BOX_SSID_NAME`, then `rotate_box_wifi`; no kiosk setting touched.
- [ ] 4. **Telling the owner, tests first** (criterion 4). The status line in `piso-setup status` and the summary; the Check-up line in the setup program. Regenerate `website/pisophone_setup.py`.
- [ ] 5. **Pairing tips, tests first** (criterion 5). The tip at 60 s and 150 s and in the "did not join" message; the pairing itself unchanged (it must keep using `PisoCoinBox`).

### Part A: the first setup without the router's internet
- [ ] 6. **Router-side check and install, tests first** (criterion 11). `setup/src/21-local-packages.sh` with `install_local_packages`; tests with stub `usign`, `sha256sum`, `opkg`, including a tampered `.ipk` and a tampered `Packages`.
- [ ] 7. **Installer without internet, tests first** (criterion 12). `PISO_LOCAL_PKGS` in `20-preflight.sh` (no ping, no `opkg update`, `install_local_packages` instead); `install.sh --local`. The existing tests pass unchanged. `setup/RELEASE` was already raised in group 2, so do not raise it again. Regenerate; `--check` passes.
- [ ] 8. **Reading the router and resolving packages, tests first** (criteria 7, 8). Create `router/tests/test_offline_setup.py` and list it in `quality.yml` in this group. `read_router_feeds`, `resolve_packages` with the two fixture sets.
- [ ] 9. **Building and pushing the bundle, tests first** (criteria 9, 10). `build_offline_bundle` against a local HTTP server; `push_offline_bundle` through a stub SSH; the RAM rule.
- [ ] 10. **The whole flow, tests first** (criterion 13, decisions A7 and A8). Offline first in the order of decision A7 (bundle built before the router changes; only `install.sh` at the factory address; the bundle and `PISO_LOCAL_PKGS` at 10.0.0.1), the fallback with the ping test, the stop with both reasons, `--router-online`, the bundle deleted afterwards over SSH. Regenerate `website/pisophone_setup.py`.

### Both parts
- [ ] 11. **Docs and the hardware checks** (criterion 14). `docs/REAL_WORLD_TESTING.md` (new checks for both parts, "not run"); `setup/README.md` (the router no longer needs internet during setup with the setup program; `--router-online`; the `box-wifi-name` command and "switch the other router off while pairing"); `setup/MANUAL.md`; `website/manual.html`; `CHANGELOG.md` (one entry describing both parts for the owner).
- [ ] 12. **Validate** (criterion 15). Every criterion in `validation.md` with evidence, the gates, "Not tested" (at least: all hardware, community sources, real OpenWrt indexes beyond the fixtures), "Money and time"; update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` 1.5.17 -> **1.5.18**, once, in group 2, carrying both parts. Firmware and phone app: not touched.
- Setup program: the new `website/pisophone_setup.py` is published with the website when merged to `main` (owner's order).
- Publishing: after the owner's order, the merge to `main` makes CI publish at 0%; the owner raises it to 100%.
