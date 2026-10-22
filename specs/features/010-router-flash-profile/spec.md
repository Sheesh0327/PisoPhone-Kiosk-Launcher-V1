# Feature 010: check the setup, and a smaller PisoWiFi router image for 16 MB flash

Status: **spec approved by the owner (2026-10-22), then corrected after review (2026-10-22, section "Corrections after review"); the owner accepted every proposed answer to the open questions (2026-10-22, round 4); spec and plan still need the owner's re-approval before code**   Branch: `feat/010-router-flash-profile` (from `beta`; `beta` and `main` held the same code when it was made; the pull request goes into `beta`, `CLAUDE.md` section 5).   Area: router, setup   Release: `setup/RELEASE` raised once, by the first change under `setup/src/` or `tools/pisoportal/`; the small image rolls out at 0% first.
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
  - **Device memory across power cuts**: paid sessions are kept in a file, not only in memory: the roll (`roll.rs:1-4`, one line per device with paid time, in the data folder `/etc/coinslot.d`), written to a temporary file and renamed, under the process-wide lock; it is read back at start (`core.rs`, `roll::load`). A session paid before a power cut is covered by a unit test (`roll.rs:422`) and by feature 001 (RT-01 to 07: the clock jump after a power cut moves sessions, `core.rs:256`). The box-signing answer is also remembered in a file (`boxlink.rs:13`). Proven on a computer only; the hardware check is RS32 (not run).
  - **The wheel's spin journal**: a spin is written to `<data folder>/wheel/<id>.spin` before the session changes and finished or voided after a restart by `recover` (`wheel_spin.rs:1-4`, `:218`). This code lives only in the wheel files that the small image leaves out (see open question 3).
- Baseline: `cargo test` in `tools/pisoportal` passes, 141 tests, 0 failed (run on `main`'s code, 2026-10-22).
- PisoPhone is the Android kiosk app (`app/`). Its setup is the setup program on a computer (`setup/pisophone_setup.py`) and needs no router image. "Both" means a router with PisoWiFi plus phones on the same coin box. A PisoPhone-only shop is already supported: `pisophone_setup.py --phone-only` sets up the coin box on the shop's normal router over USB, with no router software (`setup/pisophone_setup.py:10`, `:3461`).
- How a router program reaches a router today: each CPU family has one installer file with one program embedded (`tools/build_piso_setup.py`; `setup/piso-setup.sh` for mipsel, `piso-setup-<family>.sh` for the others), and one signed feed entry per family (`website/update/<family>/router.json`: one `sha256`, one `size`, one `sig`). The programs are built by `router-program.yml`, signed and published by `publish-router.yml`, checked by `scripts/verify_release.py`, and their sizes are held by `router/tests/test_footprint.py` (programs 1200 KB, installer 1700 KB). There is no second program per family yet.
- Size of what the small image removes: the wheel is about 64 KB; the mini-game and the coin-box view are smaller. Against a program of about 1 MB and 8 to 10 MB of writable flash, of which the packages above take most, the saving is likely well under 10% of the program. Not yet measured.

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
- Work is on a branch from `beta`, and goes into `beta` by pull request. Nothing is merged to `main` and nothing is released without the owner's order.
- Generated router files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`) are never edited by hand; regenerate with the tooling.
- Any change under `setup/src/` or `tools/pisoportal/` raises `setup/RELEASE` once for this feature (`CLAUDE.md` section 4). The current value is `1.5.16`.
- The box <-> phone message contract is not touched by this feature.
- Real routers run this now. A router that is already installed must keep working through an update. An installed router never moves to the small image by itself at an update: only a new install, or the owner running `piso-setup` again, can switch it (round 4, answer 2).

## Success criteria (planned; each becomes a line in `validation.md`)
1. **Setup check (computer):** `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `setup/test_setup_phone_web.py` and `test_phone_only.py` all pass on the current branch before any change (baseline recorded).
2. **Flash measurement:** a pure function reads `/proc/mtd` and classes the router as small (16 MiB or less) or large. The chip size is **not** the sum of all partitions: on many boards they overlap (`firmware` covers `kernel` and `rootfs`; some list a whole-chip `fullflash`), so a sum can make a 16 MB chip look like 32 MB or more and give it the full image. The rule (open question 4 confirms it): the size of a whole-chip partition when one is listed, otherwise the largest end offset the partitions reach, which never exceeds the chip. Expected answers: 8 MB, 16 MiB: small; 32 MB, 128 MB: large; a 16 MB sample with overlapping `firmware`/`kernel`/`rootfs`: small; no `/proc/mtd` (x86, virtual machines) and a root or overlay disk over 128 MiB: large; no `/proc/mtd` and a smaller disk, an unreadable or odd file: small (feature 008 criteria 1 and 2, with the partition rule corrected). Tests use `PISO_MTD_FILE`.
3. **Power-cut check:** the existing roll tests (a session paid before a power cut, the clock jump) pass on both builds: `cargo test` and `cargo test --features small`. A router moving from the full to the small image never leaves a wheel spin half done: the rule chosen in open question 3 is tested (a `.spin` journal left behind is finished or voided exactly once, never dropped). A hardware check is added to `docs/REAL_WORLD_TESTING.md` as not run.
4. **Size budget on the small image:** before any release, CI measures what it can build: the small program per CPU family against the full one (the saving in KB), and the installer that carries it against the 1700 KB budget. The free space left on a real 16 MB router (packages plus program in the writable flash) is measured on hardware (criterion 8), or with an OpenWrt image builder if the owner chooses (open question 5). Both are written in `validation.md`. If the saving is too small to matter, that is reported to the owner before the build and feed work (open question 6).
5. **Kept features work on the small image:** a coin credits time once and a payment is recorded before the credit (`cargo test` and `tests/test_flow.py`); pause, endurance and HyperSpeed still work; the isolation tables load (`router/piso_isolation.sh`, needs `kmod-nft-bridge`).
6. **Removed features are really gone:** on the small image no wheel code, no Rental phones page in the console, no recent-spins view, no free mini-game, and no file for them exists (a listing check, not only a setting).
7. **Setup choice:** the setup program on the computer (`pisophone_setup.py`, not the router installer, which a PisoPhone-only shop never runs) asks PisoWiFi, PisoPhone or both, with no fixed default; a non-interactive run needs an explicit choice. PisoPhone only reuses the existing `--phone-only` path and installs nothing on a router (`test_phone_only.py`, `test_setup_phone_web.py`).
8. **Hardware:** not run (no 16 MB router yet). Added to `docs/REAL_WORLD_TESTING.md` as a new check, marked "not run".
9. **Gates:** `bash scripts/check_security_rules.sh`, `cargo test`, the router and setup Python tests, `ruff`, `mypy`, `pytest -x` on touched Python files.
10. **Delivery and signing:** the small program is built by `router-program.yml`, signed and published in the same feed by `publish-router.yml` in the way open question 1 decides, checked by `verify_release.py` (0 FAIL) and held by a size budget in `test_footprint.py`; a router never installs an unsigned or wrong-size program.

## Corrections after review (2026-10-22)
The review compared this spec with the code; the facts above are corrected. What changed:
- Paid sessions are kept in a file across a power cut (the roll); the earlier text said they appeared to be held in memory. The owner's power-cut answer (round 3) stays: it now applies to the small image's own risk, the wheel's spin journal (open question 3).
- `beta` exists; the branch comes from `beta` and goes back into it.
- The product choice belongs in the setup program on the computer, and PisoPhone only is already there (`--phone-only`).
- Measuring the chip as the sum of the `/proc/mtd` partitions over-counts on many boards; criterion 2 now names a rule and the expected answers.
- Delivery (criterion 10) and the spin journal (criterion 3) were missing from the criteria.
- `plan.md` was written before the owner's answers and must be brought in line before code (it still asks for decisions 1 to 6, makes the stripped build conditional, and has no tasks for the `small` Cargo feature, the feed and signing, the CI size check or the setup program's choice).

