---
name: sdd-plan
description: Start a feature the spec-driven way. Use when the owner picks a backlog row or roadmap item to work on, before any code is written. Creates the feature folder and stops for the owner to approve.
---
1. Read, in order and nothing more: `constitution.md`, `CLAUDE.md` section 1 and 6, `specs/README.md`, `specs/STATUS.md`, the backlog row (`specs/spec_NN_findings.json`) and the spec section it came from.
2. Pick the next free number. `git checkout -b feat/NNN-name main` (a router change: from `beta`, see `CLAUDE.md` section 5).
3. Copy `specs/features/_template/` to `specs/features/NNN-name/`. Fill `spec.md` (intent, constraints, success criteria, out of scope, owner decisions) and `plan.md` (context manifest, small ordered task groups, release).
3b. For every feature that shows, hides or refuses something depending on a condition (payment, a switch, time left), write in the spec what a visitor *without* that condition sees, and ask the owner if it was not said (feature 005 hid the wheel from unpaid visitors; the owner expected the button).
4. Do not open big or generated files; use `grep -n` and read only line ranges (constitution rule 1).
5. Stop. Show the owner the spec and plan in a short summary and wait for approval. Write no code.
