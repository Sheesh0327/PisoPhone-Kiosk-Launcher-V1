# Feature 033: remove the spinning wheel

Status: **approved by the owner 2026-10-28 (epic D1 = remove). Starts only after feature 032 is merged (epic D13).**
Branch: `feat/033-remove-wheel`   Area: router
Epic: `specs/spec_03_overhaul.md` feature 033   Release: router

## Intent
The spinning wheel is **off on every router**, has **never run on real hardware** (RS34 in `docs/REAL_WORLD_TESTING.md` was never
run), and its responsibility-warning text has **never had a legal review** (roadmap 8.3). In the Philippines a paid spin for a
prize sits close to gambling regulation. It costs 5 Rust files plus `wheel.html` (~99 KB of source), 57 references in other
files, and 5 test files. Removing it deletes the largest block of unproven complexity in the product and makes the legal
question moot. It can be restored from git history if a market for it ever appears.

## Invariants touched
- **Money and time:** the wheel never wrote to `revenue.csv` — it wrote to `wheel.log` and changed session time on the roll. So
  removing it **cannot change any revenue figure, any coin total, or `reconcile`**. The one place it touches the money path is
  `core.rs:1562`, `wheel_spin::recover(...)` in the restart path, which applies a journalled-but-unapplied spin. Because the
  wheel is off everywhere there can be no such journal; see "Routers that turned it on" below for the case where there is.
  **No customer loses time they already hold**: the roll is not touched by this feature.
- **Only owner-signed software:** not touched. This is a normal signed router release.

## Exact scope — the files
**Delete outright (6):**
```
tools/pisoportal/src/wheel.rs
tools/pisoportal/src/wheel_gate.rs
tools/pisoportal/src/wheel_limits.rs
tools/pisoportal/src/wheel_run.rs
tools/pisoportal/src/wheel_spin.rs
tools/pisoportal/src/wheel.html
```
**Delete outright (5 test files):**
```
tools/pisoportal/tests/test_wheel.py
tools/pisoportal/tests/test_wheel_visitors.py
tools/pisoportal/tests/test_wheel_browser.py
tools/pisoportal/tests/test_wheel_layout.py
tools/pisoportal/tests/test_wheel_graphics.py
```

**Edit (the 57 references). Line numbers are from `main` at `9a92c33` — re-grep, do not trust them blindly:**

| file | lines | what is there | what to do |
|---|---|---|---|
| `src/main.rs` | 30-34 | `mod wheel; mod wheel_gate; mod wheel_limits; mod wheel_run; mod wheel_spin;` | delete the 5 lines |
| `src/config.rs` | 49-51, 194 | the `wheel: bool` field and `parse_switch(&get("WHEEL", ""), false)` | delete the field and its parse |
| `src/config.rs` | 303-312 | the test `the_wheel_switch_is_off_unless_the_file_says_on_and_no_setup_script_writes_it` | delete the test, but **keep its second half as a new test** asserting no setup script writes `WHEEL=` (see criterion 7) |
| `src/console.rs` | 653-656, 702 | `GET/POST /api/wheel`, `c.core.wheel_changed()` | delete the two routes and the call |
| `src/http.rs` | 106-108 | `wheel_page()` | delete the function |
| `src/http.rs` | 604-605, 638-651, 666, 702, 717-736 | `/wheel`, `/wheel/state`, `/wheel/spin` routes, `wheel_reply`, `handle_wheel_spin` | delete them; see "the two `games.enabled()` guards" below |
| `src/core.rs` | 29, 84 | the `wh` view fields (the Spin button) | delete the fields and every use |
| `src/core.rs` | 252-253, 284, 312 | the `wheel: Wheel` member and its construction | delete |
| `src/core.rs` | 356-357 | `wheel_changed()` | delete |
| `src/core.rs` | 454-457, 482 | `gm: if self.wheel.enabled() { -1 } else { ... }`, `wh: self.wheel.enabled()` | **`gm` becomes plain `self.games.left(mac, now)`** and `wh` goes away entirely |
| `src/core.rs` | 1562 | `wheel_spin::recover(...)` | delete the call |
| `src/core.rs` | 1695-1730 | `core_with_wheel` and the two `rt_with_the_wheel_*` tests | delete the helper and both tests |

**The two `games.enabled()` guards** (`http.rs:630` and `http.rs:666`) read
`if !games.enabled() || p.core.wheel.enabled() { ... }`. With the wheel gone the second half is always false, so each becomes
`if !games.enabled() { ... }`. **Do not delete the guard** — it is the mini-game's own gate and feature 034 removes it.

