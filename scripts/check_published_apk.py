#!/usr/bin/env python3
"""
Checks that the APK the website publishes is the one update/app.json describes. Phones set up by QR code download
update/app-release.apk and Android installs it only if the signing certificate's SHA-256 equals app.json's
"signatureChecksum": a mismatch (for example after a merge kept the APK of one build and the app.json of another) means
every QR setup fails on the phone with a generic error. Also checks the file's size and sha256, which the in-app updater uses.

  python3 scripts/check_published_apk.py [website/update]        exit 0 = consistent (or nothing published yet)

Standard library only: the certificate is read from the APK's v2/v3 signing block.
"""

import base64
import hashlib
import json
import os
import struct
import sys

V2, V3 = 0x7109871A, 0xF05368C0


def signing_block_pairs(data: bytes) -> dict:
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a zip/APK file")
    cd_offset = struct.unpack("<I", data[eocd + 16:eocd + 20])[0]
    if data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
        raise ValueError("the APK has no v2/v3 signing block")
    size = struct.unpack("<Q", data[cd_offset - 24:cd_offset - 16])[0]
    pairs = data[cd_offset - size - 8 + 8:cd_offset - 24]
    found, pos = {}, 0
    while pos < len(pairs):
        length = struct.unpack("<Q", pairs[pos:pos + 8])[0]
        found[struct.unpack("<I", pairs[pos + 8:pos + 12])[0]] = pairs[pos + 12:pos + 8 + length]
        pos += 8 + length
    return found


def first_certificate(blob: bytes) -> bytes:
    """The first signer's first certificate (DER) from a v2/v3 signer sequence."""
    total = struct.unpack("<I", blob[:4])[0]
    signer_len = struct.unpack("<I", blob[4:8])[0]
    signer = blob[8:8 + signer_len]
    assert total >= signer_len
    signed_len = struct.unpack("<I", signer[:4])[0]
    signed = signer[4:4 + signed_len]
    digests_len = struct.unpack("<I", signed[:4])[0]
    rest = signed[4 + digests_len:]
    certs_len = struct.unpack("<I", rest[:4])[0]
    certs = rest[4:4 + certs_len]
    cert_len = struct.unpack("<I", certs[:4])[0]
    return certs[4:4 + cert_len]


def signature_checksum(apk: bytes) -> str:
    pairs = signing_block_pairs(apk)
    blob = pairs.get(V2) or pairs.get(V3)
    if blob is None:
        raise ValueError("the APK has neither a v2 nor a v3 signature")
    return base64.urlsafe_b64encode(hashlib.sha256(first_certificate(blob)).digest()).decode().rstrip("=")


def check(directory: str) -> list:
    problems = []
    meta_path = os.path.join(directory, "app.json")
    apk_path = os.path.join(directory, "app-release.apk")
    if not os.path.exists(meta_path) and not os.path.exists(apk_path):
        return problems   # nothing published yet
    if not (os.path.exists(meta_path) and os.path.exists(apk_path)):
        return ["app.json and app-release.apk must be published together"]
    meta = json.load(open(meta_path))
    apk = open(apk_path, "rb").read()
    if meta.get("size") != len(apk):
        problems.append(f"app.json says {meta.get('size')} bytes, the APK has {len(apk)}")
    if meta.get("sha256") != hashlib.sha256(apk).hexdigest():
        problems.append("app.json's sha256 is not the APK's")
    expected = meta.get("signatureChecksum")
    if not expected:
        problems.append("app.json has no signatureChecksum: QR setup is unavailable")
    else:
        try:
            actual = signature_checksum(apk)
            if actual != expected:
                problems.append(f"app.json's signatureChecksum ({expected}) is not the APK's signing certificate ({actual}): QR setup would fail")
        except Exception as e:   # noqa: BLE001 - say what is wrong with the file, whatever it is
            problems.append(f"could not read the APK's signing certificate: {e}")
    return problems


if __name__ == "__main__":
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "website", "update")
    issues = check(folder)
    for i in issues:
        print("FAIL:", i)
    if issues:
        sys.exit(1)
    print("the published APK matches app.json (size, sha256, signing certificate)")
