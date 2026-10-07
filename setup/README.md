# Automatic router setup

One file, `piso-setup.sh`, turns a factory-reset OpenWrt router into the whole PisoWiFi system.

## Before you start
1. **Flash the ESP32 coin box** from the browser (https://pisophone.pages.dev/flash.html, see "Flashing the coin box" below) and power it on. It joins the hidden `PisoCoinBox` Wi-Fi by itself, which the router creates. A used box must be factory reset first (it remembers its old Wi-Fi).
2. **Modem into the router's WAN port** (internet is needed once, to download packages).
3. A PC on one of the router's **LAN ports**.
4. The modem's own network must not be `10.0.0.x` or `192.168.30.x` (the script stops and tells you if it is).

### Flashing the coin box (once per box, by USB)
Open **https://pisophone.pages.dev/flash.html** in Chrome or Edge on a computer, plug the box's ESP32 in with a USB data
cable and click **Connect the box and install**. The page detects the chip (ESP32-C3 or ESP32), downloads the matching
image, checks its sha256, writes it, reads it back to verify, and restarts the box. Keep **Erase everything first** ticked
for a new or used box. If it does not connect: hold the board's BOOT button, tap RESET, release BOOT, and try again.
The images are built by CI from the branch's firmware (`.github/workflows/firmware-images.yml`, the `-dev` environments)
and published in `website/flash/`; on the beta site the page flashes beta's firmware.

For developers, PlatformIO still works:
```
cd esp32_firmware
pio run -e esp32-c3-dev -t upload     # ESP32-C3 boards; esp32dev-dev for a classic ESP32 (environments: esp32_firmware/envs/)
```
The box starts with the published defaults (admin password `Coinslot@Setup`, setup Wi-Fi `PisoCoinBox`); the router setup
changes the password and key by itself, and the box refuses coins until its password has been changed.

## Easiest: let your computer do steps 1 and 2 (no commands to type)
Download **https://pisophone.pages.dev/pisophone_setup.py** (also in this folder: `setup/pisophone_setup.py`), plug the
computer into a LAN port of the factory-reset router (modem in its WAN port, the coin box flashed and powered on), turn the
computer's Wi-Fi off, and run it:
```
python3 pisophone_setup.py                  (Windows: py pisophone_setup.py, or double-click it)
python3 pisophone_setup.py --branch beta    (the beta branch's setup, for testing)
```
It asks for the public Wi-Fi name, the site name and the three passwords (Enter generates them), shows everything on a review
screen, and after you answer `y` it does the rest by itself: it finds the router (192.168.1.1 or 10.0.0.1), runs the one-line
installer there unattended (`install.sh --yes`, which checks the setup file's sha256), waits for the router to come back at
10.0.0.1 (if it does not within half a minute it tells you to unplug and replug the cable; Windows renews its address by
itself), runs the setup with your answers while you watch its output, and saves **the summary (every password) and the
printable setup sheet** in the current folder (only you can read them), then opens the sheet. At the end it offers to connect
Telegram alerts.

It needs Python 3.8 or newer and the `ssh` command, which Windows 10/11, macOS and Linux already have (Windows: Settings > Apps
> Optional features > OpenSSH Client, if it is missing). The passwords travel over the SSH connection's input, never on a
command line, and the router's SSH key is pinned for the run (the same key must answer at 192.168.1.1 and at 10.0.0.1).
Running it again is safe: a router that was set up before keeps its names and passwords and is only finished or repaired
(ssh then asks for the router password, which is in the saved summary). Other uses: `--update` installs new software on a
router that is set up already, `--yes` asks nothing (defaults and generated passwords), `--guest-ssid` and `--site-name` set
the names, `--out <folder>` chooses where the files go. If it stops with an error, it says why; fix that and run it again.

The steps below are the same thing by hand.

## Step 1: one line on the router
Plug your computer into a LAN port of the factory-reset router, log in and paste one line:
```
ssh root@192.168.1.1
wget -qO- https://pisophone.pages.dev/install.sh | sh
```
It downloads the current setup file to `/root/piso-setup.sh`, checks it (its published sha256, that it is complete and readable),
then asks to move the router from `192.168.1.1` to **10.0.0.1** (the kiosk network). Answer `y`: the SSH session ends, which is
expected. Unplug and replug the computer's cable (or wait a minute), then continue:
```
ssh root@10.0.0.1
./piso-setup.sh
```
(A router that is already at 10.0.0.1 goes straight on to the setup.) If the modem itself uses `10.0.0.x`, the installer stops and
tells you to change the modem's address first.

Other uses of the same line: `... | sh -s update` installs new software on a router that is set up already, and
`... | sh -s -- --branch beta` uses the setup file of another branch (testing); `... | sh -s -- --yes` asks nothing (it moves the
router by itself and runs the setup unattended, with passwords from `ROOT_PASSWORD`, `KIOSK_PASSWORD`, `BOX_NEW_ADMIN_PASSWORD`
or generated: this is what `pisophone_setup.py` uses). The installer is `setup/install.sh`; the website serves copies of it and
of `pisophone_setup.py` (`website/install.sh`, `website/pisophone_setup.py`, kept equal by `tools/build_piso_setup.py`).

## Step 2: run the setup
Without internet on the router (or to do it by hand): set the address yourself (`uci set network.lan.ipaddr='10.0.0.1'; uci commit
network; /etc/init.d/network restart`, then log in at 10.0.0.1), then copy the file over and start it:
```
scp -O setup/piso-setup.sh root@10.0.0.1:/root/
ssh root@10.0.0.1 'sed -i "s/\r$//" piso-setup.sh && sh piso-setup.sh'
```
Answer `y` when asked. It then asks for the **public Wi-Fi name** (default `PisoWiFi`; Enter keeps it; 32 characters at most, no quotes; the rental-phone network is always the hidden `PisoKiosk`) and then to choose **three passwords** (each typed twice, not shown; just press Enter to have a strong one generated for you): the router password (SSH and LuCI), the **PisoKiosk Wi-Fi** password (typed into each phone's setup page) and the **coin box admin** password (the box's web page, also the phones' admin PIN). Use 8 or more characters without spaces or quotes. The coin box's *super-admin* password is not asked: the firmware keeps it under remote management and it cannot be set locally. For unattended runs set `ROOT_PASSWORD`, `KIOSK_PASSWORD` and `BOX_NEW_ADMIN_PASSWORD` in the environment. Before anything is changed it shows a **review screen** (names, which passwords you chose and which will be generated, router address and country) and asks `Apply these settings? [y/N]`. It also asks for a **site name** (printed on the setup sheet and shown in Telegram messages) and offers to connect **Telegram** at the end of the setup. It runs in front of you for about 3 to 8 minutes and **keeps your SSH session open the whole time**: it installs packages, creates the networks, waits for the ESP32 to join, sets its password and key, starts everything and ends with a health check. When it prints `SETUP COMPLETE`, read the summary:
```
cat /root/piso-setup-summary.txt
```
It has every password (router, PisoKiosk Wi-Fi, coin box admin). The setup also writes a **printable page for the shop owner**, `/root/piso-handout.html` (copy it off with `scp -O root@10.0.0.1:/root/piso-handout.html .`, or run `piso-setup handout` later). Keep it private. The coin box also gets its own random Wi-Fi password for the hidden box network (the built-in one is public); you never need to type it. If you replace the box, `piso-setup pair` starts again from the built-in one.

## Step 3: check that it works (do this before any customer or partner uses it)
1. `piso-setup status` must end with `All checks passed.`
2. `piso-setup test-coin`: it arms the slot; insert one coin. It must say `the box counted 1 peso(s)`.
3. **Wi-Fi customer:** join the customer Wi-Fi (`PisoWiFi`) with a phone, open the login page, pick a plan, tap Insert Coin, insert one coin. The page shows each coin at once with a countdown, and a **Done** button. You are *not* online yet (so the phone does not close the login page while you add coins). Insert more coins if you like, then tap **Done** (or wait 15 s after the last coin): the page then says you are online with your time and code. Browse something.
4. **Second device:** while a window is open, try Insert Coin on another phone. It must be refused with a "Try again" page.
5. **Rental phone:** join `PisoKiosk` with a kiosk phone (the app's own flow) and pay once.
6. `piso-setup reconcile` must say `RECONCILE OK` (needs firmware 3.2.1). After the box's revenue is collected its count starts again at 0;
   the portal notices that by itself ("compared from now on"). If a mismatch stays after a collection: `piso-setup reconcile rebase`.
7. `piso-setup diag > diag.txt` and keep the output: it contains the coin timings and no passwords. Send it with any bug report.

## Step 4 (optional, recommended): Telegram alerts and remote control
1. In Telegram, open **@BotFather**, send `/newbot`, pick a name, copy the token it gives you.
2. On the router: `piso-setup telegram`. Paste the token, give the site a name (it prefixes every message), then open your new bot in Telegram and send it any message. The router shows the chat it found; answer `y` if it is you.
3. You get a "connected" message. From then on you receive: router restarted, box offline for 5 minutes (and back), revenue mismatch or edited ledger, a device abusing the coin slot, and a daily report at 21:00.
4. Commands (only from your chat): `/status`, `/report [days]`, `/reconcile`, `/diag`, `/restart` (the portal), `/reboot` (then `/reboot confirm` within 2 minutes), `/help`.
5. Optional dead-man switch: create a check at healthchecks.io, put its ping URL in `/etc/piso-monitor.conf` as `HEALTHCHECK_URL='...'`, then `/etc/init.d/piso_monitor restart`. You are alerted when the router stops pinging (power cut, internet down).
The router needs internet for this: while the site is offline nothing can be sent, and alerts raised in that time are not delivered later (the dead-man switch covers that case).

## Step 5: set up each rental phone (QR code, or USB as the fallback)
Open the box's page, click **Install & Provision** for the slot, and type the **PisoKiosk** Wi-Fi password (from
`piso-setup summary`; the page remembers it on that computer). Then:

**QR code (recommended; USB only for the phones that need it):**
1. Use a new or **factory-reset** phone. On the first welcome screen, **tap the same spot 6 times**: a QR reader opens (some
   older phones first ask for a Wi-Fi to download the reader).
2. Click **Show the setup code** on the page and scan it with the phone.
3. The phone joins PisoKiosk, downloads PisoPhone, checks its signature, makes it the device owner and hands it the box's
   key, MAC, slot and the Wi-Fi password. Accept the screens it shows until PisoPhone shows **One last step**: the lock
   screen and the time bubble need "display over other apps", which no app may grant to itself. The app picks the way:
   - **Most phones:** tap **Open the setting**, choose PisoPhone, turn on **Allow display over other apps**, press Back. The
     kiosk starts.
   - **Android Go phones** (they have no such switch), or if the switch is missing or greyed out: plug the phone into the
     computer showing the page, tap **Allow** on "Allow USB debugging?" (the app turned USB debugging on by itself at the end
     of the setup: no Developer options), and click **Finish over USB**. The computer grants the permissions, the kiosk
     starts, and the page opens the box's page to pair the slot.
   Either way USB debugging goes off again as soon as the permission is on (and after 15 minutes at the latest).
4. Pair the slot on the box's page as usual (Finish over USB opens it for you).
No PIN is asked within 30 minutes of the setup; later the admin PIN is.
The code holds the box's key and the Wi-Fi password: show it only to the phone you are setting up (the page hides it when
you click Hide or change the password). It needs an app published by CI with its signing fingerprint
(`update/app.json` has `signatureChecksum`); until then the page says to use the USB cable.

**USB cable (fallback):** for a phone whose welcome screen has no QR reader. Chrome or Edge on a computer, a USB data cable,
a factory-reset phone with no account, USB debugging on (Xiaomi/Redmi/POCO: also "Install via USB" and "USB debugging
(Security settings)"; they need a Mi account: sign in, turn them on, then remove the account). Click **Connect the phone
and set it up**: the page checks the phone first (accounts, extra users), installs, makes PisoPhone the device owner and
gives it the box's details in one step that the app confirms.

Either way the app joins PisoKiosk, rejoins it whenever the phone is on another network (checked every minute), and shows
short status lines (Wi-Fi, box search) while it finds its box.
To fix a phone that is already provisioned (it needs the admin PIN or box secret because it is paired already):
```
adb shell am broadcast -a com.pisophone.kiosk.CONFIGURE_ESP32 -n com.pisophone.kiosk/.receiver.KioskAdminActionReceiver --es wifi_ssid PisoKiosk --es wifi_pass '<password>' --es pin '<admin PIN>'
```

## What you get
| network | for | bands | notes |
|---|---|---|---|
| **PisoKiosk** (hidden) | the rental phones | 2.4 + 5 GHz | WPA2, fixed name, **not broadcast**: it appears in no Wi-Fi list, so only phones set up from the coin box's provisioning page (which gives them the name and password) can join. Password chosen or generated. This is the router's LAN (`10.0.0.0/24`, the address you set in step 1). |
| **PisoCoinBox** (hidden) | the ESP32 only | 2.4 GHz | After pairing, only the box's MAC address may join. The box is always `10.0.0.10`. |
| **PisoWiFi** | customers | 2.4 + 5 GHz | Open, behind the openNDS login and coin payment. Rename: `piso-setup wifi-name "My Shop"`. Separate network `192.168.30.0/24`. |

The script also installs the packages, `pisoportal` (the resident coin portal: openNDS FAS mode, one WebSocket per customer), sets the box's admin password and gateway key through its API,
sets a root password, and ends with a health check. The router's wired LAN ports stay on the same network as PisoKiosk (administration).

## Day to day
| command | what it does |
|---|---|
| `piso-setup status` | checks every part |
| `piso-setup wifi-name "Name"` | renames the customer Wi-Fi (also the login page title) |
| `piso-setup pair` | replaces the coin box: re-opens pairing for a few minutes, then locks to the new box |
| `piso-setup summary` | shows the saved passwords again |

Running `piso-setup.sh` again is safe: it keeps the passwords and names it already made. To install new software only (no Wi-Fi or box changes), use `./piso-setup.sh update` from the new file.

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
| `piso-setup reconcile [rebase]` | compares the box's own coin count with the router's revenue ledger and checks that nobody edited the ledger (needs firmware 3.2.1). The box's count restarts when its revenue is collected; that is noticed by itself, and `rebase` compares from now on. |
| `piso-setup telegram` | connects a Telegram bot: alerts (router restarted, box offline, revenue mismatch, a device abusing the coin slot, daily report) and remote commands `/status /report /reconcile /diag /restart /reboot`, answered only to your chat. Create the bot with @BotFather first. Optional: put a healthchecks.io URL in `/etc/piso-monitor.conf` (`HEALTHCHECK_URL`) so you are told when the whole router goes silent. |
| `piso-setup self-update [check]` / `auto-update on\|off` | **updates from the website**: the router checks hourly for a release you signed (docs/RELEASE.md), installs it when no customer is online, goes back by itself if it does not run, and tells you in Telegram (`/update` installs now). Only releases signed with your owner key are accepted. |
| `./piso-setup.sh update` | **updates only the software** on an installed router: installs the `pisoportal` program and the Telegram monitor files from the new file and restarts them. It changes no Wi-Fi or network settings, passwords, pairing or customer data. Copy the new `piso-setup.sh` to the router and run it from there (running the installed `piso-setup update` would reinstall the old copy, so it refuses). A coin window open at that moment is picked up by crash recovery. |
| `piso-setup handout` | writes the printable setup sheet `/root/piso-handout.html` again. |
| `piso-setup rotate-box-wifi` | gives the coin box a new random Wi-Fi password for the hidden box network (done automatically by the setup). |
| `piso-setup lock-admin [MAC...]` | opt-in: only the named computers (default: the one you are on) may reach the router's SSH and web pages (ports 22, 80, 443); the rental phones can no longer try them. It **undoes itself after 2 minutes unless you open a new SSH session to check, then type CONFIRM** (or run `piso-setup lock-admin-confirm`), so it cannot lock you out. `piso-setup unlock-admin` removes it. |
| `piso-setup diag` | prints one block (status, manager, box, firewall, recent logs; no passwords) to paste when asking for help. |

## If something fails
* **"the coin box did not join"**: the box must be powered, have the current firmware, and be a fresh or factory-reset unit (an old unit remembers its old Wi-Fi). Then `piso-setup pair`.
* **"the box refused the admin login"**: the box already has its own password. Factory reset it, or run `BOX_ADMIN_PASSWORD='<its password>' piso-setup pair`.
* **`: not found` errors and a syntax error right at the start**: the file has Windows line endings. Run `sed -i 's/\r$//' piso-setup.sh` and try again.
* Everything is logged in `/root/piso-setup.log`.

## For developers
`setup/piso-setup.sh` is generated from `setup/piso-setup.sh.in` plus the portal files: after changing any of them run
`python3 tools/build_piso_setup.py` (CI fails if the committed file is out of date). Tests: `python3 router/tests/test_setup.py`.
`./piso-setup.sh --dry-run` prints the router settings without applying anything.
