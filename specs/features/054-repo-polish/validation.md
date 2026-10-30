# Validation: 054 repo polish

## Baseline (task group 0, before any change)
- Branch `feat/054-repo-polish` from `origin/main` `74ae3f5`, with the two spec commits cherry-picked. `main` had moved since the spec
  was cut (feature 055 added a row to `specs/STATUS.md`); that conflict was resolved by keeping both rows.
- `git rev-parse HEAD`: `ccdcac37b38453e14eb087001fb85c3a19868776`
- `git ls-files | wc -l`: 985
- `python3 scripts/check_links.py`: `0 dead link(s)`
- `bash scripts/check_security_rules.sh`: `ok: 8 workflows, no signing step follows a git reset --hard` / `security rules: all passed`
- Ledger (`ledger/negative-constraints.md`): no unresolved entry touches documents or links.
- Success criteria that fail at the start (the red state, docs-only so no ledger entry): no `LICENSE` (1); no `README.md` in `app`,
  `esp32_firmware`, `router`, `protocol`, `tools` (2); no `docs/README.md`, 12 tracked files under `docs/` (3). Root `README.md` is
  127 lines, `CONTRIBUTING.md` 39.

## Naming audit (task group 8, S8)
Method: every tracked file name was classified (UPPER, snake, kebab, camel, Pascal) and compared with the other files of the same kind in the
same folder. Left out: generated and published paths (`CLAUDE.md` section 4), `README.md`, dot files, resources, screenshots, images and the
feature folders. Also checked on their own: Kotlin files (all PascalCase), firmware `src/` and `include/` (all PascalCase except `main.cpp`),
`esp32_firmware/host_tests/` (all snake_case), test files (`test_*.py`/`.js` except two helpers), feature folders (all `NNN-kebab`).
"Readers" is the number of other tracked files that name the file (`git grep -l -F`, without `CHANGELOG.md` and `specs/features/ARCHIVE.md`).

| File | Why it stands out | Readers | Decision |
|---|---|---|---|
| `specs/GUIDE.md`, `RELEASE_STATE.md`, `REPLAN.md`, `STATUS.md` | UPPER among lower-case epics (`spec_NN_*.md`, `mission.md`) | 4, 24, 5, 39 | kept: read by `CLAUDE.md`, the `sdd-*` skills, `constitution.md`; the `specs/` layout is frozen |
| `tools/pisoportal/src/brand_fil.js` | snake_case among kebab-case JS (`console-ui.js`, `console-verify.js`) | 5 | kept: `include_str!` in `brandpage.rs`, `router/tests/test_footprint.py`; `tools/pisoportal/src` is code |
| `tools/pisoportal/src/console-ui.js` | kebab-case (the other side of the same pair) | `publish-router.yml`, `scripts/sign_console_ui.py` | kept: read by the signing workflow |
| `website/js/yume-chan-bundle.js` | kebab-case among snake_case | 5 | kept: vendored; `check_security_rules.sh`, `check_vendored.py`, `VENDORED.md` |
| `docs/api/gateway-coinslot.md` | lower-case in a folder of UPPER_CASE documents | 14 | kept: named in app, firmware and router source comments (spec constraint 1) |
| `esp32_firmware/src/main.cpp` | lower-case among PascalCase | - | kept: the Arduino/PlatformIO entry file |
| `tools/pisoportal/tests/fakebox.py`, `harness.py` | no `test_` prefix | 5, 3 | kept: they are helpers, not tests |
| `.github/pull_request_template.md` | lower-case; GitHub accepts both cases | 2 | kept |

Result: no file renamed, no link changed. Nothing in the audit was a docs-only file with zero readers.

