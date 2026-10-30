# Release checklist (beta → main → shops)

Everything the software checks by itself runs in CI on every push (`docs/DEPLOY.md`). This page is what only the owner
can do, in order, before a build goes to shops.

## 1. Owner keys (once; on your own computer, never in CI or a cloud session)
- [ ] `python3 scripts/make_owner_keys.py`, commit the public key it writes into `esp32_firmware/include/LicensePubKey.h`
      **and** `tools/pisoportal/owner_key.b64` (`docs/KEYS.md`). Until then boxes accept **unsigned firmware updates**
      (the production firmware builds in CI show a warning), and routers install **no** update
      from the website at all (they fail closed). Let CI build the router program once more after committing the key (it does
      so on every push), and install that setup file on each router by hand this one time: the key is inside the program.
- [ ] `python3 scripts/make_admin_qr.py` makes your admin QR code and, the first time, `SuperAdminPubKey.h` (commit it, update the
      boxes once; `docs/REFERENCE.md`). Until then no one can open super admin.

## 2. Hardware and real-world tests
- [ ] Pass 1 and 2 of `docs/REAL_WORLD_TESTING.md` on `beta` (box, phones, router), including R3, R5, RS11
      and RS12. Log every failure in its Issue log.

## 2b. Before the merge: check the path (about a minute, no hardware)
- [ ] `git fetch && python3 scripts/verify_release.py` says **0 FAIL**. It checks that the owner key in the box firmware and the
      router program is the same, that every firmware image and the router file in `website/update` match their checksums and
      carry signatures that verify, that the app feed matches the APK, and the rule that most often makes an update "do
      nothing": **a box installs firmware only if `PISO_FW_VERSION` is higher than the one it runs** (and a router only a higher
      `setup/RELEASE`). If firmware code changed since `main` but the version did not, it fails and says so. `firmware-images.yml`
      rebuilds the images on every push under the *same* version number, so `beta` and `main` can both publish different
      binaries called `3.3.3`; boxes ignore both.
- [ ] The merge itself: `git checkout main && git merge beta` conflicts in files CI regenerates (`website/flash/*`,
      `website/update/app.json`, `website/update/app-release.apk`) and usually in `CHANGELOG.md`. Take `main`'s copy of the
      generated files (`git checkout --ours -- website/flash website/update/app.json website/update/app-release.apk`) and keep both
      sides of `CHANGELOG.md`; the next push rebuilds and re-signs them. (Tried on a throwaway merge: the result passes
      `verify_release.py`, the formatting check and the firmware host tests.)

