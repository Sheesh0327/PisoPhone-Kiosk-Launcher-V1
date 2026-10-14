#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair and box-type tokens for box_license_test.cpp.
Uses the real scripts/sign_box_type.py so the script and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import sign_box_type as sb
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
    f.write(f'static const char* LIC_RENTAL = "{sb.make_token(key, mac, "rental")}";\n')
    f.write(f'static const char* LIC_PURCHASED = "{sb.make_token(key, mac, "purchased")}";\n')
    f.write(f'static const char* LIC_RENTAL_OTHER_BOX = "{sb.make_token(key, "AA:BB:CC:00:11:23", "rental")}";\n')
    f.write(f'static const char* LIC_RENTAL_OTHER_KEY = "{sb.make_token(other, mac, "rental")}";\n')
