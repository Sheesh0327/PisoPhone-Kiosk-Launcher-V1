#!/usr/bin/env python3
import json
import os
import hashlib
from datetime import datetime

FIRMWARE_BIN_PATH = "website/update/firmware.bin"
FIRMWARE_JSON_PATH = "website/update/firmware.json"

def get_file_sha256(filepath):
    if not os.path.exists(filepath):
        return ""
    sha256_hash = hashlib.sha256()
    with open(filepath, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

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
    data["version"] = f"3.0.{new_build}"
    data["releaseDate"] = release_date
    
    # Calculate SHA256 if binary exists
    sha256 = get_file_sha256(FIRMWARE_BIN_PATH)
    if sha256:
        data["sha256"] = sha256

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
