---
name: sdd-implement
description: Implement an approved feature spec one task group at a time, test first. Use only after the owner approved specs/features/NNN-name/spec.md and plan.md.
---
1. Read `specs/features/NNN-name/spec.md` and `plan.md` only (plus files named in the plan). Check the spec status is `approved`; if not, stop.
2. For each task group: write the test and see it fail; add the red result to `ledger/negative-constraints.md`; write the fix in the source (never edit generated files, regenerate them); see the test pass; tick the box in `plan.md`.
3. If the work needs something the spec does not say, stop, make a small edit to `spec.md`, tell the owner, then continue (constitution rule 8).
4. Commit per task group in plain English for the owner (what changed and why), with the session's trailer lines. Do not merge to `main`, push to `beta` or open a PR unless the owner said so.
5. At 20 turns or 60K input tokens, run `sdd-handoff` and stop (constitution rule 5).
