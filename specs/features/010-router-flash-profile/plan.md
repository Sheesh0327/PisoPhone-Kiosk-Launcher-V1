# Plan: feature 010, fit the full PisoWiFi and a PisoWiFi-only shop

Status: **approved by the owner (2026-10-22, round 7). Not started: no code, and no task group run, until the owner says to start.**

How to work (for the execution model): one task group at a time, in order. Test first: write the test, run it, see it fail, log the red result in `ledger/negative-constraints.md`; then the code; then the test passes. One commit per group, plain English, with the session's trailer lines. Tick the box here. Use only the names in the spec's "Names" table. Generated files are made by `python3 tools/build_piso_setup.py` or by CI, never by hand. If the spec does not say something you need, stop and ask the owner.

Context manifest (constitution rule 1). Unresolved ledger entries loaded: read `ledger/negative-constraints.md` at the start.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/010-router-flash-profile/spec.md` | the contract | all | 6,000 |
| `setup/install.sh` | where the copy is saved (23, 117-160), the factory-address message (60-92) | ranges | 1,500 |
| `setup/src/00-header.sh`, `10-settings.sh` | `CONF`, `SELF_PATH`, Wi-Fi names, `conf_get` / `conf_set` | 1-70 each | 1,500 |
| `setup/src/30-router-settings.sh` | the `kiosk_*` and `box_ap` Wi-Fi sections | 1-90 | 1,200 |
| `setup/src/40-files.sh` | `piso-setup` saved without payload (80-95), `write_coinslot_conf` (105 on) | ranges | 800 |
| `setup/src/50-coin-box.sh` | handing the kiosk name and password to the box (140-175) | ranges | 600 |
| `setup/src/60-summary.sh`, `95-owner-tools.sh` | summary and handout lines | grep, ranges | 800 |
| `setup/src/70-agreement.sh`, `75-checks-stage2.sh` | checks of the kiosk Wi-Fi | 1-40, 1-20 | 800 |
| `setup/src/80-commands.sh`, `90-updates.sh`, `98-setup-flow.sh` | `kiosk-wifi`, hourly sync (97), questions, plan and apply (30-160) | ranges | 2,500 |
| `setup/src/96-space-backup.sh` | `mem_available_kb`, `work_base`, `cmd_space` (1-60) | 60 | 900 |
| `tools/pisoportal/src/console.html`, `console-ui.js`, `console.rs`, `config.rs` | the tab label and where settings are read | grep, ranges | 1,500 |
| `router/tests/test_install.py`, `test_setup.py`, `test_setup_tools.py`, `test_selfupdate.py` | existing test style and stubs | grep, ranges | 3,000 |
| `setup/pisophone_setup.py` | arguments (5430-5470), `--phone-only` (3461 on), web page answers (200-250) | ranges | 2,500 |
| `docs/DEPLOY.md`, `REAL_WORLD_TESTING.md`, `RELEASE.md`, `ROUTERS.md` | the `./piso-setup.sh` instruction | grep | 400 |

## Task groups
Each group names the spec criteria it proves.

- [x] 0. **Owner gate.** Rounds 1 to 7 approved (2026-10-22). Nothing open.
- [x] 1. **Baseline, no code change** (criterion 1). Run every command of criterion 1; counts and last lines in `validation.md`. If anything fails, stop and report it.
- [x] 2. **Does the full install fit?** (criterion 10). No product code. The estimate table of criterion 10 in `validation.md`; use the 360 T6GS readings if the owner has sent them. Report the result to the owner and continue (this feature ships either way).
- [x] 3. **Installer copy in RAM, and none left behind, tests first** (criteria 2, 3). `setup/install.sh` saves to `/tmp` or `/root` by `MemAvailable` (its own copy of the 24000 KB rule, honouring `PISO_MEM_KB` and `PISO_INSTALL_DIR`), with the new messages; the setup deletes the copy it was started from after success (`$0` made absolute). No doc change (fact 4 corrected). This is the first change under `setup/src/`: raise `setup/RELEASE` to 1.5.17 here. Regenerate (`website/install.sh` and the installers); `--check` passes.
- [x] 4. **`flash_class` and the free-space lines, tests first** (criteria 4, 5). `setup/src/15-flash.sh`; the summary and `piso-setup space` lines and both warnings. Regenerate; `--check` passes.
- [x] 5. **A PisoWiFi-only router, tests first** (criterion 7). `SHOP_PRODUCT` read from the environment and `/etc/piso-setup.conf` (missing: `both`), written to both settings files; every place of fact 6 skips the kiosk Wi-Fi when it is `wifi`; the console tab label from `SHOP_PRODUCT` in `/etc/coinslot.conf`. The existing tests pass unchanged for `both` and missing.
- [x] 6. **Switching the product keeps everything else, tests first** (criterion 8). Re-run with `wifi`, with `both`, and without; an update; the data-folder and settings comparisons. If a test shows a loss: a small fix here, a large one stops for the owner.
- [x] 7. **The setup program's product choice, tests first** (criterion 6). `--product`, the web page and `--cli` question, the `--yes` refusal, `--phone-only` kept, the kiosk password only for `both`. Regenerate `website/pisophone_setup.py`.
- [ ] 8. **Docs and the hardware check** (criterion 11). The new check in `docs/REAL_WORLD_TESTING.md` ("not run"), `docs/ROUTERS.md` (sizes, 16 MB routers), `setup/README.md` and `website/manual.html` (the product choice, what a PisoWiFi-only router has), `CHANGELOG.md`.
- [ ] 9. **Validate** (criteria 9, 12). Every criterion in `validation.md` with its evidence, the gates, "Not tested" (at least: all hardware, the T6GS, the jffs2 sizes), "Money and time"; update `specs/STATUS.md`. Pull request into `beta` only when the owner asks.

## Release
- Router: `setup/RELEASE` 1.5.16 -> 1.5.17, once, in group 3 (the program changes only the console's tab label).
- Firmware, phone app: not touched.
- Publishing: after the owner's order, the merge to `main` makes CI publish at 0%; the owner raises it to 100% (`CLAUDE.md` section 5); `verify_release.py` 0 FAIL.
