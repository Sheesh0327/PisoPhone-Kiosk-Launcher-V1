# OpenNDS coin-slot integration

Customers on your Wi-Fi pay with coins at the PisoPhone box to get internet time. OpenNDS shows the
portal; the box's coin slot is reached through the gateway API (`docs/overhaul/gateway-coinslot-api.md`).

```
phone browser --> openNDS portal --> theme_coinslot.sh --(127.0.0.1)--> coinslot-listener.sh --(LAN)--> ESP32 box
                      |  libopennds.sh does the portal plumbing                                   (coin slot)
                      +--> ndsctl auth  (grants the minutes the coins paid for)
```

## Files
| file | what it is |
|---|---|
| `theme_coinslot.sh` | the ThemeSpec (page sequence); everything else is done by openNDS' own `libopennds.sh` |
| `coinslot-listener.sh` | coin-slot manager: a small local listener (socat) plus a background worker that holds each customer's coin window |
| `coinslot.conf` | reference copy of the settings file (INSTRUCTIONS.md writes the real one to /etc/coinslot.conf) |
| `coinslot.init` | OpenWrt service script |
| `INSTRUCTIONS.md` | step-by-step copy/paste setup (there is no installer script) |
| `tests/` | end-to-end test with a fake box (`python3 opennds/tests/test_flow.py`) |

## Install (OpenWrt with openNDS)
Follow `INSTRUCTIONS.md`: every step is a copy/paste command.

## What the customer sees
1. **Welcome**: rate ("1 coin = 10 minutes") and **Insert coin**.
2. **Insert coin(s) now**: live count and countdown (page reloads itself every 2 s). **Connect now** or let the
   window end.
3. **Thank you**: "2 coin(s) = 20 minutes" and **Connect**. Pressing it grants exactly that session length.
4. If nobody pays within the window: "No coins were detected", nothing is granted.
5. If someone else is paying: "The coin slot is busy", the page retries every 5 s (one physical slot, one customer at a time).

## Safety properties
- The minutes are decided on the router by the listener from coins the box counted, never taken from the browser.
- Coins are acknowledged on the box only after openNDS confirmed the client is authenticated. If anything fails
  before that, the coins stay claimable ("Your coins are still safe, Try again"), also after a page reload.
- The slot is always released: on window end, Connect, worker kill or a router hiccup (3 retries); the box also caps
  every session at 120 s by itself.
- The listener binds to 127.0.0.1 only and validates every session id; the box key lives in `/etc/coinslot.conf` (mode 600).
- The session id is a hash of the client's private openNDS id, so another client cannot claim your coins.

## Limits and things to know
- Written against openNDS' current `libopennds.sh` (functions used: `auth_log`, `configure_log_location`, the
  `header`/`footer`/`landing_page`/`display_terms` hooks, `$hid`, `$fas`, `$client_zone`). It was tested here with those
  helpers stubbed, not on a live router: do a first real run with one phone and watch `logread -e opennds -e coinslot`.
- One customer pays at a time. A customer who closes the page mid-window keeps their counted coins for ~2 hours
  (state is in RAM; a router reboot loses the record, and the coins then stay on the box until it discards them after 24 h).
- Coins that arrive on the box while a phone rental session holds the slot belong to that phone, not to Wi-Fi.
- Wi-Fi time uses openNDS' `sessiontimeout` (minutes). Rate/quota limits can be added in `landing_page()` if you want them.
