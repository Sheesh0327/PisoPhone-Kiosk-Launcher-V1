# Feature 048: codebase cleanup (fewer files, nothing live removed)

Status: **done 2026-10-29: validated on a computer (`validation.md`); waits for the owner to merge**   Branch: `feat/048-codebase-cleanup` (from `main`)   Area: docs, specs, scripts, ci
Backlog rows: none (owner request 2026-10-29: "refactor the entire code base, remove obsolete files or ones that are not important, merge documentation into a unified file")   Release: none (a change that only touches docs, specs and tests needs no router, firmware or app release; Tier C below would change that and is not in this feature)

## Intent
A shop owner or a new helper opens the repository and finds a lot of history: 43 finished feature folders, three finished epics, handoff notes, two old audit reports and design drafts, and many stale branches. This feature removes what no longer serves anyone and merges what is kept into fewer files, so the repository reads cleaner. It serves both project rules by changing **no running code**: nothing in `app/`, `esp32_firmware/`, `tools/pisoportal/src/`, `setup/src/` or the signed update feeds is touched.

## What the survey found (measured 2026-10-29 on `main` at `aba69c8`)
- Tracked size is about 70 MB. **Documents are small**: `docs/` 0.3 MB, `specs/` 1.5 MB (of which `specs/features/` 1.3 MB, one 224 KB screenshot of the removed wheel), `ledger/` 24 KB. Merging them saves little space; it saves *files* (about 160 under `specs/`, 16 under `docs/`).
- **The space is in generated copies**: `website/` 35 MB, `setup/` 8 MB, `tools/pisoportal/bin` 5.7 MB. The router installer (1.5 MB) exists in 15 copies (`setup/`, `website/setup/`, `website/update/<arch>/`). They are made by `tools/build_piso_setup.py` and CI and read by routers and the signed feeds. They are **not removed here** (Tier C).

## Candidates, by risk
**Tier A: delete, nothing refers to it for real work**
- A1 `specs/features/007-wheel-graphics/evidence/wheel-360x740.png` (224 KB; the wheel was removed in 033).
- A2 the wheel and mini-game features' folders `005-spinning-wheel`, `006-wheel-button`, `007-wheel-graphics` (and the mini-game one if present): the code they describe is gone; their decisions live in `spec_03_overhaul.md` and `CHANGELOG.md`.
- A3 `docs/maintainers/AUDIT_REPORT.md` (28 KB) and `docs/maintainers/HARDENING_PLAN.md` (16 KB): the 2026-10-17 pre-production audit; only `README.md`, `specs/roadmap.md`, `specs/RELEASE_STATE.md` and two epics link to them. Their open items are the rows of `spec_01_findings.json`.
- A4 handoff notes in `specs/notes/` and `specs/replans/`: history, not instructions. **Except** `2026-10-29_session-1-run-sheet.md` (the hardware session 1 sheet is still to be run) and the newest replan and batch note, which `STATUS.md` and `RELEASE_STATE.md` point to: those stay.

