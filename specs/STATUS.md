# Status board

Updated: 2026-10-23 (after router 1.5.18 was merged to `main` and published at 0%; feature 011, parts A and B). Live (`main`, all at 100%): router 1.5.17 (feature 010: full PisoWiFi on 16 MB routers, the installer copy off flash, the PisoWiFi / PisoPhone / both choice), firmware 3.11.1, app build 495. `beta` and `main` hold the same features 001 to 007, 009 and 010. Nothing has run on real hardware yet: RS28 to RS35 are owed, and the wheel's warning text needs a legal review before anyone turns it on.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 011 | Setting up a router in a real shop: **part A** first setup without the router's internet (the computer downloads the installer and the OpenWrt packages, the router checks their signatures and installs from RAM) and **part B** a coin box network of its own, so a second router set up nearby cannot take a box's place | **built and validated on a computer; hardware checks open; no pull request yet** (`specs/features/011-offline-setup/`, `validation.md`). Release would be router **1.5.18** (`setup/RELEASE` already raised) | All 15 criteria pass on a computer except **14 (hardware), not run**: RS36 (part A: WAN cable unplugged, 23.05/24.10, the owner's T6GS sources, memory) and RS37 (part B: rename on an older router, a coin during the switch, pairing next to another router) are in `docs/REAL_WORLD_TESTING.md`. Spec edited three times while building (Check-up reading, WAN address with offline packages, factory-address file list); no decision changed. Not seen: BusyBox ash, real OpenWrt package lists, real `usign`, https sources, a real ssh. Owner questions: (1) this container's `/dev/null` is a regular file (breaks one test check here, not in CI), see the ledger; (2) installer size 1635 of 1700 KB, source 144 of 150 KB: little room left for the next feature. Owner decisions: open a pull request into `beta`? release 1.5.18? |
| 010 | Fit the full PisoWiFi on 16 MB routers and a PisoWiFi-only shop: the installer copy in RAM and none left on flash, free-space warnings, the flash chip measured, the PisoWiFi / PisoPhone / both choice (owner request 2026-10-22) | **released: router 1.5.17 live at 100% (2026-10-23)** (`specs/features/010-router-flash-profile/`, `validation.md`). Release would be router 1.5.17 (`setup/RELEASE` raised) | Verified on a computer (all gates, browser tests, BusyBox ash, ShellCheck). Owed: the packages' sizes (`opkg info opennds curl ca-bundle jsonfilter libmicrohttpd-no-ssl`) and hardware check **RS35** on the owner's Qihoo 360 T6GS. The cut-down `small` image stays parked. |
| 009 | A rarer jackpot, drawn thin to scale: x5 becomes about 1 in 39 and its slice is half as wide (owner request 2026-10-22) | **released: router 1.5.16 live at 100% (2026-10-22)** (`specs/features/009-thin-jackpot/`, `validation.md`) | The owner chose "rarer jackpot, drawn thin to scale". New table mark `t` (thin slots), the draw and the picture follow sizes (slice width = chance). Proposed default `0x12 2x6 3x1 5x1t`: jackpot 2.6%, win 38.5%, return 89.7% (today 5.0%, 35%, 90%). Router release after 1.5.15. |
| 008 | The wheel as an install option on routers with more than 16 MB of flash (owner request 2026-10-22) | **parked: the owner will write a separate spec** (my draft with the facts is in `specs/features/008-wheel-install-option/`, not approved) | Facts found: the wheel's code is about 64 KB in every install since 1.5.14; the installer has no flash-size rule; `/proc/mtd` holds the chip size, `df /overlay` does not. The size budgets were raised instead (see 006). |
| 007 | The wheel looks like a real spinning-wheel game (many equal slices, rim and pointer, odds explanation) (owner request 2026-10-22) | **released: router 1.5.15 at 100%** (PR #100) | Looks only. One labelled slice per slot, wins spread between losses, cap of 24 slots, bright arcade look, odds explainer, sound (off by default), celebration on a win, recent spins in the browser. RS34 not run. |
| 006 | The wheel button for everyone (owner error report 2026-10-22: no button on a page that has not paid) | **released: router 1.5.15 at 100%** (PR #99) | With the wheel on, every visitor sees Spin the wheel and the odds; spinning needs paid time. RS34 not run. |
| 005 | Spinning wheel on the PisoWiFi portal (replaces the free mini-game) | **released: router 1.5.14, now inside 1.5.15 (PR #98 merged to `beta`, `main` published 2026-10-22); the wheel is OFF everywhere** | Needs the owner's typed confirmation in the console to turn on. `validation.md` written; hardware check **RS34** not run; **legal review of the warning text owed** (roadmap 8.3). Later: remove the old mini-game code (owner answer 3). |
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
Feature 011 (parts B and A) is merged into `beta` (PR #103, all 13 checks green) and into `main`; CI publishes **router 1.5.18 at 0%**. **Raising it to 100% waits for the hardware checks**, by the owner's instruction (2026-10-23): run **RS36** (first setup with the WAN cable unplugged, and the 360 T6GS sources) and **RS37** (the box network's own name, including a coin put in *during* the switch being credited exactly once) in `docs/REAL_WORLD_TESTING.md`, write the results into `specs/features/011-offline-setup/validation.md`, and only then run `publish-router.yml` on `main` with rollout 100 and check `verify_release.py` again. Nothing of feature 011 has run on real hardware, and `setup/src/21-local-packages.sh` has not run under BusyBox `ash`. RS28 to RS35 are still owed from earlier features, the wheel's warning text still needs a legal review, and the open owner decisions above are still open. Then R1 app rows or R2 signing and CI fixes.
Handoff notes: `specs/notes/2026-10-23_feature-011-offline-setup.md` (written after groups 0 to 2; everything in it is done now), `specs/notes/2026-10-21_feature-005-wheel-2.md`.
