# Flash coin portal (green theme)

The "flash coin" portal is the pay-with-coins portal in the look and structure of your working paper-voucher theme
(`flash_voucher.sh` / `green_sprite_params.sh`), without the paper vouchers: a customer chooses a plan, drops coins, and the
coin-slot manager **verifies the payment**; the theme then records it on one roll file and authenticates the device through
openNDS (`auth_log`). Nothing else decides whether somebody gets online.

Keep it simple: one roll file, one lock, one writer (the theme), one small manager that only counts coins.

| piece | file | role |
|---|---|---|
| portal pages | `flash_coin.sh` | the ThemeSpec: welcome, coin window, result, connect, auto-reconnect, pause/resume, restore by code |
| roll + helpers | `flash_coin_lib.sh` | the roll (lock, atomic writes), payments, pause, restore, openNDS calls |
| status page | `flash_coin_status.sh` | the green page a connected customer sees at `http://<router>/` (time left, Pause, Add time) |
| fair use | `flash_fairuse.sh` | HyperSpeed slowed after 5 GB (limits come from the manager), old sessions purged |
| service | `flash_coin.init` | `/etc/init.d/flash_coin`: manager + live stream + fair-use watcher |
| coin counting | `coinslot-listener.sh` | **shell edition only** (`/verify` and `/ack` are used by this portal) |

## What it does

* **Plans:** HyperSpeed and Endurance with the rates and speed caps of the settings file, exactly as in the other portal.
* **Roll:** `/etc/coinslot.d/vouchers.txt` on the router flash, one CSV line per device
  (`code,rate_down,rate_up,quota_down,quota_up,time_limit_min,first_punched,mac,pauses_used,paused_at,remaining_at_pause,plan,wid,pesos`;
  the first 11 fields are your voucher-roll layout). It is written only when somebody **pays, pauses or resumes**, never on a
  page view, so flash wear is a few writes a day. Change the place with `FLASH_ROLL=/mnt/sda1/ndslog/vouchers.txt` in
  `/etc/flash_coin.conf` (a USB stick is kinder to the flash on a busy shop). `revenue.csv` is kept next to it.
* **Online on the first coin, no Connect tap:** the box pushes each coin to the router the moment it is counted (signed UDP
  "coin events", firmware 3.2.0+; older firmware still works, the router then asks it every 0.1 s). The router puts the
  device online at the first coin, keeps counting while the slot is open (15 s after each coin), and when the window
  closes prices the **window's total** once at the best rate (P17 = 10 + 5 + 1 + 1), records it on the roll, re-grants
  once, and only then acknowledges the coins on the box. Closing the page or a sleeping phone loses nothing.
* **One live page:** after the first page, Insert Coin, the coins, "You're online" and the final time and code all update
  in place through the router's small coin API (port 8100), with no page reloads. Without scripts, or if that port is
  blocked, the regular pages are used instead.
* **Safe payments:** every window has a one-time id (`wid`). Writing the same window again (first coin, then close)
  replaces its share; a closed window is never credited twice, and revenue is logged once per window.
* **Top-up:** coins on the same plan add to the time left. The other plan needs the customer's agreement (the time left on
  the old plan is forfeited when they pay).
* **Automatic reconnect:** after a reboot or power cut openNDS forgets its sessions; the first page a device opens reconnects it
  from the roll by MAC address, even if the manager is down.
* **Pause / Resume:** Endurance sessions of P10 or more can pause once. Pausing disconnects the device and freezes the
  time on the roll (kept 72 hours); it resumes from the portal page ("Resume") or with coins.
* **Code:** every session has a code like `k3f9-a1b2`. Typing it on another device moves the session there (10 wrong tries in
  10 minutes locks guessing for 10 minutes).
* **Live coin updates and spoken confirmations** work as in the other portal (stream on port 8100).

## Install

Follow **`INSTRUCTIONS-SHELL.md` Parts 1 to 6 and 8** unchanged (router, two networks, box, key, settings). At Part 7, copy
this set of files instead:
```
scp opennds/flash_coin.sh opennds/flash_coin_lib.sh opennds/flash_coin_status.sh opennds/flash_fairuse.sh opennds/flash_coin.init \
    opennds/coinslot-listener.sh root@192.168.1.1:/root/
```
On the router:
```
cd /root
sed -i 's/\r$//' flash_coin.sh flash_coin_lib.sh flash_coin_status.sh flash_fairuse.sh flash_coin.init coinslot-listener.sh
cp flash_coin.sh flash_coin_lib.sh flash_coin_status.sh flash_fairuse.sh /usr/lib/opennds/
cp coinslot-listener.sh /usr/bin/coinslot-listener.sh
cp flash_coin.init /etc/init.d/flash_coin
chmod +x /usr/lib/opennds/flash_coin*.sh /usr/lib/opennds/flash_fairuse.sh /usr/bin/coinslot-listener.sh /etc/init.d/flash_coin
```
(The `.ipk` from `build_ipk.py` already contains all of these.) Then, instead of Parts 9 and 10:
```
uci set opennds.@opennds[0].login_option_enabled='3'
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/flash_coin.sh'
uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/flash_coin_status.sh'
# bursting and the rest of Part 9 stay as written there
uci commit opennds

/etc/init.d/coinslot stop; /etc/init.d/coinslot disable      # only one service may own port 8099
/etc/init.d/flash_coin enable
/etc/init.d/flash_coin start
/etc/init.d/opennds enable
/etc/init.d/opennds stop
/etc/init.d/opennds start
```

## Check
```
curl http://127.0.0.1:8099/info          # contains "fair_kb" and "fair_down" (the flash portal needs a current coinslot-listener.sh)
/etc/init.d/flash_coin status            # running
ls -l /usr/lib/opennds/flash_coin*.sh    # four files, executable
```
First test: join the guest Wi-Fi with a phone, pick a plan, Insert Coin, drop a coin: the phone is online about half a
second later. `piso-setup diag` shows the timings of the last coins. Then
`cat /etc/coinslot.d/vouchers.txt` shows the new line and `logread -e opennds -e coinslot` the grant.

Reboot test: with a paid phone connected, `reboot` the router. When it is back, open any web page on the phone: it reconnects by
itself with the time that was left.

## Go back to the other portal
```
/etc/init.d/flash_coin stop; /etc/init.d/flash_coin disable
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/coinslot_status.sh'
uci commit opennds
/etc/init.d/coinslot enable; /etc/init.d/coinslot start
/etc/init.d/opennds stop; /etc/init.d/opennds start
```
The two portals keep their sessions in different places (`vouchers.txt` and `vouchers/`), so time bought on one is not
visible on the other.

## Troubleshooting
* **"Coin payment is offline"**: the manager is not running (`/etc/init.d/flash_coin start`), or it is an old version
  (no `/verify`): copy the current `coinslot-listener.sh`.
* **A device keeps asking for coins although it paid**: `grep <mac> /etc/coinslot.d/vouchers.txt`; no line means the payment was
  never recorded (the coins are still on the box until acknowledged: open the portal again), an expired line is replaced by the
  next payment.
* **Roll busy**: another request holds `/tmp/flash_coin.lock` (it clears itself after 10 seconds).
* **Edit by hand**: stop nothing; edit with care while nobody is paying, or `rm -rf /tmp/flash_coin.lock` first if a lock is stuck.
