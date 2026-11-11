# Legacy specification cleanup report

Inventory snapshot: repository commit `0a2b5c563faa771184a8755e6d9c213a393ef8f3`. Source and gap review completed on 2026-11-11; unresolved entries do not claim verification.

`legacy-inventory.json` version 1 contains `schema_version`, `reviewed_at`, `reviewed_commit`, and `records`. Each record has a `kind` (`feature`, `epic`, or `finding`), an ID unique within its kind, one or more repository `paths` (archive headings and finding IDs follow `#`), a `disposition`, `related_ids`, `evidence`, and `gaps`. Feature IDs unify folder and archive sources; finding IDs are scoped by epic. Permitted dispositions are `active`, `historical`, `superseded`, `cancelled`, and `unresolved`. An unresolved entry or gap does not assert an approval or test result.

## Corrections and open evidence

Evidence gaps and owner decisions remain explicit. Do not infer current deployment or hardware status from the inventory alone.

### Findings at the start of review (since corrected)

- Feature 036 and 044 were merged in commit `662d759`; the 036 and 044 validation files record computer results. The earlier `STATUS.md` said 036 had not started and 044 had no spec. Hardware checks RS58 and RS59 remain outstanding in the status record. Do not treat the computer evidence as a hardware pass.
- The older Spec 04 next-step paragraph in `STATUS.md` says to plan 045, while the same board and `RELEASE_STATE.md` record PR #131 for 045–047. The stale planning statement has been marked superseded; broader record review remains open.
- Feature 055's spec records a direct-main owner instruction while its release line says beta. Preserve the decision as a historical exception and reconcile the wording against the release evidence.

### Corrections applied

- Qualified the 2026-10-29 status header as a historical snapshot; later observations are in `specs/RELEASE_STATE.md`.
- Corrected the 036/044 status summaries using merge `662d759` and their retained feature specs and validations; RS58/RS59 remain open.
- Corrected the Spec 04 summary and marked the obsolete 045 planning note as superseded by PR #131; RS60–RS62 remain open (`specs/RELEASE_STATE.md` and feature validations).

### Historical link exception review

The explicit spec scan identifies 41 occurrences covered by 32 exact source/target exceptions in `specs/link-exceptions.json`. Each exception records a reason and, where applicable, the Git commit that removed or consolidated the target. Feature 048's planned epic consolidation was explicitly dropped, so its proposed epic consolidation file and shorthand are historical descriptions, not missing current files. Exceptions never cover an entire feature directory. The checker reports each excluded occurrence and fails on a different broken link in the same file.

## Python gate evidence

On 2026-11-11, Python 3.14.6 with Ruff 0.17.4, mypy 2.4.1 and pytest 9.2.0 in a temporary virtual environment: Ruff passed on the six checker files; mypy reported no issues in six files; pytest collected nine tests and all nine passed; standalone `python scripts/tests/test_check_links.py` passed. Initial Ruff errors in the new checker and pytest collection failure from the legacy script's top-level `sys.exit` were corrected before these reruns. These checks do not constitute hardware or hosted CI verification.

## Specification CI local run

On 2026-11-11 the workflow's local commands checked 185 inventory records, scanned 122 spec/workflow files, reported 41 historical exception occurrences, and found zero remaining broken links. Strict OpenSpec validation included the governance change (one item). The standalone link fixture suite, nine pytest cases, Ruff and mypy passed with the tool versions documented in CONTRIBUTING. Negative fixtures verify failure on invalid references and empty governance coverage. An initial scan flagged the report's own backtick reference to a deliberately uncreated file; that wording was corrected and the scan rerun. Hosted CI was not run: no PR or push has been authorized for this cleanup.
- Clarified feature 055's release line as the owner-authorized direct-main exception recorded in its own status and `specs/RELEASE_STATE.md`; the normal beta safeguard remains. Clarified that feature 036 was missing from the original 1.5.28 signed program but was included in the later 1.5.29 release record. Neither statement closes a hardware check.

## Coverage design gap

The initial inventory discovered feature directories and archive headings, but `specs/STATUS.md` also lists historical features 005, 006 and 007, whose original folders are absent from both sources. Their status rows and Git history remain. Therefore the count of 44 feature records is not complete legacy-feature coverage, and a zero-error checker run does not prove such coverage. The following repair added a defined representation and coverage checks for status-only records.

