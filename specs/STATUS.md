# Status board

Updated: 2026-10-22 (replan `specs/replans/2026-10-22.md`). Live (`main`, all at 100%): router 1.5.14 (the wheel, off by default), firmware 3.11.1, app build 495. Owner report: the wheel button is hidden from visitors who have not paid; fix planned as feature 006 (router 1.5.15). Nothing has run on real hardware yet: RS28 to RS34 are owed, and the wheel's warning text needs a legal review before anyone turns it on.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 009 | A rarer jackpot, drawn thin to scale: x5 becomes about 1 in 39 and its slice is half as wide (owner request 2026-10-22) | **approved by the owner 2026-10-22 (proposed default confirmed); starts after router 1.5.15 is live; spec on branch `feat/009-thin-jackpot`** (`specs/features/009-thin-jackpot/`) | The owner chose "rarer jackpot, drawn thin to scale". New table mark `t` (thin slots), the draw and the picture follow sizes (slice width = chance). Proposed default `0x12 2x6 3x1 5x1t`: jackpot 2.6%, win 38.5%, return 89.7% (today 5.0%, 35%, 90%). Router release after 1.5.15. |
| 008 | The wheel as an install option on routers with more than 16 MB of flash (owner request 2026-10-22) | **parked: the owner will write a separate spec** (my draft with the facts is in `specs/features/008-wheel-install-option/`, not approved) | Facts found: the wheel's code is about 64 KB in every install since 1.5.14; the installer has no flash-size rule; `/proc/mtd` holds the chip size, `df /overlay` does not. The size budgets were raised instead (see 006). |
| 007 | The wheel looks like a real spinning-wheel game (many equal slices, rim and pointer, odds explanation) (owner request 2026-10-22) | **built on the computer, PR into `beta` opened** (`specs/features/007-wheel-graphics/`) | Looks only: odds, limits and money path unchanged. One labelled slice per slot with wins spread between losses, a cap of 24 slots, bright arcade look, odds explainer, sound (off by default), celebration on a win, recent spins in the browser. `validation.md` written; RS34 not run. Ships as router 1.5.15 with 006. |
| 006 | The wheel button for everyone (owner error report 2026-10-22: no button on a page that has not paid) | **planned, waits for the owner's approval** (`specs/features/006-wheel-button/`) | With the wheel on, every visitor sees Spin the wheel (also on the "You are online" screen) and the odds; spinning still needs paid time. Router 1.5.15. Cause: 005's rule "before payment shows no wheel"; replan `specs/replans/2026-10-22.md`. |
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
Feature 006 waits for the owner's approval (`/sdd-implement` after the yes). Owner-side meanwhile: run the hardware checks RS28 to RS34 (RS33 on the tablet with `adb`), get the wheel's warning text read by a lawyer, answer the open decisions above. Then R1 app rows or R2 signing and CI fixes.
Handoff notes: `specs/notes/2026-10-21_feature-005-wheel-2.md` (written before the wheel was finished; `specs/features/005-spinning-wheel/plan.md` and `validation.md` are current).