## Success criteria (task group 10)
Run on the final branch head; `origin/main` is `74ae3f5`.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | `LICENSE` exists; README links it | `ls -l LICENSE`; `grep -n LICENSE README.md` | pass | `LICENSE` (818 bytes, proprietary, all rights reserved, TechNet Solutions 2026). README names it in the layout table and links it in its last section (`See [`LICENSE`](LICENSE)`); `website/README.md` also names it |
| 2 | Every code folder has a `README.md` | `ls */README.md tools/pisoportal/README.md` | pass | `app`, `esp32_firmware`, `protocol`, `router`, `scripts`, `setup`, `tools`, `website`, `tools/pisoportal` (and `docs`, `specs`, and `esp32_firmware/web`, `.github`) all have one. New: app 43 lines, esp32_firmware 44, router 48, protocol 42, tools 35 (target 30 to 80) |
| 3 | `docs/README.md` lists every file under `docs/` | `for f in $(git ls-files 'docs/*.md' 'docs/**/*.md' ...); do grep -q "$(basename $f)" docs/README.md \|\| echo MISSING $f; done` | pass | prints nothing; 12 documents indexed (the 12 files under `docs/` besides the index itself) |
| 4 | `check_links.py` 0 dead links; its test passes | `python3 scripts/check_links.py`; `python3 scripts/tests/test_check_links.py` | pass | `0 dead link(s)`; `check_links: OK` |
| 5 | The four repository checks pass as before | `bash scripts/check_security_rules.sh`; `python3 tools/build_piso_setup.py --check`; `python3 scripts/check_vendored.py`; `python3 scripts/embed_web.py --check` | pass | `security rules: all passed`; `... website's copies ... are up to date`; `vendored files match their record`; `WebAssets.h is up to date.` None of them changed a file (`git status` clean after) |
| 6 | The diff against `origin/main` touches only docs and metadata | `git diff --name-status origin/main..HEAD` | pass | 29 entries: 11 `A`, 18 `M`, no `R`, no `D`. Every path is `*.md` (that includes `ledger/negative-constraints.md`, one row added), `LICENSE`, `.editorconfig`, `.gitattributes`, `.gitignore`, `.github/ISSUE_TEMPLATE/*`, `.github/CODEOWNERS`, `.github/pull_request_template.md` or `.github/README.md`. A grep for `app/src`, `esp32_firmware/src\|include`, `tools/pisoportal/src\|bin`, `setup/src`, `setup/piso-setup*`, `website/update\|flash\|setup`, `.github/workflows` finds nothing. No workflow comment was changed either |
| 7 | Every command written exists | the table "Commands" below | pass, with the limits stated there | 24 commands run here and pass; the rest are copied from CI or `CONTRIBUTING.md` and their files and task names were checked; each is marked |
| 8 | `CONTRIBUTING.md` and `specs/README.md` do not contradict `CLAUDE.md` section 5 | read side by side | pass | `CLAUDE.md` section 5: one feature, one branch `feat/NNN-name`; a green feature branch may merge straight into `main`; no PR, merge or release unless the owner asks; a router change goes through `beta`. `CONTRIBUTING.md` "Branches" says the same, four bullets. `specs/README.md` lines 22, 27 and 28 already say features start from `main` and only router changes use `beta`: no edit needed. See "For the owner" for documents that still say otherwise |
| 9 | Root README at most about 150 lines, sections in S2 order | `wc -l README.md`; `grep -n '^## ' README.md` | pass | 104 lines. Banner, summary, then `## Start here`, `## Repository layout`, `## Quick start for developers`, `## Status`, `## Security model`, `## Documentation`, `## Development process`, `## Contributing`, `## Licence` (S2 order). The Code quality badge for `quality.yml` is under the title (owner answer Q1). No non-ASCII character in the file, so no emoji |

## Commands (criterion 7)
Every command written in `README.md`, `CONTRIBUTING.md`, `.github/README.md` and the component READMEs. Backticked file paths in the nine
READMEs were also checked against `git ls-files` with a script (all resolve; the only two that do not are router runtime paths
`DATA_DIR/open` and `DATA_DIR/reconcile.state` in text that was already there). Text I kept from the old `scripts/README.md` and the owner
part of `setup/README.md` was not re-verified line by line.

**Run here and passed**

