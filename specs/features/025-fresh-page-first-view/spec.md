# Feature 025: a fresh page never sees the last result first

Status: **approved (owner asked for this task in the session, 2026-10-24)**   Branch: `feat/025-fresh-page-first-view`   Area: router
Backlog rows: found by CI and by local runs of `tools/pisoportal/tests/test_flow.py` (check "a fresh page's first view is its own, not the last result", failed 3 times, passed on re-run)   Release: router 1.5.21

## Intent
A customer's page on the PisoWiFi portal opens a WebSocket and then says `hello`, with `reset: true` when it wants a fresh start. The portal
must not show that page the previous window's result (the `final` screen: pulses and minutes of a window that is over) before the reset is applied.
Today, now and then, it does. Serves rule 1 as far as the screen goes: the customer is never shown a result that is not theirs to see now.

## Cause (read from the code, proven by the test below)
In `tools/pisoportal/src/http.rs` (about lines 400 to 420) the thread that sends a page its view is **started before** the page's `hello` is
read (about line 448). That thread wakes whenever the portal's state version changes and sends `view_for(mac)`. If anything changes the version
in the gap between the handshake and the `hello` (another customer's coin, a window ending, a maintenance tick), it sends the stale
`final` first; the `hello` with `reset` is applied a moment later and then the right view follows. A page that said `reset` therefore
sometimes shows a flash of the old result, and the test that checks "the first view is its own" fails about once in several runs.

## Invariants touched
- Money and time: **not touched.** No credit, expiry, deduction, ledger or payment code changes; only *when a page is first sent a view*.
- Only owner-signed software: the router program changes, so it is released and signed the normal way (router 1.5.21).

## Constraints
- A page that never sends `hello` is still answered as today within one wake-up of its first state change (the ping and idle behaviour do not change).
- A page that does say `hello` gets its first view from the `hello` handler, after `clear_result` when `reset` is true, exactly as now.
- After the `hello`, changes reach the page as fast as today (no extra delay).
- The router program uses only `sha2`, `hmac` and `p256`; no new thread.

## Success criteria
1. **A deterministic test fails first.** With a result waiting for the address, a page connects (handshake done), the portal state changes
   (another customer's page connects) and *then* the page sends `hello` with `reset`: today the page's first message is the old `final`.
   The test requires that the page receives **nothing before its `hello`** and that its first message after it is `idle`. (red result logged)
2. The fix: the view-sending thread sends nothing until the `hello` has been handled (the pings still go out), and nothing is missed after it.
3. `test_flow.py` passes 202/202 **ten times in a row**; `test_console.py`, `cargo test`, clippy, rustfmt and the router setup tests pass.
4. `setup/RELEASE` is raised to 1.5.21, generated files rebuilt (`tools/build_piso_setup.py --check`), `CHANGELOG.md` entry, `validation.md`.

## Out of scope
The page's own script (`page.html`), the console, the box view, the firmware.

## Owner decisions
None open. This sits on top of feature 022 (PR #115) because both raise the router release: it ships as 1.5.21 after 1.5.20.
