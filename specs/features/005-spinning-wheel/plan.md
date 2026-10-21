# Plan: spinning wheel

Context manifest (rule 1): read line ranges only.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/game.rs` | the mini-game: per-device daily allowance, day index, tick cap (model for limits) | 254 | ~3k |
| `tools/pisoportal/src/game.html` | the mini-game page | 74 | ~1k |
| `tools/pisoportal/src/http.rs` | routes `/game`, `/game/tick` (lines ~600-650) | read range | ~1k |
| `tools/pisoportal/src/roll.rs` | sessions: time left, plan, `session`, add/remove minutes (lines ~250-300, shift/purge) | ~580 | ~3k for ranges |
| `tools/pisoportal/src/core.rs` | file lock `files`, game minutes settings (`set_game_minutes`, ~265-275) | big | ranges only |
| `tools/pisoportal/src/config.rs`, `console.rs`, `console-ui.js` | settings and the console switch | ranges | ~2k |
| `tools/pisoportal/tests/test_flow.py`, `test_game_browser.py` | end-to-end and browser tests | ranges | ~2k |
| `setup/src/*.sh` | install defaults (new install on, update keeps off) | find with `grep -n GAME_MINUTES` | ~1k |

Unresolved ledger entries: none. Playwright and Chromium are needed for the browser test (installed in earlier sessions; CI has them).

## Task groups (test first; red result logged in `ledger/negative-constraints.md`)
- [ ] 1. **Odds and rules (pure code, `wheel.rs`)**: the table, its exact average return, refusal above 100%, bet rules, the draw from a random source passed in (so tests can seed it). Tests first.
- [ ] 2. **Time change and journal**: one function that applies a spin to a session under the lock; spin id idempotence; write-ahead journal; power-cut tests at both points. Tests first.
- [ ] 3. **Limits**: spins per device per day, the wait between spins, reset at local midnight (reuse `game.rs` day logic).
- [ ] 4. **Routes**: `/wheel/state` (odds, limits, time left, spins left) and `/wheel/spin` (POST with a spin id). Refuse paused, expired, unpaid.
- [ ] 5. **Portal page**: `wheel.html` replacing `game.html` when the wheel is on; odds panel always visible; animation ends on the router's segment; result in words; no auto-spin. Browser test.
- [ ] 6. **Responsibility gate (router side), test first**: the confirmation record (file written only by the turn-on request), exact phrase and warning-version check, refusal messages, off by hand-edited setting without a record, record cleared on turn off and on a new warning version, log rows. Tests in `cargo test` and `test_flow.py`.
- [ ] 7. **Console and defaults**: the blocking warning dialog (the switch stays disabled until the exact phrase is typed; browser test), limits, table (with the refusal and the warning); every router, new or updated, starts with the wheel off; updated routers keep the mini-game; wheel-spin log file separate from `revenue.csv`; owner sees net minutes given away and "Wheel on since ..., responsibility confirmed".
- [ ] 8. **Docs and release**: raise `setup/RELEASE`, `build_piso_setup.py`, `CHANGELOG.md` in owner terms, hardware check (not run), `validation.md`.
- [ ] 9. **Pull request into `beta`** (when the owner asks), read Code quality. Merge and rollout are the owner's.

## Release
Router only (`setup/RELEASE` next number after 1.5.13). Every router (new or updated): wheel **off**. Nothing reaches shops' customers until the owner has read the responsibility warning, typed the confirmation and switched it on.
