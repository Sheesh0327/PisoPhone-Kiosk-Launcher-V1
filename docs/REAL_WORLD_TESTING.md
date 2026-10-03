# Real-world testing checklist

Fill this in while testing on real hardware. It is the baseline of what works and what does not: tick what passes, write what
fails in the **Issue log** at the bottom, then paste the log (plus the evidence listed there) to the developer so each issue
becomes a fix. Commit this file after each session so progress is visible in git history.

## How to run it (one person, three passes)
- **Pass 1: money path, about 2 hours.** B1–B3, P1–P4, C1–C4, F1–F3. If anything here fails, stop, log it, and send it before going on.
- **Pass 2: the rest of the box and phone, a second session.** Remaining B, P, C and F items, plus S1 and S3.
- **Pass 3: later, only when passes 1 and 2 are clean.** K (keys, licenses, updates), R (router), L (soak).

Tip: a phone hotspot you can switch off, and a power strip with a switch, make the Wi-Fi-drop and power-cut tests quick to do alone.
Save `adb logcat -d > phone.log` and the serial output right after each failure.

**Legend:** `[x]` pass, `[ ]` not tested yet, `[!]` failed (add a line to the Issue log with the same ID).

## 0. Test setup (fill in first)
| Item | Value |
|---|---|
| Date / tester | |
| Firmware version and board (`esp32-c3-dev`, etc.) | |
| APK build number (`app.json` versionCode) | |
| Phone model / Android version | |
| Router model / OpenWrt version / layout (A or B) | |
| Coin acceptor model | |

Collect logs as you go: box serial output at 115200 baud (`pio device monitor`), phone `adb logcat -s KioskSecurity KioskService`
(`adb logcat -d > phone.log` after a failure), router `logread -e coinslot -e opennds`.

## 1. Box basics
*B1–B3 are pass 1, the rest pass 2*
- [ ] **B1** Box boots; serial shows setup-AP password and admin password (no factory passwords)
- [ ] **B2** Box opens its setup Wi-Fi when no Wi-Fi is saved; joins your Wi-Fi once configured
- [ ] **B3** Dashboard opens; first-run checklist shows; it disappears when its items are done
- [ ] **B4** Changing the admin password works; old password stops working
- [ ] **B5** Five wrong admin passwords lock login for a minute
- [ ] **B6** Reboot from the dashboard works; settings survive
- [ ] **B7** Wi-Fi drops and returns (turn the router off/on): box reconnects by itself
- [ ] **B8** Box runs 1 hour idle without rebooting (check serial for restart reasons)

## 2. Phone install and pairing
*P1–P4 are pass 1, the rest pass 2*
- [ ] **P1** Install the APK from the website; phone becomes the locked kiosk (device owner setup)
- [ ] **P2** "Install & Provision" link gives the phone its box secret and an admin PIN
- [ ] **P3** Phone finds the box and shows it online; slot shows as paired in the dashboard
- [ ] **P4** Reboot the phone: kiosk starts by itself, box still reachable, **no re-provisioning needed** (Keystore check)
- [ ] **P5** Admin PIN works; five wrong PINs lock further tries
- [ ] **P6** Phone shows "legacy key" warning only if it really has no box secret

## 3. Coins and time (the money path)
*C1–C4 are pass 1, the rest pass 2*
- [ ] **C1** Arm the slot from the phone, insert 1 coin: time is added once; the box counts 1 coin
- [ ] **C2** Insert 5 coins quickly: total time is exactly 5 coins' worth, no double credit
- [ ] **C3** Dashboard lifetime coin/earnings counters match the coins inserted (₱ amounts exact)
- [ ] **C4** Time counts down; the phone locks at zero
- [ ] **C5** Insert coins while the phone is unlocked and armed: time extends
- [ ] **C6** Slot is busy for a second phone while the first is armed
- [ ] **C7** Unlicensed/extra slot: coins are refused, phone shows the license message
- [ ] **C8** Revenue counter resets after 5 minutes or on logout as intended

## 4. Failure handling (most important)
*F1–F3 are pass 1, the rest pass 2*
- [ ] **F1** Pull the **box's power** right after a coin: after power returns the coin is credited once (not lost, not doubled)
- [ ] **F2** Switch the **Wi-Fi off** after a coin, back on 30 s later: the phone credits it exactly once
- [ ] **F3** Reboot the **phone** mid-session: remaining time is restored, no free time
- [ ] **F4** Kill the app on the phone (or let it crash): it comes back by itself, session intact
- [ ] **F5** Change the phone's clock: paid time is not extended or shortened
- [ ] **F6** Unplug the coin acceptor / simulate a jam: no phantom credit
- [ ] **F7** Dashboard "factory reset": Wi-Fi and admin password reset; **license slots and lifetime revenue survive**
- [ ] **F8** Unacknowledged payment survives a factory reset and is delivered afterwards

## 5. Security spot checks
*S1 and S3 are pass 2, the rest pass 3*
- [ ] **S1** Phone's HTTP port (8080) answers `/ping` only; `http://<phone>:8080/status` without a signature returns 401
- [ ] **S2** A replayed signed request is ignored (response `OK:DUPLICATE`) — optional, needs a packet capture
- [ ] **S3** Remote admin actions need the admin PIN (broadcast without a PIN does nothing)
- [ ] **S4** Phone cannot be factory-reset or have USB debugging enabled from the network

## 6. Keys, licenses, updates
*pass 3*
- [ ] **K1** `python3 scripts/make_owner_keys.py` runs on your computer; self-test passes; key backed up
- [ ] **K2** Box flashed with the public key accepts a license issued with `generate_license.py issue`
- [ ] **K3** The old deprecated license keys are now refused
- [ ] **K4** A signed firmware update installs from the dashboard; an unsigned or older one is refused
- [ ] **K5** Phone updates itself from a newer APK (checksum and signature verified)

## 7. Router (OpenNDS), only after sections 1–4 pass
*pass 3*
- [ ] **R1** Package installs (`opkg install`), service starts, `coinslot-listener.sh box` says the box answers
- [ ] **R2** Wi-Fi customer sees the portal; Insert Coin starts a coin window; coins grant the right minutes (HyperSpeed and Endurance)
- [ ] **R3** Layout A: change the box's IP; the listener finds it again within ~30 s (set `GW_BOX_MAC`)
- [ ] **R4** Layout B: a guest-network device cannot ping the box or the phones; the portal still takes coins
- [ ] **R5** Voucher code restores time on another device; pause/resume works for Endurance

## 8. Soak (when everything above passes)
*pass 3*
- [ ] **L1** 24 hours running with real use: no unexplained reboots, no lost or doubled coins (compare coins inserted vs credited)
- [ ] **L2** 72 hours: same, and memory/uptime in the dashboard diagnostics looks stable

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
-

## Baseline summary (update after each session)
| Date | Passed | Failed | Not tested | Open blockers | Verdict |
|---|---|---|---|---|---|
| | | | | | |
