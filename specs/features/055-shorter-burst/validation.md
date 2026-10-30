# Validation: feature 055, a shorter bursting window

Run on 2026-10-30 on a computer. Nothing was run on a real router.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | The installer writes `ratecheckwindow='1'`, `checkinterval='15'`, both bursting switches `'1'` | new check in `router/tests/test_setup.py` | pass | failed first against the old installer (481 checks, 1 failure, in the ledger); now `481 checks, 0 failures` |
| 2 | An update on a router with the old value changes it | read of `cmd_update` (`setup/src/80-commands.sh:235`): it runs `portal_uci` again, commits openNDS only if something changed and restarts it; `test_selfupdate` 89 and `test_install` 47 (BusyBox ash) pass | pass by reading and the update tests; **the old-to-new change itself is not asserted by a test** | RS65 (a) shows it on a real update |
| 3 | `--check`, router and setup tests, security rules | `build_piso_setup.py --check` up to date; `test_setup` 481, `test_monitor` 60, `test_install` 47, `test_pisophone_setup` 259, `test_setup_web` 165, `test_setup_tools` 87, `test_setup_phone_web` 47, `test_phone_only` 51, `test_selfupdate` 89, `test_boxupdate` 47, `test_footprint` 12 budgets: all 0 failures; `shellcheck -s sh` on the three CI scripts; `check_security_rules.sh`; `check_links.py` 0 dead | pass | outputs above |
| 4 | Hardware RS65 on the 360T6M | `docs/REAL_WORLD_TESTING.md` RS65 | **not run** | owner's |
| 5 | Money and time not touched | `git diff main` of the code commit: `setup/src/30-router-settings.sh` (one line), the generated setup files, `setup/RELEASE`, `router/tests/test_setup.py`, ledger, CHANGELOG, RS65, this feature's folder | pass | the installed payload (the portal program) is byte-identical to 1.5.33 |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` (router and setup tests above)
- [x] `bash scripts/check_security_rules.sh`
- [x] Python: only a test file changed; the checks above ran it
- [x] Generated files regenerated, `--check` passes

## Not tested
- Everything on a real router (RS65). openNDS accepting `ratecheckwindow='1'` is believed (the status page prints the window) but was not seen; the owner can set it by hand first (`uci set opennds.@opennds[0].ratecheckwindow='1'`).
- The old-to-new change on an updating router is not asserted by a test (criterion 2).
- No Rust or console code changed, so `cargo` and the browser tests were not run for this feature.

## Money and time
Not touched. Only an openNDS setting the installer writes changed; no payment, credit, expiry, deduction or ledger code.
