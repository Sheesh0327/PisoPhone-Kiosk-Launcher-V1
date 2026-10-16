<p align="center"><img src="website/img/pisohub-banner.jpg" alt="TechNet Solutions: PisoWiFi and PisoPhone" width="720"></p>

# TechNet PisoHub

**Coin-powered Wi-Fi and phone rentals.** By TechNet Solutions.

PisoHub is one coin box for a whole shop. It runs two services: **PisoWiFi** (customer Wi-Fi sold by the coin) and **PisoPhone** (rental phones that lock and unlock by the minute, with prepaid time cards for regular players), both managed from one owner dashboard.

## Start here
**Opening a shop? You do not need anything else in this file.** Pick the job:

| I want to... | Go to |
|---|---|
| **Set up a shop** (coin box and router, about 15 minutes) | [Set up a shop](https://pisophone.pages.dev/start/shop.html) |
| **Add a phone** (a factory-reset Android phone becomes a rental kiosk) | [Add a phone](https://pisophone.pages.dev/start/phone.html) |
| **Add a print server** (optional: customers send you a file to print) | [Add a print server](https://pisophone.pages.dev/start/print.html) |

The pages are in [`website/start/`](website/start). Nobody has to read, copy or type a key to open a shop; the keys are the maintainer's
business and are described in the "For maintainers" part of the documentation table below.

## How it is built
Customers insert coins into an ESP32 box; the box credits time to a locked-down Android phone (the kiosk), and can also
sell Wi-Fi time through an OpenWrt/openNDS router. Three parts plus a router add-on:

| Folder | What it is |
|---|---|
| `app/` | Android kiosk launcher (Kotlin, Compose, Room): locks the phone, counts paid time, talks to the box. |
| `esp32_firmware/` | Coin-box firmware (Arduino/PlatformIO, ESP32-C3 and ESP32): coin pulses, durable payment queue, admin dashboard, signed OTA. |
| `website/` | The PisoPhone website: the phone setup page (QR code, or USB as the fallback), the router installer and the update feeds (`website/update`). |
| `router/` + `tools/pisoportal/` | Router add-on: `pisoportal` (resident Rust program: coin page over WebSocket, openNDS FAS), Telegram monitor; installed by `setup/piso-setup.sh`. |
| `setup/` | Setting up a site: `pisophone_setup.py` (the setup program, run on a computer) and the router setup it drives. |
| `protocol/`, `scripts/` | Shared test vectors; owner tools (keys, firmware signing, checks). |

```
coins -> ESP32 box <-- Wi-Fi (signed + encrypted) --> phone kiosk app
              |                                           |
        admin dashboard                       locks/unlocks the phone
```

## Setting up a site
One program does it all, from the bare boards to working phones: [`pisophone_setup.py`](https://pisophone.pages.dev/pisophone_setup.py)
(also `setup/pisophone_setup.py`). The short steps are on the [Start page](https://pisophone.pages.dev/start/shop.html); the full
guide is [`setup/README.md`](setup/README.md), and doing it by hand is [`setup/MANUAL.md`](setup/MANUAL.md) (advanced).

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
**For owners and operators**

| Doc | For |
|---|---|
| [Start here](https://pisophone.pages.dev/start/) | Set up a shop, add a phone, add a print server: one screen each |
| [`website/manual.html`](website/manual.html) | **Owner's Manual**: setup, daily use, every feature, revenue, troubleshooting (print-ready) |
| [`setup/README.md`](setup/README.md) | The setup program in full: coin box, router, phones, owner tools, fixes |
| [`setup/MANUAL.md`](setup/MANUAL.md) | Advanced: the same by hand, router commands, repair tools |
| [`docs/ROUTERS.md`](docs/ROUTERS.md) | Which routers work (five CPU families), what they need |

**For maintainers** (keys, releases, CI, hardware tests)

| Doc | For |
|---|---|
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | First run, CI, where APKs and firmware are published |
| [`docs/RELEASE.md`](docs/RELEASE.md) | Release checklist: the owner-only steps before a build goes to shops |
| [`docs/KEYS.md`](docs/KEYS.md) | Owner key, signed firmware, per-box secrets and migration |
| [`scripts/README.md`](scripts/README.md) | What every script is for, grouped by who runs it and when |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How a coin becomes time; who talks to whom; where code lives |
| [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md) | Hardware test checklist and issue log |
| [`docs/PROVISIONING_SOLD_UNIT.md`](docs/PROVISIONING_SOLD_UNIT.md) | Secure boot and flash encryption for boxes you sell |
| [`docs/api/`](docs/api) | Router gateway API, the super-admin QR login, print server pairing |
| [`docs/maintainers/AUDIT_REPORT.md`](docs/maintainers/AUDIT_REPORT.md) | The security audit and what was done about each finding |

## Branches
| Branch | Contains | Purpose |
|---|---|---|
| `main` | Everything: PisoPhone + PisoWiFi (OpenNDS) | Production. Only receives tested code from `beta`. |
| `beta` | Everything | Testing and bug fixing; all shared fixes land here first. |

Flow: fix and test on `beta` (checklist: `docs/REAL_WORLD_TESTING.md`), then merge it into `main` (`docs/RELEASE.md`). The ESP32
firmware is shared by both products and contains both features.
