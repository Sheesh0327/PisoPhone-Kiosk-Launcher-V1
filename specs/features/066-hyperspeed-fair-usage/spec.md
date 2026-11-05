# Feature 066: rephrase HyperSpeed splash description to fair usage

Status: **in progress**   Branch: `feat/066-hyperspeed-fair-usage`   Area: router (portal captive splash page & Filipino dictionary)
Backlog rows: follow-up to feature 049 (`specs/features/049-guest-page-branding/evidence/filipino-wording.md`)   Release: router

## Intent

On the PisoWiFi splash / captive portal page, HyperSpeed's plan description currently says:
`Full speed · slows after %FAIR_GB% GB` (in Filipino: `Buong bilis · bumabagal pagkatapos ng $1 GB`).

The word "slows" (and in Filipino, "bumabagal") is a major red flag for prospective customers, creating negative perception of service throttling. This feature rephrases the description to professional telecom-standard wording:
`Full speed · Fair usage applies after %FAIR_GB%+ GB`
(in Filipino: `Buong bilis · patas na paggamit pagkatapos ng $1+ GB`).

This removes the negative "slows" / "bumabagal" phrasing, highlights full line speed, and clearly conveys that standard fair usage applies only after substantial usage (5+ GB by default).

## Invariants touched

- Money and time: **not touched**. No payment, coin pulse, credit, expiration, or ledger logic is changed. Only user-facing copy and localization text change.
- Only owner-signed software: **kept**. Ships inside the router program and signed setup files.

## Constraints

- `tools/pisoportal/src/page.html` must remain within its footprint budget (`<= 28 KB`).
- `tools/pisoportal/src/brand_fil.js` must remain within its footprint budget (`<= 14 KB`).
- Unbranded guest page golden record (`tools/pisoportal/tests/golden/page_states.json`) `page_bytes` and `page_sha256` must accurately match the squeezed, filled unbranded page.
- Branded portal Filipino translation in `brand_fil.js` must contain zero words matching `test_page_languages.py`'s `ENGLISH` regex.
- `specs/features/049-guest-page-branding/evidence/filipino-wording.md` must be updated to keep owner documentation accurate.

## Success criteria

1. **English splash page:** In `tools/pisoportal/src/page.html`, line 63 renders `Full speed &middot; Fair usage applies after %FAIR_GB%+ GB`.
2. **Filipino localization:** In `tools/pisoportal/src/brand_fil.js`, the regex matches `Full speed · Fair usage applies after (\d+)\+? GB` and translates it to `Buong bilis · patas na paggamit pagkatapos ng $1+ GB` with 0 words matched by `ENGLISH` regex.
3. **Golden hash & footprint:** `tools/pisoportal/tests/golden/page_states.json` is updated (`page_bytes: 27613`, `page_sha256: 95a7cf8475dbaf4aa4cafaf429cec1993c48ffbd9117134221155dc88adec6a4`); `router/tests/test_footprint.py` passes with 0 failures.
4. **Documentation sync:** `specs/features/049-guest-page-branding/evidence/filipino-wording.md` row 1 is updated with the new English pattern and Filipino draft.
5. **Money and time untouched:** No payment, credit, or ledger file is touched.

## Out of scope

- Changing plan pricing tiers, speed limits, or openNDS fair-use queue parameters (`fair.rs`, `core.rs`).
- Modifying coin box firmware or Android app kiosk.
