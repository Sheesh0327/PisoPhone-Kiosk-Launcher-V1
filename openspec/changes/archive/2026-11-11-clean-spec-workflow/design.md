# Design

## Context

See proposal.md for motivation. Inspection found an empty OpenSpec capability inventory, untracked OpenSpec and agent scaffolding, and a mature legacy feature tree. Current contradictions include constitution section 8 branching from beta versus CONTRIBUTING branching from main, mandatory Python Ruff/mypy/pytest versus recorded standalone checks, and same-chat retry prohibition versus GUIDE retry instructions. STATUS still calls 036 unstarted and 044 unspecified alongside merged rows. The legacy validation skill updates CLAUDE section 2 even though RELEASE_STATE explicitly owns release facts. The quality workflow omits spec-only triggers; check_links.py excludes all legacy features and reads tracked files only.

## Goals / Non-Goals

**Goals:** Establish reviewable policy decisions, exhaustive record coverage, and a small enforceable integrity contract. Separate mechanical validation from human assessment of evidence.

**Non-Goals:** Prove every product behavior, run hardware checks, rewrite historical prose for style, remove old evidence, adopt a new test framework throughout the project, or alter production/release automation.

## Decisions

### 1. OpenSpec transition with retained history

The owner selected OpenSpec for new work and preservation/reconciliation of legacy records. Use specs/README.md as the concise navigation and authority map: OpenSpec capability specs for established requirements, OpenSpec changes for proposed work, legacy feature files for their historical approvals/evidence, STATUS for summaries, RELEASE_STATE for dated release observations, and REAL_WORLD_TESTING for outstanding physical checks. Published OpenSpec product coverage remains incomplete and must be stated explicitly.

New or resumed implementation uses an OpenSpec change referencing its legacy ID where applicable. Existing approved scope is reusable evidence, not approval for additional scope. Record owner approval in the change's evidence.md with scope and reference; checked tasks alone are not approval. Record criterion results in the same companion file. Archive only implemented scope with documented remaining verification obligations; archiving is not hardware certification.

Update the legacy SDD skills to compatibility entry points, and legacy templates to clearly labelled historical references. Put project-specific OpenSpec constraints in config context/rules and project guides rather than modifying generated skill internals. Inspect generated skills for contradictory project directives but avoid forking generic upstream instructions. Version the frozen constitution as an explicit workflow revision. Provider-specific cache/model instructions become conditional guidance where supported, never fabricated mandatory telemetry.

Alternative rejected: bulk migration would imply a product baseline and risk losing historical semantics. Keeping both workflows active would retain competing authorities.

### 2. Concrete policy reconciliation

Keep CONTRIBUTING as the executable check matrix and CLAUDE section 5 as the branch/release safety reference; other current documents link to them rather than restating divergent rules. Feature work starts from main; router changes retain the beta build route before release. Historical 052/055 direct-main exceptions remain attributed exceptions.

Preserve owner-controlled PR/merge/release actions and pre-merge green checks on the exact candidate commit. Document the authorized PR route for pre-merge CI; where no PR is authorized and the exact candidate has no qualifying run, report the blocked merge rather than recommending merge-first. This cleanup does not add feature-push production workflows or relax the gate.

Preserve the existing required Python Ruff/mypy/pytest gates; document installation, scope, and exact supported commands alongside standalone area tests. Missing tooling or unsupported test collection is a not-run blocker, not an implied exemption. No mass lint/type remediation is included: pre-existing failures are reported separately and prevent unsupported completion claims. Replace the contradictory blanket no-retry rule with bounded retries after a recorded diagnosis/change; distinguish intentional red regression tests from failed acceptance gates. These are proposed workflow revisions requiring approval of this plan before application.

### 3. Evidence inventory and conservative corrections

