# Validation: feature 049, branding on the guest Wi-Fi page

## Baseline (group 0, `main` at `2489b58`, router 1.5.29 live, 1.5.30 awaiting signing, 2026-10-30)
- **Today's page is recorded** in `tools/pisoportal/tests/golden/page_states.json` and checked by `tools/pisoportal/tests/test_page_golden.py`: the sha256 and size of the page the portal serves (26,972 bytes), of `brand.png` and `mascot.png`, and for 18 states (new customer, voucher choice, time left, another customer paying, cooldown, paused, starting, armed with and without coins, counting, online, paid but not online yet, voucher code, empty, busy, cooldown, plan mismatch, error) the visible text and the visible controls at 360 by 740. **24 checks, 0 failures** on today's code.
- **The test can fail:** with one golden entry altered on purpose (a text and the page hash) it reported 5 failures (`ledger/negative-constraints.md`). `--update` rewrites the file and is only for a change the owner asked for.
- **Size budgets** (`router/tests/test_footprint.py`): program 1027 of 1200 KB (mipsel and mips), 901 (aarch64), 859 (armv7), 997 (x86_64); `setup/piso-setup.sh` 1560 of 1700 KB; `setup/piso-setup.sh.in` 148 of 150 KB; `page.html` 25 of 28 KB (to be raised to 40 KB, Q5).
- **Layout candidates:** four layouts and the six palettes were drawn as one static mock-up (`evidence/layout-candidates.html`, open it with `?l=1..4&p=technet|dagat|tropiko|ginto|rosas|berde&a=%23rrggbb`) and shown to the owner. The owner's pick (Q1) is recorded below.

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
