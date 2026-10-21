# Plan: firmware money rows (feature 004)

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: feat 003 row (resolved in code, tablet check owed; not related).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `esp32_firmware/src/DeviceNetwork.cpp` | phone coin path: queue, revenue, LED, push (FW-03) | 220 | ~2.5k |
| `esp32_firmware/src/CoinSlotManager.cpp` | slot state, idle coin (FW-06), hardware re-apply hook (FW-02) | 453 | ~5k |
| `esp32_firmware/src/HardwareManager.cpp` | coin pin and reset pin (FW-01, FW-02) | 316 | ~3.5k |
| `esp32_firmware/src/WebServerConfig.cpp` | Settings save, reboot, update, reset (FW-01, 02, 05) | 408 | ~4.5k |
| `esp32_firmware/src/PaymentQueueManager.cpp` | queue, "busy" test (FW-01); read only the busy function | 633 | read ~80 lines |
| `esp32_firmware/src/Config.cpp` | minutes-per-coin load and clamp (FW-05); read ~40 lines | 814 | read ~40 lines |
| `esp32_firmware/host_tests/*_test.cpp` (4 new) + `run.sh` | tests, auto-discovered by `*_test.cpp` | ~165 | ~2k |
| `git show beba862 5ebf443 eeae494` (without the ledger) | the source of the port | 327 added | ~4k |

## Task groups
Test first: in each group, add the test, run it on the unfixed code and see it fail (log it in `ledger/` and `validation.md`), then add the fix, see it pass. One commit per group. Port with `git diff <commit>^ <commit> -- . ':!ledger' | git apply --index --3way`.

- [x] 1. Read the feature 002 diff for coin-counter changes (`git log -p 60c8eaf^..60c8eaf -- esp32_firmware/src/CoinSlotManager.cpp esp32_firmware/src/HardwareManager.cpp` line ranges only) and confirm FW-02, FW-05, FW-06 do not duplicate it. Stop and amend the spec if they do. (no code). Result: feature 002 did not touch the queue/revenue order, the minutes-per-coin limit, the idle-coin path or the re-apply on save; no overlap.
- [x] 2. FW-03 order: `coin_record_order_test.cpp` + `CoinRecordOrder.h` + `DeviceNetwork.cpp`. Red result logged: yes (compile failure: helper missing)
- [ ] 3. FW-02 apply only on change and when idle: `coin_hw_apply_test.cpp` + `CoinHwApply.h` + `HardwareManager`, `WebServerConfig`, `CoinSlotManager`. Red result logged: no
- [ ] 4. FW-01 restart guard: `restart_guard_test.cpp` + `RestartGuard.h` + `PaymentQueueManager`, `HardwareManager`, `WebServerConfig`. Red result logged: no
- [ ] 5. FW-05 clamp and 64-bit credit: `coin_credit_test.cpp` + `CoinCredit.h` + `Config`, `DeviceNetwork`, `WebServerConfig`. Red result logged: no
- [ ] 6. FW-06 idle coin to the orphan list: find or write the host test first, then `CoinSlotManager`. Red result logged: no
- [ ] 7. `FirmwareVersion.h` to 3.11.1; add RS28 to RS31 to `docs/REAL_WORLD_TESTING.md` (keep RS32, RS33); run all gates (below); fill `validation.md`; update `STATUS.md` and the five backlog rows to `fixed`.

Gates for every group: `cd esp32_firmware/host_tests && ./run.sh` (background, last lines only), `clang-format --dry-run --Werror` on touched files, `bash scripts/check_security_rules.sh`. At the end: `pio run` for both environments if PlatformIO exists here.

## Release
Raise `PISO_FW_VERSION` to 3.11.1 (one raise for the batch). CI (`firmware-images.yml`) regenerates `website/flash/*` and `website/update/*`; never by hand. No router, setup or app release. Not released to `main` until the owner asks; the version stays unreleased on `beta` and `RELEASE_STATE.md` is updated when it goes out.
