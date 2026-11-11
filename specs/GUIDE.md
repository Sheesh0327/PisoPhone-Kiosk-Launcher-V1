# Developing with specifications

Read `specs/README.md` for the authority map, new-work and legacy-transition procedures. Use OpenSpec changes for planning, approval, implementation tasks and evidence; retain legacy records for their historical scope. Product capability coverage in OpenSpec is incomplete.

Write testable intent, constraints and acceptance criteria before implementation. Record owner approval and its scope separately from task completion. If implementation needs material new scope, update the change and obtain approval before proceeding. For each criterion record the result and evidence, or why it was not run. Computer validation, hardware verification, merging and release are separate facts.

Use `CONTRIBUTING.md` for applicable commands, including Python gates and security rules. Record a failed acceptance gate and its diagnosis; a bounded retry after diagnosis or a change is allowed. An intentional failing regression test is not a failed acceptance gate. Never represent unavailable tooling or hardware as a pass.

Start a feature branch from `main`. Follow `CLAUDE.md` section 5 for router beta build safeguards and exact-candidate pre-merge checks. Open PRs, merge and release only when the owner asks. If the required candidate check has not run, report the missing evidence rather than merge to trigger it.

## Historical templates and cache guidance

`specs/features/_template/` describes the earlier legacy workflow; it is retained for reading old records, not for creating a second contract for new work. `specs/templates/` contains supporting templates. Cache behavior, telemetry, provider model choices and chat limits are runtime-specific; measure only when supported and do not claim a universal cache hit rate. Keep handoffs when useful and preserve unresolved findings.
