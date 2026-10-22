# Plan: a rarer jackpot, drawn thin to scale

Context manifest (rule 1): read line ranges only. Unresolved ledger entries loaded: none for this feature.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/wheel.rs` | `Segment`, `Table` (parse, to_text, draw, rtp, win, validate, default) and its tests | ~300 | ~4k |
| `tools/pisoportal/src/wheel_run.rs` | `state()` JSON (segments with `thin`, total size), `odds_rows`, console JSON | ranges | ~2k |
| `tools/pisoportal/src/wheel.html` | layout/landing functions, drawing, odds table and explainer | ~220 | ~5k |
| `tools/pisoportal/tests/test_wheel_layout.py` | node tests of the layout (angles by size) | ~110 | ~2k |
| `tools/pisoportal/tests/test_wheel_graphics.py`, `test_wheel.py`, `test_wheel_browser.py` | browser and end-to-end tests (default wheel is the new one) | ranges | ~3k |
| `tools/pisoportal/src/console-ui.js`, `console.html` | `wavg` (chances over sizes) and the help text | ranges | ~1k |
| `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `website/manual.html`, `setup/RELEASE` | docs and release | ranges | ~1k |

## Task groups
Small, ordered, test first (red result in `ledger/negative-constraints.md`). Start after router 1.5.15 is published and the owner approves.

- [ ] 1. **The table** (`wheel.rs`): `Segment.thin`, parse/`to_text` with `t`, sizes in the draw, return and win chance, default `0x12 2x6 3x1 5x1t`. Unit tests first (criterion 1), including a seeded large draw.
- [ ] 2. **The state and the odds table** (`wheel_run.rs`): segments carry `thin`, the total size is sent, `odds_rows` and the console JSON use the real chances. Unit tests first.
- [ ] 3. **The layout** (`wheel.html` pure functions): slice angles by size, wins spread and the thin jackpot apart over sizes, landing inside the middle 80% of the slice's own width. `test_wheel_layout.py` first (criterion 3).
- [ ] 4. **The picture and the odds on the page** (`wheel.html`): half-width jackpot slice, odds table and explainer with the real chances. Browser tests first (criterion 4).
- [ ] 5. **The console** (`console-ui.js`, `console.html`): chances over sizes, the `t` help text, refusals (criterion 5).
- [ ] 6. **Docs and release**: `setup/RELEASE`, `build_piso_setup.py`, CHANGELOG (the default changed), manual, RS34 wording, `validation.md`, footprint check.
- [ ] 7. **Pull request into `beta`** (when the owner asks), read Code quality. Merge and rollout are the owner's.

## Release
Router only (`setup/RELEASE`, the number after 1.5.15). Generated files are regenerated, never edited by hand. 1.5.15 stays live until this is published and the owner widens it.
