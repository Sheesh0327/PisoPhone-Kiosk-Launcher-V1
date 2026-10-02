# Setup instructions (OpenWrt + openNDS + PisoPhone coin slot)

Everything below is copy/paste. Replace the values in `<angle brackets>`.
Steps 2 onwards run on the router over SSH (`ssh root@<router-ip>`).

## 0. Before you start
- The PisoPhone box runs the firmware that includes the gateway API. You know its IP (give it a DHCP
  reservation so it never changes) and its admin password.
- The router has internet access (for `opkg`).

## 1. Copy the files to the router
From the PC that has this folder (Windows PowerShell, macOS or Linux terminal), replace `<router-ip>`:

```
scp opennds/theme_coinslot.sh opennds/coinslot-listener.sh opennds/coinslot.init root@<router-ip>:/root/
```

Then log in to the router: `ssh root@<router-ip>`.

## 2. Fix line endings (needed if the files ever touched Windows)
```
cd /root
sed -i 's/\r$//' theme_coinslot.sh coinslot-listener.sh coinslot.init
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
| `coinslot-listener.sh` | `/usr/bin/coinslot-listener.sh` | executable |
| `coinslot.init` | `/etc/init.d/coinslot` | executable |

```
cp /root/theme_coinslot.sh /usr/lib/opennds/theme_coinslot.sh
cp /root/coinslot-listener.sh /usr/bin/coinslot-listener.sh
cp /root/coinslot.init /etc/init.d/coinslot

chmod +x /usr/lib/opennds/theme_coinslot.sh
chmod +x /usr/bin/coinslot-listener.sh
chmod +x /etc/init.d/coinslot
```

## 6. Write the settings file (holds the key, so owner-only)
Run this in the same session as step 4 so `$KEY` is filled in. Paste the whole block at once:

```
cat > /etc/coinslot.conf << EOT
GW_BOX=<box-ip>
GW_KEY=$KEY
WIFI_MINUTES_PER_COIN=10
COIN_WINDOW_SECONDS=60
LISTEN_PORT=8099
STATE_DIR=/tmp/coinslot
EOT
chmod 600 /etc/coinslot.conf
cat /etc/coinslot.conf
```
Check the printed file shows your real box IP and a 64-character key (not the text `$KEY`). If you opened a new
session and lost `$KEY`, repeat step 4 to make a new one.

## 7. Tell openNDS to use the theme
```
uci set opennds.@opennds[0].login_option_enabled='3'
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
uci commit opennds
```

## 8. Start everything
```
/etc/init.d/coinslot enable
/etc/init.d/coinslot start
/etc/init.d/opennds enable
/etc/init.d/opennds stop
/etc/init.d/opennds start
```
(`restart` can print "Command failed: Not found" when openNDS was not running; `stop` then `start` avoids that.)

## 9. Check each piece
```
curl http://127.0.0.1:8099/info                       # expect {"rate":10,"window":60}
curl http://<box-ip>/api/gateway/challenge            # expect {"nonce":"..."}
uci show opennds | grep -E "login_option|themespec"   # expect both lines
ndsctl status                                         # openNDS is running
```
Then join the Wi-Fi with a phone and watch: `logread -f -e opennds -e coinslot`

## Changing settings later
Edit `/etc/coinslot.conf` and run `/etc/init.d/coinslot restart`. To rotate the key, repeat steps 4 and 6, then restart.

## If something fails
| symptom | fix |
|---|---|
| `Permission denied` or `not found` on a script | step 2 (line endings), then the `chmod` lines in step 5 |
| `/info` gives nothing | `/etc/init.d/coinslot start`, then `logread -e coinslot`; check `socat` is installed |
| challenge gives `GATEWAY_DISABLED` | the key was not set on the box: repeat step 4 |
| portal page does not appear | `ndsctl status`, `logread -e opennds`; confirm step 7 with `uci show` |
