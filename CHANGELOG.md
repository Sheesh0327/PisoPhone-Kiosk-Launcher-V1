# Changelog

What changed for people who run PisoPhone sites. The phone app, the coin-box firmware and the router software are versioned
separately (app: build number `1.0.<build>`; firmware: `PISO_FW_VERSION`; router: `setup/RELEASE`). Newest first.

## Unreleased
- **Rental phone lock screen for younger users.** The old logo animation is replaced by the TechNet mascot, big in the middle, bobbing,
  breathing and swaying in turning light rays over slow colour clouds. Eleven mascot stickers float, wiggle and pop around the edges
  (PisoWiFi, PisoPhone, gamer, laptop, cool, wink, and more), sparkles twinkle and gold coins spin down the screen. It is one canvas under
  the content, so the layout and every button stay as they were. It stands still, with the same pictures, on phones where background
  videos are off or not affordable (the admin's switch, low memory, power saving, low battery). The launcher still has its own logo video.
- Router 1.2.7: **fixes self-update undoing itself.** On a real router `pgrep -x pisoportal` said "not running" while the portal ran (found by
  `pidof`), so every update was undone. All process checks (portal, openNDS, the scheduler) now ask `pidof` and `pgrep`.
  Also: when a self-update is undone, the log, the Telegram message and `piso-setup status` now say **why** (the installer stopped
  with a code, the new portal program failed its self-test, or it was not running). The check no longer depends on `pgrep` alone (it also
  uses `pidof`) and waits up to 30 s for the portal program to start instead of 8 s. The installer's last lines are written to the log.
- **TechNet look on every screen people see.** One theme (navy, blue-to-teal buttons, gold accents) with the TechNet logo and mascot:
  the PisoWifi login page (new header, a welcome mascot, gradient buttons), the rental phones' lock screen (the mascot as a faint, gently
  moving watermark under the unchanged layout; it holds still where background videos are off), the coin box's sign-in page and
  dashboard, the phone setup website (with the PisoWiFi + PisoPhone banner), the Owner's Manual cover, the setup program and the
  printed Wi-Fi join card. Pictures are small PNGs; the website and the README carry the banner. The box's `/assets/brand.png` and
  `/assets/mascot.png` are new.
- **Product name: TechNet PisoHub** (by TechNet Solutions): the umbrella for PisoWiFi and PisoPhone (rental phones, with time cards as a PisoPhone feature). README, the Owner's
  Manual, the setup guides and the setup program's pages and printouts use it. Technical names are unchanged on purpose (the Android package, `piso-setup`,
  the PisoKiosk / PisoCoinBox / PisoWiFi networks, key prefixes) so deployed phones and boxes keep updating.
- **Owner's Manual** (`website/manual.html`): one print-ready page covering setup, rental phones, the dashboard tab by tab, how customers
  rent and use PisoWiFi, time cards, collecting revenue with the admin QR, phone admin console, router commands, updates,
  troubleshooting, safety and a quick-reference page. Checked by `scripts/tests/test_manual.py` (links, no outside resources, print rules).
- Setup program: a printable **Wi-Fi join card** for every unit (`pisophone-wifi-card-<date>.html`, saved next to the summary and
  opened from the last page). One QR code joins the hidden `PisoKiosk` network from a phone (Android and iPhone cameras read it),
  another opens the coin box's page; the same steps by hand and a privacy note are on it, on one A4 page. The QR codes are drawn by
  a small encoder inside the program, so nothing needs installing.
- Super admin now opens with **one QR code that only you hold**, instead of a password that had to be published from the website.
  On a box's login page there is a **Scan admin QR** button next to the admin password: it opens the phone's camera, the page reads
  your code and answers a one-time challenge from the box with a signature (the key in the QR code never leaves your phone), and
  the box opens the dashboard with vendor mode unlocked (a session of 15 idle minutes, one hour at most). Make the code with
  `python3 scripts/make_admin_qr.py`; it uses the key you already made, if any. Gone: the password feed
  (`website/update/credentials.json`, `scripts/superadmin_credentials.py set`), the hourly check for it, the stored password and
  the vendor password form on the dashboard. A box needs this firmware (and the public key in it) for the QR code to work; see
  `docs/api/superadmin-qr.md`. The setup script's comment about the super-admin password changed, so the router release is 1.2.5.
- Coin box firmware 3.5.0 (3.4.0 plus the admin QR login below): the release that carries the fixes listed below (payments, the revenue ledger, the update safety net, the list of undelivered payments). Boxes on 3.3.3 install it from the dashboard: Update > Install update.
- Phone app: a rental phone now connects only to the one Wi-Fi network its box paired it with (PisoKiosk). On Android 13 and newer
  the system itself is told to allow only that network (an SSID allowlist set by the device owner), and on every version any other
  saved network is removed, so a phone no longer wanders onto a neighbour's Wi-Fi or a hotspot. An admin maintenance window lifts the lock
  while it is open (so an admin can still use Settings) and it is back as soon as the window ends; a phone that has no
  kiosk network saved yet, or that leaves kiosk mode or has the app removed, is not held. (Needs the next APK; test S5.)
