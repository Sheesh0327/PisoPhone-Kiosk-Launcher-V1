# Validation: feature 048, codebase cleanup

## Baseline (group 0, `origin/main` at `aba69c8`, 2026-10-29)
Tracked files **901** (specs 150, docs 16), tracked size **64,712 KB**.

Checks before any change (the same must hold at the end):
- `check_security_rules.sh` passes; `build_piso_setup.py --check` up to date; `check_vendored.py` matches.
- `scripts/tests`: pass: `test_check_published_apk` 8, `test_console_ui` 13, `test_make_firmware_bundles` OK, `test_manual` OK, `test_publish_firmware` 14, `test_sold_unit_check` OK, `test_start_pages` 4 pages, `test_workflow_signing` 8 workflows, `test_provisioning.js` 55.
- Already failing or skipped in this container before any change (so they prove nothing either way): `test_admin_qr` and `test_make_cards` (missing temp file), `test_make_wifi_poster` (qrcode and pillow not installed), `test_recovery_code` (1 failure), `test_portal_*_ui` and `test_site_page` (Playwright browser not found; run with `CHROME=...` where the test allows).

## Results
| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | no dead links in the files that remain | `python3 scripts/check_links.py` | not run | |
| 2 | gates as before | the list above | not run | |
| 3 | no running code or generated file touched | `git diff --stat origin/main..HEAD` | not run | |
| 4 | file count falls | before 901 | not run | |
| 5 | a reader can still find the live state and the old reasons | by hand | not run | |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md` (website and scripts rows)
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check` and `pytest -x` on `scripts/check_links.py` and its test
- [ ] Generated files: none touched; `--check` passes

## Removed files
(filled in group by group)

## Not tested
(to fill at the end)

## Money and time
Not touched: no code, firmware, router or app file changes.
