# Real-world testing checklist

**Easier on a phone:** the same tests as a tickable page (Pass / Fail / Skip, a note on failures, a report to paste back): `python3 tools/checklist/build.py out.html`, then publish `out.html`. Re-run it after changing this file. This file stays the single list. **To write the results back into this file** (ticks, Issue log rows for failures, the setup table, the baseline row): export the page's shared record as JSON and run `python3 tools/checklist/apply.py results.json` (add `--dry-run` to preview), then commit and push; a failed test becomes a GitHub issue by the workflow below, and a retest that passes closes it. Running it again with the same results changes nothing.

Fill this in while testing on real hardware. It is the baseline of what works and what does not: tick what passes, write what
fails in the **Issue log** at the bottom, then paste the log (plus the evidence listed there) to the developer so each issue
becomes a fix. Commit this file after each session so progress is visible in git history.

## How to run it (one person, three passes)
- **Pass 1: money path, about 2 hours.** B1–B3, P1–P4, C1–C4, F1–F3. If anything here fails, stop, log it, and send it before going on.
- **Pass 2: the rest of the box and phone, a second session.** Remaining B, P, C and F items, plus S1 and S3.
- **Pass 3: later, only when passes 1 and 2 are clean.** K (keys, updates), R (router), L (soak).

Tip: a phone hotspot you can switch off, and a power strip with a switch, make the Wi-Fi-drop and power-cut tests quick to do alone.
Save `adb logcat -d > phone.log` and the serial output right after each failure.

**Legend:** `[x]` pass, `[ ]` not tested yet, `[!]` failed (add a line to the Issue log with the same ID).

## Owed checks, in the order to do them (feature 039, 2026-10-28)
This section only sorts and orders. It changes no check: every row is still in its own section below with its own tick, and the text there is what you run. The lines here are pointers, not the checks. Nothing was run to write it. Rows are never deleted and never renumbered; a row that stops being needed is retired where it stands, like RS34.

Do the sessions in this order. Inside a session, go top to bottom: money checks come first. `S2` below is the older security row S2, not a session. In section 10 the five lines have no id; they are called §10-1 to §10-5, counted from the top.

### Summary
- **55 checks are owed:** 38 `RS` rows, 12 older rows (B9, C3b, C8, F8, R4, H2, S1 to S6) and the 5 unnumbered lines of section 10. By session: 15 + 17 + 12 + 11 + 0 = 55.
- **0 were retired by this triage.** None met one of the three reasons (the thing it tested is gone; a later check on a newer release covers the same ground and can be named; the code was replaced and the new code has its own check). One `RS` row was retired earlier (RS34, feature 033) and 11 are done; they are in the closed list at the end of this section. 11 done + 1 retired + 38 owed = the 50 `RS` rows.
- **Start with session 1, part 1a.** It needs the least set up (a box, a phone, a power strip) and holds the money rows that have never run on a box: RS30, RS28, RS31, F8, RS29. A failure there means a coin lost or counted twice, so it is the place to look first.

