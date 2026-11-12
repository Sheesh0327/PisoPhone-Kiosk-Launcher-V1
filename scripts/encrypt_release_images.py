#!/usr/bin/env python3
"""Check, then encrypt, the release images for ONE ESP32-C3 unit. Run it on your own computer; it never touches a board.

  encrypt_release_images.py --bundle DIR --key flash_encryption_key.bin --out enc/ [--env esp32-c3-production-encrypted]

DIR is the unzipped PisoPhone-firmware-release-<version>.zip. The script stops (exit 1, nothing written) if ANY check fails.
Every check exists because the failure it catches leaves a board that does not boot, or one that boots and silently saves nothing:

  1. every file matches the SHA256 in manifest.json (a corrupted download is not encrypted and flashed);
  2. bootloader.bin and firmware.bin are ESP32-C3 images (chip id 5), and the bootloader's flash mode is DIO (the build sets
     it; the bytes that get encrypted must be the final bytes, because nothing can patch an encrypted header afterwards);
  3. the partition table parses, its MD5 is right, ota_0 is where the guide says and the app fits, boot_app0 sits inside otadata;
  4. nvs and spiffs are NOT flagged encrypted (an encrypted nvs makes every save fail), everything else is;
  5. the key is 32 bytes (XTS-AES-128 on the C3), not blank, not all 0xFF, and not inside a git work tree;
  6. every output file decrypts back to exactly the input (round trip) before it is declared good.

It prints the esptool command to use. Read docs/PROVISIONING_SOLD_UNIT.md, section 4 first.
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

ENV_DEFAULT = "esp32-c3-production-encrypted"
CHIP_ID_C3 = 0x0005
FLASH_MODE_DIO = 0x02
PARTITION_TABLE_OFFSET = 0x8000
TYPE_APP, TYPE_DATA = 0x00, 0x01
SUB_OTA0, SUB_OTADATA, SUB_NVS, SUB_SPIFFS = 0x10, 0x00, 0x02, 0x82
FLASH_SIZE = 4 * 1024 * 1024
ENC_ALIGN = 16
KEY_LEN = 32


class Refused(Exception):
    pass


def read(path):
    with open(path, "rb") as f:
        return f.read()


def check_image_header(label, data):
    if len(data) < 24 or data[0] != 0xE9:
        raise Refused(f"{label}: not an ESP image (first byte is not 0xE9)")
    chip = struct.unpack_from("<H", data, 12)[0]
    if chip != CHIP_ID_C3:
        raise Refused(f"{label}: chip id {chip:#06x}, expected {CHIP_ID_C3:#06x} (ESP32-C3). Wrong chip's build: do not encrypt it.")


def parse_table(data):
    entries, md5_ok, i = [], None, 0
    while i + 32 <= len(data):
        e = data[i:i + 32]
        if e[:2] == b"\xeb\xeb":
            md5_ok = hashlib.md5(data[:i]).digest() == e[16:32]
            break
        if e[:2] != b"\xaa\x50":
            break
        typ, sub, off, size = struct.unpack("<BBII", e[2:12])
        flags = struct.unpack("<I", e[28:32])[0]
        entries.append({"name": e[12:28].split(b"\0")[0].decode("ascii", "replace"), "type": typ, "subtype": sub,
                        "offset": off, "size": size, "encrypted": bool(flags & 1)})
        i += 32
    if not entries:
        raise Refused("partitions.bin: no partition entries")
    if md5_ok is False:
        raise Refused("partitions.bin: the MD5 entry does not match the table (damaged file)")
    return entries


def check_table(table, app_size, boot_app0_size, boot_app0_offset):
    def one(typ, sub):
        for p in table:
            if p["type"] == typ and p["subtype"] == sub:
                return p
        raise Refused(f"partition table has no entry of type {typ:#x} subtype {sub:#x}")

    ota0, otadata = one(TYPE_APP, SUB_OTA0), one(TYPE_DATA, SUB_OTADATA)
    one(TYPE_DATA, SUB_NVS)
    spans = sorted((p["offset"], p["offset"] + p["size"], p["name"]) for p in table)
    for (_, a1, an), (b0, _, bn) in zip(spans, spans[1:]):
        if a1 > b0:
            raise Refused(f"partitions {an} and {bn} overlap")
    if spans[-1][1] > FLASH_SIZE:
        raise Refused("the partition table runs past the 4 MB flash")
    if not (otadata["offset"] <= boot_app0_offset and boot_app0_offset + boot_app0_size <= otadata["offset"] + otadata["size"]):
        raise Refused("boot_app0.bin does not fit inside the otadata partition")
    if app_size > ota0["size"]:
        raise Refused(f"firmware.bin ({app_size} bytes) does not fit ota_0 ({ota0['size']} bytes)")
    if ota0["offset"] % 0x10000:
        raise Refused("ota_0 is not 64 KB aligned")
    for p in table:
        plain_only = p["type"] == TYPE_DATA and p["subtype"] in (SUB_NVS, SUB_SPIFFS)
        if plain_only and p["encrypted"]:
            raise Refused(f"partition {p['name']} is flagged encrypted; nvs and spiffs must not be (every save would fail)")
        if not plain_only and not p["encrypted"]:
            raise Refused(f"partition {p['name']} is not flagged encrypted")
    return ota0, otadata


def check_key(path):
    key = read(path)
    if len(key) != KEY_LEN:
        raise Refused(f"{path}: the key is {len(key)} bytes, the ESP32-C3 (XTS-AES-128) needs exactly {KEY_LEN}")
    if key == b"\x00" * KEY_LEN or key == b"\xff" * KEY_LEN or len(set(key)) < 8:
        raise Refused(f"{path}: this does not look like a random key")
    folder = os.path.dirname(os.path.abspath(path))
    probe = os.path.abspath(folder)
    while True:
        if os.path.exists(os.path.join(probe, ".git")):
            raise Refused(f"{path} is inside a git work tree ({probe}): keep unit keys outside any repository")
        parent = os.path.dirname(probe)
        if parent == probe:
            break
        probe = parent
    return key


def espsecure_cmd():
    exe = shutil.which("espsecure") or shutil.which("espsecure.py")
    if not exe:
        raise Refused("espsecure not found: pip install esptool")
    return exe


def run_espsecure(exe, op, args):
    """esptool 5 spells commands and options with dashes, esptool 4 with underscores; try the new one, then the old one."""
    last = ""
    for cmd, xts in ((op.replace("_", "-"), "--aes-xts"), (op, "--aes_xts")):
        r = subprocess.run([exe, cmd, xts] + args, capture_output=True, text=True)
        if r.returncode == 0:
            return
        last = (r.stdout or "") + (r.stderr or "")
    raise Refused(f"espsecure {op} failed: {last.strip()[-400:]}")


def pad16(data):
    return data + b"\xff" * (-len(data) % ENC_ALIGN)


def encrypt_one(exe, keyfile, name, data, address, out_dir):
    if address % ENC_ALIGN:
        raise Refused(f"{name}: address {address:#x} is not a multiple of {ENC_ALIGN}")
    plain = pad16(data)
    with tempfile.TemporaryDirectory() as tmp:
        src, enc, back = (os.path.join(tmp, n) for n in ("plain", "enc", "back"))
        with open(src, "wb") as f:
            f.write(plain)
        run_espsecure(exe, "encrypt_flash_data", ["--keyfile", keyfile, "--address", hex(address), "--output", enc, src])
        ciphertext = read(enc)
        if len(ciphertext) != len(plain) or ciphertext == plain:
            raise Refused(f"{name}: the encrypted file looks wrong (size {len(ciphertext)} vs {len(plain)}, or unchanged)")
        run_espsecure(exe, "decrypt_flash_data", ["--keyfile", keyfile, "--address", hex(address), "--output", back, enc])
        if read(back) != plain:
            raise Refused(f"{name}: decrypting the encrypted file did not give back the original. Stop; do not flash.")
    out = os.path.join(out_dir, name.replace("/", "_"))
    with open(out, "wb") as f:
        f.write(ciphertext)
    return out


def prepare(bundle, key_path, out_dir, env=ENV_DEFAULT):
    manifest = json.loads(read(os.path.join(bundle, "manifest.json")))
    build = next((b for b in manifest["builds"] if b["env"] == env), None)
    if build is None:
        raise Refused(f"manifest.json has no build named {env}")
    if build["esptool_chip"] != "esp32c3":
        raise Refused(f"{env} is not an ESP32-C3 build; only the C3 is covered")
    files = {}
    for f in build["files"]:
        data = read(os.path.join(bundle, f["file"]))
        if hashlib.sha256(data).hexdigest() != f["sha256"] or len(data) != f["size"]:
            raise Refused(f"{f['file']} does not match manifest.json (corrupted or edited)")
        files[os.path.basename(f["file"])] = (data, f["offset"])
    for need in ("bootloader.bin", "partitions.bin", "boot_app0.bin", "firmware.bin"):
        if need not in files:
            raise Refused(f"manifest.json lists no {need}")
    boot, boot_off = files["bootloader.bin"]
    app, app_off = files["firmware.bin"]
    parts, parts_off = files["partitions.bin"]
    b0, b0_off = files["boot_app0.bin"]
    check_image_header("bootloader.bin", boot)
    check_image_header("firmware.bin", app)
    if boot_off != 0:
        raise Refused("the ESP32-C3 bootloader goes to 0x0")
    if parts_off != PARTITION_TABLE_OFFSET:
        raise Refused("the partition table goes to 0x8000")
    if boot[2] != FLASH_MODE_DIO:
        raise Refused(f"bootloader.bin flash mode byte is {boot[2]:#04x}, expected DIO (0x02): the build must set board_build.flash_mode = dio")
    table = parse_table(parts)
    ota0, _ = check_table(table, len(app), len(b0), b0_off)
    if app_off != ota0["offset"]:
        raise Refused(f"firmware.bin goes to {app_off:#x} in the manifest but ota_0 is at {ota0['offset']:#x}")
    check_key(key_path)
    exe = espsecure_cmd()
    os.makedirs(out_dir, exist_ok=True)
    plan = []
    for name in ("bootloader.bin", "partitions.bin", "boot_app0.bin", "firmware.bin"):
        data, off = files[name]
        plan.append((off, encrypt_one(exe, key_path, name, data, off, out_dir)))
    plan.sort()
    return plan


def flash_command(plan):
    # esptool must not touch the (now encrypted) bootloader header, and must not restart the chip when it is done.
    parts = " ".join(f"{off:#x} {path}" for off, path in plan)
    return ("esptool --chip esp32c3 -p <PORT> --after no-reset write-flash --flash-mode keep --flash-freq keep --flash-size keep "
            + parts)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--env", default=ENV_DEFAULT)
    a = ap.parse_args()
    try:
        plan = prepare(a.bundle, a.key, a.out, a.env)
    except (Refused, OSError, KeyError, ValueError) as e:
        sys.exit(f"REFUSED: {e}\nNothing is safe to flash from this run.")
    print("All checks passed and every file round-trips. Encrypted files:")
    for off, path in plan:
        print(f"  {off:#08x}  {path}")
    print("\nFlash with (esptool 4.x uses write_flash and underscores in the option names):\n  " + flash_command(plan))


if __name__ == "__main__":
    main()
