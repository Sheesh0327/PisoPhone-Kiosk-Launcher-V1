# Feature 034: remove the mini-game

Status: **approved by the owner 2026-10-28 (epic D2 = remove). Starts only after 033 is merged.**
Branch: `feat/034-remove-minigame`   Area: router   Epic: `specs/spec_03_overhaul.md` feature 034   Release: router

## Intent
The free mini-game (`/game`) was meant to be replaced by the spinning wheel, and feature 005's own notes record removing it as
agreed by the owner and never done. With the wheel gone (feature 033) it is the last of the two: `game.rs` + `game.html`
(~18 KB of source), 50 references, and two browser test files, for a feature nobody has asked for. Removing it finishes the
clean-up the wheel work started and leaves the customer page doing one thing: selling time.

## Invariants touched
- **Money and time:** the mini-game never touched money. `/game/tick` counts play minutes per device per day against
  `GAME_MINUTES` (a soft limit) and writes nothing to `revenue.csv` or the roll's paid time. Removing it **cannot change any
  coin total, any paid minute, or `reconcile`**. Proved by criterion 10.
- **Only owner-signed software:** not touched.

## Exact scope — the files
**Delete outright (3):**
```
tools/pisoportal/src/game.rs
tools/pisoportal/src/game.html
tools/pisoportal/tests/test_game_browser.py
```

**Edit (the 50 references). Line numbers are from `main` at `9a92c33` and assume 033 has landed — re-grep first:**

| file | what is there | what to do |
|---|---|---|
| `src/main.rs` | `mod game;` | delete the line |
| `src/config.rs` (5 refs) | `game_minutes` field, `num("GAME_MINUTES", 10).min(game::MAX_MINUTES)` at ~line 192, and any test | delete the field, the parse and the test |
| `src/core.rs` (19 refs) | the `games` member, its construction, `self.games.left(mac, now)` in the views, `games.today(now)` | delete the member and every use. **The `gm` view field goes away entirely** — after 033 it is a plain `self.games.left(...)`; now there is nothing to report |
| `src/http.rs` (17 refs) | the `/game`, `/game/tick` routes and the two `if !games.enabled()` guards (which 033 left with their first half only) | delete the routes **and the guards**, which now guard nothing |
| `src/console.rs` (7 refs) | the Settings entry for `GAME_MINUTES` | delete |
| `src/util.rs` (1 ref) | one helper reference | re-grep and remove if it is only used by the game; **keep it if anything else uses it** |

`src/wheel_limits.rs` also referenced the game, but that file is deleted by 033. If it is still present, **stop: 033 has not
landed and this feature is out of order.**

**Also update (do not delete):**
```
tools/pisoportal/tests/test_console.py       (the GAME_MINUTES setting is gone)
tools/pisoportal/tests/test_flow.py          (any game step)
router/tests/test_footprint.py               (size expectations: smaller again)
docs/ARCHITECTURE.md                         (section 5 table row for game.rs)
specs/RELEASE_STATE.md, CHANGELOG.md         (one owner-readable line each)
```

## The customer page after this
The `gm` field disappears from the view JSON. **Check `page.html` for anything that reads it** (grep for `gm`), and remove the
mini-game button and any "you have N minutes of game left" text. The page must render correctly in every state — idle,
starting, armed, closing, final, paused — with no empty gap or dead button where the game was. This is criterion 6 and it is
the one most likely to be missed, because the Rust side will compile perfectly while the page shows a hole.

## Routers that have it on
`GAME_MINUTES` defaults to 10, so the mini-game is **on by default on every router** — unlike the wheel. That makes this a
visible change for customers:
- `/game` and `/game/tick` return 404 after the update.
- A customer mid-game loses nothing: the game never held money or paid time.
- `CHANGELOG.md` must say plainly that the free game is gone, because an owner may be asked about it.
- Any `GAME_MINUTES` line already in a router's `settings.txt` is **left in place and ignored**, not deleted. Removing settings
  from an owner's file is not ours to do, and an unknown key is harmless.

## Constraints
- A change under `tools/pisoportal/` raises `setup/RELEASE`.
- Generated files are never hand-edited; `python3 tools/build_piso_setup.py --check` must pass.
- Do not reformat files you are not otherwise changing. Do not tidy `core.rs` beyond the game members.

## Success criteria
1. `rg -n "game|GAME_" tools/pisoportal/src/ router/ setup/src/` returns nothing but unrelated words (check each hit by hand;
   "game" appears inside other words). Documentation and `CHANGELOG.md` may still mention it historically.
2. `cd tools/pisoportal && cargo test` passes; no game tests remain and none is `#[ignore]`d.
3. `cd tools/pisoportal && python3 tests/test_flow.py` passes.
4. `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` pass.
5. `tests/test_console.py` passes with no `GAME_MINUTES` setting, and `GET /game` returns 404.
6. The customer page renders correctly in all six states with no game button and no gap. Prove it with the browser test or a
   new assertion, and **say which states you actually checked**.
7. `router/tests/test_footprint.py` passes; record the `pisoportal-mipsel` size before and after.
8. `bash scripts/check_security_rules.sh` passes.
9. `python3 tools/build_piso_setup.py --check` passes; `setup/RELEASE` raised.
10. **Money unchanged:** `reconcile` and the ledger total byte-identical for a fixture ledger before and after. Say how you
    checked it (`CLAUDE.md` section 6).
11. A fixture `settings.txt` containing `GAME_MINUTES=10` is left byte-identical by the update.

## Out of scope
- Anything the wheel removal (033) should have done. If you find a wheel leftover, **report it, do not fix it here** — it means
  033's criterion 1 was not met and that feature needs a follow-up.
- Replacing the game with anything. The customer page is meant to get simpler, not different.

## Owner decisions
All answered. **D2 = remove** (2026-10-28); the owner accepts that this is visible to customers, since the game is on by
default. **D14**: 033, 034 and 035 go out as **one** router release — raise `setup/RELEASE`, but do not run a rollout here.