Create specs/legacy-inventory.json with schema_version, reviewed_at, reviewed_commit, and records. Each record has a unique kind/id, existing path or archive anchor, disposition (active, historical, superseded, cancelled, unresolved), related feature/backlog IDs, evidence references, and gaps. Include every feature directory except _template, every feature entry in ARCHIVE.md, each individually numbered feature row in STATUS.md, every epic and every findings JSON row. A status-only feature uses the verifiable reference `specs/STATUS.md#feature-NNN`, identifying its table row; historical evidence gaps remain explicit. Do not force missing legacy plans into existence.

Create specs/cleanup-report.md listing corrections with source references and unresolved contradictions. Review feature spec/plan/validation together, relevant git history, backlog entries, RELEASE_STATE, feed snapshots, and hardware checklist. Prefer explicit owner decisions for intent; use commits for merge facts, retained test output for validation, and dated feed evidence for publication. A newer summary alone does not override stronger evidence. Unavailable remote evidence remains an explicit gap.

Correct STATUS header and feature/epic/next-step contradictions, including 036/044, 045-047, superseded release decisions, and the mismatch between 055's branch label and recorded exception. Audit all other records using the same procedure rather than limiting repairs to these examples. Historical release assertions remain dated; no claim of current remote rollout is made from a local file alone.

### 4. Focused automated checks

Add scripts/check_specs.py using the Python standard library, with fixture tests under scripts/tests/. Validate inventory shape, unique IDs, full discovered-record coverage, referenced paths, and structured exception entries. Do not attempt to infer truth from free-form prose or regex-match every status sentence; human reconciliation remains required.

Extend check_links.py compatibly with an explicit specification scope that includes untracked, non-ignored planning files locally and tracked files in CI. Check current OpenSpec files, current workflow documents, templates with placeholders respected, and legacy feature documents. Retain ordinary historical handling for notes/replans/changelog; allow exact source/target/reason exceptions for historical feature references instead of excluding the entire tree. Report scanned counts and exceptions. Keep existing default callers compatible.

Add a dedicated .github/workflows/spec-quality.yml for PRs into main/beta and pushes to main/beta, filtered to specs/**, openspec/**, constitution, CLAUDE, CONTRIBUTING, workflow skills, PR template, checker/tests, and the workflow itself. Run link/inventory checks plus pinned OpenSpec 1.17.0 strict validation and report counts. Require at least the governance main spec OR this valid in-flight governance delta during bootstrap. Empty main specs during this change are expected; zero applicable baseline/change coverage is not success. Do not edit production build triggers. Document the dedicated check name; changing hosted branch protection is outside this repository change.

Negative fixtures cover broken current links, duplicate/missing inventory entries, invalid dispositions, missing required artifacts, and empty validation scope. Positive fixtures cover legitimate archive references, justified exceptions, placeholders, and an in-flight bootstrap change. A clean run establishes structural consistency, not proof that historical tests really ran.

## Risks / Trade-offs

- Historical contradictions may lack resolvable evidence -> retain explicit gaps with affected criteria; never fabricate closure.
- Stricter checks may expose old debt -> use narrow reviewed exceptions and report their count; never disable whole feature coverage.
- Tool upgrades can overwrite generated skills -> keep repository policy in guides/config and compatibility wrappers.
- Python gates may already fail -> report baseline failures separately and do not silently weaken policy or expand into product fixes.
- Untracked scaffolding can be missed by git-based checks -> include non-ignored local spec files and review exactly which scaffolding belongs in a later commit; do not blanket-add unrelated files.

## Migration Plan

1. Obtain approval of these artifacts, including proposed policy reconciliation; implementation starts only on a new apply request.
2. Add the inventory and correction report, reconcile current records, then align the authority map and entry points.
3. Add checker regression fixtures and documentation CI. Validate a spec-only change path and intentional failure cases locally; record CI as not run if no authorized PR/push exists.
4. Produce change evidence.md mapping every requirement to checks and human review, with unresolved historical gaps separated from structural defects. Do not mark the cleanup complete while unexplained current contradictions remain; explicitly qualified unknowns are valid outcomes.
5. Sync/archive the governance delta only through the applicable workflow after implementation review. Preserve legacy paths. Rollback consists of reverting the cleanup changes; retained historical files and product artifacts remain intact.
