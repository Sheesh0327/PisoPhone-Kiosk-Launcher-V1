#!/usr/bin/env python3
"""
Publishes the box firmware for the dashboard's "Install update": copies freshly built images into website/update/, signs them, and
writes website/update/firmware.json (version, build, sha256, size, signature). Run by CI on main (firmware-images.yml); also by hand.

  python3 scripts/publish_firmware_ota.py --images DIR [--key owner.pem]   copy, sign and announce new images
  python3 scripts/publish_firmware_ota.py --json-only                      only rewrite firmware.json from the files already in
                                                                           website/update/ (after signing by hand, docs/KEYS.md)
  --product full|phone|wifi                                                which coin box product (default full): the PisoPhone-only
                                                                           and PisoWiFi-only images have their own signed feed in
                                                                           website/update/phone/ and website/update/wifi/, so a box
                                                                           never sees another product's image

DIR holds firmware-esp32c3.bin and firmware-esp32.bin: the app-only images PlatformIO builds (.pio/build/<env>/firmware.bin).
For each chip this copies the image to website/update/, signs it when a key is given (website/update/firmware-<chip>.bin.manifest.json),
and then rewrites firmware.json: the version compiled into the firmware, the build number (+1), the date, and each image's sha256, size
and signature (the signature is taken over only when its manifest describes exactly this image and version). An image that does not
start with 0xE9 is never announced.

Nothing is changed when the images are byte for byte what firmware.json already announces for this version.
If the firmware has an owner public key built in (esp32_firmware/include/LicensePubKey.h) a signing key is required, because a box
with a key refuses unsigned images; publishing one would only hand the shops an update that cannot install.
A box installs firmware only if its version is NEWER: raise PISO_FW_VERSION when the firmware changes (this script warns, and
scripts/verify_release.py fails, when it was not).
Run it from the repository root. Needs `pip install cryptography` only when signing.
"""

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

UPDATE_DIR = os.path.join("website", "update")
FIRMWARE_JSON = os.path.join(UPDATE_DIR, "firmware.json")
PUBKEY_HEADER = os.path.join(ROOT, "esp32_firmware", "include", "LicensePubKey.h")
CHIPS = ("esp32c3", "esp32")
ESP_IMAGE_MAGIC = 0xE9
VERSION_HEADER = os.path.join(ROOT, "esp32_firmware", "include", "FirmwareVersion.h")
PAGES_UPDATE_URL = "https://pisophone.pages.dev/update"


PRODUCTS = ("full", "phone", "wifi")


def use_product(product):
    """Points the script at one product's feed: website/update (full) or website/update/<product>."""
    global UPDATE_DIR, FIRMWARE_JSON, PAGES_UPDATE_URL
    if product not in PRODUCTS:
        sys.exit(f"Error: --product is one of {', '.join(PRODUCTS)}")
    suffix = "" if product == "full" else "/" + product
    UPDATE_DIR = os.path.join("website", "update") + suffix.replace("/", os.sep)
    FIRMWARE_JSON = os.path.join(UPDATE_DIR, "firmware.json")
    PAGES_UPDATE_URL = "https://pisophone.pages.dev/update" + suffix


def firmware_has_owner_key():
    with open(PUBKEY_HEADER, encoding="utf-8") as f:
        return not re.search(r"LICENSE_PUBKEY_LEN\s*=\s*0\s*;", f.read())


def read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def read_firmware_version():
    """The version compiled into the firmware, so firmware.json and the box always agree."""
    try:
        with open(VERSION_HEADER, encoding="utf-8") as f:
            match = re.search(r'#define\s+PISO_FW_VERSION\s+"([^"]+)"', f.read())
        return match.group(1) if match else None
    except OSError:
        return None


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(4096), b""):
            h.update(block)
    return h.hexdigest()


