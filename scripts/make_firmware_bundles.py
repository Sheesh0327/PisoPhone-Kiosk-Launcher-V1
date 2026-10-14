#!/usr/bin/env python3
"""
Packs the coin box firmware into two zip files for the owner:

  PisoPhone-firmware-dev-<version>.zip       the development builds (no flash encryption): one merged image per chip, plus the
                                             separate files with their flash offsets, ready for esptool or the web flasher.
  PisoPhone-firmware-release-<version>.zip   the production builds with the encrypted partition layout (every partition carries
                                             the "encrypted" flag), as PLAIN files plus the exact offsets. NO keys are in it:
                                             the flash-encryption key and the Secure Boot key are made and kept on the owner's
                                             computer (docs/PROVISIONING_SOLD_UNIT.md), and the images are encrypted there.

It runs in CI (.github/workflows/firmware-images.yml, a manual run with "bundle" on) after PlatformIO built the four
environments. The offsets are READ from each build's partitions.bin, never assumed, and the run fails when a layout is wrong:
the boot_app0 file must fit inside the otadata partition (PlatformIO writes it at 0xe000), the app must sit where the
merged image and the README say, and in the release layout every partition must be flagged encrypted.

  python3 scripts/make_firmware_bundles.py --version 3.5.6 --commit <sha> --build-dir esp32_firmware/.pio/build \
      --boot-app0 <path to boot_app0.bin> --merged-dir website/flash --out bundles
"""

import argparse
import hashlib
import json
import os
import struct
import sys
import zipfile

# env, chip (esptool name), chip as the flasher reports it, bootloader offset, "dev" or "release"
ENVS = [
    ("esp32-c3-dev", "esp32c3", "ESP32-C3", 0x0, "dev"),
    ("esp32dev-dev", "esp32", "ESP32", 0x1000, "dev"),
    ("esp32-c3-production-encrypted", "esp32c3", "ESP32-C3", 0x0, "release"),
    ("esp32dev-production-encrypted", "esp32", "ESP32", 0x1000, "release"),
]
PARTITION_TABLE_OFFSET = 0x8000
BOOT_APP0_SIZE = 0x2000
FLASH_SIZE = 4 * 1024 * 1024
# PlatformIO's Arduino build writes boot_app0.bin at 0xe000 whatever the partition table says (the merged images do the same)
PIO_BOOT_APP0_OFFSET = 0xE000
DEV_APP_OFFSET = 0x10000
TYPE_APP, TYPE_DATA = 0, 1
SUB_OTA0, SUB_OTA1 = 0x10, 0x11
SUB_OTADATA, SUB_PHY, SUB_NVS, SUB_SPIFFS = 0x00, 0x01, 0x02, 0x82
EPOCH = (2026, 1, 1, 0, 0, 0)   # a fixed time stamp: the same input gives the same zip


class BundleError(Exception):
    pass


def read(path):
    with open(path, "rb") as f:
        return f.read()


def parse_partition_table(data):
    """The entries of a partitions.bin (the format gen_esp32part.py writes): name, type, subtype, offset, size, encrypted."""
    out = []
    for i in range(0, len(data) - 31, 32):
        e = data[i:i + 32]
        if e[:2] != b"\xaa\x50":
            break
        typ, sub, off, size = struct.unpack("<BBII", e[2:12])
        name = e[12:28].split(b"\0")[0].decode("ascii", "replace")
        flags = struct.unpack("<I", e[28:32])[0]
        out.append({"name": name, "type": typ, "subtype": sub, "offset": off, "size": size, "encrypted": bool(flags & 1)})
    if not out:
        raise BundleError("partitions.bin has no partition table entries")
    return out


def find(table, typ, sub):
    for p in table:
        if p["type"] == typ and p["subtype"] == sub:
            return p
    raise BundleError(f"the partition table has no entry of type {typ} subtype {sub:#x}")


