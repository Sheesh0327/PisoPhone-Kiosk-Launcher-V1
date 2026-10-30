#!/usr/bin/env python3
"""scripts/make_brand_pack.py, the owner's tool that signs a shop's logo into a brand pack (feature 049). It runs in a scratch folder with a
throwaway key, so nothing in the repository is touched. Needs `pip install cryptography`.
The router's own check of the same file is tested in tools/pisoportal/tests/test_brand_pack.py (tool, then router, end to end).
Run: python3 scripts/tests/test_make_brand_pack.py"""
import base64
import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import zlib

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import make_brand_pack as mbp

TOOL = os.path.join(ROOT, "scripts", "make_brand_pack.py")
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(w, h, total=0):
    """A real PNG (the pixels are one colour), padded with a text chunk to at least `total` bytes."""
    raw = b"".join(b"\x00" + b"\x20\x60\xa0" * w for _ in range(h))
    body = chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw))
    end = chunk(b"IEND", b"")
    pad = b""
    if total > 8 + len(body) + len(end) + 12:
        pad = chunk(b"tEXt", b"x" * (total - 8 - len(body) - len(end) - 12))
    return b"\x89PNG\r\n\x1a\n" + body + pad + end


def jpeg(w, h):
    return bytes([0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0x4A, 0x46, 0xFF, 0xC0, 0, 11, 8]) + struct.pack(">HH", h, w) + bytes([1, 1, 0x11, 0, 0xFF, 0xD9])


tmp = tempfile.mkdtemp()
key = ec.generate_private_key(ec.SECP256R1())
pem = os.path.join(tmp, "owner.pem")
with open(pem, "wb") as f:
    f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
pub_der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
router_key = os.path.join(tmp, "router_owner_key.b64")
with open(router_key, "w") as f:
    f.write(base64.b64encode(pub_der).decode() + "\n")


def write(name, data):
    p = os.path.join(tmp, name)
    with open(p, "wb") as f:
        f.write(data)
    return p


def run(*args, key_file=router_key):
    r = subprocess.run([sys.executable, TOOL, "--private", pem, "--router-key", key_file, *args], capture_output=True, text=True, cwd=tmp, check=False)
    return r.returncode, r.stdout + r.stderr


def lines_of(path):
    with open(path, encoding="ascii", newline="") as f:
        return f.read().split("\n")


# ---- a good pack: the six lines the router reads, signed over the checksums ------------------------------------------------------------
logo, pic = png(64, 64), jpeg(300, 200)
out = os.path.join(tmp, "nena.brand")
rc, text = run("--logo", write("logo.png", logo), "--picture", write("hero.jpg", pic), "--out", out)
check(rc == 0 and os.path.exists(out), "a logo and a picture make a pack: " + text)
L = lines_of(out)
check(len(L) == 7 and L[6] == "" and L[0] == "pisophone-brand-v1", "six lines and a final line break")
check(L[1] == f"logo {len(logo)} {hashlib.sha256(logo).hexdigest()}" and L[2] == f"picture {len(pic)} {hashlib.sha256(pic).hexdigest()}", "the sizes and checksums")
check(base64.b64decode(L[4]) == logo and base64.b64decode(L[5]) == pic, "the pictures follow, in base64")
canon = f"pisophone-brand-v1|{hashlib.sha256(logo).hexdigest()}|{len(logo)}|{hashlib.sha256(pic).hexdigest()}|{len(pic)}".encode()
try:
    key.public_key().verify(base64.b64decode(L[3][len("sig "):]), canon, ec.ECDSA(hashes.SHA256()))
    check(True, "")
except Exception as e:  # noqa: BLE001
    check(False, f"the signature does not verify over the signed text: {e}")
check("Traceback" not in text, "no crash")

# ---- a logo only, and the limits that are allowed exactly -------------------------------------------------------------------------------
out2 = os.path.join(tmp, "only.brand")
rc, text = run("--logo", write("big.png", png(512, 512, 65536)), "--out", out2)
check(rc == 0 and len(lines_of(out2)[4]) > 0 and lines_of(out2)[2] == "picture 0 -" and lines_of(out2)[5] == "", "512 by 512 pixels and 64 KB exactly are allowed; no picture: " + text)
check(len(png(512, 512, 65536)) == 65536, "(the test image really is 64 KB)")

