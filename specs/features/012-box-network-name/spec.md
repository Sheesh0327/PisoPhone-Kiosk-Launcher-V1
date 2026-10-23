# Feature 012: no coin box mix-ups when a second router is set up nearby

Status: **draft, waiting for the owner's approval. No code until approved.**
Branch: `feat/012-box-network-name` (from `beta`). Area: router, setup. Release: router (`setup/RELEASE` 1.5.17 -> 1.5.18). Proposed to ship before feature 011 (011 then takes the next router release).
Backlog rows: none (owner report 2026-10-23: while setting up router 2 next to a working router 1, coin box 2 did not reliably talk to router 2).

## How to read this spec (for the execution model)
"Facts", "Decisions", "Names", "Invariants", "Constraints" and "Success criteria" are the contract; "Owner decisions" must be answered before code. Every criterion names its test file. If something needed is not here, stop and ask the owner (constitution rule 8); do not guess.

## Intent
The owner sets up router 2 and coin box 2 while router 1 already runs nearby. Box 2 sometimes joins router 1 instead of router 2 and the pairing fails or is unreliable. The cause is Wi-Fi, not the modem or the internet: during pairing a new box only knows the name built into its firmware, and an older router 1 still broadcasts that same name. This feature gives every router's coin box network a name of its own (also on routers set up before per-router names existed), and makes the pairing say what to do when another router is in the way. Nothing here may cost a customer money or time.

## Facts (checked in the code on 2026-10-23, router 1.5.17)
1. **The name a new box knows:** the firmware has `PisoCoinBox` with the password `PisoCoinBox@Setup` built in (`esp32_firmware/include/SetupGate.h:19-20`); a factory-reset box goes back to them (`esp32_firmware/src/Config.cpp:759-760`). The box connects with `WiFi.begin(ssid, pass)` (`esp32_firmware/src/main.cpp:107`), which joins the first access point that answers to the name, not the strongest.
2. **Pairing:** `pair_box` (`setup/src/50-coin-box.sh:32-100`) opens the box network as `PisoCoinBox` with the built-in password and no MAC lock, waits up to `PAIR_WAIT` for a station, then locks the network to that MAC (`lock_box_network`). Its tips at 60, 150 and 300 s talk about power, firmware and distance; none mentions another router.
3. **Each router's own names:** since router 1.5.0, a newly set-up router gets `PisoKiosk-<id>` and `PisoCoinBox-<id>`, `<id>` being the last two bytes of its MAC (`setup/src/10-settings.sh:50-77`, `router_id`, `resolve_wifi_names`). After pairing, `rotate_box_wifi` (`50-coin-box.sh:177-197`) gives the box its own password **and the router's own name** in one request (`/save` with `wifi_pass` and `wifi_ssid`; the box stores both, `esp32_firmware/src/WebServerConfig.cpp:233-235`), switches the router's network to them, and waits up to 240 s for the box to answer.
4. **Older routers keep the shared names:** a router set up before 1.5.0 (it has a stored `KIOSK_PASS`) keeps `PisoKiosk` and `PisoCoinBox` (`resolve_wifi_names`, line 70), on purpose, so its phones and box keep working. Its box network is therefore always called `PisoCoinBox`, even after its password was changed. A newer router whose password change failed also stays on `PisoCoinBox` (`rotate_box_wifi` logs "it keeps the built-in one for now (still locked to its MAC)").
5. **The clash:** while router 2 pairs, a router 1 that broadcasts `PisoCoinBox` answers box 2 too. If router 1 answers first, its MAC lock (or its own password) refuses box 2, the box retries, and may hit router 1 again: pairing is slow or fails. Once router 2 has rotated, box 2 uses `PisoCoinBox-<id2>` and the clash is over; a router 1 on its own name never clashes.
6. **`piso-setup rotate-box-wifi`** exists (`98-setup-flow.sh:194`): it sets `BOX_WIFI_ROTATED 0` and runs `rotate_box_wifi`. When the box refuses the request, `rotate_box_wifi` returns without changing the router, but `BOX_WIFI_ROTATED` stays 0, so `box_wifi_key` then names the built-in password although the router and box still use their own one (a latent fault; criterion 3 covers it).
7. **Where the owner sees the router's state:** `piso-setup status` (`80-commands.sh:4`), the setup summary (`60-summary.sh`), and the setup program's read-only Check-up (`setup/pisophone_setup.py`, `checkup`, line 3972).
8. **Payments while the box is off its network:** the box records every coin in its own flash queue before anything else and the phone or router credits each payment once when the link is back (`docs/ARCHITECTURE.md`); during a network switch, Wi-Fi customers cannot pay by coin for up to the 240 s wait.
9. The computer side: every router's LAN is 10.0.0.1 and a factory router is 192.168.1.1, which many modems also use. A computer connected to router 1 or to the modem's Wi-Fi while cabled to router 2 can reach the wrong device (not changed by this feature; see "Questions for later").

## Decisions (proposed; the owner approves them with the spec)
1. **A command for older routers:** `piso-setup box-wifi-name` gives the box network the router's own name `PisoCoinBox-<id>` (and a new own password), by telling the box first and then switching the router, the way `rotate_box_wifi` already does. The rental phones' network keeps its name (phones are not touched).
2. **Never by itself:** an update does not run it; the owner runs it (it takes the box off its network for up to four minutes).
3. **Tell the owner:** `piso-setup status`, the setup summary and the setup program's Check-up say when the box network is still called `PisoCoinBox`, with the one command that fixes it.
4. **Pairing says what to do:** the pairing tips and its failure message mention that another PisoHub router nearby may answer the setup name, and to switch that router off for a few minutes (or run the command on it).
5. **If anything fails, the box keeps working:** before the switch, nothing changes; after the switch, the router keeps the new name and tells the owner to power-cycle the box (same as `rotate_box_wifi` today).