### Session 1: the box on the bench (the firmware money rows)
**Needs:** a coin box with its coin acceptor, a USB cable and a computer with the setup program, one paired phone, a power strip with a switch, a serial monitor (`pio device monitor`), and a Wi-Fi the box can join. Part 1b also needs a new or erased ESP32-C3. **Roughly:** part 1a about 3 hours, plus a 24-hour wait for one part of RS46; part 1b about 2 hours. (My estimates, not measured.) The box here needs no OpenWrt add-on; if a row turns out to need the router, move it to session 3.

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 1 | RS30 | Power cut between the coin and the queue (FW-03) | Money. Several tries; the switched power strip is the tool. |
| 2 | RS28 | Settings saved during a coin drop (FW-02) | Money. The full P5 must be credited. |
| 3 | RS31 | A coin at an idle slot (FW-06) and a large rate (FW-05) | Money. |
| 4 | F8 | Unacknowledged payment survives a factory reset | Money. You wrote on 2026-10-11 that you were not sure how to make one: the ticked M3a describes how to leave a coin unacknowledged (switch the phone's app off after the coin). F8's own text is unchanged. |
| 5 | RS29 | Reboot, update and reset refused while a slot is armed (FW-01) | Nothing paid is lost to a restart. |
| 6 | RS46 | A coin box that keeps working (feature 017), parts a to g | Do a, b, c, d, f in the evening; start the 24-hour soak (e) and read it the next day; g needs a box on 3.12.0 first (may not be possible: say so). |
| 7 | C3b | Vendor page: finish collecting, the counter goes to ₱0, lifetime coins do not go down | Money. You skipped it on 2026-10-11 because super admin would not open. The admin QR code comes from `python3 scripts/make_admin_qr.py` (S6 names it) and the box must be built with the matching `SuperAdminPubKey.h`. |
| 8 | C8 | Revenue counter resets after 5 minutes or on logout | Skipped on 2026-10-11 for the same reason as C3b. |

Part 1b, a new or erased board on USB:

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 9 | B9 | The setup program writes a factory ESP32-C3 over USB | Add a classic ESP32 if you have one; it has its own image. |
| 10 | RS50 | PisoPhone-only setup over USB on Windows: the board stays up | Windows computer. |
| 11 | §10-1 | Run the setup program, choose PisoPhone only | Needs an ordinary Wi-Fi router. |
| 12 | §10-2 | The saved summary opens only for you; the admin page opens with that password | |
| 13 | §10-3 | A rental phone finds the box and pairs; a coin unlocks it | Money. Do it right after 11 and 12; keep this box for part 2b. |
| 14 | §10-4 | Run the setup again on that working box: it asks to type ERASE first | |
| 15 | §10-5 | A box with its own admin password refuses the USB Wi-Fi command | |

### Session 2: the box and a phone in a shop
**Needs:** a working box on the current firmware with two paired phones (a tablet as one of them, if you have one: RS33 and RS49 want it), the shop's usual Wi-Fi, and a phone you can factory-reset. Part 2b needs the PisoPhone-only box you set up in session 1b and a factory-reset phone. RS27 wipes the box's pairings, so it is last. **Roughly:** part 2a about 4 hours (RS38 alone is long), part 2b about 1.5 hours. (My estimates.)

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 1 | RS39 | Nothing old breaks: old phone build with the new box and the other way round | Money (a coin credited once, also during a box update). Needs phone build 495 and a box on 3.11.1: if you cannot get them, write that under Notes and leave it owed. |
| 2 | RS49 | Phone profiles: PisoTab and PisoPhone Pro | Minutes a coin gives must be the same at every step. Wants a tablet and a phone. |
| 3 | RS26 | Spoken welcome for QR cards | Time and balance must be the same as before. |
| 4 | RS33 | Touch in games on a tablet (APP-27) | Includes the lock when time runs out. Its "built-in game" wording will change when feature 034 merges. |
| 5 | RS55 | Owner can uninstall an installed game during the admin window | |
| 6 | RS53 | YouTube, Chrome, Maps and Photos are back on the home screen | Needs a phone set up before feature 029 (YouTube missing); may no longer exist. |
| 7 | RS48 | The shop name as a running band (feature 023) | An hour on the lock screen to watch heat and battery. Run it with RS38. |
| 8 | RS38 | Shop branding set on the box reaches the phones (feature 013), parts a to h | Long. Its parts f, g and g2 about the name standing still are older than feature 023; the wording is left as it is. |
| 9 | S1 | Phone's port 8080 answers `/ping` only; `/status` without a signature is 401 | |
| 10 | S3 | Remote admin actions need the admin PIN | |
| 11 | S4 | A phone cannot be factory-reset or get USB debugging from the network | |
| 12 | H2 | Box commands are fresh (a replayed command gets `STALE_TIMESTAMP`) | |
| 13 | S2 | A replayed signed request is ignored | Optional (the row says so): needs a packet capture. |
| 14 | RS27 | Slot licensing (firmware 3.10.0) | Needs four paired phones for its first part and a box you may factory-reset; a licence key from the setup program's Owner tools. |

Part 2b, the PisoPhone-only box from session 1b:

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 15 | RS52 | The box page's *Set up a phone* fills in the shop Wi-Fi; QR on a factory-reset phone | |
| 16 | RS51 | A new rental phone joins the shop Wi-Fi, once with the network saved and once without | |
| 17 | RS54 | The "joining" toast no longer repeats every minute | |

### Session 3: the router
**Needs:** the shop's router with the add-on installed, the box paired to it with its coin acceptor, one guest phone (two for RS22: one on 2.4 GHz, one on 5 GHz), a computer on a LAN cable, a smart plug on a timer for RS45. Do it with the shop closed: you skipped RS7 on 2026-10-11 because customers were connected, and RS14 waits for a quiet moment by design. **Roughly:** about 5 hours. RS45 is ten power cuts; RS44 and RS56 change the router's date. (My estimates.)

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 1 | RS45 | A power cut at the worst moment (feature 021) | Money. Ten tries is not too many. |
| 2 | RS32 | Clock jump with a voucher (RT-01 to RT-07) | Money and time. |
| 3 | RS7 | Restart the router in the middle of a window | Money. |
| 4 | RS44 | A router that keeps running (feature 015), parts a to f | Part d is a month roll with money; part b freezes the portal. |
| 5 | RS56 | The sales record's space cap (feature 032) | Needs sales in the ledger; also a month roll, so do it next to RS44. |
| 6 | RS6 | `piso-setup reconcile` says RECONCILE OK; a broken ledger is reported | |
| 6b | RS58 | The console front page money line (feature 036) | Added after the triage counted 55 owed. Do it next to RS6: they read the same comparison. |
| 6c | RS59 | The console update line (feature 044) | Added after the triage counted 55 owed. Needs the box paired and a newer release to wait for; do it after RS13 or whenever a release is published. |
| 7 | R4 | Layout B: a guest device cannot reach the box or the phones | Skipped on 2026-10-11 ("will check later"). The Test setup table's layout cell is empty: fill it in. |
| 8 | RS22 | Guests apart (router 1.5.9/1.5.10) | Two guest phones. |
| 9 | RS10 | `piso-setup handout` writes `/root/piso-handout.html` | |
| 10 | RS13 | A signed test release installs with `piso-setup self-update` | Needs your owner key in `tools/pisoportal/owner_key.b64` and a router built from it. It publishes a release: read `docs/RELEASE.md` first. I could not tell from the row whether the test release can reach routers other than the one under test. |
| 11 | RS14 | `self-update auto` waits while a phone is online | After RS13. |
| 12 | RS16 | A hand-edited, signed script is refused | After RS13. Also publishes: same warning. |

### Session 4: the whole site
**Needs:** part 4a: the working site (router, box, a rental phone on PisoKiosk, a guest phone, a computer on a LAN cable). Part 4b: a router you can factory-reset (not the shop's), a second coin box board on USB, another router in range for RS21, and for RS35 the owner's 360T6M with a NOR-only and a NAND build. Do not run 4b on a router that is trading. **Roughly:** part 4a about 3 hours; part 4b about 5 hours or more. (My estimates.)

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 1 | RS47 | Quick time adjust from the console: Add, Subtract, all phones | Time must change once, not twice. Two paired phones. |
| 2 | RS23 | Rental phones apart from each other; the router's login page closed to phones | Includes arming a coin and unlocking. |
| 3 | RS24 | One admin page: the box's page inside the console | |
| 4 | RS25 | `admin.hub`, the router at `:8080`, no mDNS | |
| 5 | S5 | A provisioned phone joins only the box's PisoKiosk network | |
| 6 | S6 | Owner QR login | Needs the admin QR code from `python3 scripts/make_admin_qr.py`. |

Part 4b, a spare router and bare units:

| # | Check | What it is (pointer only) | Notes |
|---|---|---|---|
| 7 | RS35 | PisoWiFi on a 16 MB router (feature 010), parts a to e | Includes a coin, a pause, a voucher and a power cut that keeps paid time. Its part d takes the rental phones' Wi-Fi away on a set-up router; the row asks first. |
| 8 | RS17 | One click from a bare unit | |
| 9 | RS19 | *Set up the next unit* | After RS17. |
| 10 | RS20 | No board, or two boards, on USB | |
| 11 | RS21 | Two routers in range of each other (router 1.5.0, firmware 3.6.0) | |

### Session 5: the hardened box (last, by the owner's rule)
**Needs:** a sacrificial board and the eFuse steps (feature 040's bench work, feature 043's checks). **Roughly:** not known yet.

No owed row of this document belongs here yet: features 040 and 043 add theirs when they ship, at the next free `RS` number (RS57 or later). Their bench steps are in `specs/features/040-sold-box-hardening/bench.md`. The owner's rule of 2026-10-28 (epic D30) decides that this session is last: the legacy, field-proven build is the default and stays selectable, and hardening is an option. Nothing in sessions 1 to 4 needs a hardened box.

### Closed: done and retired (12 `RS` rows, not owed)
| RS | Status | Reason and where the evidence is |
|---|---|---|
| RS1 | done | Ticked in section 9. |
| RS2 | done | Ticked in section 9. |
| RS3 | done | Ticked in section 9. |
| RS4 | done | Ticked in section 9. |
| RS5 | done | Ticked in section 9. |
| RS8 | done | Ticked in section 9. |
| RS9 | done | Ticked in section 9. |
| RS11 | done | Ticked in section 9. |
| RS12 | done | Ticked in section 9. |
| RS15 | done | Ticked in section 9. |
| RS18 | done | Ticked in section 9. |
| RS34 | retired | Router 1.5.25, feature 033: the spinning wheel it tested was deleted before it ever ran. Already marked on its own row; not touched here. |

"Done" for the eleven means: ticked from the owner's session of 2026-10-10/11 (Test setup table below: firmware 3.3.3, APK 411, ImmortalWrt 23.05.0), committed in `72c68c7`, counted in the Baseline summary. The only evidence is the tick, and it is from an earlier release than the current one.

### Notes on this triage
**Where I was unsure (each stays owed):**
- RS24 and RS25 overlap on how the console opens. RS25 does not repeat RS24's checks, so both stay; run RS24 first.
- RS38 and RS48 both describe the shop name on the lock screen, and feature 023 (RS48) changed how it looks. RS38 still owns palette, title, validation and survive-a-restart, so it stays whole. Its parts f, g and g2 are older than 023; I left the wording.
- RS39, RS53 and part g of RS46 need an old version (phone build 495, a box on 3.11.1 or 3.12.0, a phone set up before feature 029). "Hard to arrange" is not a reason to retire; if you cannot arrange them, write that under Notes and ideas.
- RS35's last sentence is about the one-line installer, which feature 041 will touch; 041 has not started.
- Session 1 assumes its rows run on a box with only an ordinary Wi-Fi. I could not tell from the rows alone that every one does.
- RS13 and RS16 publish a release. I could not tell whether the test release can reach routers other than the one under test.
- Where a row sits (RS27 last in session 2 because it wipes pairings, RS35 in 4b because it needs a re-flash, RS47 in session 4 because it needs the console and a rental phone) and every time estimate are my judgment.

**Timing, as asked:** feature 034 (the mini-game) has not merged: its code is still in this checkout. No row tests the mini-game by itself; RS33 says "a built-in game" and 034 will change that wording. Feature 041 (the offline package path) has not started, and there is no RS36 row in this document to retire (next note).

**Numbers other files use but this document does not have:**
- RS36 and RS37 (feature 011, offline setup and the box network name). `specs/features/ARCHIVE.md` (011-offline-setup) says you ran both on 2026-10-23 and they passed, but no row exists in this file or in its history, and the per-step output was never pasted in. Feature 041 plans to "retire RS36"; there is nothing to retire.
- RS40 to RS43 (feature 019, the soak checks) were planned in `specs/spec_02_always_on.md` and never written here.
- I added none of them: they would be invented rows. Decide whether to add them at the next free number.

**A numbering clash to fix in the other specs:** RS53 to RS56 are already features 029 to 032 in this document, so the next free number is **RS57**. Feature 041's plan ("a new RS55") and feature 043's plan ("RS53, RS54 and RS55 are taken by features 032, 040 and 041") assume otherwise, and feature 040's plan already says "next free". Those files are outside this feature and were not changed.

**Things that look wrong, left as they are:**
- The five lines of section 10 have no ids, so the tickable page (`tools/checklist/build.py`) does not show them and a result cannot be written back for them.
- The Test setup table's router layout (A or B) cell is empty, and R4 needs layout B.
- The Baseline summary's last row (2026-10-16) is older than most of the rows above it; it is updated when results are written back.
- RS38 part g2 has stray backslashes before two quote marks. A few rows are out of numeric order (RS27 before RS26, RS46 before RS45, RS50 and RS49 before RS48); renumbering is forbidden, so they stay.

### Suggestions (not owed, not numbered, not part of the list)
- After sessions 1 and 2, run the short money path again (B1 to B3, P1 to P4, C1 to C4, F1 to F3) on the current firmware and app. The ticks are from firmware 3.3.3 and APK 411, and the money code has changed since (3.11.1, router 1.5.13 and 1.5.19).
- Give the five section 10 lines ids (RS57 onward), so the phone page can report them.
- Record the 2026-10-23 results of the offline-setup checks (RS36 and RS37) as done, if you want the log to say so.

## 0. Test setup (fill in first)
| Item | Value |
|---|---|
| Date / tester | 10-10-2026/Mil Admin |
| Firmware version and board (`esp32-c3-dev`, etc.) | 3.3.3/esp32-dev-c3 |
| APK build number (`app.json` versionCode) | 411 |
| Phone model / Android version | Infinix Hot 40i/13 |
| Router model / OpenWrt version / layout (A or B) | 360T6M (board `360,360t6m`) / ImmortalWrt 23.05.0 (r27285) / |
| Coin acceptor model | Weiyu Universal Coin slot |

Collect logs as you go: box serial output at 115200 baud (`pio device monitor`), phone `adb logcat -s KioskSecurity KioskService`
(`adb logcat -d > phone.log` after a failure), router `logread -e coinslot -e opennds`.

## 1. Box basics
*B1–B3 are pass 1, the rest pass 2*
- [x] **B1** A fresh or factory-reset box boots with the default password `Coinslot@Setup` and the built-in Wi-Fi `PisoCoinBox` / `PisoCoinBox@Setup`; a coin request is refused (`SETUP_REQUIRED`) until the admin password is changed (the router setup changes it)
- [x] **B2** The box has no access-point mode: it joins the router's hidden `PisoCoinBox` network by itself, after the router setup it has its own random Wi-Fi password (`piso-setup status` checks it), and it answers at `10.0.0.10`
- [x] **B3** Dashboard opens; first-run checklist shows; it disappears when its items are done
- [x] **B4** Changing the admin password works; old password stops working
- [x] **B5** Five wrong admin passwords lock login for a minute
- [x] **B6** Reboot from the dashboard works; settings survive
- [x] **B7** Wi-Fi drops and returns (turn the router off/on): box reconnects by itself
- [x] **B8** Box runs 1 hour idle without rebooting (check serial for restart reasons)
- [ ] **B9** (replaces the web flasher test) The setup program writes a factory ESP32-C3 over USB by itself: it finds the board, names the chip, ends with "Hash of data verified", and the box starts, joins PisoCoinBox and answers at its page. A classic ESP32 board gets its own image the same way. A used box forgets its old Wi-Fi (the program erases it first)

## 2. Phone install and pairing
*P1–P4 are pass 1, the rest pass 2. Testing the beta branch: the box's Set up a phone link always opens the production site (main's APK). Copy the link and change only its host to beta.pisophone.pages.dev, keeping everything after it; check that beta.pisophone.pages.dev/update/app.json shows a -dev version first.*
- [x] **P1** QR setup: on a factory-reset phone, 6 taps on the welcome screen open the QR reader; scanning the page's code ("Show the setup code") installs PisoPhone, makes it the locked kiosk (device owner) and it opens by itself at the end
- [x] **P2** After P1 (or the USB cable setup) the phone has its box secret and gets its admin PIN; with the PisoKiosk password typed on the page, the phone joins PisoKiosk by itself (turn its Wi-Fi to another network and see it come back within about a minute)
- [x] **P3** Phone finds the box and shows it online; slot shows as paired in the dashboard
- [x] **P4** Reboot the phone: kiosk starts by itself, box still reachable, **no re-provisioning needed** (Keystore check)
- [x] **P5** Admin PIN works; five wrong PINs lock further tries
- [x] **P6** Phone shows "legacy key" warning only if it really has no box secret
- [x] **P8** QR, the last step: on a normal phone "One last step" leads with **Open the setting**; turning the switch on starts the kiosk (lock screen and time bubble) and Developer options then show USB debugging off. On an Android Go phone (or with the switch greyed out) it leads with USB: with the phone plugged in, "Allow USB debugging?" appears by itself, **Finish over USB** grants the permissions, the lock screen appears and the box page opens to pair the slot
- [x] **P7** USB fallback: with the page's "USB cable" method, a factory-reset phone with USB debugging is set up in one run; a phone with a signed-in account is refused within seconds, before anything is copied

## 3. Coins and time (the money path)
*C1–C4 are pass 1, the rest pass 2*
- [x] **C1** Arm the slot from the phone, insert 1 coin: time is added once; the box counts 1 coin
- [x] **C2** Insert 5 coins quickly: total time is exactly 5 coins' worth, no double credit
- [x] **C3** Dashboard revenue counter matches the coins inserted (₱ amounts exact)
- [ ] **C3b** Vendor page (signed in with the admin QR code): unmask, then "finish collecting": the counter returns to ₱0, `/api/superadmin/collections` lists the collection with the right amount, and `lifetime_coins` did NOT go down; unplug and reconnect the box: the history and the ₱0 are still there
- [x] **M3a** An unclaimed payment is not lost silently: arm a phone, put in a coin, then switch the phone's app off (or unpair it) so it never acknowledges. Wait 24 hours, or for a quick test queue 18 or more unacknowledged coins (the queue then gives up after 30 minutes). The dashboard shows the red banner `N payments (₱X) could not be delivered`; Review lists the coin with its amount, the phone and the reason; `Handled` clears it; the banner survives a reboot of the box until every payment is handled. The same coin does not appear twice
- [x] **M3b** Diagnostics (`/api/diagnostics`) show `orphaned_payments`, the counters `payments_orphaned` and `orphan_write_failures`, and `nvs` (`used_entries` / `free_entries`): note how full the settings flash is on both the C3 and the classic ESP32 builds
- [x] **C4** Time counts down; the phone locks at zero
- [x] **C5** Insert coins while the phone is unlocked and armed: time extends
- [x] **C6** Slot is busy for a second phone while the first is armed
- [x] **C7** Unpaired phone: coins are refused, phone shows the "activate device slot" message
- [ ] **C8** Revenue counter resets after 5 minutes or on logout as intended

## 4. Failure handling (most important)
*F1–F3 are pass 1, the rest pass 2*
- [x] **F1** Pull the **box's power** right after a coin: after power returns the coin is credited once (not lost, not doubled)
- [x] **F2** Switch the **Wi-Fi off** after a coin, back on 30 s later: the phone credits it exactly once
- [x] **F3** Reboot the **phone** mid-session: remaining time is restored, no free time
- [x] **F4** Kill the app on the phone (or let it crash): it comes back by itself, session intact
- [x] **F5** Change the phone's clock: paid time is not extended or shortened
- [x] **F6** Unplug the coin acceptor / simulate a jam: no phantom credit
- [x] **F7** Dashboard "factory reset": Wi-Fi and admin password reset; **lifetime revenue survives**
- [ ] **F8** Unacknowledged payment survives a factory reset and is delivered afterwards

## 5. Security spot checks
*S1 and S3 are pass 2, the rest pass 3*
- [ ] **S1** Phone's HTTP port (8080) answers `/ping` only; `http://<phone>:8080/status` without a signature returns 401
- [ ] **S2** A replayed signed request is ignored (response `OK:DUPLICATE`) — optional, needs a packet capture
- [ ] **S3** Remote admin actions need the admin PIN (broadcast without a PIN does nothing), except `DEPROVISION`, which also runs while USB debugging is on (the website's "Remove from a phone" needs no PIN); with USB debugging off it needs the PIN
- [ ] **S4** Phone cannot be factory-reset or have USB debugging enabled from the network
- [ ] **S5** A provisioned phone joins only the box's PisoKiosk network: with a phone hotspot or another Wi-Fi in range it never connects to it (Android 13 and newer: the system refuses it; any version: a network added by hand during an admin maintenance window is removed within a minute after the window ends). It still rejoins PisoKiosk by itself, and after *Remove from a phone* it can join any network again
- [ ] **S6** Owner QR login: join `PisoKiosk` with your phone, open `http://10.0.0.10` with no password saved: the page offers *Sign in with the admin password* and *Scan admin QR*. Scan opens the camera; a photo of your admin QR code (from `python3 scripts/make_admin_qr.py`) opens the dashboard and the Vendor tab is already unlocked. A photo of another QR code, or of a code made with another key, is refused with a message; five wrong tries lock the phone out for a minute. *Lock now* in vendor mode (or *Sign out*) ends the session. Needs boxes built with the matching `SuperAdminPubKey.h`
- [ ] **H2** Box commands are fresh: from the dashboard send *Locate*/*Identify* to a phone and arena-mode on/off: they work. Then capture one command (`adb logcat -s KioskHttpServer`, or a packet capture) and re-send it by hand a few minutes later (`curl` the same URL): the phone answers `STALE_TIMESTAMP` (403) and does nothing. Reboot the box and send a new command at once: it works (no lock-out while the box relearns the time)

## 6. Keys and updates
*pass 3*
- [x] **K1** `python3 scripts/make_owner_keys.py` runs on your computer; self-test passes; key backed up
- [x] **K2** Box flashed with the public key refuses an unsigned firmware update
- [x] **K4** A signed firmware update installs from the dashboard; an unsigned or older one is refused
- [x] **K5** Phone updates itself from a newer APK (checksum and signature verified)
- [x] **H4a** A good update is kept: update the box over the air from the dashboard. The serial log says `New firmware is on trial`; `/api/diagnostics` shows `ota_image_state: pending_verify`; after about a minute the log says `New firmware confirmed` and the state is `valid`. Power-cycle: the new version is still running
- [x] **H4b** A bad update is reverted: build a signed image with `PLATFORMIO_BUILD_FLAGS="-D PISO_TEST_REFUSE_CONFIRM=1" pio run -e esp32-c3-dev` (it can never pass its start-up check), and update to it. After 5 minutes the log says `restoring the previous firmware`, the box restarts on the OLD version, `/api/diagnostics` shows `ota_last_update_rolled_back: true` and the restart cause `ota-rollback`. No coin session was cut (try with a coin session open: the revert waits until the slot is idle)
- [x] **H4c** Power loss during the trial reverts: update, then pull the power within 30 seconds. The box comes back on the OLD version (this is the intended safe behaviour: restarting a box in the first minute after an update undoes the update)
- [x] **H4d** A second update during the trial is refused, not queued: start another update right after the first one restarts the box. The dashboard says `the box is still checking the firmware it was just updated to`; a minute later the same update works

## 7. Router (openNDS + pisoportal), only after sections 1–4 pass
*pass 3*
- [x] **R1** `piso-setup` (or `update`) finishes, `pisoportal` is running, `pisoportal box` says the box answers, `pisoportal selftest` prints all true
- [x] **R2** Wi-Fi customer sees the portal; Insert Coin starts a coin window; coins grant the right minutes (HyperSpeed and Endurance)
- [x] **R3** One customer pays with 25 one-peso coins in a single window: all 25 are counted (the box keeps one record per window, not per coin)
- [ ] **R4** Layout B: a guest-network device cannot ping the box or the phones; the portal still takes coins
- [x] **R5** From a phone on the customer Wi-Fi, `http://10.0.30.1` and `ssh root@10.0.30.1` (192.168.30.1 on a router set up before the range changed) are refused; from the kiosk LAN (10.0.0.1) both work

## 8. Soak (when everything above passes)
*pass 3*
- [x] **L1** 24 hours running with real use: no unexplained reboots, no lost or doubled coins (compare coins inserted vs credited)
- [x] **L2** 72 hours: same, and memory/uptime in the dashboard diagnostics looks stable

## Issue log
One row per problem. Keep it factual: what you did, what you expected, what happened. Attach evidence (serial or logcat text,
a photo of the dashboard, the time it happened) and say how often it happens. Set **Status** to `open`, `fixed`, or `wontfix`.

**Automatic GitHub issues:** leave `GitHub #` empty. When you push the file, a workflow opens a GitHub issue for every `open` row that
has a checklist item or an actual result, and writes its number (`#12`) into that cell. Setting a row to `fixed` or `wontfix` and
pushing closes its issue. Avoid `|` characters inside cells.

| ID | Checklist item | Severity (blocker / bug / polish) | What I did | Expected | Actual | Evidence (file or paste) | Frequency | Status | GitHub # |
|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | open | |

## Notes and ideas (not bugs)
- C3b skipped (2026-10-11): I can't open super admin. I don't know how to set the global password for it.
- C8 skipped (2026-10-11): Can't open super admin. Don't know how to setup password.
- F8 skipped (2026-10-11): Not sure how to simulate that. It doesn't happen often, overlooking for now.
- RS7 skipped (2026-10-11): There are still users actively connected to the portal.
- R4 skipped (2026-10-11): will check later.

In this checklist "PisoKiosk" means the hidden kiosk network: `PisoKiosk-xxxx` on a router set up with release 1.5.0 or later (the name is in the setup summary), `PisoKiosk` on an older one.

## Baseline summary (update after each session)
| Date | Passed | Failed | Not tested | Open blockers | Verdict |
|---|---|---|---|---|---|
| 2026-10-11 | 59 | 0 | 15 | 0 | no failures so far; 15 not tested |
| 2026-10-16 | 57 ticked | 0 | 22 unticked | 0 | the checklist grew since (router 1.4 and 1.5, firmware 3.6.0); the unticked items are untested, not failed |

## 9. Router setup and customer Wi-Fi (setup/README.md)
*pass 1 unless noted*
- [x] **RS1** `piso-setup.sh` by hand (the advanced part of setup/README.md) on a factory-reset router: it asks for the public Wi-Fi name, a site name and three passwords (Enter generates), shows the review screen, and ends with `SETUP COMPLETE` (all checks pass)
- [x] **RS2** The hidden `PisoKiosk` network appears in no Wi-Fi list; a provisioned phone joins it by itself and rejoins within about a minute when moved to another network
- [x] **RS3** `piso-setup test-coin` counts one inserted coin
- [x] **RS4** Customer phone: the page shows each coin at once with a countdown and **Done**; it is **not** online while coins are going in; after Done (or 15 s idle) it is online with the total time
- [x] **RS5** A second device is refused (Try again) while a window is open; a device that opens empty windows repeatedly is put in a short cooldown
- [ ] **RS6** `piso-setup reconcile` says `RECONCILE OK`; editing a line of `/etc/coinslot.d/revenue.csv` makes it report a broken ledger
- [ ] **RS7** Restart the router in the middle of a window (power cut after the first coin): after it is back, the coins are credited once (pass 2)
- [x] **RS8** `piso-setup telegram`: messages and `/status` work from your chat only; unplug the box for 5 minutes and an alert arrives (pass 2)
- [x] **RS9** `piso-setup lock-admin` from your computer: another device on PisoKiosk can no longer open the router's pages or SSH; `piso-setup unlock-admin` undoes it (pass 2)
- [ ] **RS10** `piso-setup handout` writes `/root/piso-handout.html` with the right names and passwords
- [x] **RS11** `./piso-setup.sh update` (a new file) on a router set up with an earlier version: it ends with all checks passing and customers get the coin page (openNDS moved to the FAS portal on port 2080); paid sessions and the ledger are kept
- [x] **RS12** Collect the box's revenue (super admin): afterwards `piso-setup reconcile` says `RECONCILE OK` and no REVENUE MISMATCH alert arrives in Telegram. Box firmware newer than 3.3.3 keeps counting through a collection (nothing to note); an older box starts its count again at 0 and `reconcile` then prints a note saying so
- [ ] **RS13** (needs your owner key in `tools/pisoportal/owner_key.b64`, then a router built from that) Publish a test release with `scripts/sign_router.py --rollout 100` (docs/RELEASE.md): on the router `piso-setup self-update check` names it, `piso-setup self-update` installs it, customers still get the coin page afterwards and a Telegram message says it is installed and running
- [ ] **RS14** With one phone online as a customer, `piso-setup self-update auto` installs nothing and says it waits for a quiet moment; after the phone leaves, it installs
- [x] **RS15** Publish a release with `--rollout 0`: the router does not install it; `/update` in Telegram says it is rolling out in stages
- [ ] **RS16** Hand-edit `website/update/router-setup.sh` after signing (one character) and publish it: the router refuses it, installs nothing and Telegram says REFUSED
- [ ] **RS17** One click from a bare unit: with the LAN cable in the factory-reset router and the coin box board on USB, the setup page (opened on a phone with the PIN the program prints) checks the computer, finds the router by itself, asks for the shop name and the public Wi-Fi name (empty at the start), writes the box over USB, installs the router (moving it to 10.0.0.1: replug the cable if it asks), pairs the box, saves the summary and the setup sheet in Documents/PisoPhone, then opens the phone setup page already filled in. Run it again on the finished router: it keeps the passwords and passes again
- [x] **RS18** The program's last page: **Test a coin** says the box counted 1 peso after you insert one coin; the customer Wi-Fi steps work; **Set up Telegram alerts** connects (optional)
- [ ] **RS19** *Set up the next unit* on the last page: the program asks for the next router (the old one is not used), the shop name and the Wi-Fi name are empty again, the passwords are new, the board on USB is written again, and the first unit's summary file is still there and unchanged
- [ ] **RS20** No board on USB, or two at once: the install page says so and nothing on the router is changed; with *Do not write it* ticked the router installs without touching the box. A board that is unplugged mid-write ends with a clear message and the install can be tried again
- [ ] **RS21** (router 1.5.0, box firmware 3.6.0) Two routers in range of each other, the first one working: set up the second from a factory reset. Its summary shows `PisoKiosk-xxxx` (the first, if set up before, still `PisoKiosk`); the new box pairs (keep it next to the second router while it pairs), then shows `PisoCoinBox-xxxx` in `piso-setup box-diag`; a phone set up from the second box's *Set up a phone* link joins only `PisoKiosk-xxxx` and finds its box, with the first router still on; customers on the first router notice nothing
- [ ] **RS22** (router 1.5.9/1.5.10, not tried yet) Guests apart: with two guest phones, one on 2.4 GHz and one on 5 GHz (and, if you use one, a phone on an access point plugged into the guest port), neither can ping or open the other; both still get the coin page, pay, browse and *Send a file to the owner* (it arrives in the print inbox). From a guest phone `http://192.168.1.1/`, another `192.168.x.x` computer and the print server's address do not open. `piso-setup diag` lists the tables `piso_guest` and `piso_kiosk` (if it says the table does not exist, the router lacks kmod-nft-bridge).
- [ ] **RS23** (router 1.5.10, not tried yet) Rental phones: with two phones on PisoKiosk, neither can ping or open the other, nor the print server nor a computer; both still get internet, arm a coin on the box and unlock (the box and the phones still talk). A phone cannot open the router's SSH or login page (`http://<router>/`); the same page opens from a computer on a LAN cable. `piso-setup wifi-admin on` opens it on Wi-Fi again, `off` closes it. A phone cannot open `http://192.168.1.1/`; any website works.
- [ ] **RS24** (router 1.5.11, box firmware 3.8.0, not tried yet) One admin page: sign in to the console (`http://admin.hub`, or the router's address), open *Rental phones*: the coin box's page (Overview, Settings, Tools, Vendor) shows inside it with no second sign-in; save a setting, pair or unpair a slot, look at the phones list. The light/dark button of the console changes the box page too (on a box still on 3.7.x it keeps the device's theme: update the box, then it follows). The link under the page opens the box's page on its own. Signed out of the console, `http://<router>:8082/` shows 401; a guest or a rental phone cannot open the box or port 8082.
- [ ] **RS25** (router 1.5.12, box firmware 3.9.0, phone app, not tried yet) `admin.hub` and no mDNS: after the router update, `http://admin.hub` (and `http://<router address>`) opens the console from a computer on a LAN cable; the router's own settings page (LuCI) is at `http://<router address>:8080`; from a rental phone's Wi-Fi neither opens (until `piso-setup wifi-admin on`); `piso-setup console status` says front page; `piso-setup console off` keeps the old layout after the next update. After firmware 3.9.0 the box no longer answers `kioskmanager.local` but opens at its address and through the console's Rental phones tab; with the new app build a phone still finds and pairs with the box (fixed address and broadcast).
- [ ] **RS27** (box firmware 3.10.0, not tried yet) Slot licensing: update a box that has 4 phones paired; it keeps 4 slots (admin page, Phone slots: "4 of 12 unlocked", the note says it keeps the slots it had) and you can unpair one phone and pair another. On a fresh box (factory reset it, then pair): two phones pair and the third is refused with the P500 message; the empty slots show "P500 license". Make a key in the setup program (Owner tools > Slot licenses) for the box code on the page and paste it under *Add slot*: the third slot opens. The same key twice, and a key for another box code, are refused. An operator's factory reset keeps the slots; re-flashing and then *Re-issue* gets them back.
- [ ] **RS26** (phone app, not tried yet) Spoken welcome for QR cards: the first scan of a new card says "Welcome, VIP card activated"; after signing out, the same card says "Welcome, VIP <its name>" (and "Welcome, VIP" if it never got a name); with speech off or in quiet hours it says nothing; the sign-in, time and balance are the same as before.
- [ ] **RS28** (box firmware 3.11.1, not tried yet) Settings saved during a coin drop (FW-02): arm a slot, start dropping a P5 coin, and save any setting on the admin page at the same moment. The full P5 is credited. Then change the coin pulse width (or the coin pin) while a slot is armed: the page says it is saved, and the change takes effect only after the slot is released (serial log: "they apply when the coin slot is idle"). With the slot idle the change takes effect at once.
- [ ] **RS29** (box firmware 3.11.1, not tried yet) Reboot, update and reset with a slot armed (FW-01): with a phone's slot armed (or a coin just dropped), *Reboot*, *Install update* and *Factory reset* are refused with the BUSY message until the slot is released; hold the reset pin 5 s while armed: nothing happens until the slot drains (up to 30 s more), then it resets. With an idle slot they work as before.
- [ ] **RS30** (box firmware 3.11.1, not tried yet) Power cut between the coin and the queue (FW-03): drop a coin on an armed slot and cut the power at once, several times. After each restart, either the phone shows the credit (the payment was queued) and the revenue counts it, or neither does. Revenue never counts a coin the phone was not credited for.
- [ ] **RS31** (box firmware 3.11.1, not tried yet) A coin at an idle slot (FW-06) and a large rate (FW-05): put a coin in with no phone armed: the Orphan payments list shows P1 for "idle-..." (at most one entry every 5 s). Set *Minutes per P1* to 5000: it is saved as 1440. With P20 at 1440 minutes a phone is credited 28 800 minutes, not a wrapped number.
- [ ] **RS32** (router 1.5.13, not tried yet) Clock jump with a voucher (RT-01..RT-07): unplug the router's internet so its clock is stale, let a customer pay and buy a voucher, then restore the internet so the clock jumps forward: the paid session and the voucher are still there with the right time left (nobody lost time, nobody gained any); a customer who pays right after the jump is not given extra time. Cut the power during a payment, power back on: `piso-setup` / the console shows no "LEDGER WAS CHANGED" alert, and the next payment is booked. Two late-coin rounds in one window (arm, pay, release, pay again quickly) are both credited once.
- [ ] **RS33** (phone app, dev build, not tried yet) Touch in games on a tablet (APP-27): insert coins, get time, open an installed game and a built-in game: touch works in both; the pill still works. Let the time run out: the lock screen is back and no touch reaches the game. Add time again: touch works again. If touch is still dead, run `adb logcat -d | grep -i -E "untrusted|obscur"` and `adb shell dumpsys window windows | grep -E -A12 "pisophone"` and keep the output. A phone behaves as before.
- [x] **RS34** RETIRED, not run: removed in router 1.5.25 (feature 033) because the spinning wheel it tested was deleted before it ever ran on a router or phone. The steps are kept so the log stays readable. (router 1.5.14 and 1.5.15) Spinning wheel (feature 005). (1) After the update the coin page still shows the mini-game and no wheel; console Settings says *The wheel is off*. (2) Console, *Turn on...*: the warning shows, the Turn on button stays grey until you type I TAKE RESPONSIBILITY exactly; the wheel is on afterwards and the coin page shows *Spin the wheel* instead of the mini-game. (2b) With the wheel on, a phone that has paid nothing sees *Spin the wheel* on the coin page, opens it, reads the odds and sees *Buy Wi-Fi time first* with Spin grey (1.5.15); the same button is on the *You are online* screen after paying. (2c) 1.5.15 look (1.5.16: the default is 12 LOSE, 6 x2, 1 x3 and a thin x5 jackpot half as wide as the other slices, odds "1 of 39 (2.6%)", chance to win 38.5%; a wheel saved earlier keeps its old odds): the wheel shows one labelled slice per slot (the default 20, wins spread between losses), a lit rim, a pointer that flicks, the odds explained underneath, a sound button that is silent until pressed; it reads well on the shop's real phones and the stop always matches the result shown. (3) Buy time, open the wheel: the odds panel lists the slots; spin a few times at 1 and at 10 minutes: the result in words matches the time left on the coin page and the console's customer list; a bet over half the time or one that leaves under 5 minutes is refused. (4) Press Spin twice quickly: one spin. Pull the router's power right after pressing Spin, start it again, press Spin on the same page again: the time is changed once or not at all, never twice. (5) After 10 spins the page says no spins left; the 10-second wait shows. (6) The sales report and `revenue.csv` did not change; `wheel.log` in the data folder has one line per spin. (7) Console *Turn off*: the wheel disappears at once from open coin pages and the mini-game is back. (8) Type `WHEEL=on` into `/etc/coinslot.conf` and restart the portal with the wheel turned off: it stays off and `wheel.log` says why. Have the warning text read by a lawyer before anyone turns the wheel on in a shop.
- [ ] **RS35** (router 1.5.17, not tried yet; nothing of feature 010 has run on a router) PisoWiFi on a 16 MB router, the product choice, and the installer copy in memory (feature 010). On the owner's Qihoo 360T6M (MT7621, 16 MB NOR and 128 MB NAND). First readings, before anything: `cat /proc/mtd`, `grep . /sys/class/mtd/mtd*/type /sys/class/mtd/mtd*/offset`, `df -k /overlay`, `cat /etc/openwrt_release`, `uname -r`, `free`; write them in `specs/features/010-router-flash-profile/validation.md`. (a) On a build that runs from the NAND, `piso-setup space` prints `flash chip:   large ... nand`. (b) On the NOR-only build it prints `small 16777216 nor`; install PisoWiFi only (`wget -qO- https://pisophone.pages.dev/install.sh | SHOP_PRODUCT=wifi sh`, or the setup program's *PisoWiFi only*): the copy was saved in `/tmp/piso-setup.sh` and is gone after the install (`ls /tmp/piso-setup.sh /root/piso-setup.sh`), `df -k /overlay` shows at least 2048 KB free (write the number down; the summary shows no "less than 2 MB" warning), a coin, a pause, HyperSpeed and a voucher work, a power cut keeps the paid time, there is no `PisoKiosk` Wi-Fi (`iwinfo`, `uci show wireless | grep kiosk`), the coin box still joins its own hidden Wi-Fi, and the console tab that shows the coin box page reads *Coin box*. (c) `opkg list-installed | grep nft-bridge`: is `kmod-nft-bridge` there, or can it be installed on this community build? (the setup warns when it is missing, so the guest and kiosk isolation may be off.) (d) On a router that is already set up with rental phones, run the installer with `SHOP_PRODUCT=wifi`: it warns that rental phones will lose their Wi-Fi and asks; after yes the PisoKiosk Wi-Fi is gone and paid sessions, vouchers and the ledger are unchanged; running it with `SHOP_PRODUCT=both` brings the Wi-Fi back with a new password (the phones need it typed again). (e) A router update (`piso-setup update`) keeps the product. Also write down whether the one-line installer on a factory-address router tells you to run `sh /tmp/piso-setup.sh`, and whether that still works after the move to 10.0.0.1.
- [x] **RS36** (router 1.5.18, feature 011 part A; **passed 2026-10-23 by the owner**. This row was written up after the fact, on 2026-10-29, from `specs/features/ARCHIVE.md` (011-offline-setup) and `spec.md`: the check was run and reported passed without being written down here, and the owner's per-step readings were never recorded, so only the scope below is known, not the numbers.) First setup of a router with its internet cable (WAN) unplugged. Covered: a factory router on official OpenWrt 23.05 or 24.10 with the WAN cable unplugged: the setup program sets it up completely, the computer having downloaded the installer and the OpenWrt packages and the router having checked their signatures before installing; then plugging the WAN cable in afterwards: customers get Wi-Fi and the router's next hourly update works; (c) the owner's Qihoo 360 router: whether its community package sources serve signed lists (offline setup works) or the setup falls back to the online way, with `/etc/opkg/distfeeds.conf` and the message noted; (e) the router's free memory and the copy time. Only the letters (c) and (e) come from the validation file; the other steps are as the spec lists them, and their letters (a, b, d) were not recorded. The spec and the validation file name this router "360 T6GS", the test log header and RS35 say "360T6M"; which one it was is not settled here. The validation file confirms only that RS36 as a whole passed, and that the (c) message and the (e) figures were not pasted in. **Feature 041 deletes the offline path this tested and will retire this row** (not yet merged, so the row stands as passed). **RETIRED in router 1.5.26 (feature 041):** the path it tested, a first setup where the computer copies the packages to a router with no internet, was deleted, so it is not run again. It passed once, as recorded here. RS37 is not affected and stays.
- [x] **RS37** (router 1.5.18, feature 011 part B; **passed 2026-10-23 by the owner**. Written up after the fact on 2026-10-29 from `specs/features/ARCHIVE.md` (011-offline-setup) and `spec.md`; no per-step readings were recorded.) The coin box network gets its own name, so two shops' routers nearby no longer answer to the same one. (a) On an older router (or one forced to the shared name `PisoCoinBox`): run `piso-setup box-wifi-name`; the box comes back on `PisoCoinBox-<id>` within four minutes and takes a coin; **a coin put in during the switch is credited exactly once**. (b) With router 1 on its own name and powered on next to it, a factory-reset box pairs with router 2 at the first try. (c) With router 1 forced to `PisoCoinBox`, the pairing tip appears and switching router 1 off lets the pairing finish. The validation file records by name that (a), including the coin credited once, passed; it says only that RS37 as a whole passed, so whether (b) and (c) were each run is not written down. **Stays after feature 041**: it tests part B, which 041 does not touch.
- [ ] **RS38** (box firmware 3.12.0 and the phone app with feature 013, not tried yet) Shop branding set on the box reaches the phones (feature 013). (a) On the box's admin page, *Settings*, the *Shop branding* section: pick the palette *Rosas*, type a shop name and a title, set the animation to *Normal*, press *Save branding*: the page says it was saved; a paired phone's lock screen changes colour within a few seconds, shows the title in place of "PisoPhone" and the shop name running under it, and the *Config Synced* message does **not** appear for it (a branding push is not a config update). (b) Try to save a 33-character name, a title with `;` or a line break, and 32 accented letters (ñ): each is refused with a reason and nothing changes on the page after a reload. Clear all four boxes and save: the phones go back to the TechNet look, title "PisoPhone", no name row. (c) Restart the box: *Shop branding* still shows what you saved (it survives a restart) and a phone that is re-paired gets the look again. (d) Switch the box off, then restart the phone: the lock screen still draws the shop's palette, title and name before the phone is unlocked (it is read from the preferences that work before unlock). (e) Open the owner vault on the phone: the *Shop branding* card shows the same four settings, says when the box last sent them, and has nothing to edit. (f) Heat: with the phone warm (a game for ten minutes, or `adb shell cmd thermalservice override-status 2`), the shop name stands still, centred and fully readable, and the background stops moving; the "by TechNet Solutions" words stay and only their shine rests. (g) Each animation level looked at on a real T606 phone: *Off* stands still, *Low* shows 4 stickers and a slow drift, *Normal* is the screen as it was before this feature, *High* drifts quicker; at every level a coin still brings the coin rain, the sparkle, the *time added* message and the spoken confirmation, and the "by TechNet Solutions" line is on the lock screen and the home screen, readable on every palette and not over the coin-box status, the rate, the card link or the owner-settings button. (g2) Look at three things: a 20-character title on a narrow phone (does the ellipsis look sensible, and the phone chip stays on screen); with a shop palette set the palette button at the bottom left is not drawn and the box status stays centred, while with no palette it still cycles the three tints; with a title set the header shows the title alone (no \"TechNet\" before it), with none set it reads \"TechNet PisoPhone\"; at *Off* the shop name stands still while the credit's shine still sweeps on a cool phone. (h) Write down: the settings memory the four settings use (`nvs` stats in the serial log if available) against the 512-byte limit, and whether the lock screen's footer and everything above it sit one line higher than before (expected: the credit takes that line). Also run the new golden pictures: `./gradlew :app:testDebugUnitTest -Pscreenshots.update=true` once, look at the difference pictures of the existing lock screen goldens (only the bottom line may differ) and commit them.
- [ ] **RS39** (an old phone build with the new box, and a new phone build with a live 3.11.1 box; not tried yet) Nothing old breaks (feature 013). (a) Pair a phone that still runs build 495 to a box on 3.12.0 that has branding saved: pairing, a coin, credited time, the rate line and a config save all work as before; the phone shows nothing new and shows no error (it ignores the branding push). (b) Pair a phone with the new build to a box still on 3.11.1 (no branding in it): pairing, a coin, credited time, the rate and a config save work as before; the phone shows the TechNet look, title "PisoPhone", no name row and the "by TechNet Solutions" line; the owner vault's *Shop branding* card shows the defaults and *Last from the box: never*. (c) Update the box from 3.11.1 to 3.12.0 with phones paired: they keep working, and a coin dropped during the update is still credited exactly once. (d) Put one ₱1 coin in during a branding save: the credit is exactly one coin's time.

- [ ] **RS44** (router 1.5.19, not tried yet; nothing of feature 015 has run on a router) A router that keeps running. (a) After the update `ls /etc/coinslot.d` shows `alerts.txt` after the first alert, and `/etc/init.d/piso_monitor status` says running **even if Telegram was never set up** (`ls /etc/piso-monitor.conf` fails). (b) Freeze the portal: `kill -STOP $(pidof pisoportal)`. Within 4 minutes `ps | grep pisoportal` shows a new process id, `tail /etc/coinslot.d/alerts.txt` has one line "the portal program did not answer for 3 minutes and was restarted", and the coin page answers again; with Telegram set up the same line arrives once. Do it while a customer window is open with a coin in: the coin is credited once (look at `piso-setup status` and the ledger). (c) The console front page shows an *Alerts* tile with the line, and the setup program's Check-up has the *Router alerts* line. (d) Month roll: set the router's date to the last day of a month, make a payment, set it to the next month (or wait), make another: `ls /etc/coinslot.d` shows `revenue-YYYY-MM.csv` for the old month and `revenue.csv` starting with a `carry` row; `pisoportal verify all` says OK with the same pesos as before; the console's all-time total and `piso-setup reconcile` are unchanged. Put the date back and check nothing was credited twice. (e) Owner tools, *Sales history on the router*: copy one month, open the copy, confirm it is gone from the router and `pisoportal verify all` still says OK. (f) `df /overlay` before and after, `free` after a day: write the numbers down.
- [ ] **RS46** (coin box firmware 3.12.1, not tried yet; nothing of feature 017 has run on a box) A coin box that keeps working. (a) **Two phones, one switched off:** pair two phones, switch one off, put a coin in on the other: it is credited within a few seconds as before; the serial log shows "[AUTH WORKER] ... skipped: ... did not answer twice" for the off phone and its payments stay queued; switch it on again and within about 40 seconds it is credited once. (b) **A held-open port:** from a computer on the same Wi-Fi run `nc <box ip> 81` and send nothing; drop a coin on a phone at the same time: the coin is counted and credited with no 2-second stall, and the connection is closed by the box after about 2 seconds. (c) **A normal phone connects** and arms the slot as before (the WebSocket handshake changed). (d) **Beacon:** a phone that was away comes back near the box and finds it (it now probes, and the box answers at once); with a packet capture the box's unsolicited broadcast comes about every 15 s. (e) **Soak:** after 24 hours, `free heap` and `largest free block` in the diagnostics (`[HEALTH] ... largest=`) do not fall: write the first and last readings. (f) A phone that is switched off AND a coin dropping do not make the box restart; if the AuthWorker is ever stuck the log has "AuthWorker made no progress for 60 s" followed by a restart reason `worker-stall` (hard to provoke on purpose: a note is enough if it never happens). (g) Update the box from 3.12.0 with a phone paired; a coin dropped during the update is credited exactly once.

- [ ] **RS45** (router 1.5.19, not tried yet) A power cut at the worst moment (feature 021, AO-11). With a coin box and a phone on the PisoWiFi page, start a window ("Insert coin") and put a coin in; **pull the router's power within about one second of the coin dropping**, several times (the moment is short: ten tries is not too many; a smart plug on a timer makes it easier). Power back on and wait 3 minutes. For every try, `piso-setup status` and the console's *Sales* show the coin credited exactly once (never zero, never twice), the customer has the paid time, and the box's own counter and the router's ledger agree (`piso-setup reconcile`). Also pull the power with an armed window and **no** coin: after the restart there is no credit and `ls /etc/coinslot.d/open` is empty within a few minutes. Write down how many tries landed in the moment between the coin and the router's screen update.
- [ ] **RS47** (router 1.5.20, not tried yet; nothing of feature 022 has run on a router) Open the coin box's admin page from the link inside the router console. On *Quick time adjust* press **Add** for one paired phone, then **Subtract**, then **Add** for ALL phones, then once with one phone switched off. Expect: no "refused" message, the page comes back, and the phone's time changes by the minutes you typed (once, not twice). Then open the page by the box's own address and repeat once. Lastly, from another website's page, a hidden form posting to the console must still be refused (use the console's own *Open* link only; do not try to attack your own shop's box).
- [ ] **RS50** (firmware 3.12.2, not tried yet) PisoPhone-only setup on a new or erased ESP32-C3 over USB on Windows: write the software with the setup program, then the board must stay up (no restart loop in a serial monitor at 115200), answer `PISO-HELLO` with a line starting `PISO-BOX `, take the Wi-Fi and admin password, and join the shop Wi-Fi.
- [ ] **RS49** (firmware 3.12.3 and the phone app, not tried yet; nothing of feature 024 has run on a box or phone) With one tablet and one phone paired to one box: on the admin page, Phone profiles, tap **PisoTab** and **PisoPhone Pro**, Save, give the tablet's slot PisoTab and the phone's slot Standard. Expect: the tablet's lock screen and home screen header say PisoTab; the phone still says what it said before (the Shop branding title, or PisoPhone). Rename the profile and Save: the tablet follows within a minute. Put the tablet's slot back on Standard: its label goes. The minutes a coin gives are the same as before at every step.
- [ ] **RS48** (phone app, not tried yet; nothing of feature 023 has run on a phone) On a cheap phone: set a shop name on the box, then look at the lock screen and the home screen. Expect: a full-width band, name in big bold gold-on-dark (or the shop palette), running left without a stop and a slow shine crossing; with no shop name the band says PisoPhone. Leave it on the lock screen for an hour: the phone must not get hot and the battery must not drain faster than before. Put the phone in battery saver: the name stands still, centred and whole. The timer, rate, coin button and card link stay fully visible on the smallest phone.
- [ ] **RS51** (phone app, not tried yet; feature 027) On a PisoPhone-only shop (coin box on the shop's normal router), set up a new rental phone with the shop's Wi-Fi name and password, once with that network already saved on the phone and once without. Expect: the phone joins the network, finds the box and pairs; no "refused to add" line. If the line still shows, write down the Android version and "device owner" text it now carries.
- [ ] **RS52** (firmware 3.12.4 and the setup page, not tried yet; feature 028) On a PisoPhone-only box: open its page, *Set up a phone*. Expect the setup page to open with the shop Wi-Fi name filled in and its password filled in ("given by your coin box"). Make the QR code, scan it on a factory-reset phone at the welcome screen. Expect the phone to join the shop Wi-Fi, download the app, find the box and pair. The full install (hidden PisoKiosk) must still work the same.
- [ ] **RS53** (phone app, not tried yet; feature 029) On a rental phone that was set up before this update (YouTube missing): after the update and a minute of the kiosk running, YouTube, Chrome, Maps and Photos are on the home screen again and open. On a newly set up phone, finish the first sign-in: they are still there. Settings, the dialer and Google's Wellbeing stay out of sight.
- [ ] **RS54** (phone app, not tried yet; feature 030) A PisoPhone-only rental phone on the shop Wi-Fi: after the update the "Wi-Fi: joining <network>..." toast no longer repeats every minute (at most "connected" every ten minutes), and the phone stays connected and finds the box.
- [ ] **RS55** (phone app, not tried yet; feature 031) Sign in as admin (PIN), open Settings, Apps, pick an installed game: Uninstall works. After the window ends (or the phone locks) the same screen shows Uninstall greyed out again, and a customer cannot reach Settings.
- [ ] **RS56** (router program, not tried yet; feature 032: nothing of it has run on a real router) On a real router with sales in the ledger: in the console, Settings, set **Sales record: most space it may use** to 256 KB (the smallest), then let a month roll (set the router's date to the first day of the next month and make one sale, or wait for it). Expect: the sale is recorded and the customer is online as usual; the console front page tile **Sales detail** shows fewer months than before and says how many older months were deleted; the **All time** total and the **Records** tile do not change except by that sale; `piso-setup status` prints a *Sales record* line with the months kept, the months deleted on purpose and the same lifetime total; `pisoportal reconcile` still says RECONCILE OK against the coin box; `pisoportal verify all` says OK; `ls -l /etc/coinslot.d` shows `revenue-pruned.txt` with one short line per deleted month and the oldest `revenue-YYYY-MM.csv` files gone; `df -h /overlay` (or `/`) shows the flash use dropped. Then delete one remaining `revenue-YYYY-MM.csv` by hand: `pisoportal verify all` must say BAD. Last, set the space back to 1024 KB. Also confirm a router updated from 1.5.x (one single `revenue.csv`, no monthly files) is pruned correctly at its first month roll. Pass: the total never moved and no sale was lost.
- [ ] **RS57** (router 1.5.26, not tried yet; feature 041) A first setup that needs only internet. (a) On a router with internet (modem online in the WAN port), run the one-liner the box page gives (`wget -qO- https://pisophone.pages.dev/install.sh | sh`) or the setup program on a computer: it runs to its end and the shop works. (b) Read `website/start/shop.html` before starting: it says *the router needs internet for the first setup* above the steps. (c) Unplug the modem, factory reset the router and run the setup program: it stops before it changes anything on the router and says *The router has no internet, and the first setup needs it*; nothing was changed (check `cat /etc/piso-setup.conf` does not exist). Plug the modem in, wait a minute, tap *Try again*: it completes. Paste the exact message you saw.
- [ ] **RS58** (router 1.5.29, not tried yet; feature 036: nothing of it has run on a router) The money line on the console front page, under *Money today*. (a) On a router with sales and the box paired, open the console: the line says *Money agrees. Coin box counted P..., sales record P...* with the same number twice (current firmware). (b) Put a coin in a rental phone and reload: the line still agrees (rental-phone coins are not in the Wi-Fi count). (c) Pay one Wi-Fi coin and reload at once: it agrees, or for up to a minute says *coins counted but not yet in the record* in gold, never red, and agrees after a minute. (d) Collect the box's revenue (the vault reset): the line shows *The box's count restarted...* and is never red; it loses the note after the next hourly check (up to an hour). (e) Unplug the box: within about a minute the line says *Coin box is not answering* and still shows the sales record; plug it back and it agrees again. (f) Open the console on a phone: the line is readable and fits the screen. (g) A coin still buys its time exactly once while the console is open on this page all day. Result: ______
- [ ] **RS59** (router 1.5.29, not tried yet; feature 044: nothing of it has run on a router) The update line on the console front page, under the money line. (a) On a router with automatic updates on, after the hourly update check (the router at its minute, the coin box half an hour later; or run `piso-setup self-update auto` and `piso-setup box-update auto`): the line says *Router X is up to date. Coin box Y is up to date.* (b) Run `piso-setup auto-update off` and publish or wait for a newer coin box firmware: the line says *Coin box ... is available. Automatic updates are off: run piso-setup box-update.*, neutral; after a day it turns gold and one line appears in the console's alerts (and in Telegram if set up), once a day; `piso-setup auto-update on` clears it at the next check. (c) With a customer online (or a rental phone with time running) during the hourly check: the line says *waiting for a quiet moment*; after a day it turns gold, says how many days and the command; run that command after closing and the line goes back to up to date. (d) Unplug the coin box: after the next check the line says *Coin box: not checked: the coin box did not answer*, neutral, with no alert; plug it back and it recovers. (e) If an update ever fails (the router says *FAILED and was undone*, or the box refuses): the line is gold at once with the reason, and one line is in the alerts, not a second Telegram message. (f) Open the console on a phone: the line is readable and fits the screen. (g) A coin still buys its time exactly once while the line is gold. Result: ______
- [ ] **RS60** (setup program, not tried yet; feature 045) The setup computer's side of a first setup in a real shop. (a) The computer's Wi-Fi on the shop modem at 192.168.1.1 and the cable on a factory router: the Router step stops with the "two networks" advice and sends nothing to any router; with the Wi-Fi on the phone's hotspot it finds the router and goes on. (b) The same run on a **Windows 10** computer: after the router password was set, *Try again* logs in with the saved password (SU-09); if a login that needs a password hangs, write it in the Issue log (SU-14, the built-in OpenSSH is older than 8.4). (c) The saved folder holds `pisophone-setup-log-*.txt` with the computer's addresses at the top and no password in it; a router with OpenWrt 25.x (apk) is refused with its release named before anything is changed (SU-06a). (d) A router that starts at another address (GL.iNet 192.168.8.1 or Cudy 192.168.10.1) is found, named and moved to 10.0.0.1. (e) The Phones step on a router with no internet says to plug the modem into WAN and shows the setup page after that.
- [ ] **RS61** (router installer, not tried yet; feature 046) On the 360T6M with the router program of the new release. (a) A first setup from a **Linux laptop** and from the **Armbian box**: after the router moves to 10.0.0.1 the computer gets a 10.0.0.x address by itself within about a minute, without replugging the cable (SU-03); write down in the Issue log whether the router's LAN ports are `lan1`..`lan4` in `/sys/class/net` (DSA) or not. (b) Two freshly written coin boxes powered on at the bench, two routers: each router pairs only the box its own setup wrote, and the log of the other says that another coin box joined and was not paired (SU-11); with *Do not write it* ticked any box is paired as before. (c) The end of the setup over ssh: the summary arrives on the computer and `cat /tmp/piso-setup.state` says `DONE`; if the session was never cut by the SSH and LuCI restart, SU-08 closes `wont-fix` (SU-08). (d) Plug a modem whose LAN is 10.x.x.x/8 into the WAN port after the setup: within the hour the monitor alert and `piso-setup status` name the overlap (SU-05). RS17, RS20, RS21 and RS36 are run with this one.
- [ ] **RS62** (coin box firmware 3.12.5 and the setup program, not tried yet; feature 047) With a spare **ESP32-C3** and a shop router with 2.4 GHz: set up a PisoPhone-only box through the setup page, but type the **Wi-Fi password wrong** on purpose. The box never joins; the page's message says the box reports a wrong password (`why=wrong-password`), and a wrong network name gives "cannot find that network". Run the setup again with the **right** password **without ticking the erase box**: the page does not ask for ERASE, does not write the software again, the box takes the new details and joins (address shown). Then (b) run it a third time on that box: it refuses and asks for ERASE (it has joined). (c) A box that joined once and is then given a wrong Wi-Fi from its admin page (or a box with a coin counted) is never offered new details over USB. Write what the serial hello says (`setup=` and `why=`) in the Issue log.
- [ ] **RS63** (router 1.5.31, shop branding, not tried yet; feature 049) On the 360T6M with the new release, a real **Android** phone and a real **iPhone**. (a) In the console on your computer open **Branding**: type a shop name, pick a palette and a layout, write a promo, house rules, contact and hours in English and Filipino; the preview follows. Press **Publish**. Join the guest Wi-Fi on the phone: the **captive-portal sheet** (the small window that opens by itself) shows the branded page with the shop name, colours, promo, rules, contact line and "by TechNet Solutions"; write down whether anything is cut off or hard to read in the sheet, on both phones. (b) Pay ₱1: **Insert Coin works exactly as before** and the phone gets online with the right time. Try this in each of the four layouts, and on a short phone check that **Poster's** stuck coin button does not cover the plans. (c) Tap the language button: the page turns Filipino, stays Filipino after reloading, and goes back to English; **the owner reads the Filipino and signs `evidence/filipino-wording.md`** (the release stays at 0% until then). (d) Make a brand pack with `scripts/make_brand_pack.py` and your owner key; install it from the Branding tab on a phone-sized console, and once again with `piso-setup brand-pack <file>` over SSH; a pack signed with another key must be refused, and nothing may change. After Publish the logo shows in the page header and as the home-screen icon (Add to Home screen); buy a voucher code and check the saved voucher file: it must be named for the shop (`<Shop-name>-voucher-XXXXX.html`), say the shop's name and no TechNet, and open the router page when its link is tapped on the shop Wi-Fi. (e) **Restore the standard TechNet look**: the page is the old one again. (f) Publish a branding, then run `piso-setup update`, reboot the router and do a firmware upgrade with the keep-settings box ticked: the branding and the logo are still there. (g) In the console on a phone the bottom bar shows all seven tab names and the Branding editor is usable. Write down what you saw in the issue log.

## 10. PisoPhone only setup (no router; firmware 3.11.0 and the setup program)
Not tried on a real board. With a new ESP32-C3 on USB and an ordinary Wi-Fi router:
- [ ] Run the setup program, choose *PisoPhone only*: the software is written, the board joins the Wi-Fi and the page shows the admin address and password.
- [ ] The saved summary file opens only for you; the admin page opens at that address with that password.
- [ ] A rental phone on the same Wi-Fi finds the box and pairs; a coin unlocks it.
- [ ] Run the setup again on that working box: it asks to type ERASE first.
- [ ] A box that already has its own admin password refuses the USB Wi-Fi command (nothing changes).
