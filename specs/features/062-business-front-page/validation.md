# Validation: 062-business-front-page

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | No public setup files or pages in `website/` (`pisophone_setup.py`, `install.sh`, `setup/`, `flash/`, `start/`) | `test_site_page.py` + `git status` | not run | - |
| 2 | `tools/build_piso_setup.py --check` and CI workflows updated | `python3 tools/build_piso_setup.py --check` | not run | - |
| 3 | Business front page shown without a coin-box link (`#restrictedView`), no setup file links, no CSP errors | `python3 scripts/tests/test_site_page.py` | not run | - |
| 4 | Phone setup tool (`#mainView`) works when opened from a coin-box link (`?mac=...#secret=...`) | `node scripts/tests/test_provisioning.js && python3 scripts/tests/test_site_page.py` | not run | - |
| 5 | Owner's manual (`website/manual.html`) has no DIY `pisophone_setup.py` or `install.sh` instructions | `python3 scripts/tests/test_manual.py` | not run | - |
| 6 | Redirects (`/start/*` -> `/`) and headers cleaned in `website/_redirects` and `website/_headers` | `test_site_page.py` | not run | - |
| 7 | Repository security, link, vendored and release checks pass | `bash scripts/check_security_rules.sh && python3 scripts/check_links.py && python3 scripts/check_vendored.py && python3 scripts/verify_release.py` | not run | - |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md`
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check`, `mypy`, `pytest -x` on touched files
- [ ] Generated files regenerated, `--check` passes

## Not tested
Pending implementation.

## Money and time
Not touched.
