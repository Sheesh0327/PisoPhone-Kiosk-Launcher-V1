# Feature 054: a professional-looking repository (structure, naming, documentation)

Status: **approved 2026-10-30; amended at the merge (S11, owner answer in the session)**   Branch: `feat/054-repo-polish` (from `main`)   Area: docs, repo hygiene (no running code)
Backlog rows: none (owner request 2026-10-30: "make the repo look as professional as possible: file organization, correct naming, well crafted but clean documentation; adapt to a professional developer's repo structure")   Release: **none**

## Intent
A developer, buyer or reviewer who opens the repository should, within two minutes, know what the product is, how the parts fit,
how to build and test each part, where the documentation is and what the licence is. Today the root README mixes owner and developer
content, four of seven component folders have no README, there is no LICENSE, `docs/` has no index, and some documents still contradict
the owner's branch rule (D34). Feature 048 already cut files; this feature adds the missing standard pieces and tidies what remains,
**without moving any path that code, CI, the signed router script, routers or the skills read.**

## Owner answers (2026-10-30, before planning)
- **Depth:** documents and safe moves only. No code folder, build file, test path, generated or published file moves or is renamed.
  A full monorepo re-layout (e.g. `app/` to `android/`) is out of scope; it may become its own feature through `beta`.
- **Licence:** a proprietary `LICENSE` ("All rights reserved, TechNet Solutions").
- **Agent workflow files** (`CLAUDE.md`, `constitution.md`, `ledger/`, `specs/`, `.claude/`) stay where they are; the README explains
  them in a short "Development process" section.
- **Naming of documents:** root community files and `docs/*.md` keep UPPER_CASE (four `docs/` files are read by path by workflows).

## Invariants touched
- Money and time: **not touched.** No file under `app/src/`, `esp32_firmware/src|include|web/`, `tools/pisoportal/src/`, `setup/src/`,
  `setup/pisophone_setup.py`, `router/*.sh|*.init`, `protocol/` changes, except comment-only link fixes listed in the plan (none expected).
- Only owner-signed software: **not touched.** No generated, signed or published file changes.
- Release checks: S11 changes `scripts/verify_release.py` (read-only; it signs and publishes nothing) so it stops reading a document as a
  firmware or router change. This is the one code change in the feature.

## Constraints
1. **Frozen paths** (never moved, renamed or deleted): everything in `CLAUDE.md` section 4's table; `docs/RELEASE.md`, `docs/KEYS.md`,
   `docs/DEPLOY.md`, `docs/REAL_WORLD_TESTING.md`; `docs/design/*` and `docs/api/*` (named in app, firmware and router source comments);
   `router/tests/*` (CI and `CLAUDE.md` section 3 call them by path); `tools/checklist/*`, `tools/build_piso_setup.py`;
   `setup/piso-setup.sh.in`; `constitution.md`, `ledger/`, `specs/` layout, `.claude/`; `detekt.yml`, `detekt-baseline.xml`,
   `app/lint-baseline.xml`, `app/dev-signing/`; every script in `scripts/` (workflows and docs call them).
2. Before moving or renaming **any** other file: `git grep -n <name>` across the whole tree; a hit outside `specs/features/ARCHIVE.md`
   and `CHANGELOG.md` means it stays (or the reader is fixed in the same commit, docs only).
3. `constitution.md` is frozen; not edited.
4. `CHANGELOG.md` history entries are not rewritten; only a header/intro may be added.
5. Plain English, short sentences, the house tone (`CLAUDE.md` section 6). No marketing filler, no emoji, no badges that point to
   services the repo does not use. Documentation must stay true: every command written must exist and every link must resolve.
6. New READMEs are short (target 30 to 80 lines): purpose, layout, build, test, where the details are. They link to existing docs
   instead of copying them.
7. Owner-facing setup content stays on the website (`website/start/`); the root README links to it, it does not repeat it.

## Scope (what the executor does)
- **S1 `LICENSE`** at the root: proprietary, all rights reserved, TechNet Solutions, year 2026. README and `website/README.md` mention it.
- **S2 Root `README.md` rewrite** to a standard shape, at most about 150 lines:
  banner; one-paragraph summary; "Start here" (owner links, kept); **Repository layout** table (each top-level folder, one line);
  **Quick start for developers** (prerequisites: JDK/Android SDK, PlatformIO, Rust, Python 3, Node; one build/test line per part,
  taken from `CONTRIBUTING.md`); Status (link `specs/RELEASE_STATE.md`); Security model (short, link `docs/KEYS.md`, `SECURITY.md`);
  **Documentation** (link `docs/README.md`); **Development process** (spec-driven: `CLAUDE.md`, `constitution.md`, `specs/`, `ledger/`,
  `.claude/skills/`, two to four lines); Contributing; Licence. CI status badges only for workflows that exist (`quality.yml`).
- **S3 `docs/README.md`**: an index of every document under `docs/` (and the key ones elsewhere: `CONTRIBUTING.md`, `SECURITY.md`,
  `CHANGELOG.md`, `setup/README.md`, `scripts/README.md`), grouped by reader: owner, developer, release/keys, hardware testing, design.
- **S4 Component READMEs** (new): `app/README.md`, `esp32_firmware/README.md`, `router/README.md`, `protocol/README.md`,
  `tools/README.md`. Each: what it is, layout of its subfolders, build, test, generated files in it, links. Existing
  `tools/pisoportal/README.md`, `setup/README.md`, `scripts/README.md`, `website/README.md`, `esp32_firmware/web/README.md` are
  reviewed for the same shape and trimmed or extended (content kept true).
