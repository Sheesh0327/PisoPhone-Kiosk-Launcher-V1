# Contributing

Read `README.md` for what the parts are and `docs/ARCHITECTURE.md` for how they fit. `CLAUDE.md` describes the workflow used
here (an architect writes issues, an implementer makes the pull request) and the checks every change must pass.

## Branches
- Work on a branch made from `beta` and open the pull request **into `beta`**.
- `main` is the production channel: it is only updated by merging `beta` into it (`docs/RELEASE.md`). Merging to `main` publishes
  the phone app, the coin-box firmware and the router update, so nothing goes there that was not tested on `beta`.

## Before you open a pull request
Run the checks for every area you touched (the full list is in `CLAUDE.md`, section 3):

| Area | Commands |
|---|---|
| Android app | `./gradlew :app:testDebugUnitTest assembleDebug`, `./ktlint --relative "app/src/**/*.kt" "*.kts" "app/*.kts"` |
| ESP32 firmware | `cd esp32_firmware/host_tests && ./run.sh`, `pio run -e esp32-c3-dev && pio run -e esp32dev-dev`, `clang-format --dry-run --Werror <files>` |
| Dashboard files (`esp32_firmware/web/`) | `python3 scripts/embed_web.py` then `python3 scripts/embed_web.py --check` |
| Router / setup | `python3 router/tests/test_monitor.py`, `python3 router/tests/test_pisophone_setup.py`, `python3 tools/build_piso_setup.py --check` |
| Website | `node scripts/test_provisioning.js`, `node scripts/test_flasher.js`, `python3 scripts/tests/test_site_page.py` |
| Always | `bash scripts/check_security_rules.sh` |

## Rules that are easy to break
- **Generated and published files are never edited by hand**: `esp32_firmware/include/WebAssets.h`, `setup/piso-setup.sh`,
  `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`, `website/update/*`, `website/flash/*`,
  `tools/pisoportal/bin/*`, `app/schemas/*`. Change the source and regenerate (`CLAUDE.md`, section 4).
- **The box <-> phone message contract changes on both sides in one pull request** (`protocol/gen_vectors.py` and
  `esp32_firmware/host_tests/protocol_contract_test.cpp`).
- **Never commit a key, password or token.** Owner keys are made on the owner's own computer (`docs/KEYS.md`).
- **Raise the version of what you ship**: `PISO_FW_VERSION` for firmware, `setup/RELEASE` for the router. Boxes and routers install
  only a newer version. The app version comes from the CI run number.
- Money and time accounting (credit, expiry, deduct, the payment queue) is reviewed carefully; explain how you checked it.

## Reporting problems
Use the issue templates. For anything security related, follow `SECURITY.md` instead of opening an issue.
