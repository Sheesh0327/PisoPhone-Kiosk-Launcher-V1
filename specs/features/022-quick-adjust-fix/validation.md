# Validation: feature 022 (quick add / subtract time through the router console)

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | The failing request is a test, red first | `cargo test boxview` before the fix | pass | `the_box_pages_send_their_real_origin_on_their_own_posts` failed on `Referrer-Policy: no-referrer` (ledger row 2026-10-24, feat 022 group 1) |
| 2 | Box pages are sent with `same-origin`; the dashboard's own post is accepted | same test, and `the_dashboards_own_form_post_passes_and_every_foreign_post_still_fails` | pass | both pass after the one-line change in `frame_policy` |
| 3 | Attacks still fail (other Origin, `null`, `https`, other port, look-alike host, no session) | the same test; `same_site_post` was not changed | pass | the foreign cases pass before and after; session check unchanged |
| 4 | Router suite | `cargo test` 158 passed; clippy `-D warnings` clean; `cargo fmt --check` clean; `test_flow` 202; `test_console` 98 | pass | `test_flow` showed one failure of "a fresh page's first view is its own" in the first run and passed 202/202 on the re-run (a known intermittent check, unrelated to the box view) |
| 5 | RS47 added | `docs/REAL_WORLD_TESTING.md` | pass (added, not ticked) | RS47 |

## Gates (constitution rule 7)
- [x] Area checks: `cargo test`, clippy, rustfmt, `test_flow.py`, `test_console.py` ; setup tests: test_setup 458, test_pisophone_setup 232, test_setup_web 115, test_setup_tools 78, test_monitor 37, test_phone_only 39, test_setup_phone_web 31, footprint 10 budgets: 0 failures
- [x] `bash scripts/check_security_rules.sh`: all passed
- [x] Generated files regenerated: `setup/RELEASE` 1.5.20, `python3 tools/build_piso_setup.py` and `--check` pass

## Not tested
Not run in a real browser, on a real router or with a real coin box and phone. The cause (a plain form post under `no-referrer` carries
`Origin: null`) is a browser rule that I know from the web standard and read in the code; the tests prove the headers the router sends and
the check's decisions, not what a browser does. RS47 is the check on real equipment. The router programs in `bin/` are built by CI.

## Money and time
Not touched: no credit, expiry, deduction or payment-queue code changed. The adjustment still goes to the box as before; it could not reach the box through the console before.
