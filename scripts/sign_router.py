#!/usr/bin/env python3
"""
Signs a router update so every router will install it (owner only; run on your own computer; needs `pip install cryptography`).

The router's one-file setup (setup/piso-setup.sh, built by CI together with the portal program) is what a router installs from.
This tool signs that file with the same offline owner key that signs licenses and firmware, and writes the two files the
routers fetch from the website:

  website/update/router-setup.sh   the setup file, byte for byte
  website/update/router.json       {"version", "sha256", "size", "rollout", "changelog", "sig"}

  python3 scripts/sign_router.py --private ~/pisophone_license_key.pem --changelog "Fixes the coin page on old phones"
  python3 scripts/sign_router.py --private KEY.pem --rollout 10      first only 10% of the routers (chosen by a stable hash)
  python3 scripts/sign_router.py --private KEY.pem --rollout 100     then everyone: sign the same file again with a higher rollout

The version is PISO_RELEASE inside the setup file (setup/RELEASE): raise it for every update, routers never go down in version.
To withdraw a bad update, publish a fixed one with a higher version. Signed text:
  pisophone-router-v1|<version>|<sha256 hex of router-setup.sh>|<size>|<rollout>
Commit the two files to main (website/update/); Cloudflare Pages publishes them. Routers check once a night.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import generate_license as gl  # noqa: E402  (shared key loading)

MAX_SIZE = 4 * 1024 * 1024  # what a router accepts (tools/pisoportal/src/update.rs)
DEFAULT_SETUP = os.path.join(ROOT, "setup", "piso-setup.sh")
DEFAULT_OUT = os.path.join(ROOT, "website", "update")


def canonical(version, sha256_hex, size, rollout):
    return f"pisophone-router-v1|{version}|{sha256_hex}|{size}|{rollout}"


def release_of(data):
    m = re.search(rb"^PISO_RELEASE='(\d{1,5}\.\d{1,5}\.\d{1,5})'$", data, re.M)
    return m.group(1).decode() if m else None


def make_manifest(private_key, data, rollout, changelog=""):
    hashes, _, ec = gl._crypto()
    version = release_of(data)
    if not version:
        raise ValueError("this is not a router setup file (no PISO_RELEASE='x.y.z' line): build it with tools/build_piso_setup.py")
    if not data.startswith(b"#!/bin/sh"):
        raise ValueError("a setup file starts with #!/bin/sh")
    if b"\n#@@B64 /usr/bin/pisoportal " not in data:
        raise ValueError("this setup file carries no portal program: use the file CI built (it commits it to the branch)")
    if not 0 <= rollout <= 100:
        raise ValueError("rollout is a percentage, 0..100")
    if not 1024 <= len(data) <= MAX_SIZE:
        raise ValueError(f"the setup file must be between 1 KB and {MAX_SIZE} bytes")
    sha = hashlib.sha256(data).hexdigest()
    sig = private_key.sign(canonical(version, sha, len(data), rollout).encode(), ec.ECDSA(hashes.SHA256()))
    return {"version": version, "sha256": sha, "size": len(data), "rollout": rollout,
            "changelog": changelog.replace("\n", " ").replace('"', "'")[:300], "sig": base64.b64encode(sig).decode()}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="the owner's private key (PEM), made by make_owner_keys.py")
    p.add_argument("--setup", default=DEFAULT_SETUP, help="the setup file to sign (default: setup/piso-setup.sh)")
    p.add_argument("--rollout", type=int, default=100, help="percent of routers that take it now (default 100)")
    p.add_argument("--changelog", default="", help="one line shown in the routers' Telegram message")
    p.add_argument("--out-dir", default=DEFAULT_OUT, help="default: website/update")
    args = p.parse_args()
    try:
        with open(args.setup, "rb") as f:
            data = f.read()
        manifest = make_manifest(gl.load_private(args.private), data, args.rollout, args.changelog)
    except (ValueError, OSError) as e:
        sys.exit(f"Error: {e}")
    os.makedirs(args.out_dir, exist_ok=True)
    shutil.copyfile(args.setup, os.path.join(args.out_dir, "router-setup.sh"))
    with open(os.path.join(args.out_dir, "router.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"Signed router release {manifest['version']} ({manifest['size']} bytes), rollout {manifest['rollout']}%")
    print(f"sha256 {manifest['sha256']}")
    print(f"Written to {args.out_dir}: router-setup.sh and router.json. Commit both to main.")


if __name__ == "__main__":
    main()
