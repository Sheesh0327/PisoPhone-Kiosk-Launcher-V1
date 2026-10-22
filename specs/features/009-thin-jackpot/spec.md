# Feature 009: a rarer jackpot, drawn as a thin slice to scale (x5 becomes the jackpot)

Status: **draft** (the owner chose the approach on 2026-10-22; waits for the owner's approval of this spec and plan)   Branch: `feat/009-thin-jackpot` (from `beta`)   Area: router (odds table, draw, wheel page, console)
Backlog rows: none (owner request 2026-10-22)   Release: router (`setup/RELEASE` the number after 1.5.15; after 1.5.15 is live)   Depends on: 007 (the wheel page and its layout)

## Intent
"Make the x5 multiplier smaller so it serves as the jackpot." The owner chose (2026-10-22): **a rarer jackpot, drawn thin to scale**: x5 becomes about 1 in 39, and its slice on the wheel is half as wide as the others, so the picture shows the real chance. The shop keeps about the same average return.

## Why this is more than a picture change (so the spec states it)
- Today every slice is equal and one slice is one slot (007). A thin slice only tells the truth if **a slice's width is exactly its chance**: every slice's angle is proportional to the probability of landing on it. This feature makes that the rule for the whole wheel.
- A rarer jackpot changes the odds, so the table needs a way to say "this row's slots are half-size". A normal slot has size 2 and a thin slot size 1 (half-units); the chance of a row is its slots times their size over the total size. The draw, the return, the win chance and the odds table all follow the sizes.
- The picture must never show a thin slice for a normal chance, or the reverse (005's "no wheel that draws a different picture from the result").

## Owner choice (2026-10-22)
"Rarer jackpot, drawn thin to scale." Not chosen: equal slices with 40 of them, a bigger x10 prize, or a thin picture over unchanged odds (refused: it would show false odds).

## Proposed numbers (for the owner to confirm)
New default wheel: `0x12 2x6 3x1 5x1t` (the `t` marks the thin jackpot row): 20 slices (12 LOSE, 6 x2, 1 x3, 1 thin x5), total size 39 half-units.
| | today (`0x13 2x5 3x1 5x1`) | proposed |
|---|---|---|
| chance of the jackpot (x5) | 1 in 20 (5.0%) | 1 in 39 (2.6%) |
| chance to win anything | 35.0% | 38.5% |
| average return (the shop keeps the rest) | 90.0% | 89.7% |
| slices on the wheel | 20, all equal | 20: 19 normal, 1 half-width |
A customer wins slightly more often (38.5% against 35%) but the top prize is rarer; the shop's edge stays about 10%.

## Invariants touched
- Money and time (rule 1): the **draw changes** (it now picks by size) and the journal and the spin are unchanged: one spin, one change to the session, once; `wheel_spin.rs` still receives a decided row. The ledger, limits, journal and file lock are not touched. The router still refuses a table that returns more than 100%.
- Only owner-signed software: not touched.

## What a router without the precondition does (rule 3b)
- A router that **saved its own table** (WHEEL_TABLE in `settings.txt`): its table keeps working exactly as saved (all slots normal size); nothing changes for it.
- A router on the **default table** (nothing saved): gets the new default at the update. Nobody can have the wheel on without the owner's typed confirmation, and the odds panel always shows the current odds; the changelog says the default changed.
- A table with no `t` marks behaves as today, with the same chances.
- Wheel off: nothing about the wheel is shown, as before.

## Constraints
1. **Table format:** `mult x slots`, optionally followed by `t` for thin slots, for example `5x1t`. `to_text` writes it back. A row with `mult` 0 may also be thin (allowed, rarely useful). Parsing, validation (return at most 100%, multiplier 0 or 2 to 10, at most 24 slices in all) apply as today; the cap counts slices, not size.
2. **The draw:** chance of a row = slots x size / total size (size 2 normal, 1 thin). `Table::draw` picks over sizes; the same random number logic as today; no other input.
3. **The wheel picture:** one slice per slot; a normal slice spans 2/total of the circle, a thin slice 1/total. Layout still spreads wins between losses and prizes apart (007), now over the sizes (a thin slice counts half); the stop is inside the middle 80% of a slice of the result's row (the slice's own width).
4. **Odds shown:** the odds table and the explainer use the real chances (the jackpot "1 of 39 (2.6%)" is not rounded to "1 in 40"); the worked example, limits and return line follow the new numbers.
5. **Tables above 24 slices** (saved earlier) are still drawn as 24 proportional slices (007), now with sizes.
6. The console help text explains `t`; the refusal messages stay plain English.
7. No new library; the page stays under 30 KB; the program stays within the footprint budgets (1100 / 1600 KB).

## Success criteria
1. `cargo test`: parse and `to_text` round trip with `t`; chances and return computed over sizes (the proposed default gives 15/39 win, 1/39 jackpot, 89.7%); a seeded draw over a large run lands within a tight range of those chances (the thin row about half as often as a normal slot); the return and cap rules refuse what they refused before; a saved old-format table gives exactly the chances it gave.
2. The existing wheel tests stay green (`test_wheel.py`, `test_wheel_visitors.py`, `test_wheel_browser.py`); tables without `t` produce the same wheel and odds as before.
3. Layout (node): slice angles sum to 360; each slice's angle is proportional to its size; the proposed default gives 19 slices of 18.46 degrees and one of 9.23; wins spread between losses and the thin jackpot is not next to the x3; a thin slice is half the width of a normal one in every table.
4. Browser: the default wheel has 20 slices, exactly one half-width and labelled x5; the odds table says "1 of 39 (2.6%)" for x5; for a fixed table the stop is inside the middle 80% of a slice of the result's row (including a thin one); a win and a loss still turn the same way.
5. Console: saving `0x12 2x6 3x1 5x1t` works and shows its chances; a table with a mark on a row above the cap or above 100% return is refused with the reason.
6. Router setup tests, `cargo fmt/clippy`, footprint test, `check_security_rules.sh`, `build_piso_setup.py --check`; `setup/RELEASE` raised once; CHANGELOG, manual, RS34 wording (hardware check not run).

## Out of scope
A third size (quarter slots), per-slice colours chosen by the owner, changing the limits, the gate, the journal or the ledger, fractional multipliers, a separate jackpot counter or progressive prize.

## Owner decisions
1. Approve this spec and plan.
2. Confirm the proposed default (`0x12 2x6 3x1 5x1t`: jackpot 1 in 39, win chance 38.5%, return 89.7%) or give other numbers.
3. Release: after router 1.5.15 is live; this becomes router 1.5.16 (merge to `beta`, then `main` at 0%, your 100% order).
