# Real-world testing checklist (PisoWiFi)

Tick what passes, mark failures `[!]` and add a row to the **Issue log** with the same ID, then send the log plus the evidence it
names. Commit this file after each session. Legend: `[x]` pass, `[ ]` not tested yet, `[!]` failed.

**Passes (one person):** pass 1 = B1–B4, R1–R3, F1–F2 (about 2 hours; stop and report on the first failure). Pass 2 = the rest of
B, R and F. Pass 3 (only when 1 and 2 are clean) = K and L.

## 0. Test setup
| Item | Value |
|---|---|
| Date / tester | |
| Firmware version and board | |
| Router model / OpenWrt version / layout (A or B) | |
| Package version (`opkg list-installed opennds-coinslot`) | |
| Coin acceptor model | |

Logs: box serial at 115200 baud (`pio device monitor`); router `logread -e coinslot -e opennds`; `/usr/bin/coinslot-listener.sh report 1`.

## 1. Box
- [ ] **B1** Box boots; serial shows setup-AP and admin passwords (no factory passwords)
- [ ] **B2** Dashboard opens; admin password change works; five wrong passwords lock login for a minute
- [ ] **B3** Box joins Wi-Fi; reconnects by itself after the router restarts
- [ ] **B4** Gateway key set on the box; `curl http://<box-ip>/api/gateway/challenge` returns a nonce
- [ ] **B5** Box runs 1 hour idle without rebooting

## 2. Router and portal
- [ ] **R1** `opkg install` of the package works; `coinslot-listener.sh box` says the box answers
- [ ] **R2** A customer device sees the portal; Insert Coin opens a coin window on the box
- [ ] **R3** Coins grant the right minutes for HyperSpeed and Endurance (check the rates table)
- [ ] **R4** Top-up while connected extends time; fair-use slowdown starts after the limit (HyperSpeed)
- [ ] **R5** Voucher code restores remaining time on another device; Endurance pause/resume works
- [ ] **R6** Layout A: change the box's IP; the listener finds it again within about 30 s (`GW_BOX_MAC` set)
- [ ] **R7** Layout B: a guest device cannot reach the box; the portal still takes coins
- [ ] **R8** Revenue report (`coinslot-listener.sh report 7`) matches the coins inserted

## 3. Failure handling
- [ ] **F1** Cut the box's power right after a coin: after power returns the customer gets the time once (not lost, not doubled)
- [ ] **F2** Restart the router mid-session: paid customers keep their remaining time (voucher/resume)
- [ ] **F3** Unplug the coin acceptor or simulate a jam: no phantom credit
- [ ] **F4** Factory reset the box from the dashboard: license and lifetime revenue survive; Wi-Fi and admin password reset

## 4. Keys and updates
- [ ] **K1** `python3 scripts/make_owner_keys.py` works on your computer; key backed up
- [ ] **K2** A box with the public key accepts a license from `generate_license.py issue`; old license keys are refused
- [ ] **K3** A signed firmware update installs; an unsigned or older one is refused

## 5. Soak
- [ ] **L1** 24 hours of real use: no unexplained reboots, coins inserted = minutes granted
- [ ] **L2** 72 hours: same

## Issue log
| ID | Checklist item | Severity (blocker / bug / polish) | What I did | Expected | Actual | Evidence | Frequency | Status |
|---|---|---|---|---|---|---|---|---|
| 1 | | | | | | | | open |

## Baseline summary
| Date | Passed | Failed | Not tested | Open blockers | Verdict |
|---|---|---|---|---|---|
| | | | | | |