| Command | Result |
|---|---|
| `python3 scripts/check_links.py`, `python3 scripts/tests/test_check_links.py`, `bash scripts/check_security_rules.sh`, `python3 scripts/check_vendored.py`, `python3 scripts/embed_web.py --check`, `python3 tools/build_piso_setup.py --check` | see criteria 4 and 5 |
| `python3 protocol/gen_vectors.py && git diff --exit-code protocol` | exit 0 and 0: the committed vectors are reproduced |
| `node scripts/tests/test_provisioning.js` | 55 checks, 0 failures |
| `python3 scripts/check_product_strip.py` | `phone: no trace of the other product`, `wifi: ...` |
| `python3 scripts/tests/test_start_pages.py`, `python3 scripts/tests/test_manual.py` | `4 pages OK`, `manual: OK` |
| `python3 router/tests/test_setup.py` | 479 checks, 0 failures (the BusyBox-only test was skipped: busybox is not installed) |
| `python3 router/tests/test_monitor.py`, `test_install.py`, `test_footprint.py`, `test_boxupdate.py`, `test_phone_only.py` | 60, 47, 12 budgets, 47, 51 checks; 0 failures |
| `python3 tools/checklist/test_apply.py`; `python3 tools/checklist/build.py out.html` | 31 checks, 0 failures; the page was written |
| in `tools/pisoportal`: `cargo fmt --check`; `cargo clippy --locked --release --all-targets -- -D warnings`; `cargo build --locked --release`; `cargo test --locked --release`; `python3 tests/test_flow.py` | exit 0 each; 193 tests passed; 216 checks, 0 failures |

Running them found one mistake in the docs, now fixed: `tests/test_flow.py` runs `target/release/pisoportal`, which `cargo test` does not
build, so it failed with `FileNotFoundError` until `cargo build --release` came first. The root README, `CONTRIBUTING.md`,
`tools/README.md` and `tools/pisoportal/README.md` now say so. (The old README and `CLAUDE.md` section 3 leave the build out too; see "For the owner".)

