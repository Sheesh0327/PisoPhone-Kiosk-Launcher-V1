# Status board

Updated: 2026-10-22. Live (`main`, all at 100%): router 1.5.14 (the wheel, off by default), firmware 3.11.1, app build 495. `beta` and `main` hold the same features 001 to 005. Nothing has run on real hardware yet: RS28 to RS34 are owed, and the wheel's warning text needs a legal review before anyone turns it on.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 005 | Spinning wheel on the PisoWiFi portal (replaces the free mini-game) | **released: router 1.5.14 at 100% (PR #98 merged to `beta`, `main` published 2026-10-22); the wheel is OFF everywhere** | Needs the owner's typed confirmation in the console to turn on. `validation.md` written; hardware check **RS34** not run; **legal review of the warning text owed** (roadmap 8.3). Later: remove the old mini-game code (owner answer 3). |
| 004 | Firmware money rows FW-01, 02, 03, 05, 06 (firmware 3.11.1) | **released: firmware 3.11.1 (PR #97)** | `specs/features/004-firmware-money/`. Not tried on a box: RS28 to RS31. |
| 003 | Tablet game touch (APP-27) | **released: app stable build 495** (PR #96) | Cause is a hypothesis: **owner runs RS33 on the tablet**; if touch is still dead the adb output decides the next spec edit. |
| 002 | Restore firmware 3.11.0 (product images, USB Wi-Fi setup, coin-counter hardening) + ST-1 | **released with firmware 3.11.1** (PR #94) | Not tried on a box. |
| 001 | Router money and time (RT-01 to 07, 14) | **released: router 1.5.13, now inside 1.5.14** | Not tried on a router: RS32. |

## Backlog: Spec 01 (`specs/spec_01_findings.json`, 107 rows: 15 `fixed`, 87 `open`, 5 `owner-decision`)

Earlier work exists on branch `claude/sleepy-gauss-rn31gl` (tip `748add9`) with tests that failed first. It is **not on `beta`**. 38 rows carry `prior_work`; bring each back through its own feature spec by cherry-picking, not rewriting. Nothing from it has run on real hardware.

| Package | What is on that branch | Rows |
|---|---|---|
| R1 money and time | Firmware 3.11.1 (FW-01, 02, 03, 05, 06: **brought back in feature 004**); app (APP-01, 02, 03, 05, 06, 08, 09, 10, not compiled); router (RT-01 to 07, 14: **done on `beta`**) | 21 |
| R2 signing and release | CI-1, 2, 4, 7, 9; ST-1, 3, 4; SC-1, 2, 3, 7; ktlint hash | 12 |
| R3 reliability | RT-08, 10, 18, 22, 26 (router 1.5.15) | 5 |
| R3 to R6 rest | not started | the remaining open rows |

## Ready to plan (suggested order: money first, then safety)

1. R1 app: APP-01 to 10 (needs a machine with the Android SDK).
2. R2 signing and CI fixes.

## Owner decisions open

ST-2 (signed flash manifest), SC-4 (`scripts/apk_production_cert.txt` empty), ST-8 (card and slot limits), DEAD-1 (root `package.json`), APP-04 (refuse coins until restore), roadmap 0.1 (any boxes in legacy mode?).

## Next step
Owner-side first: run the hardware checks RS28 to RS34 (RS33 on the tablet with `adb`), get the wheel's warning text read by a lawyer, answer the open decisions above. Then the next feature is R1 app rows (needs the Android SDK) or R2 signing and CI fixes; start it with `/sdd-plan`.
Handoff notes: `specs/notes/2026-10-21_feature-005-wheel-2.md` (written before the wheel was finished; `specs/features/005-spinning-wheel/plan.md` and `validation.md` are current).
