# Feature 034: a PisoPhone-only box that was given a wrong Wi-Fi password can be given the right one

Status: **approved by the owner 2026-10-28 (spec, plan, Q1 and Q2 both yes); in progress**   Branch: `feat/034-box-wifi-retry` (from `origin/main` plus the epic commit, as for 032 and 033)   Area: firmware (and one small program change in `setup/pisophone_setup.py`)
Backlog rows: SU-16 (`specs/spec_03_findings.json`)   Release: firmware (`PISO_FW_VERSION` 3.12.4 -> 3.12.5; `firmware-images.yml` builds and signs it); the website's copy of the setup program is rebuilt

## Intent
A PisoPhone-only shop owner who mistypes the shop Wi-Fi password during the USB setup is stuck today: the box saved the wrong password, never joins, and every
new run answers `already-set-up`; the only way out is to write the software again with an erase and type ERASE. The box also never says why it cannot join.
This feature lets a box that has **never joined a Wi-Fi and never recorded a payment** take new Wi-Fi details over USB, and makes it report why it cannot join.
It serves the owner-who-is-not-a-programmer goal. It must not weaken the one rule that protects a working box: a box in use ignores `PISO-SETUP`.

## Invariants touched
- Money and time: **the payment queue, coin recording, credit, expiry and `revenue.csv` are not touched.** The new state is derived from facts the box already
  keeps (the lifetime coin total, the pending-payment queue) and a new "waiting for its first Wi-Fi" flag; **any payment, recorded or pending, makes the box
  refuse `PISO-SETUP` exactly as today.** Checked by a host test.
- Only owner-signed software: not weakened. The firmware is built and signed by `firmware-images.yml` as always; `PISO_FW_VERSION` goes up so boxes take it over the air.

## Constraints
- **Who can be "joining" (the safety rule).** Only a box whose Wi-Fi was given by the **new firmware's own USB setup** and that has not got an address since. A flag
  `wifi_wait` is saved by `applySetup` together with the details and cleared the first time the box gets an IP address. A box set up by older firmware, by its web page
  or by a factory reset has no such flag and stays `setup=done`: **no box already working in a shop can become "joining" by an update.** State rule (pure, host-tested):
  `needed` = admin password not changed; `joining` = admin password changed **and** `wifi_wait` **and** no payment (lifetime coins 0, nothing pending, nothing unpersisted);
  `done` = anything else. `PISO-SETUP` is accepted in `needed` and `joining` only.
- Serial hello (spec 03 section 5): `PISO-BOX fw=.. mac=.. setup=<needed|joining|done> wifi=.. ip=.. why=<wrong-password|no-network|none>`; every old field keeps its
  place and meaning, `why=` is added at the end. An older program reads `setup=joining` as "not needed" (like `done`) and `why=` is just another key; `PISO-SETUP` and
  `PISO-SETUP-ERR` stay byte for byte the same. No change to the box <-> phone message contract (`protocol/`).
- `why=` comes from the last Wi-Fi disconnect reason the box already logs (`WifiLink.h`): password-type reasons (14, 15, 202, 204) -> `wrong-password`; 201 -> `no-network`;
  anything else, or none yet, or connected -> `none`.
- Host tests only run on a computer (`esp32_firmware/host_tests/run.sh`); **`pio` is not installed here, so the ESP32 build is not made** and is said so in `validation.md`.
- Program change is small and in the PisoPhone-only flow only: a `joining` box does not need ERASE and is not rewritten; the typed Wi-Fi details replace the saved ones.
  The router (full-system) flow is not touched. Generated files rebuilt with `python3 tools/build_piso_setup.py` (`--check` must pass; if a router file changes, stop and tell the owner).

## What the owner and the box see without the condition (rule 3b)
- A box that **joined once, or has any payment, or was set up by older firmware or the web page**: reports `setup=done`, answers `PISO-SETUP-ERR already-set-up` to `PISO-SETUP`, and the program
  asks for ERASE exactly as today. There is no way to change a working box's Wi-Fi over USB.
- A box that is **joining** and the program is **older** (does not know the word): the old program reads `joining` as not `needed`, stops with "not in first-time setup mode ... Run this again" (the
  page flow) or goes on to the ERASE route; nothing is lost. Updating the program (the website copy) fixes it.
- A box that is **joining** and the program is **new**: no ERASE question, no rewrite of the software; the page says the box never joined and, when the box says why, adds the reason
  ("the box says the Wi-Fi password was wrong"). It takes the typed Wi-Fi name and password (and the admin password the page already holds) and restarts to join them.
- A box with **old firmware** and the new program: no `joining` state exists; unchanged.

## Success criteria
Each becomes a line in `validation.md`; ids follow spec 03 section 7.
1. AC3.1 Host test: a box that never joined and has no payment takes new Wi-Fi details; one that joined once, one with a payment recorded or pending, one with lifetime coins > 0, one set up by older firmware (no `wifi_wait`) and one whose admin password was never changed behave as the state rule says (`done` / `needed`) and accept `PISO-SETUP` only in `needed` and `joining`.
2. AC3.2 The hello line carries `setup=joining` and `why=` as above; the existing `PISO-BOX` fields are unchanged; `parse_status` of the old program still reads it (`setup=joining` is not `needed`); a new box is still provisioned by the old program path (`setup=needed`).
3. AC3.3 `applySetup` in a `joining` box saves the new details, keeps `wifi_wait`, and restarts; the first IP address clears `wifi_wait` (code review plus the pure rule tested on the host; the NVS and Wi-Fi calls cannot run on a computer).
4. AC3.4 Program: a `joining` box is not asked for ERASE, is not rewritten, and is given the new Wi-Fi; the reason from `why=` is shown; an old-style `done` box still asks for ERASE (page and terminal tests with a fake board).
5. AC3.5 `PISO_FW_VERSION` raised (3.12.5); all firmware host tests (`run.sh`) and the router tests listed in `CLAUDE.md` section 3 for the setup program pass; `clang-format` clean; `python3 tools/build_piso_setup.py --check` passes; `check_security_rules.sh` passes.
6. AC3.6 RS58 added to `docs/REAL_WORLD_TESTING.md`, unticked: a PisoPhone-only ESP32-C3 given a wrong Wi-Fi password, then the right one, without an erase. The owner runs it before this feature merges (D8).

## Out of scope
032 and 033 rows. A box address on a shop router without a DHCP reservation (spec 03 Q2, after RS58). Changing the Wi-Fi of a working box over USB. The router (full-system) product's pairing. Any change to the web admin page.

## Owner decisions
Spec 03 D1 to D8 are answered (D6 is this feature). For this feature:
Answered by the owner on 2026-10-28:
- **Q1** Yes: only a box set up by the new firmware's USB setup can be "joining"; a box already stuck (older firmware) still needs the ERASE route once.
- **Q2** Yes: in the joining state the box also accepts a new admin password, in the same `PISO-SETUP` line.
