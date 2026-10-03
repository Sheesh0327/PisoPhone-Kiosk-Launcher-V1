# Portal live updates: how they work

The portal feels instant because the router pushes changes to the customer's page instead of the page asking again and again.
Setup steps: `opennds/INSTRUCTIONS.md` ("Instant updates"). Everything here is optional: without it the portal still works with refreshes.

## What happens when someone taps Insert Coin
1. The page swaps to the waiting screen immediately (no server round trip) and the request that arms the slot runs in the background.
2. The router's worker for that one customer asks the box for coins every `COIN_POLL_SECONDS` (default 0.1; needs
   `coreutils-sleep`, otherwise 1 s). Nobody else polls; an idle router polls nothing. In practice the rate is bounded by one
   round trip to the box (challenge + signed status), and the box reports a coin only after its 280 ms pulse gap.
3. The page listens to a Server-Sent Events stream on the router (`STREAM_PORT`, default 8100). Each change of the worker's state
   is pushed: coin count, minutes, countdown, end of window. If the stream cannot be reached the page falls back to the old
   2 s refresh.

## A busy coin slot is pushed, never retried
- The page shows "slot is busy" and listens to its place in line. Nothing reloads.
- Customers wait in a first-come line (`$STATE_DIR/queue`). Only the first is checked against the box (`slot_free` in the gateway status).
- The moment the slot is free the first customer is pushed "ready" and has `QUEUE_CLAIM_SECONDS` (30) to tap Start; a newcomer cannot
  start ahead of the line. If they do not tap, the claim passes to the next. A closed page leaves the line within 6 s.
- A box with older firmware (no `slot_free`) makes the page fall back to retrying every 5 s.

## Security rules (all enforced in `coinslot-listener.sh stream-handle`)
1. Its own port, opened for the guest zone only (`layout_b.sh` prints the rule; the local `127.0.0.1:8099` API stays private).
2. A stream is only given to the device that started that session: the secret `sid` plus the MAC the router sees for the connecting address.
3. Bounded: `STREAM_MAX_CLIENTS` connections at once (socat `max-children`), `STREAM_MAX_SECONDS` each, nothing but `/stream` is answered.
4. Read-only: it reports a session's own state or place in line; it never arms, grants, finishes or changes anything.

## Not built (deliberately)
- A push connection from the box itself (WebSocket/TCP): it would save only the poll round trip and costs firmware work on the ESP32.
- Pushing to the customer's page from the browser's side of the box: the customer's phone never talks to the box.
