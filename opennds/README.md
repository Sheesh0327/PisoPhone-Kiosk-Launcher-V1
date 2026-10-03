# OpenNDS coin-slot integration

Customers on your Wi-Fi pay with coins at the PisoPhone box to get internet time. OpenNDS shows the
portal; the box's coin slot is reached through the gateway API (`docs/api/gateway-coinslot.md`).

```
phone browser --> openNDS portal --> theme_coinslot.sh --(127.0.0.1)--> coinslot-listener.sh --(LAN)--> ESP32 box
                      |  libopennds.sh does the portal plumbing                                   (coin slot)
                      +--> ndsctl auth  (grants the minutes the coins paid for)
```

## Files
| file | what it is |
|---|---|
| `theme_coinslot.sh` | the ThemeSpec (page sequence); everything else is done by openNDS' own `libopennds.sh` |
| `coinslot-listener.sh` | coin-slot manager: local listener (socat), coin window worker, rates, top-up, vouchers, fair-use watcher, revenue report |
| `coinslot.conf` | reference list of every setting (the package keeps them in UCI, `/etc/config/coinslot`; the old `/etc/coinslot.conf` still works) |
| `coinslot.init` | OpenWrt service script |
| `package/` | builds the `opennds-coinslot` .ipk (`build_ipk.py`) and holds the default UCI config |
| `layout_b.sh` | prints the `uci` commands for the guest/kiosk split network (Layout B) |
| `INSTRUCTIONS.md` | step-by-step copy/paste setup (there is no installer script) |
| `tests/` | end-to-end test with a fake box (`test_flow.py`), plus `test_uci_config.py`, `test_ipk.py`, `test_layout_b.py` |

## Install (OpenWrt with openNDS)
Follow `INSTRUCTIONS.md`: every step is a copy/paste command.

## What the customer sees
Everything is one small page (about 4 KB, inline CSS, no images or downloads), bilingual English/Tagalog.
1. **Welcome**: rates for both plans on top, an **Insert Coin** button in the middle. The customer picks a plan first.
   - **HyperSpeed**: no speed limit. 5 pesos = 30 min, 10 = 1 hr, 20 = 2 hrs (1-4 pesos at 6 min each). Slowed
     intermittently after 5 GB (fair use).
   - **Endurance**: capped at 5 Mbit/s down and 2 Mbit/s up, with openNDS' native bursting on (no cap until a
     client's speed stays above its limit for about 30 s), so short bursts never feel the cap. 1 peso = 15 min, 5 = 3 hrs, 10 = 8 hrs, 20 = 24 hrs. A session that
     paid 10 pesos or more can be **paused once** (status page): the remaining time is frozen for up to 72 hours and
     restored with **Resume** on this device or with the voucher code.
   - Coins add up: the best combination of tiers is used, e.g. Endurance 17 pesos = 10 + 5 + 1 + 1 = 11 hrs 30 min.
2. **Insert coin(s) now**: running pesos and time earned, a countdown bar that restarts with every coin
   (30 s to start, 15 s after each coin, 115 s at most), **Connect now**. The page updates itself and plays a short
   coin "ding" for every coin (Web Audio, nothing to download). Browsers only allow sound after a tap on the same
   page, so the **Insert Coin** tap itself unlocks it: the script starts the coin window without leaving the page
   and the waiting view replaces the welcome view. Without scripts it submits normally and the waiting page shows a
   small "Tap for coin sound" button; a connect jingle plays when the browser allows it.
3. **Thank you**: the time earned, **Connect**. Then a **voucher code** to restore the time on any device.
4. **Status page** (a connected customer opening the portal address): live time left, plan, data used, voucher code and
   **Add time**. Coins added while connected extend the session (same plan); a different plan is refused until the
   current time ends, so the speed rules never mix.
5. **Voucher**: enter a code on any device to continue; the time moves to that device. A returning device that still
   has paid time is offered it on the welcome page.
6. If nobody pays: "No coins detected", nothing is granted. If the slot is in use: "Coin slot is busy" and the page retries.
   If the manager or box is down: a friendly offline notice.

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
- Bursting is openNDS' own (`download_unrestricted_bursting`, window = `ratecheckwindow` x `checkinterval`), read from its
  source but not run on your router. Pause and plan switching re-grant time with deauth then auth (a brief reconnect).
- Time uses openNDS' `sessiontimeout`; Endurance caps use its per-client rate limits.
- Top-up and fair-use throttling re-grant time with `ndsctl deauth` then `ndsctl auth` (openNDS only applies new limits
  to a de-authenticated client), so the client reconnects for a moment. Both rely on this openNDS behaviour, which was
  read from its source but has not been run on your router: check them first on a real phone.
- HyperSpeed fair use is judged per session by the router's traffic counters (every minute); the slowdown is
  5 minutes on, 2 minutes off, until the paid time ends.
- Phones that randomise their MAC address are not recognised as returning devices; the voucher code covers that.