The first attempt at the required security script did not complete locally: the default Bash points to WSL without a distribution; Git Bash started the scan but had not produced a result when it was stopped for this design issue. No security pass is claimed.

### Coverage gap repaired

After the initial enumeration, individually numbered status-board rows were added as a discovery source. Features 005–007 now have status-row references and explicit evidence gaps in the inventory. The checker verifies those references against the actual rows. A negative fixture and a real pre-repair run both failed when a status-only feature was absent; after repair, 188 records passed structural checks. Review of historical claims remains outstanding.
- Reviewed status-only features 005–007 against their historical validation files at `e0a1453^` and feature 033's retained archive entry. The old validations describe computer checks; RS34 was retired because the wheel was removed before it ran (`docs/REAL_WORLD_TESTING.md`). The inventory marks these features superseded, not hardware-verified. Status rows now identify their release descriptions as historical.
- Feature 055's former status claimed router 1.5.34 was signed at 0%. The locally recorded `origin/main` at `0a2b5c5` contains the feature commit but its `website/update/router.json` still records 1.5.33 at 100%; the available local history does not substantiate the 1.5.34 signing claim. The status now marks signing and rollout unverified rather than inferring live state. Its validation also states the old-to-new update is not directly asserted, and RS65 has not run.
- Marked the obsolete 1.5.26 rollout question and 2026-10-29 next-step sequence as historical: `specs/RELEASE_STATE.md` records 1.5.29 at 100% and 036 merged. Clarified that 1.5.30 was superseded by 1.5.31; outstanding bench checks remain open.

### Verification after coverage repair

The security gate completed under Git Bash on 2026-11-11: `security rules: all passed` and the signing workflow test reported nine workflows checked. WSL Bash was unavailable; the installed Git Bash was used. After the status-only coverage repair, the standalone link fixture passed, pytest passed 10 cases, Ruff passed, and mypy reported no issues in six checker files. Hosted CI remains unrun.
- Reconciled AO-11: feature 021's approved spec, computer validation, and merge `69c8cfd` support `fixed` on the backlog row. The old decision options remain in the row; RS45 remains not run. Updated the 2026-10-29 roadmap sequence to mark 1.5.26 and the 036 planning step as superseded by later recorded events.

