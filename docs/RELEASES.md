# Releases: where APKs and firmware are published

This repository is **private**, so phones and boxes cannot download files from its GitHub Releases. APKs are
published to a separate **public** repository that holds nothing but release files. The source stays private.

## One-time setup (about 10 minutes)
1. Create a public repository, for example `Sheesh0327/PisoPhone-Releases`, with a short README (an empty repository
   cannot hold releases). If you use another name, set the repository variable `RELEASES_REPO` (Settings > Secrets
   and variables > Actions > Variables) to `owner/name`.
2. Create a fine-grained personal access token limited to **that repository only**, permission **Contents: read and
   write**, with an expiry you will remember. Add it to this repository as the Actions secret `RELEASES_TOKEN`.
3. Push to any branch. The `Build and Deploy APK` workflow now:
   - builds and signature-checks the release APK,
   - creates a release in the releases repository (`app-stable-<run>` for `main`, `app-dev-<run>` pre-releases for
     other branches; only the 20 newest dev builds are kept),
   - writes `website/update/app.json` with the APK's `url`, `sha256` and `size`. Phones read this, then download the APK
     from the release and refuse it unless the checksum, package name and signing certificate all match.

Without `RELEASES_TOKEN` the workflow still works the old way (it commits the APK to `website/update`) and prints a
warning, so nothing breaks while you set this up.

## Stop committing APKs
Phones that run a build from before the `url` field existed still fetch `website/update/app-release.apk`, so the
workflow keeps publishing it until you are ready:
1. Check that every phone runs a build made after the `url` change (installed build number is shown in the vault
   diagnostics).
2. Set the repository variable `KEEP_PAGES_APK` to `false`. From the next build the APK is removed from
   `website/update` and no new copies are committed.
3. Optionally shrink the repository history with `docs/HISTORY_REWRITE.md`.

## Firmware
Firmware images stay on the website (`website/update/firmware-<chip>.bin` with their signed manifests, see
`docs/FIRMWARE_SIGNING.md`). The box's update page downloads the image in your browser, and a browser cannot
reliably fetch GitHub release downloads from another site (cross-origin rules), while it can from the website. The
images are small and change rarely. Attach each signed `firmware.bin` and `manifest.json` to a release in the releases
repository as well if you want an archive.

## Version numbers
`versionCode` is the workflow run number, which only goes up across all branches, so a build can never be older than
the one before it. `versionName` is `1.0.<run>` (plus `-dev` off `main`).
