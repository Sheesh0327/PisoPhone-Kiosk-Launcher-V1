# Plan: feature 010, router flash profile and setup check

Status: **approved by the owner (2026-10-22); aligned with the spec's round 6 review (one contract, fixed names). Not started: no code, and no task group run, until the owner says to start. D1 and D2 are accepted (spec decisions 12 and 13); nothing is open.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py` or by CI, never by hand. If the spec does not say something you need, stop and ask the owner.

Context manifest (constitution rule 1). Unresolved ledger entries loaded: read `ledger/negative-constraints.md` at the start (line 28: a test CI never ran).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/010-router-flash-profile/spec.md` | the contract | all | 6,500 |
| `setup/src/00-header.sh`, `10-settings.sh` | `CONF`, `SELF_PATH`, `conf_get` / `conf_set` | 1-60 each | 1,500 |
| `setup/src/96-space-backup.sh` | `work_base`, `cmd_space`, the setup's space checks | 1-60 | 900 |
| `setup/src/40-files.sh` | where the program and `piso-setup` are installed | 70-100 | 500 |
| `setup/src/60-summary.sh` | the setup summary (where the flash line and warnings go) | grep, ranges | 600 |
| `setup/src/90-updates.sh` | the hourly update and its feed URL | 85-160 | 1,200 |
| `setup/src/98-setup-flow.sh` | the order of the setup steps, re-run handling | grep, ranges | 800 |
| `setup/install.sh` | the one-line installer: file choice by CPU | 100-160 | 900 |
| `tools/build_piso_setup.py` | how installers and website copies are made | 1-120 | 1,800 |
| `tools/pisoportal/Cargo.toml`, `src/main.rs`, `src/http.rs`, `src/console.rs`, `src/core.rs` | where the wheel, `/game` and `boxview` are wired in | grep, ranges | 3,000 |
| `tools/pisoportal/src/page.html`, `console.html`, `console-ui.js` | the buttons and tabs to hide on the small build | grep, ranges | 1,500 |
| `.github/workflows/quality.yml`, `router-program.yml`, `publish-router.yml` | CI build, test, sign, publish | ranges | 3,000 |
| `scripts/verify_release.py`, `router/tests/test_footprint.py` | feed checks, size budgets | ranges | 1,200 |
| `router/tests/test_setup.py`, `test_install.py`, `test_selfupdate.py`, `test_setup_tools.py` | existing test style and helpers (stubs for `df`, `opkg`, `/proc`) | grep, ranges | 3,000 |
| `setup/pisophone_setup.py` | arguments (5430-5470), `--phone-only` (3461 on), web page answers (200-250) | ranges | 2,500 |

## Task groups
Each group names the spec criteria it proves.

- [x] 0. **Owner gate.** Rounds 1 to 5 approved; D1 and D2 accepted (2026-10-22).
- [ ] 1. **Baseline, no code change** (criterion 1). Run every command of criterion 1; write the counts and last lines in `validation.md`. If anything fails, stop and report it.
- [ ] 2. **Does the full install fit?** (criteria 4 and 13, decisions 11 to 13). No product code. For one CPU family (mipsel): build the program, and in a scratch copy a build without the wheel, the game and the coin-box view; for every file of the spec's fact 2 write its size and its estimated size on flash (gzip -6 of each 4 KB piece, as jffs2 stores it), plus the packages' `Installed-Size` from the OpenWrt package index for the owner's release, in a table in `validation.md`. Compare with 6 to 8 MB writable and the 2 MB rule, counting `/root/piso-setup.sh` as deleted (decision 12), and say how much room is left for an update on a low-memory router (decision 13). If the owner has sent the 360 T6GS readings (criterion 8), use them. Say plainly what is estimated. Report to the owner, who decides: groups 5 to 10 run, or the small build is parked (then groups 5 to 10 are dropped, the spec says so, and groups 3, 4, 11 to 13 still run).
- [ ] 3. **`flash_class`, tests first** (criterion 2). In `router/tests/test_setup.py`: one test per expected answer of criterion 2, with fake `/proc/mtd` files and fake sysfs folders through `PISO_MTD_FILE`, `PISO_MTD_SYSFS`, `PISO_OVERLAY_KB`. The overlapping-NOR and the two-chip T6GS cases must be seen failing against a naive version first.
- [ ] 4. **`flash_class` code, the summary and the leftover installer** (criteria 2, 13 and 15). `setup/src/15-flash.sh` with `flash_class` exactly as criterion 2; the setup summary prints its line and the free KB on `/overlay`, and both warnings of criterion 13 (tests with a stubbed `df` and stubbed free memory). The setup deletes `/root/piso-setup.sh` after a successful install and keeps it after a failed one (criterion 15, tests first). Raise `setup/RELEASE` to 1.5.17 here. Regenerate; `build_piso_setup.py --check` passes.
- [ ] 5. **The image choice, tests first** (criterion 14). `ROUTER_IMAGE` read from the environment and `/etc/piso-setup.conf`; every install writes `ROUTER_IMAGE_IN_USE`. Tests for `auto`, `full`, `small` on a small and a large sample, new install and re-run, the forced-`full` space sum with a stubbed `df` and `opkg info`, and the refusal leaving everything unchanged.
- [ ] 6. **The `small` build, tests first** (criteria 5, 6, 9). `small = []` in `Cargo.toml`; `#[cfg(not(feature = "small"))]` on the wheel, the game, the coin-box view, their routes, the console's Rental phones tab and wheel settings, and the buttons on the coin and *You are online* pages. Tests of criteria 5 and 6 on the small build. Both builds pass `cargo fmt --check`, `clippy` and `test`. Add the `--features small` test and clippy to the pisoportal job in `quality.yml`.
- [ ] 7. **No switch with an open spin, tests first** (criterion 3). In the installer, before switching to small: the checks and the message of criterion 3 (tests with a fake data folder: wheel on; a `.spin` with `state=intent`; all `state=done`). The roll tests pass on both builds.
- [ ] 8. **Small programs, installers and feed** (criteria 4, 10). `router-program.yml` also builds `pisoportal-<family>-small` (CI commits them); `build_piso_setup.py` makes `setup/piso-setup-small.sh` and `setup/piso-setup-<family>-small.sh` and their website copies; `publish-router.yml` signs and publishes `router-small.json` and `router-setup-small.sh` per family with the full ones; `verify_release.py` checks both; budgets in `test_footprint.py`; CI prints the sizes. `setup/install.sh` gets its copy of `flash_class` and picks the small installer per criterion 10; `test_install.py` checks the copy matches `setup/src/15-flash.sh`. The CI-built small programs do not exist locally yet: tests use stand-in programs the way the existing tests do.
- [ ] 9. **The update keeps the image, tests first** (criterion 11). `90-updates.sh` picks `router-small.json` / `router-setup-small.sh` when `ROUTER_IMAGE_IN_USE=small`. Tests in `test_selfupdate.py` / `test_setup.py`: the small router fetches only the small entry; the full router's request is unchanged; a missing setting means full; the update never calls `flash_class` or changes the setting.
- [ ] 10. **A switch keeps the money, tests first** (criterion 12). The full -> small and small -> full test with a data folder holding a paid session, a sale, ledger lines and an unused voucher. If it shows a loss, a small fix here; a large one stops for the owner.
- [ ] 11. **The setup program's choices, tests first** (criterion 7). `--product`, `--image`, the web page and `--cli` questions, the `--yes` refusal, `--phone-only` kept. Then `website/start/` and `setup/README.md`. Regenerate `website/pisophone_setup.py`.
- [ ] 12. **Docs and the hardware check** (criterion 8). The new check in `docs/REAL_WORLD_TESTING.md` ("not run"), `docs/ROUTERS.md` (the measured sizes, how small routers are handled), `CHANGELOG.md`, `website/manual.html` (what a small router does not have, how to choose the image).
- [ ] 13. **Validate** (criterion 9). Every criterion in `validation.md` with its evidence, the gates, "Not tested" (at least: all hardware, the T6GS, the jffs2 sizes), "Money and time"; update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` 1.5.16 -> 1.5.17, once, in group 4.
- Firmware, phone app: not touched.
- Publishing: after the owner's order, the merge to `main` makes CI publish full and small at 0%; the owner raises both to 100% (`CLAUDE.md` section 5); `verify_release.py` 0 FAIL each time.
