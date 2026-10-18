# Changelog

What changed for people who run PisoPhone sites. The phone app, the coin-box firmware and the router software are versioned
separately (app: the CI build number, shown as hundreds.tens.ones, build 466 is 4.6.6; firmware: `PISO_FW_VERSION`; router: `setup/RELEASE`). Newest first.

## Unreleased
- Setup program, **time-card sheets: the "3 hour play card" line is no longer covered by the QR code.** The QR code sat on top of the second line of every card when printed or viewed; the title lines now sit above it and the code is a little smaller (38 mm, still well within what a phone reads). Reprint any sheet made before this. No router, box or app update is needed: it is in the setup program on the computer (download the newest setup page). Checked by laying out a sheet in a real browser and confirming that no element of a card overlaps another.
- Router 1.5.12, coin box firmware 3.9.0 and the phone app, **one name for the admin page: `http://admin.hub`; no mDNS any more.** The router now answers the name `admin.hub` itself (its own DNS, so it works on any device that gets its network from the router: a computer on the LAN cable, a phone on the kiosk Wi-Fi; if a browser uses its own secure DNS, `http://<router address>` still works; type the full `http://admin.hub`). The console becomes the router's front page on port 80, so `http://admin.hub` opens the admin page with the coin box's page inside *Rental phones*; the router's own settings page (LuCI) moves to port 8080 (8443 for https) and is the *Advanced* link in the console. Every router that never chose is moved by the update; `piso-setup console off` is remembered as the owner's choice and the update keeps it. New setups start with the front page. The Wi-Fi block of the router's login pages (1.5.10) now covers the console and the coin box page too (ports 80, 81 on a front-page router; 8081, 8082 otherwise): from a rental phone's Wi-Fi they do not open, from a computer on a cable they do; `piso-setup wifi-admin on` opens them on Wi-Fi again. Firmware 3.9.0: the coin box no longer announces itself by mDNS (`kioskmanager.local` is gone; it is reached at its address or inside the console). Phone app: the box is looked for at its fixed address and by its broadcast only (the `kioskmanager.local` attempt is removed). Checked by the router setup tests (288 checks: the name entry, the front page for a router that never chose, the owner's choice kept, a failed switch does not stop an update, the Wi-Fi block's port list) and the scanner's unit test; the app and firmware are compiled by CI. Not tried on a router, a box or a phone: open `http://admin.hub` from a computer on a cable (and on the kiosk Wi-Fi only after `wifi-admin on`), the Rental phones tab, and `http://<router>:8080` for LuCI; update a box and check that a phone with the new app still finds and pairs with it.
- Router 1.5.11 and coin box firmware 3.8.0, **one admin page: the router console now holds the coin box's page in its Rental phones tab.** Open the console as before (the router's address, port 8081, the router password) and *Rental phones* shows the box's own page (Overview, Settings, Tools, Vendor) inside it, in the same look and with the console's light or dark switch applied to it; there is one sign-in and one address. The router passes the box's page through on the console's port plus one (8082) and adds the box's admin password itself (the router keeps it in its settings; if it does not know it, the tab says so and the box can still be opened on its own, the link under the page). Only a browser signed in to the console gets the box's page, from the console's address only; the box still cannot be reached by guests or rental phones. Firmware 3.8.0 gives the box's page a light/dark button of its own (it follows the device until used) and an embedded view (no header or sign-out when shown in the console). A box that is still on 3.7.x works in the tab too, but keeps following the device's theme instead of the console's switch until it is updated from its dashboard. `piso-setup lock-admin` now also covers the new port. Checked by the router's console tests (pass-through with login added, console cookie kept from the box, post from another page refused, no box password, framing), a real-browser test of the tab and its theme message, a real-browser test of the box page's theme and embedded view, and the firmware host tests. Not tried on a router or a box: open the console, Rental phones, and use the box's page there (a setting, a pairing, an update); try the light/dark switch; open the link under the page to see the box's page on its own.
- Router 1.5.10, **PisoPhone: rental phones can reach the router and the coin box, nothing else; the router's login pages are not offered on Wi-Fi.** On the kiosk Wi-Fi a phone no longer reaches other phones, the print server or any computer, and no computer or device reaches a phone, except the coin box (phones and the coin box still talk to each other, and phones still get internet, DHCP and DNS from the router). The router's own login pages (SSH, LuCI: ports 22, 80, 443, 8080, 8443) no longer answer on Wi-Fi: use a computer on a LAN cable; `piso-setup wifi-admin on` lets Wi-Fi in again, `off` closes it, `status` shows it. The owner's console page keeps its own port and its signed login and is not affected. Phones that come in by Wi-Fi are also refused the modem's network (192.168.x.x and the other private ranges behind the modem); a computer on a LAN cable keeps its way to the modem's pages; the internet is open as before. This is done by two small firewall tables on the router (guests apart, phones apart), put in place by setup and update and again at every firewall reload, and a firewall rule `Kiosk-Wi-Fi-No-Private`. They need the router's bridge firewall support (`kmod-nft-bridge`); without it the update logs that it is off and `piso-setup diag` shows it; nothing else changes. A shop whose owner uses the kiosk Wi-Fi to reach the router's login page, a computer or a phone with adb must use a LAN cable or run `piso-setup wifi-admin on`. Not tried on a router: with two phones on PisoKiosk neither can open the other, both arm a coin on the box and unlock, neither opens the router's login page or `http://192.168.1.1/`, a computer on a cable still opens the router's login page, and `piso-setup diag` lists the tables `piso_guest` and `piso_kiosk`. Checked by the router setup tests (280 checks: the firewall rule, the rules' text for the box, with the box not yet paired and with `wifi-admin on`, a router that cannot load them) and by the real `nft` syntax check of the generated rules.
- Router 1.5.9, **PisoWiFi: guests cannot reach each other, or the private networks behind the modem.** (1) A rule on the guest bridge now stops anything passing from one guest to another, also between the 2.4 and 5 GHz Wi-Fi and from an access point plugged into a guest port (the Wi-Fi isolation of each radio alone did not cover those). Guests still reach the router's DHCP, DNS and the portal, so paying and *Send a file to the owner* work as before; the portal passes the file on to the print server, guests never connect to the print server. It needs the router's bridge firewall support (`kmod-nft-bridge`); without it the update logs that the block is off and `piso-setup diag` shows it, and nothing else changes. (2) The guest network is also refused every private and link-local address on the modem's side (10.x, 172.16-31.x, 192.168.x, 169.254.x, 100.64-127.x), not only the modem's own address; the internet is not affected. A shop whose internet provider hands out private addresses for something customers need (for example IPTV) must tell me before updating. Not tried on a router: from one guest phone ping or open another guest phone (must fail, on 2.4 and 5 GHz), open `http://192.168.1.1/` and another 192.168.x.x computer (must fail), open any website and send a file through the portal (must work); then `piso-setup diag` lists the rule under *guests cannot reach each other*. Checked by the setup tests (the firewall rules, the bridge rule text and a router that cannot load it).
- Router 1.5.8, **PisoWiFi: pause from ₱10.** A customer whose session has paid ₱10 or more in all (top-ups and vouchers add up) can now pause, once per session; it used to need ₱20. Below ₱10 the Pause button stays hidden and the page says how many pesos more unlock it. The pause itself is unchanged (1 kbit/s and 2 MB while paused, time kept for a day). It is the default of `PAUSE_MIN_PESOS`; a router whose owner set that value in `/etc/coinslot.conf` keeps it, and `PAUSE_MIN_PESOS=0` lets anyone with time left pause. Checked by the portal's tests (the default is 10; the flow test uses its own value and covers the hidden button, the missing amount and the pause).
- **Phone app: a Force close app button in the floating pill.** When an app a customer is using hangs, open the pill and tap *Force close app* (twice: the second tap confirms, because what the app has not saved is lost). The phone goes Home, the app is stopped for good, and it is opened again. It acts on the app in front when the phone can see it (Usage Access granted, the same as for Boost), otherwise on the app the kiosk opened last; if there is none it says so. The kiosk itself and core system apps are never touched. As device owner the app is fully stopped; otherwise it is stopped as far as Android allows an ordinary app to do it. Checked by unit tests of the choice of the app; the buttons and the stop run only on a phone: hang or open any app, tap the button twice, and the app must come back fresh.
- Router 1.5.7, **PisoWiFi: guest devices can no longer reach the modem.** A phone on the guest Wi-Fi could open the modem's own pages (its login page and settings, usually 192.168.1.1) because guests are forwarded to the internet through the modem. The router's firewall now refuses traffic from the guest network to the modem's address: 192.168.1.1 and the router's own default gateway (whatever the modem's address is on that line; an address can be added with `MODEM_IP='...'` in `/etc/piso-setup.conf`). Everything else is as before: guests still reach the internet, and openNDS keeps its own rules for paying guests (router 1.5.5 replaced them, which was taken back in 1.5.6). It is one narrow firewall rule, applied by the update. Not tried on a router: from a paying guest phone open `http://192.168.1.1/` (it must fail) and any website (it must work); if a guest phone has no internet after the update, run `piso-setup diag`.
- Coin box firmware 3.7.0, **phone profiles: a rate of its own for each kind of rental phone.** In the box dashboard, *Settings*, *Phone profiles*: make a profile for each kind of device (a basic phone, a gaming phone, a tablet) with its own minutes per ₱1, up to 8, and give each phone slot a profile. A coin put in for a phone is worth that phone's rate; its lock screen shows it too. A phone with no profile, or whose profile was removed, uses the *Coin rate* above (now the *Standard* rate), so a box that does not use profiles works exactly as before. Changing a profile tells the phones that use it at once; coins already paid keep the minutes they were worth when they were put in. The phone app is unchanged. PisoWiFi is not part of this (its prices are in the router console). Checked by a host test of the profile rules (names, rates, the saved text, a damaged saved text, a deleted profile); the box's handlers and screen run only on a box (CI compiles them): make two profiles, give them to two phones, insert the same coin for each, and each must get its own minutes.
- Router 1.5.6, **PisoWiFi: a customer who paid is let on again by the router, and the 1.5.5 private-network rule is taken back.** Reports after 1.5.5: the page says *You are online* but the phone has no internet. Two changes:
  - The portal now puts a device with paid time back online by itself. Every minute it looks at the clients openNDS lists, and one that has paid time on the roll but is not let on is granted again (openNDS forgets its sessions when it restarts, which an update does, or when it crashes; before, only opening the page again put the customer back online, and the page said *online* from the paid time alone). A device in the middle of paying or paused is left alone.
  - Router 1.5.5 gave openNDS its own list of rules for paying guests (private networks blocked, then everything allowed). It was the only change to guest traffic in that release and it had not been tried on a router. An update to 1.5.6 removes that list: paying guests are back under openNDS's own rules, as in 1.5.4. Keeping guests off the modem's page and other private networks stays open (HARDENING_PLAN.md) until it can be tried on a router.
  - Not tried on a router. If a phone still has no internet after paying, run `piso-setup diag` and send the result (it now shows the client, the rules openNDS built for guests and the last grants).
- **Phone app: switching off is for the admin only.** While the phone is pinned as a kiosk, holding the power button does not offer Power off or Restart. When an admin opens a maintenance window (the admin bypass, the same one that opens Settings), the power menu works for as long as the window is open, then it is hidden again. The app now sets this itself instead of relying on the phone maker's default (Android 9 and newer). It cannot stop a forced restart (holding the button for 10 to 15 seconds), pulling the battery, or a recovery-mode wipe with the hardware keys; those need a locked case and a phone with a locked bootloader. Not tested on a real phone: with no admin window, hold the power button and confirm no menu appears; open the admin bypass and confirm the menu appears, then closes again after the window ends.
- **Phone app: paid time starts at Done.** A coin put in while the phone is locked used to start the countdown at once, so the seconds spent putting in more coins were taken from the minutes paid for. Now the time is kept and the clock starts when the customer taps Done (or when the coin window ends); time that is already running is extended as before. Complimentary, admin and card sign-in time still starts at once. Checked by unit tests of the payment store (held time adds up, the clock starts once, a checkpoint or an admin deduction or bypass keeps held time); the app's own flow runs only in CI, so try it on a phone: insert two coins a minute apart, tap Done, and the full paid time must be left.
- **Production hardening pass** (all found by an audit of the money, update and setup paths; each fix has a test). Router **1.5.5**, coin box firmware **3.6.1**, phone app:
  - Router, **no lost coins when the box's link drops**: a window whose box never confirmed it was done is no longer acknowledged (an acknowledgement erases every coin the box holds); recovery waits until the box is idle, credits coins the box counted after the router stopped looking, then acknowledges. A window that saw no coin because the link was down is kept for recovery instead of being closed as empty.
  - Router, **time bought right after a power cut is kept**: the router starts with a stale clock until it reaches a time server; when the clock then jumps, sessions paid in between move with it (before, an hour paid could vanish at once).
  - Router, a voucher code is marked spent before its time is added (a crash in between could let it be spent twice); a damaged byte in the session or voucher files no longer empties them; window records are flushed to flash; another site's page cannot drive a guest's coin window.
  - Router setup: a full flash no longer empties the settings file (every password and the gateway key); `/etc/coinslot.conf` keeps the owner's own lines (prices, `SEND_HOST`, ...); stopping a pairing or a setup halfway (Ctrl-C, lost SSH) puts the coin box network back and starts openNDS again (guests were online for free); paying guests can no longer reach the modem or other private networks; an update must answer on the portal address to count as healthy; text files are replaced only when all are complete.
  - Coin box: a pin on the flash bus (or the C3's USB pins, or one pin for two jobs) is refused in Settings, and a stored bad pin falls back to the default at start (it crashed the box at every start); the Settings form's phone-app port, relay type and LED type now save (wrong field names); a refused save changes nothing and says why; the relay test switches itself off after 10 s; the pulse counter hand-off is locked (a pulse could be lost); no coin is dropped 49.7 days after a start; controller payments count as revenue; a factory reset saves the last coins' revenue first; secrets made at first start use the chip's real entropy; signatures are compared in constant time; a captured heartbeat can no longer clear queued coins; anyone on the network can no longer sign the owner out of super admin.
  - Phone app: a second "finish" (Done tapped as the coin window ran out) no longer locks a customer who has paid time.
  - CI: the router signing job signs only the files of the commit that was approved.
- **Setup program: fewer questions, a Check-up.** The first page has a *My phone can't open this page* help; in a terminal that shows colours the program draws a QR code of the phone's address, so there is nothing to type; the three passwords are folded under *Advanced: type my own passwords* (they are still made for every unit and saved on the setup sheet; the section opens by itself if a typed password is not usable). **Owner tools** begins with a read-only **Check-up** (router, coin box, the website's update list, the print server if there is one; green or red, with the one-line fix). The router, firmware and app are unchanged.
- **Setup is simpler to find: a "Start here" page** (`/start/` on the website) with three jobs, each on one screen with at most six steps: set up a shop, add a phone, add a print server. The README begins with the same three links and its documentation table is split into owners and maintainers; `setup/MANUAL.md` is labelled as the advanced way. The security audit moved to `docs/maintainers/`. No router, firmware or app change. The print server pairing (a code instead of copying a key) is specified in `docs/api/print-pairing.md`; it needs the print server to add `/api/inbox/pair` first.
- Router 1.5.4, **PisoWiFi: the paused page now shows the real deadline.** It said "Use by <now + time left>; unused time after that is lost", which is not what happens: paused time is kept for 24 hours after the pause. It now says "Resume by <that moment>; time not resumed by then is lost", using the moment the router already sends. Also: cleanup of leftovers in tests and documentation (the owner's manual now says 6 phones per purchased box and 12 per rental box, the branch list and several stale command names are corrected).
- Router 1.5.3, **Send a file: a way into the phone's normal browser.** The sign-in window of some phones cannot open the file chooser. The send page now says what to do: open the phone's normal browser (Chrome or Safari) and go to any website that starts with `http://` (for example `neverssl.com`), which shows this Wi-Fi's page there, or copy the link shown on the page (a *Copy link* button; if the phone refuses, the link is selected and can be copied by hand).
- Router 1.5.2, **PisoWiFi: less waiting between Done and "You are online".** After Done the router closes the coin slot, waits for the last coins, records the payment, lets the phone on and tells the box to forget the coins. The page used to hear about it only after that last step, and a slow answer from the box (it writes its flash) added up to 2 seconds more. The page now hears the moment the phone is let on; the box is acknowledged right after, as before, and no other payment can start until then. openNDS's "busy" answers are retried after 150 ms instead of 500 ms. The router log now says where the time goes after every payment (`piso-setup diag`, lines with `timing`): box released, coins drained, recorded, granted, acknowledged. About one second of the wait is the coin box itself, which keeps the slot powered for a second after Done in case a coin is still falling; that is deliberate.
- Router 1.5.1, **PisoWiFi: the "Send a file to the owner" button did nothing on phones.** Both extra buttons on the coin page (Send a file, Play a mini-game) were links that open a new tab, and the Wi-Fi sign-in window that phones show (Android's and iOS's) opens no new tabs, so tapping them did nothing there. They now open in the same window; the page has a *Back to the Wi-Fi page* link. Some phones' sign-in windows also cannot open the file chooser: the send page now shows its address (`10.0.30.1:2080/send`) to open in the phone's normal browser as a fallback.
- Router 1.5.0 and coin box firmware 3.6.0, **a second router no longer disturbs the first.** Every router used to broadcast the same hidden networks (`PisoKiosk` for the rental phones, `PisoCoinBox` for the box), each with its own password. With two routers in range of each other (two shops, or a new router being set up next to a working one) the phones and the coin box could not tell them apart: a phone joined (or tried to join) the wrong one and never found its box. A router that is **set up from now on** gets names with its own ending, made from its MAC address: `PisoKiosk-a1b2` and `PisoCoinBox-a1b2`. While pairing, the box network is still called `PisoCoinBox` (the only name a new box knows); once paired, the box is told its own name and password. The box's *Set up a phone* link now carries the router's kiosk network name too (firmware 3.6.0; the setup page has a *Kiosk Wi-Fi name* field, filled in by that link and by the setup program), so the phone joins the right one. **A router that is set up already keeps the old names**, so nothing changes for its phones and box. The new boxes need firmware 3.6.0 for the link (`piso-setup box-update`); the setup says when the link does not carry the name yet. Limit: while a new box is being paired (a few minutes), a router set up before 1.5.0 still broadcasts the shared `PisoCoinBox` name, so the new box (or, for a moment, that router's own box) may pick the wrong one: keep the new box next to the new router while it pairs.
- Router 1.4.1, **Setup: the "openNDS is running" check no longer fails right after an install.** openNDS needs a few seconds to start, and the final checks ran at once; they now wait up to 30 seconds for it (and stop waiting as soon as it answers). `piso-setup check` waits the same way.
- **One program for everything: the setup program now has Owner tools.** Tap **Owner tools** in `pisophone_setup.py` (or open it at `/#tools`) to print QR **time cards** for any coin box (as many batches as you like; each card number is used once, kept in the same ledger as before), show your **admin QR code**, make a **phone recovery code**, and import or check your key files. The QR codes are drawn by the program itself and printed from the browser (no PDF tools or pictures to install); signing needs the `cryptography` package, installed on request into the program's own folder. The old **owner console** (`scripts/owner_console.py`) is gone: its operator pages live here now. The command-line scripts for releasing and checking stay for the maintainer.
- Router 1.4.0, **PisoWiFi: guests can send a file to the owner.** The coin page has a **Send a file to the owner** button (it opens in a new browser tab, like the mini-game). A guest picks a document or picture, with no payment and no login, and it shows up in the **Inbox** of the print server's admin page, where the owner opens it for printing or deletes it. Nothing is printed automatically.
  The router receives the bytes and passes them on to the print server's inbox (`10.0.0.95`) with a shared key, so guests never connect to the print server; it keeps nothing itself. Limits: 10 MB a file (`SEND_MAX_MB`), 5 files an hour per device (`SEND_PER_HOUR`), 3 uploads at a time, 3 minutes per upload, and the print server's own limits and file-type list. Another site's page cannot make a guest's phone upload (origin check).
  **A switch for sites without printing:** the console's Settings page has **Send a file to the owner: On / Off** (`SEND_ENABLED` in `/etc/coinslot.conf` sets the starting value). Off hides the button on open customer pages at once and refuses uploads, even when the key is installed.
  **Off until you copy the key:** the button appears only when `/etc/coinslot.d/send.key` exists (copy the content of `/etc/webprint/send.key` from the print server; `SEND_HOST`/`SEND_PORT` default to `10.0.0.95:80`). Needs print server release 2.2.0 or newer (repo armbian-print-vpn-server).
- Router 1.3.8, **PisoWiFi: Pause is a perk of paying ₱20 or more, once per session.** A device can pause only after the session has paid **₱20 in all** (top-ups and vouchers add up; `PAUSE_MIN_PESOS`, default 20, 0 = any amount),
  and only **one** pause per session (`PAUSE_MAX`, default 1). Below ₱20 the Pause button is hidden and the page says "Pay ₱N more to unlock a pause". The 1 kbit/s crawl and the 2 MB allowance are unchanged.
  Phones that drop the Wi-Fi when they are slowed down just reconnect, as before.
- Router 1.3.7, **PisoWiFi: Pause no longer takes the device off.** A paused device stays on the Wi-Fi but is slowed to **1 kbit/s** (`PAUSE_KBPS`), so the page, its buttons and its scripts keep working;
  with only **2 MB** of data for the whole pause (`PAUSE_QUOTA_KB`; used up, openNDS takes the device off as a pause always did, and Resume still works). The clock is still stopped and the time is still kept for a day. Resume gives back the time and the plan's own speed. The console's Customers page marks a paused device. Found on the way and fixed: the fair-use
  timer used `roll::session`, which resumes a paused session, so a device that stayed online while paused would have been resumed by it within a minute; it now leaves paused sessions alone (a test proves it).
  If openNDS refuses the slow grant the device is taken off, as before.
- Router 1.3.6, **PisoWiFi: a mini-game.** The coin page has a **Play a mini-game** button that opens *Coin Catch* (catch the coins, dodge the sparks) in a new browser tab. The game is one small page the router serves
  itself (7 KB, nothing from the internet), so it works before anyone has paid. Each device gets **10 minutes a day**, counted by the router while the game is played (a hidden tab or a pause does not count; the router adds only the
  real time that passed). When the minutes are gone the page says so until the next day. Change the minutes, or turn the game off with 0, in the console **Settings** page or with `GAME_MINUTES` in `/etc/coinslot.conf`. It is a soft limit
  for the child at the counter, not a lock. New: `game.rs`, `game.html`, `/game`, `/game/tick`; the coin page's state carries `gm` (seconds left today).
- **Release cleanup.** Removed code and files nothing used: an unreferenced app-closing function, two colour tokens, a manifest action no receiver handled, three strings and a picture the lint check
  reported as unused, two Compose tooling libraries (the app has no previews), a stray Python wheel in the firmware folder and an unused function in the desktop installer. The changelog's version
  note now describes the digit-based app version. Nothing in the firmware or the router portal was found to be dead.
- **Coin box firmware: the encrypted-production partition table was wrong and is fixed.** PlatformIO writes `boot_app0.bin` at 0xe000, but the encrypted layout put otadata at 0xf000 and ran nvs up to it, so every
  upload would have overwritten the end of nvs. otadata is now at 0xe000 and nvs is 20 KB like the development layout (nothing sold used the encrypted layout yet). New: `scripts/make_firmware_bundles.py` packs the
  development and release firmware into two zips (a manual run of the firmware workflow with *bundle* on); it reads every build's partition table and fails on a bad layout. The encrypted release route is still
  unproven on a board (`docs/PROVISIONING_SOLD_UNIT.md`).
- **Phone app: fixes from an outside code review.** The admin PIN is no longer written to the log when the box syncs it (a second place that the earlier fix missed), and CI now fails any log line
  that carries the PIN or a secret. The setup step, the auto-update attempt counter and the pill position now use the phone's device-protected storage like the rest of the app, so they cannot fail before the
  first unlock. Six remote-action branches that could never be reached over the network were deleted from the server coordinator, and an "is provisioned" check that was always false was removed (no behaviour change).
  The lock screen's fallback rate (before the phone has heard from the box) is now the box's own unit, one peso for 6 minutes, instead of 5 pesos for 30 minutes, so coins are shown correctly even then.
- Router 1.3.5, **console: edit the prices.** The console's **Settings** page has a **Prices** editor: the HyperSpeed and Endurance price lists (pesos to minutes,
  up to eight lines each), the minutes each extra HyperSpeed peso adds, and the Endurance download and upload limits. A change applies at once: the next payment uses
  it and the customer page shows the new list, while a payment already under way keeps the prices it started with (the amount on the screen never changes under the customer).
  Each plan must keep a 1-peso line (or pro-rata minutes) so every coin counts; limits stop typos (at most 8 lines, 1 to 1000 pesos, up to 7 days a line). The prices are kept on the router
  next to the voucher choice and survive `piso-setup`; **Go back to the settings file** forgets them. The settings file (`HYPER_TIERS`, `ENDURANCE_TIERS` ...) still gives the starting prices.
- **Admin pages: one look.** The router console, the coin box dashboard and the owner tool share one style: a near-black canvas (light theme mirrored), hairline-bordered cards, small muted labels,
  the tabs as one bordered group in the top bar, a status chip, and a white primary button.
- **Coin box firmware 3.5.6: spark protection for the coin slot.** The box now measures how long every coin pulse holds the signal line low and
  shows it (box settings page and diagnostics: a histogram of pulse widths, and a log line when pulses too short to be a coin are seen). A new
  setting, **Spark protection** (off by default, so nothing changes until you switch it on), counts a pulse only when it lasted at least the
  **shortest real coin pulse** you set (default 12 ms), which keeps a lighter or electrical noise near the coin slot from being credited as coins.
  How to set it up on your box, and the wiring that matters most (twisted pair, metal case, optocoupler, RC filter): `docs/COIN_SLOT_PROTECTION.md`.
  Only the width rules are tested on a computer; try it on one box before relying on it. Routers with automatic updates install box firmware by themselves.
- **Phone app version name** is now the build number cut into digits, hundreds.tens.ones (build 437 is 4.3.7, build 1000 is 10.0.0), instead of `1.0.<build>`. Only the build number (versionCode) decides what counts as an update, so phones update normally; Owner settings still shows the build number in brackets.
- Router 1.3.4, **PisoWiFi: voucher codes bought with coins, a page that no longer gets stuck, speech that works.**
  **Vouchers:** before Insert Coin a customer can pick **Use now** (as before) or **Get a voucher code**. A voucher window counts and prices the coins as
  usual, but connects nobody and adds no time: it makes one voucher worth exactly the minutes the coins bought, on the chosen plan, valid 30 days, and shows
  the code (large, Copy button, "nothing is connected yet", how to redeem under "Have a voucher?"). The page offers a tiny voucher file by itself (and a
  "Save voucher file" button and a screenshot tip; opening the file later brings the code to the page, which only fills it in) and the device's start page lists
  its unused vouchers for 30 days. Codes are never stored in the clear (derived from the router's secret and a salt; only fingerprints on flash). **Money:**
  the coins are booked as a normal coin row (kind `vbuy`) when the window settles and are part of the reconcile with the box; redeeming a code adds no
  revenue row. Older ledgers stay valid; printed slips of earlier releases still redeem until they expire or are used. **The owner's batch making and slip
  printing are removed** (the console's Vouchers page now lists issued vouchers and cancels unused ones; Sales shows coins, voucher codes issued, redeemed
  and waiting). New setting `VOUCHER_CHOICE` (default on): console **Settings** page, `piso-setup voucher-choice on|off`, `/etc/coinslot.conf`.
  **Stuck "Counting coins":** the page now asks the router again after 3 s in that state (and every 3 s while it lasts), pings every 4 s and reconnects
  when it hears nothing, and asks again when the phone wakes, the page is shown again or the network returns. **Speech:** the coin page now speaks by
  default (unlocked on the first tap, kept referenced until it ends, a gap after cancel, late voices handled, resumed when the page is shown again) and plays
  the beeps instead only when the phone cannot speak, the engine errors or does not start within a second. The page is about 7 KB larger (26.6 KB; it is still fetched once and then cached). New for your
  computer: `scripts/make_wifi_poster.py`, an A4 poster (PNG and PDF) with a code that joins the Wi-Fi and a code that opens the page for time and vouchers.
- Router 1.3.3, **PisoWiFi: a new look, Pause, and voucher codes.** The customer page is redesigned (navy band with a signal line, one clear
  button, prices in gold, "Connected" / "Almost out" / "Paused" chips). A customer can **Pause time** (the clock stops and the device goes off the
  internet; **Resume time** brings both back; 10 pauses, 2 minutes needed, 60 s between pauses, paused time kept 24 hours; owner settings
  `PAUSE_MAX`, `PAUSE_MIN_LEFT_SECONDS`, `PAUSE_GAP_SECONDS`), and can enter a **voucher code** under "Have a voucher?". The owner console is redesigned
  (light or dark, left menu or bottom bar on a phone, confirmation dialogs that name what they do) with a new **Sales** page (per day, coins and
  vouchers, CSV download) and a **Vouchers** page: make a batch (time, price, plan, how many, how long the slips stay valid), print slips on A4 (3 across) or
  on a 58 mm receipt printer, cancel unused codes. Codes are not stored on the router (only a fingerprint of each; the console prints them again from a secret
  and the batch); a code works once; five wrong tries slow a device down (1 minute, doubling to 15). A voucher is booked in the revenue records as a voucher and
  is left out of the comparison with the coin box's own count (that counts coins). The page is 19 KB (it was 16 KB); it is still fetched once and then cached.
- **Phone app: a livelier lock screen, a smaller pill, speech and idle behaviour, and more** (firmware 3.5.5 for the box page).
  - Lock screen: the panel is half see-through over a moving backdrop (colour clouds, turning rays, rising bubbles, ten floating stickers, twinkling sparkles,
    falling coins, a shooting star), the mascot hangs over the panel's top edge, waves or hops every few seconds and breathes; the screen slides up when it takes over.
    Everything moves only when the phone is not hot, the animation scale is not 0 and the owner's video switch is on.
  - Pill: no sticker, 28dp, fades to about half strength five seconds after it was touched, remembers where it was left. When time is added during a session,
    the mascot pops over the game for 1.5 seconds with sparkles, coins and "+30 min".
  - Voice: the last five seconds are spoken one number at a time on their own seconds (from small clips made once by the phone's own speech engine, so a slow
    phone does not lag); "5 minutes", "3 minutes", "1 minute" and "10 seconds" can no longer be skipped by a late tick; "Time is up, thank you for playing!" after the
    time-up sound. Owner settings: voice on or off, volume, quiet hours.
  - Boost (replaces Close app and Clear RAM; two taps): stops the other apps and keeps the one in front (the owner grants Usage Access once; without it only background
    apps are stopped). Refresh Wi-Fi: a quick off and on that never leaves it off, then back onto the kiosk network (not available while coins are awaited, 15 s apart).
    The phone is put back on the kiosk network the moment it joins or loses any Wi-Fi.
  - Idle: after 5 minutes of the lock screen being left alone the screen turns off, and picking the phone up wakes it (pick-up sensor, else motion sensor, else the
    accelerometer). After 5 minutes without paid time the data of social, chat, mail and browser apps is wiped (device owner, Android 9+; games are left alone; the list
    is editable in the owner settings).
  - Restart guard: a crash that repeats waits 30 s (third in 10 min) or 2 min (fifth) before the restart; the box's phone list shows "↻ N" for restarts in the last 24 hours.
  - Box page: the phone list columns no longer crowd the buttons.
  - Cards: `make_cards.py` makes the card key itself the first time it runs (no separate key step); the key stays outside the repository.
- **Phone app: the time pill, its panel and the owner's screens in the new look.** The pill is 36dp with the brand mark, a level icon (a dot, an hourglass at
  5 minutes, an alert at 1 minute) and the time as MM:SS (H:MM:SS over an hour); in the last minute it swells once a second. A note slides in under it
  at 5 minutes ("5 minutes left", gone after 6 s) and at 1 minute ("1 minute left! Insert coin for more time.", stays), each with an "Add time" button. The
  panel has the mascot, the phone's name, the time at 40sp in the colour of its level with a bar for what is left, one coin button (Add coin / Done · N coins),
  Home, Close app and Sign out (still two taps), "Clear RAM" (replaces Turbo Boost), and bigger volume and brightness sliders. The owner sign-in,
  the owner settings (quick tools, settings, hidden apps, app update, diagnostics) and the system recovery screen use the same cards and buttons; locking the
  phone and removing time ask first and say what will happen. Wrong password is said in words. Nothing below 12sp.
- **Phone app: a new lock screen** (docs/design/GUI_SPEC_PISOPHONE_KIDS.md, screens A and B). One opaque panel with the TechNet mascot on a blue-to-teal halo,
  the rate as "₱5 = 30 min" (and the same for 2 and 4 coins), one big button ("Ready, insert coin"), and the coin box status under the panel. While
  coins are going in, the rate card becomes a running total ("₱10 = 1 hr") with a bar and a ring for the seconds left; each coin makes the mascot hop,
  drops a few coins and gives a short buzz. The 8 neon themes are now 3 backdrop colours (Dagat, Tropiko, Ginto); the old stored choice maps onto them.
  Nothing moves while the screen rests; effects stop when the phone runs hot (thermal status), when the system animation scale is 0, and when the owner's
  video switch, low RAM or power saving say so. Battery, "device not activated" and arena notices share one banner. Technical text (IP address, controller
  wording) is gone from the kids' screen. Not in this step: the time pill and its panel, the owner's vault screens, sounds, the Nunito font, pause and the
  voucher entry on the phone (those need decisions or a router/box API first).
- Router: **`install.sh ... update` on a router that already has the setup now installs only the release the owner signed** (it runs
  `piso-setup self-update`: signature, no lower release, staged rollout). Before, it downloaded a setup file whose checksum came from the same website.
  `--branch <name> update` still installs that branch's file for testing and says it is not signed. `lock-admin` also closes ports 8080, 8443 and the console port.
- **Phone app: a coin now has to be proven by the box before the phone credits time.** The box signs every coin it reports (`v_sig`, in the
  WebSocket message and now also in the status list, firmware 3.5.4); the phone credits nothing from a message that is not signed with its
  box secret, names another phone, or whose seconds do not fit the signed amount. (Before, something posing as the box on the kiosk network could
  have sent "coin inserted" and the phone believed it.) A phone that was set up but can no longer read its box secret (the Keystore key was lost)
  stops talking to the box and says so in the diagnostics, instead of silently falling back to the old public key. A phone in lockdown (slot
  expired or unpaired) refuses a coin instead of crediting it and then wiping it. The USB setup page now pairs the phone under the id it really
  uses (the old hardware-fingerprint id left a "paired" phone that was not the real one). The admin PIN is no longer written to the log.
  The app's updater downloads only from the update site or GitHub releases and refuses a build of another channel. Order of updating: box first
  (firmware 3.5.4), then phones; a phone on this version needs a box on 3.5.4 for its status-poll fallback (the coin push itself already worked).
- Firmware 3.5.4, the box's admin pages: **another website can no longer make your browser change the box.** A change (save settings, add time,
  pair, unpair, relay test, restart) that a web page on another site asks for is refused; opening a link never changes anything any more: the
  setup page's "pair this phone" link now lands on a "Pair this phone?" confirmation, and the relay test, pair and unpair are POST-only. Also: coin
  counters reach flash at least every 20 s even while coins keep coming (a power cut loses at most that), an unpaired hello can no longer fill the
  box's list of phones and push a paired phone out of it, and the production (encrypted) builds have no serial "add minutes" command.
- Firmware 3.5.4 and router 1.3.3: **the box limits phones by its type, and the router no longer takes the box's word without a signature.**
  A *purchased* box pairs up to 6 phones, a *rental* box up to 12. The type is changed only with a token you sign for that box
  (`python3 scripts/sign_box_type.py`, pasted with `POST /api/license`) or while signed in with the admin QR code; an operator's factory reset
  keeps it. Phones already paired above the limit keep working. The coin box now signs its answers to the router (`rsig`, docs/api/gateway-coinslot.md),
  and the router refuses an unsigned or wrongly signed answer once it has seen the box sign (so a device pretending to be the box cannot report
  coins). A box that still runs the old shared key no longer sends its admin password to the phones (anyone on the network could have
  decrypted it); switch such a box to its own key in the dashboard. The coin slot no longer tells which phone holds it, and the
  "Set up a phone" links keep the box secret after the "#" (never sent to the website's server).
- **Release signing is locked down.** The owner key and the APK keystore now belong in a protected GitHub Environment (`production-signing`:
  main only, your approval before each signing job) instead of plain repository secrets, and building is split from signing: Gradle and PlatformIO
  run in a job with no secrets. Branch (dev) APKs are signed with a public test key, so a dev build can never be installed over a production
  phone. One-time steps for you in GitHub Settings: docs/RELEASE.md 2a. Phones that run a dev build must be reset once.
- Router 1.3.3: **the PisoWiFi page gets customers online without a refresh.** A phone's sign-in window only looks for the internet again when
  a page loads, and the coin page never loaded again after paying, so the phone stayed "not signed in" until the customer refreshed. Once the
  page says online it now loads itself once, so the phone notices at once (and closes its sign-in window). When openNDS refuses the first grant,
  the router keeps trying every 2 s for half a minute and the open page turns to online by itself ("Connecting you…" meanwhile, the Wi-Fi
  advice only after 30 s); before, only a refresh tried again. Customers are let online before the box is acknowledged (one round trip sooner).
- **Adding time while online:** the phone may close its sign-in window as soon as it sees the internet, even in the middle of a top-up. The
  coins still count (the window runs on the router and settles by itself); the page now says so ("Page closed? Keep inserting, coins still
  count"). The router log tells the two cases apart: `window … closing: <why>` and `the page of <device> closed while its coin window is open`
  (`logread -e coinslot`).
- Firmware 3.5.3: **signing in to the box's admin page keeps you signed in.** After a correct password, the box now gives the browser a session
  cookie (like the admin QR login already did) and the page's background checks carry that, instead of relying on the browser to resend its
  cached password on scripted requests (some do not, which ended the sign-in a few seconds after it began). A session lasts 30 idle minutes
  (12 hours at most), up to six browsers at once, and ends when the admin password is changed, on **Sign out** or when the box restarts. The page
  also needs two "not signed in" answers in a row before it shows the signed-out bar, and the box notes in Tools, Diagnostics why it
  answered 401 (password sent or not, cookie sent or not).
- **Everything updates itself now** (router 1.3.2). The **coin box** gets its new firmware through the router: every hour (half an hour after the
  router's own check) the router looks at `firmware.json` and, at a quiet moment (no customer online, no phone with time running), hands the box the
  signed image; the box still verifies your signature itself and goes back to its old firmware if the new one does not start. `piso-setup box-update
  [check]` does it now, `auto-update off` stops it, and Telegram `/update` now covers the box too. The **rental phones'** app checks hourly and
  installs a new build by itself when it is the device owner and idle (lock screen, no time left, nobody signed in), three tries per build at most.
  The router already updated itself hourly. (docs/RELEASE.md)
- Publishing a router release now starts at **0%** of the installed routers (a router set up with the installer still gets the newest file).
  Widen it by hand with the workflow's rollout input (10, then 100); a push to main never re-signs a file that is already published, so a widened
  rollout stays. (docs/RELEASE.md)
- Router 1.3.1: **five CPU families, and a console fix.** (1.3.0 was published from an earlier snapshot, with the console, the backups and the leaner updates.) In the console, a request's body is always read before answering: an unread body reset the connection, so signing out could show an error.
  The portal program (and so the console) is now built for `mipsel` (as before; MT7621, MT7628, the
  360T6M), `mips` (ath79, big-endian), `aarch64` (newer Wi-Fi 6 routers, Raspberry Pi 4, NanoPi), `armv7` (Qualcomm IPQ40xx) and `x86_64`, each with
  OpenWrt's own toolchain in CI. Each family has its own setup file (`setup/piso-setup-<family>.sh`, signed into `website/update/<family>/`;
  mipsel stays where it was, so deployed routers keep updating). The one-line installer and `piso-setup update` pick the file by the router's CPU
  and a file for another CPU refuses to run before changing anything. Every build passes its self-test and the console tests in QEMU, including the
  big-endian MIPS one. Only the 360T6M has run on real hardware. See `docs/ROUTERS.md`.
- Router 1.3.0: **the owner's console.** A friendly page in place of LuCI, inside the portal program (nothing more to install): customers
  online, money today, the internet line with a short graph, every PisoWiFi device with its name, plan, time left, data and live speed
  (and a Disconnect button), and the rental phones from the coin box. It signs in with the router's root password (checked like LuCI does,
  5 wrong tries lock it for a minute) and keeps its history in memory only. It is at `http://10.0.0.1:8081`; `piso-setup console on`
  makes it the front page at `http://10.0.0.1` and moves LuCI to `:8080` as "Advanced" (and puts everything back if it does not come
  up; `console off` reverts). The page's script can be replaced by a newer one from the website without a router update, but only a
  script the owner signed (`scripts/sign_console_ui.py`, checked in the browser against the owner key the router already trusts; an
  unsigned, changed, expired or older script is ignored). Tests: `tools/pisoportal/tests/test_console.py`, `test_console_loader.py`,
  `scripts/tests/test_console_ui.py`. Not tried on a real router yet (the openNDS client list format is read as documented).
- Router 1.3.0: **space, backups and safety on small routers.**
  - A firmware upgrade (sysupgrade) now keeps the PisoPhone settings, the revenue ledger, the monitor settings and the programs
    (`/etc/sysupgrade.conf`), so an upgraded router still pairs with its box and keeps its records.
  - `piso-setup export [file]` / `piso-setup import <file>`: a private backup of the settings and the ledger to copy to your computer, and
    back after a reset or on a replaced router (it refuses a file with anything else in it).
  - `piso-setup space`: what is kept on the router, what is free, what can go. `piso-setup forget-summary` deletes the saved summary
    (it holds passwords; `piso-setup summary` shows them again).
  - An update's backup of the old release is deleted as soon as the new one has proven itself; the log keeps its last 600 lines;
    an update on a router with under ~24 MB of free memory downloads into flash instead of memory.
  - The portal program is built for mipsel_24kc only: on another CPU the setup and update now stop before changing anything and say so.
  - CI checks a footprint budget (`router/tests/test_footprint.py`).
- **Owner console** (`python3 scripts/owner_console.py`): a web page for every owner script, in place of typing its command line. Each
  tool has its own page (`/p/<name>`): admin QR, recovery code, card key, owner key, print time cards, sign firmware, sign router update,
  publish box firmware, release/APK/library/security checks and the sold-unit checks. A form fills in the options, Run shows the output
  and the files made (QR pictures inline, PDFs and ledgers to download). Keys never go through the browser (only their file path); it
  listens on 127.0.0.1, needs the printed secret, checks the Host name and refuses requests that did not come from its own pages.
- Firmware 3.5.2: **the dashboard no longer asks for the password every few seconds.** The page polls the box (`/api/status` every 3 s)
  and, when its sign-in had ended, each poll got a Basic-auth challenge, so the browser's password box popped up again and again.
  Requests to `/api/...` now get a plain 401 with no challenge; the page stops polling and shows one "You are signed out. Sign in
  again" bar. Pages (not scripts) still ask for the password as before.
- Lock screen: the original texts and cards are easier to read over the new art. The cards are darker (the theme colour mixed with black) and
  still translucent, a dark translucent panel sits behind the lock, the title and the cards, and the dim layer over the art is stronger. The
  layout is the same (8 dp of padding inside the panel).
- Setup program: the first page no longer says "no internet" without saying why. It tries two addresses on the website, names itself
  (`TechNetPisoHubSetup/1.0`) instead of Python's own default, which websites often refuse, waits 15 s instead of 8, and shows the real
  error when it still fails (an HTTP code, a timeout or a certificate problem, with a hint for a security program that inspects HTTPS).
  Every download the program makes uses the same name.
- Router 1.2.8: **pairing tells a refused box from a joined one.** A coin box that was set up before keeps its own Wi-Fi password; when the
  router offers another, the box is listed as joined for a moment and thrown out again, which pairing took for "joined" and then blamed
  an address lease. Pairing now reads the router's Wi-Fi log ("POSSIBLE-PSK-MISMATCH"): it says the box is refused and to factory reset
  it (GPIO 2 to ground for 5 seconds, or flash it again with an erase), keeps waiting, and ends with that message instead of the lease one.
  `piso-setup box-diag` gives the same verdict.
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