## 2a. Protect the signing keys (one-time, in GitHub Settings)
The release workflows sign with two secrets: the **owner key** (`OWNER_SIGNING_KEY_B64`: firmware and router updates) and the **APK keystore**
(`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: phone updates). Whoever holds them can ship software to every shop, so they
live in one protected GitHub **Environment** called `production-signing` instead of ordinary repository secrets:
- Only a run on `main` can read them; a run on any other branch cannot, even if someone edits the workflow files on that branch.
- The signing jobs wait for **your approval** before they start (GitHub shows "Waiting for review"; one tap per job).
- Building is separate from signing: Gradle, PlatformIO and everything they download run in a job that has no secrets at all.
- Branch (dev) APKs are signed with a **public test key** (`app/dev-signing/dev-test-key.p12`). A dev APK can therefore never be installed over a
  production phone: Android refuses an update signed by a different key.

Steps (GitHub website, repository **Settings**):
1. **Environments > New environment**, name it exactly `production-signing`.
2. In that environment: tick **Required reviewers** and add yourself (leave "Prevent self-review" off if you are the only person). Save.
3. **Deployment branches and tags > Selected branches and tags > Add rule**, enter `main`. Save.
4. **Environment secrets > Add secret**, five times, with the same values you have now: `OWNER_SIGNING_KEY_B64`, `KEYSTORE_BASE64`,
   `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. (GitHub cannot show a secret again: use the copies in your password manager. If you only
   have the keystore file: `base64 -w0 release.jks`.)
5. **Secrets and variables > Actions > Repository secrets**: delete those same five. This is the step that makes it safe; do it only after step 4.
6. Optional but recommended: **Rules > Rulesets** for `main`: require a pull request, require review from Code Owners (`CODEOWNERS` already lists
   you for `.github/`), block force pushes. And **Actions > General**: set "Workflow permissions" to read, and "Require approval for first-time contributors".
7. `RELEASES_TOKEN` (publishes APK files to the public releases repository): make it a fine-grained token limited to that one repository, "Contents:
   read and write" only.
8. After the next push to `main`, open the run in the Actions tab: the **Sign the APK**, **Sign and publish the coin box images** and **Sign and
   publish the router update** jobs say "Waiting for review". Approve each. If a job fails with "Missing secret(s) in the production-signing Environment",
   step 4 is incomplete.

Phones that already run a **dev (branch) build** were signed with the production key; they cannot update to the new dev builds. Uninstall or reset
them once and set them up again. Production phones are not affected.
Honest limits: a GitHub administrator can change these rules, and a stolen token of yours that can approve deployments can approve a run. For the strongest
protection keep the owner key off GitHub entirely and sign by hand on your own computer ("Without the secret, sign on your own computer").

## 3. Publish (merging `beta` into `main` ships all three)
One-time: the owner key `OWNER_SIGNING_KEY_B64` (step 1, `python3 scripts/make_owner_keys.py --github-secret`) in the protected Environment of step 2a,
so CI can sign firmware and router updates once you approve. After that, merging `beta` into `main` publishes everything to https://pisophone.pages.dev/update:
- [ ] **Phone app**: `build-apk.yml` builds and signs the APK and writes `app.json`. On the phone: the app's update screen, **Install update**.
- [ ] **Coin box (ESP32)**: raise `PISO_FW_VERSION` first (a box installs only a newer version). `firmware-images.yml` builds both chips,
      signs them and writes `firmware-<chip>.bin` and `firmware.json`. On the box's dashboard: **Update > Install update**.
      Set `changelog` in `website/update/firmware.json` for the text it shows.
- [ ] **Router**: raise `setup/RELEASE` first (a router installs only a newer release), and let the router-program workflow rebuild
      `setup/piso-setup.sh` on `beta`. `publish-router.yml` signs it and writes `router.json` and `router-setup.sh`. On the router:
      `piso-setup update`.
After the merge has built, check what the world actually downloads (the sandbox of a cloud session cannot reach it): `curl -s https://pisophone.pages.dev/update/firmware.json`
shows the new `version`, `app.json` the new `versionCode`, `router.json` the new release; compare the `sha256` values with
the files in `website/update` on `main`.
Check the three workflows are green on `main`; a red `publish-router` or `firmware-images` run says what is missing (usually the secret or
the owner key in `tools/pisoportal/owner_key.b64`).

## 3a. Router updates over the website (no more copying files to routers)
Routers check `https://pisophone.pages.dev/update/router.json` every hour (each at its own minute). They install a release only
if **your key signed it**, it is **newer** than the one they run, and **their turn** in the staged rollout has come. A forged or
damaged file, a downgrade, or an update that does not start after installing (the router program's self-test, the portal process)
is refused or undone, and you get a Telegram message either way. With customers online an update waits for a quiet moment.
1. Change the router software on `beta`, test it, raise `setup/RELEASE` (for example `1.1.0`). Push; CI builds the router program
   and `setup/piso-setup.sh` (check `tools/pisoportal/bin/BUILD-INFO` shows your commit).
2. Merge `beta` into `main` when your tests pass, `git pull`.
3. `publish-router.yml` signs the file with `OWNER_SIGNING_KEY_B64` and commits `website/update/router.json` and `router-setup.sh` to `main`
   (Cloudflare publishes them), at **0%** of the routers: a new release reaches no installed router (a new router set up with the installer gets
   it at once) until you widen it. Run the workflow by hand (Actions > Publish the router update > Run workflow) with rollout `10`, then again with
   `100`; a push never changes a rollout you widened. Without the secret, sign on **your own computer** instead:
   `python3 scripts/sign_router.py --private ~/pisophone_license_key.pem --rollout 10 --changelog "what changed"` and commit both files.
4. Watch Telegram ("update: release 1.1.0 installed and running"). A router that cannot run the new release goes back by itself
   and tells you ("FAILED and was undone"). When the first routers are fine, sign the **same file again** with `--rollout 100`.
5. To withdraw a bad release: publish a fixed one with a higher version (routers never go to a lower version).
Router commands: `piso-setup update` (install the newest release now, even with customers online; the same as `piso-setup self-update`;
add `check` to only look; or send `/update` in Telegram), `piso-setup auto-update off|on` (off: you are told, nothing installs by itself).
The key is built into the router program, so a stolen Cloudflare account can publish nothing a router will install. A stolen GitHub account with write access
could only publish something if it could also pass the protected Environment of step 2a (your approval); without that step, it could.
`./piso-setup.sh update`, run from a NEW copy of the setup file that you copied to the router, still works for a router that has no internet.
A router still on release 1.0.0 has the old `update` command (it only installs from a file): run `piso-setup self-update` once on it, after that
`piso-setup update` fetches from the website.

## Everything updates itself
- **Router**: hourly (`piso-setup self-update auto`), a release you signed, no customer online, rolled back if it does not run. Staged by the rollout percentage above.
- **Coin box**: the router checks half an hour after that (`piso-setup box-update auto`): it reads `website/update/firmware.json`, and when the box
  runs an older version it downloads the image for the box's chip, checks the size and checksum you published, gives the box your signed
  manifest and then the image (exactly what the dashboard does), and waits for the box to come back on the new version. It waits while a customer is
  online or a phone has time running, tries a version three times at most, and tells you in Telegram. The box still verifies your signature
  itself, and goes back to its old firmware if the new one does not start. There is no staged rollout for the box: publish firmware only when you
  have tried it on your own box (`piso-setup auto-update off` on a router stops both). `piso-setup box-update` installs now; `check` only looks.
- **Phones**: the app checks `app.json` hourly (the first time 5 minutes after it starts) and installs by itself when it is the device owner (a silent
  install; otherwise Android would ask the person holding the phone) and the phone is idle: lock screen showing, no time left, nobody signed in, no
  arena match. The APK is checked against the published checksum and the signing certificate, a build that fails to install three times is left alone
  until a newer one is published, and the app restarts by itself afterwards.
## 4. After `main` serves the new website
Nothing pending: the box's **Set up a phone** links already carry the secret after `#` (never sent to a server), not in the query string.
