# Super admin: the owner's admin QR code

Super admin (revenue collection, the vault and the revenue split, installing an older firmware, the full factory wipe) is opened
with **one QR code that only you hold**. There is no password to set, publish or rotate, and no website feed.

## How it works
- `python3 scripts/make_admin_qr.py` makes a key (the first time) and draws the QR code from it. The code holds the **private key**
  (`PISOADMIN1.<d>.<x>.<y>`); keep the picture and the key file (`~/pisophone-superadmin.key`) private.
- The boxes carry only the matching **public key** (`esp32_firmware/include/SuperAdminPubKey.h`), built into the firmware.
- On the box's login page (any browser that has no sign-in yet), **Scan admin QR** opens the phone's camera. The page reads the
  photo with the vendored jsQR, asks the box for a one-time challenge, signs `pisophone-admin-v1|<nonce>|<box id>` with the key
  from the QR code (ECDSA P-256, in `web/adminqr.js`: the box's pages are plain HTTP, where the browser's own crypto is not
  available) and sends **only the signature**. The key never leaves the phone, and a signature is good for one box and one
  challenge for one minute.
- The box checks the signature with its public key and starts a **session**: a random cookie, valid for 15 idle minutes and one
  hour at most. The session is both the dashboard sign-in and super admin; the admin password still works for the normal admin.
  Five wrong tries lock a phone out for a minute, like wrong passwords. "Lock now" in vendor mode, or Sign out, ends it.
- The box's page is reached the usual way: join the hidden kiosk Wi-Fi (`PisoKiosk-xxxx`; its name and password are in the setup summary) with your phone (its password is in the setup summary)
  and open `http://10.0.0.10`.

## Setting it up (once)
1. `python3 scripts/make_admin_qr.py` (needs `pip install cryptography qrcode pillow`). It reuses `~/pisophone-superadmin.key` if
   you made one before, or makes it. It writes `pisophone-admin-qr.png`: print it or keep it on your phone.
2. If it made a **new** key it also rewrote `SuperAdminPubKey.h`: commit that, merge to `main`, and update every box once
   (dashboard **Update > Install update**). A box only trusts the key it was built with.

## If the QR code leaks or is lost
`python3 scripts/make_admin_qr.py --new`, commit the new header, update every box. Until a box is updated it still trusts the old
code. (An old code on a box that has the new key is simply refused.)

## Test
Checklist items S6 and C3b in `docs/REAL_WORLD_TESTING.md`; the logic is covered by `esp32_firmware/host_tests/admin_qr_test.cpp`
and the browser side (signing, reading a photo) by `scripts/tests/test_admin_qr.py`.
