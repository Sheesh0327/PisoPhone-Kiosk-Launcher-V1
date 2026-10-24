# Plan: 017 Coin box always on

Unresolved ledger entries loaded: none for the firmware.
| file | purpose | lines | est_tokens |
|---|---|---|---|
| `esp32_firmware/src/WebServerAuth.cpp:69-250` | AuthWorker task: liveness counter, back-off, ACK parse | 180 | 3,000 |
| `esp32_firmware/src/main.cpp:126-165` | health check every 10 s | 40 | 700 |
| `esp32_firmware/src/WebSocketsUdp.cpp:266-445` | WebSocket handshake (FW-07), ACK document (FW-13) | 180 | 3,500 |
| `esp32_firmware/src/Config.cpp:162-215` | master clock (FW-10), boot counter (FW-11) | 60 | 1,000 |
| `esp32_firmware/src/HardwareManager.cpp:96-115` | pulse reset (FW-08) | 20 | 400 |
| `esp32_firmware/src/Discovery.cpp`, `WebServerApi.cpp:314-445` | beacon, probe reply, status buffer (FW-17, FW-18) | 120 | 2,000 |
| `esp32_firmware/include/HealthPolicy.h`, `host_tests/health_policy_test.cpp` | restart policy | 120 | 1,500 |

## Task groups (test first for every pure rule)
- [x] 1. New pure headers with host tests (red: the tests did not build until the headers and the `HealthPolicy` action existed): `WorkerLiveness.h` (AO-07), `AckParse.h` (FW-14), `MasterClock.h` (FW-10), `PushBackoff.h` (FW-04), `PushParams.h` (FW-12, FW-13).
- [x] 2. Use them: AuthWorker counter and watch in `main.cpp` (restart only through `canPerformRebootOrOta()`), ACK parse, back-off, no sleeping while others wait (FW-04); master clock in a critical section (FW-10); pulse reset under `pulseMux` (FW-08); refuse to arm without a boot counter (FW-11); refuse and log params that do not fit, 512-byte ACK document (FW-12, FW-13).
- [x] 3. FW-07: the handshake is read a little on every pass (one at a time), not in a 2 s blocking loop. FW-17: beacon every 15 s, the probe reply goes to the prober and its broadcast copy is rate-limited. FW-18: `/api/status` is built in one kept buffer.
- [x] 4. `PISO_FW_VERSION` 3.12.1; clang-format with the CI's version (23.1.2) clean; host tests (37 here, 8 more in CI).
- [ ] 5. Open the pull request into `beta`; read the firmware build and host tests; fix every red check until green.

## Release
Firmware 3.12.1 (`PISO_FW_VERSION`). The signed images are built by `firmware-images.yml` on `beta`/`main`; this feature publishes nothing.
