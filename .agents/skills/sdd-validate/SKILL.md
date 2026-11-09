---
name: sdd-validate
description: Validate a finished feature against its spec and write the evidence. Use when all plan task groups are ticked.
---
1. Fill `specs/features/NNN-name/validation.md`: for every success criterion, execute the verification check and capture the relevant trailing output as evidence. Honestly mark `not run` where only physical hardware can execute it, and add a numbered test to `docs/REAL_WORLD_TESTING.md`.
2. Run mandatory gates:
   - `bash scripts/check_security_rules.sh`
   - Area-specific checks from `CONTRIBUTING.md` / `GEMINI.md` section 5
   - For Python changes: `ruff check`, `mypy`, `pytest -x`
   - For generated assets: run their respective `--check` verification commands
3. Explicitly state how money and time logic was validated (or affirm that money/time paths were not touched).
4. Update backlog row status in `specs/spec_NN_findings.json`, `specs/STATUS.md`, and `CHANGELOG.md` (when a release is cut).
5. Set the spec status in `spec.md` to `done` only if all criteria passed; otherwise list open items. Summarize the spec, criteria, and evidence for owner review.
