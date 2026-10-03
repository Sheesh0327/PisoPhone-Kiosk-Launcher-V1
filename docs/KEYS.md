# Keys: owner key, licenses, signed firmware

## Owner key (one key signs licenses and firmware)
Run on **your own computer**, never in CI or a cloud session (`pip install cryptography` first):
- `python3 scripts/make_owner_keys.py`: saves `~/pisophone_license_key.pem` (refuses to overwrite it or to write inside the repo).
- `python3 scripts/make_owner_keys.py --github-secret`: saves nothing; prints the key once as one base64 line for the repository secret
  `OWNER_SIGNING_KEY_B64`. GitHub secrets cannot be read back, so also keep that line in a password manager. Only use the secret in
  workflows that run on your own branch (never `pull_request` from forks), write it to a temp file, sign, delete the file.

Back the key up in two offline places: lose it and no box can take a new license or update; leak it and anyone can forge both.
It writes only the **public** key into `esp32_firmware/include/LicensePubKey.h`: commit that, then rebuild and flash every box.
Until a public key is built in, boxes still accept the old deprecated license keys and unsigned firmware.

## Licenses
`python3 scripts/generate_license.py issue --private KEY.pem --code <box request code> --slots N` prints a `PISOLIC1...` token to paste into
the box dashboard. Only your private key can make one.

## Signed firmware updates
A box with a public key built in only flashes images you signed.
1. Bump `PISO_FW_VERSION` in `esp32_firmware/include/FirmwareVersion.h`, build each chip with PlatformIO.
2. `python3 scripts/sign_firmware.py --private KEY.pem --chip esp32c3 --image <path>/firmware.bin` writes `firmware.bin.manifest.json`.
3. Cloud update: copy the image and manifest to `website/update/firmware-<chip>.bin` (+ `.manifest.json`); `update-firmware-json.yml` (or
   `scripts/update_firmware_json.py`) publishes size and signature in `firmware.json`. Manual update: choose both files on the box's update page.
The box checks the signature, chip and that the version is newer (a downgrade needs `allow_downgrade=1` and the super-admin password), hashes the
upload against the signed hash and size, accepts one upload per manifest within 15 minutes, and confirms the new image after a minute of normal running.

For units you sell, see `docs/PROVISIONING_SOLD_UNIT.md`.
