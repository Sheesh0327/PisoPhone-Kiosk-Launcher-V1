#!/usr/bin/env python3
import json
import os
import hashlib
import re
from datetime import datetime

FIRMWARE_JSON_PATH = "website/update/firmware.json"
# One image per chip family; an image built for one chip is rejected by the other.
FIRMWARE_BIN_PATHS = {
    "esp32c3": "website/update/firmware-esp32c3.bin",
    "esp32": "website/update/firmware-esp32.bin",
}
ESP_IMAGE_MAGIC = 0xE9
FIRMWARE_VERSION_HEADER = "esp32_firmware/include/FirmwareVersion.h"


def read_firmware_version():
    """The version compiled into the firmware, so firmware.json and the device always agree."""
    try:
        with open(FIRMWARE_VERSION_HEADER, "r", encoding="utf-8") as f:
            match = re.search(r'#define\s+PISO_FW_VERSION\s+"([^"]+)"', f.read())
        return match.group(1) if match else None
    except OSError:
        return None

def get_file_sha256(filepath):
    if not os.path.exists(filepath):
        return ""
    sha256_hash = hashlib.sha256()
    with open(filepath, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def attach_signature(entry, image_path, version):
    """Copies size and signature from <image>.manifest.json (made by scripts/sign_firmware.py) into the entry,
    but only when the manifest describes exactly this image and version; otherwise the entry carries no
    signature and boxes with a signing key will refuse it."""
    entry.pop("sig", None)
    entry.pop("size", None)
    manifest_path = image_path + ".manifest.json"
    if not os.path.exists(manifest_path):
        print(f"Warning: {manifest_path} not found; {image_path} is published without a signature.")
        return
    with open(manifest_path, "r", encoding="utf-8") as f:
        m = json.load(f)
    if m.get("sha256") != entry["sha256"] or m.get("version") != version:
        print(f"Warning: {manifest_path} does not match the image/version {version}; not publishing its signature.")
        return
    entry["size"] = m["size"]
    entry["sig"] = m["sig"]


def main():
    if not os.path.exists(FIRMWARE_JSON_PATH):
        print(f"Error: {FIRMWARE_JSON_PATH} does not exist.")
        return

    with open(FIRMWARE_JSON_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Calculate build number
    current_build = int(data.get("build", 300))
    new_build = current_build + 1

    # Format release date (UTC YYYY-MM-DD)
    release_date = datetime.utcnow().strftime("%Y-%m-%d")

    # Update version string
    data["build"] = new_build
    data["version"] = read_firmware_version() or f"3.0.{new_build}"
    data["releaseDate"] = release_date
    
    # Record each chip's SHA256. A file that is not a valid ESP image (wrong first byte, e.g. one
    # mangled by git text conversion) is never published: its entry is cleared instead.
    chips = data.setdefault("chips", {})
    for chip, path in FIRMWARE_BIN_PATHS.items():
        entry = chips.setdefault(chip, {"url": f"https://pisophone.pages.dev/update/{os.path.basename(path)}"})
        if not os.path.exists(path):
            entry["sha256"] = ""
            continue
        with open(path, "rb") as f:
            first = f.read(1)
        if first != bytes([ESP_IMAGE_MAGIC]):
            print(f"Error: {path} does not start with 0x{ESP_IMAGE_MAGIC:02X}; refusing to publish it.")
            entry["sha256"] = ""
            continue
        entry["sha256"] = get_file_sha256(path)
        attach_signature(entry, path, data["version"])
    data.pop("url", None)
    data.pop("sha256", None)

    # Update changelog if provided via env
    commit_msg = os.environ.get("COMMIT_MSG", "").strip()
    if commit_msg and not commit_msg.startswith("chore:"):
        data["changelog"] = commit_msg

    with open(FIRMWARE_JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

    print(f"Successfully updated {FIRMWARE_JSON_PATH}: build={new_build}, version={data['version']}, releaseDate={release_date}")

if __name__ == "__main__":
    main()
