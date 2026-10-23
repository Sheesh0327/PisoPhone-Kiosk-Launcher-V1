# Plan: feature 011, first setup without internet on the router

Status: **approved by the owner (2026-10-22). Not ready to start (2026-10-23): 010 is merged and live as router 1.5.17 and this branch is rebased on `beta` `b1db87f` with the spec's references refreshed, but the owner then chose to ship feature 012 first as router 1.5.18. Group 0 stays open until 012 is merged into `beta`, this branch is rebased on that `beta`, and the owner says to start.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py`, never by hand. If the spec does not say something you need, stop and ask the owner.

Context manifest (constitution rule 1). Ledger: read `ledger/negative-constraints.md` at the start and append this feature's red results to it. **No open entry bears on 011** — every row that touches the setup or the installer is marked resolved (checked 2026-10-23). Worth knowing rather than loading: the installer and router-program size budgets were raised twice (to 1200 and 1700 KB) because earlier features grew them, so if group 3's new `21-local-packages.sh` pushes `setup/piso-setup.sh` past its budget, `router/tests/test_footprint.py` is the check that will say so, and the budget is an owner decision, not something to raise by yourself.

Line numbers below are hints true at `beta` `b1db87f` (2026-10-23); the **symbol name is the anchor**, so `grep -n` for it rather than trusting the number (010's merge moved most of them by 16 to 34 lines).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/011-offline-setup/spec.md` | the contract | all | 5,000 |
| `setup/pisophone_setup.py` | `class Ssh` (1747, **not** `SshRunner`), `installer_command` (1874), `setup_command` (1884), `run_remote` (1931), `install` (1981), arguments in `main` (5465-5500) | grep the symbols | 3,500 |
| `setup/install.sh` | `pick_dir` (37-42, 010's RAM rule), `fetch()` (49), argument parsing (115-121), CPU file choice (135-144), download and sha256 check (149-167) | grep the symbols | 1,200 |
| `setup/src/20-preflight.sh` | `preflight`'s ping check (38), `install_packages` (43-56) | 30-57 | 500 |
| `setup/src/98-setup-flow.sh` | `stage1`: `preflight` (116), `install_packages` (135), `apply_batch` (139) | 114-141 | 400 |
| `setup/src/96-space-backup.sh` | `mem_available_kb` (15), its use (23) | 14-25 | 200 |
| `router/tests/test_setup.py`, `test_install.py`, `test_pisophone_setup.py` | existing stubs and helpers | grep, ranges | 2,500 |
| `.github/workflows/quality.yml` | where the setup tests are listed (`test_setup.py` 346, `test_install.py` 350, through `test_phone_only.py` 358) | 340-365 | 600 |

## Task groups
Each group names the spec criteria it proves.

- [ ] 0. **Owner gate.** Spec, its three owner decisions and this plan approved (2026-10-22). Done: feature 010 merged into `beta` and live as router 1.5.17; this branch rebased on `beta` `b1db87f` with the spec's references refreshed (2026-10-23, architect session). **Left: feature 012 merged into `beta` (the owner chose to ship it first as router 1.5.18), this branch rebased on that `beta`, and the owner's go.** When 012 lands, re-check the line hints for `setup/src/98-setup-flow.sh` and `setup/pisophone_setup.py`: 012's plan touches both, so `main`'s argument block will move (012 edits `checkup` above it) even though the `Ssh`-to-`install` anchors should hold. Grep the symbols; that is what they are for.
- [ ] 1. **Baseline** (criterion 1). Run the area checks the criterion lists; counts in `validation.md`. Four are already measured on this exact base (`test_setup` 379, `test_install` 39, `test_pisophone_setup` 189, `test_phone_only` 39, 0 failures; security rules passed; `--check` up to date) — re-run them to confirm, and run or record-as-skipped the four browser-backed files (`test_setup_web`, `test_setup_tools`, `test_setup_phone_web`, and the browser part of `test_pisophone_setup`) depending on whether Playwright is installed.
- [ ] 2. **Router-side check and install, tests first** (criterion 6). `setup/src/21-local-packages.sh` with `install_local_packages`; tests with stub `usign`, `sha256sum`, `opkg`, including a tampered `.ipk` and a tampered `Packages`.
- [ ] 3. **Installer without internet, tests first** (criterion 7). `PISO_LOCAL_PKGS` in `20-preflight.sh` (no ping, no `opkg update`, `install_local_packages` instead); `install.sh --local`. The existing tests pass unchanged. Raise `setup/RELEASE` here. Regenerate; `--check` passes.
- [ ] 4. **Reading the router and resolving packages, tests first** (criteria 2, 3). Create `router/tests/test_offline_setup.py` and list it in `quality.yml` in this group. `read_router_feeds`, `resolve_packages` with the two fixture sets.
- [ ] 5. **Building and pushing the bundle, tests first** (criteria 4, 5). `build_offline_bundle` against a local HTTP server; `push_offline_bundle` through a stub SSH; the RAM rule.
- [ ] 6. **The whole flow, tests first** (criterion 8, decisions 7 and 8). Offline first in the order of decision 7 (bundle built before the router changes; only `install.sh` at the factory address; the bundle and `PISO_LOCAL_PKGS` at 10.0.0.1), the fallback with the ping test, the stop with both reasons, `--router-online`, the bundle deleted afterwards over SSH. Regenerate `website/pisophone_setup.py`.
- [ ] 7. **Docs and the hardware check** (criterion 9). `docs/REAL_WORLD_TESTING.md` (new check, "not run"), `setup/README.md` (the router no longer needs internet during setup with the setup program; `--router-online`), `website/manual.html`, `CHANGELOG.md`.
- [ ] 8. **Validate** (criterion 10). Every criterion in `validation.md` with evidence, the gates, "Not tested" (at least: all hardware, community sources, real OpenWrt indexes beyond the fixtures); update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` raised once in group 3, from feature 012's 1.5.18 to **1.5.19** (the owner chose to ship 012 first). Rebase on `beta` after 012 is merged, too. Firmware, phone app: not touched.
- Setup program: the new `website/pisophone_setup.py` is published with the website when merged to `main` (owner's order).
