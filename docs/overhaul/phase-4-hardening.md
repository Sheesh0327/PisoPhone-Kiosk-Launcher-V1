# Phase 4: Production hardening

Android and firmware. Rebuild the firmware with `pio run` and sideload the APK from this branch.

## What changed
- All admin broadcasts need the admin PIN; the shared secret alone is ignored. `adb shell am broadcast ... --es pin <PIN>` keeps working (the provisioning site already uses the PIN).
- The login dialogs no longer say the default PIN. The admin vault shows a red warning while the default PIN (1234) is in use.
- Firmware: the shared secret is read through `getSharedSecret()` and written through `setSharedSecret()` behind a mutex, so the auth worker task and the main loop cannot touch it at the same time.
- Firmware: OTA uploads are checked against the board's chip before writing. `host_tests/ota_check_test.cpp` covers the check.

## Hardware test
1. `adb shell am broadcast -a com.pisophone.kiosk.ADMIN_BYPASS -n com.pisophone.kiosk/.receiver.KioskAdminActionReceiver --es secret PISOPHONE_HMAC_MASTER_KEY` does nothing; the same with `--es pin <your PIN>` starts a bypass.
2. Open the admin vault with the default PIN: the red warning shows; change the PIN (from the ESP32 admin password push or the vault) and it disappears.
3. Lock dialog with a wrong PIN says only "Invalid password."
4. Firmware: dashboard, pairing, a coin and an ack all work as before. Change a setting that restarts nothing and check the phone still receives time (the shared secret path).
5. OTA: flash this build's `.bin` for the right chip: succeeds. Upload a file for the other chip (e.g. the C3 `.bin` to a classic ESP32) or any non-firmware file: the page reports "OTA rejected: ..." and the box keeps running the old firmware.
6. Let a box run a few hours with coins and phone reconnects, then check `/api/diagnostics` for unexpected resets.

## Rollback
Revert the Phase 4 commit; no stored data changed.

## Follow-up: signed coin-slot calls, backups, crash report
- `/api/coinslot/arm|unarm|ack` now carry `ts` + `sig` (HMAC of the shared secret over
  `v1:<action>:<device>:<ts>[:<tx>]`). A wrong signature is always refused. Unsigned calls from
  old app builds are still served and counted in diagnostics (`[AUTH] Unsigned ...`) while
  `PISO_REQUIRE_SIGNED_COINSLOT` is 0. Once every phone runs the new app, build with
  `-DPISO_REQUIRE_SIGNED_COINSLOT=1`. Replays are limited to the +/-5 minute window.
- `/crash_report` requires admin Basic auth (the app never used it).
- `allowBackup` is off, so app data can no longer be pulled with adb/cloud backup.
- Hardware test: pay a coin with the new app and new firmware (arm, coin, ack all succeed). With
  curl, send `/api/coinslot/ack` with a bad `sig`: expect 403 and a `[AUTH] Rejected` log line.
