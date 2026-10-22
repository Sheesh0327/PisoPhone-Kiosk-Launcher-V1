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
- [x] 1. **Odds and rules (pure code, `wheel.rs`)**: the table, its exact average return, refusal above 100%, bet rules, the draw from a random source passed in (so tests can seed it). Tests first.
- [x] 2. **Time change and journal**: one function that applies a spin to a session under the lock; spin id idempotence; write-ahead journal; power-cut tests at both points. Tests first.
- [x] 3. **Limits** (`wheel_limits.rs`, 8 tests): spins per device per day, the wait between spins, the daily win cap, reset at local midnight (`game::day_index`). A spin is reserved before it is made and given back if refused.
- [x] 4. **Routes** (`wheel_run.rs`, `http.rs`, console `/api/wheel`; `test_wheel.py`): `/wheel/state` (odds, limits, time left, spins left) and `/wheel/spin` (POST with a spin id). Refuse paused, expired, unpaid.
- [x] 5. **Portal page** (`wheel.html`, `test_wheel_browser.py`): `wheel.html` replacing `game.html` when the wheel is on; odds panel always visible; animation ends on the router's segment; result in words; no auto-spin. Browser test.
- [x] 6. **Responsibility gate (router side), test first**: the confirmation record (file written only by the turn-on request), exact phrase and warning-version check, refusal messages, off by hand-edited setting without a record, record cleared on turn off and on a new warning version, log rows. Tests in `cargo test` and `test_flow.py`.
- [x] 7. **Console and defaults** (`console.html`, `console-ui.js` v9, `/api/wheel`): the blocking warning dialog (the switch stays disabled until the exact phrase is typed; browser test), limits, table (with the refusal and the warning); every router, new or updated, starts with the wheel off; updated routers keep the mini-game; wheel-spin log file separate from `revenue.csv`; owner sees net minutes given away and "Wheel on since ..., responsibility confirmed".
- [x] 8. **Docs and release** (`setup/RELEASE` 1.5.14, CHANGELOG, manual, RS34, `validation.md`): raise `setup/RELEASE`, `build_piso_setup.py`, `CHANGELOG.md` in owner terms, hardware check (not run), `validation.md`.
- [ ] 9. **Pull request into `beta`** (when the owner asks), read Code quality. Merge and rollout are the owner's.

## Release
Router only (`setup/RELEASE` next number after 1.5.13). Every router (new or updated): wheel **off**. Nothing reaches shops' customers until the owner has read the responsibility warning, typed the confirmation and switched it on.

## Progress (2026-10-21)
Done and committed, all test first with the red result in the ledger: task 1 (`wheel.rs`: odds table, 9 tests), task 6 (`wheel_gate.rs`: responsibility gate record, exact phrase and version, 6 tests), task 2 (`wheel_spin.rs`: one spin applied once, journal, repeat by id, power cut at both points, void on a changed session, win cap, wheel log, 10 tests). `cargo test` 112 passed, clippy clean, `fmt --check` clean. The modules are not wired to anything: no route, page, setting or console switch exists, so **nothing changes for any router or customer yet**.
Left: task 3 (spins per day per device, the wait, local midnight, `win_room` from the day's wins: reuse `game.rs` day logic), task 4 (routes `/wheel/state`, `/wheel/spin`; random number from `util::random_hex`; hold the `files` lock; call `wheel_spin::recover` at start and in maintenance; refuse unless `wheel_gate::enabled`), task 5 (portal page), task 7 (console: blocking warning dialog, switch, limits, table; defaults: every router off), task 8 (docs, hardware check, `setup/RELEASE`), task 9 (PR when the owner asks).
Design note for task 4: the confirmation must be asked of the console through its own login (the console already has a session), not the customer portal; the `on` request carries `phrase` and `version`; the setting alone must never enable (`wheel_gate::enabled(data_dir, setting)`).

Update 2026-10-21 (later): task 3 done and committed (`cargo test` 120 passed, clippy and fmt clean). Still not wired to anything. For task 4: make `Games::today` public (or add a small `pub fn local_day`) so the routes get the day number; add `wheel_spin::seen(data_dir, id)` so a repeated spin id is answered from the journal *before* `Days::reserve` (a retry inside the 10 s wait must get its old result, not "wait").

Update 2026-10-21 (end): tasks 4, 5, 7 and 8 done on the computer (routes, wheel page, console switch with the blocking warning, limits and table, docs, `setup/RELEASE` 1.5.14). Not run: anything on a real router (RS34), the legal review of the warning text. Task 9 (pull request) waits for the owner. The merge to `main` was held by the owner (option A): this release goes to `main` as one router release 1.5.14, which publishes at 0% and is widened only by the owner.
