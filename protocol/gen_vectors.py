#!/usr/bin/env python3
"""
Writes protocol/fixtures/box_phone_v1.json: known-answer vectors for the box<->phone message format.

Both implementations must reproduce them: the firmware (esp32_firmware/host_tests/protocol_contract_test.cpp)
and the phone app (on the pisophone branches). They are made here with Python's `cryptography` library,
independent of either implementation, and committed, so a change to one side that breaks the other fails a test.

Format (version 1):
  key         = SHA-256(secret as UTF-8)
  encrypt     = hex(iv) + hex(AES-256-CBC(PKCS#7 padded plaintext))     (lower-case hex)
  signature   = hex(HMAC-SHA256(secret as UTF-8, message))              (lower-case hex)
Run again only when the format changes on purpose (and bump the version in the file name).
"""
import hashlib, hmac, json, os
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SECRET_A = "Abcd2345Efgh6789Jkmn"
SECRET_B = "x7Yq-9_zK.m+3=Rt8UvW4nBp"
IV1 = bytes(range(16))
IV2 = bytes((i * 7 + 3) % 256 for i in range(16))


def encrypt(secret, iv, plain):
    key = hashlib.sha256(secret.encode()).digest()
    padder = padding.PKCS7(128).padder()
    data = padder.update(plain.encode()) + padder.finalize()
    enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return iv.hex() + (enc.update(data) + enc.finalize()).hex()


def sign(secret, message):
    return hmac.new(secret.encode(), message.encode(), hashlib.sha256).hexdigest()


cases = [
    ("short", SECRET_A, IV1, "minutes=30&amount=5&tx_id=tx-b1-1-0000abcd&ts=1760000000000"),
    ("block-aligned", SECRET_A, IV2, "0123456789abcdef"),  # 16 bytes: a whole padding block is added
    ("empty-ish", SECRET_B, IV1, "x"),
    ("pin", SECRET_B, IV2, "PIN:hX7kQ2mNp4Rt"),
    ("long", SECRET_A, IV1, "action=set_config&" + "name=PisoPhone%201&" * 8 + "end=1"),
]
messages = [
    ("payload-hmac", SECRET_A, encrypt(SECRET_A, IV1, cases[0][3])),  # signature over the encrypted payload
    ("discovery", SECRET_A, "DISCOVERY:AA:BB:CC:DD:EE:FF:192.168.1.50"),
    ("heartbeat-timestamp", SECRET_B, "dev-1234:1760000000000"),
    ("coin-ack", SECRET_A, "v1:dev-1234:tx-b1-1-0000abcd:5:1760000000000"),
]
out = {
    "version": 1,
    "encrypt": [{"name": n, "secret": s, "iv": iv.hex(), "plaintext": p, "ciphertext": encrypt(s, iv, p)}
                for n, s, iv, p in cases],
    "hmac": [{"name": n, "secret": s, "message": m, "hmac": sign(s, m)} for n, s, m in messages],
}
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "box_phone_v1.json")
with open(path, "w") as f:
    json.dump(out, f, indent=2)
    f.write("\n")
print("wrote", path)
