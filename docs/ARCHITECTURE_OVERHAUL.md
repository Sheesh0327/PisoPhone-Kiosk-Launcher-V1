# PisoPhone architecture review and overhaul plan

## Ground rules

Fixed owner decisions, not changed by this plan:
- The hardcoded shared secret stays (so the protocol phase improves how it is used, not the secret itself).
- The 5-minute / logout revenue reset stays.
- Slot licensing is the only licensing.
- Wiping customer data between rentals is out of scope (known risk).

Every phase below:
- is self-contained: it builds, passes the unit tests and works on hardware on its own,
- changes behaviour only where stated (refactors are behaviour-preserving),
- has a hardware test list and a rollback (revert one commit/branch),
- is verified here only as far as this environment allows (Android unit tests in CI, JavaScript and Python by running them, firmware host tests where a phase adds them). Firmware cannot be compiled here, so each firmware phase is verified on your hardware.

## Findings

Severity: **High** = can lose money or brick/lock devices, **Medium** = breaks under stress or is hard to maintain safely, **Low** = hygiene.
Evidence is what was measured in the code on `main` at `749c885`.

| # | Finding | Evidence | Severity |
|---|---|---|---|
| F1 | The paid-session state machine is raw integers (0 locked, 1 armed-locked, 2 unlocked, 3 unlocked-armed) spread over many files. Almost every Phase 1 money bug was an invalid transition. | 25 magic-number state reads/writes in Kotlin; transitions in `KioskEngine`, `KioskEsp32Coordinator`, `KioskSessionSupervisor`, the overlays | High |
| F2 | No field diagnostics. A failure on a deployed box leaves only a serial console that nobody is attached to. | Firmware logs are `Serial.printf` only; no reset-reason, heap or queue visibility | Medium (but it multiplies the cost of every other bug) |
| F3 | Firmware builds JSON and HTML by string concatenation with no escaping. A device name containing a quote breaks the response, and names reach the dashboard. | ~125 concatenation lines, e.g. `WebServerTelemetry.cpp` builds `"device_name":"..."` from a request argument | Medium |
| F4 | Admin login has no throttling and defaults are `admin`/`admin`; HTTP Basic over plain HTTP. | `checkAdminAuth()` calls `authenticate()` every request, no counter | Medium |
| F5 | Firmware has zero automated tests, and its logic is tied to Arduino types, so it cannot be tested without hardware. | no `test/` directory; 5.6k lines in `src/` | Medium |
| F6 | The firmware has no threading model. One extra FreeRTOS task (`AuthWorker`) reads and writes global `String`s (`sharedSecret`, device lists) that the main loop also mutates; only the payment queue has a mutex. | 67 `extern` globals in `Config.h`; one mutex outside the queue: none | Medium |
| F7 | The Android layer is a few god objects plus singletons: `KioskEngine` (570 lines), `Esp32ConnectionManager` (760), `KioskSecurity` (450, mixes prefs, crypto, policy and recovery), 9 Kotlin `object` singletons, 12 `runBlocking` calls. Hard to test and easy to break. | file sizes and counts above | Medium |
| F8 | The wire protocol is ad hoc: unversioned query strings, AES-CBC+HMAC with the AES key derived as plain SHA-256(secret) and no key separation. The construction is sound (MAC is verified first); it is hand-rolled and cannot evolve safely. | `aes_encrypt`/`encrypt`, `KioskHttpServer.serve` | Low |
| F9 | OTA trusts the download host and a SHA-256 published next to the file. Anyone with the admin password can flash any image. | `/update` accepts any binary; no signature check | Medium |
| F10 | Settings are scattered NVS keys and SharedPreferences with no schema version or validation, so upgrades rely on each key's default. | `loadAllConfig()`, many `prefs.get*` | Low |
| F11 | Both coin paths (HTTP retry and WebSocket) duplicate delivery and ack logic. | `DeviceNetwork.cpp`, `WebSocketsUdp.cpp`, `PaymentQueueManager.cpp` | Medium |

Things that are **fine** and stay: the exactly-once-by-receipt payment model (ESP32 queues until acked, phone credits idempotently by `tx_id`), the ISR-based pulse capture, Room as the source of truth for paid time, the signed discovery.

