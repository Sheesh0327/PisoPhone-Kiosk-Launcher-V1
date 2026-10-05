# Automatic router setup

One file, `piso-setup.sh`, turns a factory-reset OpenWrt router into the whole PisoWiFi system.

## Before you start
1. **Flash the ESP32 coin box** with firmware **3.2.1 or later** (see "Flashing the coin box" below) and power it on. It joins the hidden `PisoCoinBox` Wi-Fi by itself, which the router creates. A used box must be factory reset first (it remembers its old Wi-Fi).
2. **Modem into the router's WAN port** (internet is needed once, to download packages).
3. A PC on one of the router's **LAN ports**.
4. The modem's own network must not be `10.0.0.x` or `192.168.30.x` (the script stops and tells you if it is).

### Flashing the coin box (once per box, by USB)
```
cd esp32_firmware
sh host_tests/run.sh                  # optional: the firmware's own tests
pio run -e esp32-c3-dev -t upload     # ESP32-C3 boards; use esp32dev-dev for a classic ESP32 (environments: esp32_firmware/envs/)
pio device monitor                    # optional: watch it start
```
CI compiles the firmware on every push (job "Firmware format and host tests" and the build job). The box starts with the published defaults (admin password `Coinslot@Setup`, setup Wi-Fi `PisoCoinBox`); the router setup changes the password and key by itself, and the box refuses coins until its password has been changed.

## Step 1: set the router's LAN address to 10.0.0.1 (by hand, once)
The script does not change the router's address, so your SSH connection is never cut while it runs. Do this first, on a factory-reset router:
```
ssh root@192.168.1.1
uci set network.lan.ipaddr='10.0.0.1'
uci commit network
/etc/init.d/network restart
```
The SSH session ends (that is expected). Unplug and replug the PC's LAN cable so it gets a `10.0.0.x` address, then continue at `10.0.0.1`.
(In LuCI instead: Network > Interfaces > LAN > Edit > IPv4 address `10.0.0.1`, then Save & Apply.)

