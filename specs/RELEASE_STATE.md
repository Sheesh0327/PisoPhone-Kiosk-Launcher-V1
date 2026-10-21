Moved out of `CLAUDE.md` section 2 so that `CLAUDE.md` stays byte-for-byte the same between releases (the first bytes of a chat can then
be read from the prompt cache; see `specs/GUIDE.md`, part B). **Update this file whenever a release goes out**, never `CLAUDE.md`.
It is read after the stable files, on demand: `specs/README.md` lists it with `specs/STATUS.md` (the board of features).

# Where the repository is now (release state) (2026-10-18)
- **Live (all at 100%):** router **1.5.12**; coin box firmware **3.9.0** (`PISO_FW_VERSION`; boxes update from their dashboards); the phone app from CI (dev builds from `beta`,
  stable builds from `main`; the number is the CI run number). **Routers 1.5.7-1.5.12 have never run on a real router** (modem block; guests apart and
  rental phones reaching only the router and the coin box: bridge rules in `router/piso_isolation.sh`, they need `kmod-nft-bridge`; the router login pages
  and the console closed on Wi-Fi, `piso-setup wifi-admin`; one admin page: `http://admin.hub`, the router's own DNS name, the console as the front page on
  port 80 with LuCI on 8080, and the coin box's page inside its Rental phones tab through `boxview.rs`, port 81/8082). Firmware 3.7.0-3.9.0 (phone profiles; the
  box page's light/dark button and embedded view; no mDNS any more) and the phone app without the `kioskmanager.local` fallback have not been tried on a box
  or a phone. The hardware checks are in `docs/REAL_WORLD_TESTING.md` (RS22 and later).
- **Branches:** `main` is production, `beta` is where work happens. After CI rebuilds on each branch their generated router
  files differ byte for byte, so merges to `main` keep `main`'s generated files (section 5).
- **Production hardening pass** (`docs/maintainers/HARDENING_PLAN.md`: what was fixed, what is open, the hardware checks): router
  1.5.5 went to 100% before the owner's hardware checks were done, so those checks are still owed. After 1.5.5 paying phones were
  reported online with no internet; the fix in 1.5.6 (the portal lets on a paid customer openNDS forgot; the 1.5.5 guest
  private-network rules removed) was tried by the owner on a router and works. Phone app: paid time starts at
  Done (coins are held while the lock screen waits; `PaymentRepository.startHeldClock`), the power menu is available only
  inside an admin maintenance window (`KioskPolicyManager.applyPowerMenu`), and the floating pill has a Force close app button
  (`AppCloser`); none of these has run on a real phone.
- **Recent work:** each router has its own Wi-Fi names (`PisoKiosk-xxxx`, `PisoCoinBox-xxxx`, 1.5.0); coin page: vouchers, pause
  (from 10 pesos, once, 1 kbit/s and 2 MB), mini-game, "send a file to the owner" (relayed to the print server's inbox);
  quicker "you are online" after Done; the setup program (`setup/pisophone_setup.py`) does the whole install and has Owner tools
  (time cards, admin QR, recovery code) and a read-only Check-up; "Start here" pages (`website/start/`); codebase cleanup of the
  setup program, tests, scripts and the phone app.
- **Setup simplification plan** (nobody opening a shop should handle a key): Start pages, README front door and the Check-up are
  done. **Print server pairing** (a code instead of copying `send.key`) is only specified (`docs/api/print-pairing.md`): it needs
  `/api/inbox/pair` on the print server, which is a separate project, not in this repo. Until then the key is copied by hand.
- **Known and open:**
  - cleanup skipped on purpose: firmware dead code (needs a firmware release); Android: `MainActivity` `isLockTaskActive`, unused
    constructor parameters, stale lint-baseline entries, `device_admin.xml`, legacy launcher icons, `ACTION_FACTORY_RESET`,
    a duplicate `getLocalIpAddress`.
- **Not in this repo:** the print server (its own project; this repo only specifies how the router talks to it).
- The hardware test checklist and issue log is `docs/REAL_WORLD_TESTING.md`; what changed for owners is `CHANGELOG.md`.
