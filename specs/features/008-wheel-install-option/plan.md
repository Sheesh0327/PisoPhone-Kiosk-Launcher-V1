# Plan: the wheel as an install option (routers with more than 16 MB of flash)

Context manifest (rule 1): read line ranges only. Unresolved ledger entries loaded: none for this feature.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `setup/src/20-preflight.sh` | where hardware is checked (add the flash measurement) | ranges | ~1k |
| `setup/src/96-space-backup.sh` | existing space and memory helpers (`df /overlay`, `PISO_LOW_MEM_KB` test hook) | ~60 | ~1k |
| `setup/src/98-setup-flow.sh`, `setup/src/60-summary.sh` | the setup flow and summary (ask, show) | ranges | ~2k |
| `setup/src/80-commands.sh`, `setup/src/90-updates.sh` | `piso-setup` commands (`wheel`), update step | ranges | ~2k |
| `setup/src/10-settings.sh` | `conf_set` (settings file) | ~70 | ~1k |
| `setup/pisophone_setup.py` | the computer program's router step (question, passing the answer) | ranges (`grep -n` for the router step) | ~3k |
| `tools/pisoportal/src/wheel_run.rs`, `config.rs`, `console.rs`, `http.rs`, `console-ui.js` | `WHEEL_ALLOWED`: off whatever else is set, no card, no button, 404 | ranges | ~3k |
| `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py` | tests to extend | ranges | ~3k |
| `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `website/manual.html`, `setup/RELEASE` | docs and release | ranges | ~1k |

## Task groups
Small, ordered, test first (red result in `ledger/negative-constraints.md`). Start after 006 and 007 are merged to `beta` (same release train), or earlier if the owner prefers.

- [ ] 1. **Flash measurement** (`sh` function in the installer; sample `/proc/mtd` files): large / small / unknown rules, `PISO_MTD_FILE` hook. Tests first (criterion 1).
- [ ] 2. **The router program honours `WHEEL_ALLOWED`** (`wheel_run.rs`, console JSON, button, routes): off whatever else is set; missing key = as today (or per question 4). `cargo test` and `test_wheel.py` first (criterion 3).
- [ ] 3. **The installer asks and stores** (`98-setup-flow.sh`, summary, non-interactive answers): large asks, small does not and writes `no`. `router/tests/test_setup.py` first (criterion 2).
- [ ] 4. **`piso-setup wheel`** (status / allow / deny, and `--force` only if the owner wants it) and the update rule (criterion 5).
- [ ] 5. **The computer setup program's router step** shows the question for a large router only and passes the answer (web and text) (criterion 4).
- [ ] 6. **Docs and release**: `setup/RELEASE`, `build_piso_setup.py`, CHANGELOG, manual, hardware check, `validation.md`.
- [ ] 7. **Pull request into `beta`** (when the owner asks), read Code quality. Merge and rollout are the owner's.
(If the owner chooses two builds, question 1 option (b), this plan is replaced by a larger one: Cargo feature `wheel`, the build matrix, two feeds and installers, `verify_release.py`. That is its own spec.)

## Review questions (asked 2026-10-22; answers go into `spec.md` before approval)
1. What does "add the wheel" mean: (a) one program, the installer decides if the wheel is allowed (no flash saved, ~64 KB stays); or (b) two builds, lite and full (real saving, much bigger release process)?
2. How is "storage" measured: the flash chip size from `/proc/mtd` (recommended), or the free space left on the router?
3. A router with 16 MB or less: never offer it, or hide it but allow `piso-setup wheel allow --force`?
4. Routers that already updated to 1.5.14 or 1.5.15: leave their wheel as it is until they choose, or apply the rule at their next update?
5. On a large router, does the installer ask "Add the spinning wheel? [Y/n]" with Yes as the default, or No as the default?

## Release
Router (`setup/RELEASE` next number after 007's) and the setup program on the computer (no release of its own). Generated files are regenerated (`build_piso_setup.py`), never edited by hand.
