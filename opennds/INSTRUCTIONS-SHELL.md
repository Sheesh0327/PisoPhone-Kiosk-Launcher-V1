# Router setup: shell edition (supported)

> **Note:** this manual guide describes the older layout (the box opening its own `PisoPhone-Setup-xxxx` Wi-Fi, networks `192.168.20.x`/`192.168.30.x`). Current firmware has no setup access point: it joins a hidden `PisoCoinBox` network. Use the automatic setup in `setup/README.md` unless you have a reason to do it by hand.

This guide sets up a **factory-reset router** from nothing to a working PisoWiFi coin-operated portal with the
**shell edition** of the coin-slot manager (`coinslot-listener.sh`). It is the supported edition. Everything is copy/paste;
replace the values in `<angle brackets>`. Total time: about an hour.

## What you need
- A router running OpenWrt or ImmortalWrt 21.02 or newer (the shell edition has been run on a MediaTek MT7621 router with
  ImmortalWrt 23.05), with a working internet connection through a modem on its WAN port.
- A PC with `ssh` and `scp` (Windows 10/11 PowerShell, macOS and Linux have them) and this repository's `opennds/` folder.
- The PisoPhone coin box (ESP32) flashed with the current firmware and powered. **Its starting password is `Coinslot@Setup`
  and it must be changed before it takes coins** (Part 2).
- A password you choose for the **kiosk** Wi-Fi (the box and the rental phones use it) and the name customers will see
  (default `PisoWiFi`).

## Part 1. Factory-reset the router and log in

1. **Reset it.** Hold the reset button for about 10 seconds until the lights change (or use the router's own
   "Reset to defaults" in its web page). Wait until it has booted again (about 2 minutes).
2. **Connect your PC to one of the router's LAN ports** with a cable. Leave the WAN port empty for now. The PC gets an
   address automatically. The router is at `192.168.1.1`.
3. **Set the root password.** Open `http://192.168.1.1` in a browser (LuCI). It shows "No password set": go to
   System, Administration and choose a long root password; write it down. (Until a password is set, SSH is off;
   setting it turns SSH on.)
4. **Log in over SSH** from the PC (Windows PowerShell, macOS or Linux terminal):
   ```
   ssh root@192.168.1.1
   ```
5. **Plug the modem into the router's WAN port** and check the router has internet:
   ```
   ping -c 3 openwrt.org
   ```
   It must answer. If it does not, fix the WAN connection first (the modem may need to be restarted).
6. **If your modem also uses `192.168.1.x`**, change the router's own address now so they do not clash (you will be
   disconnected; reconnect at the new address):
   ```
   uci set network.lan.ipaddr='192.168.10.1'
   uci commit network
   /etc/init.d/network restart
   ```
   The rest of this guide writes `192.168.1.1`; use your address instead if you changed it.

## Part 2. Set up the coin box (ESP32) first

The box ships with one known password, **`Coinslot@Setup`**, so it can be set up without a serial monitor. Because that
password is public, **the box accepts no coins until you change it.**

1. Power the box. With no Wi-Fi configured it starts its own setup Wi-Fi called `PisoPhone-Setup-xxxx`. (It does the same
   after a factory reset from its dashboard, which also brings back the default password.)
2. Join that Wi-Fi from the PC. Its password is **`Coinslot@Setup`**.
3. Open `http://192.168.4.1` and log in as **`admin`** with password **`Coinslot@Setup`**.
4. **Change the admin password** (Settings). Use at least 8 characters; the default is refused. Write it down:
   you need it in Part 6. A red banner stays on the dashboard until this is done.
5. Write down the box's **MAC address** (shown on its dashboard, like `AA:BB:CC:DD:EE:FF`). Part 4 uses it to give the
   box a fixed address.
6. Leave the box's Wi-Fi settings alone for now: Part 5 connects it to the kiosk network you are about to create.

*A box that was set up before keeps the passwords it already has* (they live in its flash and survive re-flashing).
For a clean start, erase its flash completely before flashing (`pio run -e <environment> -t erase`), or use the dashboard's
factory reset, which returns it to `Coinslot@Setup`.

