#!/usr/bin/env python3
"""
Makes a RECOVERY QR code: scan it on a phone's lock screen and PisoPhone removes itself from that phone (the kiosk lock and
Device Owner are cleared, so the app can be uninstalled). No USB cable, no USB debugging, and it works on a phone that never
reached its coin box. Run it on YOUR computer. Needs: pip install cryptography qrcode pillow

  python3 scripts/make_recovery_code.py                  valid for 30 minutes, written to ./pisophone-recovery.png
  python3 scripts/make_recovery_code.py --minutes 10     shorter
  python3 scripts/make_recovery_code.py --private /path/key.pem --out code.png

The code is signed with your OWNER key (the one made by make_owner_keys.py): the app checks it against the public key built
into it, so nobody else can make one, and it stops working when its time is up. Treat the picture like a key while it is
valid: anyone holding it can remove the app from a phone until it expires. Delete it afterwards. On the phone: lock screen,
open the admin entry, "SCAN A RECOVERY CODE (REMOVE APP)".
"""

import argparse
import base64
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import owner_key  # noqa: E402

PREFIX = "PISOREC1"
MAX_MINUTES = 24 * 60   # the app refuses a code that lives longer than 25 hours


def message(expiry: int) -> bytes:
    return f"pisophone-recover-v1|{expiry}".encode()


def make_code(private_key, expiry: int) -> str:
    hashes, _, ec = owner_key._crypto()
    signature = private_key.sign(message(expiry), ec.ECDSA(hashes.SHA256()))   # DER, as the app's Signature expects
    return f"{PREFIX}.{expiry}." + base64.urlsafe_b64encode(signature).decode().rstrip("=")


def write_png(text: str, path: str):
    import qrcode
    from qrcode.constants import ERROR_CORRECT_M
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, box_size=10, border=4)
    qr.add_data(text)
    qr.make(fit=True)
    qr.make_image(fill_color="black", back_color="white").save(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--private", default=os.path.join(os.path.expanduser("~"), "pisophone_license_key.pem"),
                    help="your owner private key (default ~/pisophone_license_key.pem)")
    ap.add_argument("--minutes", type=int, default=30, help=f"how long the code works (1 to {MAX_MINUTES}, default 30)")
    ap.add_argument("--out", default="pisophone-recovery.png", help="where to write the picture")
    a = ap.parse_args()
    if not 1 <= a.minutes <= MAX_MINUTES:
        sys.exit(f"--minutes must be between 1 and {MAX_MINUTES}")
    if not os.path.exists(a.private):
        sys.exit(f"No owner key at {a.private}. Use --private to point at it (make_owner_keys.py made it).")
    key = owner_key.load_private(a.private)
    expiry = int(time.time()) + a.minutes * 60
    code = make_code(key, expiry)
    write_png(code, a.out)
    print(f"Recovery code written to {a.out}. It works until {time.strftime('%H:%M', time.localtime(expiry))} "
          f"({a.minutes} minutes from now).")
    print("Show it to the phone's camera on the lock screen (admin entry > SCAN A RECOVERY CODE). Delete the picture afterwards.")


if __name__ == "__main__":
    main()
