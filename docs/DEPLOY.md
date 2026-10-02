# Deploying and releasing

## First run (own shop, one box, one or two phones)
1. **Build.** Push; CI builds the APK and compiles the firmware. Locally: `cd esp32_firmware && sh host_tests/run.sh && pio run -e esp32-c3-dev`
   (the dev environment has no flash encryption and can be re-flashed freely).
2. **Test order** (serial monitor at 115200 baud):
   1. The box boots and prints its setup-AP and admin passwords (there are no factory passwords).
   2. Open the dashboard, change the admin password, connect Wi-Fi.
   3. Provision a phone with the dashboard's **Install & Provision** link, then reboot the phone: the box must still be reachable.
   4. Insert coins: time is added once per coin. Cut the box's power mid-session: no coin lost or doubled.
   5. Drop the Wi-Fi between a coin and its acknowledgement: the box retries and the phone credits it once.
   6. Router (`opennds/INSTRUCTIONS.md`), only after the above.
   7. Factory reset from the dashboard: license slots and lifetime revenue survive; Wi-Fi and admin password reset.
3. If payments stop after a phone reboot, collect `adb logcat -s KioskSecurity`. The phone's box secret and PIN are
   Keystore-wrapped with a plain fallback; if the Keystore key is lost the phone reads them as unset: re-provision it.

The full checklist and issue log are in `docs/REAL_WORLD_TESTING.md`.

Keys are not needed for this test (see `docs/KEYS.md`); boxes then still accept the old license keys and OTA is off, so flash by USB.

## CI
`quality.yml` runs on every push: ktlint, clang-format, firmware host tests, a real PlatformIO build of all four firmware
environments, security-rule and vendored-file checks, and the OpenNDS/router tests. `build-apk.yml` runs the Android unit
tests, builds and signs the release APK and publishes it to the branch's channel (`main` = production, other branches =
`-dev`). Dependabot opens update PRs (majors and Kotlin-toolchain minors are ignored on purpose).

Repository secrets for the APK build: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (the build stops
if one is missing, because an APK signed with another key cannot update installed phones), and optionally `RELEASES_TOKEN`.

## Where APKs and firmware are published
The repository is private, so phones cannot download from its GitHub Releases. APKs go to a separate **public** releases
repository (default `Sheesh0327/PisoPhone-Releases`, override with the variable `RELEASES_REPO`):
1. Create that public repository (with a README) and a fine-grained token limited to it, permission *Contents: read and write*.
   Save it here as the secret `RELEASES_TOKEN`.
2. Each build creates a release there (`app-stable-<run>` for `main`, `app-dev-<run>` pre-releases otherwise; the newest 20 dev
   builds are kept) and writes `website/update/app.json` with the APK's `url`, `sha256` and `size`. Phones refuse an APK
   whose checksum, package name or signing certificate do not match.
3. Without `RELEASES_TOKEN` the APK is committed to `website/update` instead (with a warning). Once every phone runs a build
   that understands `url`, set the variable `KEEP_PAGES_APK` to `false` to stop committing APKs.
4. Optional, owner-run: once APKs are out of git, `git filter-repo --invert-paths --path-glob '*.apk'` (on a fresh mirror clone,
   then force-push every branch) shrinks the repository. It rewrites history; everyone re-clones.

`versionCode` is the workflow run number (always increasing across branches); `versionName` is `1.0.<run>` (+`-dev`).
Firmware images stay on the website (`website/update/firmware-<chip>.bin` + signed manifest): the box's update page downloads
them in the browser, which cannot fetch GitHub release files cross-origin.
