# Design proposal: live coin updates on the portal (for review, not built yet)

**Goal:** the coin amount and countdown update the moment a coin is detected, without the customer's phone reaching the box and
without the browser deciding who gets the time. Phase 1 (instant waiting screen on tap) is already in the theme.

## Today
coin -> box -> router worker polls the box **every 1 s** -> state file -> the page asks the router **every 2 s** (each ask re-runs the theme
script). A coin therefore shows after about 0.5-3 s.

## Phase 2: router pushes to the browser (Server-Sent Events)
- New endpoint on the router, e.g. `GET /events?sid=<sid>`, answered with `text/event-stream`. The worker's state changes (`pulses`,
  `minutes`, `remaining`, `state`) are written as events; the page updates its numbers from them and keeps today's 2 s poll as the fallback
  if the stream drops.
- Why SSE and not WebSocket: the data only flows router -> browser; SSE is plain HTTP, reconnects by itself and needs no handshake code in
  `socat`/`ash`.
- **Security rules (must hold before it is built):**
  1. The stream must be reachable by not-yet-authenticated customers, so one extra TCP port (e.g. 8100) is opened in the **guest zone input**
     firewall rule only. `layout_b.sh` and `INSTRUCTIONS.md` get the matching rule. The listener's existing `127.0.0.1:8099` API stays private.
  2. `sid` is already a hash of the client's secret openNDS id and cannot be guessed; the stream also checks that the request comes from the
     MAC that owns that `sid`, and answers nothing else (no other paths).
  3. At most one stream per `sid`, at most N (default 40) streams in total, an idle limit equal to the coin window (115 s), and no request
     body or query beyond `sid`. Over the limit: close and let the browser fall back to polling.
  4. Read-only: the stream can never start, finish or change a session.
- **Cost:** each open stream is one small process on the router for up to two minutes. Fine for dozens of simultaneous customers.

## Phase 3: the router hears coins from the box immediately
- Add a WebSocket path on the box for the gateway (router) only, e.g. `/api/gateway/events`, authenticated like the gateway API today:
  nonce from `/api/gateway/challenge`, signature with the gateway key (`GW_KEY`), one connection at a time. The box pushes `coin` and
  `session_ended` events; the worker keeps its 1 s poll as the fallback and as the reconciliation step (the box's payment queue and the
  acknowledgement flow are unchanged, so no coin can be lost or credited twice).
- Never the shared master key, never browser-supplied device addresses or tokens (the weaknesses of the pasted Gemini version).
- Needs a firmware change on the box and a `websocat`-style client or a small socat/openssl handshake on the router; check the package
  is available for your OpenWrt version first.

## Test plan
Router test with a fake box (existing `opennds/tests`): stream sends an event within 200 ms of a coin; falls back to polling when the
stream is closed; refuses a wrong MAC, an unknown `sid`, and the 41st connection. On hardware: coin to screen under 300 ms; 40 phones
connected; unplug the router's WAN and the portal still works.

## Decision needed
Phase 2 opens a port to unauthenticated Wi-Fi clients. Approve (or change) the four security rules above before it is built.