**Tier B: merge into one file, then delete the originals**
- B1 finished feature folders (`spec.md`, `plan.md`, `validation.md` each) into one `specs/features/ARCHIVE.md`: per feature a header, intent, success criteria and the evidence line. Active features (045 to 048) and anything with open hardware rows stay as folders.
- B2 `docs/COIN_SLOT_PROTECTION.md`, `docs/LICENSING.md`, `docs/api/print-pairing.md` and `docs/api/superadmin-qr.md` into `docs/REFERENCE.md`. **Stay where they are** (the reader scan, `validation.md`, found a reader): `docs/PROVISIONING_SOLD_UNIT.md` (`scripts/make_firmware_bundles.py` copies it into the sold-unit zip; Code quality's website filter lists it), `docs/api/gateway-coinslot.md` (named in comments of the app, firmware and router sources) and both `docs/design/GUI_SPEC_*` files (named in Kotlin comments and in `make_wifi_poster.py`). Changing those would mean touching code.
- B4 `setup/MANUAL.md` (36 KB, "set up by hand") into `setup/README.md`, or removed if the owner says nobody sets up by hand.
- B5 ~~`specs/spec_01..03` epics into one `specs/EPICS.md`~~ **dropped during the work**: it saves two files, `spec_03_overhaul.md` is still the live decision list that `STATUS.md` and `RELEASE_STATE.md` cite, and the unmerged PR #123 adds `spec_04_*` beside them; merging would only create conflicts. Revisit when the epics are closed.

**Tier C: not in this feature (would change what ships)**
- C1 the 15 copies of the router installer and the 5 router programs, the app APK and firmware images under `website/` (generated; signed feeds read them).
- C2 any code, tests of live code, `app/lint-baseline.xml`, `detekt-baseline.xml`, `app/dev-signing/dev-test-key.p12`.
- C3 unused code inside the Kotlin, C++, Rust and shell sources: needs its own feature per area, with its own tests (spec_01 already tracks these as rows).

## Invariants touched
- Money and time: **not touched.** No file under `app/`, `esp32_firmware/`, `tools/pisoportal/`, `setup/src/` or `router/` changes.
- Only owner-signed software: **not touched.** No generated, signed or published file changes (`website/update`, `website/flash`, `setup/piso-setup*`).

## Constraints
- Files that workflows or the signed router script read **by path must not move or be renamed**: `docs/RELEASE.md`, `docs/KEYS.md`, `docs/DEPLOY.md`, `docs/REAL_WORLD_TESTING.md` (`CLAUDE.md` section 5).
- Before removing or merging any file: `git grep` its name in `.github/`, `scripts/`, `tools/`, `setup/src/`, `setup/pisophone_setup.py`, `esp32_firmware/` and `app/src/`. A hit means it stays, or the reader is changed in the same step with its test.
- Every link to a removed or merged file in the files that stay is fixed (a link check is run and passes).
- `constitution.md` is frozen: it stays as it is. Its rule 8 says specs live in `specs/features/NNN-name/`; keeping `ARCHIVE.md` under `specs/features/` and keeping the active folders satisfies it. Anything else needs a new constitution version and the owner's yes.
- The remote branches (67) are not deleted by this feature; deleting a branch cannot be undone from here.
- Generated files are never edited by hand.

## Success criteria
1. `git grep` finds no link to any removed or merged file in the files that remain (a script lists the dead links; 0 expected).
2. `bash scripts/check_security_rules.sh`, `python3 tools/build_piso_setup.py --check`, `python3 scripts/check_vendored.py` and every test under `scripts/tests/` pass as before.
3. `git diff --stat origin/main..HEAD` shows no path under `app/`, `esp32_firmware/`, `tools/pisoportal/`, `setup/src/`, `router/`, `website/update/`, `website/flash/` or `setup/piso-setup*`.
4. Tracked file count falls from the measured baseline (to be recorded in `validation.md` before the first deletion) by the amount the owner approved for Q1 to Q4; the list of every removed file is in `validation.md`.
5. A reader can still find: what is live (`specs/RELEASE_STATE.md`), the board (`specs/STATUS.md`), each active feature, and the reason behind an old decision (`ARCHIVE.md`, `CHANGELOG.md`).

## Out of scope
Code refactors (Tier C3), removing generated copies (Tier C1), deleting branches or closing dependabot PRs, rewriting history, the constitution, and any release.

## Owner decisions
- **Q1** Delete Tier A, or keep some? Recommended: **delete all of A**. The wheel and mini-game are gone; the audit is superseded by the findings rows. Git history keeps every file.
- **Q2** Merge finished feature folders into `ARCHIVE.md` (B1)? Recommended: **yes**, keeping 040 to 048 and every feature with an unticked hardware row as folders. Otherwise: delete the folders and rely on `CHANGELOG.md` and git history (smallest repository, but the "why" of old decisions then lives only in git).
- **Q3** Docs: merge the small reference docs (B2) into one file? (B3, the design drafts, was dropped: code names them.) Recommended: **yes**, after the grep shows nothing reads them by path.
- **Q4** `setup/MANUAL.md`: merge into `setup/README.md`, keep, or delete? Recommended: **merge**, keeping only the steps an owner needs when the setup program cannot run.
- **Q5** Tier C (the 15 copies of the router installer): a separate feature later, where CI builds them at release and the repository keeps one? It changes the release flow and needs `beta`. Recommended: **yes, later, as its own feature**; not now.

## Owner answers (2026-10-29)
Q1 delete all of Tier A. Q2 merge finished features into `ARCHIVE.md`, keep 040 to 048 and any feature with an unticked hardware row as folders. Q3 yes (one file: `docs/REFERENCE.md`). Q4 merge `setup/MANUAL.md` into `setup/README.md`. Q5 later, as its own feature; not here.
