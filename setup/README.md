# Automatic router setup

One file, `piso-setup.sh`, turns a factory-reset OpenWrt router into the whole PisoWiFi system.

## Before you start
1. **Flash the ESP32 coin box** with the current firmware (or factory reset a used one) and power it on. It joins the hidden `PisoCoinBox` Wi-Fi by itself, which the router creates.
2. **Modem into the router's WAN port** (internet is needed once, to download packages).
3. A PC on one of the router's **LAN ports**.
4. The modem's own network must not be `10.0.0.x` or `192.168.30.x` (the script stops and tells you if it is).

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
Answer `y` when asked. It runs in front of you for about 3 to 8 minutes and **keeps your SSH session open the whole time**: it installs packages, creates the networks, waits for the ESP32 to join, sets its password and key, starts everything and ends with a health check. When it prints `SETUP COMPLETE`, read the summary:
```
cat /root/piso-setup-summary.txt
```
It has every password generated for you (router, PisoKiosk Wi-Fi, coin box admin). Save them somewhere safe.

## What you get
| network | for | bands | notes |
|---|---|---|---|
| **PisoKiosk** | the rental phones | 2.4 + 5 GHz | WPA2, name is fixed, password generated. This is the router's LAN (`10.0.0.0/24`, the address you set in step 1). |
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

## If something fails
* **"the coin box did not join"**: the box must be powered, have the current firmware, and be a fresh or factory-reset unit (an old unit remembers its old Wi-Fi). Then `piso-setup pair`.
* **"the box refused the admin login"**: the box already has its own password. Factory reset it, or run `BOX_ADMIN_PASSWORD='<its password>' piso-setup pair`.
* **`: not found` errors and a syntax error right at the start**: the file has Windows line endings. Run `sed -i 's/\r$//' piso-setup.sh` and try again.
* Everything is logged in `/root/piso-setup.log`.

## For developers
`setup/piso-setup.sh` is generated from `setup/piso-setup.sh.in` plus the portal files: after changing any of them run
`python3 tools/build_piso_setup.py` (CI fails if the committed file is out of date). Tests: `python3 opennds/tests/test_setup.py`.
`./piso-setup.sh --dry-run` prints the router settings without applying anything.
