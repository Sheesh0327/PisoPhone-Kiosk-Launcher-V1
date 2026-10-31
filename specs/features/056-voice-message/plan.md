# Plan: 056 voice message from the box's admin page to a rented phone

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Nothing below was opened in full except the first four; the others are read by line range when their group starts.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `esp32_firmware/web/portal-core-phones.js` | `deviceRow` draws each phone row; `toggleLocate` is the pattern for a per-phone action and `window.locating` for state kept outside the redrawn row | 238 | 3,500 |
| `esp32_firmware/src/WebServerApi.cpp` | `handleApiLocate` (admin check, only configured phones, signed send) and the `/api/devices` JSON (line ~401) | 484 | 5,300 |
| `esp32_firmware/src/DeviceNetwork.cpp` + `include/PushParams.h` | `sendAuthenticated` and its 256-byte parameter cap: why a clip needs its own message | 229 + 29 | 2,900 |
| `app/.../server/KioskHttpServer.kt` | the phone's routes (`/trigger_action` at line 360), `NETWORK_ACTIONS`, rate limits, decrypt and freshness | 384 | 5,300 |
| `esp32_firmware/src/WebServerModule.cpp` | route table, the OTA upload handler (line ~225) as the pattern for a chunked upload | 374 | 4,500 |
| `esp32_firmware/src/DeviceManager.cpp` + `WebServerTelemetry.cpp` | per-phone record (restarts, battery) and where the heartbeat is read; the capability flag goes here | 447 + 378 | 8,800 |
| `esp32_firmware/include/OriginCheck.h` | same-site rule for POSTs from the dashboard | 46 | 540 |
| `app/.../audio/KioskAudioManager.kt` | audio focus, `AudioTrack`, speech priority (978 lines: read only the ranges the group needs) | 978 | 10,400 |
| `app/.../service/KioskServerCoordinator.kt` | `onTriggerAction` wiring | 254 | 3,100 |
| `protocol/gen_vectors.py`, `esp32_firmware/host_tests/protocol_contract_test.cpp`, `app/src/test/.../ProtocolContractUnitTest.kt` | the contract both sides must reproduce | 80 + 74 + (not measured) | 2,100 + |
| `scripts/embed_web.py`, `scripts/check_product_strip.py` | how web files are packed and kept out of the PisoWiFi-only image | not measured | |
| `docs/REAL_WORLD_TESTING.md` | hardware check list: RS66 onward (18,800 tokens: append only, read the last 20 lines) | 372 | 18,800 |

Unresolved ledger entries loaded: none relevant (the open rows concern the router console and `test_flow`).

## Task groups
Small, ordered, each finished and checkable on its own. Test first: see the test fail, log it in `ledger/negative-constraints.md`, then fix in the source.
Group 0 is the only one that may stop the feature. Branch note: work happens on this session's branch `ccr-6f20d511-8e6cu1`; no merge to `main`, no pull request and no release unless the owner asks.

- [ ] 0. **Proof before code.** (files: `specs/features/056-voice-message/evidence/group0-findings.md`; no production code)
  (a) On this computer: headless Chromium (already installed) opens a plain `http://` page with and without the "treat as secure" setting and with a fake microphone; write down what `navigator.mediaDevices` is in each case and what `MediaRecorder` and an `AudioContext` give, so the recorder code in group 4 starts from facts.
  (b) Read `scripts/embed_web.py` and `scripts/check_product_strip.py` and decide where the page code lives (default: inside `portal-core-phones.js`, no new file).
  (c) Write the owner's hardware sheet for the numbers a computer cannot give: free heap and largest block on a real box before, during and after a 60 KB upload; whether the 15 s watchdog is fed; Android Chrome and desktop Chrome with the setting; an iPhone. These become RS66 onward; they set the memory reserve used in group 2 (until measured, the reserve is the larger of 24 KB, the `Diagnostics.cpp` floor, and 2 x the clip). Decision rule: if the real box cannot hold the clip above the reserve, **stop and ask the owner** (a smaller clip or a streamed relay is a spec change).
  (d) Red result logged: n/a (no code).
