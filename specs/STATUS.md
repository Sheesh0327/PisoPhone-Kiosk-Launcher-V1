# Status board

Updated: 2026-10-21. `beta` was recreated from `main` (`49ebad4`) with only the spec-driven files added. Live: router 1.5.12, firmware 3.10.0.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 001 | Router money and time (RT-01 to 07, 14) | **spec and plan drafted, waiting for owner approval** | `specs/features/001-router-money-time/`. Patch verified on `claude/sleepy-gauss-rn31gl` (89 cargo tests pass); not on `beta`; does not apply cleanly (depends on `4b6312d`). Owner question 1 open. |

## Backlog: Spec 01 (`specs/spec_01_findings.json`, 106 rows, all `open` or `owner-decision`)

Earlier work exists on branch `claude/sleepy-gauss-rn31gl` (tip `748add9`) with tests that failed first. It is **not on `beta`**. 38 rows carry `prior_work`; bring each back through its own feature spec by cherry-picking, not rewriting. Nothing from it has run on real hardware.

| Package | What is on that branch | Rows |
|---|---|---|
| R1 money and time | Firmware 3.11.1 (FW-01, 02, 03, 05, 06); app (APP-01, 02, 03, 05, 06, 08, 09, 10, not compiled); router (RT-01 to 07, 14) | 21 |
| R2 signing and release | CI-1, 2, 4, 7, 9; ST-1, 3, 4; SC-1, 2, 3, 7; ktlint hash | 12 |
| R3 reliability | RT-08, 10, 18, 22, 26 (router 1.5.15) | 5 |
| R3 to R6 rest | not started | the remaining open rows |

## Ready to plan (suggested order: money first, then safety)

1. R1 router: RT-01 to 07, 14 (cherry-pick from the branch).
2. R1 firmware: FW-01 to 06.
3. R1 app: APP-01 to 10 (needs a machine with the Android SDK).
4. R2 signing and CI fixes.

## Owner decisions open

ST-2 (signed flash manifest), SC-4 (`scripts/apk_production_cert.txt` empty), ST-8 (card and slot limits), DEAD-1 (root `package.json`), APP-04 (refuse coins until restore), roadmap 0.1 (any boxes in legacy mode?).

## Next step
Owner reads `specs/features/001-router-money-time/spec.md` and `plan.md`, answers owner question 1, and approves; then `/sdd-implement`.