- **S5 `CONTRIBUTING.md`**: fix the branch section to the owner's rule D34 (feature branch straight to `main`; router changes through
  `beta`), add "Development setup", "Commit messages" (what and why, for the owner), "Code style" (ktlint, clang-format, detekt,
  rustfmt/clippy if CI runs them, ruff), keep the checks table. Same D34 fix in `specs/README.md` (constitution stays).
- **S6 `.github/` hygiene**: the PR template's checklist matches `CONTRIBUTING.md`; issue templates have clear titles/labels;
  `CODEOWNERS` covers every top-level folder; add `.github/README.md` only if it helps (workflows table: name, trigger, what it does).
  Workflow files are **not** edited except comments.
- **S7 Root tidiness**: `.editorconfig` and `.gitattributes` cover every language in the repo (kt, kts, cpp, h, rs, py, sh, js, html,
  css, md, yml); `.gitignore` covers build outputs of every part and editor folders. No tracked file that is a build output or local
  junk (check with `git ls-files` against `.gitignore` patterns).
- **S8 Naming audit (report, then fix only safe ones)**: list files whose names break the folder's own convention (e.g. mixed
  `snake_case`/`kebab-case`/`camelCase` in one folder, Markdown not UPPER at root). Fix only files that S-constraint 2 clears; the rest
  go into `validation.md` as "kept: read by <path>".
- **S9 `CHANGELOG.md`**: add a short header (what the file is, newest first, version scheme per part). History untouched.
- **S10 `docs/ARCHITECTURE.md`**: check it matches today's code (048 removed wheel and mini-game); add one diagram (Mermaid, renders on
  GitHub) of box, phone, router, website feeds if none exists. Fix stale statements only; no rewrite.

- **S11 `scripts/verify_release.py` (added at the merge, owner-approved)**: the pre-merge check said "box firmware changed" and "router
  software changed" (2 FAIL) for `esp32_firmware/web/README.md` and `router/README.md`, which change nothing a box or a router installs.
  A `*.md` file under the watched folders no longer counts. Test first: `scripts/tests/test_verify_release.py`. Nothing else in the
  script changes. The test is not added to `quality.yml` (workflows are not edited here); the owner may add the one line.

## Success criteria
1. `LICENSE` exists; README links it.
2. Every top-level folder that holds code (`app`, `esp32_firmware`, `router`, `protocol`, `scripts`, `setup`, `tools`, `tools/pisoportal`,
   `website`) has a `README.md` (`ls */README.md tools/pisoportal/README.md`).
3. `docs/README.md` lists every file under `docs/` (a one-line shell check: each `docs/**/*.md` name appears in it).
4. `python3 scripts/check_links.py` passes (0 dead links); `python3 scripts/tests/test_check_links.py` passes.
5. `bash scripts/check_security_rules.sh`, `python3 tools/build_piso_setup.py --check`, `python3 scripts/check_vendored.py`,
   `python3 scripts/embed_web.py --check` pass as before.
6. `git diff --name-status origin/main..HEAD` shows only `A`/`M` of `*.md`, `LICENSE`, `.editorconfig`, `.gitattributes`, `.gitignore`,
   `.github/**` (templates, CODEOWNERS, README) and `R` only for files cleared in S8; **no** path under `app/src`, `esp32_firmware/src`,
   `esp32_firmware/include`, `tools/pisoportal/src`, `tools/pisoportal/bin`, `setup/src`, `setup/piso-setup*`, `website/update`,
   `website/flash`, `website/setup`, `.github/workflows` (comments only allowed, show the diff). S11 adds exactly two paths:
   `scripts/verify_release.py` (M) and `scripts/tests/test_verify_release.py` (A).
10. `python3 scripts/verify_release.py` says 0 FAIL on the branch, and `python3 scripts/tests/test_verify_release.py` passes (S11).
7. Every command written in README, CONTRIBUTING and component READMEs exists (the executor lists each and how it checked it).
8. `CONTRIBUTING.md` and `specs/README.md` no longer contradict `CLAUDE.md` section 5 (D34).
9. Root README is at most about 150 lines and has the sections in S2, in that order.

## Out of scope
Moving or renaming code, build, test, generated or published paths; a monorepo re-layout; renaming the GitHub repository (owner action,
see Q2); deleting branches; rewriting `CHANGELOG.md` history or archived specs; editing the constitution; code refactors; any release.

## Owner decisions (answered 2026-10-30: the defaults in bold, Q1 to Q4)
- **Q1** CI badge in the README for `quality.yml`? **Yes.**
- **Q2** The GitHub repository is named `PisoPhone-Kiosk-Launcher-V1` but the product is "TechNet PisoHub". Renaming it (e.g.
  `technet-pisohub`) is a GitHub setting, outside this feature; GitHub redirects old URLs, but check `website/`, workflows and app
  update URLs first. **Note it in `validation.md` as a recommendation; do nothing.**
- **Q3** Add `CODE_OF_CONDUCT.md`? It is usual for open source, not for a proprietary repo. **No.**
- **Q4** A follow-up feature for the full re-layout (`android/`, `firmware/`, `router/portal/`, one `tests/` per part) through `beta`?
  **Only if the owner asks later.**
