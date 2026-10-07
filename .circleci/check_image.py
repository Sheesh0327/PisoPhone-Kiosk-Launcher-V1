#!/usr/bin/env python3
"""Checks a merged coin box image for the web flasher: the bootloader's and the app's image magic and the partition table's
magic at the right places, and that it fits a 4 MB flash.   python3 check_image.py <image> <bootloader offset, hex>"""
import sys

data, base = open(sys.argv[1], "rb").read(), int(sys.argv[2], 16)


def at(addr):
    return data[addr - base:]


assert at(base)[0] == 0xE9, "bootloader image magic missing"
assert at(0x8000)[:2] == b"\xaa\x50", "partition table magic missing at 0x8000"
assert at(0x10000)[0] == 0xE9, "app image magic missing at 0x10000"
assert len(data) < 4 * 1024 * 1024 - base, "image larger than the 4 MB flash"
print(f"{sys.argv[1]}: {len(data)} bytes from 0x{base:x}, structure OK")
