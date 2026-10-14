#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair, a fake image and signed manifests for fw_manifest_test.cpp.
Uses the real scripts/sign_firmware.py so the script and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import sign_firmware as sf
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

def arr(b): return ", ".join(f"0x{x:02x}" for x in b)
key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
image = bytes([0xE9]) + bytes((i * 31 + 7) % 256 for i in range(5000))
m = sf.make_manifest(key, "esp32c3", "3.2.0", image)
with open(sys.argv[1], "w") as f:
    f.write(f"static const unsigned char FW_PUB[] = {{{arr(der)}}};\n")
    f.write(f"static const unsigned char FW_IMAGE[] = {{{arr(image)}}};\n")
    f.write(f'static const char* FW_VERSION = "{m["version"]}";\n')
    f.write(f'static const char* FW_SHA = "{m["sha256"]}";\n')
    f.write(f'static const unsigned FW_SIZE = {m["size"]};\n')
    f.write(f'static const char* FW_SIG = "{m["sig"]}";\n')
    f.write(f'static const char* FW_SIG_OTHER_KEY = "{sf.make_manifest(other, "esp32c3", "3.2.0", image)["sig"]}";\n')
