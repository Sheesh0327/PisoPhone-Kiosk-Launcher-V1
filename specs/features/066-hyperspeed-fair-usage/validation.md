# Validation: feature 066, rephrase HyperSpeed splash description to fair usage

Run on 2026-11-05 on a computer.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | English splash page wording | Read of `tools/pisoportal/src/page.html:63` | pass | `<div class="th"><p class="mut">Full speed &middot; Fair usage applies after %FAIR_GB%+ GB</p><div class="rows">%TIERS_H%</div></div>` |
| 2 | Filipino localization & language completeness | Regex evaluation in `brand_fil.js` & `test_page_languages.py` `ENGLISH` regex | pass | `Buong bilis · patas na paggamit pagkatapos ng 5+ GB` matches regex and produces 0 forbidden English words |
| 3 | Golden hash & footprint budgets | `tools/pisoportal/tests/golden/page_states.json` & `router/tests/test_footprint.py` | pass | `page_bytes: 27613`, `page_sha256: 95a7cf84...`; `test_footprint.py` 12 budgets, 0 failures (`page.html` 26 KB of 28 KB, `brand_fil.js` 9 KB of 14 KB) |
| 4 | Documentation sync | `specs/features/049-guest-page-branding/evidence/filipino-wording.md` | pass | row 1 updated with new English pattern and Filipino draft |
| 5 | Money and time not touched | `git diff origin/main` | pass | diff touches only `page.html`, `brand_fil.js`, `page_states.json`, `filipino-wording.md`, `STATUS.md`, and this feature directory |

## Gates (constitution rule 7)

- [x] Area checks from `CONTRIBUTING.md`
- [x] Python / script links check: `python scripts/check_links.py` (0 dead links)
- [x] Vendored files check: `python scripts/check_vendored.py` (vendored files match record)
- [x] Size budgets: `python router/tests/test_footprint.py` (12 budgets, 0 failures)

## Not tested

- Real router hardware execution (the router program will be cross-compiled by CI on `beta` per `CLAUDE.md` section 5).

## Money and time

Not touched. Only visual text and localization dictionary entries in the portal splash page were altered.
