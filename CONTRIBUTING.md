# Contributing

Read `README.md` for what the parts are, `docs/ARCHITECTURE.md` for how they fit and `docs/README.md` for the other documents. `CLAUDE.md`
says what the project is for, where it stands and the rules for working on it (also for Claude Code sessions). The repository is
proprietary (`LICENSE`): changes are made by or for the owner.

## Development setup
Install what the part you change needs. The versions are the ones CI uses (`.github/workflows/quality.yml`, `build-apk.yml`).

| Part | Tools |
|---|---|
| Android app | JDK 17 and the Android SDK (compileSdk 36). The Gradle wrapper (`gradlew`, Gradle 9.3.1) fetches Gradle. ktlint 1.5.0 and detekt 1.23.8 are downloaded by CI; the install lines are in `quality.yml`. |
| Coin box firmware | PlatformIO (CI pins `platformio==6.2.0`; the platform is pinned in `esp32_firmware/envs/*.ini`). For the host tests: `g++`, `libmbedtls-dev`, Python 3 with `cryptography`. For style: `clang-format` (CI uses `clang-format==23.1.2` from pip). |
| Router program | Rust with rustfmt and clippy (`rustup component add rustfmt clippy`). Edition 2021; no other crates than `sha2`, `hmac` and `p256`. |
| Router add-on, setup program | Python 3 (the setup program uses only the standard library), `shellcheck`, `busybox` for the router tests, Playwright with Chromium for the browser tests. |
| Website, scripts | Node.js for `scripts/tests/test_provisioning.js`; Python 3 with `playwright`, `qrcode`, `pillow` and `cryptography` for the rest. |

## Branches
- One feature, one branch, made from `main`: `feat/NNN-name` (or `fix/NNN-name`). A feature starts from an approved spec (`specs/README.md`).
- The owner's rule (2026-10-24, confirmed 2026-10-29): a feature branch that Code quality passes may be merged straight into `main`.
  Do not open a pull request, merge to `main` or release unless the owner asks (`CLAUDE.md` section 5).
- **A change to the router (`tools/pisoportal/src/`, `setup/`, `router/`, `setup/RELEASE`) goes through `beta` first.** CI builds the
  router programs only on `beta`; a release signed from a `main` that skipped it carries old programs. Bring `main` into `beta`,
  raise `setup/RELEASE`, let CI build, then merge `beta` into `main` (`docs/RELEASE.md`).
- Code quality runs on pushes and pull requests to `main` and `beta`, not on pushes to `feat/*`. Run the checks below yourself first.
- `main` is the production channel. Merging to `main` publishes the phone app, the coin-box firmware and the router update, so nothing
  goes there that Code quality has not passed.

## Before you open a pull request
Run the checks for every area you touched:

| Area | Commands |
|---|---|
| Android app | `./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug` (and `bash scripts/ci_emulator_tests.sh` with an emulator or phone connected), `./ktlint --relative "app/src/**/*.kt" "*.kts" "app/*.kts"`, `java -jar detekt-cli-1.23.8-all.jar --config detekt.yml --build-upon-default-config --baseline detekt-baseline.xml --input app/src/main/java` (new findings fail; coverage minimums: `app/coverage-minimums.txt`) |
| ESP32 firmware | `cd esp32_firmware/host_tests && ./run.sh`, `pio run -e esp32-c3-dev && pio run -e esp32dev-dev` (from `esp32_firmware/`), `clang-format --dry-run --Werror <files>` |
| Box <-> phone protocol | `python3 protocol/gen_vectors.py && git diff --exit-code protocol` |
| Dashboard files (`esp32_firmware/web/`) | `python3 scripts/embed_web.py` then `python3 scripts/embed_web.py --check` |
| Router program (`tools/pisoportal/`) | in that folder: `cargo fmt --check`, `cargo clippy --locked --release --all-targets -- -D warnings`, `cargo build --locked --release`, `cargo test --locked --release`, `python3 tests/test_flow.py` (the Python tests run the release build; CI runs the other `tests/test_*.py` too) |
| Router add-on and setup | `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_monitor.py`, `test_setup_web.py`, `test_setup_tools.py` (same folder); for the setup program also `test_setup_phone_web.py` and `test_phone_only.py`; `python3 tools/build_piso_setup.py --check` |
| Website | `node scripts/tests/test_provisioning.js`, `python3 scripts/tests/test_site_page.py` |
| Always | `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py` (no document may link to a file that is gone), `python3 scripts/check_vendored.py` |

Say plainly in the pull request or the validation notes what you could not run (for example no Android SDK, no board, no router).

## Code style
- Kotlin: ktlint (rules in `.editorconfig`) and detekt (`detekt.yml`; findings that already existed are in `detekt-baseline.xml`).
- C++: clang-format with `esp32_firmware/.clang-format`. The files it checks are listed in `quality.yml`.
- Rust: `cargo fmt` (`tools/pisoportal/rustfmt.toml`) and `cargo clippy` with warnings as errors.
- Shell: `shellcheck`; router scripts run under BusyBox `ash`, so plain `sh`.
- Python: standard library only in the setup program. CI does not run a Python linter; the agent rules (`constitution.md`, rule 7) ask for
  `ruff check`, `mypy` and `pytest -x` on the Python files you touch.
- Every file: UTF-8, LF line ends, a final newline, no trailing spaces (`.editorconfig`).
- Documents: plain English, short sentences, no emoji. Every command written in a document must exist.

## Commit messages
Say what changed and why, in plain English, for the owner to read. A short first line, then a paragraph if the reason is not obvious.
One commit per task group of a feature (`specs/README.md`). Do not put a key, password or token in a message.

## Rules that are easy to break
- **Generated and published files are never edited by hand**: `esp32_firmware/include/WebAssets.h`, `setup/piso-setup.sh`,
  `website/setup/*`, `website/install.sh`, `website/update/*`, `website/flash/*`, `tools/pisoportal/bin/*`, `app/schemas/*`.
  Change the source and regenerate (`CLAUDE.md`, section 4).
- **The box <-> phone message contract changes on both sides in one change** (`protocol/gen_vectors.py` and
  `esp32_firmware/host_tests/protocol_contract_test.cpp`).
- **Never commit a key, password or token.** Owner keys are made on the owner's own computer (`docs/KEYS.md`).
- **Raise the version of what you ship**: `PISO_FW_VERSION` for firmware, `setup/RELEASE` for the router. Boxes and routers install
  only a newer version. The app version comes from the CI run number.
- Money and time accounting (credit, expiry, deduct, the payment queue) is reviewed carefully; explain how you checked it.

## Reporting problems
Use the issue templates. For anything security related, follow `SECURITY.md` instead of opening an issue.
