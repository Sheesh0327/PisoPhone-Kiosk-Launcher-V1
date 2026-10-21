# Validation: router money and time (feature 001)

Branch `feat/001-router-money-time`. Patch source: `e827c98` + `66a4ad6` (branch `claude/sleepy-gauss-rn31gl`), applied to the new `beta` (`013cbef`).

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Each row has a test that fails before and passes after | `cargo test` (87 pass). Red: the original red run is in the ledger of the old branch (15 tests failed on assertions). Re-checked here by mutation: RT-01 (clock check removed from the tick) and RT-07 (late id back to `{wid}L`) | pass | `rt01_rt02_the_fair_use_tick...` FAILED when mutated; `rt07_...` FAILED left `w9L` right `w9L3`; both pass restored. The tests do not exist on `beta` at all (it has no `clockjump`, no torn-tail code). Not mutated one by one: RT-03, 04, 05, 06, 14 |
| 2 | `cargo test`, `test_flow.py` | `cargo test`; `PISOPORTAL=<release> python3 tests/test_flow.py` | pass | 87 passed, 0 failed; 202 checks, 0 failures |
| 3 | No `fs::rename` outside `util.rs` | test `rt14_no_other_file_renames_a_file_itself` | pass | in the 87 |
| 4 | Generated files and security rules; `setup/RELEASE` raised once | `build_piso_setup.py --check`; `check_security_rules.sh` | pass | "up to date"; "all passed". `setup/RELEASE` 1.5.12 -> 1.5.13 |
| 5 | Router setup tests unchanged | `test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` | pass | 288, 164, 103, 67 checks, 0 failures |
| 6 | Hardware check RS32 added, not run | `docs/REAL_WORLD_TESTING.md` | added | marked "not tried yet" |
| 7 | `CHANGELOG.md` in owner terms | `CHANGELOG.md` "Router 1.5.13" | added | |

## Gates
- [x] `cargo fmt --check`, `cargo clippy --all-targets` (0 warnings)
- [x] `bash scripts/check_security_rules.sh`
- [x] Generated files regenerated, `--check` passes
- [ ] Python tools: no Python tool code changed

## Not tested
- Nothing has run on a real router. `tools/pisoportal/bin/*` are built by CI after the push (router-program workflow), not here.
- CI "Code quality" has not run on this branch yet.
- RT-03, 04, 05, 06, 14 were not individually mutated to see them fail; their red runs are in the old branch's ledger.

## Money and time
Touches credit, expiry and the ledger: sessions are no longer deleted before the clock shift, shift covers only the stale period, vouchers and revenue rows move with it, ledger rows are written whole, late-coin rounds get distinct window ids. Checked by the unit tests above and 202 end-to-end checks, including two late-coin rounds and a torn ledger tail.