## Step 2: run the setup
```
scp -O setup/piso-setup.sh root@10.0.0.1:/root/
ssh root@10.0.0.1
sed -i 's/\r$//' piso-setup.sh     # removes Windows line endings if the file touched Windows (otherwise: ": not found" errors)
chmod +x piso-setup.sh
./piso-setup.sh
```
Answer `y` when asked. It then asks for the **two Wi-Fi names** (the public customer network, default `PisoWiFi`, and the rental-phone network, default `PisoKiosk`; Enter keeps the default, 32 characters at most, no quotes) and then to choose **three passwords** (each typed twice, not shown; press Enter to have one generated): the router password (SSH and LuCI), the **PisoKiosk Wi-Fi** password (typed into each phone's setup page) and the **coin box admin** password (the box's web page, also the phones' admin PIN). Use 8 or more characters without spaces or quotes. The coin box's *super-admin* password is not asked: the firmware keeps it under remote management and it cannot be set locally. For unattended runs set `ROOT_PASSWORD`, `KIOSK_PASSWORD` and `BOX_NEW_ADMIN_PASSWORD` in the environment. It runs in front of you for about 3 to 8 minutes and **keeps your SSH session open the whole time**: it installs packages, creates the networks, waits for the ESP32 to join, sets its password and key, starts everything and ends with a health check. When it prints `SETUP COMPLETE`, read the summary:
```
cat /root/piso-setup-summary.txt
```
It has every password generated for you (router, PisoKiosk Wi-Fi, coin box admin). Save them somewhere safe.

## Step 3: check that it works (do this before any customer or partner uses it)
1. `piso-setup status` must end with `All checks passed.`
2. `piso-setup test-coin`: it arms the slot; insert one coin. It must say `the box counted 1 peso(s)`.
3. **Wi-Fi customer:** join the customer Wi-Fi (`PisoWiFi`) with a phone, open the login page, pick a plan, tap Insert Coin, insert one coin. The page shows each coin at once with a countdown, and a **Done** button. You are *not* online yet (so the phone does not close the login page while you add coins). Insert more coins if you like, then tap **Done** (or wait 15 s after the last coin): the page then says you are online with your time and code. Browse something.
4. **Second device:** while a window is open, try Insert Coin on another phone. It must be refused with a "Try again" page.
5. **Rental phone:** join `PisoKiosk` with a kiosk phone (the app's own flow) and pay once.
6. `piso-setup reconcile` must say `RECONCILE OK` (needs firmware 3.2.1).
7. `piso-setup diag > diag.txt` and keep the output: it contains the coin timings and no passwords. Send it with any bug report.

## Step 4 (optional, recommended): Telegram alerts and remote control
1. In Telegram, open **@BotFather**, send `/newbot`, pick a name, copy the token it gives you.
2. On the router: `piso-setup telegram`. Paste the token, give the site a name (it prefixes every message), then open your new bot in Telegram and send it any message. The router shows the chat it found; answer `y` if it is you.
3. You get a "connected" message. From then on you receive: router restarted, box offline for 5 minutes (and back), revenue mismatch or edited ledger, a device abusing the coin slot, and a daily report at 21:00.
4. Commands (only from your chat): `/status`, `/report [days]`, `/reconcile`, `/diag`, `/restart` (coin manager), `/reboot` (then `/reboot confirm` within 2 minutes), `/help`.
5. Optional dead-man switch: create a check at healthchecks.io, put its ping URL in `/etc/piso-monitor.conf` as `HEALTHCHECK_URL='...'`, then `/etc/init.d/piso_monitor restart`. You are alerted when the router stops pinging (power cut, internet down).
The router needs internet for this: while the site is offline nothing can be sent, and alerts raised in that time are not delivered later (the dead-man switch covers that case).

## Step 5: provision each rental phone (it joins PisoKiosk by itself)
A phone that is not on the **PisoKiosk** Wi-Fi cannot find its coin box, so it can never pair or learn its admin PIN. The provisioning page (the "Install & Provision" link on the box's page) therefore has a **Kiosk Wi-Fi** section: the name (`PisoKiosk`) and the PisoKiosk password from `piso-setup summary`. It is sent to the phone together with the box's secret; the app (a device owner) adds the network itself, joins it, and rejoins whenever the phone is on another network (checked every minute). The page remembers the password on that computer, so the next phone needs no typing. Without a password the page warns you before it continues.
To fix a phone that is already provisioned (it needs the admin PIN or box secret because it is paired already):
```
adb shell am broadcast -a com.pisophone.kiosk.CONFIGURE_ESP32 -n com.pisophone.kiosk/.receiver.KioskAdminActionReceiver --es wifi_ssid PisoKiosk --es wifi_pass '<password>' --es pin '<admin PIN>'
```

## What you get
| network | for | bands | notes |
|---|---|---|---|
| **PisoKiosk** (or the name you chose) | the rental phones | 2.4 + 5 GHz | WPA2, name chosen at setup and then fixed (the phones are provisioned with it; type the same name on the phone setup page), password chosen or generated. This is the router's LAN (`10.0.0.0/24`, the address you set in step 1). |
| **PisoCoinBox** (hidden) | the ESP32 only | 2.4 GHz | After pairing, only the box's MAC address may join. The box is always `10.0.0.10`. |
| **PisoWiFi** | customers | 2.4 + 5 GHz | Open, behind the openNDS login and coin payment. Rename: `piso-setup wifi-name "My Shop"`. Separate network `192.168.30.0/24`. |

The script also installs the packages, the portal and the coin-slot manager, sets the box's admin password and gateway key through its API,
sets a root password, and ends with a health check. The router's wired LAN ports stay on the same network as PisoKiosk (administration).

## Day to day
| command | what it does |
|---|---|
| `piso-setup status` | checks every part |
| `piso-setup wifi-name "Name"` | renames the customer Wi-Fi (also the login page title) |
| `piso-setup pair` | replaces the coin box: re-opens pairing for a few minutes, then locks to the new box |
| `piso-setup summary` | shows the saved passwords again |

Running `piso-setup.sh` again is safe: it keeps the passwords and names it already made.

## Locked out of the router?
The setup asks you to choose the router password (or shows a generated one and asks you to type it back), and prints every password at the end. If you still lose it, OpenWrt's failsafe mode keeps all settings:
1. Give the PC the fixed address `192.168.1.2` (netmask `255.255.255.0`), plugged into a LAN port.
2. Power-cycle the router; when the power LED starts blinking fast, press the reset button once (WPS on some models). The LED blinks very fast.
3. `telnet 192.168.1.1`, then `mount_root`, then `cat /etc/piso-setup.conf` (all the generated passwords are in it), then `passwd`, then `reboot -f`.

`piso-setup set-password` changes the router password later; `piso-setup summary` shows all saved passwords.

## Check the coin path
| command | what it does |
|---|---|
| `piso-setup test-coin` | arms the coin slot and waits for a coin, with no customer portal involved. It says whether the box counts the coin. |
| `piso-setup reconcile` | compares the box's own lifetime coin count with the router's revenue ledger and checks that nobody edited the ledger (needs firmware 3.2.1). |
| `piso-setup telegram` | connects a Telegram bot: alerts (router restarted, box offline, revenue mismatch, a device abusing the coin slot, daily report) and remote commands `/status /report /reconcile /diag /restart /reboot`, answered only to your chat. Create the bot with @BotFather first. Optional: put a healthchecks.io URL in `/etc/piso-monitor.conf` (`HEALTHCHECK_URL`) so you are told when the whole router goes silent. |
| `piso-setup diag` | prints one block (status, manager, box, firewall, recent logs; no passwords) to paste when asking for help. |

## If something fails
* **"the coin box did not join"**: the box must be powered, have the current firmware, and be a fresh or factory-reset unit (an old unit remembers its old Wi-Fi). Then `piso-setup pair`.
* **"the box refused the admin login"**: the box already has its own password. Factory reset it, or run `BOX_ADMIN_PASSWORD='<its password>' piso-setup pair`.
* **`: not found` errors and a syntax error right at the start**: the file has Windows line endings. Run `sed -i 's/\r$//' piso-setup.sh` and try again.
* Everything is logged in `/root/piso-setup.log`.

## For developers
`setup/piso-setup.sh` is generated from `setup/piso-setup.sh.in` plus the portal files: after changing any of them run
`python3 tools/build_piso_setup.py` (CI fails if the committed file is out of date). Tests: `python3 opennds/tests/test_setup.py`.
`./piso-setup.sh --dry-run` prints the router settings without applying anything.
