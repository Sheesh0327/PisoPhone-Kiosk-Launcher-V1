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
