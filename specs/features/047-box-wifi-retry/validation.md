# Validation: feature 047, a box that never joined takes new Wi-Fi details

Run 2026-10-28 on a computer (Linux container; no ESP32 board, no PlatformIO, no mbedtls development files). Router tests run with `< /dev/null`.
Branch `feat/047-box-wifi-retry` from `origin/main` (2591448) plus the epic commit.

| # | Success criterion (spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | AC3.1 the state rule: only a never-joined, payment-free box set up over USB takes new details | `host_tests/serial_provision_test.cpp` (38 checks) | pass (computer) | needed / joining / done for: factory box; set up and waiting; joined once (flag cleared); set up by older firmware or the web page (no flag); lifetime coins 1; a pending payment; an unpersisted payment; the flag without a changed password. `acceptsSetup` only for needed and joining |
| 2 | AC3.2 hello carries `setup=joining` and `why=`; old fields unchanged; the old program still reads it | `serial_provision_test.cpp`, `wifi_link_test.cpp` (18 checks: `whyCode`), `test_phone_only.py` (`parse_status` of the new line, the Python box model) | pass (computer) for the rules and the program's reading; **the hello line itself is ESP32 code and was not run** | `whyCode`: 14/15/202/204 wrong-password, 201 no-network, else none; the line is `PISO-BOX fw= mac= setup= wifi= ip= why=` (code review of `SerialCli.cpp`) |
| 3 | AC3.3 `applySetup` keeps the flag; the first address clears it | code review (`SerialCli.cpp`, `Config.cpp` `markWifiJoined`, `main.cpp`) | **not run: ESP32-only code** | `wifi_wait` is saved before the password-changed flag; cleared once, at the first got-IP, writing flash only that once; a factory reset clears it with everything else |
| 4 | AC3.4 program: a joining box gets no ERASE question, no rewrite, the new Wi-Fi; the reason is shown | `test_phone_only.py` (terminal flow, `wait_online` message, Python box), `test_setup_phone_web.py` (page) | pass (computer) | flashed 0 times, no ERASE prompt, new details accepted; boxes that joined, have a payment or were set up another way still refused; `wait_online` says the box's own reason and "without an erase"; the page test failed on the old program (it flashed the board) |
| 5 | AC3.5 version raised; tests, format, generated files, security | below | pass (computer) | `PISO_FW_VERSION` 3.12.5; `--check` passes; only `website/pisophone_setup.py` changed among generated files, no router file |
| 6 | AC3.6 RS62 added, unticked; owner runs it before merge (D8) | `docs/REAL_WORLD_TESTING.md` | **RS62 not run** | |

## Gates (constitution rule 7)
- [x] Firmware host tests: 38 of 46 built and passed (`g++ -std=c++17 ... -fsanitize=address,undefined`, as `run.sh`). The 8 that need mbedtls (`admin_qr`, `admin_session`, `card_codec`, `fw_manifest`, `gateway_auth`, `gateway_event`, `protocol_contract`, `slot_license`) were **not built here** (`libmbedtls-dev` is missing and cannot be installed); none of them includes a file changed by this feature. CI runs all of them.
- [x] `clang-format --dry-run -Werror` clean on every changed firmware file.
- [ ] **ESP32 build: not made** (`pio` is not installed here). The Arduino/ESP-IDF parts are three small edits (`Config.cpp`, `main.cpp`, `SerialCli.cpp`); CI (`firmware-images.yml` builds, and the PlatformIO tests) is the first real compile.
- [x] Router tests: `test_setup` 458, `test_pisophone_setup` 232, `test_setup_web` 115, `test_offline_setup` 98, `test_setup_tools` 78, `test_install` 48, `test_phone_only` 51, `test_setup_phone_web` 34 checks, 0 failures.
- [x] `bash scripts/check_security_rules.sh`: all passed.
- [x] Python: `ruff check setup/pisophone_setup.py` no new finding (46, the same as `main` without the scratch-copy artifact); `mypy` 8 errors before and after; `pytest -x` cannot collect these script tests.
- [x] Generated files: `python3 tools/build_piso_setup.py` then `--check` passes; `git status` shows no router file changed, so `setup/RELEASE` is not raised.

## Not tested
Nothing ran on a real board, router, phone or computer. Not seen: the real hello line on a board (the format is by code review); NVS writes and the clearing of `wifi_wait` at the first IP; the real `Wi-Fi disconnect` reason codes (the mapping uses the codes `WifiLink.h` already names); that the first-run `PISO-SETUP` really restarts into a box that the later run sees as `joining` (RS62); the 8 mbedtls host tests; the ESP32 build. The first-IP event in `reportWifiEvents` runs from `loop()`, so a box that gets an address and loses power within that moment would still count as not joined: it then simply takes new details again, which is harmless (no payment can exist yet).
Hardware check added: **RS62** (a to c).

## Money and time
Not touched. No payment, coin, credit, expiry or `revenue.csv` code was changed. The new state reads `totalCoinsLifetime`, `getPendingPaymentCount()` and `hasUnpersistedPayments()` and refuses `PISO-SETUP` when any of them says money exists (host test: lifetime coins, pending and unpersisted each force `done`). A box already working in a shop cannot become "joining" by this update: the flag exists only on a box whose Wi-Fi was given by this firmware's USB setup.
