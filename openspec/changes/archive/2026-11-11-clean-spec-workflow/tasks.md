# Tasks

## 1. Record coverage and evidence

- [x] 1.1 Create specs/legacy-inventory.json with the schema in design.md and document it in specs/cleanup-report.md; verify every feature directory, archived feature, individually numbered status-board feature, epic, and findings row has one unique inventory identity (multiple sources for one feature share that identity).
- [x] 1.2 Reconcile features 001-032 and their linked backlog rows against retained files and git evidence; verify each inventory record has a disposition and evidence references or explicit gaps, preserving all outstanding hardware checks.
- [x] 1.3 Reconcile features 033 onward and their linked backlog rows, including 036/044, 045-047, 049, 052 and 055; verify the report distinguishes historical exceptions, computer checks, hardware checks, merge and publication evidence.
- [x] 1.4 Reconcile remaining epics/findings rows, STATUS header/next steps, RELEASE_STATE summaries, roadmap current claims and stale decisions; verify every correction has a source, every unresolved conflict is qualified, and inventory coverage has no omissions. Preserve dated history and existing feature paths.

## 2. Authoritative workflow and policy

- [x] 2.1 Rewrite specs/README.md as the authority map and transition guide, with OpenSpec for new/resumed work and legacy evidence links; verify both a new feature and resumed legacy feature can be followed without creating a second legacy contract.
- [x] 2.2 Version the constitution and align CLAUDE, CONTRIBUTING, GUIDE and session templates with the approved policy decisions in design.md; verify consistent branch routes, exact-candidate pre-merge checks, owner approval, bounded retries, and conditional provider telemetry guidance.
- [x] 2.3 Document executable Python gate prerequisites and scopes in CONTRIBUTING alongside existing area checks; execute the documented commands on the cleanup checker files once present, and record absent tools or baseline failures without granting silent exemptions.
- [x] 2.4 Convert .claude/skills/sdd-* to compatible navigation/handoff instructions and label legacy feature templates as historical; verify release updates point to RELEASE_STATE, and no current entry point requires new legacy feature folders or edits to CLAUDE release facts.
- [x] 2.5 Add project OpenSpec context/rules and update the PR template to reference change artifacts and criterion evidence; verify generated .agents skills remain generic, approval is distinct from checked tasks, and incomplete product capability coverage is explicitly documented.

## 3. Integrity tooling and focused tests

- [x] 3.1 Add scripts/check_specs.py and scripts/tests/test_check_specs.py with inventory schema/coverage/reference validation, including status-only feature rows; verify fixtures reject duplicate IDs, missing records, invalid dispositions, invalid references and malformed exception entries while accepting documented evidence gaps and archive references. Document the command in CONTRIBUTING.
- [x] 3.2 Extend scripts/check_links.py with explicit spec scope and focused tests in scripts/tests/test_check_links.py; verify broken active-feature and OpenSpec references fail, untracked non-ignored local planning files are covered, and existing default behavior remains compatible.
- [x] 3.3 Add exact historical reference exceptions with source, target and reason plus scanned/excluded counts; verify an allowed historical link passes and a different broken link in the same feature still fails. Document exception review in the cleanup report.
- [x] 3.4 Add strict OpenSpec validation orchestration and bootstrap coverage checks; verify missing required artifacts and zero governance coverage fail, while a valid in-flight governance delta or published governance baseline passes. Keep incomplete unrelated draft changes distinguishable from changes claiming readiness.
- [x] 3.5 Run the checker fixture suites, applicable Python gates and security check; record exact commands/results and fix defects introduced by this change. Verify any unrelated baseline failures remain clearly reported and do not become a false pass.

## 4. Documentation CI

- [x] 4.1 Add .github/workflows/spec-quality.yml with pinned OpenSpec tooling and the documented path filters; verify representative spec-only, skill-only, constitution-only and checker-only changes match, runtime-only changes do not require this dedicated gate, and production workflows are unchanged.
- [x] 4.2 Run the exact documentation job commands locally and exercise negative fixtures; verify nonzero failure propagation, meaningful scanned counts, and matching instructions in CONTRIBUTING/PR guidance. Record hosted CI as not run unless a PR or push is separately authorized.

## 5. Integrated acceptance review

- [x] 5.1 Run the full spec integrity checks and openspec validate --all --strict; verify the governance change is actually included and no missing current references or unaccounted inventory records remain. Record command output and counts in this change's evidence.md.
- [x] 5.2 Walk every spec-governance scenario against the final documents/tooling and evidence; verify workflow entry points agree, current contradictions are corrected or explicitly unresolved, and no historical hardware check, approval or release fact was fabricated.
- [x] 5.3 Review the final diff and cleanup report with artifact/record counts; verify edits stay within workflow, documentation and integrity tooling, all proposed policy revisions are visible, and unrelated pre-existing untracked files were not silently included. List any blockers before claiming implementation complete.

## Workflow follow-up

- After implementation review, sync/archive through the appropriate OpenSpec workflow and verify the governance main spec retains its purpose and requirements.
- Commit, PR, merge, and hosted branch-protection changes require their normal authorization; none is performed by this planning change.
