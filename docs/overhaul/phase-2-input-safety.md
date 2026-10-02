# Phase 2: Firmware input safety

Firmware only. Build with `pio run` and flash as usual; the Android app is unchanged.

## What changed
- **JSON escaping:** every string field a client can influence (device id, IP, names, SSID, coin-slot holder, tx id) is escaped before it goes into a JSON response, so a `"` or `\` or control character can no longer break the dashboard's data. This uses a small tested helper (`InputSafety.h`) on the existing string building rather than rewriting every response with ArduinoJson, because the firmware cannot be compiled in the review environment and a smaller change is safer to verify.
- **Names:** device names received from the network are cleaned on arrival (markup and quote characters and control characters dropped, 32 characters max). The dashboard also HTML-escapes names it renders.
- **Device ids and IPs are not altered**, because ids take part in the signed payment/telemetry checks and changing them could break a working pairing. They are escaped on output only.
- **Login throttling:** five wrong admin passwords from one client lock that client out for 60 seconds (HTTP 429 with `Retry-After`); each wrong password also costs about a quarter second. A request with no password (the browser's first prompt) is not counted. A correct login clears the count. Phones and the controller are not affected, since they use signed requests, not the admin password.
- **Default credentials warning:** `/api/status` reports `default_credentials`, the dashboard shows a red banner while the admin or super-admin password is still the default, and the boot log prints a warning.
- The serial log never printed the admin password (only the factory-reset message lists the default values), so nothing was removed there.

## Hardware test
1. Open the dashboard: it loads as before. With default passwords a red banner shows at the top. Change the admin password in Settings and the super-admin password; after both change, the banner disappears at the next refresh.
2. Pair/identify a phone as usual; slots, battery and time still update.
3. From a browser or `curl`, call `/api/status` with a wrong password five times: the sixth attempt, even with the right password, returns 429; after about a minute the right password works again. (`curl -u admin:wrong http://<ip>/api/status`)
4. Send a heartbeat or identify with `name=A"B<script>x</script>`: the name shows as `ABscriptx/script` (quote and angle brackets removed, nothing runs), and `/api/status` stays valid JSON (paste it into a JSON validator).
5. A coin test still works end to end (arm, insert coin, ack), and `/api/diagnostics` shows `[AUTH] Failed admin login` entries from step 3.

## Rollback
Revert the Phase 2 commit; nothing is stored in flash by this phase.
