<p align="center"><img src="website/img/pisohub-banner.jpg" alt="TechNet Solutions: PisoWiFi and PisoPhone" width="720"></p>

# TechNet PisoHub

**Coin-operated Wi-Fi and phone rental**, from TechNet Solutions.

One coin box serves a whole shop with two services:

- **PisoWiFi**: customer Wi-Fi sold by the coin, through an OpenWrt router running openNDS.
- **PisoPhone**: rental Android phones that lock and unlock by the minute, with prepaid QR time cards for regular players.

Both are run from the box's owner dashboard and one setup program.

## Start here
Pick the job you need. Each page is a short, step-by-step guide.

| I want to... | Go to |
|---|---|
| **Set up a shop** (coin box and router, about 15 minutes) | [Set up a shop](https://pisophone.pages.dev/start/shop.html) |
| **Add a phone** (a factory-reset Android phone becomes a rental kiosk) | [Add a phone](https://pisophone.pages.dev/start/phone.html) |
| **Add a print server** (optional: customers send files to print) | [Add a print server](https://pisophone.pages.dev/start/print.html) |

The pages are in [`website/start/`](website/start). Setting up a shop and its phones does not require copying or typing any
keys. The optional print server is the exception: its send key is still copied by hand until pairing is built
([`docs/api/print-pairing.md`](docs/api/print-pairing.md) is the specification).

## Status
- PisoPhone and PisoWiFi run in real shops. Each release is signed and published only after its checks pass.
- Not every feature has been tested on real hardware yet. The current release, and which checks are still waiting for hardware, is in
  [`specs/RELEASE_STATE.md`](specs/RELEASE_STATE.md). The hardware test log is [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md).
- Router support depends on the router's CPU. [`docs/ROUTERS.md`](docs/ROUTERS.md) lists the five CPU families and says which
  ones have been run on real hardware.
- What changed for owners, release by release: [`CHANGELOG.md`](CHANGELOG.md).
- There is no license file in this repository yet. Ask the owner before reusing or redistributing the code.

## How it is built
Customers insert coins into an ESP32 coin box. The box credits time to a locked-down Android phone (the kiosk app), and can also
sell Wi-Fi time through an OpenWrt router.

| Folder | What it is |
|---|---|
| `app/` | The Android kiosk app (Kotlin, Compose, Room). It locks the phone, counts paid time down, and talks to the coin box. |
| `esp32_firmware/` | The coin box firmware (Arduino/PlatformIO, ESP32-C3 and ESP32). It handles coin pulses, a durable payment queue, the owner dashboard and signed over-the-air updates. |
| `website/` | The public website: the setup pages, the router installer, and the signed update feeds (`website/update`). |
| `router/` and `tools/pisoportal/` | The router add-on. `pisoportal` is a small Rust program that serves the guest coin page and the owner console. A monitor sends optional Telegram alerts. The router add-on is installed by `setup/piso-setup.sh`. |
| `setup/` | The setup program (`pisophone_setup.py`, run on a computer) and the router installer it drives. |
| `protocol/`, `scripts/` | Shared test vectors for the box-to-phone messages; owner and maintainer tools. |

```
coins -> ESP32 coin box <-- Wi-Fi (signed and encrypted) --> phone kiosk app
              |                                                  |
     owner dashboard                              locks and unlocks the phone
```

## Setting up a site
One program does the whole setup, from bare boards to working phones:
[`pisophone_setup.py`](https://pisophone.pages.dev/pisophone_setup.py) (also in `setup/pisophone_setup.py`). The short steps are on the
[Start page](https://pisophone.pages.dev/start/shop.html). The full guide is [`setup/README.md`](setup/README.md). Doing it by hand, for
advanced users, is in [`setup/MANUAL.md`](setup/MANUAL.md).

## How it stays safe
- **Money and time.** The box records a coin before anything else. Payments wait in a flash queue on the box until the phone
  confirms them, and the phone credits each transaction once. The router credits each coin window once.
- **Per-box secret.** Each box makes its own random secret on first start and gives it to its phones during setup. The phone keeps
  the secret encrypted with the Android Keystore. Where the Keystore is unavailable, the phone falls back to plain storage. See
  [`docs/DEPLOY.md`](docs/DEPLOY.md).
- **Legacy mode.** A box upgraded from older firmware that already had Wi-Fi saved keeps its old key until the owner migrates it.
  The dashboard shows an orange banner until then. See [`docs/KEYS.md`](docs/KEYS.md).
- **Signed updates.** Firmware, router and app updates are installed only if the owner signed them. The signing key is the owner's.
  Where it is kept is the owner's choice: offline, or in GitHub's protected `production-signing` environment for one-tap publishing.
  The trade-off of the second option is in [`docs/KEYS.md`](docs/KEYS.md).
- **Factory defaults.** A new box uses published default passwords so it can be set up. It takes no coins until its admin password
  is changed.
- **Scope.** What the design protects against, and what it does not, is in [`SECURITY.md`](SECURITY.md).

## Build and test
```bash
# Android (JDK 17)
./gradlew :app:testDebugUnitTest :app:assembleRelease      # a release build needs the signing secrets: docs/DEPLOY.md

# Coin box firmware (PlatformIO)
cd esp32_firmware
sh host_tests/run.sh                                        # pure-logic tests; no board needed
pio run -e esp32-c3-dev                                     # also esp32dev-dev and the *-production-encrypted environments
pio run -e esp32-c3-dev -t upload

# Router add-on
(cd tools/pisoportal && cargo test && python3 tests/test_flow.py)
python3 router/tests/test_setup.py
```
CI (GitHub Actions, `.github/workflows/`) runs on pushes to `main` and `beta` and on pull requests into them. It runs only the
checks for the parts a change touches. Style is ktlint (Kotlin) and clang-format (`esp32_firmware/.clang-format`). The full list
of checks is in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Documentation
**For owners and operators**

| Document | For |
|---|---|
| [Start here](https://pisophone.pages.dev/start/) | Set up a shop, add a phone, add a print server: one page each |
| [`website/manual.html`](website/manual.html) | The owner's manual: setup, daily use, features, revenue and troubleshooting (print-ready) |
| [`setup/README.md`](setup/README.md) | The setup program in full: coin box, router, phones, owner tools and fixes |
| [`setup/MANUAL.md`](setup/MANUAL.md) | The advanced version: the same steps by hand, router commands and repair tools |
| [`docs/ROUTERS.md`](docs/ROUTERS.md) | Which routers work, the CPU families, and what each one needs |

**For maintainers** (keys, releases, CI, hardware tests)

| Document | For |
|---|---|
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | First run, CI, and where APKs and firmware are published |
| [`docs/RELEASE.md`](docs/RELEASE.md) | The release checklist, including the steps only the owner can take |
| [`docs/KEYS.md`](docs/KEYS.md) | The owner key, signed firmware, and per-box secrets |
| [`docs/LICENSING.md`](docs/LICENSING.md) | Phone slot licensing: how keys are made, sold and checked |
| [`scripts/README.md`](scripts/README.md) | What each script is for, grouped by who runs it and when |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How a coin becomes time, which part talks to which, and where the code lives |
| [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md) | The hardware test checklist and issue log |
| [`docs/PROVISIONING_SOLD_UNIT.md`](docs/PROVISIONING_SOLD_UNIT.md) | Secure boot and flash encryption for boxes you sell |
| [`docs/api/`](docs/api) | The router gateway API, the super-admin QR login and print server pairing |
| [`docs/maintainers/AUDIT_REPORT.md`](docs/maintainers/AUDIT_REPORT.md) | The security audit and what was done about each finding |
| [`docs/maintainers/HARDENING_PLAN.md`](docs/maintainers/HARDENING_PLAN.md) | The hardening pass: findings, fixes, confidence, hardware checks and release order |

## Branches
| Branch | Contains | Purpose |
|---|---|---|
| `main` | Everything: PisoPhone and PisoWiFi (openNDS) | Production. A feature branch is merged into it when the owner asks and Code quality is green. |
| `beta` | Everything | Where CI builds the router programs. A router change goes through it before it is released; keep it up to date with `main`. |

Flow: a feature is a branch from `main`, merged when it is green and the owner asks (hardware checklist: `docs/REAL_WORLD_TESTING.md`). A router change goes through `beta` first: bring `main` into `beta`, raise `setup/RELEASE`, let CI build the programs, then merge `beta` into `main` (`docs/RELEASE.md`).
The coin box firmware is shared by both products and includes both features.
