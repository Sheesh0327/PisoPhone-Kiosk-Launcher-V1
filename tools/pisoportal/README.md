# pisoportal: step 1 (try it on a router)

A tiny resident web server that proves the pieces the real portal needs, on **your** router, before anything else is built:
openNDS FAS mode (redirect, port, `ndsctl auth`), a WebSocket, how fast a page comes back, and how much memory it uses.
It changes nothing permanent: you point openNDS at it for the test and switch back after.

Design and reasoning: the portal is a resident program that serves the splash from memory (openNDS only redirects and
enforces); the page opens a WebSocket for Insert Coin, coins and Done. openNDS docs used: FAS chapter (`fasport`,
`faspath`, `fas_secure_enabled 1`), `ndsctl auth` (10.3.1).

## 1. The program

CI builds `bin/pisoportal-mipsel` (for `mipsel_24kc`, like MT7621) and commits it to the `beta` branch (a few hundred KB).
On your computer, after `git pull origin beta`:

```
scp -O tools/pisoportal/bin/pisoportal-mipsel root@10.0.0.1:/tmp/pisoportal
ssh root@10.0.0.1
chmod +x /tmp/pisoportal
/tmp/pisoportal --selftest        # must print: sha1: true  websocket accept key: true  base64: true  fas query: true
```
Send me that output (it also shows the CPU type and the program's memory).

## 2. Point openNDS at it (temporarily)

```
/tmp/pisoportal --port 2080 &
uci set opennds.@opennds[0].fasport='2080'
uci set opennds.@opennds[0].faspath='/'
uci set opennds.@opennds[0].fas_secure_enabled='1'
uci set opennds.@opennds[0].login_option_enabled='0'
uci add_list opennds.@opennds[0].users_to_router='allow tcp port 2080'
uci commit opennds
/etc/init.d/opennds restart
```
Port 80 cannot be used (openNDS keeps it for captive-portal detection); 2080 is the documented example.

## 3. Try it from a phone

Join the customer Wi-Fi and open any `http://` page (for example `http://neverssl.com`). You should land on a dark page
titled "pisoportal step 1" that shows what openNDS sent (client IP and MAC, gateway name) and how fast it was served.
Then tap:
* **Test WebSocket**: five round trips, in milliseconds. Tell me the numbers.
* **Grant this phone 5 minutes**: runs `ndsctl auth` for the phone. Afterwards the phone should have internet.

On the router, `logread` is not used: the program prints to its own output (the terminal you started it from).
Useful checks: `ps | grep pisoportal` and `grep VmRSS /proc/$(pidof pisoportal)/status` (memory), and
`time wget -q -O /dev/null http://192.168.30.1:2080/` (how fast a page comes back on this CPU).

## 4. Switch back

```
kill $(pidof pisoportal)
uci delete opennds.@opennds[0].fasport
uci delete opennds.@opennds[0].faspath
uci delete opennds.@opennds[0].fas_secure_enabled
uci set opennds.@opennds[0].login_option_enabled='3'
uci del_list opennds.@opennds[0].users_to_router='allow tcp port 2080'
uci commit opennds
/etc/init.d/opennds restart
```
(or just run `./piso-setup.sh update` and then `./piso-setup.sh`; the full setup rewrites the openNDS section).

## What this does not do yet

No coins, no box, no money records: this is only the proof of the transport. The real portal (coin flow only at first) is
built on top of it once these checks pass on your router.
