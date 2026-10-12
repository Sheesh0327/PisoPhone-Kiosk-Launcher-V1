#!/usr/bin/env python3
"""Signs the owner console's page script so routers can take a newer one from the website (owner only; needs `pip install cryptography`).

The console (tools/pisoportal/src/console.html) runs its built-in script (console-ui.js). When the website holds a NEWER one that this tool
signed with the owner key, the page checks the signature in the browser (with the owner key the router already trusts for updates) and runs
it instead: the page can then change without a router update. An unsigned, expired, damaged or older script is ignored.

  python3 scripts/sign_console_ui.py --private ~/pisophone_license_key.pem             signs tools/pisoportal/src/console-ui.js
  python3 scripts/sign_console_ui.py --private KEY.pem --expires-days 60

It writes website/console/ui.js (the script, byte for byte) and website/console/ui.json:
  {"version", "api", "expires", "sha256", "sig"}     signed text: pisophone-console-ui-v1|<version>|<api>|<expires>|<sha256 hex>
The version is `window.PISO_UI_VERSION = N` inside the script: raise it for every published change. The api (1) is the page <-> router interface;
a script for another api is ignored by an older router. Commit both files to main (CI does it on main, publish-router.yml).
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import owner_key  # noqa: E402

API = 1
DEFAULT_SRC = os.path.join(ROOT, "tools", "pisoportal", "src", "console-ui.js")
DEFAULT_OUT = os.path.join(ROOT, "website", "console")


def canonical(version, api, expires, sha256_hex):
    return f"pisophone-console-ui-v1|{version}|{api}|{expires}|{sha256_hex}"


def version_of(data):
    m = re.search(rb"PISO_UI_VERSION\s*=\s*(\d+)", data)
    return int(m.group(1)) if m else None


def make_manifest(private_key, data, now=None, expires_days=120):
    hashes, _, ec = owner_key._crypto()
    version = version_of(data)
    if version is None:
        raise ValueError("the script has no `window.PISO_UI_VERSION = N` line")
    if not 100 <= len(data) <= 190_000:
        raise ValueError("the script must be between 100 bytes and 190 KB (the page refuses more)")
    expires = int((now if now is not None else time.time()) + expires_days * 86400)
    sha = hashlib.sha256(data).hexdigest()
    sig = private_key.sign(canonical(version, API, expires, sha).encode(), ec.ECDSA(hashes.SHA256()))
    return {"version": version, "api": API, "expires": expires, "sha256": sha, "sig": base64.b64encode(sig).decode()}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="the owner's private key (PEM), made by make_owner_keys.py")
    p.add_argument("--src", default=DEFAULT_SRC, help="the script to sign (default: tools/pisoportal/src/console-ui.js)")
    p.add_argument("--out-dir", default=DEFAULT_OUT, help="default: website/console")
    p.add_argument("--expires-days", type=int, default=120, help="how long the signature is good for (default 120; sign again before)")
    args = p.parse_args()
    try:
        with open(args.src, "rb") as f:
            data = f.read()
        manifest = make_manifest(owner_key.load_private(args.private), data, expires_days=args.expires_days)
    except (ValueError, OSError) as e:
        sys.exit(f"Error: {e}")
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "ui.js"), "wb") as f:
        f.write(data)
    with open(os.path.join(args.out_dir, "ui.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"Signed console script version {manifest['version']} ({len(data)} bytes), good for {args.expires_days} days.")
    print(f"Written to {args.out_dir}: ui.js and ui.json. Commit both to main.")


if __name__ == "__main__":
    main()
