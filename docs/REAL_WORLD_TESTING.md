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

## 0. Test setup (fill in first)
| Item | Value |
|---|---|
| Date / tester | 10-10-2026/Mil Admin |
| Firmware version and board (`esp32-c3-dev`, etc.) | 3.3.3/esp32-dev-c3 |
| APK build number (`app.json` versionCode) | 411 |
| Phone model / Android version | Infinix Hot 40i/13 |
| Router model / OpenWrt version / layout (A or B) | 360T6GS/23.5.0/ |
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
- [ ] **C3b** Vendor page: unmask, then "finish collecting": the counter returns to ₱0, `/api/superadmin/collections` lists the collection with the right amount, and `lifetime_coins` did NOT go down; unplug and reconnect the box: the history and the ₱0 are still there
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

## Baseline summary (update after each session)
| Date | Passed | Failed | Not tested | Open blockers | Verdict |
|---|---|---|---|---|---|
| 2026-10-11 | 59 | 0 | 15 | 0 | no failures so far; 15 not tested |

## 9. Router setup and customer Wi-Fi (setup/README.md)
*pass 1 unless noted*
- [x] **RS1** `piso-setup.sh` by hand (setup/MANUAL.md) on a factory-reset router: it asks for the public Wi-Fi name, a site name and three passwords (Enter generates), shows the review screen, and ends with `SETUP COMPLETE` (all checks pass)
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
