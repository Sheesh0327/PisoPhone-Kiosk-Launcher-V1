# Implementation evidence

## Approval and scope

The owner selected OpenSpec for new work and asked to apply `clean-spec-workflow`; the later instruction prioritized crucial tasks and authorized fixing status-only inventory coverage. Planning artifacts define the scope. This record does not assert approval of a merge or release.

## Structural validation, 2026-11-11

- `python scripts/check_specs.py`: 188 inventory records checked, zero structural errors. This does not prove the facts in the records.
- `python scripts/check_links.py --specs`: 125 files scanned, 41 exact historical reference occurrences excluded with reasons, zero remaining dead links.
- `python scripts/check_openspec.py`: one item validated, zero errors; the in-flight governance delta supplies bootstrap coverage.
- `openspec validate --all --strict --json`: one change passed, zero failed; the main capability inventory still contains zero published specs. This is not product specification coverage.
- Fixture suites: ten pytest cases passed and the standalone link fixtures passed; Ruff passed and mypy found no issues in six checker files.
- Git Bash `scripts/check_security_rules.sh`: security rules passed; signing workflow fixture reported nine workflows checked. WSL Bash was unavailable.
- Hosted CI, live release state, and physical hardware checks: not run as part of this change.

## Remaining review

The index `specs/cleanup-feature-review.md` links all 47 feature identities to source snapshots and local Git history. It does not certify their current status; the inventory retains unresolved evidence gaps. The source-and-gap reconciliation is recorded in the cleanup report; unresolved evidence and owner decisions remain open. Feature 055's 1.5.34 signing claim remains unverified; the local feed snapshot records 1.5.33. See `specs/cleanup-report.md` for corrections and exceptions.

## Governance scenario review

| Requirement and scenario | Evidence and limitation |
|---|---|
| New or resumed work | `specs/README.md`, the compatible SDD entry points, and `openspec/config.yaml` direct work to OpenSpec and distinguish owner approval from completed tasks. Existing product capability coverage is explicitly incomplete. |
| Router exception and missing CI | `CLAUDE.md` section 5 and `CONTRIBUTING.md` retain the beta safeguard, owner authorization and exact-candidate gate. The 052/055 historical exceptions are attributed; missing CI is a blocker rather than a merge instruction. Hosted CI for this change was not run. |
| Evidence reconciliation and missing plans | `specs/legacy-inventory.json` covers 188 discovered identities with references or gaps; `specs/cleanup-report.md` records corrections. Features 026–031 have missing criterion-level records documented in `specs/cleanup-criteria-gaps.md`; historical tests are not invented. |
| Completion, hardware and release | `specs/STATUS.md`, the dated `specs/RELEASE_STATE.md` and `docs/REAL_WORLD_TESTING.md` separate computer tests, historical release observations and unperformed hardware. RS34's retirement, retrospective RS36/RS37 reports, and unchecked RS50/RS58–RS65 retain their stated limitations. The local snapshot does not establish 1.5.34 signing. |
| Validation prerequisites | `CONTRIBUTING.md` names Python tools and commands and requires reporting unavailable checks. The initial local failures and subsequent passing reruns are in `specs/cleanup-report.md`; no prior unrun gate was retroactively passed. |
| Broken references, exceptions and empty scope | Focused link fixtures cover broken active/OpenSpec links and exact exceptions. Inventory fixtures cover missing records and malformed exceptions; OpenSpec fixtures cover empty governance coverage and missing bootstrap artifacts. The dedicated workflow is present; hosted execution remains not run. |

Unresolved findings and historical gaps are not acceptance passes or a claim that product specs are complete. Further owner decisions, CI observations, or physical checks can resolve them separately.
