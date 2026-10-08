# PisoPhone architecture and logic flow

Three parts work together: the **Android kiosk app** on each rental phone, the **ESP32 box**
(coin acceptor + web admin) and the **website** (installer and update feed).

```
 coins -> ESP32 box  <--- Wi-Fi (HTTP/WebSocket, signed + encrypted) --->  phone app (kiosk)
             |                                                              |
        admin web page                                              locks/unlocks the phone
             |
   website/update  (app.json + APK, firmware.json + .bin, credentials.json)
```

## 1. A coin payment, step by step
1. **Arm.** The locked phone calls `/api/coinslot/arm` (app: `Esp32ConnectionManager.armSlot`
   builds the URL with `Esp32CoinslotRequests`). The box checks the signature, that the phone is
   paired, and that payment storage works, then energises the coin acceptor for
   a limited time (`CoinSlotManager.reserveCoinSlot`). Only one phone can hold the slot.
2. **Coin.** The acceptor produces pulses; `universalCoinIsr` (HardwareManager) only counts them.
   `CoinSlotManager.processCoinSlotSession` waits for a gap in the pulses, then delivers the total.
3. **Record.** `triggerUniversalCoinEvent` (DeviceNetwork) saves the payment in the durable queue
   (`PaymentQueueManager.enqueuePendingPayment`, persisted in NVS) *before* anything else.
4. **Deliver.** The box pushes the payment to the phone over WebSocket and HTTP. Both carry the
   transaction id and a signature.
5. **Credit.** The phone (`KioskEngine` -> `PaymentRepository`) adds time exactly once per
   transaction id, then acknowledges (`/api/coinslot/ack`). The ack is only sent when crediting
   succeeded, so a failure leaves the payment queued.
6. **Retry/expire.** Until acknowledged the box retries every 10 s; very old unacknowledged
   payments are evicted so they cannot block arming forever.

## 2. The phone's session
State is one number, defined in `service/SessionRules.kt` (all transitions live there, with tests):

| state | meaning |
|---|---|
| 0 | locked |
| 1 | locked, coin slot armed (waiting for coins) |
| 2 | unlocked (paid time running) |
| 3 | unlocked and armed for more coins |
| 4 | unlicensed |

`KioskSessionSupervisor` ticks once a second, counts time down, saves a checkpoint (so a reboot
restores the session) and locks the phone when time reaches zero.

## 3. Who talks to whom
- **Phone -> box:** heartbeat (time, state, battery) every few seconds, arm/unarm/ack, pairing.
  Signatures use the shared secret; see `KioskSecurity.signCoinslotRequest` and firmware
  `coinslotRequestAuthorized`.
- **Clock:** the box has no clock of its own: it takes its time from paired phones and accepts a signed request only within
  5 minutes of it. So every answer of the box carries `server_time_ms`, and a phone signs with its own clock corrected by the
  difference (`network/BoxClock.kt`), whatever date the phone thinks it is. A request refused only because of its time
  (`403`, `reason: STALE_TIMESTAMP`) carries the box's time too: the phone adopts it and retries once. A `BAD_SIGNATURE`
  means the phone has another box secret (press Save on the box dashboard, or set the phone up again). Before pairing,
  the heartbeat answer already says `auth_ok` (does the box accept this phone's key) and carries the box's time.
- **Box -> phone:** add time, deduct time, config sync (`KioskHttpServer`, encrypted + HMAC).
- **Admin -> box:** the web dashboard (Basic auth, lockout after repeated failures).
- **Admin -> phone:** `KioskAdminActionReceiver` broadcasts, all gated by the admin PIN.

## 4. Credentials
- **Operator admin password:** every box starts on the published default `Coinslot@Setup` (admin login and setup Wi-Fi) so it can be set up and reset without a serial monitor. Changing it in the dashboard is required: until then a banner shows and the box arms no coin slot (`SETUP_REQUIRED`). A new password needs 8+ characters and cannot be the default.
- **Admin PIN (phone):** per phone; also required for every admin broadcast.
- **Super-admin password:** one value for all boxes, published as a signed hash in
  `website/update/credentials.json` and applied by `SuperAdminCreds.cpp`. See
  `docs/api/superadmin-credentials.md`.

## 5. Where things live
| area | files |
|---|---|
| Firmware entry and loop | `esp32_firmware/src/main.cpp` |
| Coin slot state machine | `CoinSlotManager.cpp`, `HardwareManager.cpp` |
| Payment queue | `PaymentQueueManager.cpp` |
| OpenNDS Wi-Fi payments | `tools/pisoportal/` (portal program), `router/` (monitor, init scripts), `setup/` (installer) |
| Network gateway (router payment check) | `GatewayCoinslot.cpp` (logic), `WebServerGateway.cpp` (HTTP), `include/GatewayAuth.h` (auth), docs in `docs/api/gateway-coinslot.md` |
| Web API | `WebServerModule.cpp` (routes), `WebServerApi.cpp`, `WebServerCoinslot.cpp`, `WebServerTelemetry.cpp`, `WebServerConfig.cpp` |
| Auth and crypto | `WebServerAuth.cpp`, `Security.cpp`, `SuperAdminCreds.cpp`, `include/CredCrypto.h` |
| Pure, PC-testable logic | `include/InputSafety.h`, `OtaCheck.h`, `DiagRing.h`, `CredCrypto.h` (tests in `host_tests/`) |
| App engine | `service/KioskEngine.kt`, `KioskStateManager.kt`, `KioskSessionSupervisor.kt`, `SessionRules.kt` |
| App <-> box | `network/Esp32ConnectionManager.kt`, `Esp32CoinslotRequests.kt`, `Esp32DiscoveryScanner.kt`, `server/KioskHttpServer.kt` |
| Payments (app) | `repository/PaymentRepository.kt`, `db/` |
| Admin broadcasts | `receiver/KioskAdminActionReceiver.kt` |

## 6. Quality checks
`.github/workflows/quality.yml` runs ktlint, clang-format and the firmware host tests on every push.
`build-apk.yml` runs the Android unit tests, builds and publishes the APK (per-branch channel).
Format locally with `ktlint -F` (Kotlin) and `clang-format -i` (firmware, config in `esp32_firmware/.clang-format`).
