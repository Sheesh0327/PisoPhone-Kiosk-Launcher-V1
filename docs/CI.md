# CI on CircleCI

All CI runs on CircleCI (`.circleci/`). The repository is private, and GitHub Actions' free minutes for private repositories
ran out. GitHub only hosts the code.

## How it is put together
- `.circleci/config.yml` is a setup workflow: one small job (`what-changed`) lists the files the push changed and continues
  with `.circleci/main.yml`, passing one flag per part (`.circleci/what_changed.py`). Each workflow in `main.yml` runs only
  when its flag is true: a portal change does not build the firmware, and so on. Without a list of changed files (a new
  branch) everything runs; CI's own commits (author `ci@pisophone.invalid`) run nothing.
- Checks: security rules, the website (helpers, stylesheet, the page in a browser), ktlint, firmware format and host tests,
  the firmware for all four environments, the router portal and setup (rustfmt, clippy, unit, end-to-end and browser tests,
  the installer, the computer-side setup, the Telegram monitor, self-updates, shellcheck).
- Builds that are committed back to the branch: the signed APK (`website/update`), the router program and the setup file
  (beta), the coin box images for the web flasher (`website/flash`, main and beta), the test-log issue numbers, and
  `firmware.json` (main).
- The APK's `versionCode` is `400 + the pipeline number` (`APP_VERSION_BASE` in `main.yml`), always above the last GitHub
  Actions build (336).

## One-time setup (the owner, in a browser)
1. **Sign in to https://circleci.com with GitHub** and allow CircleCI to see your repositories.
2. **Projects > Set Up Project** next to `PisoPhone-Kiosk-Launcher-V1`. Choose "Fastest: use the .circleci/config.yml in my
   repo" and the branch `beta`.
3. **Project Settings > Advanced**: turn on **Enable dynamic config using setup workflows** (the setup workflow needs it),
   and keep **Auto-cancel redundant workflows** on (a newer push to a branch cancels the unfinished run).
4. **A GitHub token for CI's commits**: GitHub > Settings > Developer settings > Fine-grained tokens > Generate new token.
   Repository access: only `PisoPhone-Kiosk-Launcher-V1`. Permissions: *Contents: Read and write*, *Issues: Read and write*.
   Copy it.
5. **The secrets.** Easiest and safest: on your own computer, in the repository folder, run
   `python3 scripts/setup_ci_secrets.py` (needs a Java JDK for keytool; `--dry-run` checks without uploading). You type
   everything hidden, and it uploads straight to CircleCI's API: nothing passes through the clipboard, a file or the screen. It refuses a keystore that
   is not the one the published app is signed with, wrong passwords, and GitHub tokens that cannot open the repository.
   It asks for a CircleCI personal API token (User Settings > Personal API Tokens) for that run: revoke it afterwards.
   **Fresh start** (a new signing key; phones that have the app must be factory reset, or have it removed on the setup
   page's *Remove from a phone* tab, before they take the new builds): `python3 scripts/setup_ci_secrets.py --new-keystore`.
   It shows the new password once: keep it and the keystore file in your password manager and a second, offline place.
   Set up no phone until the first CircleCI APK build is published (`update/app.json` shows a versionCode of 401 or more):
   before that, the site still serves the APK signed with the old key.
   Or by hand, in **Project Settings > Environment Variables**, add:

   | Name | Value |
   |---|---|
   | `GITHUB_PUSH_TOKEN` | the token from step 4 |
   | `KEYSTORE_BASE64` | the same value as the GitHub secret of that name (the signing keystore, base64) |
   | `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | the same values as the GitHub secrets |
   | `RELEASES_TOKEN` | optional, as before (docs/DEPLOY.md) |
   | `RELEASES_REPO`, `KEEP_PAGES_APK` | optional, as the GitHub variables were |

   GitHub secrets cannot be read back: take the values from where you keep them (the keystore file:
   `base64 -w0 release.jks`). **The keystore must be the same one**, or the new APKs cannot update the installed phones.
6. Push to `beta` (or use **Trigger Pipeline**). The first pipeline runs everything (no previous commit to compare with).

## Credits
The free plan has 30,000 credits a month. A `small` Docker job costs 5 credits a minute, `medium` 10, `large` 20 (the Android
build, which needs the memory). A typical push runs only the parts it touched; the full set (the first run, or a change to
`.circleci/`) takes roughly 25 to 35 minutes of jobs, most of it in parallel. The usage is under Organization Settings > Plan.

## Running the checks without CI
Every job's commands can be run locally; see the `run:` steps in `.circleci/main.yml`. The quickest set:
```
sh scripts/check_security_rules.sh && python3 scripts/check_vendored.py
python3 router/tests/test_setup.py && python3 router/tests/test_install.py && python3 router/tests/test_pisophone_setup.py
node scripts/test_provisioning.js && node scripts/test_flasher.js
python3 .circleci/test_what_changed.py
```
