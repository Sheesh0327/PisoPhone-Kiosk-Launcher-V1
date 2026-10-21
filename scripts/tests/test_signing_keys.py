#!/usr/bin/env python3
"""The signing tools refuse a key that is not the repository's:
  - sign_router.py and sign_firmware.py sign with a key whose public half is not the one the routers / boxes trust: they must exit
    non-zero and write nothing (the bad release would only have shown up in verify_release.py);
  - make_owner_keys.py refuses a key path inside the repository in --github-secret mode too.
Everything runs in a temporary copy of the scripts with throwaway keys. Needs `pip install cryptography`.
Run: python3 scripts/tests/test_signing_keys.py"""
import base64, os, shutil, subprocess, sys, tempfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


tmp = tempfile.mkdtemp()


def new_key(name):
    key = ec.generate_private_key(ec.SECP256R1())
    path = os.path.join(tmp, name + ".pem")
    with open(path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return path, der


good_pem, good_der = new_key("good")
bad_pem, _ = new_key("bad")

repo = os.path.join(tmp, "repo")
os.makedirs(os.path.join(repo, "scripts"))
os.makedirs(os.path.join(repo, "esp32_firmware", "include"))
os.makedirs(os.path.join(repo, "tools", "pisoportal"))
for f in ("sign_router.py", "sign_firmware.py", "owner_key.py", "make_owner_keys.py"):
    shutil.copy(os.path.join(ROOT, "scripts", f), os.path.join(repo, "scripts", f))
with open(os.path.join(repo, "esp32_firmware", "include", "LicensePubKey.h"), "w") as f:
    f.write("static const uint8_t LICENSE_PUBKEY_DER[] = {%s};\n" % ",".join("0x%02x" % b for b in good_der))
with open(os.path.join(repo, "tools", "pisoportal", "owner_key.b64"), "w") as f:
    f.write(base64.b64encode(good_der).decode() + "\n")


def run(*args):
    p = subprocess.run([sys.executable, *args], cwd=repo, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


# --- sign_router.py
setup = os.path.join(tmp, "setup.sh")
with open(setup, "wb") as f:
    f.write(b"#!/bin/sh\nPISO_RELEASE='1.2.3'\n" + b"#" * 2000 + b"\n#@@B64 /usr/bin/pisoportal AAAA\n")
out_good, out_bad = os.path.join(tmp, "out_good"), os.path.join(tmp, "out_bad")
rc, out = run("scripts/sign_router.py", "--private", good_pem, "--setup", setup, "--out-dir", out_good)
check(rc == 0 and os.path.exists(os.path.join(out_good, "router.json")), "sign_router.py signs with the repository's key: " + out[-200:])
rc, out = run("scripts/sign_router.py", "--private", bad_pem, "--setup", setup, "--out-dir", out_bad)
check(rc != 0, "sign_router.py exits non-zero with another key: " + out[-200:])
check(not os.path.exists(os.path.join(out_bad, "router.json")) and not os.path.exists(os.path.join(out_bad, "router-setup.sh")),
      "sign_router.py writes nothing with another key")
check("not the owner key" in out or "does not match" in out, "sign_router.py says why: " + out[-200:])

# a repository with no owner key installed signs nothing either (fail closed)
open(os.path.join(repo, "tools", "pisoportal", "owner_key.b64"), "w").close()
rc, out = run("scripts/sign_router.py", "--private", good_pem, "--setup", setup, "--out-dir", os.path.join(tmp, "out_nokey"))
check(rc != 0, "sign_router.py refuses when the repository has no owner key")
with open(os.path.join(repo, "tools", "pisoportal", "owner_key.b64"), "w") as f:
    f.write(base64.b64encode(good_der).decode() + "\n")

# --public-key names the key to check against (for tests and for a key that is not installed yet)
rc, out = run("scripts/sign_router.py", "--private", bad_pem, "--setup", setup, "--out-dir", os.path.join(tmp, "out_pk"),
              "--public-key", os.path.join(repo, "tools", "pisoportal", "owner_key.b64"))
check(rc != 0, "--public-key with the repository's key still refuses another private key")

# --- sign_firmware.py
image = os.path.join(tmp, "firmware.bin")
with open(image, "wb") as f:
    f.write(b"\xe9" + bytes(range(251)) * 8)
rc, out = run("scripts/sign_firmware.py", "--private", good_pem, "--chip", "esp32c3", "--image", image, "--version", "3.0.0")
check(rc == 0 and os.path.exists(image + ".manifest.json"), "sign_firmware.py signs with the repository's key: " + out[-200:])
os.remove(image + ".manifest.json")
rc, out = run("scripts/sign_firmware.py", "--private", bad_pem, "--chip", "esp32c3", "--image", image, "--version", "3.0.0")
check(rc != 0, "sign_firmware.py exits non-zero with another key: " + out[-200:])
check(not os.path.exists(image + ".manifest.json"), "sign_firmware.py writes no manifest with another key")

# --- make_owner_keys.py --github-secret: a key path inside the repository is refused, as it is without the flag
inside = os.path.join(repo, "mykey.pem")
header = os.path.join(tmp, "hdr.h")
rc, out = run("scripts/make_owner_keys.py", inside, "--github-secret", "--header", header)
check(rc != 0 and "inside the repository" in out, "--github-secret refuses a key path inside the repository: " + out[-200:])
check(not os.path.exists(inside) and not os.path.exists(header), "nothing was written for a path inside the repository")
outside = os.path.join(tmp, "elsewhere.pem")
rc, out = run("scripts/make_owner_keys.py", outside, "--github-secret", "--header", header)
check(rc == 0 and os.path.exists(outside) and os.path.exists(header), "--github-secret still works for a path outside the repository: " + out[-200:])

shutil.rmtree(tmp, ignore_errors=True)
print("test_signing_keys: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
