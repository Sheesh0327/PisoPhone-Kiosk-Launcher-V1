# Negative constraints ledger

Failures seen while working, logged before any fix is attempted.

## 2026-10-21: FW-03 `coin_record_order_test` (expected red test, before the fix)

Command (WSL, g++ 17; no native g++ on this Windows machine):
`g++ -std=c++17 -include limits.h -include sys/param.h -Wall -Wextra -Werror -o /tmp/cro coin_record_order_test.cpp`

```
coin_record_order_test.cpp:5:10: fatal error: ../include/CoinRecordOrder.h: No such file or directory
    5 | #include "../include/CoinRecordOrder.h"
      |          ^~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
compilation terminated.
```

Cause: the header does not exist yet. Test written first on purpose; fix is to add `esp32_firmware/include/CoinRecordOrder.h`.
Resolved: header added; `coin_record_order_test` passes (with `-fsanitize=address,undefined`).

## 2026-10-21: `host_tests/run.sh` in WSL, link error (environment, not FW-03)

Command: `sh esp32_firmware/host_tests/run.sh` (WSL Ubuntu)

```
/usr/bin/x86_64-linux-gnu-ld.bfd: cannot find -lmbedcrypto: No such file or directory
collect2: error: ld returned 1 exit status
```

Cause: `libmbedtls-dev` is not installed in the WSL distro (run.sh header says it is required). `set -e` stops the whole
suite at the first test that links it. Not caused by the FW-03 change.

## 2026-10-21: FW-02 `coin_hw_apply_test` (expected red test, before the fix)

Command (WSL): `g++ -std=c++17 -include limits.h -include sys/param.h -Wall -Wextra -Werror -fsanitize=address,undefined -o /tmp/chw coin_hw_apply_test.cpp`

```
coin_hw_apply_test.cpp:5:10: fatal error: ../include/CoinHwApply.h: No such file or directory
compilation terminated.
```

Cause: the header does not exist yet. Test written first on purpose.

## 2026-10-21: FW-01 `restart_guard_test` (expected red test, before the fix)

```
restart_guard_test.cpp:4:10: fatal error: ../include/RestartGuard.h: No such file or directory
```

Cause: the header does not exist yet. Test written first on purpose.

## 2026-10-21: FW-05 `coin_credit_test` (expected red test, before the fix)

```
coin_credit_test.cpp:5:10: fatal error: ../include/CoinCredit.h: No such file or directory
```

Cause: the header does not exist yet. Test written first on purpose.

## 2026-10-21: router rows RT-01..RT-07 `cargo test` (expected red tests, before the fix)

Command (WSL): `cd tools/pisoportal && cargo test`, with the new tests and behaviour-preserving stubs of the new functions.
15 tests failed on assertions, each as intended, for example:

```
core::tests::rt01_reconnect_keeps_a_session_...   RT-01: the paid session was not deleted as expired before the shift ran  left: 0 right: 1
ledger::tests::rt04_a_torn_last_row_...           left: Err(3)  right: Ok((2, 15, true))
roll::tests::rt02_a_session_paid_after_the_jump_is_not_moved   left: 3 right: 1
voucher::tests::rt06_an_existing_voucher_key_is_never_overwritten   left: Some("3e63...") right: None
util::tests::rt14_no_other_file_renames_a_file_itself   config.rs renames a file itself
core::tests::rt07_...                              left: "w9L" right: "w9L3"
```

Cause: the fixes are not written yet. Tests written first on purpose.
## 2026-10-21: router rows: `roll::tests::session_and_purge` after the RT-01 fix

```
---- roll::tests::session_and_purge stdout ----
thread 'roll::tests::session_and_purge' panicked at src/roll.rs:573:9: assertion failed: load(&p).is_empty()
```

Cause: the test relied on `session()` deleting an expired line (the behaviour RT-01 removes). Its second mint then topped the still-present line up, so purge's two-day limit was not reached. Fix: the test now checks the line stays after `session()` and goes at `purge`. Not a defect in the program.

## 2026-10-21: router rows: `python3 tests/test_flow.py` timing flakes (existing checks, not the new ones)

```
(run 1, right after cargo build --release, machine busy)  FAIL: a fresh page's first view is its own, not the last result: {'t': 'state', 's': 'final', ... 'pulses': 1, 'minutes': 6, 'left': 360, 'online': True}
(run 2)  FAIL: after 3 pesos in all the pause is unlocked: None
(run 3)  202 checks, 0 failures
```

Cause: wall-clock waits in checks that predate this work (a page that opens within a second of the previous result; a 3 s coin window); each failed once under load in WSL on /mnt/c and passed on the rerun. The old binary run (before the fixes) showed neither. Re-run to confirm; not changed.

## 2026-10-21: environment-only failures seen while checking router rows (not caused by R1)

- `router/tests/test_pisophone_setup.py`: "the website serves the same setup program as setup/pisophone_setup.py". The two files
  are identical in git; on this Windows checkout (CRLF) they differ byte for byte. `build_piso_setup.py --check` passes.
- `router/tests/test_setup_web.py`: `RemoteDisconnected` from the local test server when run under WSL. Not looked into.
- `test_flow.py` needs `PISOPORTAL=<release binary>`; without it the stale `target/release` binary gave 12 false failures.

## 2026-10-21: APP-04 skipped on purpose

Refusing payments until a state restore succeeds could leave a phone that never takes coins again, and there is no device or
Android SDK here to try it. Left open for the owner (spec status `owner-decision`).