# Plan: 016 Router hardening rows

Unresolved ledger entries loaded: none for these rows.
| file | purpose | lines | est_tokens |
|---|---|---|---|
| `git show 74c39e2` (source files only) | the prior work | ~250 | 4,000 |
| `router/piso_isolation.sh` | RT-10, RT-18 | 24 changed | 600 |
| `tools/pisoportal/src/util.rs` | RT-08, RT-26 | +159 | 2,500 |
| `setup/src/96-space-backup.sh` | RT-22 | 10 | 300 |

## Task groups
- [x] 1. Apply the source and test changes of `74c39e2` (not its generated files, changelog or specs) on top of feature 015.
- [ ] 2. Run the router gates; regenerate with `tools/build_piso_setup.py`; report sizes.
- [ ] 3. Open the pull request into `beta`; fix CI until green.

## Release
Router 1.5.19 (raised by 015).
