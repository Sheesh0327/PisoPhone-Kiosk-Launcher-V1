# Network coin-slot gateway (OpenNDS and similar)

Lets a router (or any trusted device on the LAN) use the universal coin slot to verify a payment,
without involving a PisoPhone. It is **off until a key is set** and does not change phone behaviour.

## Setup
1. Choose a long random key (at least 16 characters), e.g. `openssl rand -hex 24`.
2. Store it on the box (admin login required, the superadmin login also works):
   `curl -u admin:<password> "http://<box>/api/gateway/config" --data-urlencode "key=<key>"`
   Clear it again (disables the gateway): same call with `--data-urlencode "key="`.
3. Put the same key on the router. Anyone holding it can arm the slot, so treat it like a password.

## Calls
Every call is preceded by `GET /api/gateway/challenge` returning `{"nonce":"<32 hex>"}`. The nonce is
valid for one request and 30 seconds. Sign each request:

    sig = HMAC-SHA256(key, "gw1:<action>:<session>:<nonce>")      # lowercase hex

`session` is chosen by the gateway (client MAC, token, ...): 1-64 characters of `A-Z a-z 0-9 - _ . :`.

| call | purpose | extra params | answer |
|---|---|---|---|
| `POST /api/gateway/arm` | reserve the slot and power the coin acceptor | `duration` seconds (5-120, default 60); optional `wid` + `evport` (coin events, below) | status object |
| `GET /api/gateway/status` | state and coins received so far | | status object |
| `POST /api/gateway/release` | stop accepting (in-flight coins are still counted) | | status object |
| `POST /api/gateway/ack` | the coins were used; remove them from the box | | `{"acknowledged_pulses": n}` |

Status object: `{"success":true,"session":"..","state":"armed|draining|idle","armed_remaining":s,"pulses":n,"minutes_per_coin":m,"ready_in_ms":ms,"slot_free":true|false,"lifetime_pulses":n}`. `lifetime_pulses` is the box's own total of coins counted since its last factory reset (phone and gateway coins alike); the router compares it with its revenue ledger (`coinslot-listener.sh reconcile`). `ready_in_ms` is how long the coin acceptor is still settling after it was powered on: the box ignores coin pulses until it reaches 0 (a power-on surge can produce a stray pulse), so a gateway must not invite the customer to insert coins before then. The phone arm answer carries the same value as `settle_ms`. `slot_free` is true when nobody holds the coin slot, so a gateway can tell a waiting customer the moment it is available.
`pulses` is the coins received for that session and not yet acknowledged (1 pulse = 1 coin); multiply by
`minutes_per_coin` for time.

Errors: `403 AUTH_FAILED` (bad signature, used or expired nonce), `409 SLOT_BUSY` (a phone, controller or
another gateway session holds the slot), `503 STORAGE_UNAVAILABLE`, `503 GATEWAY_DISABLED`, `400 INVALID_SESSION`,
`400 INVALID_EVENT_TARGET` (`wid`/`evport` given but not both valid; nothing was armed), `503 ACK_INCOMPLETE` (some
coins could not be removed from flash: retry the ack; until it succeeds those coins are still counted for the session).

## Coin events (firmware 3.2.0 and later)
So the router does not have to poll, the box can push every change of a window. Add `wid=<window id, lower-case hex,
up to 40>` and `evport=<udp port>` to `arm`; the status object then carries `"events":true`, and the box sends one UDP
line (twice, against Wi-Fi loss) to the address the arm request came from, at that port:

    gw1ev:<session>:<wid>:<seq>:<type>:<pulses>:<sig>        type = ready | coin | end
    sig = HMAC-SHA256(key, "gw1ev:<session>:<wid>:<seq>:<type>:<pulses>")

`ready` once the acceptor has settled, `coin` the moment a coin is counted (about 0.3 s after its last pulse), `end`
after the slot was released and drained. `pulses` is the window's running total, never a delta, so a lost or repeated
line does no harm; `seq` only orders them. The `wid` ties a line to one window: an old line cannot be replayed into a
later window. Re-arming the same window (same `wid`) keeps its sequence. `status`, `release` and `ack` stay the
authority for acknowledging coins; the router keeps one signed `status` a second as a safety net.

