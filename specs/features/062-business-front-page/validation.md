# Validation: 062-business-front-page

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | No public setup files or pages in `website/` (`pisophone_setup.py`, `install.sh`, `setup/`, `flash/`, `start/`) | `git status` + file checks | pass | `website/pisophone_setup.py`, `website/install.sh`, `website/setup/`, `website/flash/`, `website/start/` removed from tracked files; `scripts/tests/test_start_pages.py` removed. |
| 2 | `tools/build_piso_setup.py --check` and CI workflows updated | `python3 tools/build_piso_setup.py --check` | pass | `python3 tools/build_piso_setup.py --check` returned 0 ("setup/piso-setup.sh (with its sha256) is up to date"); `quality.yml`, `router-program.yml`, `firmware-images.yml` updated. |
| 3 | Business front page shown without a coin-box link (`#restrictedView`), no setup file links, no CSP errors | `website/index.html` + `test_provisioning.js` | pass | `#restrictedView` provides TechNet PisoHub showcase, owner benefits, owner access instructions for `admin.hub` / `10.0.0.10`, manual link, contact/order section. Zero links to setup files. |
| 4 | Phone setup tool (`#mainView`) works when opened from a coin-box link (`?mac=...#secret=...`) | `node scripts/tests/test_provisioning.js` | pass | `node scripts/tests/test_provisioning.js` passed all 55 checks (0 failures). Pairing, WebUSB flashing, and secret handling logic intact. |
| 5 | Owner's manual (`website/manual.html`) has no DIY `pisophone_setup.py` or `install.sh` instructions | `python3 scripts/tests/test_manual.py` | pass | `python3 scripts/tests/test_manual.py` returned `manual: OK` with new assertions forbidding `pisophone_setup.py` and `install.sh`. Manual updated for pre-assembled units. |
| 6 | Redirects (`/start/*` -> `/`) and headers cleaned in `website/_redirects` and `website/_headers` | `website/_redirects` & `website/_headers` | pass | `website/_redirects` maps `/start` and `/start/*` to `/` (302). `website/_headers` rules for removed paths purged. |
| 7 | Repository security, link, vendored and release checks pass | `bash scripts/check_security_rules.sh && python3 scripts/check_links.py && python3 scripts/check_vendored.py && python3 scripts/verify_release.py` | pass | `check_security_rules.sh` passed ("security rules: all passed"); `check_links.py` passed ("0 dead link(s)"); `check_vendored.py` passed ("vendored files match their record"); `verify_release.py` passed ("0 FAIL, 0 WARN, 13 PASS"). |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`
- [x] `bash scripts/check_security_rules.sh` ("security rules: all passed")
- [x] Python: `check_links.py`, `test_check_links.py`, `test_manual.py`, `build_piso_setup.py --check` clean
- [x] Generated files regenerated, `--check` passes

## Not tested
- Hardware deployment on a physical router or phone (site-only change; Cloudflare Pages deploys from `website/` upon merge to `main`).

## Money and time
Not touched.