**Also update (do not delete):**
```
tools/pisoportal/tests/test_soak.py          (drop any wheel step; the soak must still run)
router/tests/test_footprint.py               (the size expectations change: the program gets smaller)
tools/pisoportal/src/console-ui.js           (remove the wheel panel script; 8 lines)
tools/pisoportal/src/console.html            (remove the wheel card and the responsibility dialog; 8 lines)
tools/pisoportal/src/page.html               (remove the "Spin the wheel" link, line 117)
website/manual.html                          (remove the wheel section)
docs/ARCHITECTURE.md                         (section 5 table row, and the wheel paragraph)
docs/ROUTERS.md                              (any wheel mention)
docs/REAL_WORLD_TESTING.md                   (RS34: mark removed with a one-line reason, do not silently delete the row)
specs/RELEASE_STATE.md                       (the wheel paragraph; say it was removed in this release)
CHANGELOG.md                                 (one owner-readable line)
```
`website/console/ui.js` and `ui.json` are NOT edited by hand (corrected 2026-10-28: they are the signed copy that `publish-router.yml` makes from `src/console-ui.js`; a hand edit would break the signature). The three `src/` files above are the real source, and criterion 1 needs them clean.
Check `website/js/VENDORED.md` is untouched, and **never hand-edit** anything in `CLAUDE.md` section 4's generated list.

## Routers that turned the wheel on
Believed to be none (`specs/RELEASE_STATE.md`: off everywhere, needs a typed confirmation). The update must still be safe if one
exists:
- **Leave the owner's data files alone.** Do not delete `wheel-confirm.txt`, `wheel.log` or `wheel.log.1` from
  `/etc/coinslot.d`. They are small, they are the owner's record, and deleting someone's records in an update is not ours to do.
- **`/wheel`, `/wheel/state` and `/wheel/spin` are simply gone from the router** (corrected 2026-10-28: the customer portal answers every
  address it does not know with the ordinary customer page, which a captive portal needs, so a GET of them returns that page, not a
  404; `POST /wheel/spin` returns 405). The console's `GET /api/wheel` is a 404.
- **A journalled spin that was never applied is abandoned.** It can only exist on a router that had the wheel on, and it is at
  most one spin's minutes on one device. The update must **note this in `alerts.txt`** when `wheel-confirm.txt` exists, so the
  owner is told rather than it happening silently: one line, "the spinning wheel was removed in this release; your
  wheel.log is kept".
- Customers keep every minute they already hold: the roll is not touched.

## Constraints
- `WHEEL=` must never appear in a generated setup script. The existing `config.rs` test proves this; keep that assertion
  (criterion 7) even though the setting is gone, so a future change cannot reintroduce it quietly.
- A change under `tools/pisoportal/` changes what routers install: **raise `setup/RELEASE`** (`CLAUDE.md` section 4).
- Generated files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`, `website/setup/*`, `website/install.sh`) are rebuilt by CI
  and `tools/build_piso_setup.py`; never edited by hand. Run `python3 tools/build_piso_setup.py --check`.
- Crypto unchanged. No new dependency. Do not reformat files you are not otherwise changing.

## Success criteria
Each becomes a line in `validation.md` with real command output.
1. `rg -n "wheel|WHEEL" tools/pisoportal/src/ router/ setup/src/` returns **only** the two places the other criteria require (corrected 2026-10-28, the spec contradicted itself): the
   `WHEEL=` guard test in `config.rs` (criterion 7) and the one-time notice that reads `wheel-confirm.txt` and writes `alerts.txt`
   (criterion 13). Every other match is a leftover and fails. Documentation and `CHANGELOG.md` may still mention it historically.
2. `cd tools/pisoportal && cargo test` passes, with no wheel tests remaining and no test merely `#[ignore]`d.
3. `cd tools/pisoportal && python3 tests/test_flow.py` passes.
4. `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` pass.
5. `python3 tools/pisoportal/tests/test_soak.py` runs to completion with no wheel step.
6. `router/tests/test_footprint.py` passes with updated expectations, and **the program is smaller than before**: record the
   before and after byte sizes of `pisoportal-mipsel` in `validation.md`.
7. A test still asserts that no generated setup script contains `WHEEL=`.
8. The customer page has no Spin button and no odds text in any state: idle, paid, paused, final. Prove it by the browser test
   that remains, or by a new assertion in `test_flow.py`.
9. The console has no wheel panel and `GET /api/wheel` returns 404. Proven in `tests/test_console.py`.
10. `bash scripts/check_security_rules.sh` passes.
11. `python3 tools/build_piso_setup.py --check` passes and `setup/RELEASE` is raised.
12. **Money unchanged:** `reconcile` output and the ledger total are byte-identical for a fixture ledger before and after the
    change. State in `validation.md` how you checked it — this is the `CLAUDE.md` section 6 requirement.
13. A fixture `DATA_DIR` containing `wheel-confirm.txt` and `wheel.log` survives the update untouched, and one line is written
    to `alerts.txt`.

## Out of scope
- The mini-game (feature 034). Leave `game.rs`, `game.html` and the `games.enabled()` guards working exactly as they are.
- Anything in `core.rs` other than the wheel members and the two view fields. Do not "tidy while you are in there".
- Any change to pricing, vouchers, pause, the roll, the ledger, or the box link.

## Owner decisions
All answered. **D1 = remove** (2026-10-28). **D13**: this feature starts only once feature 032 (the ledger size cap) is merged —
both touch `tools/pisoportal` and a conflict in `config.rs` or `console.rs` is avoidable by waiting. **D14**: this does not ship
on its own; 033, 034 and 035 merge to `main` and then go out as **one** router release. So: raise `setup/RELEASE` as the spec
says, but do **not** run a rollout or ask for one — the release happens after 035.
