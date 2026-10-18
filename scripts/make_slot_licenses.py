#!/usr/bin/env python3
"""
Makes slot license keys: each one opens ONE extra phone slot on ONE coin box (owner only; needs `pip install cryptography`).

Every box has 2 free phone slots. Each further slot needs a license key (the price is yours to set; the dashboard says P500).
A key names the box's own code (its MAC address, shown on its dashboard under Phone slots) and carries its own number, so it
works on that box only and only once. The owner key is the one that signs firmware (scripts/make_owner_keys.py).

  python3 scripts/make_slot_licenses.py --private ~/pisophone_license_key.pem --box AA:BB:CC:DD:EE:FF --count 2

Give the customer the printed line(s); they paste one into the box's dashboard, Phone slots, "Add slot". Add --ledger FILE to
append every key you made (date, box, key number) to a CSV, so you can see what you sold and cancel a dispute with proof.
Signed text: pisophone-slot-v1|<mac, 12 lowercase hex digits>|<key number, 8 lowercase hex digits>
"""

import argparse
import base64
import csv
import datetime
import os
import re
import secrets
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import owner_key  # noqa: E402  (shared key loading)

PREFIX = "PSLOT1"
FREE_SLOTS = 2
MAX_SLOTS = 12


def normalize_mac(mac):
    m = re.sub(r"[:\-]", "", mac).lower()
    if not re.fullmatch(r"[0-9a-f]{12}", m):
        raise ValueError("the box code must look like AA:BB:CC:DD:EE:FF")
    return m


def canonical(mac_hex, key_id):
    return f"pisophone-slot-v1|{mac_hex}|{key_id}"


def new_id():
    return secrets.token_hex(4)


def make_token(private_key, mac, key_id=None):
    hashes, _, ec = owner_key._crypto()
    mac_hex = normalize_mac(mac)
    key_id = (key_id or new_id()).lower()
    if not re.fullmatch(r"[0-9a-f]{8}", key_id):
        raise ValueError("a key number is 8 hex digits")
    sig = private_key.sign(canonical(mac_hex, key_id).encode(), ec.ECDSA(hashes.SHA256()))
    return f"{PREFIX}.{mac_hex}.{key_id}.{base64.b64encode(sig).decode()}"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="private key (PEM) made by make_owner_keys.py")
    p.add_argument("--box", required=True, help="the box code (MAC address) as the dashboard shows it")
    p.add_argument("--count", type=int, default=1, help=f"how many keys (1 to {MAX_SLOTS - FREE_SLOTS}, default 1)")
    p.add_argument("--ledger", help="CSV file to append the keys you make to")
    args = p.parse_args()
    if not 1 <= args.count <= MAX_SLOTS - FREE_SLOTS:
        sys.exit(f"Error: a box holds at most {MAX_SLOTS - FREE_SLOTS} license keys")
    try:
        key = owner_key.load_private(args.private)
        tokens = [make_token(key, args.box) for _ in range(args.count)]
    except (ValueError, OSError) as e:
        sys.exit(f"Error: {e}")
    if args.ledger:
        now = datetime.datetime.now().isoformat(timespec="seconds")
        with open(args.ledger, "a", newline="") as f:
            for t in tokens:
                csv.writer(f).writerow([now, normalize_mac(args.box), t.split(".")[2]])
    for t in tokens:
        print(t)


if __name__ == "__main__":
    main()
