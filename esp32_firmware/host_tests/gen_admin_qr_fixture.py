#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair and signed login challenges for admin_qr_test.cpp.
Uses the real scripts/make_admin_qr.py so the script and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import make_admin_qr as qr
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec


def arr(b):
    return ", ".join(f"0x{x:02x}" for x in b)


key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
NONCE = "00112233445566778899aabbccddeeff"
BOX = "AA:BB:CC:DD:EE:01"
with open(sys.argv[1], "w") as f:
    f.write(f"static const unsigned char FX_PUB[] = {{{arr(der)}}};\n")
    f.write(f'static const char* FX_NONCE = "{NONCE}";\n')
    f.write(f'static const char* FX_BOX = "{BOX}";\n')
    f.write(f'static const char* FX_SIG_GOOD = "{qr.sign(key, NONCE, BOX)}";\n')
    f.write(f'static const char* FX_SIG_OTHER_KEY = "{qr.sign(other, NONCE, BOX)}";\n')
    f.write(f'static const char* FX_SIG_OTHER_BOX = "{qr.sign(key, NONCE, "AA:BB:CC:DD:EE:02")}";\n')
    f.write(f'static const char* FX_SIG_OTHER_NONCE = "{qr.sign(key, "ffeeddccbbaa99887766554433221100", BOX)}";\n')
