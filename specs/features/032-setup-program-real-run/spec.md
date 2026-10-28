# Feature 032: the setup program in a real shop

Status: **built and validated on a computer 2026-10-28; waits for the owner's RS56 bench run (D8)**   Branch: `feat/032-setup-program-real-run` (from `origin/main`, owner's choice 2026-10-28; `beta` is behind `main` on purpose)   Area: setup
Backlog rows: SU-18, SU-09, SU-10, SU-06a, SU-01, SU-02, SU-04, SU-15, SU-17, SU-21 (`specs/spec_03_findings.json`; SU-14 only if RS56 (b) fails)   Release: site (the website's copy of the program, rebuilt; no router, firmware or app release)

## Intent
The one-click setup program (`setup/pisophone_setup.py`) has passed its tests on a computer but has never run end to end in a real shop.
This feature fixes what a desk review says a real run will hit, on the computer's side only: a log the owner can send, a *Try again* that
works, a router found at its own address, downloads that survive a router without internet, and clear advice instead of a loop. It serves
both rules by getting an owner who is not a programmer to a working unit on the first try; it changes no money path and no signature check.

## Invariants touched
- Money and time: not touched (no credit, expiry, deduction, payment queue or `revenue.csv`; no box <-> phone message change).
- Only owner-signed software: not touched. The downloads still go through the existing signature checks (SU-02 only moves *when* the
  coin box software and installer files are fetched, and keeps them in the same cache); `build_offline_bundle` and `fetch_image` keep
  verifying exactly what they verify today.

## Constraints
- Only `setup/pisophone_setup.py`, its tests in `router/tests/`, `setup/README.md`, `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `specs/`.
  Nothing under `setup/src/`, `setup/install.sh`, `tools/pisoportal/` or `esp32_firmware/` (those are features 033 and 034).
- The program uses only the Python standard library. Website copy rebuilt with `python3 tools/build_piso_setup.py`; `--check` must pass and
  `git status` must show no router file changed (else `setup/RELEASE` would need raising: stop and tell the owner).
- The log file (spec 03 section 5): `pisophone-setup-log-<YYYYMMDD-HHMM>.txt` in the saved folder, mode 0600, plain text, the unit's
  passwords written as `***`. It covers both paths that write page lines today (`Session.log` and `plog`, the PisoPhone-only one).
- Passwords already saved by the program (`pisophone-router-password.txt`) keep their format and place.
- Every page the owner can reach keeps its present wording unless a row below changes it.

## What the owner sees without the condition (rule 3b)
- **SU-01** (two networks in 192.168.1.x): stops *before any ssh* with the advice and a *Check again* button. A computer with no clash sees
  nothing new. There is no "continue anyway" (see decision Q2).
- **SU-02** (website not reachable when the router packages are needed): the page asks to unplug the router cable for the download and plug it
  back in, then continues by itself. If the website is still not reachable after that, the run stops with the "this computer cannot reach the
  website" message it shows today.
- **SU-06a** (apk router): refused before anything is changed; the page names the router's release and the supported ones. A router with
  `opkg` sees nothing new.
- **SU-15** (the router cannot reach the app's address): the Phones step shows "plug the modem into the router's WAN port" and *Check again*
  instead of the QR code; it shows the QR code as soon as the router reaches it. A router with internet sees the QR code as today.
- **SU-09/SU-10**: a *Try again* uses the saved router password; when none is saved the owner is sent to the Router step to type it; a refused
  login says which password is needed.

## Success criteria
Each becomes a line in `validation.md`; ids follow spec 03 section 7.
1. AC1.1 A run with a fake core writes `pisophone-setup-log-*.txt`, mode 0600, holding every page line and the computer's addresses and routes,
   and none of the unit's passwords (SU-18); the last page and the error banner name the file.
2. AC1.2 After a first run that set the router password, *Try again* logs in with the saved password and continues (SU-09); a refused login says
   which password is needed instead of "the connection was lost" (SU-10). The fake `Core` in the page tests models the router password.
3. AC1.3 With two fake interfaces in 192.168.1.x (Windows, Linux and macOS address-table forms) the page stops before any ssh and shows the hotspot
   advice; `setup/README.md` "Which network?" recommends the owner's phone hotspot (SU-01).
4. AC1.4 After the Start page the coin box software (manifest and image for every chip), the installer files and `app.json` are in the cache;
   with the website unreachable later the page asks for the cable to be unplugged and finishes the offline bundle after it is (SU-02).
5. AC1.5 A router found only at the gateway named by the cable's DHCP lease (192.168.8.1 in the test) is found and named at that address (SU-04);
   the move to 10.0.0.1 is unchanged.
6. AC1.6 `@@APK yes` (or no `opkg`) stops with the release named and nothing changed on the router (SU-06a).
7. AC1.7 The Phones step does not show the QR code while the router cannot reach the app's address (SU-15).
8. AC1.8 The coin box list and image requests carry `USER_AGENT` (SU-17); `--install-service` copies the program to `/var/lib/pisophone-setup/`
   and starts that copy (SU-21).
9. AC1.9 `python3 tools/build_piso_setup.py --check` passes; the eight router test files, `check_security_rules.sh`, `ruff`, `mypy`, `pytest -x` on the
   touched files pass; no router file changed. RS56 is added to `docs/REAL_WORLD_TESTING.md`; the owner runs it before this feature merges (D8).

## Out of scope
SU-03, SU-11, SU-06b, SU-05, SU-07, SU-08 (feature 033), SU-16 (feature 034). Full apk support, a hotspot made by the setup computer (D2), the
PisoPhone-only box address (Q2 of spec 03), the Android Go USB path, the Telegram step, the Owner tools. SU-14 (Windows 10 OpenSSH 8.1) is
decided only by RS56 (b): if it fails, one more small group is added after the owner's run.

## Owner decisions
Spec 03 D1 to D8 are answered (2026-10-28). For this feature:
Answered by the owner on 2026-10-28 (all four as recommended):
- **Q1** SU-02: unplug prompt and flow as in the epic (D5).
- **Q2** SU-01: no "continue anyway"; the page stops with the advice and *Check again*.
- **Q3** SU-15: no "show it anyway"; the QR code waits until the router reaches the app's address.
- **Q4** SU-18: the log is linked in the error banner and on the last page.
