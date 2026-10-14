"""scripts/make_recovery_code.py: the code verifies with the matching public key only, carries its expiry, and the picture
decodes to the same text. Run: python3 scripts/tests/test_recovery_code.py"""
import base64
import os
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import make_recovery_code as m  # noqa: E402
import owner_key  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

checks = failures = 0


def check(ok, msg):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", msg)


def verify(code, pub, now):
    """What the app does (RecoveryCode.verify), in Python."""
    parts = code.split(".")
    if len(parts) != 3 or parts[0] != "PISOREC1":
        return "MALFORMED"
    expiry = int(parts[1])
    sig = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
    try:
        pub.verify(sig, m.message(expiry), ec.ECDSA(hashes.SHA256()))
    except Exception:
        return "BAD_SIGNATURE"
    if now > expiry:
        return "EXPIRED"
    if expiry - now > 25 * 3600:
        return "TOO_FAR"
    return "OK"


key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
now = int(time.time())
code = m.make_code(key, now + 1800)
check(code.startswith("PISOREC1.") and code.count(".") == 2, "format: " + code)
check(len(code) < 140, f"short enough for an easy QR: {len(code)}")
check(verify(code, key.public_key(), now) == "OK", "valid code verifies")
check(verify(code, other.public_key(), now) == "BAD_SIGNATURE", "another key's code is refused")
check(verify(code, key.public_key(), now + 3600) == "EXPIRED", "expired code is refused")
check(verify(m.make_code(key, now + 30 * 3600), key.public_key(), now) == "TOO_FAR", "a code that lives too long is refused")
forged = code.rsplit(".", 1)[0].replace(str(now + 1800), str(now + 99999)) + "." + code.rsplit(".", 1)[1]
check(verify(forged, key.public_key(), now) == "BAD_SIGNATURE", "changing the expiry breaks the signature")

# the script end to end: key file in, picture out, the picture decodes to the code
tmp = tempfile.mkdtemp()
pem = os.path.join(tmp, "k.pem")
open(pem, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
png = os.path.join(tmp, "r.png")
r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "make_recovery_code.py"), "--private", pem, "--minutes", "5", "--out", png],
                   capture_output=True, text=True)
check(r.returncode == 0 and os.path.exists(png), "script runs: " + r.stdout + r.stderr)
try:
    from pyzbar.pyzbar import decode
    from PIL import Image
    text = decode(Image.open(png))[0].data.decode()
    check(verify(text, key.public_key(), int(time.time())) == "OK", "the picture decodes to a valid code")
except ImportError:
    print("note: pyzbar not installed; the picture was not decoded")
bad = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "make_recovery_code.py"), "--private", pem, "--minutes", "2000", "--out", png],
                     capture_output=True, text=True)
check(bad.returncode != 0, "more than 24 hours is refused")
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
