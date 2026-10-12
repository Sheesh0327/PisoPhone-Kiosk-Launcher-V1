# Set up PisoPhone

One program does the whole setup, from the bare boards to phones that are working: **`pisophone_setup.py`**. It runs on any
computer, with **no screen needed**: it serves its setup as a web page, so a small Armbian or Parrot computer can run it and you
open the page on your **phone**. It asks only for the shop's name and Wi-Fi name and does the rest by itself: it writes the coin
box over USB, sets up the router and pairs them. About 15 minutes a unit, and one tap for the next.

## What you need
- The **ESP32 coin box board** and a USB data cable (a charge-only cable does not work), plugged into the setup computer
- An **OpenWrt router** (for example the 360T6M), factory reset
- A **modem** with internet, and a network cable from it to the router's **WAN** port
- A network cable from the setup computer to one of the router's **LAN** ports
- **A phone or laptop** to open the setup page (it must reach the setup computer: see "Which network?" below)
- The **rental phones**, factory reset

## Start it
1. Download **[pisophone_setup.py](https://pisophone.pages.dev/pisophone_setup.py)** onto the setup computer (Windows, macOS or
   Linux, including Armbian, Parrot, Debian, Ubuntu and Raspberry Pi OS) and run it: `python3 pisophone_setup.py` (Windows:
   `py pisophone_setup.py`; it needs Python 3.8 or newer).
2. It checks the computer by itself and **installs what is missing**: `ssh` from the system's packages (on Linux, when it runs as
   root or sudo needs no password) and Espressif's `esptool` with pip into its own folder (`~/.pisophone`, no root needed, also on
   systems whose Python is "externally managed"). `--no-install` turns this off.
3. It prints the addresses and a **PIN**:
   ```
   The setup page is running. Open it in a browser:
     on this computer:  http://127.0.0.1:8765
     on your phone:     http://192.168.1.50:8765   (same network as this computer)
     PIN: 482913
   ```
   Open the address on your phone (or the computer's own browser, which opens by itself when it has a screen) and type the PIN.

**No monitor at all (a small Armbian PC):** run it once over SSH (or with a keyboard) as `sudo python3 pisophone_setup.py
--install-service`. The setup page then starts by itself whenever the computer boots; it prints the address and PIN once, and
`cat /var/lib/pisophone-setup/pin` shows the PIN again (`--pin 123456` chooses your own). Stop it with
`sudo systemctl disable --now pisophone-setup`. The service runs as root so it can use the USB port and install what it needs;
its files (the saved summaries) are in `/var/lib/pisophone-setup/saved`, and the page opens them for you.

### Which network? (so the phone can reach the page)
The page is served on the setup computer's own address, so the phone must be on a network that reaches it. A factory-reset
router has **no Wi-Fi yet**, so the phone cannot use that one. Two ways that work:
- **The setup computer has a second connection** besides the cable to the router (its Wi-Fi, or another Ethernet port) on your own
  network, and the phone is on that network too. Keep that connection on: the program talks to the router through the cable only
  (192.168.1.1, then 10.0.0.1).
- **Or use the computer's own browser** (`http://127.0.0.1:8765`), when it has a screen.
The setup page itself needs no internet on the phone, but the *phone setup page* in step 5 is on the PisoPhone website: open that
link on a phone or laptop that has internet.

**Safety:** the page is plain HTTP to your local network, protected by the PIN (five wrong PINs lock it for a minute; a web page
from another site cannot reach it). It shows the passwords of the unit you are setting up, so use it on a network you trust, or
add `--local-only` to serve the computer's own browser only.

## The steps

| Step | What you do | What the program does |
|---|---|---|
| **1 Start** | Read what you need | Checks this computer, installs what is missing |
| **2 Router** | Reset the router, plug in the modem and the cable | Finds the router by itself |
| **3 Settings** | Type this customer's shop name and public Wi-Fi name (asked every time); keep the passwords it made (new ones for every unit), or type your own | Checks every entry |
| **4 Install** | Plug the ESP32 in by USB (a data cable), tap *Start the installation*, wait about 10 minutes | Writes the PisoPhone software to the box (erasing it first), installs the router software, pairs the coin box, checks everything, saves your passwords and a printable sheet |
| **5 Phones** | Open the setup page link and scan its QR code with each factory-reset phone | Makes the link to the phone setup page, already filled in with this box's key |
| **6 Finish** | Insert one coin; try the customer Wi-Fi with a phone; tap *Set up the next unit* for the next router and box | Tests the coin and offers Telegram alerts; starts over for the next unit |

Keep the USB cable plugged in during the installation: it powers the coin box. If the box already has the PisoPhone software,
tick *Do not write it* on the install page. Your passwords and the printable setup sheet are saved on the setup computer
(`Documents/PisoPhone`, readable only by you) and can be opened from the last page.

## Setting up a phone
On the phone setup page (step 5) the link is filled in for the chosen slot. On the rental phone:
1. On its first welcome screen **tap the same spot 6 times**: a QR reader opens.
2. On your own phone or laptop open the link, tap **Show the setup code** and scan it with the rental phone. It joins the Wi-Fi,
   installs PisoPhone and locks itself as a kiosk.
3. When the rental phone shows **One last step**, do what it says. On most phones: tap *Open the setting* and switch on *Allow
   display over other apps*. On an **Android Go** phone (it has no such switch): plug it into a computer, tap *Allow* on "Allow USB
   debugging?" and use *Finish over USB* on the setup page (it needs Chrome or Edge on that computer).
4. Pair the slot on the coin box's page (`http://10.0.0.10`, from a computer on the router's network). Done: the phone is a kiosk.

For the next phone, choose the next slot and make the link again.

## If something goes wrong
| What you see | What to do |
|---|---|
| The page does not open on the phone | The phone and the setup computer must be on the same network (see above). Use the address printed in the terminal; a firewall may block port 8765 (`--port` changes it). |
| "No board found" / the board does not answer | Use a USB **data** cable. Hold the board's BOOT button, tap RESET, release BOOT, tap *Try again*. Linux: you must be in the `dialout` group (or run as root). Windows: install the CH340 / CP210x driver once. Close any serial monitor that uses the port. On Ubuntu / Parrot desktops ModemManager can grab the port: `sudo systemctl stop ModemManager`. |
| "No router found" | Cable in a **LAN** port (not WAN), router on for 2 minutes after the reset. The page keeps looking every few seconds; after a fix it finds the router by itself. |
| "The router could not download..." | The modem must be in the router's WAN port and have internet. |
| "The coin box did not join" | Is the box powered (the USB cable does it), near the router, and written by this program (it erases the board first)? Tap *Try again*: the installation continues where it stopped. |
| esptool or ssh "Missing" on the first page | Tap *Install what is missing*. Without root rights it shows the exact `sudo apt install ...` command to run. |
| The phone does not ask "Allow USB debugging?" | Unlock the phone, use a data cable, unplug and replug. Or use the on-phone setting. |
| Anything else | Tap *Try again*: it is always safe, and the router keeps what is already done. |

## After the setup
- **Another phone:** run the program (or open the coin box's page at `http://10.0.0.10`) and use *Set up a phone* for the next slot.
- **New software** for the router: `python3 pisophone_setup.py --update`.
- **Check on the router at any time:** `ssh root@10.0.0.1`, then `piso-setup status`. More commands: [`MANUAL.md`](MANUAL.md), "Day to day".

## Prefer to do it by hand?
[`MANUAL.md`](MANUAL.md) has every step, the router commands, the command-line version of the program (`--cli`) and the repair tools.
