# Plan: 054 repo polish

Executor: Tier-2 (Sonnet), effort medium (constitution rule 4). Docs only; no code, no release. One commit per task group.
Branch `feat/054-repo-polish` from `main`. Unresolved ledger entries loaded: read `ledger/negative-constraints.md` first, note any that touch docs or links.

## Context manifest (read only these; line ranges where large)

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/features/054-repo-polish/spec.md` | this contract | ~110 | 2,000 |
| `CLAUDE.md` | rules, frozen paths (sections 4, 5) | ~90 | 2,200 |
| `README.md` | to rewrite | 127 | 2,000 |
| `CONTRIBUTING.md` | to fix and extend | 39 | 900 |
| `docs/ARCHITECTURE.md` | to check (S10) | 194 | 3,000 |
| `docs/*.md` headers only (`head -15`) | for the index | - | 1,500 |
| `scripts/README.md`, `setup/README.md`, `tools/pisoportal/README.md`, `website/README.md`, `esp32_firmware/web/README.md` | existing READMEs | <=100 each | 4,000 |
| `.github/pull_request_template.md`, `.github/CODEOWNERS`, `.github/ISSUE_TEMPLATE/*` | S6 | small | 800 |
| `.editorconfig`, `.gitattributes`, `.gitignore` | S7 | small | 600 |
| `.github/workflows/quality.yml` `grep -n "run:"` only | real commands for READMEs | - | 1,000 |
| `scripts/check_links.py` `head -40` | how the link check works | 40 | 500 |

Never open: `esp32_firmware/include/WebAssets.h`, `setup/piso-setup*.sh`, `website/update|flash|setup/*`, `website/js/*-bundle.js`,
`tools/pisoportal/bin/*`, `app/schemas/*`, `specs/*_findings.json` (whole).

## Task groups

- [x] 0. **Baseline.** Record in `validation.md`: `git rev-parse HEAD`, `git ls-files | wc -l`, output of `python3 scripts/check_links.py`
  and `bash scripts/check_security_rules.sh` (both must pass before you start; if not, stop and tell the owner).
- [x] 1. **LICENSE (S1).** Proprietary text, "Copyright (c) 2026 TechNet Solutions. All rights reserved." plus a short no-use-without-
  written-permission clause. Commit.
- [x] 2. **Component READMEs (S4).** Write `app/README.md`, `esp32_firmware/README.md`, `router/README.md`, `protocol/README.md`,
  `tools/README.md` in one shape:
  `# Name` / one-paragraph purpose / `## Layout` (subfolders, one line each) / `## Build` / `## Test` / `## Generated files` (if any,
  from `CLAUDE.md` section 4) / `## See also`. Facts come from `ls`, `CONTRIBUTING.md`, `quality.yml` run lines, `platformio.ini`
  env names, `build.gradle.kts`; do not invent commands. Then bring the five existing READMEs to the same shape (keep content true;
  trim duplication with `docs/`). Run `check_links.py`. Commit.
- [x] 3. **`docs/README.md` (S3).** Index grouped by reader (Owner / Developer / Releases and keys / Hardware testing / Design and API).
  One line per document: link and what it answers. Check: `for f in $(git ls-files 'docs/*.md' | grep -v docs/README.md); do grep -q "$(basename $f)" docs/README.md || echo MISSING $f; done` prints nothing. Commit.
- [ ] 4. **`CONTRIBUTING.md` + `specs/README.md` (S5).** Branch section per `CLAUDE.md` section 5 (D34); add Development setup
  (tool versions from `gradle/`, `platformio.ini`, `rust-toolchain` or `Cargo.toml`, CI setup steps), Commit messages, Code style.
  Keep the checks table; add the router program row from `CLAUDE.md` section 3 (`cargo test`, `python3 tests/test_flow.py`). In
  `specs/README.md` change only the lines that say `beta` for every feature. Commit.
- [ ] 5. **Root README (S2).** Rewrite in the section order of S2, <= ~150 lines. Move long owner/how-it-is-built text out only if a
  doc already holds it (link instead). Badge: `![Code quality](https://github.com/Sheesh0327/PisoPhone-Kiosk-Launcher-V1/actions/workflows/quality.yml/badge.svg)`.
  Layout table lists: `.github/ app/ docs/ esp32_firmware/ gradle/ ledger/ protocol/ router/ scripts/ setup/ specs/ tools/ website/`
  and root files `CLAUDE.md constitution.md CHANGELOG.md CONTRIBUTING.md SECURITY.md LICENSE`. Run `check_links.py`. Commit.
- [ ] 6. **`.github/` (S6).** Align PR template checklist with CONTRIBUTING; check issue template front matter (`name`, `about`,
  `labels`); CODEOWNERS covers every top-level folder (owner handle as already used there). Optional `.github/README.md`: table of the
  eight workflows (name, trigger from `on:`, purpose from the file's header comment). No workflow edits. Commit.
- [ ] 7. **Root tidiness (S7).** Extend `.editorconfig` (indent per language as the code already uses: Kotlin 4, C++ per
  `.clang-format`, Rust 4, Python 4, shell as existing files, YAML/JSON/MD 2) and `.gitattributes` (`* text=auto eol=lf`, binary
  types, `*.bat eol=crlf`, generated files `linguist-generated=true`: the paths of `CLAUDE.md` section 4). **Check first** that
  `git add --renormalize . && git status` shows no content change to generated or signed files; if it does, drop the eol line and note
  it. `.gitignore`: build outputs (`build/`, `.gradle/`, `.pio/`, `target/`, `__pycache__/`, `node_modules/`, `.idea/`, `.vscode/`,
  `*.iml`, `local.properties`, `.DS_Store`). Check `git ls-files -ci --exclude-standard` is empty. Commit.
- [ ] 8. **Naming audit (S8).** Script: per folder, list file names whose style differs from the folder majority (exclude generated
  paths). Put the table in `validation.md` with, for each, `git grep` hit count and decision (rename / keep: read by X). Rename only
  docs-only files with zero readers, fixing links in the same commit. Expect few or none. Commit.
- [ ] 9. **`CHANGELOG.md` header (S9) and `docs/ARCHITECTURE.md` (S10).** Header: purpose, newest first, version scheme (app build,
  `PISO_FW_VERSION`, `setup/RELEASE`). ARCHITECTURE: `grep -n -i "wheel\|mini-game\|minigame"`, fix stale lines; add one Mermaid
  diagram (coin box, phones, router/openNDS/pisoportal, website feeds, owner tools) if none exists. Commit.
- [ ] 10. **Validate.** Run success criteria 1 to 9; paste evidence in `validation.md` (last lines of each command, the diff
  name-status summary, the command list of criterion 7 with how each was checked). Note Q2 (repo rename) as a recommendation.
  Update `specs/STATUS.md` with a row for 054. Stop; the owner decides on merge (no PR unless asked).

## Release
None. Docs, licence and repo metadata only. `setup/RELEASE`, `PISO_FW_VERSION` and the app build are not raised; nothing is regenerated.
