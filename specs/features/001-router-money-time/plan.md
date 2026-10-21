# Plan: router money and time

Context manifest (constitution rule 1). Lines on the prior branch; `beta` is a little shorter.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/core.rs` | window, clock check, recovery, tick | ~1725 | read ranges only |
| `tools/pisoportal/src/ledger.rs` | revenue ledger rows and verify | ~254 | ~2.5k |
| `tools/pisoportal/src/roll.rs` | session roll, shift, purge | ~578 | ~6k |
| `tools/pisoportal/src/voucher.rs` | voucher files and key | ~670 | ~7k |
| `tools/pisoportal/src/util.rs` | write helper | ~450 | ~4k |
| `tools/pisoportal/src/fair.rs`, `main.rs`, `config.rs`, `game.rs`, `reconcile.rs` | small call-site changes | small | ~2k |
| `tools/pisoportal/tests/test_flow.py` | end-to-end checks | ~ | add ~52 lines |

Unresolved ledger entries: none. Source of the patch: `git show e827c98` and `66a4ad6` on `claude/sleepy-gauss-rn31gl`. Do not read the generated `setup/piso-setup*.sh`.

## Task groups (test first; each ends with the red result logged in `ledger/negative-constraints.md`)

- [ ] 1. **Base.** Decide owner question 1. For (a): list the lines of `4b6312d` in `core.rs`, `ledger.rs`, `boxlink.rs`, `console.rs`, `reconcile.rs` that the RT patch touches or relies on, port only those.
- [ ] 2. **RT-05, RT-06, RT-14**: `util::write_atomic` (write, fsync, rename, fsync dir); move every writer to it; voucher key never replaced. Tests: `rt05_*`, `rt06_*`, `rt14_no_other_file_renames_a_file_itself`.
- [ ] 3. **RT-04**: ledger row written whole with one write; torn tail skipped and reported, not "LEDGER WAS CHANGED". Tests: four `rt04_*`.
- [ ] 4. **RT-01, RT-02, RT-03**: clock check first in `tick()` and `reconnect`; only `purge` deletes; shift only the stale period (`clockjump` record); vouchers move with the clock. Tests: `rt01_*`, `rt02_*`, `rt03_*`.
- [ ] 5. **RT-07**: late-coin window id `{wid}L{boxed}`. Test `rt07_*` and the two-late-rounds check in `test_flow.py`.
- [ ] 6. **Docs and release**: raise `setup/RELEASE`, run `build_piso_setup.py`, `CHANGELOG.md`, hardware check RS32.

## Release
Router only. `setup/RELEASE` raised once (next after the live 1.5.12). Regenerate `setup/piso-setup*.sh`, `website/setup/*`; CI rebuilds `tools/pisoportal/bin/*`. No merge to `main` unless the owner asks.
