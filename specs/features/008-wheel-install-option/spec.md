# Feature 008: the wheel is an install option, offered only on routers with more than 16 MB of flash

Status: **parked** (2026-10-22: the owner will write a separate spec for this; the facts below are kept for it; no questions answered, no code)   Branch: `feat/008-wheel-install-option` (made from `beta` when work starts)   Area: setup (the router installer and the computer setup program) and router (one switch)
Backlog rows: none (owner request 2026-10-22)   Release: router (`setup/src/` changes what routers install: raise `setup/RELEASE`, the number after 007's release)

## Intent
"During setup, there should be options to add the spinning wheel game into the installation if the storage of the router is above 16 MB." Small routers (16 MB flash) are tight on room; the wheel adds to what the router stores (program, page, console code, and files it writes: `wheel.log`, the spin journal, daily counts). The owner wants the installer to **offer** the wheel only where the router has the room, and not to put it on small routers.

## What is true today (so the options are honest)
- One router program per CPU family carries the wheel in every install and every update since 1.5.14 (live at 100%): mipsel 962 KB before the wheel, 1026 KB with it; the installer file 1443 KB, then 1529 KB. So the wheel's code costs about **64 KB** of a 16 MB flash. What grows while it runs is small too (a log line and a small file per spin; `wheel.log` is append-only).
- The wheel is OFF until the owner confirms a responsibility warning in the console, so a router that never turns it on stores the code for nothing.
- The router installer (`setup/src/*.sh`) already measures space (`96-space-backup.sh`: `df -k /overlay`, low-memory handling); it has no flash-size rule and no option like this one.
- On OpenWrt `df /overlay` shows only the writable part (about 8 to 10 MB on a 16 MB router), not the chip size; the chip size is in `/proc/mtd` (partition sizes), and boards without flash (x86, virtual machines) have no `/proc/mtd`.

## The decision to settle first (review question 1)
What should "add the wheel to the installation" mean?
- **(a) One program for everyone; the installer decides whether the wheel is allowed.** The program still contains the wheel code, but on a small router the wheel is not offered anywhere: no console card, no button, no pages, and a switch typed into a file does nothing. Saves **no** flash (the 64 KB stays) but keeps small routers free of the feature and its files. Small change, one build, one signed update feed.
- **(b) Two builds per CPU family: a lite program without the wheel and a full one.** Real savings on small routers (about 64 KB program, about 85 KB installer), but it doubles the router builds, the signed update files and the installers (`website/setup/`, `website/update/`), and each router must stay on its own track at every update. A large change to the release process (`router-program.yml`, `publish-router.yml`, `verify_release.py`, `router.json`).
- **(c) The wheel as a separate download** is not possible: it is Rust code inside the one program.

## Invariants touched
- Money and time (rule 1): **not touched** (no change to the spin, limits, journal, ledger, file lock, or any payment code).
- Only owner-signed software (rule 2): no new unsigned path. Under (b) every extra program/feed would be signed in the protected environment like today's; (a) adds none.

## What a router without the precondition does (rule 3b of `sdd-plan`)
- Flash over 16 MB: the installer offers the wheel (a question with the answer saved); the owner can change the answer later (`piso-setup wheel allow|deny`).
- Flash 16 MB or less: the wheel is not offered and the console has no wheel card; the coin page shows the free mini-game as before; `piso-setup wheel allow --force` exists only if the owner chooses (review question 3).
- Flash size cannot be read: treated as small (nothing is added by guessing) and the setup summary says so.
- A router that already has 1.5.14 or 1.5.15 (the wheel code and possibly the owner's confirmation): see review question 4.

## Constraints
1. **Measuring "above 16 MB":** the flash chip size = the sum of the partitions in `/proc/mtd` (a 16 MB chip is exactly 16 MiB, which is **not** above); a board with no `/proc/mtd` and an overlay or root disk larger than 128 MiB counts as large; anything else (unreadable, odd) counts as small. The number measured is shown in the setup summary ("Flash: 32 MB: the spinning wheel can be added"). Tests use a file in place of `/proc/mtd` (`PISO_MTD_FILE`, like the existing `PISO_LOW_MEM_KB` hook).
2. The answer is stored in the router's settings as `WHEEL_ALLOWED=yes|no` (never `WHEEL=`: the switch and the owner's typed confirmation remain separate and unchanged, see 005). The router program treats the wheel as off when `WHEEL_ALLOWED=no`, even with a switch and a confirmation on record (defense in depth); the console shows the wheel card only when it is allowed; the page button and `/wheel*` answer "not found" when it is not.
3. The question appears in **both** setup paths: the one-file router installer (`piso-setup`, on the router, also used for updates) and the computer setup program's router step (`setup/pisophone_setup.py`, web and text). The computer program passes the answer to the installer; neither asks on a small router.
4. No new library; the installer stays POSIX `sh` checked by `shellcheck`; the setup program uses only the Python standard library. No change to the coin box, phone app or protocol.
5. The footprint budgets stay as the owner set them (1100 / 1600 KB, 2026-10-22). Under (b) the lite programs would also be checked against the old 1000 / 1500 KB.

## Success criteria
1. Flash measurement (pure function, tested in `router/tests/test_setup.py` with `/proc/mtd` samples): 8 MB, 16 MB (exactly 16 MiB: not above), 32 MB, 128 MB, no `/proc/mtd` with a big disk, no `/proc/mtd` with a small overlay, unreadable file; each gives the right answer.
2. Installer: on a large router the question is asked, `yes` writes `WHEEL_ALLOWED=yes`, `no` writes `no`; a non-interactive run (the setup program's answers, `--yes`) uses the stated answer or the default; on a small router the question is not asked and `no` is written; the summary shows the measured flash and the decision.
3. Router program (`cargo test`, `test_wheel.py`): `WHEEL_ALLOWED=no` makes the wheel off whatever else is set (switch on, confirmation on record), the console JSON says "not available on this router", the button and `/wheel*` are absent/404, the mini-game stays; `yes` or a missing key behaves as today (review question 4 for updated routers).
4. Computer setup program: the question is shown for a large router and not for a small one (web and text paths), the answer reaches the installer (`test_pisophone_setup.py`, `test_setup_web.py`).
5. Update path: an update on a small router sets `WHEEL_ALLOWED=no` (review question 4); on a large router keeps the owner's earlier answer; never changes the wheel's switch or confirmation record.
6. `piso-setup wheel` (status / allow / deny) works and is in `piso-setup help` and the manual; `shellcheck` clean; footprint test green; existing router tests green; `build_piso_setup.py --check`; `setup/RELEASE` raised once; CHANGELOG, manual, hardware check added (not run).

## Out of scope
Changing the wheel itself; removing the mini-game; the console UI beyond hiding the wheel card; routers' RAM; the phone app and coin box. Under (a), no program is made smaller.

## Owner decisions
See the review questions asked with this draft and answered in `plan.md`; then approve the spec and plan.
