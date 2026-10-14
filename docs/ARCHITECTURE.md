# PisoPhone architecture and logic flow

A site sells time on two things with one coin slot: **rental phones** (the PisoPhone kiosk app) and **guest Wi-Fi** (PisoWiFi, through a router).
Five parts work together:

| part | where | job |
|---|---|---|
| **Kiosk app** | `app/` (Kotlin, Android, device owner) | locks the rental phone, counts paid time down, shows the lock screen and the owner's settings |
| **Coin box** | `esp32_firmware/` (ESP32, C++) | takes the coins, keeps them safe until they are used, serves the box dashboard, holds player accounts |
| **Router add-on** | `tools/pisoportal/` (Rust program), `router/` (monitor and init scripts), `setup/` (installer) | runs next to openNDS: the coin page for guests, the money records, the owner's console |
| **Website** | `website/` | the installer, the setup pages and the update feeds (`website/update/`) |
| **Owner tools** | `scripts/` (your own computer) | make keys, cards and posters; sign updates; check a release before it ships |

```
                          signed HTTP + UDP coin events
   coins -> COIN BOX  <------------------------------------>  ROUTER (openNDS + pisoportal) <--> guest phones (coin page)
             |   ^                                                  |
             |   | Wi-Fi: HTTP / WebSocket, signed and encrypted    |  grants / revokes access with ndsctl
             v   v                                                  v
        KIOSK APP (rental phone)                          the internet
             ^
   website/update (signed feeds: app, firmware, router) -- fetched by phone, box dashboard and router
```

The rules that hold everywhere: **the box records a payment before anything else**, **a payment is credited exactly once**
(by transaction id on the phone, by coin window id on the router), and **only software the owner signed is installed**.

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

(There were five numbers once; "4 = unlicensed" is no longer used anywhere.)

`KioskSessionSupervisor` ticks once a second, counts time down, saves a checkpoint (so a reboot
restores the session) and locks the phone when time reaches zero.

### Around the session (what the phone does by itself)
Small, separately tested pieces; each decides in a pure class and a thin Android layer acts on it.
- **Speech and sounds:** `TimeAnnouncer` decides what to say (5, 3 and 1 minutes, 10 seconds, then each of the last 5 seconds, "Time is up,
  thank you for playing!"); `KioskAudioManager` plays pre-rendered clips so it is on time. Owner settings: voice on or off, volume, quiet hours (`QuietHours`).
- **Idle lock screen:** `IdlePolicy` / `LockIdle` turn the screen off after 5 minutes of the lock screen being left alone; `PickupWake` wakes the phone when it is picked up.
- **Cleaning up:** after 5 minutes without paid time, social, chat, mail and browser apps are wiped (`SocialApps`, `GuestReset`; games are left alone;
  needs device owner and Android 9 or newer). **Boost** (the pill) stops other apps and keeps the one in front (`AppCloser`; the "in front" part needs Usage Access).
- **Wi-Fi:** the phone stays on the kiosk network; if it joins or loses another one it is put back, and the pill's refresh toggles the radio off and on, never leaving it off (`KioskWifi`).
- **Staying up:** a crash that repeats is restarted after a growing wait (`CrashLoop`); the restart count goes to the box with the heartbeat and shows on the box dashboard.
- **Overlay windows:** the lock screen (full screen), the pill and the "time added" pop-over are separate `WindowManager` windows drawn with Compose (`overlay/`); animation cost is
  gated by `EffectsLevel` (thermal state, animator scale) so a weak phone does not overheat.

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
- **Box -> phone:** add time, deduct time, config sync (`KioskHttpServer`, encrypted + HMAC). Apart from payments (idempotent
  by transaction id), a signed message must also carry a time within 2 minutes of the box's clock (`server/RequestFreshness.kt`;
  `BoxClock` is the time the box itself reported), and `ReplayGuard` accepts each signed message once, so a command captured on
  the network cannot be played back later; a refused one is answered `403 STALE_TIMESTAMP`. Until the phone has heard the box's
  time (just started, or a box that has not learned the time yet) nothing is judged by time, so the box can never be locked out.
