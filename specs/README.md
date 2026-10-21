# Specs: spec-driven development in this repo

The full guide (workflow, cache rules, templates) is `specs/GUIDE.md`. Start every chat with `specs/templates/session-start.md`.

Specs are permanent files that tell a stateless agent what to build and what not to break. Chat is for steering, not for remembering.
The owner is the architect: writes or approves the spec; the agent plans, builds and proves it.

## Reading order for every new chat (a stable prefix, so it can be cached)

1. `constitution.md` (frozen rules for the agent)  2. `CLAUDE.md`  3. this file  4. `specs/STATUS.md`
5. `specs/RELEASE_STATE.md` when the task needs to know what is live. Only then the feature you are working on: `specs/features/NNN-name/`, and the backlog row it came from.
Never read generated or big files (`CLAUDE.md` section 4). Search with `grep -n`, read line ranges.

## The three layers

| Layer | Files | Changes |
|---|---|---|
| Project (rarely) | `constitution.md`, `specs/mission.md`, `specs/techstack.md`, `specs/roadmap.md` | Only at a replan, with the owner |
| Backlog | `specs/spec_NN_*.md` (an epic) and `specs/spec_NN_findings.json` (one row per item) | Rows change status as features land |
| Feature (often) | `specs/features/NNN-name/{spec,plan,validation}.md` | Written, approved, built, validated |

## The loop (one feature = one branch `feat/NNN-name` from `beta`)

1. **Plan** (`/sdd-plan`): copy `specs/features/_template/`, write the spec (intent, constraints, success criteria) and plan. **Owner approves. No code before this.**
2. **Implement** (`/sdd-implement`): test first (see it fail, log it in `ledger/`), fix in the source, regenerate generated files, one commit per task group.
3. **Validate** (`/sdd-validate`): fill `validation.md` with evidence, run the gates, update STATUS and the backlog row.
4. **CI**: Code quality runs only on pull requests into `beta`/`main` and pushes to them, not on `feat/*` pushes. Open a PR (when the owner asks) or merge to `beta`, then read the result.
5. **Review** the owner reads spec, criteria and evidence first; style last. Merge to `beta`. `main` and releases only when the owner asks (`CLAUDE.md` section 5).
6. **Replan** (`/sdd-replan`) before the next feature (`specs/REPLAN.md`).
End a long chat with `/sdd-handoff`: a note in `specs/notes/`, then start a fresh chat.

## If the spec is wrong or silent

Change the spec first, with a small precise edit, tell the owner, then change the code. The code never gets ahead of the spec.

## Status words (backlog rows)

`open` -> `test-written` -> `fixed` -> `verified-on-hardware`; also `wont-fix` (with a reason) and `owner-decision`. `fixed` means the tests pass on a computer; only `verified-on-hardware` ran on a real box, phone or router.
A row with `prior_work` has a tested solution on another branch; bring it back through a feature spec, do not rewrite it.

## Saving tokens

- One feature per chat; a handoff note beats a long history.
- Specs name files and line ranges, so the agent opens nothing it does not need.
- Keep the first bytes of every chat the same (constitution rule 2); put changing facts in `STATUS.md`, never in the constitution.
- Run slow tests in the background and read the last lines only.
- Use a cheaper model for rows that have a precise spec, test and fix (constitution rule 4).

## Files

`constitution.md` agent rules (frozen) · `specs/mission.md`, `techstack.md`, `roadmap.md` direction · `specs/spec_NN_*` epics and trackers ·
`specs/features/` active and finished features · `specs/STATUS.md` the board · `specs/REPLAN.md` + `specs/replans/` replanning ·
`specs/RELEASE_STATE.md` what is live · `specs/GUIDE.md` the guide · `specs/templates/` templates · `scripts/cache_report.py` cache hit rate · `specs/notes/` handoffs · `ledger/negative-constraints.md` failures · `.claude/skills/sdd-*` the workflow as skills.
