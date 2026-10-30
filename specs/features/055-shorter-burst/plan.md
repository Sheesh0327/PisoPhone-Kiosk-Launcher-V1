# Plan: feature 055, a shorter bursting window

Context manifest (constitution rule 1): unresolved ledger entries loaded: none for the installer's openNDS settings.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `setup/src/30-router-settings.sh` (lines 184 to 255) | `portal_uci`: the openNDS settings the installer writes | read by range | 900 |
| `setup/src/80-commands.sh` (around line 235) | the command that re-applies `portal_uci` | read by range | 400 |
| `router/tests/test_setup.py` | tests of the generated settings | 480 checks | read by range |

## Task groups
- [ ] 0. (skipped: the owner chose to build directly) Optional, the owner's router, before any release: set `ratecheckwindow` to 1 by hand, restart openNDS when nobody is online, check `ndsctl status` and a speed test (commands in the answer); tells us openNDS accepts 1 (files: none; red result logged: n/a)
- [x] 1. Test first, then the one-line change, regenerate the setup files, run all router and setup tests (files: `router/tests/test_setup.py`, `setup/src/30-router-settings.sh`, generated files; red result logged: yes)
- [x] 2. Validate: `validation.md`, `CHANGELOG.md`, hardware check RS65, `specs/STATUS.md`
- [ ] 3. Release (only when the owner asks): raise `setup/RELEASE` to 1.5.34, `beta` route as for 1.5.33; Code quality green on the code commit (run `cargo fmt --check`, clippy and the wide-font browser tests locally first, as the 053 failures taught); decode the signed program; the owner raises the rollout after RS65

## Release
Router release 1.5.34 through `beta`. The portal program does not change; CI still rebuilds the programs on `beta`, and the signed file carries the new installer settings.
