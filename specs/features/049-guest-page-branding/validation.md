# Validation: feature 049, branding on the guest Wi-Fi page

## Baseline (group 0, `main` at `2489b58`, router 1.5.29 live, 1.5.30 awaiting signing, 2026-10-30)
- **Today's page is recorded** in `tools/pisoportal/tests/golden/page_states.json` and checked by `tools/pisoportal/tests/test_page_golden.py`: the sha256 and size of the page the portal serves (26,972 bytes), of `brand.png` and `mascot.png`, and for 18 states (new customer, voucher choice, time left, another customer paying, cooldown, paused, starting, armed with and without coins, counting, online, paid but not online yet, voucher code, empty, busy, cooldown, plan mismatch, error) the visible text and the visible controls at 360 by 740. **24 checks, 0 failures** on today's code.
- **The test can fail:** with one golden entry altered on purpose (a text and the page hash) it reported 5 failures (`ledger/negative-constraints.md`). `--update` rewrites the file and is only for a change the owner asked for.
- **Size budgets** (`router/tests/test_footprint.py`): program 1027 of 1200 KB (mipsel and mips), 901 (aarch64), 859 (armv7), 997 (x86_64); `setup/piso-setup.sh` 1560 of 1700 KB; `setup/piso-setup.sh.in` 148 of 150 KB; `page.html` 25 of 28 KB (to be raised to 40 KB, Q5).
- **Layout candidates:** four layouts and the six palettes were drawn as one static mock-up (`evidence/layout-candidates.html`, open it with `?l=1..4&p=technet|dagat|tropiko|ginto|rosas|berde&a=%23rrggbb`) and shown to the owner. The owner's pick (Q1) is recorded below.

## Group 1: the brand data model (`tools/pisoportal/src/brand.rs`)
- **Red first:** 15 tests (`f049_*`) failed against stubs (ledger). **Green:** 15 passed; whole crate `cargo test --release`: **156 passed, 0 failed** (141 before); `cargo fmt --check` and `cargo clippy --release --all-targets -- -D warnings` clean.
- **What it fixes in the spec's rules:** limits counted in characters (32, 60, 160, 80, 600, 60, 120); control characters, text-direction tricks and the byte-order mark removed; only the house rules keep line breaks; unknown palette, layout and malformed accent dropped with a note; an accent that does not show (3:1 against page, band and cards of its palette) is lightened step by step; button text is black or white, whichever reads 4.5:1 or better (checked over 4,096 colours); English fallback per text; draft and published are separate files written with `write_atomic`; a damaged or empty published file means "no branding".
- **A finding the test made:** with cards one shade lighter than the band, the page's grey text was only 4.11:1 on Dagat. The cards are now set per palette (Technet's are today's exact card colour, the others the page colour moved 85% toward the band); every palette now keeps grey, white and gold text at **5.1:1 or better** on the page, the band and the cards, and every palette's own accent at 3:1 or better. The layouts (group 5) must use these colours.
- **Not yet wired in:** nothing serves or edits branding yet (groups 2 to 6); `brand.rs` carries `#![allow(dead_code)]` until then. No money-path file changed (`git diff --stat`: `brand.rs` new, one `mod brand;` line in `main.rs`).

## Owner's pick (Q1)
**All four layouts: A Classic, B Banner, C Compact, D Poster; default A Classic** (2026-10-30). Q2 to Q7 answered the same day (see `spec.md`).

## Results
| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | no branding set: today's page in every state | `test_page_golden.py` | baseline recorded | 24 checks, 0 failures |
| 2 | EN and FIL texts, switch, per-text fallback | | not run | |
| 3 | no script or HTML in any field runs | | not run | |
| 4 | contrast of every palette, layout and accent | | not run | |
| 5 | payment controls present and clickable in every layout and palette | | not run | |
| 6 | pack: good installs; tampered, wrong key, SVG, oversize, bad signature refused | | not run | |
| 7 | draft vs publish, restore default, preview cannot start a window | | not run | |
| 8 | the locked credit in every state and layout | | not run | |
| 9 | branding survives `piso-setup update` and reboot | | not run | |
| 10 | no money-path file changed; all router gates pass | | not run | |
| 11 | hardware: Android and iPhone captive-portal sheet, icon, voucher file, console pack upload | | not run (owner's checks) | |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md` (router rows)
- [ ] `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py`
- [ ] Python: `ruff check` on the new tools and tests
- [ ] Generated files regenerated, `--check` passes
- [ ] **Filipino wording read and signed off by the owner (Q6): the release stays at 0% until then**

## Not tested
(to fill at the end)

## Money and time
Not touched: no change to `core.rs`, `roll.rs`, `ledger.rs`, `boxlink.rs`, `voucher.rs`, `reconcile.rs` (checked with `git diff --stat` at the end).
