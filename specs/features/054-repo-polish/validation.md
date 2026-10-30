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

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | | command or observation | pass / fail / not run | last lines of output, or the hardware check id |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md`
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check`, `mypy`, `pytest -x` on touched files
- [ ] Generated files regenerated, `--check` passes

## Not tested
Say plainly what was not run (no device, no board, no Android SDK) and which hardware checks were added to `docs/REAL_WORLD_TESTING.md`.

## Money and time
How credit, expiry, deduction or the payment queue were checked (or "not touched").
