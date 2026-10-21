---
name: sdd-validate
description: Validate a finished feature against its spec and write the evidence. Use when all plan task groups are ticked.
---
1. Fill `specs/features/NNN-name/validation.md`: for each success criterion, run its check and paste the last lines of output. Mark `not run` honestly where only hardware can show it, and add a numbered check to `docs/REAL_WORLD_TESTING.md`.
2. Run the gates: the area checks in `CONTRIBUTING.md`, `bash scripts/check_security_rules.sh`, and for Python `ruff check`, `mypy`, `pytest -x`. Run generator `--check` commands for anything generated.
3. State how money and time were checked (or that they were not touched).
4. Update the backlog row status in the findings JSON, `specs/STATUS.md`, `CHANGELOG.md` (owner terms) when a release is raised, and `CLAUDE.md` section 2 after a release.
5. Set the spec status to `done` only if every criterion passed; otherwise list what is open. Summarize for the owner review: spec, criteria, evidence first.
