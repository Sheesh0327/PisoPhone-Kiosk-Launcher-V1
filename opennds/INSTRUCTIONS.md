# Setup instructions (OpenWrt + openNDS + PisoPhone coin slot)

Everything below is copy/paste. Replace the values in `<angle brackets>`.
Steps 2 onwards run on the router over SSH (`ssh root@<router-ip>`).

Already running and want the faster MicroPython edition? Skip to **"Switching to the MicroPython edition"** at the end
of this file. Do the normal install first; the fast edition is an optional replacement for the service, not a new install.

## 0. Before you start
- The PisoPhone box runs the firmware that includes the gateway API. You know its IP (give it a DHCP
  reservation so it never changes) and its admin password.
- The router has internet access (for `opkg`).

## Quick install with the package (recommended)
Build or download `opennds-coinslot_<version>_all.ipk` (`python3 opennds/package/build_ipk.py --version 1.0.0`, or the
file attached to the CI run), then on the PC and the router:
```
scp dist/opennds-coinslot_1.0.0_all.ipk root@<router-ip>:/tmp/ # use [scp -O] if on legacy version of openwrt
ssh root@<router-ip>
opkg update && opkg install /tmp/opennds-coinslot_1.0.0_all.ipk     # also installs opennds, socat, openssl-util, curl
```
It copies the files, fixes line endings and permissions, enables the service and moves an old
`/etc/coinslot.conf` into UCI. Then do step 4 (make the key), and store the settings in UCI instead of step 6:
```
uci set coinslot.main.gw_box='<box-ip>'
uci set coinslot.main.gw_key="$KEY"
uci commit coinslot
```
Continue with step 7 (openNDS theme) and step 8 (start). Every setting is a lower-case UCI option
(`uci set coinslot.main.hyper_tiers='5:30 10:60 20:120'`, `gw_box_mac`, `discover_iface`, ...); the manual steps 1, 2, 3 and 5 below
are only needed without the package. `coinslot-listener.sh migrate` moves an old settings file into UCI by hand.

## 1. Copy the files to the router (manual install, without the package)
From the PC that has this folder (Windows PowerShell, macOS or Linux terminal), replace `<router-ip>`:

```
scp opennds/theme_coinslot.sh opennds/coinslot_status.sh opennds/coinslot-listener.sh opennds/coinslot.init root@<router-ip>:/root/
```

Then log in to the router: `ssh root@<router-ip>`.

## 2. Fix line endings (needed if the files ever touched Windows)
```
cd /root
sed -i 's/\r$//' theme_coinslot.sh coinslot_status.sh coinslot-listener.sh coinslot.init
```
Skipping this on Windows-copied files gives "Permission denied" or "not found" even though the files exist.

## 3. Install the packages
```
opkg update
opkg install opennds socat openssl-util curl
opkg list-installed | grep -i microhttpd || opkg install libmicrohttpd-no-ssl
```

## 4. Generate a key and put it on the box
```
KEY=$(openssl rand -hex 32)
echo "$KEY"
curl -u admin:<box-admin-password> -X POST "http://<box-ip>/api/gateway/config" --data-urlencode "key=$KEY"
```
The answer must contain `"configured":true`. A 401 means the admin password is wrong (five wrong tries lock the
box for a minute). Keep the terminal open: `$KEY` is used in step 6.

## 5. Put each file where it belongs and make it executable
| file | destination | mode |
|---|---|---|
| `theme_coinslot.sh` | `/usr/lib/opennds/theme_coinslot.sh` | executable |
| `coinslot_status.sh` | `/usr/lib/opennds/coinslot_status.sh` | executable |
| `coinslot-listener.sh` | `/usr/bin/coinslot-listener.sh` | executable |
| `coinslot.init` | `/etc/init.d/coinslot` | executable |

```
cp /root/theme_coinslot.sh /usr/lib/opennds/theme_coinslot.sh
cp /root/coinslot_status.sh /usr/lib/opennds/coinslot_status.sh
cp /root/coinslot-listener.sh /usr/bin/coinslot-listener.sh
cp /root/coinslot.init /etc/init.d/coinslot

chmod +x /usr/lib/opennds/theme_coinslot.sh /usr/lib/opennds/coinslot_status.sh
chmod +x /usr/bin/coinslot-listener.sh
chmod +x /etc/init.d/coinslot
```