- **Admin -> box:** the web dashboard (Basic auth, or the owner's admin QR code, which also opens super admin; lockout after repeated failures).
- **Admin -> phone:** `KioskAdminActionReceiver` broadcasts, all gated by the admin PIN.

## 4. Credentials
- **Operator admin password:** every box starts on the published default `Coinslot@Setup` (admin login and setup Wi-Fi) so it can be set up and reset without a serial monitor. Changing it in the dashboard is required: until then a banner shows and the box arms no coin slot (`SETUP_REQUIRED`). A new password needs 8+ characters and cannot be the default.
- **Admin PIN (phone):** per phone; also required for every admin broadcast.
- **Super admin:** the owner's admin QR code (a private key). The box checks a signature over a one-time challenge with the public
  key built into its firmware and starts a session cookie (`SuperAdminSession.cpp`, `AdminQrAuth.h`). No password, no website
  feed. See `docs/api/superadmin-qr.md`.

## 5. Guest Wi-Fi payments (router add-on)
openNDS is only the doorman: it blocks a guest phone from the internet until told otherwise, and sends its first web request to our page. It knows nothing about coins.
`pisoportal` (one Rust program on the router) does the rest, in `tools/pisoportal/src/`:

| file | job |
|---|---|
| `http.rs` | serves the coin page (built once, kept in memory) and one WebSocket per customer; a customer is the MAC the router itself sees, never what the page says |
| `core.rs` | one coin window at a time: arm the box, count coins, **settle** (price the whole window once, record it, grant access once, acknowledge the box), recover after a restart |
| `boxlink.rs` | the coin box: signed HTTP calls (`docs/api/gateway-coinslot.md`) and signed UDP coin events |
| `nds.rs` | `ndsctl`: grant, revoke, state, counters |
| `roll.rs` | the roll: one line per device with paid time; written atomically |
| `pricing.rs`, `fair.rs` | plans and prices (best combination of tiers); fair use slow-down for HyperSpeed |
| `ledger.rs`, `reconcile.rs` | the revenue ledger (hash-chained lines) and its comparison with the box's own count |
| `voucher.rs` | voucher codes (below) |
| `console.rs`, `console-ui.js` | the owner's console page (signed in with the router's root password) |
| `update.rs` | self-update from the website, signed releases only |

The customer's page moves through the states `idle`, `starting`, `armed`, `closing` ("Counting coins"), then a final result (online, empty, error, busy, cooldown, mismatch)
or `paused`. Everything travels over the WebSocket; the page re-asks the router after 3 s in `closing` and pings every 4 s, so a lost message heals by itself.
Closing the page never loses coins: the window runs on the router and settles without the page. The page reloads itself once after going online so the phone's own
sign-in window notices that the internet works.

`router/piso_monitor.sh` is a separate small service: Telegram alerts (router restarted, box silent for 5 minutes, ledger and box disagree, a device keeps opening empty windows)
and a few read-only commands from the owner's chat. It cannot change prices, passwords or Wi-Fi settings.

## 6. Vouchers, the ledger and reconcile
- **Voucher choice (`VOUCHER_CHOICE`, on by default):** before inserting coins a customer picks *Use now* or *Get a voucher code*. A voucher window counts and prices coins as usual, but connects
  nobody: it makes **one** voucher worth the minutes the coins bought, on the chosen plan, valid 30 days. The page shows the code and offers a tiny HTML file that opens the portal with the code filled in.
- **Codes are never stored in the clear:** a code is `HMAC-SHA256(router secret, "v1" + salt + 0)`, shown as `ABCDE-FGHJK` (Crockford base32); only a hash and a used flag are on flash. The same device can list its unused vouchers again.
  One coin window (`wid`) never makes two vouchers, even after a restart. Redeeming is single-use, throttled, and writes no revenue row.
- **Money:** every coin becomes a ledger row when its window settles (kinds `new`, `topup`, `switch`, `vbuy`; `voucher` only for printed slips of older releases). Each line ends with a hash over the previous line, so an edit is detectable.
  `reconcile` compares the ledger with the box's lifetime coin count (which restarts when the owner collects the revenue); the box may be ahead (it also counts the rental phones' coins), never behind.
- The owner no longer makes voucher batches or prints slips; the console lists issued vouchers and can cancel an unused one.

