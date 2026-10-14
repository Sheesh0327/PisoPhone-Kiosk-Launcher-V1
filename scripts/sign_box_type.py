#!/usr/bin/env python3
"""
Signs a coin box's TYPE, which sets how many phones it may pair (owner only; needs `pip install cryptography`).

  purchased   a box the shop owner bought: up to 6 phones
  rental      a box you keep and rent out: up to 12 phones

The token names the box's own MAC address (shown on its dashboard, Tools > Box type), so it works on that box only.
The owner key is the same one that signs firmware and router updates (scripts/make_owner_keys.py).

  python3 scripts/sign_box_type.py --private ~/pisophone_license_key.pem --mac AA:BB:CC:DD:EE:FF --type rental

Paste the printed token into the box's dashboard, Tools > Box type. (Standing next to the box, the admin QR code can set the
type directly instead.) A box with neither starts as "purchased".
Signed text: pisohub-box-v1|<mac, 12 lowercase hex digits>|<type>
"""

import argparse
import base64
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import owner_key  # noqa: E402  (shared key loading)

TYPES = ("purchased", "rental")


def normalize_mac(mac):
    m = re.sub(r"[:\-]", "", mac).lower()
    if not re.fullmatch(r"[0-9a-f]{12}", m):
        raise ValueError("the MAC address must look like AA:BB:CC:DD:EE:FF")
    return m


def canonical(mac_hex, box_type):
    return f"pisohub-box-v1|{mac_hex}|{box_type}"


def make_token(private_key, mac, box_type):
    hashes, _, ec = owner_key._crypto()
    if box_type not in TYPES:
        raise ValueError(f"type must be one of {TYPES}")
    mac_hex = normalize_mac(mac)
    sig = private_key.sign(canonical(mac_hex, box_type).encode(), ec.ECDSA(hashes.SHA256()))
    return f"{box_type}:{mac_hex}:{base64.b64encode(sig).decode()}"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="private key (PEM) made by make_owner_keys.py")
    p.add_argument("--mac", required=True, help="the box's MAC address, as the dashboard shows it")
    p.add_argument("--type", required=True, choices=TYPES, dest="box_type")
    args = p.parse_args()
    try:
        token = make_token(owner_key.load_private(args.private), args.mac, args.box_type)
    except (ValueError, OSError) as e:
        sys.exit(f"Error: {e}")
    print(token)


if __name__ == "__main__":
    main()
