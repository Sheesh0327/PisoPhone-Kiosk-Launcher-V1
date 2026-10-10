# Changelog

What changed for people who run PisoPhone sites. The phone app, the coin-box firmware and the router software are versioned
separately (app: build number `1.0.<build>`; firmware: `PISO_FW_VERSION`; router: `setup/RELEASE`). Newest first.

## Unreleased
- Developers (coin box firmware): the background task that delivers coins to phones no longer changes the player-account table or
  the phone slots itself. When a phone confirms a coin it now hands the confirmation to the main loop, the only task that owns
  that state, so the two can no longer change the account table at the same moment. A confirmation that cannot be handed over
  is harmless: the coin stays queued and the box offers it again 10 seconds later.
- The router already looked for signed updates every hour and installed them when no customer was online (`piso-setup auto-update
  on|off`, on by default). `piso-setup status` now also checks that the hourly job is scheduled with the scheduler running, and
  that it ran in the last 3 hours, so a router that stopped checking is noticed. Release 1.2.3.
- Developers: the router setup script is now written in 13 parts in `setup/src/` (settings, preflight, box, agreement checks,
  updates, ...). `setup/piso-setup.sh.in` and `setup/piso-setup.sh` are generated from them and are byte-for-byte what they were,
  so nothing changes on a router. CI fails if a generated file is out of date and says to edit the parts.
- `piso-setup verify` compares the values that live in more than one place and names the ones that disagree: the gateway key,
  the coin box's address, the PisoKiosk password on every radio, the customer Wi-Fi name (settings, radios, openNDS, portal),
  the portal's address and port, the box network's password, the setup summary, and (box on) the box's gateway key, admin
  password and "Set up a phone" link. `status` includes it, and the hourly update check runs it and tells Telegram once when
  something starts to disagree, and once when everything agrees again. Needs router release 1.2.2 or newer.
- Nobody types the PisoKiosk Wi-Fi password into the phone setup page any more. The router gives it to the coin box (setup,
  `piso-setup kiosk-wifi`, every router update, and the hourly update check repairs a box that lost it), and the box's
  "Set up a phone" link carries it. A phone can no longer be set up with a stale or mistyped password, which installed fine but
  never found its box. Needs box firmware 3.3.3 and router release 1.2.1 (sign and publish it like 1.2.0); an older box just
  keeps asking for the password, and `piso-setup status` says so.
- A paired phone could be refused for good (shown offline on the dashboard, no arming) after it had once talked to the box with
  a wrong clock: the box kept that bogus time as the phone's "newest request" and refused every later request as older. The box
  now ignores a stored time that lies beyond what its master clock could have produced and replaces it with the next accepted
  request (`include/ReplayCheck.h`, tested on a PC). Needs the new firmware (raise `PISO_FW_VERSION` to ship it).
- Removing the kiosk from a phone (website, "Remove from a phone") no longer asks for the admin PIN. The phone accepts the
  removal while USB debugging is on, which it is when the setup computer is connected. With USB debugging off, as on a
  rental phone in normal use, the DEPROVISION broadcast still needs the PIN.
### Coin box and phone app: fewer refusals
- A phone with a wrong date (typical on a new phone on a Wi-Fi without internet) is no longer refused as "stale". The box puts
  its time in every answer, including refusals and the answers to phones that are not paired yet, and the phone signs with the
  box's time; a request refused only for its time is retried once with it. Needs both the new firmware and the new app.
- The box now says why it refuses a phone (`STALE_TIMESTAMP`: heals by itself; `BAD_SIGNATURE`: the phone has another box
  secret) and tells a not-yet-paired phone whether the box accepts its key (`auth_ok`), so a wrong key shows right after
  setup, not at the first coin: the phone says to press Save on the box dashboard (which sends the key) or to set it up again.
### Phone app
- Animated background: the logo animation behind the lock screen and the home screen (one 15-second muted loop, 0.6 MB). A video exists only while it is on screen: the player is released when the screen
  leaves the front, while the lock screen covers the home screen, on a low battery (below 15 % and not charging), in battery
  saver, and always on low-RAM (Android Go) phones, which show the still first frame instead. An admin can switch them off
  in the vault (Background videos).
- First start of a freshly set-up phone: Play Store opens for the admin to sign in. When the admin returns to the kiosk, the
  phone's pre-installed apps are disabled, or only hidden from the launcher when the phone needs them (home screen, dialer,
  keyboard, web view, Google services, the vendor's own apps, text-to-speech), so only apps somebody installed show. Phones
  already in use when they get this update are not touched. De-provisioning re-enables everything it disabled, and the
  `com.pisophone.kiosk.RESTORE_SYSTEM_APPS` broadcast (admin PIN) does the same on demand.
- While the admin is in Play Store, Settings or the package installer, both kiosk overlay windows (the lock screen and the
  floating pill) are now removed from the screen, not just hidden, and come back when the admin returns to the kiosk or the
  maintenance window ends. Before, the invisible full-screen lock window stayed attached.
- The coin button says why a coin slot could not be armed (`NOT PAIRED YET`, `BOX REFUSED PHONE`, `BOX SETUP NEEDED`,
  `BOX NOT FOUND`, `CAN'T REACH BOX`, `BOX ERROR`) instead of showing `COINSLOT BUSY` for every failure. Only a coin slot that
  really is in use by another device says busy.

## Slot licensing removal and one-tap updates (firmware 3.3.0, router 1.1.0)
### Coin box
- Slot licensing is gone: all 10 phone slots are always available. The slot license keys, the "Box code" and the "Add phone
  slots" dialog were removed. Boxes that were limited to fewer slots unlock all of them on update.
- Updates over the air: the dashboard's **Install update** installs the build published on https://pisophone.pages.dev.

### Phone app
- Removed the unused "slot license expires" warning.
- The floating pill hides while the admin is in Play Store, Settings or the package installer, so those apps accept touches.
  It returns when the admin is back on the kiosk screen or the maintenance window ends.

### Router
- `piso-setup update`, run on the router, installs the newest release the owner signed from the website (the same as
  `piso-setup self-update`). A new copy of `piso-setup.sh` run with `update` still installs its own files.

### Release process
- Merging `beta` into `main` publishes the app, the firmware (`firmware-images.yml`) and the router update
  (`publish-router.yml`). See `docs/RELEASE.md`.
