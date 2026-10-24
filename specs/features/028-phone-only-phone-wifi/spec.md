# Feature 028: a PisoPhone-only box hands new phones the shop's Wi-Fi

Status: **urgent fix, owner asked 2026-10-24 ("apply the fix directly on main")**   Area: box firmware (3.12.4) and website setup page   Release: firmware, website

## Problem
In the full install the router creates the hidden PisoKiosk network and tells the box its name and password; the box puts them in its
"Set up a phone" link and the phone setup page builds the QR code (WPA, hidden) from them. PisoPhone only has no router: the phones join the
shop's own Wi-Fi, the one the box joined over USB. The box kept the phones' Wi-Fi settings empty in that mode, so the link carried no Wi-Fi,
the QR step failed (or used the name "PisoKiosk", which does not exist), and a phone set up another way stopped at
"PisoPhone refused to add <network>" and never found the box.

## Fix
- Box (PisoPhone-only builds only, `!PISO_HAS_GATEWAY`): when no phone Wi-Fi was given by a router, the "Set up a phone" link carries the box's own
  Wi-Fi name and password and `wifi_hidden=0`. A router-given password still wins; the full and PisoWiFi images are unchanged.
- Setup page: `wifi_hidden=0` makes the QR say the network is visible; anything else stays hidden (as before).

## Success criteria
1. The page tests: `wifi_hidden=0` gives a visible network in the QR, no value or any other value stays hidden.
2. The firmware builds for every environment and the PisoWiFi-only image carries no trace of phones.
3. Money, time and the box <-> phone contract are not touched. Hardware check RS52 (a PisoPhone-only box, a new phone through the QR) stays unticked.

## Not tested
Nothing was run on a box or phone. The box's own page builds the link from the stored Wi-Fi at the moment it is opened.
