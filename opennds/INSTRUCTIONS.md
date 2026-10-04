# Router setup instructions

There is one complete guide (shell) plus a portal variant that builds on it. Each starts from a **factory-reset router** and ends with a working coin-operated
PisoWiFi portal. Follow the shell guide; the flash guide is an optional add-on to it.

| guide | coin-slot manager | when to use it |
|---|---|---|
| [`INSTRUCTIONS-SHELL.md`](INSTRUCTIONS-SHELL.md) | `coinslot-listener.sh` (shell scripts) | **The supported edition.** Works on any OpenWrt router. Use this unless you want to try the fast one. |
| [`INSTRUCTIONS-FLASH.md`](INSTRUCTIONS-FLASH.md) | `coinslot-listener.sh` + the green **flash coin** portal | The simplest portal: your voucher-theme structure, with the coin counter only verifying payments. A short add-on to the shell guide (same router and box setup). |

Both need the coin box set up first; the box's starting password is `Coinslot@Setup` and it accepts no
coins until you change it (Part 2 of the shell guide).
