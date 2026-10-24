# Feature 026: a new board must not crash in a loop (PisoPhone-only USB setup)

Status: **approved (owner, 2026-10-24: "badly needed fix ... apply to main and release immediately")**   Branch: `fix/026-usb-setup-boot-loop`   Area: firmware
Backlog rows: owner bug report 2026-10-24   Release: firmware 3.12.2

## Intent
The PisoPhone-only installation on a Windows computer stops after the software is written: "No PisoPhone software answered on the board".
The owner's serial log shows why: a board with no Wi-Fi saved restarts in a loop (boot #93, #94, reset reason PANIC) with
`assert failed: tcpip_send_msg_wait_sem ... (Invalid mbox)`, so it can never answer the setup program. The same happens with 3.12.0 and 3.12.1.

## Cause (from the owner's serial log and the code)
`startWifiConnect()` in `esp32_firmware/src/main.cpp` returns at once when no Wi-Fi name is saved (and the box is not a router gateway), before the first
`WiFi.mode(...)`. The network stack (lwIP) starts with that first call. `setupWebServer()` then opens the web, WebSocket and UDP ports, and
lwIP asserts. Every boot of a new board ends in this crash, before `loop()` can answer `PISO-HELLO`. The code that returns early was added with the
USB Wi-Fi setup (firmware 3.11.0), which the repo's checklist lists as never tried on a real board.

## Invariants touched
- Money and time: **not touched** (the path runs only for a board with no Wi-Fi saved, where coins are blocked anyway until the first setup).
- Only owner-signed software: the firmware is built and signed by CI after it reaches `main` as for every firmware release.

## Constraints
A board with a Wi-Fi name saved takes exactly the path it takes now (the new line is inside the "no Wi-Fi saved" branch). No setting, message or format changes.

## Success criteria
1. With no Wi-Fi saved, the board starts the Wi-Fi stack (station mode, no connection attempt) before the servers open their ports: the line is in
   `startWifiConnect()`. (Checked by reading, clang-format, the host tests and the four firmware builds in CI; **the crash itself can only be seen on a board**.)
2. **Hardware check RS50** (a new or erased ESP32-C3, write the PisoPhone-only image, the board answers `PISO-HELLO` and the setup program finishes) is added and
   stays unticked until the owner runs it; the owner is the first test.
3. `PISO_FW_VERSION` is 3.12.2; CHANGELOG entry; ledger row.

## Out of scope
Any other setup behaviour; the PisoWiFi gateway path (it always starts Wi-Fi); the `loop()` Wi-Fi retry.

## Owner decisions
None open.
