#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair and signed licenses for license_crypto_test.cpp.
Uses the real scripts/generate_license.py so the script and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import generate_license as gl
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

def arr(b): return ", ".join(f"0x{x:02x}" for x in b)
key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
mac = "AA:BB:CC:DD:EE:01"
with open(sys.argv[1], "w") as f:
    f.write(f"static const unsigned char LX_PUB[] = {{{arr(der)}}};\n")
    f.write(f'static const char* LX_MAC = "{mac}";\n')
    f.write(f'static const char* LX_GOOD3 = "{gl.make_token(key, mac, 3)}";\n')
    f.write(f'static const char* LX_GOOD6 = "{gl.make_token(key, mac, 6)}";\n')
    f.write(f'static const char* LX_OTHER_BOX = "{gl.make_token(key, "AA:BB:CC:DD:EE:02", 3)}";\n')
    f.write(f'static const char* LX_FORGED = "{gl.make_token(other, mac, 3)}";\n')
