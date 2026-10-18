#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair and slot license keys for slot_license_test.cpp.
Uses the real scripts/make_slot_licenses.py so the generator and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import make_slot_licenses as ml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

def arr(b): return ", ".join(f"0x{x:02x}" for x in b)
key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
mac = "AA:BB:CC:00:11:22"
with open(sys.argv[1], "w") as f:
    f.write(f"static const unsigned char LIC_PUB[] = {{{arr(der)}}};\n")
    f.write(f'static const char* LIC_MAC = "{mac}";\n')
    for i, name in enumerate(["A", "B", "C"]):
        f.write(f'static const char* LIC_{name} = "{ml.make_token(key, mac, "0000000%d" % (i + 1))}";\n')
    f.write(f'static const char* LIC_OTHER_BOX = "{ml.make_token(key, "AA:BB:CC:00:11:23", "00000009")}";\n')
    f.write(f'static const char* LIC_OTHER_KEY = "{ml.make_token(other, mac, "00000008")}";\n')
    f.write(f'static const char* LIC_BAD_MESSAGE = "{ml.make_token(key, mac, "00000007").replace("00000007", "00000006")}";\n')
