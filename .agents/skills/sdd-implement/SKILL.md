---
name: sdd-implement
description: Implement an approved feature spec one task group at a time, test first. Use only after the owner approved specs/features/NNN-name/spec.md and plan.md.
---
1. Read `specs/features/NNN-name/spec.md` and `plan.md` only (plus specific files identified in the plan). Confirm the spec status is `approved`; if not, stop immediately.
2. For each task group in `plan.md`:
   a. Write the test and execute it to see it fail (red).
   b. Add the red failure result to `ledger/negative-constraints.md`.
   c. Write the minimal fix in source files (never edit generated files directly; regenerate them).
   d. Run the test and confirm it passes (green).
   e. Tick off the task checkbox in `plan.md`.
3. If implementation reveals gaps or ambiguities not addressed by `spec.md`, stop, propose a minimal edit to `spec.md`, await owner confirmation, then continue (constitution rule 8).
4. Commit per task group with clear, descriptive commit messages for the owner explaining what changed and why. Never merge to `main`, push to `beta`, or open a PR without explicit owner direction.
5. If reaching token or turn limits, run `sdd-handoff` and stop.
