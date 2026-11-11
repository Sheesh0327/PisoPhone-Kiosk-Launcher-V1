# Specification workflow

OpenSpec is the planning workflow for new and resumed work. The owner approves the scope and plan before implementation; record that approval and its scope in the change's `evidence.md`. Checked tasks are not approval.

## Where to look

| Question | Record |
|---|---|
| Established requirements | `openspec/specs/` capability specs. Product coverage is incomplete: absence here does not mean a product has no requirements. |
| Proposed work | `openspec/changes/<change>/` proposal, delta specs, design and tasks. |
| Earlier decisions and validation | `specs/features/`, `specs/features/ARCHIVE.md`, legacy epics and findings. These retain their IDs and paths; historical entries describe what was known at the time. |
| Work summary | `specs/STATUS.md`; cross-check dated claims against their sources. |
| Release observations | `specs/RELEASE_STATE.md`; its dated feed observations are not proof of current device state. |
| Outstanding physical checks | `docs/REAL_WORLD_TESTING.md`. A computer pass does not close a hardware check. |
| Checks, branches and release safeguards | `CONTRIBUTING.md` for checks and `CLAUDE.md` section 5 for branches and releases. |

See `specs/legacy-inventory.json` and `specs/cleanup-report.md` for the evidence reconciliation and known gaps. Until the cleanup completes, other older guides and skills may still contain conflicting instructions; do not treat those conflicts as evidence of approval or validation.

## New work

1. Create an OpenSpec change describing the intended behavior, constraints, tasks, and applicable checks. Refer to relevant capability specs and backlog rows.
2. Obtain owner approval of the spec and plan before implementation and record its source and scope in `evidence.md`. Update the change and obtain approval for material new scope before coding it.
3. Implement the approved tasks, record each acceptance criterion's result and evidence (or a reason it was not run), and run applicable checks from `CONTRIBUTING.md`.
4. Update relevant backlog and status summaries without overstating evidence. Keep hardware checks open until actually run. Record dated release observations in `specs/RELEASE_STATE.md`.
5. Request review. PRs, merges, and releases remain owner-directed; follow `CLAUDE.md` section 5. A missing required pre-merge check is a blocker, not a reason to merge first.
6. After implementation review, use the OpenSpec archive workflow. Archiving does not certify unperformed hardware checks; retain those obligations in the evidence.

## Resuming legacy work

Create an OpenSpec change and link the legacy feature ID, existing approvals, evidence, and related backlog rows. Preserve the old files. Prior approval is evidence only for its original scope; obtain approval for the new or changed scope and plan. Record current verification separately from historical evidence. Do not create a second legacy feature contract merely to resume work.

## Backlog terminology

`open`, `test-written`, `fixed`, `verified-on-hardware`, `wont-fix` (with a reason), and `owner-decision` describe different states. `fixed` is computer evidence, not hardware verification. `prior_work` identifies work on another branch that needs its own review and evidence.
