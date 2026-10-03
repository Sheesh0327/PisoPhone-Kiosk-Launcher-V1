# Deploying

## First run
1. `cd esp32_firmware && sh host_tests/run.sh && pio run -e esp32-c3-dev -t upload` (dev environment: no flash encryption).
2. Serial monitor at 115200 baud: the box prints its setup-AP and admin passwords. Open the dashboard, change the admin password,
   connect Wi-Fi, and set the gateway key (`opennds/INSTRUCTIONS.md`, step 4).
3. Install the router package and follow `opennds/INSTRUCTIONS.md` (Layout A: box on the modem's network; Layout B: separate guest and
   kiosk networks).
4. Work through `docs/REAL_WORLD_TESTING.md`.

Keys are not needed for a first test (`docs/KEYS.md`); until a public key is built in, flash firmware by USB.

## CI
`quality.yml` runs on every push: clang-format, firmware host tests, a real PlatformIO build of all four firmware environments,
security-rule checks, and the OpenNDS tests (portal flow with a fake box, UCI settings, Layout B config, `.ipk` package, which it
uploads as a build artifact).

## Publishing firmware
Sign each build (`docs/KEYS.md`), copy `firmware-<chip>.bin` and its `.manifest.json` into `website/update/`, and let
`update-firmware-json.yml` update `firmware.json`. The box's update page downloads them from the website.
