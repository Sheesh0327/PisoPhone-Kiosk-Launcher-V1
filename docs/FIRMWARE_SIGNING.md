# Signed firmware updates

A box with a signing key built in only flashes a firmware image that you signed. Anyone else who reaches the
update page, with or without the admin password, cannot install their own image.

## One-time setup
Use the same offline key that signs licenses (`python3 scripts/generate_license.py keygen --private KEY.pem`).
Rebuild and flash the boxes once so they carry the public key (`esp32_firmware/include/LicensePubKey.h`).
Until a key is built in, a box still accepts unsigned images and logs a warning.

## Releasing a build
1. Bump `PISO_FW_VERSION` in `esp32_firmware/include/FirmwareVersion.h` and build with PlatformIO (for each chip).
2. Sign each image:
   `python3 scripts/sign_firmware.py --private KEY.pem --chip esp32c3 --image <path>/firmware.bin`
   This writes `firmware.bin.manifest.json` (chip, version, sha256, size, signature).
3. Cloud update: copy the image to `website/update/firmware-esp32c3.bin` and the manifest to
   `website/update/firmware-esp32c3.bin.manifest.json`, then let `update-firmware-json.yml` run (or run
   `scripts/update_firmware_json.py`). It copies size and signature into `firmware.json`, and only when the
   manifest matches the image and the version.
4. Manual update: on the box's update page choose the `.bin` and its `.manifest.json`.

## What the box checks
- The manifest signature, the chip, and that the version is newer than the running one.
  Installing an older or the same version needs `allow_downgrade=1` and the super-admin password.
- While the image uploads the box hashes it; the hash and size must equal the signed ones or the new image is
  discarded before it becomes the boot image.
- One manifest allows one upload and expires after 15 minutes.
- After a minute of normal running the new image is confirmed (`esp_ota_mark_app_valid_cancel_rollback`). The stock
  Arduino bootloader has no rollback, so this does nothing there; rollback needs a custom bootloader (plan S9).

## Payment ids
Coin transaction ids are now `tx-b<boot>-<n>-<random>`: a boot counter kept in flash, a per-boot counter and 32
random bits (`TxId.h`). They no longer depend on the phone clock, and two coins never share an id, so the phone's
"already credited" check can never swallow a new coin. The phone and the box already keep the payment durably
(box: flash queue retried until acknowledged; phone: one database transaction for receipt plus time).

## Keeping the owner key in a GitHub secret

`python3 scripts/make_owner_keys.py --github-secret` (run on your own computer) prints the private key once as one base64
line for the repository secret `OWNER_SIGNING_KEY_B64`, writes only the public key into the firmware header, and
leaves no key file behind. Things to know:

- GitHub secrets **cannot be read back**. Keep the same line in a password manager too; lose both and no box can take
  a new license or update.
- Any workflow that can read the secret can sign anything. Use it only in workflows that run on your own branch
  (`push` or `workflow_dispatch`), never on `pull_request` from forks, and prefer a protected Environment so the
  secret is released only to `main`. Do not echo it; write it to a temp file, sign, then delete the file.
- In a workflow: `echo "$OWNER_SIGNING_KEY_B64" | base64 -d > "$RUNNER_TEMP/key.pem"`, then
  `python3 scripts/sign_firmware.py --private "$RUNNER_TEMP/key.pem" ...`, then `rm -f "$RUNNER_TEMP/key.pem"`.
