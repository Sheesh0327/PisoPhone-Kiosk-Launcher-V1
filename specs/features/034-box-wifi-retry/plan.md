# Plan: feature 034, a box that never joined takes new Wi-Fi details

Context manifest (constitution rule 1; line numbers from `origin/main` at `2591448`, re-check with `grep -n` before editing):

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `esp32_firmware/include/SerialProvision.h` | the USB setup rules (pure); the state rule goes here | 1-102 | 1,400 |
| `esp32_firmware/src/SerialCli.cpp` | hello and `applySetup` | 1-105 | 1,300 |
| `esp32_firmware/include/WifiLink.h` | disconnect reasons; the `why` mapping goes here | 1-90 | 900 |
| `esp32_firmware/src/main.cpp` | Wi-Fi events (`reportWifiEvents`, ~line 52-85): last reason, first IP | read ranges only | 600 |
| `esp32_firmware/src/Config.cpp`, `include/Config.h` | NVS keys, `adminPwChanged` (82, 610); the `wifi_wait` flag | read ranges only | 700 |
| `esp32_firmware/include/PaymentQueueManager.h` | pending / unpersisted payment facts (26-27) | 20-40 | 200 |
| `esp32_firmware/include/FirmwareVersion.h` | version | 1-8 | 100 |
| `esp32_firmware/host_tests/serial_provision_test.cpp`, `wifi_link_test.cpp`, `run.sh` | tests | read ranges only | 1,500 |
| `setup/pisophone_setup.py` | `provision_new_box` (~4238), `do_pstart_install` (~4675), terminal flow (~4268), `wait_online` | read ranges only | 2,500 |
| `router/tests/test_phone_only.py`, `test_pisophone_setup.py`, `test_setup_phone_web.py` | program tests | read ranges only | 2,000 |
| `docs/REAL_WORLD_TESTING.md` | RS58 | one section | 300 |

Unresolved ledger entries loaded: none for the firmware or the PisoPhone-only setup. Baseline (2026-10-28, `origin/main`): router tests 458 / 232 / 115 / 98 / 78 / 48 / 39 / 31 checks, 0 failures (spec 03 section 4); firmware host tests: to be run first (`esp32_firmware/host_tests/run.sh`).

## Task groups
Test first for each: see it fail, log it in `ledger/negative-constraints.md` (table form), fix in the source, one commit per group, no push to `beta`. Run slow tests in the background and read the last lines only.

- [ ] 1. The state rule, pure: `provision::SetupState`, `setupState(facts)`, `acceptsSetup(state)`, and `wifilink::whyCode(reason)` (files: `SerialProvision.h`, `WifiLink.h`; tests: `serial_provision_test.cpp`, `wifi_link_test.cpp`; red result logged: yes/no)
- [ ] 2. The box uses it: `NVS_KEY_WIFI_WAIT` saved by `applySetup` and cleared at the first IP, `why=` and `setup=joining` in the hello, `applySetup` accepts `joining`, the last disconnect reason is kept (files: `SerialCli.cpp`, `main.cpp`, `Config.cpp/.h`; test: the pure rule is group 1; here code review plus `clang-format`; the ESP32 build is **not** made (no `pio`); red result logged: not applicable, say so)
- [ ] 3. Program, PisoPhone-only flow: `joining` is not `done` and not `needed`: no ERASE, no rewrite, the typed Wi-Fi replaces the saved one, the reason is shown (files: `setup/pisophone_setup.py` `provision_new_box`, `do_pstart_install`, the terminal flow, `wait_online`'s message, the Check-up line; tests: `test_phone_only.py`, `test_pisophone_setup.py`, `test_setup_phone_web.py` with a fake board; red result logged: yes/no)
- [ ] 4. Close-out: raise `PISO_FW_VERSION` (3.12.5), rebuild the website copy (`python3 tools/build_piso_setup.py`, `--check`, `git status` shows no router file), RS58 in `docs/REAL_WORLD_TESTING.md`, `CHANGELOG.md`, `setup/README.md` (one line), run all gates (firmware host tests, clang-format, the router tests, ruff/mypy diff against `main`, `check_security_rules.sh`), `validation.md`, row SU-16 to `fixed` in `specs/spec_03_findings.json`, `specs/STATUS.md`.

## Release
Firmware: `PISO_FW_VERSION` 3.12.4 -> 3.12.5 (`FirmwareVersion.h`); `firmware-images.yml` builds, signs and publishes after the merge (the owner approves in the `production-signing` environment, `docs/RELEASE.md`). Regenerated, never edited: the website's copy of the setup program (and `setup/piso-setup*.sh` only if a router file would change, which it must not). No push to `beta`, no pull request, no merge from here: the feature waits for the owner's RS58 bench run (D8).
