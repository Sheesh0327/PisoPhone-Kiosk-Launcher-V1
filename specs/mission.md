# Mission

TechNet PisoHub lets a shop owner sell time with one coin slot, every day, without needing to be a programmer.

## What the owner gets

- **PisoPhone:** rental Android phones that lock and unlock by the minute. Customers pay with coins or a prepaid QR time card.
- **PisoWiFi:** customer Wi-Fi sold by the same coin, through a router the owner installs.
- **One coin box** (ESP32) that takes the coins and serves both.

## What the owner should never have to do

- Copy a key, password or token by hand as part of a normal setup. Setup goes through a guided installer and "Start here" pages. (Print-server pairing by code is still open; see the roadmap.)
- Read a technical manual to run a day in the shop.

## Two rules that decide every trade-off

1. **Money and time are never wrong.** The box records a payment before anything else. A payment is credited exactly once (by transaction id on the phone, by coin window id on the router). Nothing is lost in a power cut.
2. **Only software the owner signed is installed.** Firmware, router and app updates are signed. Signing keys never sit in the repository, in a cloud session or in CI outside the protected `production-signing` environment (`docs/KEYS.md`).

## How we work toward it

- A smaller, tested change beats a clever one.
- Real customers use the live release. Say plainly what was not tested.
- When unsure whether a change can hurt a running shop, ask the owner before shipping.

## Where to read more

- `CLAUDE.md`: the project's working rules and current release status.
- `docs/ARCHITECTURE.md`: how the parts fit together.
- `docs/REAL_WORLD_TESTING.md`: the hardware checklist and issue log.
- `CHANGELOG.md`: what changed for owners.
