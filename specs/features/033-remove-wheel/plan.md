# Plan: feature 033, remove the spinning wheel

Context manifest (constitution rule 1). Unresolved ledger entries loaded: **load them first** (`ledger/negative-constraints.md`, rule 6).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/core.rs` | the wheel member, the two view fields, the restart recover call | 1794 (read only the lines in the spec table) | ~2k |
| `tools/pisoportal/src/http.rs` | the `/wheel*` routes and `wheel_page` | 916 (lines in the table only) | ~1k |
| `tools/pisoportal/src/console.rs` | `/api/wheel` routes | 898 (lines 645-710 only) | ~0.5k |
| `tools/pisoportal/src/config.rs` | the `wheel` switch and its test | lines 45-55, 190-200, 300-315 | ~0.5k |
| `tools/pisoportal/src/main.rs` | the 5 `mod` lines | 30-34 | tiny |
| `website/console/ui.js` | the console's wheel panel | grep only | - |

**Read nothing else.** The 5 wheel source files are deleted, not read. Do not open `page.html` or `console.html` beyond a grep.

## Task groups
Deletion work has no "see the test fail" step in the usual sense, so the red result is different here: **group 1 records the
before state**, and every later group is checked against it. Log the before/after in `ledger/` as rule 6 asks.

- [ ] **0. Confirm the finding still holds.** Re-read `specs/spec_03_overhaul.md` section 2. Check in the code that the wheel is
      still present and that `config.rs` still defaults `WHEEL` off. If anything has changed, stop and report.
      Confirm the owner answered D1 = remove. (files: none; no commit)
- [ ] **1. Record the before state.** Run the full gate set (spec criteria 2,3,4,10) and record: every test name that passes,
      the byte size of `tools/pisoportal/bin/pisoportal-mipsel`, and the `reconcile` + ledger-total output for a fixture ledger
      (criterion 12). Write these into `validation.md` under "before". **Commit nothing yet.**
- [ ] **2. Delete the leaf files and their tests.** The 6 source files and the 5 test files from the spec. The build will not
      compile after this; that is expected and group 3 fixes it. (one commit with group 3 if you prefer a compiling history —
      your choice, but say which you did)
- [ ] **3. Remove the references, file by file, in this order:** `main.rs` (the 5 `mod` lines) → `config.rs` → `console.rs` →
      `http.rs` → `core.rs`. Follow the spec's table exactly. Two traps, both written out in the spec: `gm:` in `core.rs:454`
      becomes a plain `self.games.left(mac, now)`, and the `games.enabled()` guards in `http.rs` keep their first half.
      `cargo build` must pass at the end of this group. (test: `cargo test`)
- [ ] **4. The safe-update path.** When `wheel-confirm.txt` exists in `DATA_DIR`, write one line to `alerts.txt` and leave every
      wheel data file alone. Criterion 13. Test first: write the test, see it fail, log it, then fix.
- [ ] **5. Tests and fixtures that mention the wheel but are not deleted.** `test_soak.py`, `test_footprint.py`,
      `test_console.py` (criterion 9), and the customer-page assertions (criterion 8). Keep the `WHEEL=` assertion (criterion 7).
- [ ] **6. The pages and the docs.** `website/console/ui.js`, `website/manual.html`, `docs/ARCHITECTURE.md`, `docs/ROUTERS.md`,
      `docs/REAL_WORLD_TESTING.md` (RS34 marked removed, not deleted), `specs/RELEASE_STATE.md`, `CHANGELOG.md`.
- [ ] **7. Gates and release.** All of spec criteria 1-13. Raise `setup/RELEASE`. Run
      `python3 tools/build_piso_setup.py --check` and `bash scripts/check_security_rules.sh`. Record the "after" numbers in
      `validation.md` next to the "before" ones from group 1.

## Release
`setup/RELEASE` raised (router release). No firmware, no app. Generated files rebuilt by CI (`tools/pisoportal/bin/*`) and by
`tools/build_piso_setup.py` (`setup/piso-setup*.sh`, `website/setup/*`, `website/install.sh`) — **never by hand**.

## Hardware check
**RS34 is retired by this feature** (it tested the wheel). Mark it removed in `docs/REAL_WORLD_TESTING.md` with a one-line
reason and the release number; do not delete the row, so the log stays readable. No new hardware check is owed: this feature
removes a code path that was never on.