## 7. Cards and player accounts
A player can keep time on the box with a card: a QR code `PISO1.<box>.<serial>.<seconds>.<signature>`. The signature (ECDSA P-256) is checked by the box against the card public key built into
`CardPubKey.h`, and the card must be meant for that box. The serial is the account id; the card's seconds are credited on its first scan only. The account table is written defensively
(`AccountStorage.cpp`: whole-file write, read-back, backup, sequence number and CRC). The phone side is `ui/CardScanActivity.kt` -> `service/AccountController.kt` -> the box.
The owner makes cards on their own computer with `scripts/make_cards.py` (the card key is made on first use and stays out of the repository; a box accepts a new key only after it is built into its firmware).

## 8. Updates, signing and releases
- **Keys:** one owner key signs firmware and router updates (`OWNER_SIGNING_KEY_B64`, held in the protected `production-signing` environment); the APK has its own keystore. The private admin QR and card keys never go into CI. See `docs/KEYS.md`.
- **Phone app:** `build-apk.yml` runs the unit tests, builds, signs and publishes the APK with `app.json`. `versionCode` is the CI run number and alone decides what is newer; the visible name is that number cut into digits (build 437 is 4.3.7).
- **Coin box:** `firmware-images.yml` builds and signs the images; the box installs only a newer `PISO_FW_VERSION`, checks the signature and the hash, and goes back to its old image if the new one does not confirm itself.
- **Router:** `publish-router.yml` signs `router-setup.sh` with a rollout percentage; a router installs only a newer, correctly signed release in its turn of the rollout, and undoes it if it does not run (`piso-setup self-update`).
- **Path:** work on `beta`; `scripts/verify_release.py` checks keys, checksums, signatures and version rules; merging `beta` into `main` publishes. Details: `docs/RELEASE.md`, `docs/DEPLOY.md`.
- A coin box is limited by its type: 6 phones for a purchased box, 12 for a rental box (`getDeviceCap`).

## 9. Where things live
| area | files |
|---|---|
| Firmware entry and loop | `esp32_firmware/src/main.cpp` |
| Coin slot state machine | `CoinSlotManager.cpp`, `HardwareManager.cpp` |
| Payment queue | `PaymentQueueManager.cpp` |
| OpenNDS Wi-Fi payments | `tools/pisoportal/` (portal program), `router/` (monitor, init scripts), `setup/` (installer) |
| Network gateway (router payment check) | `GatewayCoinslot.cpp` (logic), `WebServerGateway.cpp` (HTTP), `include/GatewayAuth.h` (auth), docs in `docs/api/gateway-coinslot.md` |
| Web API | `WebServerModule.cpp` (routes), `WebServerApi.cpp`, `WebServerCoinslot.cpp`, `WebServerTelemetry.cpp`, `WebServerConfig.cpp` |
| Auth and crypto | `WebServerAuth.cpp`, `Security.cpp`, `SuperAdminSession.cpp`, `include/AdminQrAuth.h`, `include/CredCrypto.h` |
| Pure, PC-testable logic | `include/InputSafety.h`, `OtaCheck.h`, `DiagRing.h`, `AdminQrAuth.h`, `CredCrypto.h` (tests in `host_tests/`) |
| App engine | `service/KioskEngine.kt`, `KioskStateManager.kt`, `KioskSessionSupervisor.kt`, `SessionRules.kt` |
| App <-> box | `network/Esp32ConnectionManager.kt`, `Esp32CoinslotRequests.kt`, `Esp32DiscoveryScanner.kt`, `server/KioskHttpServer.kt` |
| Payments (app) | `repository/PaymentRepository.kt`, `db/` |
| Admin broadcasts | `receiver/KioskAdminActionReceiver.kt` |
| Lock screen, pill, pop-over, owner settings (app) | `overlay/` (`KioskOverlay.kt`, `FloatingPillOverlay.kt`, `TimeAddedPopOver.kt`, `ui/`) |
| Phone behaviours (app) | `service/TimeAnnouncer.kt`, `service/LockIdle.kt`, `system/PickupWake.kt`, `util/SocialApps.kt`, `util/AppCloser.kt`, `util/CrashLoop.kt`, `network/KioskWifi.kt` |
| Cards and accounts | `CardCodec.h`, `AccountStorage.cpp`, `WebServerAccounts.cpp` (box); `ui/CardScanActivity.kt`, `service/AccountController.kt` (app); `scripts/make_cards.py` |
| Wi-Fi poster, owner key tools | `scripts/make_wifi_poster.py`, `scripts/make_owner_keys.py`, `scripts/make_admin_qr.py` |

