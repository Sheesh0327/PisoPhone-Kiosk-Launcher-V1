# Validation: 017 Coin box always on

Rows AO-07, FW-04, FW-07, FW-08, FW-10, FW-11, FW-12, FW-13, FW-14, FW-17, FW-18; firmware 3.12.1. **The firmware was not compiled in this container** (no PlatformIO, no board): the pull request's CI "Firmware build" is the compile gate. Nothing ran on a box.

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Host tests incl. the new ones | `esp32_firmware/host_tests` (a local runner of `run.sh`'s compile line: 37 tests here, the 8 that need mbedtls run in CI) | **pass locally (37), CI for the rest** | ok=37 bad=0 skipped=8 (mbedtls). New: `worker_liveness_test` (idle worker is never stalled, stall only after 60 s with work waiting, recovers, millis wrap, restart only when safe), `ack_parse_test` (a key hidden in another key or value is not that key, first of a repeated key wins, missing signature, empty values), `master_clock_test` (never set, millis wrap, a torn read is not possible with whole stamps), `push_backoff_test` (skip after two failures for 30 s, success clears, table full), `push_params_test` (255 fits and 256 does not; the largest legal ACK needs more than 256 bytes and fits 512) |
| 2 | CI firmware build and host tests green | the PR's checks | see the PR | |
| 3 | `PISO_FW_VERSION` 3.12.1 | `include/FirmwareVersion.h` | **pass** | |
| 4 | Hardware checks | `docs/REAL_WORLD_TESTING.md` RS46 | **added, not run** | |
| 5 | clang-format (CI's 23.1.2) | `clang-format --dry-run --Werror` on the CI's file list | **pass** | no output |

## How each row was done (and how it was checked)
- AO-07: `WorkerLiveness.h` watches `authWorkerPasses` (bumped per request and per attempt) while `uxQueueMessagesWaiting(authQueue) > 0`; after 60 s `health::workerStallAction(stalled, safe && canPerformRebootOrOta())` restarts through the existing health path. Host test only; the live behaviour is RS46 (f).
- FW-04: `PushBackoff.h` + no sleeping when others wait. Host test; hardware RS46 (a).
- FW-07: the handshake is read byte by byte across passes (`pumpHandshake`), one at a time, with the same 2 s limit and the same routing code (moved unchanged into `routeWebSocketHandshake`). **Not tested by anything automatic**: RS46 (b), (c). The most delicate change of this feature.
- FW-08, FW-10, FW-11, FW-12, FW-13, FW-14: as in the spec; the pure parts have host tests (FW-10, FW-12, FW-13, FW-14); FW-08 and FW-11 are one-line guards read in review and by CI's compile.
- FW-17, FW-18: smallest change (beacon interval, probe reply, kept buffer). No test possible without a box: RS46 (d), (e).

## Not tested
Everything on a real box; the 8 mbedtls host tests locally; the firmware compile for any board (CI). The box <-> phone contract did not change, so `protocol_contract_test` is unaffected, but it was not run here (it needs mbedtls).

## Money and time
Coin counting is not rewritten. The pulse reset now takes the lock the sensor's interrupt counts under (FW-08); the master clock pair is read whole (FW-10); an unreadable boot counter refuses to arm (FW-11); a push that does not fit is refused (FW-12); a phone's receipt is parsed by a tested function with the same acceptance rules as before (FW-14: same fields, same signature check, same unsigned-only-without-a-secret rule). A stalled worker restarts the box only through `canPerformRebootOrOta()` (no open coin session, no payment only in RAM, no pulse in flight).
