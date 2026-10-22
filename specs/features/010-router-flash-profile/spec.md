# Feature 010: check the setup, and a smaller PisoWiFi router image for 16 MB flash

Status: **draft**   Branch: `claude/piso-wifi-setup-verify-ikeqrm` (the designated branch for this session; `beta` does not exist in this repository, so `feat/010-...` was not created)   Area: router, setup   Release: none yet (decided at approval)
Backlog rows: none matches this request. Closest: feature 008 (`specs/features/008-wheel-install-option/`, parked: the flash-size rule and the 16 MiB threshold), and `specs/spec_01_findings.json` rows RT-22 and RT-08 to RT-26 (space checks in the installer), not directly about flash size.

## Intent
A shop owner runs PisoWiFi on a cheap router. Some of these routers have 16 MB of flash, and the full PisoWiFi install may not fit with room left for the money records, logs and updates. The owner asked three things: (1) confirm the setup works as intended, (2) choose which product a shop runs (PisoWiFi, PisoPhone or both), and (3) check whether the router can carry the full PisoWiFi image; if the flash is 16 MB or less, use a smaller image that keeps the features that matter. The owner serves the people who pay, so this must not cost money or time.

## What the repository says today (facts, not yet measured on hardware)
- Router program (`tools/pisoportal`): about 919 KB (armv7) to 1091 KB (mips), from `specs/RELEASE_STATE.md` (router 1.5.16). The wheel's code is about 64 KB of that and is off by default (feature 008 notes).
- Installer (`setup/piso-setup*.sh`): about 1617 KB. It is a download, and its size budget is 1700 KB.
- Packages the installer adds: `opennds curl ca-bundle jsonfilter` (`setup/src/20-preflight.sh:45-49`), plus `libmicrohttpd-no-ssl` (line 55); `kmod-nft-bridge` is needed for the guest and rental-phone isolation (`setup/src/75-checks-stage2.sh:15`).
- The writable part of flash on a 16 MB router is about 8 to 10 MB (feature 008 notes). **This is an estimate; nobody has measured it on a real router.** The chip size is in `/proc/mtd`. The installer does not read it yet (`setup/src/96-space-backup.sh` reads `df -k /overlay` only).
- PisoPhone is the Android kiosk app (`app/`). Its setup is the setup program on a computer (`setup/pisophone_setup.py`) and needs no router image. "Both" means a router with PisoWiFi plus phones on the same coin box.

## Invariants touched
- Money and time (a payment recorded first, credited once, nothing lost on a power cut): **must not change.** Coin handling, the payment queue, the coin-window id and the revenue records (`/etc/coinslot.d`, `revenue.csv`) stay in every image. Stripping removes only optional features. Any stripping change needs a check that a payment and a time credit still work on the small image (see success criteria).
- Only owner-signed software (OTA, signing, keys): **must not change.** A smaller image is a separate build that goes through the same signed release (`CLAUDE.md` section 5). Nothing here adds an unsigned install path.

## Constraints
- No code before the owner approves this spec and `plan.md` (constitution rule 8).
- Generated router files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`) are never edited by hand; regenerate with the tooling.
- Any change under `setup/src/` or `tools/pisoportal/` raises `setup/RELEASE` (`CLAUDE.md` section 4). The current value is `1.5.16`.
- The box <-> phone message contract is not touched by this feature.
- Real routers run this now. A router that is already installed must keep working through an update; a small image is for new installs and for routers that choose it (owner decision 4).

## Success criteria (planned; each becomes a line in `validation.md`)
1. **Setup check (computer):** `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `setup/test_setup_phone_web.py` and `test_phone_only.py` all pass on the current branch before any change (baseline recorded).
2. **Flash measurement:** a pure function reads `/proc/mtd` sizes and classes the router as small (16 MiB or less) or large; tested with 8 MB, 16 MiB, 32 MB, 128 MB, no file, unreadable file (feature 008 criteria 1 and 2 are reused, not repeated).
3. **Size budget on the small image:** the installed size of the small image is measured on a 16 MB build (or a reproducible estimate with the exact package list) and written in `validation.md`, with the free space left after install.
4. **Kept features work on the small image:** a coin credits time once and a payment is recorded before the credit (a test in `tools/pisoportal` `cargo test` and `tests/test_flow.py`); the isolation tables load (`router/piso_isolation.sh`, needs `kmod-nft-bridge`).
5. **Removed features are really gone:** on the small image no console card, page or file for a removed feature exists (a listing check, not only a setting).
6. **Hardware:** not run unless the owner has a 16 MB router. Added to `docs/REAL_WORLD_TESTING.md` as a new check, and marked "not run" until then.
7. **Gates:** `bash scripts/check_security_rules.sh`, `cargo test`, the router and setup Python tests, `ruff`, `mypy`, `pytest -x` on touched Python files.

## Out of scope
- Measuring a real router's free flash (needs the owner's hardware; listed as a question, not done here).
- Changing the wheel's odds, warning text or legal review (roadmap 8.3).
- The full list of 107 backlog rows in `spec_01_findings.json`.
- Any change to the coin box firmware or the Android app.
- Making any release or merging to `main` (owner's order only).

## Owner decisions (needed before work starts)
1. **Which product does a shop run?** PisoWiFi only, PisoPhone only, or both? The setup should offer this choice; which default should it show?
2. **Threshold:** is 16 MiB (exactly 16 MiB counts as small, as feature 008 says) the line you want, or should 32 MB also get a smaller image?
3. **What to strip on a small router.** My proposal, to be confirmed:
   - Keep: coin and payment, the portal, openNDS, the console (owner settings), the isolation rules, backups, updates.
   - Candidates to remove: the spinning wheel code (about 64 KB, already off), LuCI on port 8080 (the router's own admin page), the coin box's page inside the console, the recent-spins view.
   - Your answer decides the list; I will not guess further.
4. **One image or two?** (a) The installer decides which features a router gets (feature 008's option a: no flash saved, but the small router does not show them), or (b) a separate small build with its own signed file (saves flash; one more build to release and check). Option (b) is the one that frees the most room.
5. **Visitor without payment (step 3b).** On a small router with the wheel off, a visitor who has not paid sees the free mini-game (today's behaviour). Should the small image keep the free mini-game, and should an unpaid visitor see a wheel button anywhere? Feature 006 had the owner expect the button for everyone; with the wheel off on small routers this should stay as it is unless you say otherwise.
6. **Real router.** Is there a 16 MB router you can test on? If not, the small image stays "not run on hardware" until one is available.

## Questions for later (full specs, after the decisions above)
- Which router models do the shops own (chip size, CPU, RAM)? Routers with less than 64 MB RAM may also need the memory rules in `setup/src/96-space-backup.sh`.
- How many phones and coin boxes per shop does the owner expect (feeds the card and slot limits, open decision ST-8)?