## 6. Write the settings file (holds the key, so owner-only; the package uses UCI instead, see above)
Run this in the same session as step 4 so `$KEY` is filled in. Paste the whole block at once:

```
cat > /etc/coinslot.conf << EOT
GW_BOX=<box-ip>
GW_KEY=$KEY
EOT
chmod 600 /etc/coinslot.conf
cat /etc/coinslot.conf
```
Check the printed file shows your real box IP and a 64-character key (not the text `$KEY`). If you opened a new
session and lost `$KEY`, repeat step 4 to make a new one.

Rates, speed caps, the coin window and the fair-use limit all have the defaults you asked for built in
(HyperSpeed 5=30 min, 10=1 hr, 20=2 hrs; Endurance 1=15 min, 5=3 hrs, 10=8 hrs, 20=24 hrs at 5 Mbit/s down and
2 Mbit/s up; HyperSpeed slowed after 5 GB). To change any of them, add the matching line from `coinslot.conf`
(it lists every setting) to `/etc/coinslot.conf` and restart the service (step 8).

## 7. Tell openNDS to use the theme
```
uci set opennds.@opennds[0].login_option_enabled='3'
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/coinslot_status.sh'   # the page a connected customer sees at http://<router>/

# Bursting (openNDS' own feature): a client is not speed-limited until its speed stays above its cap for a whole
# check window (ratecheckwindow x checkinterval = 2 x 15 s = about 30 s); the cap is lifted again when it drops below.
uci set opennds.@opennds[0].download_unrestricted_bursting='1'
uci set opennds.@opennds[0].upload_unrestricted_bursting='1'
uci set opennds.@opennds[0].checkinterval='15'
uci set opennds.@opennds[0].ratecheckwindow='2'
uci commit opennds
```
Endurance customers (5 Mbit/s down, 2 Mbit/s up) therefore do not feel the caps during short bursts such as loading a page;
only sustained heavy use is limited. This applies to all clients; HyperSpeed has no cap, so it only softens the fair-use slowdown.

## 8. Start everything
```
/etc/init.d/coinslot enable
/etc/init.d/coinslot start
/etc/init.d/opennds enable
/etc/init.d/opennds stop
/etc/init.d/opennds start
```
(`restart` can print "Command failed: Not found" when openNDS was not running; `stop` then `start` avoids that.)

### Layout A: the box is on the modem's network, not behind this router
The router reaches the box through its WAN port, so the box's address comes from the modem and may change. Two
things keep this working:

1. Reserve the box's address in the modem if it lets you (DHCP reservation). Many ISP modems do not.
2. If the box stops answering at `GW_BOX`, the listener asks for it itself: it broadcasts the box's own discovery
   probe (UDP 8888) and remembers the answer in `/tmp/coinslot/box_addr`, at most once every 30 seconds. Add to
   `/etc/coinslot.conf`:
```
GW_BOX_MAC=AA:BB:CC:DD:EE:FF   # the box's MAC (shown on its dashboard); only that box is accepted
DISCOVER_IFACE=wan             # the interface facing the modem; leave out if it works without
```
Needs `socat` (already installed in step 3). Check with `/usr/bin/coinslot-listener.sh box`: it prints which
box it uses and whether it answers. No firewall change is needed: the router starts every connection to the box.

Limit: the discovery answer is not signed, so another device on the modem's network could point the router at
itself. Set `GW_BOX_MAC` and, where the modem allows it, reserve the address; set `GW_DISCOVER=0` to turn discovery off.

### Layout B: the box and the rental phones are behind this router
Use this when you control the network. Customers get their own Wi-Fi (`guest`, gated by openNDS); the box and
the phones share a separate password-protected Wi-Fi or wired port (`kiosk`) that openNDS does not touch.
Neither side can reach the other; both reach the internet. A customer can then never reach the box or a phone.