**Run here, one known failure**: `python3 router/tests/test_pisophone_setup.py`: 258 checks, 1 failure, "the answers went over the
connection's input" with a stray `/bin/bash: line 1: unalias: unsetenv: not found` line. It is the environment failure already in the
ledger from 2026-10-23 (the container's shell writes that line); a new ledger row was added. No code changed in this feature.

**Not run here; checked by reading the file, the task or environment name, and the same line in CI**

| Command | What was checked | Why not run |
|---|---|---|
| `./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug` | `gradlew` exists; the first two tasks are in `build-apk.yml` (lines 67, 200); `assembleDebug` is the standard Android task and was in the old `CONTRIBUTING.md` | no Android SDK |
| `bash scripts/ci_emulator_tests.sh`, `python3 scripts/check_coverage.py` | files exist; their headers give these usages | no emulator; needs a coverage report |
| `./ktlint --relative "app/src/**/*.kt" "*.kts" "app/*.kts"`, `java -jar detekt-cli-1.23.8-all.jar ...` | copied from `quality.yml` lines 189 and 208 | I downloaded ktlint 1.5.0 to run it; running the downloaded program was declined by the permission check, so I deleted it. detekt not downloaded |
| `sh esp32_firmware/host_tests/run.sh`, `cd esp32_firmware/host_tests && ./run.sh` | file exists and is executable; `quality.yml` runs it | the mbedtls development headers (`libmbedtls-dev`) are not installed here |
| `pio run -e esp32-c3-dev`, `pio run -e esp32dev-dev` | both names are in `esp32_firmware/envs/*.ini` and the CI matrix | PlatformIO is not installed |
| `clang-format --dry-run --Werror <files>` | from `quality.yml`; file list is there | installed version is 18, CI uses 23.1.2, so a run here would not show what CI shows |
| `python3 scripts/embed_web.py` (no flag), `python3 scripts/check_coverage.py`, `python3 scripts/make_cards.py ...` | files exist; usage in their headers | writes a generated file / needs inputs |
| `python3 router/tests/test_setup_web.py`, `test_setup_tools.py`, `test_setup_phone_web.py`, `python3 scripts/tests/test_site_page.py`, `python3 tools/checklist/test_page.py`, `python3 tests/test_browser.py` | files exist; lines copied from `quality.yml` | need Playwright and Chromium, not installed |
| `PISO_TEST_TARGET_DIR=$HOME/test-target python3 router/tests/test_selfupdate.py` | file exists; line copied from `quality.yml` | builds the program with a test-only feature; not run |
| `shellcheck -s sh ...` (two lines) | copied from `quality.yml` | shellcheck is not installed |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: this feature touches documents and repository metadata only, so the ones that apply were run (links,
  security rules, the three `--check` commands); the area tests above were run where the tools exist. App, firmware build and host tests,
  browser tests and style tools: not run, see "Not tested".
- [x] `bash scripts/check_security_rules.sh`: `security rules: all passed`
- [x] Python `ruff check`, `mypy`, `pytest -x`: not applicable, no Python file was changed.
- [x] Generated files: none changed; the `--check` commands pass.

## Not tested
- No Android SDK, no PlatformIO, no board, no router, no phone. Nothing in this feature can change how those behave: no source, build or
  generated file changed (criterion 6).
- ktlint was not run (see the commands table). The `.editorconfig` change only adds sections for Rust, Python, JavaScript and CSS, the router
  and setup shell scripts, and `.bat`; the Kotlin section is unchanged. Run `./ktlint ...` once before relying on it.
- GitHub itself was not looked at: whether the Code quality badge renders, whether `CODEOWNERS` is accepted, and whether `linguist-generated`
  folds the diffs are not checked here.
- `git add --renormalize .` was run with the new `.gitattributes` and changed no tracked file (it flagged only `gradlew.bat` the first time:
  see "For the owner").
- No hardware checks were added to `docs/REAL_WORLD_TESTING.md`; nothing here needs one.

## Money and time
Not touched. No file under `app/src`, `esp32_firmware/src|include`, `tools/pisoportal/src|bin`, `setup/src`, `protocol` code or any generated
or signed file changed (criterion 6). No release: `setup/RELEASE`, `PISO_FW_VERSION` and the app build are as they were.

## For the owner (found while working; nothing below was changed unless it says so)
1. **Q2, renaming the repository (recommendation only, nothing done).** The product is TechNet PisoHub, the repository is
   `PisoPhone-Kiosk-Launcher-V1`. If you rename it (for example `technet-pisohub`), first look at what uses the name: the footer links in
   `website/start/*.html` (4 files), `.github/ISSUE_TEMPLATE/config.yml` (the private security form), the README badge,
   `docs/design/GUI_SPEC_OWNER_AND_WIFI.md`, and 22 paths in `app/lint-baseline.xml` that contain the CI checkout folder
   `~/work/PisoPhone-Kiosk-Launcher-V1/PisoPhone-Kiosk-Launcher-V1/`: after a rename the checkout folder changes, so the lint baseline may
   stop matching and its findings may come back; try it on a branch. Not affected: the app's update address (`pisophone.pages.dev/update`),
   the releases repository (`Sheesh0327/PisoPhone-Releases`), the website, the setup and router files (none uses the repository name).
   GitHub redirects the old address; check that Cloudflare Pages still follows the repository after the rename.
2. **Documents that still describe "beta into main" for every release (D34).** `constitution.md` rule 8 says features start "from `beta`"
   (frozen, and its own header says `CLAUDE.md` wins). `docs/RELEASE.md` section 3 ("merging `beta` into `main` ships all three", and steps
   on lines 64 to 87) is the older flow; it is a path that workflows read, and S5 did not cover it. A replan item.
3. **`CLAUDE.md` section 3** says to run `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal`; `test_flow.py` needs
   `cargo build --release` first. `CLAUDE.md` is outside this feature, so it is unchanged.
4. **`ARCHITECTURE.md` (S10):** no mention of the wheel or the mini-game was left. Two changes: "Code quality runs on every push" became
   "pushes and pull requests to `main` and `beta`", and one row was added for the shop-branding files (feature 049) that the file table of
   `tools/pisoportal/src/` was missing. The second is an omission rather than a stale line; revert that row if you want S10 read strictly.
   No Mermaid diagram was added: the document already has a diagram of box, phone, router and website feeds (S10 says "if none exists").
5. **`.gitattributes`:** `* text=auto eol=lf` plus a list of binary types. The plan said `*.bat eol=crlf`; renormalizing with that rewrote
   `gradlew.bat` (stored with CRLF) in the index, a build file, so `.bat` is `-text` and the file is untouched.
6. **`main` moved** after the spec was cut (feature 055). The `specs/STATUS.md` conflict was resolved by keeping both rows (055 first).
7. **The `LICENSE` text** is a plain notice in the owner's words (proprietary, all rights reserved, TechNet Solutions, no use without written
   permission). I am not a lawyer; have it read before relying on it.
