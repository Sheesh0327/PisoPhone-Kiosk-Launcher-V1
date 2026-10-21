#!/usr/bin/env python3
"""scripts/check_security_rules.sh must catch a private key written in the forms people paste into a secret box: a one-line
base64 of the PEM text, and a base64 DER PKCS8 key. The keys are made here with throwaway keys and planted in a temporary copy
of the few files the script reads; nothing is written in the repository. Needs `pip install cryptography` and a POSIX sh.
Run: python3 scripts/tests/test_security_rules.py"""
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


SH = shutil.which("sh") or shutil.which("bash")
if not SH:
    print("test_security_rules: no sh here, skipped")
    sys.exit(0)

tmp = tempfile.mkdtemp()
for rel in ("scripts/check_security_rules.sh", "esp32_firmware/src/WebServerCoinslot.cpp", "setup/piso-setup.sh.in"):
    os.makedirs(os.path.join(tmp, os.path.dirname(rel)), exist_ok=True)
    shutil.copy(os.path.join(ROOT, rel), os.path.join(tmp, rel))
os.makedirs(os.path.join(tmp, "website"))


def run():
    p = subprocess.run([SH, "scripts/check_security_rules.sh"], cwd=tmp, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


planted = os.path.join(tmp, "website", "planted.txt")
rc, out = run()
check(rc == 0, "the temporary copy passes before anything is planted: " + out[-200:])

key = ec.generate_private_key(ec.SECP256R1())
pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
der = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
rsa_marker = b"-----BEGIN " + b"RSA PRIVATE KEY-----"
cases = {
    "one-line base64 of a PEM key": base64.b64encode(pem).decode(),
    "base64 of a PEM key after a name=": "OWNER_SIGNING_KEY_B64=" + base64.b64encode(pem).decode(),
    "base64 of a PEM key at another byte offset": base64.b64encode(b"x" + pem).decode(),
    "base64 of a PEM key at the third byte offset": base64.b64encode(b"xx" + pem).decode(),
    "base64 of an RSA PEM key": base64.b64encode(rsa_marker + b"\nabcd\n").decode(),
    "base64 DER PKCS8 key": base64.b64encode(der).decode(),
    "base64 DER PKCS8 key after a name=": "key: " + base64.b64encode(der).decode(),
}
for name, line in cases.items():
    with open(planted, "w") as f:
        f.write("# notes\n" + line + "\n")
    rc, out = run()
    check(rc != 0 and "RULE VIOLATION" in out, f"{name} is caught")

# things that must not trip the rule: a public key (what owner_key.b64 holds) and ordinary text
pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
with open(planted, "w") as f:
    f.write(base64.b64encode(pub).decode() + "\nthe quick brown fox\n")
rc, out = run()
check(rc == 0, "a base64 public key is not flagged: " + out[-200:])
os.remove(planted)

shutil.rmtree(tmp, ignore_errors=True)
print("test_security_rules: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
