# Feature 007: the wheel looks like a real spinning-wheel game (slices, design, odds explanation)

Status: **approved** (owner, 2026-10-22; 006 first, then 007)   Branch: `feat/007-wheel-graphics` (made from `beta` when work starts)   Area: router (the wheel page only)
Backlog rows: none (owner request 2026-10-22)   Release: router (raise `setup/RELEASE`; best released together with 006 as 1.5.15)   Depends on: 006 (it changes the same page: odds for everyone, disabled Spin without time)

## Intent
Today the wheel is a plain pie chart with colour blocks: one wedge per table row, so the "x2" wedge is five slots wide. The owner wants it to look and feel like an actual wheel game: many evenly sized slices, a rim with lights, labelled slices, a pointer that flicks as it passes, a clear result moment, and a proper "how the odds work" explanation. Looks only: **the odds, the limits, the router's decision and the money path do not change.**

## What the owner asked (2026-10-22)
"Add more design and make it look like an actual spinning wheel game including the odds explanation and etc. Instead of a pie-chart looking wheel, add multiple, evenly sliced sections that alternate between lose and win."

## The conflict to settle first (why there are review questions)
A wheel whose slices alternate lose, win, lose, win would show a 50% win chance. The published odds are what the router really draws (default table: 13 of 20 slots lose, 7 win = 35%, 90% back on average). A picture that looks 50/50 over a 35/65 draw would mislead customers about what they risk, which 005's "Not built" section forbids ("no wheel that draws a different picture from the result"). And a real 50/50 wheel paying 2x gives the shop **no** house edge (100% back); any edge needs the win chance below 50% or payouts below 2x. So the design below draws **one slice per slot of the table**, with the win slices **spread as evenly as the table allows** between the lose slices (so it reads as alternating, never two wins side by side when avoidable), and nothing about the picture is cosmetic-only.