1. Generate the settings and read them (nothing is changed yet; needs OpenWrt 21.02 or newer):
```
scp opennds/layout_b.sh root@<router-ip>:/root/
KIOSK_KEY='<kiosk wifi password>' BOX_MAC=AA:BB:CC:DD:EE:FF sh /root/layout_b.sh > /tmp/layout_b.uci
cat /tmp/layout_b.uci
```
Optional settings: `RADIO` (default `radio0`), `KIOSK_SSID`, `GUEST_SSID`, `BOX_IP` (default `192.168.20.10`),
`KIOSK_PORTS="lan3 lan4"` for wired kiosk ports.
2. Apply, then reload (your `lan` network is not touched, so you can still reach the router there):
```
uci batch < /tmp/layout_b.uci && uci commit && /etc/init.d/network reload && /etc/init.d/firewall reload
/etc/init.d/opennds restart
```
3. Join the box and every rental phone to the **kiosk** Wi-Fi (or plug the box into a kiosk port), and set in
   `/etc/coinslot.conf`: `GW_BOX=192.168.20.10`, `GW_BOX_MAC=<box MAC>`, `DISCOVER_IFACE=br-kiosk`. Restart the service (step 8).
4. Give customers the **guest** Wi-Fi name. Turn off any old customer SSID on `lan`.
5. Check: from a phone on the guest Wi-Fi `ping 192.168.20.10` must fail; from the box's network the portal must still
   take coins; `/usr/bin/coinslot-listener.sh box` must say the box answers.

If you only have one Wi-Fi and cannot split it: the weaker fallback is to list the box and every rental phone as
`trustedmac` in openNDS so they skip the portal. Customers then share the network with the box and the phones, so
the box's and phones' own protections (signed requests, per-box secret) are all that stands between them.

### Instant updates (coins and "slot is free" pushed to the customer's page)
Two small things make the portal feel instant. The page works without them, only slower.
1. **Fast coin polling**: `opkg update && opkg install coreutils-sleep`. While one customer's window is open the
   router asks the box for coins every `COIN_POLL_SECONDS` (default 0.1). With BusyBox's own `sleep` it is once a second.
   Nothing is polled while nobody has the slot open. (Test: `sleep 0.3 && echo ok` must print `ok`.)
2. **Live stream**: the service also starts `coinslot-listener.sh stream` on port 8100 (`STREAM_PORT`). The customer's page
   listens there, so each coin appears as it arrives, and a customer who finds the slot busy is told the moment it
   is free (no refreshing). Only the device that started the session can listen to it; it can read, never change anything.
   Guests must be allowed to reach that one port. Layout B: `layout_b.sh` already prints the rule. Layout A / other setups:
```
uci add firewall rule
uci set firewall.@rule[-1].name='Guest-Coinslot-Stream'
uci set firewall.@rule[-1].src='<your guest zone, e.g. lan>'
uci set firewall.@rule[-1].proto='tcp'
uci set firewall.@rule[-1].dest_port='8100'
uci set firewall.@rule[-1].target='ACCEPT'
uci add_list opennds.@opennds[0].users_to_router='allow tcp port 8100'
uci commit && /etc/init.d/firewall reload && /etc/init.d/opennds restart && /etc/init.d/coinslot restart
```
   Check from a phone on the guest Wi-Fi that has NOT paid: open `http://<router-ip>:8100/stream` in the browser. A
   `403 Forbidden` answer means the port is reachable (the stream only talks to a started session). A timeout means the
   rule or openNDS' `users_to_router` did not open it; the portal then keeps working with ordinary refreshes.
   Do not open 8100 on the WAN side.

## 9. Check each piece
```
curl http://127.0.0.1:8099/info                       # expect {"first":30,"idle":15,"max":115,...}
/usr/bin/coinslot-listener.sh minutes endurance 17    # expect 690 (11 hrs 30 min)
curl http://<box-ip>/api/gateway/challenge            # expect {"nonce":"..."}
/usr/bin/coinslot-listener.sh box                     # expect: box <ip> answers
uci show opennds | grep -E "login_option|themespec|statuspath|bursting"   # expect the login/theme/status lines and both bursting lines
ndsctl status                                         # openNDS is running
```
Then join the Wi-Fi with a phone and watch: `logread -f -e opennds -e coinslot`

