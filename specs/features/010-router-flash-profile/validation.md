# Validation: feature 010, fit the full PisoWiFi and a PisoWiFi-only shop

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Baseline before any change (2026-10-22, `beta` code) | commands of criterion 1 | pass | `cargo test --locked --release`: 141 passed. `tests/test_flow.py`: 202 checks, 0 failures (needs `cargo build --locked --release` first; it runs `target/release/pisoportal`). `test_setup` 288, `test_pisophone_setup` 164, `test_setup_web` 107, `test_setup_tools` 78, `test_setup_phone_web` 31, `test_phone_only` 39, `test_install` 30, `test_selfupdate` 60, `test_footprint` 10 budgets: all 0 failures. `check_security_rules.sh`: all passed. |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md`
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check`, `mypy`, `pytest -x` on touched files
- [ ] Generated files regenerated, `--check` passes

## Not tested
Say plainly what was not run (no device, no board, no Android SDK) and which hardware checks were added to `docs/REAL_WORLD_TESTING.md`.

## Money and time
How credit, expiry, deduction or the payment queue were checked (or "not touched").