def check_layout(env, kind, table, boot, parts, app_size):
    """Everything the README tells the owner must be true of the build. Raises BundleError otherwise."""
    otadata, ota0 = find(table, TYPE_DATA, SUB_OTADATA), find(table, TYPE_APP, SUB_OTA0) if kind == "release" else None
    if boot[:1] != b"\xe9":
        raise BundleError(f"{env}: bootloader.bin does not start with the image magic 0xE9")
    if parts[:2] != b"\xaa\x50":
        raise BundleError(f"{env}: partitions.bin does not start with the table magic")
    # boot_app0 must sit inside the otadata partition and touch nothing else
    if not (otadata["offset"] <= PIO_BOOT_APP0_OFFSET and PIO_BOOT_APP0_OFFSET + BOOT_APP0_SIZE <= otadata["offset"] + otadata["size"]):
        raise BundleError(
            f"{env}: PlatformIO writes boot_app0.bin at {PIO_BOOT_APP0_OFFSET:#x}..{PIO_BOOT_APP0_OFFSET + BOOT_APP0_SIZE:#x}, "
            f"but otadata is {otadata['offset']:#x}..{otadata['offset'] + otadata['size']:#x}: it would overwrite another partition"
        )
    ends = sorted((p["offset"], p["offset"] + p["size"], p["name"]) for p in table)
    for (a0, a1, an), (b0, b1, bn) in zip(ends, ends[1:]):
        if a1 > b0:
            raise BundleError(f"{env}: partitions {an} and {bn} overlap")
    if ends[-1][1] > FLASH_SIZE:
        raise BundleError(f"{env}: the partition table runs past the 4 MB flash")
    if kind == "dev":
        app0 = find(table, TYPE_APP, SUB_OTA0) if any(p["subtype"] == SUB_OTA0 and p["type"] == TYPE_APP for p in table) else None
        slot = app0 or next((p for p in table if p["type"] == TYPE_APP), None)
        if slot is None or slot["offset"] != DEV_APP_OFFSET:
            raise BundleError(f"{env}: the first app partition is not at {DEV_APP_OFFSET:#x}, where the merged image puts the app")
        app_at, slot_size = DEV_APP_OFFSET, slot["size"]
    else:
        for p in table:
            if not p["encrypted"]:
                raise BundleError(f"{env}: partition {p['name']} is not flagged encrypted")
        find(table, TYPE_APP, SUB_OTA1)
        for sub in (SUB_NVS, SUB_PHY, SUB_SPIFFS):
            find(table, TYPE_DATA, sub)
        app_at, slot_size = ota0["offset"], ota0["size"]
        if app_at % 0x10000:
            raise BundleError(f"{env}: ota_0 at {app_at:#x} is not 64 KB aligned")
    if app_size > slot_size:
        raise BundleError(f"{env}: firmware.bin ({app_size} bytes) does not fit its partition ({slot_size} bytes)")
    return app_at


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def zip_write(z, name, data):
    info = zipfile.ZipInfo(name, EPOCH)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, data)


def fmt_table(table):
    rows = ["name        type  subtype  offset      size        encrypted"]
    for p in table:
        rows.append(f"{p['name']:<11} {p['type']:<5} {p['subtype']:#06x}   {p['offset']:#010x}  {p['size']:#010x}  {'yes' if p['encrypted'] else 'no'}")
    return "\n".join(rows)


