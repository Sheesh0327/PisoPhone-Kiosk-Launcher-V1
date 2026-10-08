#!/usr/bin/env python3
"""
Publishes freshly built ESP32 app images for the dashboard's "Install update" (run by CI on main; also usable by hand).

  python3 scripts/publish_firmware_ota.py --images DIR [--key owner.pem]

DIR holds firmware-esp32c3.bin and firmware-esp32.bin: the app-only images PlatformIO builds (.pio/build/<env>/firmware.bin).
For each chip this copies the image to website/update/, signs it when a key is given (website/update/firmware-<chip>.bin.manifest.json),
and then runs scripts/update_firmware_json.py, which writes the version, sha256 and signature into website/update/firmware.json.

Nothing is changed when the images are byte for byte what firmware.json already announces for this version.
If the firmware has an owner public key built in (esp32_firmware/include/LicensePubKey.h) a signing key is required, because a box
with a key refuses unsigned images; publishing one would only hand the shops an update that cannot install.
Run it from the repository root. Needs `pip install cryptography` only when signing.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

UPDATE_DIR = os.path.join("website", "update")
FIRMWARE_JSON = os.path.join(UPDATE_DIR, "firmware.json")
PUBKEY_HEADER = os.path.join(ROOT, "esp32_firmware", "include", "LicensePubKey.h")
CHIPS = ("esp32c3", "esp32")
ESP_IMAGE_MAGIC = 0xE9


def firmware_has_owner_key():
    with open(PUBKEY_HEADER, encoding="utf-8") as f:
        return not re.search(r"LICENSE_PUBKEY_LEN\s*=\s*0\s*;", f.read())


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--images", required=True, help="folder with firmware-esp32c3.bin and firmware-esp32.bin")
    p.add_argument("--key", help="the owner's private key (PEM); signs the images")
    args = p.parse_args()

    import sign_firmware  # noqa: E402  (scripts/sign_firmware.py)
    version = sign_firmware.header_version()
    if not version:
        sys.exit("Error: no PISO_FW_VERSION in esp32_firmware/include/FirmwareVersion.h")

    images = {}
    for chip in CHIPS:
        path = os.path.join(args.images, f"firmware-{chip}.bin")
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError as e:
            sys.exit(f"Error: {e}")
        if len(data) < 100_000 or data[0] != ESP_IMAGE_MAGIC:
            sys.exit(f"Error: {path} is not an ESP32 app image (the dashboard refuses anything under 100 KB or not starting with 0xE9)")
        images[chip] = data

    published = read_json(FIRMWARE_JSON)
    chips = published.get("chips", {})
    same = published.get("version") == version and all(
        chips.get(c, {}).get("sha256") == hashlib.sha256(images[c]).hexdigest()
        and (not args.key or chips.get(c, {}).get("sig")) for c in CHIPS)
    if same:
        print(f"Firmware {version} is already published exactly as built; nothing to do.")
        return

    if not args.key and firmware_has_owner_key():
        sys.exit("Error: this firmware has an owner public key built in, so boxes refuse unsigned updates. "
                 "Set the repository secret OWNER_SIGNING_KEY_B64 (python3 scripts/make_owner_keys.py --github-secret, docs/KEYS.md).")
    if published.get("version") == version and any(
            chips.get(c, {}).get("sha256") not in ("", None, hashlib.sha256(images[c]).hexdigest()) for c in CHIPS):
        print(f"::warning::PISO_FW_VERSION is still {version}, but the firmware changed. Boxes already on {version} will say "
              f"'up to date' and refuse the new image (not newer). Raise PISO_FW_VERSION in esp32_firmware/include/FirmwareVersion.h.")

    private = sign_firmware.owner_key.load_private(args.key) if args.key else None
    os.makedirs(UPDATE_DIR, exist_ok=True)
    for chip in CHIPS:
        out = os.path.join(UPDATE_DIR, f"firmware-{chip}.bin")
        with open(out, "wb") as f:
            f.write(images[chip])
        manifest_path = out + ".manifest.json"
        if os.path.exists(manifest_path):
            os.remove(manifest_path)   # never leave the signature of an older image next to this one
        if private:
            manifest = sign_firmware.make_manifest(private, chip, version, images[chip])
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump(manifest, f, indent=2)
                f.write("\n")
            print(f"Signed {out} as {chip} v{version}")
        else:
            print(f"Published {out} unsigned (this firmware has no owner key built in yet)")

    subprocess.run([sys.executable, os.path.join(HERE, "update_firmware_json.py")], check=True)
    result = read_json(FIRMWARE_JSON)
    for chip in CHIPS:
        entry = result.get("chips", {}).get(chip, {})
        if not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
            sys.exit(f"Error: firmware.json has no checksum for {chip} after publishing")
        if private and not entry.get("sig"):
            sys.exit(f"Error: firmware.json has no signature for {chip} after publishing")
    print(f"firmware.json now announces {result.get('version')} (build {result.get('build')})")


if __name__ == "__main__":
    main()