## Invariants touched
- Money and time (rule 1): **not touched.** No change to `wheel_spin.rs`, `wheel_limits.rs`, the journal, the draw (`Table::draw`), the limits, the ledger or the file lock. The router still returns the table row (`segment`); the page chooses which slice of that row the pointer stops on (any of that row's slices; all show the same label).
- Only owner-signed software: not touched.

## What a visitor without the precondition sees (rule 3b of `sdd-plan`)
- Wheel off or not confirmed: nothing, as before (no button, `/wheel` is 404).
- Wheel on, no paid time (006): the same wheel, odds and explanation; Spin disabled with "Buy Wi-Fi time first".
- Paused or no spins left: wheel and odds shown, Spin disabled with the reason (as now).
- No script / old browser: the odds table in plain HTML is still shown (the wheel is a picture; the table is the source).

## Constraints
1. **One slice per slot.** The wheel has as many slices as the table has slots (total weight), all the same width. A slice shows its label (LOSE, x2, x3, x5 for the default table). The count of each label on the wheel equals its weight, so the picture and the odds panel always agree. The page builds the wheel from `/wheel/state`, never from fixed numbers.
2. **Spread, not clumped.** The slices are laid out so the win slices (and, among them, the bigger multipliers) are spread as evenly as possible around the wheel; losses fill the gaps. The layout is deterministic (the same table gives the same wheel on every phone) and needs no randomness.
3. **Honest animation (005 "Not built" still holds).** Spin time and easing are the same for a win and a loss; the stop position is chosen inside the middle 80% of a slice of the result's row; no deliberate stop next to a bigger prize ("near miss"), no slow-down trick, no result before the spin is answered by the router, no auto-spin. Reduced-motion: the wheel jumps to the result.
4. **Readable table cap.** A wheel with more than N slices cannot be drawn evenly on a phone, so the router refuses a table above N total slots in the console (today up to 1000). **N = 24** (owner answer 2). Tables already saved above N keep working; the page then draws N proportional slices and says so ("each slice is about k slots").
5. **Odds explanation (always visible, plain English):** the table (slice label, pays, slots, chance), the chance to win and the average return in words ("win about 7 of every 20 spins; over many spins you get back about 90 of every 100 minutes you bet"), one worked example for the current bet ("bet 5: lose = -5, x2 = +5, x3 = +10, x5 = +20 minutes"), the limits in force (most to bet, minutes kept, spins a day, wait, win cap), and the sentence that the router decides before the wheel moves and everyone has the same odds. All numbers come from `/wheel/state`.
6. **Design:** a game look that works at 360 px width in a captive-portal browser: a wheel with a rim and pegs/lights, slices in two lose shades alternating with bright win colours by multiplier (colour is never the only cue: every slice has its label), a pointer that flicks when the wheel passes a peg, bet chips (1, 2, 5, 10 within the limit) beside the +/-, a result card with the plain-English line from 005, the time left and spins left in a header. Own offline code only: **no library, no font download, no image beyond what the router already serves**, one page, drawn with inline SVG or canvas.
7. **Weight of the page:** the wheel page stays small (squeezed HTML at most 30 KB; today 8 KB) because it is read once over the shop Wi-Fi.
8. Accessibility: the result is announced in text (`aria-live`), buttons are real buttons with labels, contrast of labels against slices meets 4.5:1, the whole thing works with the wheel picture hidden.
9. Router program keeps using only `sha2`, `hmac`, `p256`. No change to the coin box, app or protocol. The tick sound is off until the customer turns it on and is made with the browser's own audio (no sound file); the win celebration plays on a win only; recent spins are kept in the browser only.

## Success criteria
1. Layout function (pure, unit-tested in a browser-less JS test run by `node` or Python): for several tables (default, 2 slots, 1000 capped, all-loss-but-one, many multipliers) it returns exactly `weight` slices per row, equal widths, wins spread so that no two win slices are adjacent when the table allows it (maximum gap difference of 1), and is deterministic.
2. Browser test: the default wheel has 20 slices, 13 labelled LOSE and 7 win-labelled (5 x2, 1 x3, 1 x5), the odds panel numbers equal the router's `/wheel/state`, the worked example matches the bet, and after a spin the pointer ends on a slice whose label equals the router's result (all four result kinds, using fixed tables so the draw is known: `0x1`, `2x1`, `3x1`, `5x1 0x9`).
3. Honesty test: the stop angle is inside a slice of the result's row for 200 simulated spins of each kind; wins and losses use the same duration; no stop within 10% of a slice edge.
4. Unpaid visitor (006): wheel, odds and explanation shown, Spin disabled; paused and no-spins cases show the reason.
5. Reduced motion and no-script: result shown without animation; plain odds table present.
6. Table cap: the console refuses a table above N slots with the reason; a saved table above N still draws (N proportional slices, with the note); `cargo test` for the cap in `wheel.rs`.
7. Extras: sound is silent until the speaker button is pressed (tested), the celebration runs on a win and not on a loss (tested), recent spins show the last 5 after reloads and nothing breaks when storage is blocked (tested). Page size, contrast and a screenshot at 360 x 740 (kept as evidence in `validation.md`); no console errors; no network request except `/wheel/state` and `/wheel/spin`.
8. Existing tests green (`test_wheel.py`, `test_wheel_browser.py` updated where they counted table rows), `cargo fmt --check`, `clippy`, `cargo test`, router setup tests, `check_security_rules.sh`, `build_piso_setup.py --check`; `setup/RELEASE` raised once (with 006: 1.5.15), CHANGELOG, manual, RS34 wording (hardware check not run).

## Out of scope
Changing the odds, limits, gate, journal or ledger; fractional multipliers (a payout like 1.5x); new prizes; leaderboards; real-money anything; a different wheel per customer; sound that starts on its own.

## Owner answers (2026-10-22, to the review questions in `plan.md`)
1. **Slices:** one slice per slot of the table, wins spread evenly between losses (35% win, 90% back for the default table). Not a strict 50/50 alternation, and no fractional payouts.
2. **Cap:** a table may have at most **24 slots**; the console refuses more. A saved larger table keeps working and is drawn as 24 proportional slices with a note.
3. **Look:** bright arcade (gold rim with chasing lights, glossy slices, flicking pointer, result card with a pop). Constraint 6 is read that way; the chasing lights stop under reduced motion.
4. **Extras in 007:** the odds explainer with the worked example (always shown); tick sound (off until the customer turns it on, speaker button, never starts by itself); win celebration (a short burst **on a win only**; a loss gets the same plain result card as before, no "almost" effect; skipped under reduced motion); recent spins on this phone (last 5, kept only in the browser with a try/catch around storage, nothing on the router; a phone that blocks storage simply shows none).
5. Still to confirm at approval: the spec and plan; release together with 006 as router 1.5.15 (merge to `beta`, then `main` at 0%, your 100% order as before).
