# PisoWiFi: coin-operated Wi-Fi with an ESP32 coin box and OpenNDS

*Branch `pisowifi`: the Wi-Fi product only (no phone-rental app). See **Branches** below.*

Customers join the shop Wi-Fi, see the openNDS portal, insert coins into the ESP32 box and get internet time. The router asks the
box for the coins over a signed gateway API, then grants the minutes through openNDS.

| Folder | What it is |
|---|---|
| `esp32_firmware/` | Coin-box firmware (Arduino/PlatformIO, ESP32-C3 and ESP32): coin pulses, gateway API, admin dashboard, signed OTA. |
| `opennds/` | Router side: portal theme (`theme_coinslot.sh`), coin-slot listener, `.ipk` package, Layout B network setup. |
| `website/` | Firmware update feed for the box dashboard (`website/update`). |
| `protocol/`, `scripts/` | Test vectors; owner tools (keys, licenses, firmware signing, gateway test client). |

```
customer phone --Wi-Fi--> openNDS portal (router) --signed gateway API--> ESP32 box (coin slot)
```
How it works: [`opennds/README.md`](opennds/README.md). Gateway API: [`docs/api/gateway-coinslot.md`](docs/api/gateway-coinslot.md).

## Build and test
```bash
cd esp32_firmware
sh host_tests/run.sh                 # pure-logic tests, no board needed
pio run -e esp32-c3-dev -t upload    # also esp32dev-dev and the two *-production-encrypted envs
python3 opennds/tests/test_flow.py   # router flow against a fake box (from the repo root)
python3 opennds/package/build_ipk.py --version 1.0.0   # router package
```
CI (`.github/workflows/quality.yml`) runs all of this on every push.

## Documentation
| Doc | For |
|---|---|
| [`opennds/INSTRUCTIONS.md`](opennds/INSTRUCTIONS.md) | Router setup (package install, Layout A and B) |
| [`docs/DEPLOY.md`](docs/DEPLOY.md) | First run, CI, publishing firmware |
| [`docs/REAL_WORLD_TESTING.md`](docs/REAL_WORLD_TESTING.md) | Hardware test checklist and issue log |
| [`docs/KEYS.md`](docs/KEYS.md) | Owner key, licenses, signed firmware |
| [`docs/PROVISIONING_SOLD_UNIT.md`](docs/PROVISIONING_SOLD_UNIT.md) | Secure boot and flash encryption for boxes you sell |
| [`docs/api/`](docs/api) | Gateway API and super-admin credential format |

## Branches
| Branch | Contains | Purpose |
|---|---|---|
| `main` | Everything: PisoPhone + PisoWiFi (OpenNDS) | Production. Only receives tested code from `beta`. |
| `beta` | Everything | Testing and bug fixing; all shared fixes land here first. |
| `pisophone` / `pisophone-beta` | Phone rental only (app, ESP32 firmware, provisioning website) | Stable / testing builds of the phone product. |
| `pisowifi` / `pisowifi-beta` | Piso Wi-Fi only (ESP32 firmware, OpenNDS theme and listener) | Stable / testing builds of the Wi-Fi product. |

Flow: fix and test on a `*-beta` branch (checklist: `docs/REAL_WORLD_TESTING.md`), then merge it into its stable branch. Fixes to
shared parts (firmware, keys, CI) go to `beta` first and are merged into `pisophone-beta` and `pisowifi-beta`; the files each product
does not ship were removed once and stay removed on merge. The ESP32 firmware is shared and still contains both features.
