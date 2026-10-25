# Security policy

PisoPhone handles money (coin credits) and controls a locked-down Android phone and a router, so security reports are taken
seriously.

## Reporting a vulnerability
Please **do not open a public issue**. Use GitHub's private reporting: the repository's **Security** tab, **Report a
vulnerability**. Include what you found, the firmware / app / router release it affects (`PISO_FW_VERSION` in `esp32_firmware/include/FirmwareVersion.h`,
the app version name, the router release in `setup/RELEASE`), and how to reproduce it. If private reporting is not available to you, contact the repository owner
through their GitHub profile and ask for a private channel before sharing details.

You can expect an acknowledgement within a few days. Please give us reasonable time to fix a problem before you publish it.

## What this protects against (and what it does not try to)
PisoPhone is built for a shop: the people to keep out are **customers and neighbours with a phone, a laptop and some time**, not
a determined attacker with lab equipment. In plain terms it is meant to stop:
- a renter getting out of the kiosk, changing its time, or getting free time, and someone on the shop's networks reaching the
  box's or router's admin pages or forging a coin credit (the box and phone sign everything they send each other, the customer Wi-Fi
  is walled off, and a box takes no coins until its factory password is changed);
- a copied message being played back later, and anyone who has the admin password installing software the owner did not sign.

It deliberately does **not** try to stop, and does not need extra machinery for:
- someone who can open the coin box or the router and has physical access (lock them away; the box's reset pin, its USB port and
  the coin acceptor's wires are theirs then). Boxes you sell can use the optional hardware hardening in `docs/PROVISIONING_SOLD_UNIT.md`;
- someone who has already joined the hidden shop Wi-Fi (its password is random and never given to customers), so the box's admin
  page is plain HTTP and a phone restart forgets which messages it has already seen;
- a customer cloning the address of a paying device on the customer Wi-Fi, which every coin Wi-Fi system shares as a weakness.
Keep it simple: a new protection should answer a way a customer or neighbour could really beat the shop.

## What is in scope
- Forging or replaying coin credits, time, or payments (box <-> phone protocol, `docs/api/gateway-coinslot.md`).
- Getting a renter out of the kiosk, or into Settings / Play Store / the package installer, without the admin.
- Installing firmware, router software or an app update that the owner did not sign (`docs/KEYS.md`).
- Reading or guessing a box secret, admin password or signing key.

Out of scope: attacks that need physical access to open the box and read its flash (use the encrypted production build for
units you sell, `docs/PROVISIONING_SOLD_UNIT.md`), and denial of service on a Wi-Fi network you control.

## Handling of keys (for maintainers)
- The owner signing key and the super-admin key (and the QR code made from it) are created on the owner's own computer and never committed; `*.pem` and `*.key`
  are ignored by git and `scripts/check_security_rules.sh` runs in CI. See `docs/KEYS.md`.
- If a key may have leaked, treat every update signed with it as untrusted: rotate the key, rebuild and re-flash boxes and
  routers with the new public key.
