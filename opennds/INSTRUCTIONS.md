# Router setup instructions

There are two complete, separate guides. Each starts from a **factory-reset router** and ends with a working coin-operated
PisoWiFi portal. Pick one and follow only that one.

| guide | coin-slot manager | when to use it |
|---|---|---|
| [`INSTRUCTIONS-SHELL.md`](INSTRUCTIONS-SHELL.md) | `coinslot-listener.sh` (shell scripts) | **The supported edition.** Works on any OpenWrt router. Use this unless you want to try the fast one. |
| [`INSTRUCTIONS-MICROPYTHON.md`](INSTRUCTIONS-MICROPYTHON.md) | `coinslot-fast.sh` (one resident MicroPython process) | **Experimental.** Faster and pushes live updates at once, but not yet proven on real hardware. Needs `micropython` on the router. |

Both editions use the same portal pages, settings (`/etc/coinslot.conf`), vouchers and revenue files, so you can move from one to
the other without losing data. Both need the coin box set up first; the box's starting password is `Coinslot@Setup` and it accepts no
coins until you change it (Part 2 of each guide).
