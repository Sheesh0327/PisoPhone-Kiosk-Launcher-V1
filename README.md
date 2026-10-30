<p align="center"><img src="website/img/pisohub-banner.jpg" alt="TechNet Solutions: PisoWiFi and PisoPhone" width="720"></p>

# TechNet PisoHub

[![Code quality](https://github.com/Sheesh0327/PisoPhone-Kiosk-Launcher-V1/actions/workflows/quality.yml/badge.svg)](https://github.com/Sheesh0327/PisoPhone-Kiosk-Launcher-V1/actions/workflows/quality.yml)

**Coin-operated Wi-Fi and phone rental**, from TechNet Solutions. One ESP32 coin box serves a whole shop with two services:
**PisoWiFi**, customer Wi-Fi sold by the coin through an OpenWrt router running openNDS, and **PisoPhone**, rental Android phones that lock and
unlock by the minute, with prepaid QR time cards. Both are run from the box's owner dashboard and one setup program. Time is sold in
real shops every day, so two rules decide every change: money and time are never wrong, and only software the owner signed is installed.

## Start here
Pick the job you need. Each page is a short, step-by-step guide.

| I want to... | Go to |
|---|---|
| **Set up a shop** (coin box and router, about 15 minutes) | [Set up a shop](https://pisophone.pages.dev/start/shop.html) |
| **Add a phone** (a factory-reset Android phone becomes a rental kiosk) | [Add a phone](https://pisophone.pages.dev/start/phone.html) |
| **Add a print server** (optional: customers send files to print) | [Add a print server](https://pisophone.pages.dev/start/print.html) |

The pages are in [`website/start/`](website/start). Setting up a shop and its phones does not require copying or typing any
keys. The optional print server is the exception: its send key is still copied by hand until pairing is built
([`docs/REFERENCE.md`](docs/REFERENCE.md) is the specification). The full setup guide is [`setup/README.md`](setup/README.md).

## Repository layout
| Path | What it is |
|---|---|
| [`app/`](app) | The Android kiosk app (Kotlin, Compose, Room): locks the phone, counts paid time down, talks to the coin box. |
| [`esp32_firmware/`](esp32_firmware) | The coin box firmware (Arduino on PlatformIO, ESP32-C3 and ESP32): coin pulses, a durable payment queue, the owner dashboard, signed updates. |
| [`router/`](router) | Shell scripts and tests for the router add-on: the monitor, the firewall isolation, the services. |
| [`tools/`](tools) | `pisoportal` (the router's coin portal, Rust), the router setup builder and the test checklist tools. |
| [`setup/`](setup) | The setup program (`pisophone_setup.py`, run on a computer) and the router installer it drives. |
| [`protocol/`](protocol) | Test vectors for the box-to-phone messages. |
| [`scripts/`](scripts) | Owner and maintainer tools: keys, cards, signing, release checks. |
| [`website/`](website) | The public site: setup pages, the router installer and the signed update feeds. |
| [`docs/`](docs) | Architecture, releases and keys, hardware testing, design. Start at [`docs/README.md`](docs/README.md). |
| [`specs/`](specs), [`ledger/`](ledger), [`.claude/`](.claude) | How work is planned and checked (see "Development process"). |
| [`.github/`](.github) | CI workflows, issue and pull request templates, code owners. |
| [`gradle/`](gradle), `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `detekt.yml`, `detekt-baseline.xml` | The Gradle build of the app and its static analysis; Gradle and CI read them at the root. |
| `.editorconfig`, `.gitattributes`, `.gitignore` | Editor settings, line endings and binary files, files git leaves out. |
| `CLAUDE.md`, `constitution.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE` | Project rules, agent rules, changes for owners, how to contribute, how to report a vulnerability, the licence. |

How the parts fit, and where a coin goes: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Quick start for developers
You need the tools of the part you change: JDK 17 and the Android SDK (app), PlatformIO and `g++` with `libmbedtls-dev` (firmware), Rust
(router program), Python 3 (everything else) and Node.js (one website test). Versions and install lines: [`CONTRIBUTING.md`](CONTRIBUTING.md).
```bash
# Android app
./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug

# Coin box firmware
sh esp32_firmware/host_tests/run.sh                    # pure-logic tests, no board needed
(cd esp32_firmware && pio run -e esp32-c3-dev)

# Router program, router add-on and setup program
(cd tools/pisoportal && cargo test --locked --release && python3 tests/test_flow.py)
python3 router/tests/test_setup.py
python3 tools/build_piso_setup.py --check

# Website
node scripts/tests/test_provisioning.js

# Always
bash scripts/check_security_rules.sh
python3 scripts/check_links.py
```
The checks for each area, and how the parts are built, are in each folder's README. CI (`.github/workflows/`) runs on pushes and pull
requests to `main` and `beta` and runs only the checks for the parts a change touches.

## Status
- PisoPhone and PisoWiFi run in real shops. Each release is signed and published only after its checks pass.
- Not every feature has been tested on real hardware yet. The current release, and which checks are still waiting for hardware, is in
  [`specs/RELEASE_STATE.md`](specs/RELEASE_STATE.md). The hardware test log is [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md).
- Router support depends on the router's CPU. [`docs/ROUTERS.md`](docs/ROUTERS.md) lists the CPU families and which routers work.
- What changed for owners, release by release: [`CHANGELOG.md`](CHANGELOG.md).

## Security model
- **Money and time.** The box records a coin before anything else. Payments wait in a flash queue on the box until the phone
  confirms them, and the phone credits each transaction once. The router credits each coin window once.
- **Signed updates.** Firmware, router and app updates are installed only if the owner signed them. The signing keys are never in this
  repository, in a cloud session or in CI outside the protected `production-signing` environment ([`docs/KEYS.md`](docs/KEYS.md)).
- **Per-box secrets.** Each box makes its own secret on first start and gives it to its phones during setup. A new box uses a published
  default password and takes no coins until the admin password is changed ([`docs/DEPLOY.md`](docs/DEPLOY.md), [`docs/KEYS.md`](docs/KEYS.md)).
- **Scope.** What the design protects against, and what it does not, and how to report a vulnerability: [`SECURITY.md`](SECURITY.md).

## Documentation
[`docs/README.md`](docs/README.md) lists every document, grouped by reader: owner, developer, releases and keys, hardware testing, design.
The owner's manual is [`website/manual.html`](website/manual.html) (print-ready). Each part has a README with its layout, build and test
commands: [`app`](app/README.md), [`esp32_firmware`](esp32_firmware/README.md), [`router`](router/README.md), [`protocol`](protocol/README.md),
[`tools`](tools/README.md), [`setup`](setup/README.md), [`scripts`](scripts/README.md), [`website`](website/README.md).

## Development process
Work is spec-driven: no code without an approved feature spec in `specs/features/NNN-name/` (`spec.md`, `plan.md`, `validation.md`).
[`CLAUDE.md`](CLAUDE.md) is the entry point for people and Claude Code sessions: what the project is for, the rules, the checks. [`constitution.md`](constitution.md)
holds the agent rules. [`specs/`](specs/README.md) has the status board (`STATUS.md`), the release state, the templates and the loop (plan, implement,
validate). [`ledger/`](ledger) records failures. `.claude/skills/` holds the loop as skills (`sdd-plan`, `sdd-implement`, `sdd-validate`).

## Contributing
One feature, one branch from `main`; a green feature branch is merged into `main` when the owner asks; a router change goes through `beta`
first. Setup, checks, code style and commit messages: [`CONTRIBUTING.md`](CONTRIBUTING.md). Security problems: [`SECURITY.md`](SECURITY.md).

## Licence
Proprietary. Copyright (c) 2026 TechNet Solutions. All rights reserved. See [`LICENSE`](LICENSE).