## Day to day
```
/usr/bin/coinslot-listener.sh report 7       # revenue for the last 7 days, per day and plan
logread -e coinslot                          # what the manager is doing
ls /etc/coinslot.d/vouchers                  # active voucher codes (paid sessions)
```
Customers see a voucher code after paying. It restores their remaining time on any device (a phone that
randomises its MAC address, a second device, or after a router restart) and it moves the time: the previous device
is disconnected.

## Changing settings later
Edit `/etc/coinslot.conf` and run `/etc/init.d/coinslot restart`. To rotate the key, repeat steps 4 and 6, then restart.

## If something fails
| symptom | fix |
|---|---|
| `Permission denied` or `not found` on a script | step 2 (line endings), then the `chmod` lines in step 5 |
| `/info` gives nothing | `/etc/init.d/coinslot start`, then `logread -e coinslot`; check `socat` is installed |
| portal shows "Coin payment is offline" | the listener is not running or cannot reach the box: `curl http://127.0.0.1:8099/info`, then `curl http://<box-ip>/api/gateway/challenge` |
| top-up or throttle does nothing | `ndsctl status` works? `logread -e opennds`; the manager re-grants time with `ndsctl deauth` then `ndsctl auth` |
| challenge gives `GATEWAY_DISABLED` | the key was not set on the box: repeat step 4 |
| portal page does not appear | `ndsctl status`, `logread -e opennds`; confirm step 7 with `uci show` |

## Switching to the MicroPython edition (experimental, optional)

`coinslot-fast.sh` does the same job as `coinslot-listener.sh` in one resident MicroPython process: no per-request
process starts, live updates pushed the moment something changes, and a WebSocket endpoint (`/ws`) for the start and
finish commands. It is **not enabled by default and is not part of the package**; the shell edition stays the
supported one. Settings, vouchers and state files are shared, so you can switch back and forth.

**Before you start:** the normal install (steps 1 to 9) works, and nobody is paying right now. Try it on a test router
first: it has not been run on a real router yet, and it should not take real payments until it has.

Run these on the router over SSH (`ssh root@<router-ip>`):

1. **Check that MicroPython can do the job.**
   ```
   opkg update && opkg install micropython
   micropython -c "import hashlib; hashlib.sha1; print('ok')"
   ```
   It must print `ok`. If it does not, stop here and stay on the shell edition.
2. **Copy the two files to the router** (from the repository, on your computer):
   ```
   scp opennds/coinslot-fast.sh root@<router-ip>:/usr/bin/coinslot-fast.sh
   scp opennds/coinslot-fast.init root@<router-ip>:/root/coinslot-fast.init
   ```
   then on the router: `chmod +x /usr/bin/coinslot-fast.sh`. Keep `coinslot-listener.sh` in `/usr/bin/`; it is your way back.
3. **Back up the current service file and install the fast one.**
   ```
   cp /etc/init.d/coinslot /root/coinslot.init.shell
   cp /root/coinslot-fast.init /etc/init.d/coinslot
   chmod +x /etc/init.d/coinslot
   ```
   The fast service runs ONE instance, `coinslot-fast.sh serve`, which serves the API port, the stream port and the
   fair-use watcher. Do not just edit the old file's command: its separate `stream` and `fairuse` instances would keep
   running next to it, fight over the stream port and run fair-use twice.
4. **Restart and check.**
   ```
   /etc/init.d/coinslot restart
   logread -e coinslot | tail
   ps | grep coinslot-fast
   curl -s http://127.0.0.1:8099/info
   ```
   You should see one `micropython` process for `coinslot-fast.sh serve`, and `/info` should answer with the coin timings
   and a `stream_port`. Then run the checks in step 9 (`coinslot-listener.sh box` still works) and try one coin through
   the portal.
5. **Going back to the shell edition:**
   ```
   cp /root/coinslot.init.shell /etc/init.d/coinslot
   /etc/init.d/coinslot restart
   ```

Known problem: the integration test suite run against this edition (`LISTENER_IMPL=fast python3 tests/test_flow.py`)
passed in 7 of 8 consecutive runs on the fake box; a few timing-dependent checks still fail now and then. It has not
been run on a real router.