## Part 3. Install the packages on the router
```
opkg update
opkg install opennds socat openssl-util curl coreutils-sleep
opkg list-installed | grep -i microhttpd || opkg install libmicrohttpd-no-ssl
```
- `opennds` is the login page system; `socat` and `openssl-util` and `curl` are used by the coin-slot scripts;
  `coreutils-sleep` lets the router check for coins 10 times a second (with the router's own `sleep` it is once a second).
- Test: `sleep 0.3 && echo ok` must print `ok`.

## Part 4. Create the two Wi-Fi networks (Layout B)

Customers get their own Wi-Fi (**guest**, gated by openNDS). The box and the rental phones share a separate,
password-protected network (**kiosk**) that openNDS does not touch. Neither side can reach the other; both reach the
internet. A customer can then never reach the box or a phone.

1. **Turn the Wi-Fi radio on** (a factory-reset router has it off) and set your country:
   ```
   uci show wireless | grep -E "=wifi-device|band|channel"
   ```
   This lists `radio0` (and maybe `radio1`) with their bands. Use the 2.4 GHz one for the networks below (better range;
   most phones and the box use it). If it is `radio1`, put `RADIO=radio1` in front of the command in step 3.
   ```
   uci set wireless.radio0.disabled='0'
   uci set wireless.radio0.country='PH'
   uci -q set wireless.default_radio0.disabled='1'     # the router's own default "OpenWrt" Wi-Fi: off
   uci -q set wireless.default_radio1.disabled='1'
   uci commit wireless
   ```
   (`PH` is the Philippines; use your own country code.)
2. **Copy the network script** from the PC (run on the PC, in the folder that contains `opennds/`):
   ```
   scp opennds/layout_b.sh root@192.168.1.1:/root/
   ```
   (If `scp` says "sftp-server: not found", add `-O`: `scp -O opennds/layout_b.sh root@192.168.1.1:/root/`.)
3. **Generate the settings and read them.** Nothing is changed yet. Choose a Wi-Fi password for the kiosk network
   (8 or more characters; write it down: the box and every rental phone use it) and use the box MAC from Part 2:
   ```
   KIOSK_KEY='<kiosk wifi password>' BOX_MAC=AA:BB:CC:DD:EE:FF sh /root/layout_b.sh > /tmp/layout_b.uci
   cat /tmp/layout_b.uci
   ```
   Optional settings go in front of the command: `KIOSK_SSID` (default `PisoKiosk`), `GUEST_SSID` (default `PisoWiFi`,
   the name customers see), `BOX_IP` (default `192.168.20.10`), `RADIO`, `KIOSK_PORTS="lan3 lan4"` for wired ports
   that belong to the kiosk network, and `GUEST_PORTS="lan2"` if customers connect through an access point plugged
   into a LAN port (see the note below).
4. **Apply it.** Your `lan` network is not touched, so you can still reach the router at `192.168.1.1`:
   ```
   uci batch < /tmp/layout_b.uci && uci commit
   /etc/init.d/network reload && /etc/init.d/firewall reload && wifi reload
   ```
5. **Check:** two Wi-Fi names should now exist: `PisoKiosk` (password protected) and `PisoWiFi` (open). The kiosk network
   is `192.168.20.x`, the guest network `192.168.30.x`.

*An external access point for customers:* set it to **access point / bridge mode** (its own DHCP server off), plug it into
a LAN port of this router, and add that port to the guest network by using `GUEST_PORTS="lan2"` (the port you used) in step 3.
Give it the open `PisoWiFi` name. Do the same with `KIOSK_PORTS` for a wired kiosk device.

## Part 5. Connect the box to the kiosk network

1. Join the PC to the box's setup Wi-Fi again (`PisoPhone-Setup-xxxx`; its password stays `Coinslot@Setup`), open
   `http://192.168.4.1` and log in as `admin` with the new admin password from Part 2.
2. In Settings, enter the kiosk Wi-Fi name (`PisoKiosk`) and its password, and save. The box restarts, joins the
   kiosk network and the router gives it the fixed address `192.168.20.10`.
3. On the router (SSH, as in Part 1) check it answers:
   ```
   ping -c 3 192.168.20.10
   curl http://192.168.20.10/api/gateway/challenge
   ```
   You get an answer from the box (until the key is set in the next part it may say `GATEWAY_DISABLED`; that is fine).

## Part 6. Make a key and put it on the box
The router and the box sign every request with a shared key. Make one and give it to the box:
```
KEY=$(openssl rand -hex 32)
echo "$KEY"
curl -u admin:<box-admin-password> -X POST "http://192.168.20.10/api/gateway/config" --data-urlencode "key=$KEY"
```
The answer must contain `"configured":true`. A 401 means the admin password is wrong (five wrong tries lock the box for
a minute). **Keep this terminal open**: `$KEY` is used in Part 8. If you lose it, repeat this part to make a new one.

## Part 7. Install the coin-slot files (shell edition)
1. **Copy the four files** from the PC (in the folder that contains `opennds/`):
   ```
   scp opennds/theme_coinslot.sh opennds/coinslot_status.sh opennds/coinslot-listener.sh opennds/coinslot.init root@192.168.1.1:/root/
   ```
   (Use `scp -O` if `scp` complains about `sftp-server`.)
2. **On the router, fix line endings** (needed if the files ever touched Windows; skipping it gives "Permission denied" or
   "not found" even though the files exist), put the files in place and make them executable:
   ```
   cd /root
   sed -i 's/\r$//' theme_coinslot.sh coinslot_status.sh coinslot-listener.sh coinslot.init
   cp theme_coinslot.sh /usr/lib/opennds/theme_coinslot.sh
   cp coinslot_status.sh /usr/lib/opennds/coinslot_status.sh
   cp coinslot-listener.sh /usr/bin/coinslot-listener.sh
   cp coinslot.init /etc/init.d/coinslot
   chmod +x /usr/lib/opennds/theme_coinslot.sh /usr/lib/opennds/coinslot_status.sh /usr/bin/coinslot-listener.sh /etc/init.d/coinslot
   ```
   | file | goes to |
   |---|---|
   | `theme_coinslot.sh` | `/usr/lib/opennds/theme_coinslot.sh` (the portal pages) |
   | `coinslot_status.sh` | `/usr/lib/opennds/coinslot_status.sh` (the page a connected customer sees) |
   | `coinslot-listener.sh` | `/usr/bin/coinslot-listener.sh` (the coin-slot manager) |
   | `coinslot.init` | `/etc/init.d/coinslot` (starts three supervised processes: the manager, the live-update stream and the fair-use watcher) |

*Package alternative:* `python3 opennds/package/build_ipk.py --version 1.0.0` builds `dist/opennds-coinslot_1.0.0_all.ipk`
(CI attaches the same file to each run). Copy it to the router and `opkg install /tmp/opennds-coinslot_1.0.0_all.ipk`: it
installs these four files, enables the service and keeps the settings in UCI (`uci set coinslot.main.gw_box='192.168.20.10'`,
`uci set coinslot.main.gw_key="$KEY"`, `uci set coinslot.main.gw_box_mac='AA:BB:CC:DD:EE:FF'`,
`uci set coinslot.main.discover_iface='br-kiosk'`, `uci commit coinslot`) instead of `/etc/coinslot.conf`. Then skip Part 8
and continue with Part 9.

## Part 8. Write the settings file
Run this in the same session as Part 6 so `$KEY` is filled in. Paste the whole block at once:
```
cat > /etc/coinslot.conf << EOT
GW_BOX=192.168.20.10
GW_KEY=$KEY
GW_BOX_MAC=AA:BB:CC:DD:EE:FF
DISCOVER_IFACE=br-kiosk
EOT
chmod 600 /etc/coinslot.conf
cat /etc/coinslot.conf
```
Use the box's real MAC. Check the printed file shows the box address and a 64-character key (not the text `$KEY`).
`GW_BOX_MAC` makes the router accept only that box if it ever has to look for it again.

Rates, speed caps, the coin window and the fair-use limit have the defaults built in (HyperSpeed 5=30 min, 10=1 hr,
20=2 hrs; Endurance 1=15 min, 5=3 hrs, 10=8 hrs, 20=24 hrs at 5 Mbit/s down and 2 Mbit/s up; HyperSpeed slowed after
5 GB). To change any of them, add the matching line from `opennds/coinslot.conf` (it lists every setting) to
`/etc/coinslot.conf` and restart the service (Part 11).

## Part 9. Tell openNDS to use the theme
```
uci set opennds.@opennds[0].login_option_enabled='3'
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/coinslot_status.sh'   # the page a connected customer sees at http://<router>/

# Bursting (openNDS' own feature): a customer is not speed-limited until the speed stays above the cap for about 30 s.
uci set opennds.@opennds[0].download_unrestricted_bursting='1'
uci set opennds.@opennds[0].upload_unrestricted_bursting='1'
uci set opennds.@opennds[0].checkinterval='15'
uci set opennds.@opennds[0].ratecheckwindow='2'
uci commit opennds
```
Endurance customers (5 Mbit/s down, 2 Mbit/s up) then do not feel the caps during short bursts such as loading a page;
only sustained heavy use is limited. HyperSpeed has no cap, so bursting only softens the fair-use slowdown.
(Part 4 already told openNDS to gate only the guest network and opened the live-update port 8100 for guests.)

## Part 10. Start everything
```
/etc/init.d/coinslot enable
/etc/init.d/coinslot start
/etc/init.d/opennds enable
/etc/init.d/opennds stop
/etc/init.d/opennds start
```
(`restart` can print "Command failed: Not found" when openNDS was not running; `stop` then `start` avoids that.)

## Part 11. Check each piece
```
curl http://127.0.0.1:8099/info                       # expect {"first":30,"idle":15,"max":115,...}
/usr/bin/coinslot-listener.sh minutes endurance 17    # expect 690 (11 hrs 30 min)
curl http://192.168.20.10/api/gateway/challenge       # expect {"nonce":"..."}
/usr/bin/coinslot-listener.sh box                     # expect: box 192.168.20.10 answers
/etc/init.d/coinslot status                           # expect: running
uci show opennds | grep -E "login_option|themespec|statuspath|bursting|gatewayinterface"
ndsctl status                                         # openNDS is running
```
**The live-update port:** from a phone on the guest Wi-Fi that has NOT paid, open `http://192.168.30.1:8100/stream` in the
browser. `403 Forbidden` means the port is reachable (the stream only talks to a started session): good. A timeout means
the guest rule or openNDS' `users_to_router` did not open it; the portal still works, with ordinary refreshes. Never open
8100 on the WAN side.

**First coin test:** join `PisoWiFi` with a phone, open any web page, choose a plan, tap Insert Coin, drop a coin, tap
Connect. Watch the router: `logread -f -e opennds -e coinslot`.

## Day to day
```
/usr/bin/coinslot-listener.sh report 7       # revenue for the last 7 days, per day and plan
logread -e coinslot                          # what the manager is doing
ls /etc/coinslot.d/vouchers                  # active voucher codes (paid sessions)
```
Customers see a voucher code after paying. It restores their remaining time on any device (a phone that randomises its
MAC address, a second device, or after a router restart) and it moves the time: the previous device is disconnected.

**Changing settings later:** edit `/etc/coinslot.conf`, then `/etc/init.d/coinslot restart`.
**Rotating the key:** repeat Part 6 and Part 8, then restart the service.

## If something fails
| symptom | fix |
|---|---|
| `Permission denied` or `not found` on a script | line endings (`sed -i 's/\r$//' <file>`), then the `chmod +x` line in Part 7 |
| `/info` gives nothing | `/etc/init.d/coinslot start`, then `logread -e coinslot`; check `socat` is installed |
| portal shows "Coin payment is offline" | the manager is not running or cannot reach the box: `curl http://127.0.0.1:8099/info`, then `curl http://192.168.20.10/api/gateway/challenge` |
| the box answers `SETUP_REQUIRED` / customers see "coin box setup is not finished" | the box's admin password is still the default: change it (Part 2) |
| challenge gives `GATEWAY_DISABLED` | the key was not set on the box: repeat Part 6 |
| `401` when setting the key | the box's admin password is wrong (five wrong tries lock it for a minute) |
| top-up or throttle does nothing | `ndsctl status` works? `logread -e opennds`; the manager re-grants time with `ndsctl deauth` then `ndsctl auth` |
| portal page does not appear | `ndsctl status`, `logread -e opennds`; confirm Part 9 with `uci show opennds` |
| customers can reach the box | they are on the wrong network: only `PisoWiFi` should be offered to customers |

## Appendix: the box is on the modem's network, not behind this router (Layout A)
Use this only if the box cannot join the kiosk network. The router reaches the box through its WAN port, so the box's
address comes from the modem and may change. Skip Part 4's kiosk network (you may still use the guest network and the
openNDS lines from `layout_b.sh`), skip Part 5, and in Part 8 use the box's real address and the modem-facing interface:
```
GW_BOX=<box address from the modem>
GW_BOX_MAC=AA:BB:CC:DD:EE:FF   # only that box is accepted
DISCOVER_IFACE=wan             # the interface facing the modem
```
Reserve the box's address in the modem if it lets you. If the box stops answering, the manager broadcasts the box's own
discovery probe (UDP 8888) and remembers the answer, at most once every 30 seconds. `box` in Part 11 shows which box
it uses. The discovery answer is not signed, so keep `GW_BOX_MAC` set; `GW_DISCOVER=0` turns discovery off.
Guests must still reach port 8100: add the rule from `layout_b.sh` by hand for your guest zone.
