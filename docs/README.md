# Documentation

An index of the documents in this repository, grouped by who reads them. The owner's step-by-step pages (set up a shop, add a phone)
are on the website: <https://pisophone.pages.dev/start/>. What the product is and how the folders are laid out: [the root README](../README.md).

## Owner (runs a shop)
- [setup/README.md](../setup/README.md): what the setup program does, step by step, and how to set up by hand.
- [website/manual.html](../website/manual.html): the owner's manual: setup, daily use, features, revenue and troubleshooting (print-ready).
- [ROUTERS.md](ROUTERS.md): which routers work, and which CPU families are built.
- [REFERENCE.md](REFERENCE.md): protecting the coin slot against fake pulses, phone slot licensing, print server pairing, the super-admin QR code.
- [PROVISIONING_SOLD_UNIT.md](PROVISIONING_SOLD_UNIT.md): hardening a coin box you sell (Secure Boot V2 and flash encryption).

## Developer
- [ARCHITECTURE.md](ARCHITECTURE.md): the five parts, a coin payment step by step, the phone's session, who talks to whom, guest Wi-Fi payments.
- [../CONTRIBUTING.md](../CONTRIBUTING.md): development setup, branches, the checks to run for each area, code style, commit messages, the rules that are easy to break.
- Each part has its own README with layout, build and test commands:
  [app](../app/README.md), [esp32_firmware](../esp32_firmware/README.md) (and its [dashboard files](../esp32_firmware/web/README.md)),
  [router](../router/README.md), [protocol](../protocol/README.md), [tools](../tools/README.md) (and [pisoportal](../tools/pisoportal/README.md)),
  [setup](../setup/README.md), [scripts](../scripts/README.md), [website](../website/README.md).
- [HARDENING.md](HARDENING.md): hardening items and risks from the 2026-10-17 audit that were still open when it was written.
- How work is planned and checked: [../CLAUDE.md](../CLAUDE.md), [../constitution.md](../constitution.md), [../specs/README.md](../specs/README.md).

## Releases and keys
- [KEYS.md](KEYS.md): the owner key that signs firmware and router updates, signed firmware updates, per-box secrets.
- [RELEASE.md](RELEASE.md): the release checklist, including the steps only the owner can take.
- [DEPLOY.md](DEPLOY.md): first run of one box and a phone or two, the owner settings on a phone, what CI does, where APKs and firmware are published.
- [../CHANGELOG.md](../CHANGELOG.md): what changed for people who run shops, newest first.
- [../specs/RELEASE_STATE.md](../specs/RELEASE_STATE.md): what is live now and what has never run on real hardware.
- [../SECURITY.md](../SECURITY.md): how to report a vulnerability.

## Hardware testing
- [REAL_WORLD_TESTING.md](REAL_WORLD_TESTING.md): the checklist for real boxes, phones and routers, the checks still owed, and the issue log.
  `tools/checklist/build.py` turns it into a tickable page for a phone ([tools/README.md](../tools/README.md)).

## Design and API
- [design/GUI_SPEC_OWNER_AND_WIFI.md](design/GUI_SPEC_OWNER_AND_WIFI.md): the look and behaviour of the PisoWiFi portal, the coin box dashboard, the router console and the website setup page.
- [design/GUI_SPEC_PISOPHONE_KIDS.md](design/GUI_SPEC_PISOPHONE_KIDS.md): the kiosk screens on the rental phone.
- [api/gateway-coinslot.md](api/gateway-coinslot.md): the coin box's API for a router or another trusted device on the LAN.

## Keeping this folder
`RELEASE.md`, `KEYS.md`, `DEPLOY.md` and `REAL_WORLD_TESTING.md` are read by workflows and by the signed router script by path:
do not move or rename them. `design/` and `api/` are named in source comments. A new document goes in this index.
`python3 scripts/check_links.py` finds a link that points to a file that is gone.
