#!/usr/bin/env python3
"""scripts/publish_firmware_ota.py, the script CI uses to sign the box firmware and announce it in website/update/firmware.json.
It runs in a scratch copy, so nothing in the repository is touched. Needs `pip install cryptography`.
Run: python3 scripts/tests/test_publish_firmware.py"""
import base64, hashlib, json, os, shutil, subprocess, sys, tempfile

from cryptography.hazmat.primitives import hashes, serialization
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
images = os.path.join(tmp, "images")
os.makedirs(images)
for i, chip in enumerate(("esp32c3", "esp32")):
    open(os.path.join(images, f"firmware-{chip}.bin"), "wb").write(b"\xe9" + bytes((j * 7 + i) % 251 for j in range(150000)))
key = ec.generate_private_key(ec.SECP256R1())
pem = os.path.join(tmp, "owner.pem")
open(pem, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def sandbox(name, with_key_in_firmware):
    d = os.path.join(tmp, name)
    for sub in ("scripts", "esp32_firmware/include", "website/update"):
        os.makedirs(os.path.join(d, sub))
    for f in ("publish_firmware_ota.py", "sign_firmware.py", "owner_key.py"):
        shutil.copy(os.path.join(ROOT, "scripts", f), os.path.join(d, "scripts", f))
    shutil.copy(os.path.join(ROOT, "esp32_firmware/include/FirmwareVersion.h"), os.path.join(d, "esp32_firmware/include"))
    hdr = ("static const uint8_t LICENSE_PUBKEY_DER[] = {%s};\nstatic const size_t LICENSE_PUBKEY_LEN = sizeof(LICENSE_PUBKEY_DER);\n" % ",".join("0x%02x" % b for b in der)
           if with_key_in_firmware else "static const size_t LICENSE_PUBKEY_LEN = 0;\n")
    open(os.path.join(d, "esp32_firmware/include/LicensePubKey.h"), "w").write(hdr)
    shutil.copy(os.path.join(ROOT, "website/update/firmware.json"), os.path.join(d, "website/update/firmware.json"))
    return d


def run(d, *args):
    # (never main: the same version with new bytes is an error there, and these sandboxes start from the repository's real feed)
    p = subprocess.run([sys.executable, "scripts/publish_firmware_ota.py", *args], cwd=d, capture_output=True, text=True,
                       env=dict(os.environ, GITHUB_REF_NAME="beta"))
    return p.returncode, p.stdout + p.stderr


def feed(d):
    return json.load(open(os.path.join(d, "website/update/firmware.json")))


version = __import__("re").search(r'PISO_FW_VERSION "([^"]+)"', open(os.path.join(ROOT, "esp32_firmware/include/FirmwareVersion.h")).read()).group(1)

# signed: every image is announced with a checksum, a size and a signature that verifies, under the firmware's own version
d = sandbox("signed", True)
build_before = feed(d)["build"]
rc, out = run(d, "--images", images, "--key", pem)
check(rc == 0, "signing and publishing works: " + out[-200:])
f = feed(d)
check(f["version"] == version and f["build"] == build_before + 1, "firmware.json carries the firmware's own version and a new build number")
for chip, e in f["chips"].items():
    img = open(os.path.join(images, f"firmware-{chip}.bin"), "rb").read()
    text = f"pisophone-fw-v1|{chip}|{version}|{hashlib.sha256(img).hexdigest()}|{len(img)}"
    try:
        key.public_key().verify(base64.b64decode(e["sig"]), text.encode(), ec.ECDSA(hashes.SHA256()))
        good = e["sha256"] == hashlib.sha256(img).hexdigest() and e["size"] == len(img)
    except Exception:
        good = False
    check(good, f"{chip}: checksum, size and signature are right")
    check(open(os.path.join(d, "website/update", f"firmware-{chip}.bin"), "rb").read() == img, f"{chip}: the published file is the built image")

# the same images again change nothing
before = open(os.path.join(d, "website/update/firmware.json")).read()
rc, out = run(d, "--images", images, "--key", pem)
check(rc == 0 and "nothing to do" in out and open(os.path.join(d, "website/update/firmware.json")).read() == before, "publishing the same images twice changes nothing")

# the same version with different bytes: boxes would say "up to date" and never install it. A warning on a branch, a failure on main.
changed = os.path.join(tmp, "changed")
os.makedirs(changed)
for i, chip in enumerate(("esp32c3", "esp32")):
    open(os.path.join(changed, f"firmware-{chip}.bin"), "wb").write(b"\xe9" + bytes((j * 11 + i) % 253 for j in range(150000)))


def run_on(d, branch, *args):
    env = dict(os.environ, GITHUB_REF_NAME=branch)
    p = subprocess.run([sys.executable, "scripts/publish_firmware_ota.py", *args], cwd=d, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout + p.stderr


before = open(os.path.join(d, "website/update/firmware.json")).read()
rc, out = run_on(d, "main", "--images", changed, "--key", pem)
check(rc != 0 and "PISO_FW_VERSION" in out, "main: the same version with new bytes fails: " + out[-200:])
check(open(os.path.join(d, "website/update/firmware.json")).read() == before, "main: nothing was published when it failed")
rc, out = run_on(d, "beta", "--images", changed, "--key", pem)
check(rc == 0 and "::warning::PISO_FW_VERSION" in out, "beta: the same version with new bytes is only a warning: " + out[-200:])

# a firmware that has an owner key refuses to publish unsigned images
rc, out = run(sandbox("nokey", True), "--images", images)
check(rc != 0 and "owner public key" in out, "no signing key while the firmware has an owner key: refused")

# a firmware without an owner key publishes unsigned images (and announces no signature)
d = sandbox("unsigned", False)
rc, out = run(d, "--images", images)
check(rc == 0 and all("sig" not in e for e in feed(d)["chips"].values()), "no owner key in the firmware: images are published unsigned")

# an image that is not a box image is refused
bad = os.path.join(tmp, "bad")
os.makedirs(bad)
for chip in ("esp32c3", "esp32"):
    open(os.path.join(bad, f"firmware-{chip}.bin"), "wb").write(b"\x00" + b"x" * 150000)
rc, out = run(sandbox("bad", False), "--images", bad)
check(rc != 0 and "0xE9" in out, "an image that does not start with 0xE9 is refused")
tiny = os.path.join(tmp, "tiny")
os.makedirs(tiny)
for chip in ("esp32c3", "esp32"):
    open(os.path.join(tiny, f"firmware-{chip}.bin"), "wb").write(b"\xe9" + b"x" * 1000)
rc, out = run(sandbox("tiny", False), "--images", tiny)
check(rc != 0, "an image under 100 KB is refused (the dashboard would refuse it too)")

# the manual flow: sign by hand, then --json-only announces exactly those files
d = sandbox("manual", True)
for chip in ("esp32c3", "esp32"):
    shutil.copy(os.path.join(images, f"firmware-{chip}.bin"), os.path.join(d, "website/update"))
    subprocess.run([sys.executable, "scripts/sign_firmware.py", "--private", pem, "--chip", chip, "--image", f"website/update/firmware-{chip}.bin"], cwd=d, capture_output=True, check=True)
rc, out = run(d, "--json-only")
check(rc == 0 and all(e.get("sig") and e.get("size") for e in feed(d)["chips"].values()), "--json-only announces hand-signed images with their signatures")
# a stale manifest (made for another image) is not announced
open(os.path.join(d, "website/update/firmware-esp32.bin"), "wb").write(b"\xe9" + b"y" * 150000)
rc, out = run(d, "--json-only")
check(rc == 0 and "sig" not in feed(d)["chips"]["esp32"] and feed(d)["chips"]["esp32c3"].get("sig"), "a manifest that belongs to another image is not announced")
rc, out = run(d)
check(rc != 0, "without --images or --json-only it says what is missing")

shutil.rmtree(tmp, ignore_errors=True)
print("test_publish_firmware: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
