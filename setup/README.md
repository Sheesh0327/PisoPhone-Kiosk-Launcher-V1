# Set up PisoPhone

One program does the whole setup, from the bare boards to phones that are working: **`pisophone_setup.py`**. It guides you
through every step in a window, asks only for the shop's name and Wi-Fi name, and does the rest by itself: it writes the
coin box over USB, sets up the router and pairs them. About 15 minutes a unit, and one click for the next.

## What you need
- The **ESP32 coin box board** and a USB data cable (a charge-only cable does not work)
- An **OpenWrt router** (for example the 360T6M), factory reset
- A **modem** with internet, and a network cable from it to the router's **WAN** port
- A network cable from this computer to one of the router's **LAN** ports
- **Google Chrome or Microsoft Edge** on this computer (for the phone setup page)
- The **rental phones**, factory reset

## Start it
1. Download **[pisophone_setup.py](https://pisophone.pages.dev/pisophone_setup.py)**.
2. Open it:
   - **Windows:** double-click it. (If nothing happens, install Python from python.org, tick "Add to PATH" and "tcl/tk", and try
     again.)
   - **macOS:** open Terminal, type `python3 ` (with a space), drag the file in, press Enter.
   - **Linux:** `sudo apt install python3-tk openssh-client`, then `python3 pisophone_setup.py`.
   - **All:** the program writes the coin box with Espressif's tool, so run `python3 -m pip install esptool` once (Windows:
     `py -m pip install esptool`). The ready-made program (a single file, when you have one) has it built in. On Linux add
     yourself to the `dialout` group (`sudo usermod -aG dialout $USER`, then log in again); on Windows a CH340 or CP210x board may
     need its USB driver installed once.

The first page checks this computer (the `ssh` program, the internet, the flashing tool, Chrome or Edge) and tells you what
to fix if anything is missing. Then follow the steps in the window:

| Step | What you do | What the program does |
|---|---|---|
| **1 Start** | Read what you need | Checks this computer |
| **2 Router** | Reset the router, plug in the modem and the cable | Finds the router by itself |
| **3 Settings** | Type this customer's shop name and public Wi-Fi name (asked every time); keep the passwords it made (new ones for every unit), or type your own | Checks every entry |
| **4 Install** | Plug the ESP32 in by USB (a data cable), click *Start the installation*, wait about 10 minutes | Writes the PisoPhone software to the box (erasing it first), installs the router software, pairs the coin box, checks everything, saves your passwords and a printable sheet |
| **5 Phones** | Scan the QR code on the page that opens with each factory-reset phone | Opens the coin box's phone setup page, already filled in |
| **6 Finish** | Insert one coin; try the customer Wi-Fi with a phone; click *Set up the next unit* for the next router and box | Tests the coin and offers Telegram alerts; starts over for the next unit |

Keep the USB cable plugged in during the installation: it powers the coin box. If the box already has the PisoPhone software,
tick *Do not write it* on the install page.

Your passwords and the printable setup sheet are saved in **Documents/PisoPhone**, readable only by you. Keep them private.

## Setting up a phone
The program opens the phone setup page for slot 1 by itself. On the phone:
1. On its first welcome screen **tap the same spot 6 times**: a QR reader opens.
2. Click **Show the setup code** on the page and scan it. The phone joins the Wi-Fi, installs PisoPhone and locks itself as a kiosk.
3. When the phone shows **One last step**, do what it says. On most phones: tap *Open the setting* and switch on *Allow display over
   other apps*. On an **Android Go** phone (it has no such switch): plug it into this computer, tap *Allow* on "Allow USB
   debugging?" and click *Finish over USB* on the page.
4. The page then opens the coin box's page to pair the slot. Done: the phone is a kiosk.

For the next phone, choose the next slot in the program (or on the coin box's page) and open the setup page again.

## If something goes wrong
| What you see | What to do |
|---|---|
| "No board found" / the board does not answer | Use a USB **data** cable. Hold the board's BOOT button, tap RESET, release BOOT, click *Try again*. Linux: you must be in the `dialout` group. Windows: install the CH340 / CP210x driver once. Close any serial monitor that uses the port. |
| "No router found" | Cable in a **LAN** port (not WAN), router on for 2 minutes after the reset, this computer's Wi-Fi off. The program keeps looking every few seconds; after a fix it finds the router by itself. |
| "The router could not download..." | The modem must be in the router's WAN port and have internet. |
| "The coin box did not join" | Is the box powered (the USB cable does it), near the router, and written by this program (it erases the board first)? Click *Try again*: the installation continues where it stopped. |
| The phone does not ask "Allow USB debugging?" | Unlock the phone, use a data cable, unplug and replug. Or use the on-phone setting. |
| Anything else | Click *Try again*: it is always safe, and the router keeps what is already done. |

## After the setup
- **Another phone:** run the program (or open the coin box's page at `http://10.0.0.10`) and use *Set up a phone* for the next slot.
- **New software** for the router: `python3 pisophone_setup.py --update`.
- **Check on the router at any time:** `ssh root@10.0.0.1`, then `piso-setup status`. More commands: [`MANUAL.md`](MANUAL.md), "Day to day".

## Prefer to do it by hand?
[`MANUAL.md`](MANUAL.md) has every step, the router commands, the command-line version of the program and the repair tools.
