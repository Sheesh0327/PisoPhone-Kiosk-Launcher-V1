#!/usr/bin/env python3
"""
Signs a firmware image so boxes will accept it (owner only; needs `pip install cryptography`).

You build firmware.bin with PlatformIO, then sign it with the same offline key that signs licenses
(see scripts/generate_license.py keygen). The box refuses any image that does not match a signed manifest.

  python3 scripts/sign_firmware.py --private ~/pisophone_license_key.pem \
      --chip esp32c3 --image .pio/build/esp32-c3-dev/firmware.bin
  python3 scripts/sign_firmware.py --private KEY.pem --chip esp32 --image firmware.bin --version 3.2.0

It writes <image>.manifest.json next to the image:
  {"chip", "version", "sha256", "size", "sig"}
Use that file with the dashboard's manual upload, or copy the image and manifest into website/update/ as
firmware-<chip>.bin and firmware-<chip>.bin.manifest.json (scripts/update_firmware_json.py publishes the
signature in firmware.json).

The version defaults to PISO_FW_VERSION in esp32_firmware/include/FirmwareVersion.h.
Signed text: pisophone-fw-v1|<chip>|<version>|<sha256 hex>|<size>
"""

import argparse
import base64
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import generate_license as gl  # noqa: E402  (shared key loading)

VERSION_HEADER = os.path.join(HERE, "..", "esp32_firmware", "include", "FirmwareVersion.h")
CHIPS = ("esp32c3", "esp32")
ESP_IMAGE_MAGIC = 0xE9


def header_version():
    try:
        with open(VERSION_HEADER, encoding="utf-8") as f:
            m = re.search(r'#define\s+PISO_FW_VERSION\s+"([^"]+)"', f.read())
        return m.group(1) if m else None
    except OSError:
        return None


def canonical(chip, version, sha256_hex, size):
    return f"pisophone-fw-v1|{chip}|{version}|{sha256_hex}|{size}"


def make_manifest(private_key, chip, version, image_bytes):
    hashes, _, ec = gl._crypto()
    if chip not in CHIPS:
        raise ValueError(f"chip must be one of {CHIPS}")
    if not re.fullmatch(r"\d{1,5}\.\d{1,5}\.\d{1,5}", version):
        raise ValueError("version must look like 3.1.0")
    if len(image_bytes) < 1024 or image_bytes[0] != ESP_IMAGE_MAGIC:
        raise ValueError("this does not look like an ESP32 app image (first byte must be 0xE9)")
    sha = hashlib.sha256(image_bytes).hexdigest()
    sig = private_key.sign(canonical(chip, version, sha, len(image_bytes)).encode(), ec.ECDSA(hashes.SHA256()))
    return {"chip": chip, "version": version, "sha256": sha, "size": len(image_bytes),
            "sig": base64.b64encode(sig).decode()}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="private key (PEM) made by generate_license.py keygen")
    p.add_argument("--chip", required=True, choices=CHIPS)
    p.add_argument("--image", required=True, help="the firmware.bin built by PlatformIO for that chip")
    p.add_argument("--version", help="defaults to PISO_FW_VERSION in FirmwareVersion.h")
    p.add_argument("--out", help="manifest path (default: <image>.manifest.json)")
    args = p.parse_args()

    version = args.version or header_version()
    if not version:
        sys.exit("Error: no --version given and none found in FirmwareVersion.h")
    try:
        with open(args.image, "rb") as f:
            image = f.read()
        manifest = make_manifest(gl.load_private(args.private), args.chip, version, image)
    except (ValueError, OSError) as e:
        sys.exit(f"Error: {e}")
    out = args.out or args.image + ".manifest.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"Signed {args.image} ({manifest['size']} bytes) as {args.chip} v{version}")
    print(f"sha256 {manifest['sha256']}")
    print(f"Manifest written to {out}")


if __name__ == "__main__":
    main()
