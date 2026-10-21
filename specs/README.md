# Specs: how we work (spec-driven development)

Read in this order, every new task:

1. `constitution.md` (repo root): the working rules for the agent. Frozen; changing it means a new version.
2. `CLAUDE.md`: what the project is and where it stands. Its two rules win over everything else.
3. `specs/mission.md`: why the product exists. `specs/techstack.md`: what it is built with. `specs/roadmap.md`: the order of the work.
4. `specs/STATUS.md`: what is done, in progress or open right now.
5. The spec you are working on, for example `specs/spec_01_codebase_refactor.md`, and its tracker `specs/spec_01_findings.json`.
6. Unresolved entries in `ledger/negative-constraints.md`.

## The loop (one task = one small change)

1. **Pick** one open row from STATUS.md. Open rows that need the owner (`owner-decision`) wait for an answer.
2. **Manifest** (constitution rule 1): list the files you will touch, with lines and estimated tokens. Do not dump big files.
3. **Test first:** write a test that fails. Log the red result in the ledger. Where only hardware can show the problem, add a numbered check to `docs/REAL_WORLD_TESTING.md` and say it was not run.
4. **Fix** in the source. Never edit generated files (`CLAUDE.md` section 4); regenerate them.
5. **Gate** (constitution rule 7): the checks for the area (`CONTRIBUTING.md` table), `bash scripts/check_security_rules.sh`, and for Python `ruff`, `mypy`, `pytest -x`.
6. **Record:** set the row's `status` in the spec's findings JSON, update STATUS.md, add the owner-facing line to `CHANGELOG.md` if a release is raised, and update `CLAUDE.md` section 2 after a release.
7. **Commit** to `beta` (or the session branch). One area per change, so one release type is raised at a time. No merge to `main` or release unless the owner asks.

## Files

| File | What it is | Who edits it |
|---|---|---|
| `constitution.md` | Agent rules, frozen | Owner only; bump the version |
| `specs/mission.md`, `techstack.md`, `roadmap.md` | Direction and order | Owner, with the agent proposing |
| `specs/spec_NN_<name>.md` | One spec: goal, schemas, work packages, acceptance criteria | Written before work; body changes only with the owner |
| `specs/spec_NN_findings.json` | Row-by-row tracker for that spec | Updated as work moves |
| `specs/STATUS.md` | One-page board | Updated at the end of every task |
| `specs/_template_spec.md` | Copy this for a new spec | |
| `specs/notes/` | Chat summaries written before a fresh chat (constitution rule 5) | The agent |
| `ledger/negative-constraints.md` | Failures and skipped items, logged before the fix | The agent |

## Status words (findings JSON)

`open` -> `test-written` -> `fixed` -> `verified-on-hardware`. Also `wont-fix` (with a reason) and `owner-decision` (waiting for the owner).
`fixed` means the test passes on a computer. Only `verified-on-hardware` means it ran on a real box, phone or router.

## Writing a new spec

Copy `_template_spec.md` to `specs/spec_NN_<name>.md`. Keep it to one goal. Every work row has a test and a release type. List owner decisions in one place. Do not start work until the owner has read it.
