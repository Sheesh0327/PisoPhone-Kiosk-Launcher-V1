# Remote super-admin password

The super-admin password (unlocks the whole admin dashboard, vault and split settings) is now
managed from the website. Operator **admin** passwords stay per box and are unaffected.

## How it works
- `website/update/credentials.json` holds `{version, iter, salt, hash, sig}`. `hash` is
  PBKDF2-HMAC-SHA256 of the password, so the password itself is never published.
- `sig` is an ECDSA P-256 signature made with your private key. The ESP32 only accepts a file
  that verifies against the public key compiled into the firmware (`include/SuperAdminPubKey.h`)
  **and** whose `version` is higher than the one it holds, so neither a fake server nor an old
  leaked file can change or roll back the password.
- Each ESP32 with internet checks 1 minute after connecting, then hourly (10 minutes after a
  failure). On acceptance it stores the hash, deletes the old plaintext password and clears its
  login cache. A rejected file is logged in diagnostics (`[CRED] REJECTED ...`).
- The dashboard has no password-change option for the super admin; the website is the only way.

## One-time setup
1. `pip install cryptography`
2. `python3 scripts/superadmin_credentials.py keygen --private ~/pisophone-superadmin.key`
   Keep the private key offline and backed up. It is the real secret; `*.key`/`*.pem` are
   git-ignored. If it is lost or leaked, every box must be reflashed with a new public key.
3. Commit the generated `esp32_firmware/include/SuperAdminPubKey.h`, rebuild and flash.
4. `python3 scripts/superadmin_credentials.py set --private ~/pisophone-superadmin.key`
   prints a random password once. Commit `website/update/credentials.json` and merge to `main`.

## Rotating after a leak
Run the `set` command again, commit, merge to `main`. Boxes update within about an hour.
Until a box syncs, the old password still works on it. Power-cycling it triggers a sync a minute
after Wi-Fi connects.

## Hardware test
1. Flash firmware with your public key. Log in as `superadmin` with the default password: works.
2. Publish credentials v1 to the test branch URL (or set `-DPISO_CRED_URL=...` in build flags to
   point at your branch's `update/credentials.json`). Wait ~1 min after boot.
3. Serial/diagnostics shows `[CRED] Super-admin password updated remotely to version 1`.
   Old default password is now rejected; the new one works. `/api/status` shows
   `"super_admin_managed":true`.
4. Reboot: still works (stored in flash). The dashboard has no change-password control.
5. Publish v2: box switches to the new password. Re-publish the v1 file: ignored (not newer).
6. Edit one character of `hash` in the JSON without re-signing: log shows REJECTED.

## Rollback
Remove `credentials.json` from the website (404 is treated as "nothing published") – boxes keep
their last accepted password. To return a box to a local password, factory reset it.

## Not covered
- Boxes offline for a long time keep the old password until they sync.
- Choose long random passwords (the script generates one): the hash is public, so a weak
  password could be guessed offline.
