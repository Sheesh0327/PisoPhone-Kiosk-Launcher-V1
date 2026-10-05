# PisoPhone: coin-operated phone rental kiosk

Customers insert coins into an ESP32 box; the box credits time to a locked-down Android phone (the kiosk), and can also
sell Wi-Fi time through an OpenWrt/openNDS router. Three parts plus a router add-on:

| Folder | What it is |
|---|---|
| `app/` | Android kiosk launcher (Kotlin, Compose, Room): locks the phone, counts paid time, talks to the box. |
| `esp32_firmware/` | Coin-box firmware (Arduino/PlatformIO, ESP32-C3 and ESP32): coin pulses, durable payment queue, admin dashboard, signed OTA. |
| `website/` | Installer/provisioning page (WebUSB ADB) and the update feed (`website/update`). |
| `router/` + `tools/pisoportal/` | Router add-on: `pisoportal` (resident Rust program: coin page over WebSocket, openNDS FAS), Telegram monitor; installed by `setup/piso-setup.sh`. |
| `protocol/`, `scripts/` | Shared test vectors; owner tools (keys, licenses, firmware signing, checks). |

```
coins -> ESP32 box <-- Wi-Fi (signed + encrypted) --> phone kiosk app
              |                                           |
        admin dashboard                       locks/unlocks the phone
```

## How it stays safe
Each box has its own secret (provisioned to its phones, wrapped in the Android Keystore). Payments are kept in a flash queue on the
box until the phone acknowledges them, and the phone credits each transaction id exactly once. Licenses and firmware updates are
signed with your offline owner key. Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/KEYS.md`](docs/KEYS.md).

## Build and test
```bash
# Android (JDK 17)
./gradlew :app:testDebugUnitTest :app:assembleRelease      # release needs the signing secrets, see docs/DEPLOY.md

# Firmware (PlatformIO)
cd esp32_firmware
sh host_tests/run.sh                                        # pure-logic tests, no board needed
pio run -e esp32-c3-dev                                     # also esp32dev-dev and the two *-production-encrypted envs
pio run -e esp32-c3-dev -t upload

# Router add-on
(cd tools/pisoportal && cargo test && python3 tests/test_flow.py)
python3 router/tests/test_setup.py
```
CI (`.github/workflows/`) runs all of this on every push; style is ktlint (Kotlin) and clang-format (`esp32_firmware/.clang-format`).

## Documentation
| Doc | For |
|---|---|
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | First run, CI, where APKs and firmware are published |
| [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md) | Hardware test checklist and issue log |
| [`docs/KEYS.md`](docs/KEYS.md) | Owner key, licenses, signed firmware, per-box secrets and migration |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How a coin becomes time; who talks to whom; where code lives |
| [`docs/PROVISIONING_SOLD_UNIT.md`](docs/PROVISIONING_SOLD_UNIT.md) | Secure boot and flash encryption for boxes you sell |
| [`docs/api/`](docs/api) | Router gateway API and super-admin credential format |
| [`setup/README.md`](setup/README.md) | Router setup: one file (`piso-setup.sh`) from a factory-reset router; `piso-setup update` afterwards |

## Branches
| Branch | Contains | Purpose |
|---|---|---|
| `main` | Everything: PisoPhone + PisoWiFi (OpenNDS) | Production. Only receives tested code from `beta`. |
| `beta` | Everything | Testing and bug fixing; all shared fixes land here first. |
| `pisophone` / `pisophone-beta` | Phone rental only (app, ESP32 firmware, provisioning website) | Stable / testing builds of the phone product. |
| `pisowifi` / `pisowifi-beta` | Piso Wi-Fi only (ESP32 firmware, openNDS portal) | Stable / testing builds of the Wi-Fi product. |

Flow: fix and test on a `*-beta` branch (checklist: `docs/REAL_WORLD_TESTING.md`), then merge it into its stable branch. Fixes to
shared parts (firmware, keys, CI) go to `beta` first and are merged into `pisophone-beta` and `pisowifi-beta`; the files each product
does not ship were removed once and stay removed on merge. The ESP32 firmware is shared and still contains both features.