- Setup program: the setup is now a web page served by the program, so the computer that runs it needs no monitor: run it on a
  small Armbian, Parrot, Debian, Ubuntu or Raspberry Pi computer, open the address it prints on your phone and type the PIN.
  The window (Tk) is gone. On start the program detects the system and installs what is missing: `ssh` from the system's packages
  (root or passwordless sudo) and `esptool` with pip into its own folder, which also works on Debian 12 / Armbian systems whose
  Python is "externally managed". `sudo python3 pisophone_setup.py --install-service` makes the page start by itself at boot.
  The page is plain HTTP to the local network behind a PIN (five wrong PINs lock it for a minute; other sites' pages cannot reach
  it); `--local-only` serves the computer's own browser only. The phone setup link is shown on the page to open on a phone or
  laptop with internet. See `setup/README.md` ("Which network?") for how the phone reaches the page.
- Setup program: one click per unit. The program now writes the PisoPhone software to the coin box itself over USB (it detects
  the board, checks the image's size and checksum against the website's list, erases the board and verifies what it wrote), then
  sets up the router and pairs the two, and the last page has a "Set up the next unit" button that starts over for the next
  router and box: the shop name and public Wi-Fi name are asked every time and the passwords are new every time. The coin box
  step in the browser (`flash.html`) is gone: the setup program is the one way to set up a unit, next to the manual steps in
  `setup/MANUAL.md` (PlatformIO or esptool by hand). It needs `pip install esptool` (a ready-made program has it built in); on
  Linux the user must be in the `dialout` group. Phones are unchanged: the QR code, and the USB cable for phones that need it
  ("Finish over USB" stays for Android Go phones). The router's hint for a box that does not join now points to the setup program
  (router release 1.2.4).
- Coin box firmware: a payment the box gives up on is no longer lost without a trace. A coin that the phone never acknowledged was
  dropped from the box's queue after 24 hours (30 minutes when the queue was nearly full), the "clear payments" request dropped
  every queued payment, and a coin that arrived when the queue was full was only printed in a log that rolls over. Now each of
  these is first written to a list in the box's flash: the time, the amount, what it was worth, the phone, the transaction number
  and why it was given up on. The dashboard shows a red banner ("N payments (₱X) could not be delivered") with a Review dialog:
  refund the customer or add the time by hand, then press Handled. The list keeps the newest 16 and counts any older ones that no
  longer fit, so the total never shrinks until you mark them handled; it survives a restart and a firmware update, and only the
  owner's full wipe clears it. If the list cannot be written, the payment stays queued instead of being dropped. A payment that
  could not be delivered is not necessarily one the customer lost: a coin the box could not record may still have reached the
  phone, so check the phone before refunding. The diagnostics now also show how full the settings flash is (`nvs`), the number of
  waiting payments and two new counters.
- Coin box firmware: a bad firmware update is now reverted by the box itself. The box's bootloader could always go back to the
  previous firmware, but the Arduino framework told it that every update was fine before the new firmware had run a single
  line, so a firmware that crashed, hung or could not get online after an update stayed in charge until someone reflashed the
  box by USB. Now the new firmware is on trial after an update: it is kept after a minute if the payment storage works and (when
  the box was on Wi-Fi at the time of the update) it is back on Wi-Fi; if it has not passed after 5 minutes the box restores
  the previous firmware, once no coin session is open and no payment is waiting to be saved. A box that crashes or loses power
  during that first minute goes back to the previous firmware at once. This means: restarting the box (or a power cut) in the
  first minute after an update undoes the update, and a second update cannot be started during that minute (the dashboard says
  so; wait a minute and try again). Diagnostics show `ota_image_state` and `ota_last_update_rolled_back`, and a revert is
  recorded as the restart cause `ota-rollback`. Boxes with a custom bootloader (secure boot) must keep rollback enabled in it.
- Phone app: a command the box sent to a phone (a setting change, an arena or lock action, a status read) can no longer be copied
  off the network and played back later. Before, the phone only remembered the last 10 minutes of messages, so a copy played
  after that was obeyed again. Now a message must also carry a time within 2 minutes of the box's clock; a refused one is
  answered `403 STALE_TIMESTAMP`. Payments are unaffected (they are counted once by their transaction id). Until a phone has
  heard the box's time nothing is judged by time, so a box that has just restarted can still send commands.
- Coin box firmware: collecting the revenue no longer erases it. The lifetime coin and earnings counters now only go up (only the
  owner's full wipe clears them). Taking the money out is recorded as a collection (time, coins, amount, why, and the lifetime
  total at that moment; the last 16 are kept in the box and survive a restart, a firmware update and the operator's factory
  reset). The vendor page and the dashboard still show the amount counted since the last collection and still start again at
  ₱0, so nothing changes for the person collecting. The vendor can read the history from `/api/superadmin/collections`. Every
  way of resetting goes through the same step: the 5-minute window ending, finishing a collection or signing out on the vendor
  page, and the dashboard's vendor-password form (now labelled "Collect"). If the record cannot be written, nothing changes and
  the money stays in the counter. Signing out of the dashboard used to reset the revenue whenever a vendor window was open,
  even for a request that was not the vendor's; now only the vendor's own sign-out ends the collection. The router's
  reconcile check works with both old and new boxes.
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
