# Validation — Feature 063: "Counting coins" pull-down-to-refresh reminder and stuck-socket recovery

- **Date:** 2026-11-03
- **Branch:** `feat/063-counting-coins-refresh-reminder`

## Success Criteria Checklist

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | Immediate `"Counting coins"` unchanged (< 3 s); all 18 golden states in `test_page_golden.py` keep identical visible text and controls | PASS | `#lstuck` is initially `style="display:none"`; only `page_bytes` (27599) and `page_sha256` (`57201de891a42a9c9f1689f4c62182f7226cfc35b934227fb03c2cc948964811`) updated in `golden/page_states.json`. Controls and texts for all 18 states identical. |
| 2 | `#lstuck` (`"Pull down to refresh if stuck"`) appears after 3 s in `s === "closing"`, reloads page when tapped, and hides when leaving `"closing"` | PASS | Verified in `tools/pisoportal/src/page.html` (`sw = setTimeout(..., 3e3)`, `el("rl").onclick=el("lstuck").onclick=function(){location.reload()}`, cleared and hidden in `wait()`, `s !== "closing"`, and `w.onclose`). Tested in `test_browser.py`. |
| 3 | Filipino translation (`"I-pull down para i-refresh kung na-stuck"`) in `brand_fil.js` passes `test_page_languages.py` with zero English words | PASS | Added to `brand_fil.js:50`. Tested against `test_page_languages.py`'s `ENGLISH` regex: 0 matches found. |
| 4 | Stuck-socket root causes fixed (`cw` handshake guard + quiet reconnect, `dn` retry for Done/Cancel, `WS_PER_DEVICE = 6`, writer-exit `shutdown(Shutdown::Both)`) | PASS | `tools/pisoportal/src/http.rs`: `WS_PER_DEVICE = 6`, writer thread calls `shutdown(Shutdown::Both)` upon exit to unblock reader immediately. `page.html`: `cw` does not abort handshake (< 5 s), re-sends hello if socket active, reconnects if quiet >= 2.5 s; `dn` retains pending Done/Cancel across reconnects. |
| 5 | Footprint budgets respected (`page.html <= 28 KB`, `brand_fil.js <= 14 KB`) | PASS | `page.html`: 26,231 bytes raw (budget 28,672 bytes). `brand_fil.js`: 9,161 bytes raw (budget 14,336 bytes). |
| 6 | `website/manual.html` (chapter 13) and `docs/REAL_WORLD_TESTING.md` (`RS72`) updated and pass `test_manual.py` and `check_links.py` | PASS | `python scripts/tests/test_manual.py` -> `manual: OK`; `python scripts/check_links.py` -> `0 dead link(s)`. Added hardware check `RS72` to `docs/REAL_WORLD_TESTING.md`. |
| 7 | All quality and release gates pass (`check_security_rules.sh`, `check_links.py`, `check_vendored.py`, `build_piso_setup.py --check`, `verify_release.py`) | PASS | `build_piso_setup.py --check` OK; `check_security_rules.sh` passed; `check_vendored.py` passed; `verify_release.py` -> `0 FAIL, 1 WARN, 13 PASS` (warn is expected pending CI build on beta). |