## Typical flow for one customer
1. `arm` with the client's id, show the customer "insert coin".
2. Poll `status` every second or two until `pulses` > 0 (or the customer cancels).
3. `release`, then poll `status` until `state` is `idle` (a coin inserted at the last moment is still counted).
4. Grant access based on `pulses`, then `ack`. Unacknowledged coins stay on the box (and survive a reboot)
   so a router crash cannot lose a payment; they are discarded after 24 hours.

A session lasts at most 120 seconds in total, and re-arming the same session keeps its coins.
Test from a PC with `scripts/gateway_client.py`. Its `run` action does the whole flow: it arms, stays open
counting coins until `--duration` seconds pass (or Ctrl+C), always releases the slot (retrying if a request
fails), waits for in-flight coins, prints the total and, with `--ack`, clears them from the box. Shell equivalent of the signing step:
`printf 'gw1:arm:%s:%s' "$SESSION" "$NONCE" | openssl dgst -sha256 -hmac "$KEY" | awk '{print $NF}'`.

## Notes
- While a gateway session holds the slot, phones see "slot busy" (there is one physical coin slot).
- Coins received this way count in the box's revenue totals like any other coin.
- The box speaks plain HTTP on the LAN. The nonce scheme prevents replay, but anyone who can read the
  traffic can see sessions and coin counts; keep the gateway link on the private network.
- Hardware test: set a key, `arm` with the script, insert a coin, `status` shows `pulses: 1`, `release`,
  `ack` returns 1, `status` shows 0. Then press Ready for coin on a phone: it must still arm normally.

## Router (OpenWrt)
`tools/pisoportal` (`src/core.rs`, `src/boxlink.rs`) is the production client of this API: it arms, counts coins (extending the wait after each),
always releases, waits for in-flight coins, records the window once (roll and revenue ledger) and only then acknowledges it, so a
restart at any point neither loses nor doubles a coin; access is granted from the record. While a window is open the box keeps
one queued record for it, its running total, however many coins go in. See `tools/pisoportal/README.md`.
For experiments from a PC use `scripts/gateway_client.py`.

## Accounts: QR cards (phone <-> box)

A player's account is a **QR card** you sell. Their unused time stays on the box, so it is not lost when they leave. The box
holds the only copy (flash partition `spiffs`, see `esp32_firmware/include/Accounts.h`); the phone keeps nothing but the number
and name of the player who is signed in. The message format is `esp32_firmware/include/AccountProtocol.h` (box) and
`app/.../network/Esp32AccountRequests.kt` (phone), both tested against `protocol/fixtures/box_phone_v1.json`.

### The card

`PISO1.<box>.<serial>.<seconds>.<signature>`: the box's MAC (12 hex digits), the card number (1 to 65535, the account id), the
starter time in seconds (10800 = 3 hours) and an ECDSA P-256 signature over `pisophone-card-v1|<box>|<serial>|<seconds>`. The
format is `scripts/card_format.py`; `esp32_firmware/include/CardCodec.h` reads it.

- **Printing:** `scripts/make_card_key.py` makes the card key (not the firmware owner key) and writes its public half to
  `CardPubKey.h` (commit it, rebuild, flash). `scripts/make_cards.py --box <MAC> --count N` prints A4 sheets of 12 cards for back-to-back printing
  (`fronts.pdf` with the QR, `backs.pdf` with the logo as a mirror image so each logo lands behind its QR when the paper is turned
  over, and `duplex_both_sides.pdf` for printers with automatic two-sided printing; print on card stock, since a dark back can
  show through thin paper) and keeps a ledger so a serial is never reused. The dashboard shows the box ID to use. Until a card key is built in, every card is refused.
- **Guests are unaffected:** nobody needs a card. A guest inserts coins on the lock screen exactly as before; the card is only
  an extra, a place where time is saved. A signed-in player's coins add to their account the same way a guest's coins add to the
  phone's timer.
