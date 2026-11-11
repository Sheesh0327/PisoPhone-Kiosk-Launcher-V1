# spec-governance Specification

## Purpose

Give contributors one reliable specification workflow, with traceable historical records and checks that expose missing evidence rather than implying verification.

## Requirements

### Requirement: One authority for new work
New work SHALL use OpenSpec changes and approved planning artifacts. Published capability specifications SHALL describe only established behavior. Legacy records SHALL remain available at their existing IDs and paths; continuing legacy scope SHALL link from an OpenSpec change to the prior approval and evidence without treating old approval as approval of new scope.

#### Scenario: Contributor starts or resumes work
- **WHEN** a contributor follows any supported planning entry point
- **THEN** it directs new work to OpenSpec and identifies the retained legacy records and owner approval needed before implementation.

### Requirement: Consistent operational policy
Current workflow entry points SHALL reference a common branch, CI, validation, and release policy. The policy SHALL preserve owner authorization for merges and releases, router beta build safeguards, and passing required checks on the candidate commit before production merge. A historical exception SHALL NOT silently become the default policy.

#### Scenario: Past direct-main exception exists
- **WHEN** a contributor encounters a router feature with a recorded owner exception
- **THEN** that record remains historical and the current policy still describes the normal router beta route and explicit exception handling.

#### Scenario: CI is unavailable before merge
- **WHEN** required checks have not run on the candidate commit
- **THEN** the workflow reports the missing evidence and does not direct a contributor to merge merely to obtain the required check result.

### Requirement: Evidence-backed reconciliation
Every legacy feature and backlog record SHALL be inventoried with a disposition and evidence references or an explicit evidence gap. Corrections to current summaries SHALL identify their source and preserve original historical intent. Conflicting or missing evidence SHALL remain visible as unresolved; it SHALL NOT be reconstructed as a successful test or approval.

#### Scenario: Summary contradicts a merged feature record
- **WHEN** an epic says a feature is unstarted but identifiable merge and validation evidence exists
- **THEN** its current summary is corrected with those references while any unverified hardware criteria remain open.

#### Scenario: Historical plan is missing
- **WHEN** a legacy feature lacks a plan or validation artifact
- **THEN** the inventory records the gap and any available historical reference without inventing retrospective approval or test output.

### Requirement: Separate completion from verification
Records SHALL distinguish implementation, computer validation, hardware validation, merge, and release status. Each acceptance criterion SHALL have a result and evidence or an explicit not-run reason. Release snapshots SHALL identify their observation date and source; repository feed data SHALL NOT be described as a fresh observation of deployed devices.

#### Scenario: Software shipped with hardware tests outstanding
- **WHEN** a released feature has an unperformed hardware criterion
- **THEN** the feature retains its release fact and linked outstanding hardware check without being labelled fully verified.

### Requirement: Executable and honest validation policy
The workflow SHALL name applicable checks, required tool prerequisites, and how failures or unavailable checks are reported. Expected failing regression tests SHALL be distinguished from acceptance-gate failures. Updating the policy SHALL NOT retroactively mark earlier unexecuted checks as passed.

#### Scenario: Required tool is absent
- **WHEN** a contributor cannot run a required check
- **THEN** the validation record names the check and blocker and leaves its result not run rather than substituting a different command as a pass.

### Requirement: Automated specification integrity
Specification-only changes SHALL trigger a dedicated integrity gate that checks OpenSpec artifacts, current document references, and structured legacy inventory consistency. The gate SHALL report checked counts and fail for broken current references, malformed records, or missing required artifacts. Historical exclusions SHALL be explicit and reasoned, not a blanket exclusion of all feature records.

#### Scenario: Broken current feature reference
- **WHEN** a current feature summary points to a nonexistent evidence file
- **THEN** the integrity gate fails with the source and missing target unless a specific historical exception documents that reference.

#### Scenario: Empty validation scope
- **WHEN** validation finds no applicable specifications or changes despite an expected governance baseline
- **THEN** it reports the empty scope as a failure rather than claiming successful specification coverage.

#### Scenario: Historical exception is used
- **WHEN** a recorded historical reference is excluded
- **THEN** the report lists the exact exception and its reason while continuing to check current records.
