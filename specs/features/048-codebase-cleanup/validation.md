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

## Reader scan (group 2): who reads the files that are candidates
| file | read by | verdict |
|---|---|---|
| `docs/maintainers/AUDIT_REPORT.md`, `HARDENING_PLAN.md` | only documents (`README.md`, roadmap, two epics) | delete (A3) |
| `specs/features/005,006,007` | nothing | delete (A2) |
| `docs/COIN_SLOT_PROTECTION.md`, `LICENSING.md`, `api/print-pairing.md`, `api/superadmin-qr.md` | documents only (`scripts/README.md` names the last by file) | merge into `docs/REFERENCE.md` (B2) |
| `docs/PROVISIONING_SOLD_UNIT.md` | `scripts/make_firmware_bundles.py` (copied into the sold-unit zip), Code quality's website filter | **stays** |
| `docs/api/gateway-coinslot.md` | comments in `KioskEngine.kt`, `AccountProtocol.h`, `WebServerAccounts.h`, `boxlink.rs` | **stays** |
| `docs/design/GUI_SPEC_*` | comments in six Kotlin files, `make_wifi_poster.py`, the router programs' source | **stays** |
| `setup/MANUAL.md` | `README.md`, `docs/ROUTERS.md`, `docs/REAL_WORLD_TESTING.md` RS1 text, `setup/README.md`, `website/start/shop.html` (a link) | merge into `setup/README.md` (B4), links fixed |
| `website/manual.html` (not MANUAL.md) | `scripts/tests/test_manual.py` | not a candidate |

## Removed files
### Group 3 (Tier A): 22 files deleted
- `docs/maintainers/AUDIT_REPORT.md`, `docs/maintainers/HARDENING_PLAN.md`: their **open** items are kept in the new `docs/HARDENING.md` (HARDENING_PLAN section 3, audit risks 4, 9 and 10, owner questions Q5 and Q7); the rest was fixed work, now in `CHANGELOG.md` and git history at `aba69c8`. (The spec had said the open items were rows of `spec_01_findings.json`; for section 3 that was wrong, so they were moved, not dropped.)
- `specs/features/005-spinning-wheel`, `006-wheel-button`, `007-wheel-graphics` (with the 224 KB screenshot).
- `specs/notes/` all but `2026-10-29_session-1-run-sheet.md` and `2026-10-29_spec03-batch.md`; `specs/replans/` all but `2026-10-29.md`.

### Group 4 (Tier B1): 45 files merged into `specs/features/ARCHIVE.md`
Features archived (16): 001, 002, 003, 004, 008, 009, 011, 014, 016, 018, 020, 025, 026, 033, 034, 035. Each entry keeps the intent and success criteria; spec, plan and evidence are in git history at `aba69c8`.
Kept as folders because `docs/REAL_WORLD_TESTING.md` still has an unticked check for them (the owner's rule for Q2): 010, 013, 015, 017, 021, 022, 023, 024, 027, 028, 029, 030, 031, 032, 036, and 039 (its inventory table), 040, 041, 043, 044, 048. Fewer folders were archived than the plan hoped (16 of 37) because 58 hardware checks are still unticked; archiving them is a later, one-step job once those checks pass.

### Group 5 (Tier B2, B4)
- `docs/COIN_SLOT_PROTECTION.md`, `docs/LICENSING.md`, `docs/api/print-pairing.md`, `docs/api/superadmin-qr.md` merged into `docs/REFERENCE.md` (4 files to 1; headings kept one level lower). No code, script or workflow named them (grep of `.py .sh .yml .rs .kt .h .cpp .html`).
- `setup/MANUAL.md` appended unchanged to `setup/README.md` as "Advanced: setting up by hand" (code blocks untouched; headings one level lower). Links fixed in `README.md`, `docs/ROUTERS.md`, `docs/REAL_WORLD_TESTING.md` (text of RS1 only; the file did not move) and `website/start/shop.html` (one link). `test_start_pages`, `test_manual` and `test_site_page` (25 checks) pass.

## Not tested
(to fill at the end)

## Money and time
Not touched: no code, firmware, router or app file changes.
