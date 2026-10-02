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
| `POST /api/gateway/arm` | reserve the slot and power the coin acceptor | `duration` seconds (5-120, default 60) | status object |
| `GET /api/gateway/status` | state and coins received so far | | status object |
| `POST /api/gateway/release` | stop accepting (in-flight coins are still counted) | | status object |
| `POST /api/gateway/ack` | the coins were used; remove them from the box | | `{"acknowledged_pulses": n}` |

Status object: `{"success":true,"session":"..","state":"armed|draining|idle","armed_remaining":s,"pulses":n,"minutes_per_coin":m}`.
`pulses` is the coins received for that session and not yet acknowledged (1 pulse = 1 coin); multiply by
`minutes_per_coin` for time.

Errors: `403 AUTH_FAILED` (bad signature, used or expired nonce), `409 SLOT_BUSY` (a phone, controller or
another gateway session holds the slot), `503 STORAGE_UNAVAILABLE`, `503 GATEWAY_DISABLED`, `400 INVALID_SESSION`.

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

## Router scripts (OpenWrt / busybox, no Python)
`scripts/gateway_pay.sh` is the `run` flow in POSIX sh (needs `curl` or `wget`, and `openssl`):

    export GW_BOX=192.168.1.50 GW_KEY=<gateway key>
    sh gateway_pay.sh <session> [seconds] [--ack]      # prints:  PULSES=2 MINUTES=12   (or ERROR=<code>)

It arms, counts until the timeout, always disarms (also on Ctrl+C, kill or a closed connection), waits for
in-flight coins and prints one result line. If it is interrupted, the coins already received stay queued on
the box until a later `ack`.

`scripts/gateway_socat_handler.sh` wraps it as a tiny local HTTP service for OpenNDS (copy both scripts to
the router, `opkg install socat openssl-util curl`):

    socat TCP-LISTEN:8099,bind=127.0.0.1,reuseaddr,fork EXEC:/root/gateway_socat_handler.sh
    curl -s "http://127.0.0.1:8099/pay?session=<client-mac>&duration=60"
    -> {"pulses":2,"minutes":12}      (409 SLOT_BUSY, 400 INVALID_SESSION, 502 on any other error)

The handler acknowledges the coins itself, so each pulse is reported once. Keep it bound to localhost (or the
LAN side): it has no login of its own. The session id is checked against the box's alphabet before it reaches
any command line.
