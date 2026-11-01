# Plan: 061 box <-> phone signatures that cannot stand in for each other

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none for firmware/app protocol.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| protocol/gen_vectors.py, protocol/README.md, protocol/fixtures/box_phone_v1.json | how vectors are made and read (headers and first rows only) | ~150 | ranges only |
| esp32_firmware/src/WebServerAuth.cpp | box proof (line 137) and HTTP ack check (176 to 200) | ~260 | ranges only |
| esp32_firmware/src/WebSocketsUdp.cpp | WS ack check (498 to 522) | ~700 | ranges only |
| esp32_firmware/src/WebServerCoinslot.cpp | `/api/coinslot/ack` and the signed request format (lines 100 to 130, 300 to 340) | ~350 | ranges only |
| esp32_firmware/src/WebServerTelemetry.cpp | the heartbeat reply the box builds | ~120 | ranges only |
| esp32_firmware/host_tests/protocol_contract_test.cpp | the contract test | to measure | ranges only |
| app/.../network/CoinProof.kt | proof check, seconds cap | 40 | 0.8k |
| app/.../network/Esp32HeartbeatBodyHandler.kt | what a reply is allowed to change (lines 20 to 75) | ~120 | 1.5k |
| app/.../server/KioskHttpServer.kt | the phone's acknowledgement (lines 270 to 310) | ~400 | ranges only |
| app/.../network/Esp32ConnectionManager.kt | where the reply is read (needs the header too) | large | ranges only |
| docs/HARDENING.md, docs/ARCHITECTURE.md section 3, docs/api/ | the words | ~100 | ranges only |

Never opened: `esp32_firmware/include/WebAssets.h` and other generated files.

## Task groups
Small, ordered, each finished and checkable on its own. Test first. Nothing here starts before the owner approves the spec and the sequencing in Owner decisions 1.

- [ ] 1. **Vectors and recorded old messages.** `protocol/gen_vectors.py` gains `ack1`, `v2`, `r1` vectors; record real old-format messages (coin push, status answer, ack, heartbeat reply) as fixtures from the **current** code, so the "old" side of every test is the real old format. (red result logged: yes, the readers fail until the vectors exist)
- [ ] 2. **Heartbeat reply (item 3), box side.** `X-Reply-Sig` header on the reply; host test with the vectors. (files: `WebServerTelemetry.cpp` and the helper; test: `host_tests`; red result logged: yes)
- [ ] 3. **Heartbeat reply, phone side.** Check over raw bytes, latch in prefs per box secret, dangerous fields ignored once latched. (files: `Esp32ConnectionManager.kt` for the header, `Esp32HeartbeatBodyHandler.kt`; test: JVM unit tests + the mixed-version table; red result logged: yes)
- [ ] 4. **Acknowledgement (item 1), phone side.** Add `ack_sig` next to `v_sig` in the HTTP answer and the WebSocket ACK. (files: `KioskHttpServer.kt`, the WS sender; test: unit tests; red result logged: yes)
- [ ] 5. **Acknowledgement, box side.** Accept `ack_sig`; latch per slot in NVS; refuse the old form for a latched phone; a refused ack leaves the payment queued. (files: `WebServerAuth.cpp`, `WebSocketsUdp.cpp`, the per-slot record; test: host tests with recorded messages, the existing queue tests unchanged; red result logged: yes)
- [ ] 6. **Seconds (item 2).** `v2_sig` from the box; the phone latch; tests including a tampered `seconds`. (red result logged: yes)
- [ ] 7. **The mixed-version matrix** (criterion 4) and the "no stuck state" cases (criterion 6) as named tests; run the whole firmware host suite and the app unit tests.
- [ ] 8. **Documents, hardware checks, gates.** `protocol/README.md`, `docs/api/`, `docs/ARCHITECTURE.md`, `docs/HARDENING.md` (mark items 1 to 3 as fixed only after the hardware checks pass), `CHANGELOG.md`, two `RS` checks; area gates from `CONTRIBUTING.md` (clang-format, host tests, the ESP32 builds in CI, ktlint, detekt, unit tests) and `bash scripts/check_security_rules.sh`. Say plainly what could not be run (no Android SDK or board here).

## Release
Firmware: higher `PISO_FW_VERSION`, signed by the approved run. App: higher `versionCode`. Both are safe alone (old and new accepted side by side). Not released until the owner says; stage it with feature 059 if that exists by then. Update `specs/RELEASE_STATE.md`, `specs/STATUS.md` and the HARDENING list.
