#!/usr/bin/env python3
"""Writes a C++ header with a throwaway key pair and signed credentials for cred_crypto_test.cpp.
Uses the real scripts/superadmin_credentials.py so the script and the firmware stay in step."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts"))
import superadmin_credentials as sc
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

def arr(b): return ", ".join(f"0x{x:02x}" for x in b)
key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
der = lambda k: k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
good = sc.make_credentials(key, "correct horse battery", 7, 1000)
forged = sc.make_credentials(other, "attacker password!!", 8, 1000)
with open(sys.argv[1], "w") as f:
    f.write(f"static const unsigned char FX_PUB[] = {{{arr(der(key))}}};\n")
    for name, c in (("GOOD", good), ("FORGED", forged)):
        f.write(f'static const unsigned FX_{name}_VER = {c["version"]};\n')
        f.write(f'static const unsigned FX_{name}_ITER = {c["iter"]};\n')
        for k in ("salt", "hash", "sig"):
            f.write(f'static const char* FX_{name}_{k.upper()} = "{c[k]}";\n')
