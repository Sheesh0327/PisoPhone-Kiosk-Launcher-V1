# Plan: codebase cleanup

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none relevant.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `CLAUDE.md` sections 4, 5 | files never edited by hand; paths that must not move | ~40 | 700 |
| `specs/STATUS.md`, `specs/RELEASE_STATE.md` | links into feature folders (grep only) | n/a | 0 |
| `docs/*.md`, `docs/api`, `docs/design`, `setup/MANUAL.md` | merge candidates (grep + head only) | n/a | 0 |
| `scripts/tests/test_manual.py`, `test_start_pages.py` | may read `setup/MANUAL.md` or docs by path | to check | 600 |

## Task groups
Small, ordered, each checkable. Nothing is deleted before the owner answers Q1 to Q5.

- [x] 0. Baseline: record tracked file count and size, and the output of the checks in success criterion 2 in `validation.md` (files: `validation.md`; test: the checks run clean before any change; red result: n/a).
- [x] 1. A link checker `scripts/check_links.py` (relative markdown links and `path:` mentions in `*.md`) with its test: lists links to missing files. Run on `main` first: the red result (dead links that already exist) is logged. Needed so criteria 1 is a command, not a promise (files: `scripts/check_links.py`, `scripts/tests/test_check_links.py`; red result logged: yes).
- [x] 2. Reader scan: `git grep` each Tier A and B file in `.github/`, `scripts/`, `tools/`, `setup/`, `esp32_firmware/`, `app/src/`; write the result table into `validation.md`. A file with a code reader moves to "stays" or its reader changes in the same step (test: the table).
- [x] 3. Tier A deletions approved in Q1 (`git rm`), then links fixed in `README.md`, `specs/roadmap.md`, `specs/RELEASE_STATE.md`, `specs/spec_0*.md`; checker green.
- [x] 4. `specs/features/ARCHIVE.md` from the finished feature folders (Q2): generated once by a throwaway script in the scratchpad (not committed), then the folders removed; `STATUS.md` links point to `ARCHIVE.md#NNN`; checker green.
- [ ] 5. `docs/REFERENCE.md` from four small documents (Q3) and `setup/MANUAL.md` appended to `setup/README.md` (Q4); originals removed; `README.md`, `docs/ARCHITECTURE.md`, `docs/ROUTERS.md`, `docs/REAL_WORLD_TESTING.md` (text only, path unchanged), `website/start/shop.html` links fixed; the fixed-path documents stay.
- [ ] 6. Epics `spec_01..03` into `specs/EPICS.md` (only if Q2 is yes); `specs/README.md` Files section and `specs/GUIDE.md` updated to the new layout.
- [ ] 7. Validate (`/sdd-validate`): criteria 1 to 5 with evidence, the full list of removed files, file count before and after; `CONTRIBUTING.md`, `CLAUDE.md` section 2 pointers checked; `specs/STATUS.md` row 048.

## Release
None. No router, firmware or app release, no regenerated files. Merged to `main` only when the owner asks, after Code quality is green on that commit (`CLAUDE.md` section 5).