## 10. Quality checks
- `.github/workflows/quality.yml` ("Code quality") runs on every push: ktlint for Kotlin, clang-format and the firmware host tests, the security-rule and vendored-file checks, the website and provisioning tests,
  the owner's admin QR login and signed console script tests, the generated web assets check, the dashboard's scripts in a browser, and the portal and router tests (flow, console, browser, installer, self-update, box update).
- `quality.yml` also runs **detekt** (static analysis; `detekt.yml` is the rules, `detekt-baseline.xml` the findings that existed when it was added, and anything new fails).
- `emulator-tests.yml` runs the **emulator tests** (`scripts/ci_emulator_tests.sh`, in an Android emulator with `reactivecircus/android-emulator-runner`) on pushes to `main`, nightly, and by hand. `build-apk.yml` runs the Android unit tests (Robolectric, no device) with a per-package **coverage** check, **Android Lint** (`app/lint-baseline.xml`; anything new fails and lint must pass before the APK is built),
  then builds, signs and publishes the APK (per-branch channel).
- `scripts/check_security_rules.sh` also fails if a file that is not source sits in a source folder, or a tracked file is over 5 MB (outside the folders CI publishes builds into).
- Format locally with `ktlint -F` (Kotlin) and `clang-format -i` (firmware, config in `esp32_firmware/.clang-format`).

## 11. Known limits (what this document does not promise)
- **Unit-test coverage is 19% of the app's lines** (measured in CI with JaCoCo; `scripts/check_coverage.py` prints it per package and enforces minimums from `app/coverage-minimums.txt`).
  The money and session logic is much better covered (repository 74%, server 78%, db 55%, security 42%, service 34%, network 24%) than the screens and system glue
  (`overlay.ui` 2%, `ui` 0%, `audio` 4%, `system` 11%).
- **Screenshot tests:** `app/src/test/.../screenshot/` draws the lock screen (idle, coins going in, getting ready, box offline, coin slot busy, three colour themes, rental ended), the time pill (collapsed, almost out, panel), the
  "time added" pop-over and the buttons with Robolectric (animations off, so they are still) and compares each with a golden PNG in `app/src/test/screenshots/` (a few pixels may differ by a little, nothing more).
  A new screen is recorded the first time (CI commits new goldens on a branch, and refuses them on `main`); a design change on purpose is `./gradlew :app:testDebugUnitTest -Pscreenshots.update=true` and commit the changed PNGs.
  A failed comparison leaves `*.actual.png` and `*.diff.png` in `app/build/screenshot-diffs/` (uploaded by CI). They show the still version of each screen, not the animation, and not a real phone's fonts or GPU.
- **Emulator lane (new):** `app/src/androidTest/.../KioskWireFlowTest.kt` runs the real service on an Android 14 emulator and drives it the way the coin box does (signed, encrypted requests to port 8080):
  bad signatures are refused, a payment unlocks the phone, the countdown runs, the phone locks itself when time is up, a coin arms the phone first, a repeated payment is credited once and a changed one is a conflict.
  It is not required for the APK build yet (an emulator is flakier than the other jobs, and it runs on `main` and nightly, not on every branch push; see `docs/DEPLOY.md`, "CI minutes"); it becomes required after it has been stable for a while. It does **not** cover anything that needs the phone to be device owner.
- **Still not tested on a real device in CI:** Unit tests cover the decision logic; the Android calls (device policy, Wi-Fi radio, usage stats, sensors, clearing app data) and real openNDS behaviour are checked by hand
  (`docs/REAL_WORLD_TESTING.md`).
- **Structure:** the app is one Gradle module with feature folders; dependencies are not enforced by the compiler, and many pieces are singleton `object`s rather than injected. Several classes are large
  (`KioskAudioManager`, `Esp32ConnectionManager`, `KioskEngine`, `FloatingPill`, the router's `core.rs`) and would be split along the seams described above.
- **Rollouts:** router updates roll out in stages; the phone app and box firmware do not (every device takes a new build when it next checks).
