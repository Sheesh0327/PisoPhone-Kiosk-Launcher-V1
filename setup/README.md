# Set up TechNet PisoHub

One program does the whole setup, from the bare boards to phones that are working: **`pisophone_setup.py`**. It runs on any
computer, with **no screen needed**: it serves its setup as a web page, so a small Armbian or Parrot computer can run it and you
open the page on your **phone**. It asks only for the shop's name and Wi-Fi name and does the rest by itself: it writes the coin
box over USB, sets up the router and pairs them. About 15 minutes a unit, and one tap for the next.

## What you need
- The **ESP32 coin box board** and a USB data cable (a charge-only cable does not work), plugged into the setup computer
- An **OpenWrt router** (for example the 360T6M), factory reset
- A **modem that is online**, and a network cable from it to the router's **WAN** port. **The router needs internet for the first setup**: it downloads the installer and its packages itself. A router with no internet cannot be set up; the setup stops before it changes anything and says so. Check this before you begin (replacing a router, swapping a modem, or an ISP that is not connected yet are the usual causes).
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
   In a terminal that shows colours the program also draws a **QR code** of the phone's address: scan it with the phone's camera instead of typing the address.

**No monitor at all (a small Armbian PC):** run it once over SSH (or with a keyboard) as `sudo python3 pisophone_setup.py
--install-service`. The setup page then starts by itself whenever the computer boots; it prints the address and PIN once, and
`cat /var/lib/pisophone-setup/pin` shows the PIN again (`--pin 123456` chooses your own). Stop it with
`sudo systemctl disable --now pisophone-setup`. The service runs as root so it can use the USB port and install what it needs;
its files (the saved summaries) are in `/var/lib/pisophone-setup/saved`, and the page opens them for you. The service starts its own copy of the program in `/var/lib/pisophone-setup`, so moving or deleting the downloaded file does not stop it (to update the program, run `--install-service` again).

### Which network? (so the phone can reach the page)
The page is served on the setup computer's own address, so the phone must be on a network that reaches it. A factory-reset
router has **no Wi-Fi yet**, so the phone cannot use that one. Two ways that work:
- **The setup computer has a second connection** besides the cable to the router (its Wi-Fi, or another Ethernet port) on your own
  network, and the phone is on that network too. Keep that connection on: the program talks to the router through the cable only
  (192.168.1.1, then 10.0.0.1). **Use your phone's hotspot for it, not the shop modem's Wi-Fi.** A modem from PLDT, Converge and
  most internet companies is at 192.168.1.1 too, like a new router: the computer would then have two networks with the same
  addresses and cannot tell which one the router is on. The setup page stops with this advice when it sees it; turn the computer's
  Wi-Fi onto the phone's hotspot (or off), then press *Search again*.
- **Or use the computer's own browser** (`http://127.0.0.1:8765`), when it has a screen.
The setup page itself needs no internet on the phone, but the *phone setup page* in step 5 is on the PisoPhone website: open that
link on a phone or laptop that has internet.

**Safety:** the page is plain HTTP to your local network, protected by the PIN (five wrong PINs lock it for a minute; a web page
from another site cannot reach it). It shows the passwords of the unit you are setting up, so use it on a network you trust, or
add `--local-only` to serve the computer's own browser only.

## The steps

| Step | What you do | What the program does |
|---|---|---|
| **1 Start** | Choose what you set up: *The full TechNet PisoHub*, *PisoWiFi only* (no rental phones: the Phones step is left out and no rental-phone Wi-Fi is made) or *PisoPhone only* (no router software). Nothing is chosen for you. Then read what you need | Checks this computer, installs what is missing |
| **2 Router** | Reset the router, plug in the modem and the cable | Finds the router by itself |
| **3 Settings** | Type this customer's shop name and public Wi-Fi name (asked every time); the passwords are made for you (new ones for every unit; *Advanced: type my own passwords* is folded away if you want your own) | Checks every entry |
| **4 Install** | Plug the ESP32 in by USB (a data cable), tap *Start the installation*, wait about 10 minutes | Writes the PisoPhone software to the box (erasing it first), installs the router software, pairs the coin box, checks everything, saves your passwords and a printable sheet |
| **5 Phones** | Open the setup page link and scan its QR code with each factory-reset phone | Makes the link to the phone setup page, already filled in with this box's key |
| **6 Finish** | Insert one coin; try the customer Wi-Fi with a phone; tap *Set up the next unit* for the next router and box | Tests the coin and offers Telegram alerts; starts over for the next unit |

Keep the USB cable plugged in during the installation: it powers the coin box. If the box already has the PisoPhone software,
tick *Do not write it* on the install page. Your passwords and the printable setup sheet are saved on the setup computer
(`Documents/PisoPhone`, readable only by you) and can be opened from the last page. Besides the summary and the setup sheet there
is a **Wi-Fi join card**: a print-ready page with a QR code that joins the hidden kiosk network (`PisoKiosk-xxxx`) from a phone (scan it
with the camera) and one that opens the coin box's page, plus the same steps by hand. Print it for your manual and keep it with
your admin QR code: it holds the Wi-Fi password.

