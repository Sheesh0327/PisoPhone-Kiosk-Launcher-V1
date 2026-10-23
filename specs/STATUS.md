# Status board

Updated: 2026-10-23 (after router 1.5.17 at 100%). Live (`main`, all at 100%): router 1.5.17 (feature 010: full PisoWiFi on 16 MB routers, the installer copy off flash, the PisoWiFi / PisoPhone / both choice), firmware 3.11.1, app build 495. `beta` and `main` hold the same features 001 to 007, 009 and 010. Nothing has run on real hardware yet: RS28 to RS35 are owed, and the wheel's warning text needs a legal review before anyone turns it on.

## Features

| # | Feature | State | Notes |
|---|---|---|---|
| 011 | Setting up a router in a real shop: **part A** first setup without the router's internet (the computer downloads the installer and the OpenWrt packages, the router checks their signatures and installs from RAM; owner request 2026-10-22) and **part B** a coin box network of its own, so a second router set up nearby cannot take a box's place (owner field report 2026-10-23) | **approved and ready to execute; waiting only for the owner's go; not started** (`specs/features/011-offline-setup/`) | **What was feature 012 is now part B of this feature** (merged 2026-10-23 at the owner's instruction; the number 012 is retired and its branch `feat/012-box-network-name` kept only as a record). One router release, **1.5.18**, carries both parts; part B is built first (small, fixes a field problem), then part A. Dependency on 010 is **done** (merged, router 1.5.17 live). Also done 2026-10-23: branch rebased on `beta` `b1db87f`; part A's line references refreshed against it (010's merge had moved most by 16 to 34 lines) and the `SshRunner` / `subprocess.run(input=)` error corrected to `Ssh` / `run_remote(..., data=...)`; part B's references re-verified, and fact B4 now records that the box stores `wifi_ssid` and `wifi_pass` with no validation, so the new command must check the name itself; criterion 1's baseline narrowed to the setup and router area (owner); citations anchored on symbol names, not line numbers. Owner decisions, all answered: offline first by default (`--router-online` skips it), stop before changing the router when neither path works, the `piso-setup box-wifi-name` command, never run by an update, and the owner told where the box network still has the shared name. |
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
Feature 010 is merged into `beta` and live as router 1.5.17 (2026-10-23). Next: feature 011 (`feat/011-offline-setup`, approved, rebased on `beta` `b1db87f`, references refreshed) is ready to start and needs only the owner's go. It now carries both parts, since what was feature 012 was merged into it as part B; part B is built first, then part A, and one release (router 1.5.18) carries both. Owner-side meanwhile: run the hardware checks RS28 to RS35 (RS33 on the tablet with `adb`), get the wheel's warning text read by a lawyer, answer the open decisions above. Then R1 app rows or R2 signing and CI fixes.
Handoff notes: `specs/notes/2026-10-21_feature-005-wheel-2.md` (written before the wheel was finished; `specs/features/005-spinning-wheel/plan.md` and `validation.md` are current).