DEV_README = """\
PisoPhone coin box firmware {version} - DEVELOPMENT builds (no flash encryption)
commit {commit}

These are the builds a box in your own shop runs. They can be flashed again at any time (USB or the dashboard).
Each chip folder holds ONE merged image (flash it at 0x0 on the ESP32-C3, at 0x1000 on the classic ESP32) and the
separate files with their offsets, taken from the build's own partition table.

{chips}
Flash a merged image (esptool 4.x, pip install esptool; replace COM3 / /dev/ttyUSB0 with your port):
  ESP32-C3 : python -m esptool --chip esp32c3 --port COM3 --baud 460800 write_flash 0x0 esp32-c3-dev/esp32-c3-dev-merged.bin
  ESP32    : python -m esptool --chip esp32   --port COM3 --baud 460800 write_flash 0x1000 esp32dev-dev/esp32dev-dev-merged.bin
This keeps the box's saved data only if you do not erase the flash first. A deliberate full wipe (revenue, secret, Wi-Fi) is
  python -m esptool --chip esp32c3 --port COM3 erase_flash

Check the files against SHA256SUMS before flashing. manifest.json lists the same offsets and sizes.
"firmware.bin" is the application alone (the file the dashboard's "Install update" takes). The updates the router and dashboard
install by themselves are signed with the owner's key and published separately; the app file in this zip is NOT signed.
"""

RELEASE_README = """\
PisoPhone coin box firmware {version} - RELEASE builds, ENCRYPTED PARTITION LAYOUT (for flash encryption)
commit {commit}

READ THIS FIRST. What this zip is and is not:
  * It holds the production builds as PLAIN files with the partition layout where every partition is flagged "encrypted".
  * It holds NO keys. The flash-encryption key and the Secure Boot key are made on YOUR computer, offline, one flash-encryption
    key per unit, and are never put in git, in CI or in a zip (docs/PROVISIONING_SOLD_UNIT.md, section 2).
  * Burning the eFuses cannot be undone. The procedure in docs/PROVISIONING_SOLD_UNIT.md (copied into this zip) has NOT yet been
    run on a board: its gates (GATE 1 the bootloader, GATE 2 the plain boot, GATE 3 the ciphertext check, and the NVS check)
    must pass on a sacrificial board before any unit you sell. Do not burn a unit for sale with these files until they have.
  * The bootloader here is PlatformIO's stock Arduino bootloader. It is built without Secure Boot and without in-place flash
    encryption support (GATE 1 in the guide). The route this zip is meant for is the one in step 7 of the guide: encrypt every
    image on your computer with the unit's key and write the encrypted files, so the chip's ROM decrypts them. Whether that
    boots, accepts a signed update and reverts a bad one is exactly what GATE 1 and the practice log are for.
  * Use the ESP32-C3 files for units you sell. The classic ESP32 supports Secure Boot V2 only from chip revision 3 and uses a
    different encryption method (see Espressif's flash encryption guide for the ESP32); those files are included for completeness
    and are NOT covered by the commands below.

{chips}
Per-unit encryption on your computer (ESP32-C3; ESP-IDF's espsecure, pip install esptool). KEY is that unit's
flash_encryption_key.bin. Every offset below is the flash address of the file:
{encrypt_commands}
Then flash the encrypted files at the same offsets, after the eFuse steps of the guide. Verify with the guide's section 5.

Before anything: check the files against SHA256SUMS. manifest.json lists the offsets and sizes.
"""


def chip_block(env, chip, boot_off, kind, table, app_at, files):
    lines = [f"== {env} ({chip}, flash 4 MB) ==", "files and where they go:"]
    for name, off, size in files:
        lines.append(f"  {name:<16} at {off:#08x}  ({size} bytes)")
    lines.append("partition table of this build:")
    lines.extend("  " + row for row in fmt_table(table).split("\n"))
    return "\n".join(lines) + "\n"