## Owner tools (come back any time)
Tap **Owner tools** at the top of the setup page (or open `http://<this computer>:8765/#tools`). You do not need a router or a coin box connected.
- **Check-up:** tap *Check now* for a read-only look at the router, the coin box, the website's update list and the print server (if you have one). Each is green or red, with a one-line fix. It changes nothing; use it first when a shop reports a problem.
- **Time cards (QR):** type the coin box's address (shown on its page), how many cards and how many hours on each, and tap *Make the cards*. It
  gives you printable pages (fronts, backs, both sides) that print from the browser at 100% on A4, and a ledger of every card number.
  Boxes you printed for before are remembered. Come back any time for another batch: numbers continue where they stopped.
- **Admin QR code:** shows the code that logs you in as super admin on every coin box.
- **Phone recovery code:** a short-lived code that removes PisoPhone from a phone's lock screen.
- **Your keys:** the card, admin and recovery keys live in your home folder (`pisophone_card_key.pem`, `pisophone-superadmin.key`,
  `pisophone_license_key.pem`). If you already have them, import them once (a file path or paste the text). A coin box trusts only the
  keys its firmware was built with, so a new key works only after the firmware is rebuilt with it.
- The first time, it may ask to *Install what is missing* (the `cryptography` package, into this program's own folder).

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
| The page does not open on the phone | The first page has a *My phone can't open this page* help. The phone and the setup computer must be on the same network (see above). Use the address printed in the terminal; a firewall may block port 8765 (`--port` changes it). |
| "No board found" / the board does not answer | Use a USB **data** cable. Hold the board's BOOT button, tap RESET, release BOOT, tap *Try again*. Linux: you must be in the `dialout` group (or run as root). Windows: install the CH340 / CP210x driver once. Close any serial monitor that uses the port. On Ubuntu / Parrot desktops ModemManager can grab the port: `sudo systemctl stop ModemManager`. |
| "No router found" | Cable in a **LAN** port (not WAN), router on for 2 minutes after the reset. The page keeps looking every few seconds; after a fix it finds the router by itself. A router with another start address (for example 192.168.8.1) is found too, and the page names the address. |
| "This computer is on two networks that use the same addresses" | The cable is on the router and the Wi-Fi is on a modem that is also 192.168.1.x. Put the computer's Wi-Fi on your phone's hotspot (or turn it off), then press *Search again*. |
| "The router has a password ... Go back to the Router step" | A repeat after the password was set needs it: it is in `pisophone-router-password.txt` or the summary in the saved folder. *Try again* uses the saved one by itself. |
| "... installs packages with apk" | The router runs OpenWrt 25.x (or a snapshot), which this setup does not support yet. Use OpenWrt 23.05 or 24.10 (`docs/ROUTERS.md`). Nothing was changed on the router. |
| The Phones step says to plug the modem into the WAN port | A rental phone gets the app through the router, so the router needs internet. Plug the modem into the router's WAN port, wait a minute, tap *Check again*. |
| "The router has no internet, and the first setup needs it" / "The router could not download..." | The router needs internet for the first setup. Plug the modem into the router's WAN port, wait a minute until the modem is online, then tap *Try again*: nothing was changed on the router. |
| The coin box takes long to join, or never, while another PisoHub router is on | Another router nearby may answer the name a new box knows (`PisoCoinBox`). Switch it off for a few minutes while this one pairs, or run `piso-setup box-wifi-name` on it (see "Prefer to do it by hand?" in `MANUAL.md`). After pairing, this router gives its box a name of its own. |
| "The coin box did not join" | Is the box powered (the USB cable does it), near the router, and written by this program (it erases the board first)? Tap *Try again*: the installation continues where it stopped. |
| esptool or ssh "Missing" on the first page | Tap *Install what is missing*. Without root rights it shows the exact `sudo apt install ...` command to run. |
| The phone does not ask "Allow USB debugging?" | Unlock the phone, use a data cable, unplug and replug. Or use the on-phone setting. |
| PisoPhone only: "the board could not join the Wi-Fi" (the box says the password was wrong or the network was not found) | Type the Wi-Fi details again and tap *Try again*: a box that was set up over USB but has never joined (and has no payment) takes new Wi-Fi details **without an erase** (coin box software 3.12.5 or newer; an older box still needs the ERASE route once). A box that has joined once refuses, as before. |
| Anything else | Tap *Try again*: it is always safe, and the router keeps what is already done. If it keeps failing, send the file `pisophone-setup-log-<date>-<time>.txt` from the saved folder (the error names it). It holds the lines the page showed and this computer's network addresses; the unit's passwords are hidden. |

## After the setup
- **Another phone:** run the program (or open the coin box's page at `http://10.0.0.10`) and use *Set up a phone* for the next slot.
- **New software** for the router: `python3 pisophone_setup.py --update`.
- **Check on the router at any time:** `ssh root@10.0.0.1`, then `piso-setup status`. More commands: [`MANUAL.md`](MANUAL.md), "Day to day".

## Prefer to do it by hand?
In the terminal the same choice is `--product wifi|phone|both` (asked when missing; `--yes` needs it). [`MANUAL.md`](MANUAL.md) has every step, the router commands, the command-line version of the program (`--cli`) and the repair tools.
