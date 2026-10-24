# Feature 017: Coin box always on

Status: **approved** (owner, spec 02 section 8, 2026-10-24)   Branch: `feat/017-coin-box-always-on`   Area: firmware   Release: firmware 3.12.1 (`PISO_FW_VERSION`, raised once)
Backlog rows: AO-07 (`specs/spec_02_findings.json`); FW-04, FW-07, FW-08, FW-10, FW-11, FW-12, FW-13, FW-14, FW-17, FW-18 (`specs/spec_01_findings.json`, spec 01 sections 6, R3 to R6; no prior work)

## Intent
A coin box that runs for months must keep crediting phones after a worker task hangs, a clock is read half-written, an ACK is parsed
loosely, or a switched-off phone stalls everyone else. Each row is small and local. Serves rule 1 (money and time are never wrong).

## Invariants touched
- **Money and time: touched, carefully.** Coin counting (ISR, `coinPulsesTake`) is not rewritten. What changes around it: the pulse
  reset happens under the same lock as the ISR (FW-08); the master clock is read and written in a critical section so a 64-bit time is
  never half old, half new (FW-10); the box refuses to arm a coin window when it has no boot counter, because transaction ids would
  repeat between boots (FW-11); a phone push whose parameters do not fit is refused loudly, not cut (FW-12, FW-13); a phone's ACK is parsed
  by one pure function with tests (FW-14); the AuthWorker task is watched and the box restarts itself only through the existing guard
  `canPerformRebootOrOta()` (AO-07). A payment is still recorded first, credited once by transaction id, and nothing is lost on a power cut.
- Only owner-signed software: not touched (nothing installs anything).

## Constraints
- **No PlatformIO and no board in a cloud session:** the firmware is not compiled here. The pull request's CI "Firmware build" and "Firmware format and host tests" are the compile gate. `host_tests/run.sh` runs here for the 32 tests that do not need mbedtls (8 need it and run in CI).
- `PISO_FW_VERSION` is raised **once**, to 3.12.1, or boxes ignore the update.
- The box <-> phone message contract does not change (no new fields, no changed meaning); FW-17 changes only *how often* the beacon is sent and *where* a probe reply goes.
- Hardware-only rows (FW-04, FW-07, FW-17, FW-18) are implemented with the smallest change and a pure rule where one exists; their checks are hardware checks written into `docs/REAL_WORLD_TESTING.md` (owner runs them): this feature never claims them verified.
- clang-format (`esp32_firmware/.clang-format`) clean.

## Success criteria
1. `host_tests/run.sh` (in CI; locally the 32 tests that need no mbedtls) passes with the new tests: `worker_liveness_test.cpp` (AO-07), `ack_parse_test.cpp` (FW-14), `master_clock_test.cpp` (FW-10), `push_backoff_test.cpp` (FW-04), and added cases for FW-11/12/13 in `tx_id_test` / a new `push_params_test.cpp`.
2. CI "Firmware build" compiles every board and "Firmware format and host tests" is green.
3. `PISO_FW_VERSION` is 3.12.1.
4. Hardware checks RS46 (AuthWorker hung, two phones with one off, a held-open port 81, beacon/heap soak) are in `docs/REAL_WORLD_TESTING.md`.

## Out of scope
The box <-> phone protocol changes (HARDENING_PLAN items 1 to 3, a later spec); FW-26 (splitting the big functions); the phone app.

## Owner decisions
Answered in spec 02 section 8 (build now, firmware 3.12.1 once).