- Reconciled conflicting feature 011 hardware summaries: the original archived status says not run, while the later checklist records owner-reported RS36 and RS37 passes without per-step readings. RS36 was subsequently retired. The status board now preserves both the timing distinction and the evidence limitations rather than asserting a fresh run.
- Reconciled AO-01, AO-05, AO-06 and AO-09 against feature 015's computer validation and recorded them as `fixed`, retaining hardware checks separately; AO-01 also has the subsequent feature 052 correction. AO-03 remains open because its stated timing and memory targets were not measured; AO-04 needs a superseded disposition after the wheel's removal; AO-07 retains its unconfirmed CI and hardware gaps.
- Linked the five fixed firmware money rows to feature 004 and the eight fixed app money rows to feature 018, checking their retained validation at `2b2bdc2^`. The firmware red results were missing-header build errors, not failed logic assertions; the historical report makes that limitation explicit. The app validation records CI tests and builds; the status board says APP-03, APP-05 and APP-09 have no unit test naming those rows. No hardware verification is inferred.
- Reviewed the fixed feature 001 router rows, feature 002 setup row ST-1, and feature 003 tablet row against validations retained at `2b2bdc2^`. Feature 001's original red run was on the prior branch, with local mutation repeated for RT-01 and RT-07 only; RS32 was not run in that validation. Feature 002's validation predates CI, and feature 003's passing JVM test did not establish the tablet/game behavior. Inventory gaps preserve those distinctions.
- Reviewed all 18 Spec 04 findings against the retained feature 045–047 validations and the hardware checklist. The status board incorrectly listed SU-02 as fixed; its finding is `wont-fix` because feature 041 removed the offline package path. RS60–RS62 remain open; SU-08 depends on RS61(c), SU-14 on RS60(b), and feature 047's ESP32-only portions were not locally run.
- Reviewed later feature records 033–055 against retained validations, the local Git/merge history, dated release summaries, and the hardware checklist. The inventory records specific limitations for each: 040 bench gates and 043 group-0 evidence remain unresolved; RS57–RS65 are not claimed as run; 052 and 055 retain their attributed direct-main exceptions; 049 and 053 have later release observations than their original feature snapshots. The list excludes absent feature IDs and does not turn a local feed snapshot into a current live-state claim.
- Feature 026 has an owner-reported improvement in STATUS and RELEASE_STATE, but the full RS50 checklist remains unchecked and no run scope or detailed readings are recorded. Both summaries now distinguish that report from a completed RS50 rather than implying the complete check passed.
- Reviewed feature identities 001–032 against retained feature documents, dated status summaries, Git history and the hardware checklist. The inventory now records specific limitations instead of a generic pending marker: among others, 014 did not run the long soak, 020 did not execute production signing workflows in its validation, 026 lacks a retained validation file and has an unchecked RS50, 027–031 lack retained plans and validations, and 032 recorded a baseline setup-test failure and unrun Python gates. Historical and superseded dispositions do not claim that a hardware check passed.
- Linked the feature 017 firmware findings and RT-17 to their validations without changing their open statuses; feature 017 itself records missing local compile and board checks. Also linked AO-02 to feature 032's partial gate evidence, AO-08 to feature 018's measurement-only scope, and AO-10 to the unrun 30-day-equivalent soak in feature 014.
- Linked the remaining explicit early-feature associations: feature 016's five router findings, feature 020's CI-1 and CI-2, and feature 015's AO-02–AO-04. Their original backlog statuses remain unchanged where the historical validation used fakes, did not execute signing workflows, or left performance or supersession questions open.
- Annotated the historical status lines in epics 03–05 with later merge and dated release records, without inventing a separate epic approval or hardware pass. The original planning statements remain intact.
- Reviewed the remaining epic and findings source rows. Open and owner-decision entries remain open; the inventory links their source rows and records when no feature association or independently established result is available. The inventory review date records this source-and-gap review, not a claim that every product behavior, CI run, or hardware check has been verified. D29, the 040/043 gaps, and remaining backlog decisions are still explicit.
- Qualified the release record as a dated feed observation and removed an unsupported claim about what every router installed at 0% rollout. Feature 045's status now preserves the recorded baseline setup-test failure after its rebase; 046's 1.5.30 release is marked superseded by 1.5.31, with RS61 still open.
- Added `specs/cleanup-criteria-gaps.md` to list each retained criterion without criterion-level evidence for features 026–029, plus the missing numbered criteria for 030–031. “Not reconstructed” describes this review; it does not assert that prior tests never occurred. The linked hardware checks remain unchecked.
- Final diff review restored the existing snippet limits and pre-code context-manifest requirement, which did not conflict with the OpenSpec transition; provider-specific cache/model requirements remain conditional as planned.

## Final diff and scope review

The reviewed inventory has 188 records: 47 feature identities, five epics and 136 findings. Dispositions: 31 historical, 107 unresolved, four superseded and 46 active. Every record has a source reference or a gap; unresolved does not mean a failed test or a verified product claim. Open owner decisions, missing historical criteria evidence, unperformed hardware checks and the local 1.5.34 signing uncertainty remain visible.

The tracked diff contains 27 modified workflow, policy, documentation, backlog, or checker files. New files include the dedicated CI workflow, the two checkers and tests, cleanup reports, inventory and exception list, and the requested OpenSpec change artifacts. `.agents/` and `openspec/` were already untracked before this work; the selected change and project config are the intended OpenSpec edits, and the generated skills were not modified by this cleanup. Nothing is staged; no blanket `git add` was run. No app, firmware, router runtime, signed payload, release version or rollout was changed. Hosted CI and live-device state remain unverified.

On the final local run: inventory 188 records / zero structural errors; scoped links 125 files / 41 documented historical exclusions / zero remaining dead references; OpenSpec one change passed strict validation (the main product capability inventory remains empty); standalone link fixtures passed; pytest ten passed; Ruff passed and mypy reported no issues in six files. The spec workflow YAML parsed and its push/PR filters agreed. `git diff --check` reported no whitespace errors (Git emitted line-ending notices).

The Git Bash security gate was rerun after the final checker and record changes and reported `security rules: all passed`; its signing test checked nine workflows. Hosted CI remains not run.
