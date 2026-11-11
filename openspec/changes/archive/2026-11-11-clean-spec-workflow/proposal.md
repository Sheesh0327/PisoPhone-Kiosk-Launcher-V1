# Proposal

## Why

The repository has conflicting SDD instructions and stale feature summaries, while its initialized OpenSpec workspace has no capability baseline. Agents cannot reliably distinguish current policy, historical evidence, and outstanding validation; a passing link check currently excludes every legacy feature folder.

## What Changes

- Make OpenSpec authoritative for new work, as selected by the owner on 2026-11-11. Preserve legacy feature IDs, evidence, approvals, and history; reconcile their current summaries instead of bulk migrating them.
- Define one documented authority map and transition rule for continuing legacy work, and redirect legacy SDD entry points to it.
- Reconcile branch, pre-merge CI, validation, retry, and release-record rules across the constitution, guides, templates, and skills. Preserve explicit approval and router build/signing safeguards.
- Audit every legacy feature and backlog record, correct claims only against identifiable evidence, and record missing evidence or unresolved decisions explicitly.
- Add repeatable spec integrity checks and a documentation CI path covering OpenSpec and current legacy references. Keep historical exceptions narrow and visible.

## Capabilities

### New Capabilities

- `spec-governance`: Authority, approval, evidence, and integrity-check behavior for the repository's development specifications.

### Modified Capabilities

None. The OpenSpec capability inventory is empty.

## Impact

Planning and workflow documents: `constitution.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `specs/README.md`, `specs/GUIDE.md`, `specs/STATUS.md`, `specs/RELEASE_STATE.md`, legacy features/backlogs/templates, `.claude/skills/sdd-*`, `.agents/skills/openspec-*`, OpenSpec configuration and capability specs, and the PR template. Implementation may extend `scripts/check_links.py`, its tests under `scripts/tests/`, add a focused spec checker and a documentation workflow.

No app, firmware, router runtime, generated release payload, version, rollout, signing, merge, or hardware verification changes are included. Historical records will not be deleted, bulk converted, or rewritten to imply tests or approvals that did not occur. Product capability baselining is separate work; this change establishes governance only.
