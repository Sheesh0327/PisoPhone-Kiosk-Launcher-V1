#!/usr/bin/env python3
"""Feature 049, group 3: the owner's tool and the router's check, end to end. scripts/make_brand_pack.py makes and signs a pack with a throwaway
owner key; the REAL router program (built with the cargo feature test-owner-key, which lets it trust that throwaway key instead of the owner's;
the router program that ships never has it) verifies it, installs it, and serves it as the guest page's logo. Forged, changed and
foreign-key packs must be refused by the router, and the production key built in must refuse a pack signed by any other key.
Needs `pip install cryptography` and cargo. PISO_TEST_TARGET_DIR keeps the test build between runs."""
import base64
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zlib

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    print("skipped: the cryptography package is not installed")
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
TOOL = os.path.join(ROOT, "scripts", "make_brand_pack.py")
tmp = tempfile.mkdtemp(prefix="brand-pack-test-")
target = os.environ.get("PISO_TEST_TARGET_DIR") or os.path.join(tmp, "target")
built = subprocess.run(["cargo", "build", "--release", "--locked", "--features", "test-owner-key", "--target-dir", target],
                       cwd=os.path.join(ROOT, "tools", "pisoportal"), capture_output=True, text=True, check=False)
if built.returncode != 0:
    print(built.stdout[-800:] + built.stderr[-1500:])
    print("FAIL: the test build of the router program failed")
    sys.exit(1)
