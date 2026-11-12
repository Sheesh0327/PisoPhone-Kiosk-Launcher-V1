# Contributing

Read `README.md` for what the parts are and `docs/ARCHITECTURE.md` for how they fit. `CLAUDE.md` says what the project is for, where it stands and the rules
for working on it (also for Claude Code sessions).

## Branches and releases
Follow `CLAUDE.md` section 5 for branches, the required router `beta` build, merge gates and owner approval.
`main` is the production channel; release procedures are in `docs/RELEASE.md`.

## Before you open a pull request
Run every check for the area you touched. For the router add-on also run `cargo test` and `python3 tests/test_flow.py`
in `tools/pisoportal`, plus `python3 router/tests/test_setup.py`, `python3 router/tests/test_setup_web.py` and
`python3 router/tests/test_setup_tools.py`. For the setup program also run `python3 router/tests/test_setup_phone_web.py`
and `python3 router/tests/test_phone_only.py`. CI (`.github/workflows/quality.yml`) selects checks by changed area.
Code style: ktlint for Kotlin and `esp32_firmware/.clang-format` for firmware.

| Area | Commands |
|---|---|
| Android app | `./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug` (and `bash scripts/ci_emulator_tests.sh` with an emulator or phone connected), `./ktlint --relative "app/src/**/*.kt" "*.kts" "app/*.kts"`, `java -jar detekt-cli-1.23.8-all.jar --config config/detekt/detekt.yml --build-upon-default-config --baseline config/detekt/detekt-baseline.xml --input app/src/main/java` (new findings fail; coverage minimums: `app/coverage-minimums.txt`) |
| ESP32 firmware | `cd esp32_firmware/host_tests && ./run.sh`, `pio run -e esp32-c3-dev && pio run -e esp32dev-dev`, `clang-format --dry-run --Werror <files>` |
| Dashboard files (`esp32_firmware/web/`) | `python3 scripts/embed_web.py` then `python3 scripts/embed_web.py --check` |
| Router / setup | `python3 router/tests/test_monitor.py`, `python3 router/tests/test_pisophone_setup.py`, `python3 tools/build_piso_setup.py --check` |
| Website | `node scripts/tests/test_provisioning.js`, `python3 scripts/tests/test_site_page.py` |
| Always | `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py` (no document may link to a file that is gone) |

## Project rules
Follow `CLAUDE.md` sections 4 and 6 for generated files, version bumps, protocol compatibility, secrets and money/time checks.
UI guidance is in `docs/UI_DESIGN.md`.

## Reporting problems
Use the issue templates. For anything security related, follow `SECURITY.md` instead of opening an issue.
