# Keys: owner key, signed firmware, per-box secrets

## Owner key (one key signs firmware and router updates)
Run on **your own computer**, never in CI or a cloud session (`pip install cryptography` first):
- `python3 scripts/make_owner_keys.py`: saves `~/pisophone_license_key.pem` (refuses to overwrite it or to write inside the repo).
- `python3 scripts/make_owner_keys.py --github-secret`: saves nothing; prints the key once as one base64 line for the repository secret
  `OWNER_SIGNING_KEY_B64`. GitHub secrets cannot be read back, so also keep that line in a password manager. Only use the secret in
  workflows that run on your own branch (never `pull_request` from forks), write it to a temp file, sign, delete the file.
  `firmware-images.yml` and `publish-router.yml` do exactly that, on pushes to `main` only; they are what make the one-tap updates work.
  Trade-off: anyone who can run workflows on `main` can sign an update, so protect `main` (required reviews, no outside collaborators with
  write access). For stricter control skip the secret and sign by hand with a key kept offline (the "By hand" steps).

Back the key up in two offline places: lose it and no box can take a new update; leak it and anyone can forge one.
It writes only the **public** key into `esp32_firmware/include/LicensePubKey.h`: commit that, then rebuild and flash every box.
Until a public key is built in, boxes accept unsigned firmware.

The same key signs **router updates** (`scripts/sign_router.py`, docs/RELEASE.md); its public half is also written to
`tools/pisoportal/owner_key.b64`, which the router program has built in. Commit both files.

## Signed firmware updates
A box with a public key built in only flashes images you signed.
**Automatic (normal way):** raise `PISO_FW_VERSION` in `esp32_firmware/include/FirmwareVersion.h` and merge to `main`. The workflow
`firmware-images.yml` builds both chips, signs the images with the repository secret `OWNER_SIGNING_KEY_B64`, and commits them with
`firmware.json` to `website/update/` (`scripts/publish_firmware_ota.py`). Then, on the box's dashboard, **Update > Install update**.
Without the secret the images are published unsigned, which boxes accept only while no owner key is built into their firmware; once
the key is installed the workflow refuses to publish unsigned images. Edit `changelog` in `website/update/firmware.json` for the text
the dashboard shows. The images are the dev builds (no flash encryption), the same ones the website's USB flasher writes.

**By hand:**
1. Bump `PISO_FW_VERSION`, build each chip with PlatformIO.
2. `python3 scripts/sign_firmware.py --private KEY.pem --chip esp32c3 --image <path>/firmware.bin` writes `firmware.bin.manifest.json`.
3. Copy the image and manifest to `website/update/firmware-<chip>.bin` (+ `.manifest.json`); `update-firmware-json.yml` (or
   `scripts/update_firmware_json.py`) publishes size and signature in `firmware.json`. Manual update: choose both files on the box's update page.
The box checks the signature, chip and that the version is newer (a downgrade needs `allow_downgrade=1` and the super-admin password), hashes the
upload against the signed hash and size, accepts one upload per manifest within 15 minutes, and confirms the new image after a minute of normal running.

## Per-box secrets
Each box makes its own random secret on first start; the dashboard's **Set up a phone** link hands it to the phone. The old key
`PISOPHONE_HMAC_MASTER_KEY` was public, so anyone could forge credits.
A box upgraded from older firmware that already has Wi-Fi saved starts in **legacy mode** (orange banner): it keeps the old key so nothing breaks.
To migrate a site: update the app on every phone; flash the box; make sure each phone has its admin PIN (the box admin password); click
**Switch to this box's own key** (irreversible; a factory reset makes a fresh key); re-provision each phone. Do it when the shop is quiet.
Once every box is migrated, delete `LEGACY_CRYPTO_SECRET` and `DEFAULT_SHARED_SECRET` and empty the allow-list in `scripts/check_security_rules.sh`.

For units you sell, see `docs/PROVISIONING_SOLD_UNIT.md`.