BIN = os.path.join(target, "release", "pisoportal")
os.environ["PISOPORTAL"] = BIN  # (the harness runs the portal from here)
from harness import Env, check, finish  # (after the environment is set)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(w, h):
    raw = b"".join(b"\x00" + b"\x20\x60\xa0" * w for _ in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def jpeg(w, h):
    return bytes([0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0x4A, 0x46, 0xFF, 0xC0, 0, 11, 8]) + struct.pack(">HH", h, w) + bytes([1, 1, 0x11, 0, 0xFF, 0xD9])


def new_key(name):
    key = ec.generate_private_key(ec.SECP256R1())
    pem = os.path.join(tmp, name + ".pem")
    with open(pem, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    pub = os.path.join(tmp, name + ".b64")
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    with open(pub, "w") as f:
        f.write(base64.b64encode(der).decode() + "\n")
    return pem, pub


def write(name, data):
    p = os.path.join(tmp, name)
    with open(p, "wb") as f:
        f.write(data)
    return p


def make(pem, pub, logo, picture=None, out="x.brand", extra=()):
    args = [sys.executable, TOOL, "--private", pem, "--router-key", pub, "--logo", write(out + ".logo", logo), "--out", os.path.join(tmp, out), "--force", *extra]
    if picture:
        args += ["--picture", write(out + ".pic", picture)]
    r = subprocess.run(args, capture_output=True, text=True, check=False)
    return r.returncode, r.stdout + r.stderr, os.path.join(tmp, out)


def router(*args, pub=None, data=None):
    env = dict(os.environ, PISOPORTAL_CONF=os.path.join(tmp, "none.conf"))
    if pub:
        env["PISOPORTAL_TEST_OWNER_KEY"] = pub
    if data:
        env["DATA_DIR"] = data
    r = subprocess.run([BIN, *args], capture_output=True, text=True, env=env, check=False)
    return r.returncode, r.stdout.strip()


owner_pem, owner_pub = new_key("owner")
stranger_pem, stranger_pub = new_key("stranger")
logo, pic = png(120, 90), jpeg(400, 150)

# ---- the tool's pack is accepted by the router -------------------------------------------------------------------------------------------
rc, text, pack = make(owner_pem, owner_pub, logo, pic, "nena.brand")
check(rc == 0, "the tool makes a pack: " + text)
rc, out = router("brand-verify", pack, pub=owner_pub)
check(rc == 0 and out.startswith("OK logo") and "120 by 90" in out and "400 by 150" in out, f"the router accepts it: {out}")
rc, text, pack2 = make(owner_pem, owner_pub, logo, None, "only.brand")
rc2, out2 = router("brand-verify", pack2, pub=owner_pub)
check(rc2 == 0 and "no picture" in out2, f"a pack with a logo only: {out2}")

# ---- what the router must refuse ---------------------------------------------------------------------------------------------------------
with open(pack, encoding="ascii") as f:
    good = f.read()
lines = good.split("\n")


def variant(name, text):
    with open(os.path.join(tmp, name), "w", encoding="ascii", newline="") as f:
        f.write(text)
    return os.path.join(tmp, name)


flipped = lines[:]
flipped[4] = flipped[4][:40] + ("B" if flipped[4][40] != "B" else "C") + flipped[4][41:]
for name, text, why in (
    ("flipped.brand", "\n".join(flipped), "a changed picture byte"),
    ("resized.brand", good.replace(f"logo {len(logo)} ", f"logo {len(logo) + 1} ", 1), "a changed size"),
    ("swapped.brand", good.replace(lines[4], base64.b64encode(png(121, 90)).decode(), 1), "another logo under the old signature"),
    ("cut.brand", good[: len(good) // 2], "a pack cut in half"),
):
    rc, out = router("brand-verify", variant(name, text), pub=owner_pub)
    check(rc == 1 and out.startswith("REFUSED"), f"{why} is refused: {out}")
rc, _, forged = make(stranger_pem, owner_pub, logo, None, "forged.brand", extra=("--no-key-check",))
rc, out = router("brand-verify", forged, pub=owner_pub)
check(rc == 1 and "not the owner's" in out, f"a pack signed by another key is refused: {out}")
rc, out = router("brand-verify", pack, pub=stranger_pub)
check(rc == 1 and "not the owner's" in out, f"and a router that holds another owner's key refuses ours: {out}")
rc, out = router("brand-verify", pack)  # no test key: the key built into the program (the owner's real one)
check(rc == 1 and "REFUSED" in out, f"the production key refuses a throwaway key's pack: {out}")

# ---- install: all or nothing, and replacing -----------------------------------------------------------------------------------------------
data = os.path.join(tmp, "data")
rc, out = router("brand-install", forged, pub=owner_pub, data=data)
check(rc == 1 and not os.path.exists(os.path.join(data, "brand")), f"a refused pack installs nothing: {out}")
rc, out = router("brand-install", pack, pub=owner_pub, data=data)
check(rc == 0 and out.startswith("INSTALLED"), f"the pack installs: {out}")
b = os.path.join(data, "brand")
names = sorted(os.listdir(b))
check(names == sorted(["images.txt", f"logo-{hashlib.sha256(logo).hexdigest()[:12]}.png", f"picture-{hashlib.sha256(pic).hexdigest()[:12]}.jpg"]), f"the files: {names}")
rc, out = router("brand-install", pack2, pub=owner_pub, data=data)
check(rc == 0 and not any(n.startswith("picture-") for n in os.listdir(b)), "a newer pack without a picture removes the old picture")

# ---- and the guest's phone gets it: tool, router install, then the portal serves it --------------------------------------------------------
env = Env(offset=155)
try:
    check(env.start(), "the portal starts")
    rc, out = router("brand-install", pack, pub=owner_pub, data=env.data)
    check(rc == 0, f"installed into the portal's data folder: {out}")
    with open(os.path.join(env.data, "brand", "published.txt"), "w") as f:  # (the console publishes it in group 6)
        f.write("name=Aling Nena\n")
    base = f"http://127.0.0.1:{env.PP}"
    j = json.loads(urllib.request.urlopen(base + "/brand.json", timeout=5).read())
    check(j["branded"] is True and j["logo"] is True and j["picture"] is True, f"the portal offers the logo and the picture: {j}")
    r = urllib.request.urlopen(base + "/brand/logo", timeout=5)
    check(r.read() == logo and r.headers["Content-Type"] == "image/png", "the logo a guest's phone fetches is the owner's file, byte for byte")
    r = urllib.request.urlopen(base + "/brand/picture", timeout=5)
    check(r.read() == pic and r.headers["Content-Type"] == "image/jpeg", "and so is the picture")
finally:
    env.stop()
finish()
