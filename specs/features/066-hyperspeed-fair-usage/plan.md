# Plan: feature 066, rephrase HyperSpeed splash description to fair usage

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/page.html` (lines 60 to 70) | guest splash page template with plan descriptions | read by range | 300 |
| `tools/pisoportal/src/brand_fil.js` (lines 80 to 95) | Filipino localization dictionary | read by range | 400 |
| `tools/pisoportal/tests/golden/page_states.json` (lines 1 to 10) | golden bytes and sha256 checksums | read by range | 200 |
| `specs/features/049-guest-page-branding/evidence/filipino-wording.md` (lines 90 to 105) | Filipino wording sign-off reference | read by range | 300 |
| `router/tests/test_footprint.py` | footprint budget test suite | whole file | 400 |

## Task groups

- [x] 1. Red verification / baseline: check current strings in `page.html` and `brand_fil.js`; calculate new length and sha256. (files: `tools/pisoportal/src/page.html`, `brand_fil.js`; red result logged: yes)
- [x] 2. Update portal files:
  - Edit `tools/pisoportal/src/page.html` line 63 to `Full speed &middot; Fair usage applies after %FAIR_GB%+ GB`.
  - Edit `tools/pisoportal/src/brand_fil.js` line 86 to `[/^Full speed · [Ff]air usage applies after (\d+)\+?\s*GB$/,"Buong bilis · patas na paggamit pagkatapos ng $1+ GB"],`.
  - Update `tools/pisoportal/tests/golden/page_states.json` (`page_bytes: 27613`, `page_sha256: 95a7cf8475dbaf4aa4cafaf429cec1993c48ffbd9117134221155dc88adec6a4`).
  - Update `specs/features/049-guest-page-branding/evidence/filipino-wording.md` row 1.
- [x] 3. Run validation gates:
  - `python router/tests/test_footprint.py` (all 12 size budgets green).
  - Verify Filipino translation against `test_page_languages.py`'s `ENGLISH` regex (0 matches).
  - `python scripts/check_links.py` (0 dead links).
  - `python scripts/check_vendored.py` (matches record).
- [x] 4. Documentation & tracking: write `validation.md`, update `specs/STATUS.md`.

## Release

Router release. Portal source change (`tools/pisoportal/src/`) built into binaries on `beta` per `CLAUDE.md` section 5.