def build(version, commit, build_dir, boot_app0, merged_dir, out_dir):
    boot_app0_data = read(boot_app0)
    if len(boot_app0_data) != BOOT_APP0_SIZE:
        raise BundleError(f"boot_app0.bin is {len(boot_app0_data)} bytes, expected {BOOT_APP0_SIZE}")
    bundles = {"dev": {}, "release": {}}
    manifest = {"dev": {"version": version, "commit": commit, "builds": []}, "release": {"version": version, "commit": commit, "builds": []}}
    blocks = {"dev": [], "release": []}
    cmds = []
    for env, chip, name, boot_off, kind in ENVS:
        d = os.path.join(build_dir, env)
        boot = read(os.path.join(d, "bootloader.bin"))
        parts = read(os.path.join(d, "partitions.bin"))
        app = read(os.path.join(d, "firmware.bin"))
        table = parse_partition_table(parts)
        if app[:1] != b"\xe9":
            raise BundleError(f"{env}: firmware.bin does not start with the image magic 0xE9")
        app_at = check_layout(env, kind, table, boot, parts, len(app))
        otadata = find(table, TYPE_DATA, SUB_OTADATA)
        files = [("bootloader.bin", boot_off, boot), ("partitions.bin", PARTITION_TABLE_OFFSET, parts),
                 ("boot_app0.bin", otadata["offset"], boot_app0_data), ("firmware.bin", app_at, app)]
        files_out = dict(bundles[kind])
        for fname, off, data in files:
            files_out[f"{env}/{fname}"] = data
        entry = {"env": env, "chip": name, "esptool_chip": chip,
                 "files": [{"file": f"{env}/{f}", "offset": o, "size": len(x), "sha256": sha256(x)} for f, o, x in files]}
        if kind == "dev":
            merged = os.path.join(merged_dir, f"{env}.bin") if merged_dir else ""
            if merged and os.path.exists(merged):
                m = read(merged)
                files_out[f"{env}/{env}-merged.bin"] = m
                entry["merged"] = {"file": f"{env}/{env}-merged.bin", "offset": boot_off, "size": len(m), "sha256": sha256(m)}
            else:
                raise BundleError(f"{env}: the merged image {merged!r} is missing")
        bundles[kind] = files_out
        manifest[kind]["builds"].append(entry)
        blocks[kind].append(chip_block(env, name, boot_off, kind, table, app_at, [(f, o, len(x)) for f, o, x in files]))
        if kind == "release" and chip == "esp32c3":
            for f, o, x in files:
                cmds.append(f"  espsecure.py encrypt_flash_data --aes_xts --keyfile KEY --address {o:#x} --output enc/{env}-{f} {env}/{f}")
    os.makedirs(out_dir, exist_ok=True)
    result = []
    for kind in ("dev", "release"):
        files = bundles[kind]
        readme = (DEV_README if kind == "dev" else RELEASE_README).format(
            version=version, commit=commit[:12], chips="\n".join(blocks[kind]), encrypt_commands="\n".join(cmds))
        files["README.txt"] = readme.encode()
        files["manifest.json"] = (json.dumps(manifest[kind], indent=2) + "\n").encode()
        if kind == "release":
            guide = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "PROVISIONING_SOLD_UNIT.md")
            files["PROVISIONING_SOLD_UNIT.md"] = read(guide)
            csv = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "esp32_firmware", "partitions_encrypted.csv")
            files["partitions_encrypted.csv"] = read(csv)
        sums = "".join(f"{sha256(files[n])}  {n}\n" for n in sorted(files) if n != "SHA256SUMS")
        files["SHA256SUMS"] = sums.encode()
        path = os.path.join(out_dir, f"PisoPhone-firmware-{kind}-{version}.zip")
        with zipfile.ZipFile(path, "w") as z:
            for n in sorted(files):
                zip_write(z, n, files[n])
        result.append(path)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--commit", required=True)
    ap.add_argument("--build-dir", required=True, help="PlatformIO's .pio/build (one folder per environment)")
    ap.add_argument("--boot-app0", required=True)
    ap.add_argument("--merged-dir", default="", help="the folder with the merged dev images (<env>.bin)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    try:
        for p in build(a.version, a.commit, a.build_dir, a.boot_app0, a.merged_dir, a.out):
            print(f"{p}: {os.path.getsize(p)} bytes")
    except (BundleError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