- [ ] 1. **The clip format and its vectors.** (files: `protocol/gen_vectors.py`, `protocol/fixtures/` (a new voice fixture; the existing `box_phone_v1.json` is not changed), `esp32_firmware/include/VoiceClip.h` (pure: header check, length cap), `esp32_firmware/host_tests/voice_clip_test.cpp`, `app/.../audio/VoiceClipDecoder.kt` + its unit test, the page's encoder tested in group 4)
  Test first: the Python reference encodes silence, a tone, a 15.0 s clip and the shortest clip; the C++ check and the Kotlin decoder must accept those and refuse over-length, lying-length, wrong-version and empty clips. Red result logged: yes/no.
- [ ] 2. **The box: relay and capability.** (files: `esp32_firmware/include/VoiceRelay.h` (pure: one at a time, reserve rule, byte cap; host test), `WebServerApi.cpp` / `WebServerModule.cpp` (the upload route `POST /api/voice?ip=...`, admin auth, `OriginCheck`, configured phones only, plain errors for busy / low memory / phone did not answer), `DeviceNetwork.cpp` (encrypt and sign the clip with the phone's key and post it to a new phone path `/voice`; `sendAuthenticated`'s 256-byte cap is not changed), `DeviceManager.cpp` + `WebServerTelemetry.cpp` + `/api/devices` (the per-phone `voice` capability flag))
  Test first: host tests for the admission rules, the refusals in criterion 6 and 7 (fake phone, byte-for-byte delivery, buffer freed on every path), and that `coin_*`, `payment`, `push_params` and `protocol_contract` host tests are unchanged. Red result logged: yes/no.
- [ ] 3. **The phone: receive, decode, play, announce the capability.** (files: `KioskHttpServer.kt` (the `/voice` route: same decrypt, freshness and replay checks, body cap), `VoiceClipDecoder.kt`, `KioskAudioManager.kt` (8 kHz `AudioTrack`, waits for the countdown speech, deletes the buffer), the banner (the existing "time added" pop-over pattern; read `overlay/` by range), `KioskServerCoordinator.kt`, the heartbeat field, `ProtocolContractUnitTest.kt`)
  Test first (Robolectric, JVM): criteria 6 and 8. No new manifest permission. Not run on a phone; ktlint, detekt and the Android unit tests need the Android SDK (CI `build-apk.yml` is the first real run if this container has none). Red result logged: yes/no.
- [ ] 4. **The admin page.** (files: `esp32_firmware/web/portal-core-phones.js`, `esp32_firmware/web/portal.css` between `/*@phones*/` and `/*@end*/`, then `python3 scripts/embed_web.py` to regenerate `WebAssets.h`)
  The mic / recording / plane / sending / sent / failed states, the 0.5 s accident rule, the cross, per-phone state kept outside the redrawn rows (like `window.locating`), the "mic blocked" messages, the greyed mic, the ADPCM encoder and the upload.
  Test first: browser tests (Playwright, fake microphone) for criteria 1 to 5, using the vectors from group 1; `check_product_strip.py` for the PisoWiFi-only image. Red result logged: yes/no.
- [ ] 5. **Words and hardware checks.** (files: `website/manual.html` (the owner's steps for the Chrome setting, the 15 s limit, what each icon means, that iPhones cannot record; site release only), `docs/REAL_WORLD_TESTING.md` (RS66 onward, unticked), `CHANGELOG.md`, `docs/ARCHITECTURE.md` section 3 (one line for the new message))
- [ ] 6. **Validate.** (`/sdd-validate`: fill `validation.md` with evidence, run the gates, update `specs/STATUS.md` and the release state; say plainly what was not run: no board, no phone, no real browser on the shop LAN, and possibly no Android SDK or PlatformIO)

## Release
- Raised: `PISO_FW_VERSION` (proposed 3.13.0, a new capability) and the app build number. Not raised: `setup/RELEASE` (no router file changes).
- Regenerated, never edited by hand: `esp32_firmware/include/WebAssets.h` (`python3 scripts/embed_web.py`).
- Firmware is signed by `firmware-images.yml` after a merge and the owner's approval in `production-signing`; the app by `build-apk.yml`. Nothing is released, merged to `main` or opened as a pull request unless the owner asks. Either release order is safe (see the spec).
- The manual page is a site release only.
