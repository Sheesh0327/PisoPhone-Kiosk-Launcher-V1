# Status board

Updated: 2026-10-21 (replan `specs/replans/2026-10-21.md`). `beta` is at `b76f909`. Live (`main`): router 1.5.12, firmware 3.10.0. On `beta`, not released: router 1.5.13, firmware 3.11.0, the feature 003 app change.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 003 | Tablet game touch (APP-27) | **merged to `beta`** (PR #96) | Unit tests, lint, APK build green on the PR. Cause is a hypothesis: **owner runs RS33 on the tablet**; if touch is still dead the adb output decides the next spec edit. |
| 002 | Restore firmware 3.11.0 (product images, USB Wi-Fi setup, coin-counter hardening) + ST-1 | **merged to `beta`** (`60c8eaf`) | Code quality green after one re-run (flake). Not tried on a box. |
| 001 | Router money and time (RT-01 to 07, 14) | **merged to `beta`** | Router 1.5.13. Not tried on a router: RS32. |

## Backlog: Spec 01 (`specs/spec_01_findings.json`, 106 rows, 10 `fixed`, 92 `open`, 5 `owner-decision`)

Earlier work exists on branch `claude/sleepy-gauss-rn31gl` (tip `748add9`) with tests that failed first. It is **not on `beta`**. 38 rows carry `prior_work`; bring each back through its own feature spec by cherry-picking, not rewriting. Nothing from it has run on real hardware.

| Package | What is on that branch | Rows |
|---|---|---|
| R1 money and time | Firmware 3.11.1 (FW-01, 02, 03, 05, 06); app (APP-01, 02, 03, 05, 06, 08, 09, 10, not compiled); router (RT-01 to 07, 14: **done on `beta`**) | 21 |
| R2 signing and release | CI-1, 2, 4, 7, 9; ST-1, 3, 4; SC-1, 2, 3, 7; ktlint hash | 12 |
| R3 reliability | RT-08, 10, 18, 22, 26 (router 1.5.15) | 5 |
| R3 to R6 rest | not started | the remaining open rows |

## Ready to plan (suggested order: money first, then safety)

1. R1 firmware: FW-01 to 06.
2. R1 app: APP-01 to 10 (needs a machine with the Android SDK).
3. R2 signing and CI fixes.

## Owner decisions open

ST-2 (signed flash manifest), SC-4 (`scripts/apk_production_cert.txt` empty), ST-8 (card and slot limits), DEAD-1 (root `package.json`), APP-04 (refuse coins until restore), roadmap 0.1 (any boxes in legacy mode?).

## Next step
Replan done. Owner picks feature 004: R1 firmware money rows (suggested), R1 app rows, or R2 signing. Code quality on `beta` at `b76f909` was still running at replan time; do not push to `beta` until it finishes.
