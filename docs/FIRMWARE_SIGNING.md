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
