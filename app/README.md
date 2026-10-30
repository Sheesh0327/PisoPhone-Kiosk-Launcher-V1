# app: the PisoPhone kiosk app

The Android app on a rental phone. It locks the phone to a kiosk screen, unlocks it for the minutes a customer paid for at the coin
box, and counts the time down. A payment is credited once, by transaction id, and the session is saved so a reboot loses nothing.
Application id `com.pisophone.kiosk`, Android API 26 and newer. How it talks to the box: `docs/ARCHITECTURE.md`.

## Layout
- `src/main/java/com/pisophone/kiosk/`: the code, one folder per concern.
  - `service/`: the session engine (`KioskEngine`) and the lock, armed and unlocked rules (`SessionRules.kt`).
  - `network/`, `server/`: talking to the coin box, and the phone's own HTTP server for the box's messages (signature, freshness, replay checks).
  - `security/`: signing, the admin PIN, device-owner policy, recovery.
  - `db/`, `repository/`: the Room database, payments and coin events.
  - `overlay/`, `ui/`: the lock screen, the pill and pop-over (Jetpack Compose), the launcher and the QR time card scanner.
  - `provisioning/`, `receiver/`: QR setup of a fresh phone; boot, watchdog and admin broadcast receivers.
  - `audio/`, `system/`, `util/`, `model/`: speech and sounds, screen and battery helpers, small helpers.
- `src/test/`: unit tests that run on a computer. `src/androidTest/`: tests that need an emulator or a phone.
- `schemas/`: Room's snapshot of each database version.
- `dev-signing/`: the public test key that signs branch builds, so they can never be installed over a production phone (`docs/RELEASE.md`).
- `lint-baseline.xml`, `coverage-minimums.txt`, `proguard-rules.pro`: lint findings that already existed, the least line coverage per package, release shrinking rules.

## Build
Needs a JDK (CI uses 17) and the Android SDK (compileSdk 36). The Gradle wrapper at the repository root downloads Gradle.
```
./gradlew assembleDebug
```
The version comes from the CI run number (`BUILD_NUMBER` or `GITHUB_RUN_NUMBER`); a local build is version 1. Release builds are
signed and published by CI with the owner's key (`docs/RELEASE.md`, `docs/KEYS.md`).

## Test
```
./gradlew :app:testDebugUnitTest :app:lintDebug
bash scripts/ci_emulator_tests.sh        # an emulator, or a phone with USB debugging
python3 scripts/check_coverage.py        # after the unit tests and :app:createDebugUnitTestCoverageReport
```
Style: `./ktlint --relative "app/src/**/*.kt" "*.kts" "app/*.kts"` and detekt (`detekt.yml`, `detekt-baseline.xml`). The exact
commands, and how CI installs ktlint and detekt, are in `CONTRIBUTING.md` and `.github/workflows/quality.yml`.

## Generated files
`schemas/*` is written by Room during the Gradle build. Do not edit it by hand. CI fails if a committed snapshot changes (`build-apk.yml`).

## See also
`docs/ARCHITECTURE.md` (payment flow, the phone's session), `docs/REFERENCE.md` (phone slots and license keys), `protocol/README.md`
(the message format shared with the coin box), `docs/design/GUI_SPEC_PISOPHONE_KIDS.md` (the kiosk screens).
