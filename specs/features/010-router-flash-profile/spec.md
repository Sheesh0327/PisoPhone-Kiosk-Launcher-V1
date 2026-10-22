# Feature 010: check the setup, and a smaller PisoWiFi router image for 16 MB flash

Status: **spec approved by the owner (2026-10-22); plan.md still needs approval before code**   Branch: `feat/010-router-flash-profile` (based on `main`'s code; `beta` does not exist in this repository).   Area: router, setup   Release: `setup/RELEASE` raised at approval; the small image rolls out at 0% first.
Backlog rows: none matches this request. Closest: feature 008 (`specs/features/008-wheel-install-option/`, parked: the flash-size rule and the 16 MiB threshold), and `specs/spec_01_findings.json` rows RT-22 and RT-08 to RT-26 (space checks in the installer), not directly about flash size.

## Intent
A shop owner runs PisoWiFi on a cheap router. Some of these routers have 16 MB of flash, and the full PisoWiFi install may not fit with room left for the money records, logs and updates. The owner asked three things: (1) confirm the setup works as intended, (2) let the owner choose which product a shop runs (PisoWiFi, PisoPhone or both), and (3) check whether the router can carry the full PisoWiFi image; if the flash is 16 MB or less, use a smaller image that keeps the features that matter. The owner serves the people who pay, so this must not cost money or time.

## What the repository says today (facts, not yet measured on hardware)
- Router program (`tools/pisoportal`): about 919 KB (armv7) to 1091 KB (mips), from `specs/RELEASE_STATE.md` (router 1.5.16). The wheel's code is about 64 KB of that and is off by default (feature 008 notes). `docs/ROUTERS.md` on `main` gives older figures (0.7 to 0.9 MB) and calls 16 MB flash with 128 MB RAM "comfortable"; that doc should be updated with the measured numbers.
- Installer (`setup/piso-setup*.sh`): about 1617 KB. It is a download, and its size budget is 1700 KB.
- Packages the installer adds: `opennds curl ca-bundle jsonfilter` (`setup/src/20-preflight.sh:45-49`), plus `libmicrohttpd-no-ssl` (line 55); `kmod-nft-bridge` is needed for the guest and rental-phone isolation (`setup/src/75-checks-stage2.sh:15`).
- The writable part of flash on a 16 MB router is about 8 to 10 MB (feature 008 notes). **This is an estimate; nobody has measured it on a real router.** The chip size is in `/proc/mtd`. The installer does not read it yet (`setup/src/96-space-backup.sh` reads `df -k /overlay` only).
- The portal has no compile-time switches for the optional features. The wheel (`wheel.rs`, `wheel_run.rs`, `wheel_limits.rs`, `wheel_spin.rs`, `wheel_gate.rs`, `wheel.html`), the mini-game (`game.rs`, `/game` routes in `http.rs`) and the coin-box view (`boxview.rs`, started from `console.rs:787`) are always built in, and are only switched off at runtime.
- Features the owner named, found in the code:
  - **Pause**: `PAUSE_MAX`, `PAUSE_MIN_PESOS`, `PAUSE_KBPS` (`tools/pisoportal/src/config.rs:24-40`).
  - **Endurance**: time tiers with speed limits, `ENDURANCE_TIERS`, `ENDURANCE_DOWN_KBPS`, `ENDURANCE_UP_KBPS` (`config.rs:174-176`).
  - **HyperSpeed**: a plan (`Plan::Hyper`, `pricing.rs:131`) with fair-use slowdown after a traffic limit (`fair.rs:1`).
  - **Device memory across power cuts**: the box-signing answer is remembered in a file so a restart keeps it (`boxlink.rs:13`). The session timing survives restarts, and the wall-clock jump rule is in `core.rs:256`. **Whether every paid session is kept across a power cut is not yet confirmed.** Paid sessions appear to be held in memory; a read of `core.rs` session handling is the first check (plan step 3).
- Baseline: `cargo test` in `tools/pisoportal` passes, 141 tests, 0 failed (run on `main`'s code, 2026-10-22).
- PisoPhone is the Android kiosk app (`app/`). Its setup is the setup program on a computer (`setup/pisophone_setup.py`) and needs no router image. "Both" means a router with PisoWiFi plus phones on the same coin box.

## Owner answers (2026-10-22)
Round 1 (interview):
- **Product:** the setup script lets the owner pick PisoWiFi, PisoPhone or both. There is no fixed default; the owner chooses at setup.
- **Threshold:** 16 MiB or less gets the smaller image. Exactly 16 MiB counts as small (feature 008).
- **Delivery:** a separate small build, signed like the full one.
- **Unpaid visitor on a small router:** no extra games. The visitor sees only the important features: pause, HyperSpeed, endurance, and device memory across power cuts. The free mini-game is removed on the small image.
- **Keep on the small image:** pause, endurance, HyperSpeed, device memory across power cuts, and coin and payment, the portal, openNDS, the owner console settings, the isolation rules, backups and updates.
- **Remove from the small image:** the wheel code, the coin box page inside the console (the Rental phones tab view through `boxview.rs`), and the recent-spins view.
- **LuCI on port 8080:** not in the removal list, so it stays.
- **Test router:** none at 16 MB yet. The small image stays "not run on hardware" until one is available.

Round 2 (follow-up):
- **HyperSpeed:** yes, the plan in `pricing.rs` and `fair.rs` (fair-use slowdown after a traffic limit) is the one to keep.
- **Free mini-game on large routers:** kept. Only the small image drops it.

Round 3 (build and release):
- **Build:** a Cargo feature named `small` in `tools/pisoportal`. It compiles the wheel, mini-game and coin-box view out of the image, so removed code cannot run.
- **Which routers get the small image:** any router with 16 MB flash or less, regardless of RAM. RAM is not checked.
- **Delivery:** the same signed update feed as the full image. The installer picks the small or the full file by measured flash.
- **PisoPhone-only shops:** no router install at all. The setup program only sets up phones and the coin box.
- **Rental phones page:** removed on small routers. The owner manages the coin box from its own page on the coin box network.
- **Rollout:** 0% first; the owner raises it to 100% (the existing process in `CLAUDE.md` section 5).
- **Size proof:** measured on a real 16 MB build in CI before any release, not estimated.
- **Power-cut fix:** if a paid session is lost on restart, a small fix goes into this feature. A large fix stops the work until the owner approves it.

## Invariants touched
- Money and time (a payment recorded first, credited once, nothing lost on a power cut): **must not change.** Coin handling, the payment queue, the coin-window id, the revenue records (`/etc/coinslot.d`, `revenue.csv`) and device memory across power cuts stay in every image. Stripping removes only the optional features listed above. Any stripping change needs a check that a payment, a time credit and a power-cut restart still keep the customer's time on the small image (see success criteria).
- Only owner-signed software (OTA, signing, keys): **must not change.** The small image is a separate build inside the same signed feed (`CLAUDE.md` section 5). Nothing here adds an unsigned install path.

## Constraints
- No code before the owner approves this spec and `plan.md` (constitution rule 8).
- Work is based on `main`'s code. Nothing is merged to `main` and nothing is released without the owner's order.
- Generated router files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`) are never edited by hand; regenerate with the tooling.
- Any change under `setup/src/` or `tools/pisoportal/` raises `setup/RELEASE` (`CLAUDE.md` section 4). The current value is `1.5.16`.
- The box <-> phone message contract is not touched by this feature.
- Real routers run this now. A router that is already installed must keep working through an update; the small image is for new installs and for routers that choose it.

## Success criteria (planned; each becomes a line in `validation.md`)
1. **Setup check (computer):** `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `setup/test_setup_phone_web.py` and `test_phone_only.py` all pass on the current branch before any change (baseline recorded).
2. **Flash measurement:** a pure function reads `/proc/mtd` sizes and classes the router as small (16 MiB or less) or large; tested with 8 MB, 16 MiB, 32 MB, 128 MB, no file, unreadable file (feature 008 criteria 1 and 2 are reused, not repeated).
3. **Power-cut check:** a paid session survives a router restart on both the full and the small image (a test in `tools/pisoportal`, plus a hardware check added to `docs/REAL_WORLD_TESTING.md`). If the code does not keep the paid session across a restart, that is reported first. A small fix goes in this feature; a large fix waits for owner approval.
4. **Size budget on the small image:** the installed size of the small image is measured on a real 16 MB build in CI before any release, and written in `validation.md` with the free space left after install.
5. **Kept features work on the small image:** a coin credits time once and a payment is recorded before the credit (`cargo test` and `tests/test_flow.py`); pause, endurance and HyperSpeed still work; the isolation tables load (`router/piso_isolation.sh`, needs `kmod-nft-bridge`).
6. **Removed features are really gone:** on the small image no wheel code, no Rental phones page in the console, no recent-spins view, no free mini-game, and no file for them exists (a listing check, not only a setting).
7. **Setup choice:** the installer offers PisoWiFi, PisoPhone or both, with no fixed default; a non-interactive run needs an explicit choice. A PisoPhone-only shop skips the router install.
8. **Hardware:** not run (no 16 MB router yet). Added to `docs/REAL_WORLD_TESTING.md` as a new check, marked "not run".
9. **Gates:** `bash scripts/check_security_rules.sh`, `cargo test`, the router and setup Python tests, `ruff`, `mypy`, `pytest -x` on touched Python files.

## Out of scope
- Measuring a real router's free flash (needs the owner's hardware).
- Changing the wheel's odds, warning text or legal review (roadmap 8.3).
- The full list of 107 backlog rows in `spec_01_findings.json`.
- Any change to the coin box firmware or the Android app.
- Making any release or merging to `main` (owner's order only).
- A separate signed feed for small routers (not chosen).

## Questions for later (full specs, after the decisions above)
- Which router models do the shops own (chip size, CPU, RAM)? Routers with less than 64 MB RAM may also need the memory rules in `setup/src/96-space-backup.sh`.
- How many phones and coin boxes per shop does the owner expect (feeds the card and slot limits, open decision ST-8)?
