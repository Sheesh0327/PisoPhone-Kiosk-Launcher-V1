# Security policy

PisoPhone handles money (coin credits) and controls a locked-down Android phone and a router, so security reports are taken
seriously.

## Reporting a vulnerability
Please **do not open a public issue**. Use GitHub's private reporting: the repository's **Security** tab, **Report a
vulnerability**. Include what you found, the firmware / app / router release it affects (`PISO_FW_VERSION`, the app version
name, `PISO_RELEASE`), and how to reproduce it. If private reporting is not available to you, contact the repository owner
through their GitHub profile and ask for a private channel before sharing details.

You can expect an acknowledgement within a few days. Please give us reasonable time to fix a problem before you publish it.

## What is in scope
- Forging or replaying coin credits, time, or payments (box <-> phone protocol, `docs/api/gateway-coinslot.md`).
- Getting a renter out of the kiosk, or into Settings / Play Store / the package installer, without the admin.
- Installing firmware, router software or an app update that the owner did not sign (`docs/KEYS.md`).
- Reading or guessing a box secret, admin password or signing key.

Out of scope: attacks that need physical access to open the box and read its flash (use the encrypted production build for
units you sell, `docs/PROVISIONING_SOLD_UNIT.md`), and denial of service on a Wi-Fi network you control.

## Handling of keys (for maintainers)
- The owner signing key and the super-admin key are created on the owner's own computer and never committed; `*.pem` and `*.key`
  are ignored by git and `scripts/check_security_rules.sh` runs in CI. See `docs/KEYS.md`.
- If a key may have leaked, treat every update signed with it as untrusted: rotate the key, rebuild and re-flash boxes and
  routers with the new public key.