## Target architecture

Android:
- one explicit `SessionState` type with a pure `reduce(state, event) -> (state, effects)` function that is unit-tested for every transition,
- thin orchestration that applies the effects (ESP32 calls, audio, overlays),
- security split into config storage, crypto, and device policy, with a single composition root instead of reaching for singletons.

Firmware:
- layers: hardware (pins, ISR, relay) → domain (pulse accumulator, payment queue, clock, sessions, pure C++) → network (HTTP, WebSocket, UDP) → UI (dashboard),
- pure domain code in a library built for the board and for the host, so it has real tests,
- one place that owns each piece of shared state, and messages by value between tasks.

## Phases

Order is by value and risk. Each is independent: you can test or reject any one without the others (Phase 4 and 5 are the only ones that are easier after an earlier one, and that is noted).

### Phase 1: Field diagnostics (low risk, do first)
Firmware:
- an in-RAM ring buffer of recent log lines,
- reset reason and reboot count kept in NVS,
- an admin-only `GET /api/diagnostics` returning firmware version, chip, uptime, free and minimum heap, Wi-Fi RSSI and channel, payment queue depth, coin/ack/failure counters and the recent log.

Phone:
- an in-app diagnostics view in the admin vault (version, ESP32 link state, last errors, queue state).

Hardware test: open `/api/diagnostics` after boot, after a coin, after pulling power; confirm counters and the last reset reason are right.
Rollback: revert; no behaviour change.

### Phase 2: Firmware input safety
- every JSON response built with ArduinoJson (proper escaping),
- HTML-escape device names in the dashboard,
- validate request arguments (lengths, charset),
- admin login throttling with a temporary lockout and a small delay,
- a visible warning while default credentials are active.

Hardware test: dashboard works as before; a device name with `"` and `<` shows correctly; five wrong passwords lock out for a minute and the right password works afterwards.

### Phase 3: Android session state machine (F1)
- `SessionState` sealed type and a pure reducer,
- tests for every transition, including each Phase 1 bug,
- behaviour-preserving.

Hardware test: full rental flow (arm, coin, expire, admin add/deduct, busy slot, reboot mid-session).

### Phase 4: Firmware pure core with host tests (F5, F11)
- extract the pulse accumulator, master clock, tx-id generator and the payment-queue core (behind a small storage interface) into `lib/piso_core`,
- add a `native` PlatformIO environment, so `pio test -e native` runs on your PC with no hardware,
- the board build uses the same code.

Hardware test: all four boards still arm, pay and ack; `pio test -e native` is green.

### Phase 5: Firmware ownership and concurrency (F6)
- one owner per piece of shared state, with locked accessors,
- the auth worker receives copies, not live globals.

Hardware test: a soak run (several hours with coins and phone reconnects) with no resets (Phase 1 diagnostics show reset reasons).

### Phase 6: Android decomposition (F7)
- split `KioskSecurity` into config, crypto and policy,
- move the Room access that still uses `runBlocking` onto coroutines,
- one composition root.

Hardware test: same flows as Phase 3; the app starts cleanly after a reboot and a package update.

### Phase 7: Protocol v2 (F8)
- versioned envelope, AES-GCM, HKDF key separation, canonical encoding,
- phone and ESP32 negotiate and fall back to v1, so a mixed fleet keeps working during rollout.

Hardware test: new phone with old ESP32, old phone with new ESP32, and both new; coins credited exactly once in each.

### Phase 8: Signed OTA (F9)
- an offline signing script and a public key compiled into the firmware,
- the device verifies the signature before committing the update; a bad or unsigned image is rejected.

Hardware test: a signed image installs; a tampered image and an unsigned one are rejected and the old firmware still boots.

### Phase 9: Config schema (F10)
- one versioned, validated config structure for firmware settings with migrations.

Hardware test: upgrade from the current firmware keeps Wi-Fi, slots, pins and revenue totals.

## What I cannot verify here

- Anything that runs on the ESP32 or an Android phone. Each phase lists what to test.
- The 4-minute CI unit-test run exercises the Android logic only.
