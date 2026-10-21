# Negative constraints ledger

Failures seen while working, logged before any fix is attempted (constitution rule 6). Prune entries older than 7 days or once resolved.
Earlier entries from the first run are kept on branch `claude/sleepy-gauss-rn31gl` (`ledger/negative-constraints.md`).

| date | task | model | error_class | traceback_hash | constraint_violated | resolved |
|------|------|-------|-------------|----------------|---------------------|----------|
| 2026-10-21 | feat 003 LockWindowSpecUnitTest | - | expected red test | AssertionError expected:<0.0> but was:<1.0> | hidden lock window kept window alpha 1.0 (only the view was made invisible) | yes: window alpha 0 when hidden; OK (2 tests); not yet confirmed on the tablet |
| 2026-10-21 | feat 004 group 2 (FW-03) | - | expected red test | compile error | `coin_record_order_test` could not build on beta: `CoinRecordOrder.h` missing. The wiring order in `DeviceNetwork.cpp` (queue, revenue, LED) is not host-testable, checked by reading the diff | yes: helper added and wired; test OK |
| 2026-10-21 | feat 004 group 3 (FW-02) | - | expected red test | compile error | `coin_hw_apply_test` could not build on beta: `CoinHwApply.h` missing. Wiring (save only re-applies on change and when IDLE; detach of the attached pin) is not host-testable, checked by reading the diff and symbol names; no PlatformIO here | yes: helper added and wired; test OK |
| 2026-10-21 | feat 004 group 4 (FW-01) | - | expected red test; format | compile error; clang-format | `restart_guard_test` could not build on beta: `RestartGuard.h` missing. The old commit was not clang-format clean under 23.1.2 (3 comment spacings, one long line): reformatted | yes: helper added, reset pin and `canPerformRebootOrOta` wired; test OK; format clean |
| 2026-10-21 | feat 004 group 5 (FW-05) | - | expected red test | compile error | `coin_credit_test` could not build on beta: `CoinCredit.h` missing (beta only had `if (minutesPerCoin < 1)`, no upper limit, int multiplication) | yes: helper added and used in Config load, Settings save and the phone coin path; test OK |
| 2026-10-21 | feat 004 group 6 (FW-06) | - | expected red test | compile error | `idle_coin_test` could not build on beta: `IdleCoin.h` missing. The old commit had no test for this row, so the 5 s limit and the pulse count were moved into a small helper (new, not in `eeae494`) so a test exists. Wiring in `CoinSlotManager.cpp` and `PaymentQueueManager.cpp` is checked by reading the diff | yes: helper added and wired; test OK |
