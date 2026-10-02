# Per-box keys and migrating boxes already in the field

Before this change every box and phone used one shared key (`PISOPHONE_HMAC_MASTER_KEY`) that is public
in the APK and firmware, so anyone could forge coin credits. Now each box has its own random 32-character
secret, and only the phones provisioned with it can talk to the box.

## New boxes
Nothing to do. The box makes its own secret on first start and uses it straight away. The dashboard's
**Install & Provision** link carries the secret to the provisioning website, which gives it to the phone
(`--es secret ...`). A phone without a secret still falls back to the old shared key and will not
authenticate with a new box.

## Boxes and phones already in the field
A box upgraded from older firmware that already has Wi-Fi saved starts in **legacy mode**: it keeps using the
old shared key, so nothing breaks the moment you flash it. The dashboard shows an orange banner. Migrate one
site at a time:

1. Update the **app** on every phone at the site (they keep working: with no box secret they use the old key).
2. Flash the new **firmware** on the box. Everything still works in legacy mode.
3. Make sure each phone has received its admin PIN from the box (it appears after the phone first connects;
   the PIN is the box admin password). The PIN is needed to re-provision a phone that is already set up.
4. In the box dashboard click **Switch to this box's own key** and confirm. From this moment the old key is
   refused. This cannot be undone (a factory reset gives the box a fresh key).
5. Provision each phone again with **Install & Provision** (website, admin PIN = the box admin password).
   A phone starts working as soon as it holds the box secret.
6. A phone with no box secret shows "this phone still uses the old shared key" in its diagnostics.

Customers cannot pay on a phone between steps 4 and 5 for that phone, so do it when the shop is quiet.

## Details
- Firmware: `SecretMode.h` (rules), `Config.cpp` (`provisionSecretMode`, `getSharedSecret`, `switchToOwnKey`),
  `POST /api/security/switch_key` (admin login). NVS keys `shared_secret` (box secret) and `sec_legacy`.
- Phone: `KioskSecurity.setBoxSecret` / `getSharedSecret`; the secret must be 16-128 characters of
  `A-Z a-z 0-9 _ . + = -` and can never be the old shared key.
- A box secret in settings is rejected if it is the old shared key or not valid.

## Still to do (see MASTER_PLAN S3)
The message format is unchanged (AES-CBC + HMAC, timestamp and `tx_id` replay checks). Planned later: AES-GCM
with a message counter, in-app pairing (so no secret passes through a link or adb), a secret per phone, and
wrapping the phone's stored secret with the Android Keystore. After every box is on its own key, delete the
legacy constants (`LEGACY_CRYPTO_SECRET`, `DEFAULT_SHARED_SECRET`) and the old-style license path, then empty
the allow-list in `scripts/check_security_rules.sh`.