## Names (use exactly these)
| What | Name |
|---|---|
| Command | `piso-setup box-wifi-name` (a new entry in the command list of `98-setup-flow.sh`) |
| Function | `own_box_wifi_name` in `setup/src/50-coin-box.sh` |
| New name | `PisoCoinBox-<id>` from `router_id`, stored as `BOX_SSID_NAME` in `/etc/piso-setup.conf` |
| Status line | "Coin box network: PisoCoinBox (the shared setup name: a second router being set up nearby can take this box's place). Fix: piso-setup box-wifi-name" |
| Pairing tip | "another PisoHub router nearby may answer the setup name PisoCoinBox: switch it off for a few minutes, or run piso-setup box-wifi-name on it" |

## Invariants touched
- **Money and time:** no change to payments, the queue, the ledger or credit. During the switch the box is off its network for up to 240 s; coins are kept in the box's queue and credited once afterwards (fact 8). The command says so and asks before it starts.
- **Only owner-signed software:** not touched (no change to updates or signing; the box is told its new name over the existing admin request).

## Constraints
- Firmware is not changed: the box already accepts `wifi_ssid` and `wifi_pass` in one `/save` (fact 3).
- The rental phones' network, its name and password are not changed.
- A router set up on 1.5.0 or later with its own name is not changed by anything here (the command says "already has its own name" and stops).
- Shell and the Python standard library only. `setup/RELEASE` raised once. Generated files regenerated by `tools/build_piso_setup.py`.

## Success criteria (each becomes a line in `validation.md`; test file in brackets)
1. **Baseline:** the router and setup tests of `CLAUDE.md` section 3 pass on the branch before any change; counts recorded.
2. **The command** [`router/tests/test_setup.py`, stub `box_curl`, `uci`, `wifi`]. On a router whose box network is `PisoCoinBox` (an old router with stored shared names, and a newer router with `BOX_WIFI_ROTATED` 0): it says the box will be off its network for up to four minutes and coins are kept, asks (a terminal) or accepts `--yes`; sends the box `wifi_ssid=PisoCoinBox-<id>` and a new `wifi_pass` in one request; then sets `wireless.box_ap.ssid` and `.key`, `BOX_SSID_NAME`, `BOX_WIFI_ROTATED 1`; keeps the MAC lock; waits for the box (`box_up`). On a router that already has its own name: "already has its own name", nothing changed. Without a paired box (`BOX_MAC` empty) or with the box not answering before it starts: it stops with the reason and changes nothing.
3. **Failure before the switch changes nothing** [`test_setup.py`]. When the box refuses the request (HTTP not 200/302/303): `wireless.box_ap.*`, `BOX_SSID_NAME`, `BOX_WIFI_ROTATED` and `BOX_WIFI_PASS_NEW` are exactly as before (this also fixes fact 6 for `piso-setup rotate-box-wifi`). When the box does not come back after the switch: the router keeps the new name and key, and the message tells the owner to power-cycle the box and run `piso-setup status`.
4. **Telling the owner** [`test_setup.py`, `test_pisophone_setup.py`]. With the box network called `PisoCoinBox`: `piso-setup status` and the summary show the status line of "Names"; the setup program's Check-up shows it with the command. With its own name: no such line.
5. **Pairing tips** [`test_setup.py`]. The pairing tips (60 s and 150 s) and the "did not join" failure message include the pairing tip of "Names". The pairing itself is unchanged (it must keep using `PisoCoinBox`, the name new boxes know).
6. **Phones untouched** [`test_setup.py`]. The command changes no `wireless.kiosk_*` setting, no `KIOSK_SSID_NAME` and no `KIOSK_PASS`.
7. **Hardware** [`docs/REAL_WORLD_TESTING.md`, a new check, "not run"]. (a) An older router (or one forced to `PisoCoinBox`): run `piso-setup box-wifi-name`; the box comes back on `PisoCoinBox-<id>` within four minutes and takes a coin. (b) With router 1 on its own name and powered on next to it, a factory-reset box pairs with router 2 at the first try. (c) With router 1 forced to `PisoCoinBox`, the pairing tip appears and switching router 1 off lets the pairing finish.
8. **Gates:** `bash scripts/check_security_rules.sh`; the router and setup Python tests; `python3 tools/build_piso_setup.py --check`; ShellCheck on the changed files.

## Out of scope
- Changing the firmware's built-in setup name (it would break pairing for every box already sold).
- Renaming the rental phones' network on older routers (phones would need to be set up again).
- Detecting another router's hidden network by scanning (a hidden network's name is not visible in a scan).
- The computer-side address clash of fact 9.

## Owner decisions (answer before code)
1. **Where the fix lives:** proposed: the command `piso-setup box-wifi-name`, plus the status, summary and Check-up line that names it. Alternative: also a button in the router console's Settings (more owner-friendly, more work).
2. **Automatic on update:** proposed: no (it takes the box off its network for minutes, so the owner chooses when).
3. **Release order:** proposed: 012 first as router 1.5.18 (small, fixes a real field problem), then 011.

## Questions for later
- The computer-side clash (fact 9): should the setup program check that the router it reached is the one being set up (for example its MAC on the factory address) and warn when the computer is also on another network?
