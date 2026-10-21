# Handoff: feature 005 (spinning wheel), 2026-10-21

Start the next chat with `specs/templates/session-start.md`; last line: `Task: continue from specs/notes/2026-10-21_feature-005-wheel.md`.

## Where things are
- Branch `feat/005-spinning-wheel` (pushed), cut from `beta`. No PR. Nothing on `beta` or `main`. Spec approved by the owner ("Approve", taken as the recommended answers to decisions 1 to 7: wheel off everywhere until the owner confirms; default table 13 lose, 5 x2, 1 x3, 1 x5 of 20; win cap 60 min/day; limits bet 1 to 10, keep 5, 10 spins a day, 10 s wait).
- The owner asked for hidden or adaptive odds and "an illusion of winning". That was **not** built and the spec says why ("Not built"). Do not add it: fixed published odds only, the result never depends on who plays or on earlier results.

## Done (test first, red result logged in `ledger/negative-constraints.md`)
- `tools/pisoportal/src/wheel.rs`: table, exact return, refusal above 100%, multiplier 1 or over 10, draw, bet rules, net change.
- `wheel_gate.rs`: warning version 1, exact phrase `I TAKE RESPONSIBILITY`, record file `wheel-confirm.txt`, `enabled(data_dir, setting)` needs both; log `wheel.log`.
- `wheel_spin.rs`: `spin()` changes only the session's `tl` (minutes bought), once, under the caller's file lock; journal `DATA_DIR/wheel/<id>.spin` (intent, done, void); repeat by id; `recover()`.
- `cargo test` 112 passed (25 new), clippy and fmt clean. Not wired to anything: customers and the console see no change.

## Open (the plan has the order)
- Task 3 limits per day and wait (reuse `game.rs` day index), `win_room`.
- Task 4 routes `/wheel/state` and `/wheel/spin`; `util::random_hex(16)` for `rand`; call `wheel_spin::recover` at start and in `maintenance`; refuse unless `wheel_gate::enabled`.
- Task 5 portal page (odds panel always visible, result in words, no auto-spin, the animation ends on the router's segment). Task 7 console: blocking dialog with the exact warning text from the spec, switch, limits, table; every router starts off; updated routers keep the mini-game. Browser test needs Playwright (`pip install playwright==1.63.0`; Chromium in /opt/pw-browsers).
- Task 8 docs, `CHANGELOG.md`, hardware check (next RS number), `setup/RELEASE` raised once. Task 9 PR into `beta` when the owner asks.
- Owner: a lawyer should read the warning (roadmap 8.3) before anyone turns the wheel on.

## Slow tests
`python3 tools/pisoportal/tests/test_flow.py` needs `PISOPORTAL=<release binary>` (`cargo build --release`); about 2 minutes.
