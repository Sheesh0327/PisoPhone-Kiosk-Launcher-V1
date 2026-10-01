# Overhaul phase 1: field diagnostics

Adds visibility only. No payment, pairing or session behaviour changes. Branch: `claude/overhaul-p1-diagnostics` (cut from `main`).

## What was added

ESP32 firmware:
- `GET /api/diagnostics` (admin login, same as the dashboard) returns JSON:
  - `firmware`, `built`, `chip`, `mac`, `uptime_s`
  - `heap` (`free`, `min_free`, `largest_block`) and `wifi` (`connected`, `ssid`, `rssi`, `channel`, `ip`)
  - `reset` (`reason` of this boot, `boots` total, `history` newest first, up to 8)
  - `clock_synced`, `coin_slot` (state, session, pending and unpersisted payments), `slots`
  - `counters`: `coin_events`, `coin_pulses`, `payments_queued`, `payments_acked`, `payments_evicted`, `persist_failures`, `slot_reservations`, `ws_connects`, `wifi_reconnects`, `ota_attempts`
  - `log`: the last 40 events, oldest first, each prefixed with uptime in seconds
- The existing console messages for coins, the payment queue, slot reservations, OTA, Wi-Fi and boot now also go into that log. The serial output itself is unchanged.
- Counters are in RAM (reset at boot). The boot count and reset history are kept in flash.
- The endpoint answers `503 LOW_MEMORY` instead of risking a restart when the heap is very low.

Phone app:
- Admin vault, new "Diagnostics" section: app version and build, the last 40 events (ESP32 link up/down, coin credits and their result, arm success, busy slot, lockdown, admin bypass and lock, update steps), with Refresh and Copy buttons.

## Hardware test

Firmware (flash with `pio run -e <env> -t upload`):
1. Boot the board, open `http://<esp32-ip>/api/diagnostics`, log in as admin. Expect JSON with `reset.reason` of `POWERON` (or `SOFTWARE` after a restart), `boots` counting up each reset, and a `[DIAG] Boot #N` line in `log`.
2. Pull power and reconnect: `boots` increases by one and `reset.history` starts with `POWERON`.
3. Insert a coin while a phone is armed: `coin_events` and `coin_pulses` go up, `payments_queued` goes up, and once the phone credits it `payments_acked` matches. The log shows the detection and the acknowledgement.
4. Arm and release the slot a few times: `slot_reservations` increases and the log shows each reservation and release.
5. Turn the router off for a minute: `wifi_reconnects` increases and the log shows the Wi-Fi loss.
6. Without logging in, the endpoint asks for a password (nothing is revealed).
7. Normal use is unchanged: pairing, arming, coins and the dashboard behave exactly as before.

Phone (install the new APK):
1. Open the admin vault, scroll to "Diagnostics": the version and build are shown.
2. Run a rental: the list shows the link coming up, "armed", and the coin with its result.
3. Tap Copy and paste somewhere: the text matches.

## Rollback

Revert the branch. Nothing is migrated; the only new stored data is the `diag` flash namespace on the ESP32 (boot count and reset history), which is ignored by older firmware.

## Verified here, and what is not

Verified here:
- `esp32_firmware/host_tests/run.sh`: the ring buffer (ordering, wrap-around, truncation on a UTF-8 boundary, line breaks) with the address and undefined-behaviour sanitizers.
- The real `Diagnostics.cpp` was compiled with ArduinoJson 6.21.6 against stubs and run: the JSON is valid, quotes and emoji are escaped correctly, the 40-line ring stays bounded, boot history and counters are right.
- A scan for functions used without their header found nothing.

Not verified here, so please check on hardware:
- Compilation with the real ESP32 toolchain (`pio run`).
- The Android code was not built here (no Android SDK). Open a pull request to run the CI unit tests, or run `./gradlew testDebugUnitTest` locally.