- **One box:** a card works only on the box whose MAC is in it.
- **First scan:** creates the account holding the starter time and asks the player for a name. Later scans only sign in.
- **Never twice:** the box keeps a permanent "redeemed" mark per card number (a bitmap saved with the accounts). It outlives the
  account: pruning an unused account, or an admin deleting one, never lets its card claim the starter time again; scanning it
  afterwards gives an empty account.
- **The card is the key.** Anyone holding the card, or a photo of it, can use its time, and a lost card is lost time. Only one phone
  can be signed in with a card at a time.

### Calls

Every call comes from a paired, active phone and is signed with the box secret: `sig = HMAC-SHA256(secret,
"v1:acct_<op>:<deviceId>:<ts>:<bound>")`. `<bound>` holds every parameter that matters, so a captured request cannot be edited.

| op | URL | bound | answer |
|---|---|---|---|
| scan | `/api/account/scan?device_id&card&ts&sig` | the card text | `{success, id, name, balance_sec, bonus_sec}` |
| name | `/api/account/name?device_id&id&name&ts&sig` | `<id>:<name>` | same |
| signout | `/api/account/signout?device_id&id&time&ts&sig` | `<id>:<time>` | same |
| info | `/api/account/info?device_id&id&ts&sig` | `<id>` | same (only on the phone the player is signed in on) |

- `bonus_sec` is the starter time this call just gave (only on a card's first scan, else 0). `name` is empty until chosen: 1 to 16 of
  letters, digits, space, `_`, `-`. `time` is the seconds the phone still has.
- Errors: `{success:false, error}` with `BAD_CARD`, `WRONG_BOX` (403), `CARDS_OFF` (503, no card key installed), `BAD_NAME`,
  `ACCOUNTS_FULL` (507), `NO_SUCH_ACCOUNT`, `ALREADY_SIGNED_IN` (409), `NOT_SIGNED_IN`, `TOO_FAST` (429), `CLOCK_UNKNOWN` (503, the box
  has no clock yet: retry in a few seconds), `SLOT_NOT_PAIRED`, `SLOT_EXPIRED`, `SETUP_REQUIRED`, `ACCOUNTS_OFF`, `AUTH_FAILED`
  (`reason` `STALE_TIMESTAMP` or `BAD_SIGNATURE`, with the box's time).
- The box allows 12 card checks per minute in total (a signature check takes a fraction of a second on the box).

### Heartbeat

While a player is signed in the phone adds `acct` (the card number), `atime` and `asig` to its heartbeat, where `asig` signs
`v1:acct_report:<deviceId>:<ts>:<acct>:<atime>` (the heartbeat's own signature does not cover these). The box lowers the balance to
`atime` if it is lower; **a report can never raise a balance**. The heartbeat answer carries `"acct":"<number or empty>"`, `acct_name`
and `acct_bal`: the box's view of who is signed in on that slot. A phone that is still signed in locally but sees `acct` empty twice in a
row has been signed out by the box and locks.

### Coins and silent phones

When the phone acknowledges a coin (`ack`), the box adds the coin's seconds to the account signed in on that phone, once per `tx_id`,
before the queued payment is erased. A phone that stops reporting for 90 s is signed out by the box; the account keeps the last
reported balance.

### Pruning, storage and limits

- An account with no time that is not signed in is deleted after 30 days without use, or after 7 days if it never had any time.
  Accounts with time are never deleted. Up to 600 accounts at once. Nothing is deleted while the box has no clock.
- Accounts are written to a temporary file, read back and checked, and only then moved into place, with the previous good file kept as
  `accounts.bak`; after a power cut the newest intact copy of the three is loaded. The filesystem is formatted only the first time it
  is used; if it later fails to mount, accounts are switched off and nothing is erased.
- `/api/diagnostics` has an `accounts` object (`ready`, `fs_mounted`, `count`, `unsaved`, `save_failures`, `failing_now`, `saves_ok`),
  and the dashboard warns while saves are failing and while no card key is installed.
