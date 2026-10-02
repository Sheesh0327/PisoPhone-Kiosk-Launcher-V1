#!/usr/bin/env python3
"""Read-only checks for hardened (sold) boxes. Burns nothing. See docs/PROVISIONING_SOLD_UNIT.md.

  sold_unit_check.py fuses --port /dev/ttyUSB0 [--expect-locked]   show the security eFuses (needs espefuse.py)
  sold_unit_check.py flash dump.bin [--find TEXT]                  is this flash dump ciphertext?
"""
import argparse
import math
import subprocess
import sys

# eFuse names, as printed by `espefuse.py summary` for the ESP32-C3.
LOCK_FUSES = ["SECURE_BOOT_EN", "SPI_BOOT_CRYPT_CNT", "DIS_USB_JTAG", "DIS_PAD_JTAG", "ENABLE_SECURITY_DOWNLOAD"]
IMAGE_MAGIC = 0xE9


def parse_summary(text):
    """Turn `espefuse.py summary` lines like 'SECURE_BOOT_EN (BLOCK0) ... = False R/W (0b0)' into {name: raw value}."""
    out = {}
    for line in text.splitlines():
        if "=" not in line or "(" not in line:
            continue
        name = line.split()[0]
        value = line.split("=", 1)[1].strip().split()[0]
        out[name] = value
    return out


def is_set(value):
    return value not in ("False", "0", "0b0", "0b000", "0x0", "")


def fuse_report(values, expect_locked):
    problems = []
    for name in LOCK_FUSES:
        if name not in values:
            problems.append(f"{name}: not reported (different chip or esptool version?)")
            continue
        state = is_set(values[name])
        print(f"{name:28} {'SET' if state else 'clear'}")
        if expect_locked and not state:
            problems.append(f"{name} should be set on a hardened unit")
        if not expect_locked and state:
            problems.append(f"{name} is already set: this board has been hardened or touched before")
    return problems


def entropy(data):
    if not data:
        return 0.0
    counts = [0] * 256
    for b in data:
        counts[b] += 1
    return -sum(c / len(data) * math.log2(c / len(data)) for c in counts if c)


def flash_verdict(data, find=None):
    """Plain app image starts with 0xE9 and is mostly low-entropy structure; ciphertext looks random."""
    if find and find.encode() in data:
        return "PLAINTEXT", f"the text {find!r} appears in the dump"
    if data[:1] and data[0] == IMAGE_MAGIC:
        return "PLAINTEXT", "starts with the ESP image header 0xE9"
    h = entropy(data)
    if h < 7.0:
        return "PLAINTEXT", f"low entropy ({h:.2f} bits/byte), looks like data, not ciphertext"
    return "CIPHERTEXT", f"entropy {h:.2f} bits/byte and no image header"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fuses")
    f.add_argument("--port", required=True)
    f.add_argument("--expect-locked", action="store_true")
    d = sub.add_parser("flash")
    d.add_argument("dump")
    d.add_argument("--find", help="text that must NOT appear in a protected dump (a secret, a license)")
    a = p.parse_args()
    if a.cmd == "fuses":
        r = subprocess.run(["espefuse.py", "-p", a.port, "summary"], capture_output=True, text=True)
        if r.returncode:
            sys.exit(r.stderr or "espefuse.py failed")
        problems = fuse_report(parse_summary(r.stdout), a.expect_locked)
        for x in problems:
            print("PROBLEM:", x)
        sys.exit(1 if problems else 0)
    data = open(a.dump, "rb").read()
    verdict, why = flash_verdict(data, a.find)
    print(f"{verdict}: {why}")
    sys.exit(0 if verdict == "CIPHERTEXT" else 1)


if __name__ == "__main__":
    main()
