#!/usr/bin/env python3
"""
Creates YOUR signing key (one key signs firmware and router updates). Run it on YOUR computer, never in CI or a
cloud session: the private key must exist only on machines you control. Needs: pip install cryptography

  python3 scripts/make_owner_keys.py                    key saved to ~/pisophone_license_key.pem
  python3 scripts/make_owner_keys.py /path/key.pem      key saved elsewhere (not inside this repository)
  python3 scripts/make_owner_keys.py --github-secret    nothing saved: prints the key ONCE as one base64 line to paste
                                                        into the repository secret OWNER_SIGNING_KEY_B64

It refuses to overwrite an existing key, writes only the PUBLIC key into esp32_firmware/include/LicensePubKey.h,
and proves the key works by signing and verifying a test message. GitHub secrets cannot be read back: also keep one offline copy.
"""

import argparse
import base64
import os
import stat
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import owner_key  # noqa: E402

DEFAULT_KEY = os.path.join(os.path.expanduser("~"), "pisophone_license_key.pem")


def inside_repo(path):
    real = os.path.normcase(os.path.realpath(path))
    root = os.path.normcase(os.path.realpath(ROOT))
    return real == root or real.startswith(root + os.sep)


def make_key():
    hashes, serialization, ec = owner_key._crypto()
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return key, pem


def self_test(key):
    hashes, _, ec = owner_key._crypto()
    message = b"pisophone-self-test"
    try:
        key.public_key().verify(key.sign(message, ec.ECDSA(hashes.SHA256())), message, ec.ECDSA(hashes.SHA256()))
    except Exception:
        sys.exit("Self-test FAILED: the key could not sign and verify a test message.")
    print("Self-test passed (signed and verified a test message).")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", nargs="?", default=DEFAULT_KEY, help="where to save the private key")
    ap.add_argument("--github-secret", action="store_true", help="print the key once instead of saving it")
    ap.add_argument("--header", default=os.environ.get("PUBKEY_HEADER", owner_key.PUBKEY_HEADER),
                    help="firmware header that receives the public key")
    a = ap.parse_args()

    if a.github_secret:
        key, pem = make_key()
        owner_key.write_pubkey_header(key.public_key(), a.header)
        self_test(key)
        b64 = base64.b64encode(pem).decode()
        print(f"""
Public key written to {a.header} and tools/pisoportal/owner_key.b64 (commit both).

PRIVATE KEY, shown once. In GitHub: Settings > Secrets and variables > Actions > New repository secret,
name  OWNER_SIGNING_KEY_B64   value (the whole line):

{b64}

Also save that line in a password manager NOW: GitHub secrets cannot be read back and nothing was saved on this
computer. Then clear your terminal scrollback.
Next: git add esp32_firmware/include/LicensePubKey.h tools/pisoportal/owner_key.b64 && git commit -m "Install owner public key", then rebuild and flash the boxes.""")
        return

    if inside_repo(a.path):
        sys.exit(f"Refusing: {a.path} is inside the repository ({ROOT}). Pick a folder outside it, "
                 f"for example: python scripts/make_owner_keys.py C:\\keys\\pisophone_license_key.pem")
    if os.path.exists(a.path):
        sys.exit(f"Refusing: {a.path} already exists. Move it away first; replacing it makes every signed update invalid.")
    key, pem = make_key()
    fd = os.open(a.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, stat.S_IRUSR | stat.S_IWUSR)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    owner_key.write_pubkey_header(key.public_key(), a.header)
    print(f"Private key written to {a.path}")
    print(f"Public key written to {a.header} (and, for the routers, tools/pisoportal/owner_key.b64)")
    self_test(key)
    print(f"""
Done. Next steps:
  1. BACK UP {a.path} now, in two separate offline places (a USB stick in a drawer, a password manager attachment).
     Lose it and no box can ever take a new update; leak it and anyone can forge one.
  2. Commit ONLY the public key:   git add esp32_firmware/include/LicensePubKey.h tools/pisoportal/owner_key.b64 && git commit -m "Install owner public key"
  3. Rebuild and flash every box:  cd esp32_firmware && pio run -e esp32-c3-dev -t upload
  4. Sign a firmware build:        python3 scripts/sign_firmware.py --private {a.path} --chip esp32c3 --image <firmware.bin>
  5. Sign a router update:         python3 scripts/sign_router.py --private {a.path} --setup setup/piso-setup.sh   (docs/RELEASE.md)""")


if __name__ == "__main__":
    main()
