# esp32_firmware: the coin box

The firmware of the ESP32 coin box. It takes the coins, records every payment in a durable queue before anything else, delivers it to
the phone (or the router) until it is acknowledged, and serves the box dashboard. One box serves both products: PisoPhone (rental
phones) and PisoWiFi (the router's coin page). C++ on the Arduino framework, built with PlatformIO. How the parts fit:
`docs/ARCHITECTURE.md`.

## Layout
- `src/`, `include/`: the firmware. `include/Product.h` says which product an image is. `include/FirmwareVersion.h` holds
  `PISO_FW_VERSION`; a box installs an update only if this number is higher.
- `web/`: the dashboard's CSS and JavaScript (`web/README.md`).
- `host_tests/`: tests of the pure logic that run on a computer with no board (`run.sh`).
- `envs/`: the PlatformIO environments. `platformio.ini` sets the default (`esp32-c3-dev`) and reads `envs/*.ini`. Chips: ESP32-C3 and
  ESP32 (`esp32dev`). Kinds: `-dev` (no flash encryption) and `-production-encrypted`. `envs/products.ini` adds one image per
  product (`-phone`, `-wifi`): the other product's files are left out of the build.
- `partitions_encrypted.csv`, `.clang-format`: the partition table for encrypted builds, and the C++ style.

## Build
Needs PlatformIO (CI pins `platformio==6.2.0`). Run from this folder:
```
pio run -e esp32-c3-dev
pio run -e esp32dev-dev
```
Production builds need the owner's public keys in `include/LicensePubKey.h` and `include/SuperAdminPubKey.h` (`docs/KEYS.md`); until
then they hold a placeholder and CI prints a warning. Hardening a box you sell: `docs/PROVISIONING_SOLD_UNIT.md`.
CI (`firmware-images.yml`) builds the images that the setup program writes to a box and, on `main`, publishes the signed update.

## Test
```
sh host_tests/run.sh
clang-format --dry-run --Werror <files>
```
`run.sh` makes the test fixtures with Python 3 (the `cryptography` package), then builds each `host_tests/*_test.cpp` with `g++`
(linked with `-lmbedcrypto`, so `libmbedtls-dev` is needed) and runs it. The list of files for `clang-format` is in
`.github/workflows/quality.yml` (job `firmware-style-and-tests`). No board is needed for either command; a change that touches
hardware still needs a check on a real box (`docs/REAL_WORLD_TESTING.md`).

## Generated files
`include/WebAssets.h` is made from `web/` by `python3 scripts/embed_web.py` (`--check` fails when it is out of date). Never edit it by hand.
A change to the box <-> phone message format changes `protocol/gen_vectors.py` and `host_tests/protocol_contract_test.cpp` together.

## See also
`docs/REFERENCE.md` (coin slot protection, phone slots, super-admin QR login), `docs/api/gateway-coinslot.md` (the router API),
`protocol/README.md`, `docs/KEYS.md`, `docs/RELEASE.md`.
