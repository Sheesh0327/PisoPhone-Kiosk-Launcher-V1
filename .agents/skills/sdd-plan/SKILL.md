---
name: sdd-plan
description: Start a feature the spec-driven way. Use when the owner picks a backlog row or roadmap item to work on, before any code is written. Creates the feature folder and stops for the owner to approve.
---
1. Read in order and nothing more: `constitution.md`, `GEMINI.md` sections 1 and 2, `specs/README.md`, `specs/STATUS.md`, the backlog row (`specs/spec_NN_findings.json`), and the spec section it came from.
2. Pick the next free feature number. Branch `git checkout -b feat/NNN-name main` (or from `beta` for router changes per `GEMINI.md` section 4).
3. Copy `specs/features/_template/` to `specs/features/NNN-name/`. Fill `spec.md` (intent, constraints, success criteria, out of scope, owner decisions) and `plan.md` (context manifest, small ordered task groups, release requirements).
4. For every feature that alters behavior based on conditions (payment, settings, time left), explicitly specify what a visitor or device *without* that condition sees/experiences.
5. Do not open big or generated files; use `grep -n` and read targeted line ranges only (constitution rule 1).
6. Stop. Present the spec and plan to the owner in a concise summary and wait for explicit approval. Write no code until approved.
