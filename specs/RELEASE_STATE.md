Moved out of `CLAUDE.md` section 2 so that `CLAUDE.md` stays byte-for-byte the same between releases (the first bytes of a chat can then
be read from the prompt cache; see `specs/GUIDE.md`, part B). **Update this file whenever a release goes out**, never `CLAUDE.md`.
It is read after the stable files, on demand: `specs/README.md` lists it with `specs/STATUS.md` (the board of features).

# Where the repository is now (release state) (2026-10-22)
- **Live (all at 100%, `main` = `beta` in content):** router **1.5.14** (feature 005: the spinning wheel, **off on every router** until the owner confirms the responsibility warning in the console; the free mini-game stays while it is off; also the 1.5.13 money-and-time fixes of feature 001); coin box firmware **3.11.1** (`PISO_FW_VERSION`; features 002 and 004: a separate image for each product, USB Wi-Fi setup, coin-counter hardening, the firmware money fixes; boxes update from their dashboards); the phone app stable build **495** (feature 003: the hidden lock screen no longer blocks touch in games on tablets). Router 1.5.14 was published at 0% by CI after the merge to `main` on 2026-10-22 and raised to 100% by the owner's order (`publish-router.yml`, rollout 100); `verify_release.py`: 0 FAIL.
- **Owed before anyone relies on these:** none of router 1.5.7-1.5.14, firmware 3.7.0-3.11.1 or the phone app changes since the `kioskmanager.local` fallback was removed has run on real hardware. The checks are in `docs/REAL_WORLD_TESTING.md`: **RS28-RS31** (firmware 3.11.1), **RS32** (router 1.5.13, clock jump and power cuts), **RS33** (tablet touch, use `adb` if touch is still dead), **RS34** (the wheel: off after update, the warning dialog, a spin changes the time once, a power cut mid-spin, off at once). **The warning text of the wheel has had no legal review (roadmap 8.3): nobody should switch the wheel on in a shop before it does.** Earlier unproven router work: modem block; guests apart and rental phones reaching only the router and the coin box (bridge rules in `router/piso_isolation.sh`, they need `kmod-nft-bridge`); the router login pages and the console closed on Wi-Fi, `piso-setup wifi-admin`; one admin page: `http://admin.hub`, the console as the front page on port 80 with LuCI on 8080, and the coin box's page inside its Rental phones tab through `boxview.rs`, port 81/8082.
- **The wheel (feature 005), in one paragraph:** the router decides every spin (fixed published odds, default 13 of 20 slots lose, 5 pay 2x, 1 pays 3x, 1 pays 5x: 35% win, 90% back); a spin is journalled before the session changes and applied once per spin id (also after a restart); limits per device per day (bet 1 to 10 minutes, 5 kept after a loss, 10 spins, 10 s wait, 60 minutes won) are the router's; coins and `revenue.csv` are untouched, spins go to `wheel.log`. It cannot be on without the record `wheel-confirm.txt`, which only the console's turn-on request writes (exact phrase `I TAKE RESPONSIBILITY` and the warning version); a setting typed into a file does nothing. Code: `tools/pisoportal/src/wheel*.rs`, `wheel.html`; spec and evidence: `specs/features/005-spinning-wheel/`.
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
