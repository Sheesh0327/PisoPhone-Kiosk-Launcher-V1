# Plan: feature 034, remove the mini-game

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | lines to read |
|---|---|---|
| `tools/pisoportal/src/core.rs` | the `games` member and the view fields | only the lines a fresh `grep -n "game" src/core.rs` returns |
| `tools/pisoportal/src/http.rs` | the `/game` routes and the two guards | same, by grep |
| `tools/pisoportal/src/config.rs` | `game_minutes` | lines around the `num("GAME_MINUTES", ...)` hit |
| `tools/pisoportal/src/console.rs` | the Settings entry | by grep |
| `tools/pisoportal/src/page.html` | anything reading `gm` | **grep only**, 26 KB, do not read whole |

Read nothing else. `game.rs` and `game.html` are deleted, not read.

## Task groups
- [ ] **0. Check the order.** `tools/pisoportal/src/wheel*.rs` must not exist. If any does, stop: feature 033 has not landed.
      Confirm the owner answered D2 = remove. (no commit)
- [ ] **1. Record the before state.** Full gate set, the `pisoportal-mipsel` byte size, and the fixture `reconcile` + ledger
      total (criterion 10). Write them into `validation.md` under "before".
- [ ] **2. Delete `game.rs`, `game.html`, `test_game_browser.py`.**
- [ ] **3. Remove the references** in this order: `main.rs` → `config.rs` → `console.rs` → `http.rs` (routes **and** the two
      now-empty guards) → `core.rs` (the `games` member and the `gm` view field) → `util.rs` (only if the helper is unused
      elsewhere — check before deleting). `cargo build` passes at the end of this group.
- [ ] **4. The customer page.** Grep `page.html` for `gm` and for the game button; remove them. Check all six states render.
      Criterion 6 — this is the group that is easy to get wrong, because Rust compiles fine while the page has a hole.
- [ ] **5. Settings are left alone.** A `GAME_MINUTES=10` line in a fixture `settings.txt` survives the update untouched.
      Criterion 11. Test first: write it, see it fail if it does, log it, fix.
- [ ] **6. Tests, docs and the changelog.** `test_console.py`, `test_flow.py`, `test_footprint.py`, `docs/ARCHITECTURE.md`,
      `specs/RELEASE_STATE.md`, `CHANGELOG.md` (say plainly that the free game is gone — an owner will be asked).
- [ ] **7. Gates and release.** Criteria 1-11, `setup/RELEASE` raised, `build_piso_setup.py --check`,
      `check_security_rules.sh`. Put the "after" numbers beside the "before" ones.

## Release
Router release. No firmware, no app. Generated files rebuilt by CI and `tools/build_piso_setup.py`, never by hand.

## Hardware check
No new hardware check. This removes a code path that never touched money or paid time. Note in `validation.md` that nothing
here is `verified-on-hardware` and that nothing needs to be.
