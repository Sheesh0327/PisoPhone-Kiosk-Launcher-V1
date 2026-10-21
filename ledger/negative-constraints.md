# Negative constraints ledger

Failures seen while working, logged before any fix is attempted (constitution rule 6). Prune entries older than 7 days or once resolved.
Earlier entries from the first run are kept on branch `claude/sleepy-gauss-rn31gl` (`ledger/negative-constraints.md`).

| date | task | model | error_class | traceback_hash | constraint_violated | resolved |
|------|------|-------|-------------|----------------|---------------------|----------|
| 2026-10-21 | feat 001 port of e827c98 | - | merge conflict | - | patch assumed 4b6312d (3 hunks in core.rs, ledger.rs) | yes: patch side taken, nothing needed from 4b6312d; builds and 87 tests pass |
| 2026-10-21 | feat 002 group 4 (6c6e008) | - | missing file in old work | AttributeError | `scripts/check_product_strip.py` calls `embed_web.assets_for`, which does not exist: `scripts/embed_web.py` was never committed with 6c6e008; running the old generator overwrote the product-aware `WebAssets.h` | fixing in feature 002 |
| 2026-10-21 | PR 94 Codex P2 (PeriodLedger.h epochDayToDate) | - | logic | host test | wrong divisors (1024, 1461, 142401) in the civil-date conversion: 2,257 of 47,500 days from 1970 came out as the wrong date (first: day 9) | yes: divisors 1460, 36524, 146096; new host test checks every day against a day-by-day count |
| 2026-10-21 | PR 94 Codex P1 (flash list, choose_build) | - | logic | test_phone_only / test_setup_phone_web | setup program took the first image for the chip, so PisoPhone-only and PisoWiFi-only flashed the full image: `choose_build() takes 2 positional arguments`, products ['full','full'] | yes: choose_build(manifest, chip, product); Session and the phone-only flow pass the owner's choice |
| 2026-10-21 | feat 003 LockWindowSpecUnitTest | - | expected red test | AssertionError expected:<0.0> but was:<1.0> | hidden lock window kept window alpha 1.0 (only the view was made invisible) | yes: window alpha 0 when hidden; OK (2 tests); not yet confirmed on the tablet |
| 2026-10-21 | feat 004 group 2 (FW-03) | - | expected red test | compile error | `coin_record_order_test` could not build on beta: `CoinRecordOrder.h` missing. The wiring order in `DeviceNetwork.cpp` (queue, revenue, LED) is not host-testable, checked by reading the diff | yes: helper added and wired; test OK |
| 2026-10-21 | feat 004 group 3 (FW-02) | - | expected red test | compile error | `coin_hw_apply_test` could not build on beta: `CoinHwApply.h` missing. Wiring (save only re-applies on change and when IDLE; detach of the attached pin) is not host-testable, checked by reading the diff and symbol names; no PlatformIO here | yes: helper added and wired; test OK |
