---
name: sdd-plan
description: Start a feature the spec-driven way. Use when the owner picks a backlog row or roadmap item to work on, before any code is written. Creates the feature folder and stops for the owner to approve.
---
1. Read, in order and nothing more: `constitution.md`, `CLAUDE.md` section 1 and 6, `specs/README.md`, `specs/STATUS.md`, the backlog row (`specs/spec_NN_findings.json`) and the spec section it came from.
2. Pick the next free number. `git checkout -b feat/NNN-name beta`.
2b. If a backlog row has `prior_work`, do a trial apply in a throwaway `git worktree` first (cherry-pick `--no-commit`), list the conflicts and what they depend on, then remove the worktree. Put the result in `spec.md`.
3. Copy `specs/features/_template/` to `specs/features/NNN-name/`. Fill `spec.md` (intent, constraints, success criteria, out of scope, owner decisions) and `plan.md` (context manifest, small ordered task groups, release).
4. Do not open big or generated files; use `grep -n` and read only line ranges (constitution rule 1).
5. Stop. Show the owner the spec and plan in a short summary and wait for approval. Write no code.