Round 4 (2026-10-22, after review: "accept all proposed answers to the open questions"):
- **Delivery:** (b), a second signed file per CPU family in the same feed (for example `website/update/<family>/router-small.json` and its installer).
- **Routers already installed:** they stay on the full image through updates; only a new install or an explicit `piso-setup` re-run switches a 16 MB router to the small image.
- **A spin cut short by the switch:** the switch is refused while the wheel is on or a `.spin` file is not marked done; the full program turns the wheel off and finishes or voids every open spin with `recover` first.
- **Chip-size rule:** a whole-chip partition when listed, otherwise the largest end offset the partitions reach.
- **Size proof:** a measurement on a real 16 MB router when one is available, with the CI numbers (program and installer sizes) before.
- **Worth it:** measure first; if the full install already fits on 16 MB with room for records and updates, ship only the flash check and the setup choice, and park the small build.
- **Note on answers 5 and 6 together:** there is no 16 MB router yet, so "does the full install fit" can only be estimated at first: from the program size (CI), the package sizes `opkg` reports and the 8 to 10 MB writable flash of a typical 16 MB router. Plan group 2 writes that estimate and brings it to the owner, who decides whether to build the small image on the estimate or wait for a router.

## Open questions for the owner (answered in round 4 above; kept for the reasoning)
1. **How the small program is delivered.** (a) Both programs inside each installer: one file per family, but each installer grows by about 1 MB, over the 1700 KB budget. (b) A second signed file per family in the same feed (for example `website/update/<family>/router-small.json` and its installer): the installer stays small, but CI builds, signs and checks ten programs instead of five. Proposed: (b).
2. **Routers already installed.** Does an installed router with 16 MB or less move to the small image by itself at the next update (it loses the wheel, the Rental phones page and the mini-game without being asked), or only new installs and routers whose owner runs `piso-setup` again? Proposed: only new installs and an explicit re-run.
3. **A wheel spin cut short by the switch.** The small image has no code to finish a `.spin` journal. Proposed: the switch to the small image is refused while the wheel is on or a `.spin` file is not marked done; the full program turns the wheel off, finishes or voids every open spin (the existing `recover`), and only then installs the small image.
4. **The chip-size rule** in criterion 2 (whole-chip partition, else the largest end offset). Proposed: yes.
5. **The size proof.** CI can measure the program and the installer, not the free flash on a router. Is a measurement on a real 16 MB router (when one is available) enough, or should CI also build an OpenWrt image with the image builder? Proposed: the router, with CI numbers before.
6. **Is the small image worth it?** The parts it removes are likely well under 10% of a 1 MB program. Proposed first step: measure whether the full install already fits on 16 MB with room for records and updates; if it does, ship only the flash check and the setup choice, and park the small build.

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
