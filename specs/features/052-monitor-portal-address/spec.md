# Feature 052: the monitor asks the portal where the portal really listens

Status: **done on a computer 2026-10-30; applied directly to `main` at the owner's request (a small patch to a shell script, no router program change); waits for the owner to raise router 1.5.32 above 0%**   Area: router (`router/piso_monitor.sh`)   Release: router 1.5.32

## Intent
Owners saw "the portal program did not answer for N minutes and was restarted" again and again, with N growing without end. The portal was fine.
The installer binds the portal to the guest network's address only (`PORTAL_BIND='10.0.x.1'` in `/etc/coinslot.conf`), and the monitor
pinged `127.0.0.1`, where nothing listens. Every ping failed; after three the monitor restarted a healthy portal (at most once in ten minutes),
and since a restart cannot cure a wrong address, the count of failed minutes never reset. The monitor's tests bound the portal to every address
and set `PING_URL` themselves, so they could not see it.

## Invariants touched
- Money and time: **not touched** (no portal program change; `core.rs`, `roll.rs`, `ledger.rs`, `boxlink.rs` unchanged). The wrong restarts did not
  lose money (a coin window that was open is settled from its record) but could drop a paying customer's page.
- Only owner-signed software: kept (the monitor travels inside the signed setup file; the release is signed by CI as always).

## Success criteria
1. With the portal listening only on the guest address, the monitor's default check asks that address, the portal counts as healthy, and nothing is restarted.
2. A setup with no `PORTAL_BIND`, one bound to every address (`0.0.0.0`) or to `127.0.0.1` is still asked on `127.0.0.1`.
3. A portal that really hangs is restarted as before (not on two failed pings, once on the third), with the alert and the `alerts.txt` line.
4. After a restart the count of failed minutes starts again, so the alert says how long it has been failing since the last restart, never an ever larger number.
5. Nothing else in the monitor or the installer changes; the programs inside the setup files are byte for byte the committed ones.

## Out of scope
Making the portal also listen on the loopback address; changing how often or how many times the monitor restarts a hung portal.
