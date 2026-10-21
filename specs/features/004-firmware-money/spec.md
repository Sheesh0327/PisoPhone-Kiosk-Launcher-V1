# Feature 004: firmware money rows (a coin is never lost, double counted or mis-credited)

Status: **implemented on the branch, waiting for CI and owner review** (approved 2026-10-21; reset pin waits up to 30 s, then resets, confirmed by the owner)   Branch: `feat/004-firmware-money`   Area: firmware
Backlog rows: FW-01, FW-02, FW-03, FW-05, FW-06 (`specs/spec_01_findings.json`)   Release: firmware (`PISO_FW_VERSION` 3.11.1)

## Intent
The coin box is where every peso is first counted. Five rows say a coin or its credit can still go wrong on firmware 3.11.0: a coin dropped while Settings is saved is lost (FW-02); a power cut between "revenue counted" and "payment queued" leaves revenue for a payment the phone can never collect (FW-03); reboot, update or reset can strike while a coin is being counted (FW-01); a big "minutes per coin" setting can wrap into a wrong credit (FW-05); a coin at an idle slot is only logged, not kept (FW-06). Serves project rule 1.

The fixes already exist, written test first, on branch `claude/sleepy-gauss-rn31gl` as `beba862` (FW-03), `5ebf443` (FW-02) and `eeae494` (FW-01, 05, 06). This feature brings them back, it does not rewrite them. A trial apply onto `beta` (b76f909) was done without the ledger and the checklist doc: every source and test file applies cleanly; the only conflicts are `ledger/negative-constraints.md` and `docs/REAL_WORLD_TESTING.md` (hand-merged: add RS28 to RS31 and keep RS32, RS33).

## What each row changes (from the commits)
- **FW-03** (`DeviceNetwork.cpp`, new `CoinRecordOrder.h`): the phone path now queues the payment first, then counts revenue, then LED, then pushes, the same order as the gateway path.
- **FW-02** (`HardwareManager`, `WebServerConfig`, `CoinSlotManager`, new `CoinHwApply.h`): coin hardware is re-applied only when the coin pin, pulse guard or pulse width changed, and only while the slot is IDLE (a change made meanwhile is applied once idle). A coin pin change detaches the pin really attached (it used to detach the new one).
- **FW-01** (new `RestartGuard.h`, `PaymentQueueManager`, `Config`, `WebServerConfig`, `HardwareManager`): reboot, update, factory reset and the reset pin also wait while a slot is in use or a pulse is counted but not queued. The reset pin waits up to 30 s more, then resets anyway so a stuck box can still be recovered.
- **FW-05** (new `CoinCredit.h`, `Config`, `DeviceNetwork`, `WebServerConfig`): minutes per coin kept to 1..1440 (also when loaded from older settings); credit seconds worked out in 64 bits and capped.
- **FW-06** (`CoinSlotManager`): a coin with no phone owning the slot is written to the orphan payments list, at most one entry every 5 s.

## Invariants touched
- Money and time: **touched, and this is the point.** Order of queue then revenue (FW-03); no coin lost on a save (FW-02); no reset mid-count (FW-01); no wrapped credit (FW-05); no unrecorded idle coin (FW-06). Each is covered by a host test that fails before the fix. No change to the signed phone/box message formats.
- Only owner-signed software: not touched (OTA check and signing unchanged). The version raise ships through the existing signed firmware pipeline.

## Constraints
1. Port the existing commits' code; do not redesign. Any difference from them is written in `validation.md`.
2. Version 3.11.0 already carries feature 002's coin-counter hardening. Group 1 checks that FW-02, FW-05 and FW-06 do not duplicate or fight it (read the 002 diff before porting); if they do, stop and amend this spec.
3. No change to the box <-> phone protocol (`protocol/gen_vectors.py`, `protocol_contract_test.cpp`), the stored settings format other than clamping minutes per coin on load, or `WebAssets.h` (nothing under `esp32_firmware/web/` changes).
4. Reset pin: the 30 s wait must end in a reset, never a box that can not be reset.
5. `PISO_FW_VERSION` is raised once, to 3.11.1, or boxes ignore the update.
6. Generated and published files (`website/flash/*`, `website/update/*`) are made by CI, never by hand.

## Success criteria
1. `coin_record_order_test`, `coin_hw_apply_test`, `restart_guard_test` and `coin_credit_test` each **fail on today's `beta` code and pass with the fix**; the failing result is shown, not assumed.
2. Orphan-on-idle (FW-06) has a test that fails before and passes after (the old commit lists "3.11.1 batch"; if it has none, one is written first).
3. The whole `esp32_firmware/host_tests/run.sh` passes (needs `libmbedtls-dev`, python `cryptography`).
4. `clang-format --dry-run --Werror` clean on the touched files; `bash scripts/check_security_rules.sh` passes.
5. `pio run -e esp32-c3-dev` and `-e esp32dev-dev` build (if PlatformIO is available here; otherwise CI's firmware images workflow, and `validation.md` says which).
6. `PISO_FW_VERSION` is 3.11.1.
7. Hardware checks RS28 to RS31 are in `docs/REAL_WORLD_TESTING.md`, marked not run, next to RS32 and RS33.
8. `STATUS.md` and the backlog rows move to `fixed` (not `verified-on-hardware`).

## Out of scope
- FW-04, FW-07 to FW-14 and the cleanup rows FW-15 to FW-26 (later R3 to R6 features).
- Phone app rows APP-01 to APP-10 (feature 005, needs the Android SDK).
- Any router change; `setup/RELEASE` is not raised.
- Releasing to `main` or rolling the firmware out to live boxes.

## Owner decisions
- Reset pin waits up to 30 s for a safe moment, then resets anyway: confirmed by the owner 2026-10-21.
