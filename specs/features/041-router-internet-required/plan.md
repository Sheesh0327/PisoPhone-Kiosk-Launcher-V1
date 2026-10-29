# Plan: feature 041, require internet on the router

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | how to read |
|---|---|---|
| `setup/src/21-local-packages.sh` | the offline path | 55 lines, read in full — it is deleted |
| `setup/src/20-preflight.sh` | the 6 call sites | read only the lines a fresh grep returns |
| `setup/pisophone_setup.py` | 23 refs across the bundle prep, SSH copy and cleanup | **6000+ lines — grep only, never read whole** |
| `router/tests/test_pisophone_setup.py` | 38 refs, the largest block | grep, then read the offline test bodies only |
| `router/tests/test_setup.py` | 14 refs | same |

## Task groups
- [ ] **0. Confirm the decision and the scope.** Re-read `specs/spec_03_overhaul.md` section 6, feature 041, including the
      recorded consequence. Confirm part B of feature 011 is **not** in scope. (no commit)
- [ ] **1. Record the before state.** Run every test in criterion 2 and 3 and record which pass. Record the installer size
      (`setup/piso-setup.sh`) against the 1700 KB budget. Put these in `validation.md` under "before".
- [ ] **2. The shell side.** Delete `setup/src/21-local-packages.sh`; clean `20-preflight.sh` and `98-setup-flow.sh`; check
      the build list in `tools/build_piso_setup.py`. Keep it BusyBox-`ash`-safe and ShellCheck-clean.
- [ ] **3. The Python side.** Remove `--router-online`, `self.router_online`, the bundle preparation, the SSH copy and the
      cleanup from `setup/pisophone_setup.py`. Standard library only. This is the group where it is easiest to leave a dead
      branch behind — grep again when you think you are finished.
- [ ] **4. The tests.** Remove the offline tests from `test_setup.py` and `test_pisophone_setup.py`. **Add** the two new ones:
      a first setup with internet succeeds (criterion 5), and one without fails with a clear named message (criterion 6).
      Test first for the new pair: write them, see them fail, log the red result in `ledger/`, then make them pass.
- [ ] **5. Say it up front.** `website/start/shop.html` and `index.html`, `docs/ROUTERS.md`, `docs/DEPLOY.md`. Criterion 7.
      This group is the mitigation the owner accepted the consequence on — do not skip it or do it thinly.
- [ ] **6. The record.** One line in `specs/features/011-offline-setup/validation.md` (do not delete the file), RS36 retired
      with a reason in `docs/REAL_WORLD_TESTING.md` (do not delete the row), `CHANGELOG.md` and `specs/RELEASE_STATE.md`.
- [ ] **7. Gates and release.** Criteria 1-10. `bash scripts/check_security_rules.sh`,
      `python3 tools/build_piso_setup.py --check`, raise `setup/RELEASE`. Put the "after" numbers beside the "before" ones.
      **Do not run a rollout**: this ships with 033, 034 and 035 as one release.

## Release
Router release, **batched** with 033, 034 and 035 (epic D14). Raise `setup/RELEASE`; the rollout is a separate owner step
after all four are merged. Generated files are rebuilt by `tools/build_piso_setup.py` and CI, never by hand.

## Hardware check
**RS36 is retired** by this feature (it tested the offline path). Mark it removed in `docs/REAL_WORLD_TESTING.md` with a
one-line reason and the release number; do not delete the row. **RS37 stays** — it tests part B, which is not touched.
A new **RS57** (RS55 was already taken by feature 031; this file has no RS36 row, so a RETIRED RS36 line is added): a first setup on a real router with internet, using only the box page's one-liner and no computer.
