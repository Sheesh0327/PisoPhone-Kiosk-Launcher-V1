# PisoPhone: coin-operated phone rental kiosk

Customers insert coins into an ESP32 box; the box credits time to a locked-down Android phone (the kiosk), and can also
sell Wi-Fi time through an OpenWrt/openNDS router. Three parts plus a router add-on:

| Folder | What it is |
|---|---|
| `app/` | Android kiosk launcher (Kotlin, Compose, Room): locks the phone, counts paid time, talks to the box. |
| `esp32_firmware/` | Coin-box firmware (Arduino/PlatformIO, ESP32-C3 and ESP32): coin pulses, durable payment queue, admin dashboard, signed OTA. |
| `website/` | The PisoPhone website: the coin box flasher (`flash.html`), the phone setup page (QR code, or USB as the fallback), the router installer and the update feeds (`website/update`). |
| `router/` + `tools/pisoportal/` | Router add-on: `pisoportal` (resident Rust program: coin page over WebSocket, openNDS FAS), Telegram monitor; installed by `setup/piso-setup.sh`. |
| `setup/` | Setting up a site: `pisophone_setup.py` (the setup program, run on a computer) and the router setup it drives. |
| `protocol/`, `scripts/` | Shared test vectors; owner tools (keys, firmware signing, checks). |

```
coins -> ESP32 box <-- Wi-Fi (signed + encrypted) --> phone kiosk app
              |                                           |
        admin dashboard                       locks/unlocks the phone
```

## Setting up a site
One program does it all, from the bare boards to working phones: download
[`pisophone_setup.py`](https://pisophone.pages.dev/pisophone_setup.py) (also `setup/pisophone_setup.py`) and open it. A window
guides you through flashing the coin box, setting up the router (it asks for your Wi-Fi names and passwords), opening the phone
setup page, and testing a coin. The guide: [`setup/README.md`](setup/README.md); doing it by hand: [`setup/MANUAL.md`](setup/MANUAL.md).

## How it stays safe
Each box has its own secret (provisioned to its phones, wrapped in the Android Keystore). Payments are kept in a flash queue on the
box until the phone acknowledges them, and the phone credits each transaction id exactly once. Firmware and router updates are
signed with your offline owner key. Details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/KEYS.md`](docs/KEYS.md). Contributing: [`CONTRIBUTING.md`](CONTRIBUTING.md); security reports: [`SECURITY.md`](SECURITY.md); changes: [`CHANGELOG.md`](CHANGELOG.md).

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
CI (GitHub Actions, `.github/workflows/`) runs all of this on every push, only for the parts it touches; style is ktlint (Kotlin) and clang-format (`esp32_firmware/.clang-format`).

## Documentation
| Doc | For |
|---|---|
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | First run, CI, where APKs and firmware are published |
| [`docs/RELEASE.md`](docs/RELEASE.md) | Release checklist: the owner-only steps before a build goes to shops |
| [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md) | Hardware test checklist and issue log |
| [`docs/KEYS.md`](docs/KEYS.md) | Owner key, signed firmware, per-box secrets and migration |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How a coin becomes time; who talks to whom; where code lives |
| [`docs/PROVISIONING_SOLD_UNIT.md`](docs/PROVISIONING_SOLD_UNIT.md) | Secure boot and flash encryption for boxes you sell |
| [`docs/api/`](docs/api) | Router gateway API and super-admin credential format |
| [`setup/README.md`](setup/README.md) | Setting up a site with the setup program: coin box, router, phones |
| [`setup/MANUAL.md`](setup/MANUAL.md) | The same by hand, router commands, repair tools |

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
