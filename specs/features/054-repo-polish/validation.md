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
