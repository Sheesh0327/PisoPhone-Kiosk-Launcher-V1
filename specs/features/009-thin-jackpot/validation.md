# Validation: a rarer jackpot, drawn thin to scale (feature 009)

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
How credit, expiry, deduction or the payment queue were checked (or "not touched"). This feature changes the draw: say how the chances were checked.