# ---- what it refuses, with a reason, and writes nothing ---------------------------------------------------------------------------------
svg = b"<svg xmlns='http://www.w3.org/2000/svg'><circle r='5'/></svg>"
cases = [
    ("an SVG", ["--logo", write("l.svg", svg)], "PNG or JPEG"),
    ("a GIF", ["--logo", write("l.gif", b"GIF89a" + b"\0" * 30)], "PNG or JPEG"),
    ("513 pixels", ["--logo", write("w.png", png(513, 10))], "512"),
    ("a logo over 64 KB", ["--logo", write("o.png", png(100, 100, 65537))], "64 KB"),
    ("a PNG cut short", ["--logo", write("c.png", png(20, 20)[:-9])], "PNG"),
    ("a missing logo file", ["--logo", os.path.join(tmp, "nope.png")], "nope.png"),
    ("a bad picture", ["--logo", write("ok.png", png(20, 20)), "--picture", write("p.svg", svg)], "picture"),
]
for name, args, word in cases:
    target = os.path.join(tmp, "refused.brand")
    rc, text = run(*args, "--out", target)
    check(rc != 0 and not os.path.exists(target), f"{name} is refused and nothing is written: {text}")
    check(word in text, f"{name}: the reason says '{word}': {text}")

# ---- the key: a key that is not the router's, a key kept in the repository, a pack that would be overwritten --------------------------------
other = ec.generate_private_key(ec.SECP256R1())
other_der = other.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
wrong = write("wrong.b64", base64.b64encode(other_der) + b"\n")
rc, text = run("--logo", write("k.png", png(20, 20)), "--out", os.path.join(tmp, "k.brand"), key_file=wrong)
check(rc != 0 and "does not match" in text and not os.path.exists(os.path.join(tmp, "k.brand")), "a private key that is not the router program's key is refused (routers would refuse its packs): " + text)
rc, text = run("--logo", write("k2.png", png(20, 20)), "--out", os.path.join(tmp, "k2.brand"), "--no-key-check")
check(rc == 0, "the check can be switched off on purpose: " + text)
rc, text = run("--logo", write("k3.png", png(20, 20)), "--out", out)
check(rc != 0 and "already exists" in text, "an existing pack is not overwritten by accident: " + text)
rc, text = run("--logo", write("k3.png", png(20, 20)), "--out", out, "--force")
check(rc == 0, "unless --force: " + text)
inside = os.path.join(ROOT, "scripts", "tests", "_in_repo_key.pem")
try:
    with open(pem, "rb") as src, open(inside, "wb") as f:
        f.write(src.read())
    r = subprocess.run([sys.executable, TOOL, "--private", inside, "--router-key", router_key, "--logo", write("k4.png", png(20, 20)), "--out", os.path.join(tmp, "k4.brand")],
                       capture_output=True, text=True, cwd=tmp, check=False)
    check(r.returncode != 0 and "repository" in r.stdout + r.stderr and not os.path.exists(os.path.join(tmp, "k4.brand")), "a private key inside the repository is refused: " + r.stdout + r.stderr)
finally:
    if os.path.exists(inside):
        os.remove(inside)

# ---- the same rules as the router, as a library ---------------------------------------------------------------------------------------
check(mbp.check_image(png(1, 1))[:3] == ("png", 1, 1) and mbp.check_image(jpeg(5, 6))[:3] == ("jpeg", 5, 6), "check_image tells PNG from JPEG and reads the size")
for bad in (b"", b"\x89PNG\r\n\x1a\n", png(1, 1) + b"x", jpeg(1, 1)[:-2], bytes([0xFF, 0xD8, 0xFF, 0xD9]), b"<svg/>"):
    try:
        mbp.check_image(bad)
        check(False, f"check_image accepted {bad[:12]!r}")
    except mbp.PackError:
        check(True, "")

print(f"make_brand_pack: {checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
