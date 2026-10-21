# Validation: firmware money rows (feature 004)

Branch `feat/004-firmware-money`. Status: **code done and host-tested; not compiled with PlatformIO, not tried on a box.**

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Four tests fail before the fix and pass after | each test built alone on `beta` code, then with the fix | pass, with a limit | Before: all four stopped with `fatal error: ../include/<Helper>.h: No such file or directory` (CoinRecordOrder, CoinHwApply, RestartGuard, CoinCredit), logged in `ledger/`. This is a build failure, not an assertion failure: the logic lives in new pure helpers. After: `coin_record_order_test OK`, `coin_hw_apply_test OK`, `restart_guard_test OK`, `coin_credit_test OK`. The call sites (DeviceNetwork, HardwareManager, WebServerConfig, CoinSlotManager, PaymentQueueManager) cannot be run on a computer; checked by reading each diff and the symbols (declarations, includes). |
| 2 | FW-06 has a test that fails before and passes after | new `idle_coin_test.cpp` (the old commit had none) | pass, with the same limit | Before: `fatal error: ../include/IdleCoin.h`; after: `idle_coin_test OK` (pulse count, first record, 5 s limit, millisecond wrap). |
| 3 | Whole host suite passes | `cd esp32_firmware/host_tests && ./run.sh` | pass | log ends `15 checks, 0 failures`, exit 0; 32 lines of OK / 0 failures, includes the five new tests |
| 4 | clang-format and security rules | `clang-format --dry-run --Werror` (23.1.2) on every touched `.cpp` / `.h`; `bash scripts/check_security_rules.sh` | pass | `fmt-all-ok`; `security rules: all passed`. The old commit was not clean under 23.1.2 (comment spacing, one long line): reformatted in group 4 |
| 5 | Firmware builds for both boards | `pio run -e esp32-c3-dev`, `-e esp32dev-dev` | **not run here** | PlatformIO is not installed in this session. CI `firmware-images.yml` builds it on the pull request / push; read its result before calling this done |
| 6 | `PISO_FW_VERSION` is 3.11.1 | `grep PISO_FW_VERSION esp32_firmware/include/FirmwareVersion.h` | pass | `#define PISO_FW_VERSION "3.11.1"` |
| 7 | RS28 to RS31 in `docs/REAL_WORLD_TESTING.md`, not run, next to RS32 and RS33 | file | pass | added after RS26; RS32 and RS33 unchanged |
| 8 | STATUS and backlog rows updated | `specs/STATUS.md`, `specs/spec_01_findings.json` | pass | FW-01, 02, 03, 05, 06 are `fixed` (not `verified-on-hardware`) |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: host tests, clang-format; **`pio run` not run** (see 5)
- [x] `bash scripts/check_security_rules.sh`
- [x] Python: no Python touched (`ruff`, `mypy`, `pytest` not applicable)
- [x] Generated files: none touched; `embed_web.py --check` says `WebAssets.h is up to date`

## Differences from the old commits
- Ledger and checklist hand-merged (RS28 to RS31 kept, RS32 and RS33 unchanged).
- `eeae494` was split into FW-01, FW-05 and FW-06 so each has its own commit; FW-06 now uses the new `IdleCoin.h` helper and has a test; reformatted for clang-format 23.1.2.
- Feature 002 overlap checked first (group 1): it did not touch the queue and revenue order, the minutes-per-coin limit, the idle-coin path or the re-apply on save.

## Not tested
- Anything on a box: no coin was dropped, no reset pin held, no power cut. Hardware checks RS28 (settings saved during a coin), RS29 (reboot, update, reset with a slot armed), RS30 (power cut between coin and queue), RS31 (idle coin and large rate) are added and **not run**.
- The firmware compile for both boards (CI).
- Wording: the BUSY reply to a reboot or update still says "Unpersisted transactions in RAM" even when the reason is a coin in flight (the log line was updated, the web reply was not, as in the old commit). Harmless; can be changed in a later firmware batch.

## Money and time
Touched on purpose. FW-03: the payment is queued before revenue is counted, so revenue never counts a coin the phone cannot collect (host test: order Q, R, L). FW-02: a Settings save no longer re-attaches the coin interrupt or zeroes the pulse counter while a coin may be arriving (host test of the decision). FW-01: restart waits for idle slot, no counted-but-unqueued pulse and no RAM-only payment; the reset pin resets after the 5 s hold plus at most 30 s more (host test: not before 5000 ms, safe at 5000, unsafe until 35000). FW-05: minutes per coin 1..1440, credit in 64 bits capped at INT_MAX seconds (host test). FW-06: an idle-slot coin goes to the orphan list, at most one record per 5 s (host test). No change to the box <-> phone message formats or the stored format.
