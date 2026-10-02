# First deployment: what to run and what to watch

This is the stopping point for the first round of testing. Everything in `MASTER_PLAN.md` that can be done without
hardware is in. **Nothing has been compiled for the ESP32 or Android by the author**; your first build is the first
real build. Use your own shop, one box and one or two phones, not a customer site.

## 1. Build
1. **Phone app:** push to the branch; CI builds the APK and runs ktlint and the unit tests. Fix the first red CI run
   before anything else (the Android tests, including the server and Keystore tests, have never run).
2. **Box firmware:** `cd esp32_firmware && pio run -e esp32-c3-dev` (development environment: no flash encryption,
   freely re-flashable). `sh host_tests/run.sh` should pass first.
3. Do **not** flip any production, secure-boot or eFuse step yet (`PROVISIONING_SOLD_UNIT.md` is for later).

## 2. What works without the owner keys
- Boxes run without `scripts/generate_license.py keygen`: until a real public key is built in, the box still accepts
  the old deprecated license keys. Skipping the keys is fine for this test; it just leaves that hole open.
- Updates: without the firmware-signing key the box refuses OTA, so flash by USB for now.

## 3. Test order (stop at the first failure and look at the serial log, 115200 baud)
1. **Box boots**, prints its setup-AP password and admin password on serial (there are no factory passwords).
2. **Dashboard** opens; the first-run checklist shows; change the admin password; connect Wi-Fi.
3. **Provision a phone** with the dashboard's "Install & Provision" link. In `adb logcat`, look for the box secret being
   stored. After a reboot of the phone the box must still be reachable (this exercises the new Keystore storage:
   `adb shell run-as com.pisophone.kiosk` is not available on release builds, so check behaviour, not the file).
4. **Coin test:** insert coins, see time added on the phone once per coin. Pull power on the box mid-session and
   re-insert: no coin lost or doubled.
5. **Pull the Wi-Fi** between a coin and its acknowledgement: the box retries and the phone credits it exactly once.
6. **Router:** only after the above. Follow `opennds/INSTRUCTIONS.md` (package install), then Layout A or B.
7. **Factory reset** the box from the dashboard: license slots and lifetime revenue must survive; Wi-Fi and
   admin password must reset.

## 4. If the phone cannot reach the box after a reboot
The Keystore wrapping (`SecretVault`) is the newest, least-tested part. Symptoms: payments stop after a phone
reboot, the phone says it uses the legacy key. The secret falls back to plain storage if wrapping fails, but if the
Keystore key itself is lost the phone treats the secret as not set: re-provision the phone from the dashboard.
Report it with `adb logcat -s KioskSecurity`.

## 5. Left for later hardening sessions (not needed to see it work)
Per-phone key exchange and AES-GCM; removing the phone's HTTP port; NVS encryption on sold units; admin PIN in the
Keystore; `esp_http_server`; splitting the large Android classes; sold-unit eFuse practice; HIL bench and 72 h soak.
