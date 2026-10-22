# Plan: feature 011, first setup without internet on the router

Status: **approved by the owner (2026-10-22). Not started: start only after feature 010 is merged into `beta` (rebase this branch on `beta` first) and the owner says to start.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py`, never by hand. If the spec does not say something you need, stop and ask the owner.

Context manifest (constitution rule 1). Unresolved ledger entries loaded: read `ledger/negative-constraints.md` at the start (line 28: a test file CI never ran).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/011-offline-setup/spec.md` | the contract | all | 4,500 |
| `setup/pisophone_setup.py` | `SshRunner` (1715-1790), `installer_command`/`setup_command`/`run_remote`/`install` (1840-1970), arguments (5430-5470) | ranges | 3,500 |
| `setup/install.sh` | arguments (95-105), file choice and download (115-160), `fetch` (33-41) | ranges | 1,200 |
| `setup/src/20-preflight.sh` | ping check (38), `install_packages` (43-56) | 30-60 | 500 |
| `setup/src/98-setup-flow.sh` | where `preflight` and `install_packages` run (85-110) | 85-110 | 400 |
| `setup/src/96-space-backup.sh` | `mem_available_kb` (15-18) | 15-25 | 200 |
| `router/tests/test_setup.py`, `test_install.py`, `test_pisophone_setup.py` | existing stubs and helpers | grep, ranges | 2,500 |
| `.github/workflows/quality.yml` | where the setup tests are listed (around 340-365) | ranges | 600 |

## Task groups
Each group names the spec criteria it proves.

- [ ] 0. **Owner gate.** Spec, its three owner decisions and this plan approved (2026-10-22). Left: feature 010 merged into `beta`, this branch rebased on it, the owner's go.
- [ ] 1. **Baseline** (criterion 1). Run the tests; counts in `validation.md`.
- [ ] 2. **Router-side check and install, tests first** (criterion 6). `setup/src/21-local-packages.sh` with `install_local_packages`; tests with stub `usign`, `sha256sum`, `opkg`, including a tampered `.ipk` and a tampered `Packages`.
- [ ] 3. **Installer without internet, tests first** (criterion 7). `PISO_LOCAL_PKGS` in `20-preflight.sh` (no ping, no `opkg update`, `install_local_packages` instead); `install.sh --local`. The existing tests pass unchanged. Raise `setup/RELEASE` here. Regenerate; `--check` passes.
- [ ] 4. **Reading the router and resolving packages, tests first** (criteria 2, 3). Create `router/tests/test_offline_setup.py` and list it in `quality.yml` in this group. `read_router_feeds`, `resolve_packages` with the two fixture sets.
- [ ] 5. **Building and pushing the bundle, tests first** (criteria 4, 5). `build_offline_bundle` against a local HTTP server; `push_offline_bundle` through a stub SSH; the RAM rule.
- [ ] 6. **The whole flow, tests first** (criterion 8, decisions 7 and 8). Offline first in the order of decision 7 (bundle built before the router changes; only `install.sh` at the factory address; the bundle and `PISO_LOCAL_PKGS` at 10.0.0.1), the fallback with the ping test, the stop with both reasons, `--router-online`, the bundle deleted afterwards over SSH. Regenerate `website/pisophone_setup.py`.
- [ ] 7. **Docs and the hardware check** (criterion 9). `docs/REAL_WORLD_TESTING.md` (new check, "not run"), `setup/README.md` (the router no longer needs internet during setup with the setup program; `--router-online`), `website/manual.html`, `CHANGELOG.md`.
- [ ] 8. **Validate** (criterion 10). Every criterion in `validation.md` with evidence, the gates, "Not tested" (at least: all hardware, community sources, real OpenWrt indexes beyond the fixtures); update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` raised once (after 010's 1.5.17), in group 3. Firmware, phone app: not touched.
- Setup program: the new `website/pisophone_setup.py` is published with the website when merged to `main` (owner's order).