def attach_signature(entry, image_path, version):
    """Copies size and signature from <image>.manifest.json (made by scripts/sign_firmware.py) into the entry, but only when the
    manifest describes exactly this image and version; otherwise the entry carries no signature and boxes with a key refuse it."""
    entry.pop("sig", None)
    entry.pop("size", None)
    manifest_path = image_path + ".manifest.json"
    if not os.path.exists(manifest_path):
        print(f"Warning: {manifest_path} not found; {image_path} is published without a signature.")
        return
    m = read_json(manifest_path)
    if m.get("sha256") != entry["sha256"] or m.get("version") != version:
        print(f"Warning: {manifest_path} does not match the image/version {version}; not publishing its signature.")
        return
    entry["size"] = m["size"]
    entry["sig"] = m["sig"]


def write_firmware_json():
    """Rewrites website/update/firmware.json from the images and manifests in website/update/."""
    if not os.path.exists(FIRMWARE_JSON):
        sys.exit(f"Error: {FIRMWARE_JSON} does not exist.")
    data = read_json(FIRMWARE_JSON)
    new_build = int(data.get("build", 300)) + 1
    data["build"] = new_build
    data["version"] = read_firmware_version() or f"3.0.{new_build}"
    data["releaseDate"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    # An image that is not a valid ESP image (wrong first byte, e.g. mangled by git text conversion) is never published.
    chips = data.setdefault("chips", {})
    for chip in CHIPS:
        path = os.path.join(UPDATE_DIR, f"firmware-{chip}.bin")
        entry = chips.setdefault(chip, {"url": f"{PAGES_UPDATE_URL}/{os.path.basename(path)}"})
        if not os.path.exists(path):
            entry["sha256"] = ""
            continue
        with open(path, "rb") as f:
            first = f.read(1)
        if first != bytes([ESP_IMAGE_MAGIC]):
            print(f"Error: {path} does not start with 0x{ESP_IMAGE_MAGIC:02X}; refusing to publish it.")
            entry["sha256"] = ""
            continue
        entry["sha256"] = sha256_of(path)
        attach_signature(entry, path, data["version"])
    data.pop("url", None)
    data.pop("sha256", None)
    with open(FIRMWARE_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print(f"Successfully updated {FIRMWARE_JSON}: build={new_build}, version={data['version']}, releaseDate={data['releaseDate']}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--images", help="folder with firmware-esp32c3.bin and firmware-esp32.bin")
    p.add_argument("--json-only", action="store_true", help="only rewrite firmware.json from the files already in website/update/")
    p.add_argument("--key", help="the owner's private key (PEM); signs the images")
    p.add_argument("--product", default="full", help="full (default), phone (PisoPhone only) or wifi (PisoWiFi only)")
    args = p.parse_args()
    use_product(args.product)

    if args.json_only:
        write_firmware_json()
        return
    if not args.images:
        p.error("--images DIR is required (or --json-only)")

    import sign_firmware  # noqa: E402  (scripts/sign_firmware.py; needs the cryptography package)
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
        message = (f"PISO_FW_VERSION is still {version}, but the firmware changed. Boxes already on {version} will say "
                   f"'up to date' and refuse the new image (not newer). Raise PISO_FW_VERSION in esp32_firmware/include/FirmwareVersion.h.")
        if os.environ.get("GITHUB_REF_NAME") == "main":   # production: publishing it would announce an update that cannot install
            sys.exit(f"Error: {message} Nothing was published.")
        print(f"::warning::{message}")

    private = sign_firmware.owner_key.load_private(args.key) if args.key else None
    if private:   # a key that is not the one built into the firmware signs images no box installs: find out before writing anything
        try:
            for chip in CHIPS:
                sign_firmware.check_against_repo_key(sign_firmware.make_manifest(private, chip, version, images[chip]))
        except ValueError as e:
            sys.exit(f"Error: {e}")
    os.makedirs(UPDATE_DIR, exist_ok=True)
    if not os.path.exists(FIRMWARE_JSON):   # a product's first release: its feed starts empty
        with open(FIRMWARE_JSON, "w", encoding="utf-8") as f:
            json.dump({"build": 300, "chips": {}}, f, indent=2)
            f.write("\n")
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

    write_firmware_json()
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
