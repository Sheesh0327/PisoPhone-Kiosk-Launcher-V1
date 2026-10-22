# Plan: the wheel looks like a real spinning-wheel game

Context manifest (rule 1): read line ranges only. Unresolved ledger entries loaded: the 2026-10-22 owner report (feature 006 fixes it; 007 builds on 006's page).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/wheel.html` | the page to redesign (today 8 KB: pie via `conic-gradient`, odds table, bet +/-, result line) | ~110 | ~3k |
| `tools/pisoportal/src/wheel_run.rs` | `state()`: already returns segments, rtp, win, limits; may add the slice cap note | ranges | ~1k |
| `tools/pisoportal/src/wheel.rs` | `Table::validate` (cap on total slots), tests | ranges | ~1k |
| `tools/pisoportal/tests/test_wheel_browser.py` | browser test to extend (screenshot, label counts, landing) | ~140 | ~2k |
| `tools/pisoportal/tests/test_wheel.py` | end to end (cap) | ranges | ~1k |
| `tools/pisoportal/src/console-ui.js`, `console.html` | the table field's help text and the refusal message | ranges | ~0.5k |
| `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `website/manual.html`, `setup/RELEASE` | docs and release | ranges | ~1k |

Playwright and Chromium are needed (installed; CI has them). `node` runs the pure layout test.

## Task groups
Small, ordered, test first (red result in `ledger/negative-constraints.md`). Start only after 006 is merged (same page), or fold 006 into this branch if the owner prefers one release.

- [x] 1. **Layout function** (pure JS, in its own `<script>` block of `wheel.html` and tested from `node` by extracting the block): one slice per slot, equal widths, wins and bigger multipliers spread evenly, deterministic. Tests first (success criterion 1).
- [x] 2. **Table cap** (`wheel.rs::validate`, console message): refuse a table above N total slots; saved larger tables still draw N proportional slices with a note. Rust tests first.
- [x] 3. **The wheel picture** (inline SVG): rim with lights/pegs, labelled slices (two lose shades alternating, bright win colours by multiplier), pointer with flick, centre hub, the game look at 360 px. Browser test: slice counts and labels equal the odds (criterion 2).
- [x] 4. **The spin** (animation): same duration and easing for every result; stop inside the middle 80% of a slice of the result's row; reduced-motion and no-script behaviour. Landing test over simulated results (criteria 2, 3, 5).
- [x] 5. **Odds explanation + bet chips + result card + extras (answers 3 and 4)**: also the tick sound (browser audio, off by default), the win-only celebration, the last 5 spins in the browser: table with chances, the plain-English summary, the worked example for the current bet, the limits in force, header with time and spins left; accessibility (aria-live, labels, contrast). Browser test and screenshot (criteria 4, 7).
- [x] 6. **Docs and release**: `setup/RELEASE` (1.5.15 with 006), CHANGELOG, manual, RS34 wording, `validation.md`; page size check (at most 30 KB squeezed).
- [ ] 7. **Pull request into `beta`** (when the owner asks), read Code quality. Merge and rollout are the owner's.

## Review questions (asked 2026-10-22; answers: 1 one slice per slot with wins spread evenly, 2 cap 24, 3 bright arcade, 4 explainer + tick sound (off by default) + win celebration + recent spins; recorded in `spec.md`)
1. How should the slices relate to the odds? (a) one slice per table slot, wins spread evenly between losses (35% win, 90% back: honest, recommended); (b) strictly alternating lose/win 50/50 paying 2x (no house edge); (c) strictly alternating with payouts below 2x (needs fractional multipliers: a change to the money model, out of scope here).
2. How many slices, and what cap? Default table is 20 slots. Cap N for tables the owner can save: 24, 36 or 48?
3. Which look? Bright arcade/casino (lights, gold rim) or clean flat in the TechNet colours?
4. Which extras? Tick sound (off until the customer turns it on), win celebration, recent spins on this phone, vibration, the worked-example box.

## Release
Router only (`setup/RELEASE` next number; with 006 that is 1.5.15). Generated files are regenerated, never edited by hand. 1.5.14 stays live at 100% until the new release is published and the owner widens it.
