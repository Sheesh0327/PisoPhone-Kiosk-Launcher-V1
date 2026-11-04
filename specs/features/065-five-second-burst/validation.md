# Validation: feature 065, a 5-second bursting window

Run on 2026-11-04 on a computer. Nothing was run on a real router.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | The installer writes `ratecheckwindow='1'`, `checkinterval='5'`, both bursting switches `'1'` | assertion in `router/tests/test_setup.py` & verified generated `setup/piso-setup*.sh` | pass | verified red against old installer (`checkinterval='15'`); passes with `checkinterval='5'` and `ratecheckwindow='1'` |
| 2 | An update on a router with the old value changes it | read of `cmd_update` (`setup/src/80-commands.sh:237`): it runs `portal_uci` again, commits openNDS only if something changed and restarts it | pass by reading and update tests | RS73 (a) shows it on a real update |
| 3 | `--check`, router and setup tests, security rules | `build_piso_setup.py --check` up to date; `check_product_strip.py` passes for all 3 products; `check_links.py` 0 dead | pass | outputs above |
| 4 | Hardware RS73 on the 360T6M | `docs/REAL_WORLD_TESTING.md` RS73 | **not run** | owner's |
| 5 | Money and time not touched | `git diff main` shows only `setup/src/30-router-settings.sh` (one line), the generated setup files, `setup/RELEASE`, `router/tests/test_setup.py`, CHANGELOG, STATUS, REAL_WORLD_TESTING, and this feature's folder | pass | the installed payload (the portal program) is byte-identical to 1.5.36 |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`
- [x] Python / script links check: `python scripts/check_links.py` (0 dead links)
- [x] Product strip check: `python scripts/check_product_strip.py` (phone, wifi, home clean)
- [x] Generated files regenerated: `python tools/build_piso_setup.py --check` passes

## Not tested
- Everything on a real router (RS73). openNDS accepting `checkinterval='5'` is documented in openNDS configuration reference but was not seen on real hardware.

## Money and time
Not touched. Only an openNDS setting the installer writes changed (`checkinterval` from 15 to 5); no payment, credit, expiry, deduction or ledger code.
