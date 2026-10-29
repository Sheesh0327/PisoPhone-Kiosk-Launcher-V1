# Mission

TechNet PisoHub lets a shop sell time with one coin slot, every day, without anyone needing to be a programmer on site.

> **Changed 2026-10-29.** This file used to say the goal was *"a shop owner who is not a programmer can open a shop, add
> phones and run it"*. That described a customer who does not exist. **The owner builds and installs every unit and sells
> them finished.** Nobody else sets one up. A great deal of the roadmap inherited from the old wording and has been re-sorted
> or parked because of this change (`specs/spec_03_overhaul.md`, section 0a).

## Who does what

- **The owner** assembles the coin box, installs the router software, pairs the phones, and delivers a working unit. They have
  a computer, SSH and the setup program, and they know the system. Setup does not have to be short; it has to be **reliable
  and repeatable**.
- **The shop** runs the unit every day and takes the coins. Nobody there configures anything.
- **Nobody else** installs this today. If that changes — resellers, volume — the parked setup work comes back
  (`specs/features/037-setup-front-door/`, `038`, `042`).

## What the owner gets

- **PisoPhone:** rental Android phones that lock and unlock by the minute. Customers pay with coins or a prepaid QR time card.
- **PisoWiFi:** customer Wi-Fi sold by the same coin, through a router the owner installs.
- **One coin box** (ESP32) that takes the coins and serves both.

## What must be true once a unit leaves the owner's hands

This is the real test now, because the owner is not standing in the shop:

1. **Money and time are never wrong.** The box records a payment before anything else. A payment is credited exactly once (by
   transaction id on the phone, by coin window id on the router). Nothing is lost in a power cut.
2. **It keeps running unattended**, and when something breaks it **says so** — to the owner's phone if Telegram is set up, and
   in `alerts.txt`, the console and the Check-up when it is not.
3. **It can always be fixed from a distance.** A unit the owner cannot update is a unit they must physically collect. An
   update path that works is not a convenience; it is the difference between a bug and a recall.
4. **Only software the owner signed is installed.** Firmware, router and app updates are signed. Signing keys never sit in the
   repository, in a cloud session or in CI outside the protected `production-signing` environment (`docs/KEYS.md`).
5. **A unit that was sold cannot be read.** A box in someone else's hands must not give up its secrets to a flash reader
   (`docs/PROVISIONING_SOLD_UNIT.md`, and feature 043 for the part that is not yet true).

## How we work toward it

- A smaller, tested change beats a clever one.
- Real customers use the live release. Say plainly what was not tested.
- **Verification is the bottleneck, not features.** The owner's own bench time is the scarcest thing in this project; spend it
  on checks that have never run before spending it on anything new (`docs/REAL_WORLD_TESTING.md`).
- When unsure whether a change can hurt a running shop, ask the owner before shipping.

## Where to read more

- `CLAUDE.md`: the project's working rules.
- `specs/spec_03_overhaul.md`: the current epic, its decisions and what is parked.
- `docs/ARCHITECTURE.md`: how the parts fit together.
- `docs/REAL_WORLD_TESTING.md`: the hardware checklist, ordered into sessions by feature 039.
- `CHANGELOG.md`: what changed for owners.
