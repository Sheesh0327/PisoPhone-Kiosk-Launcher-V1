# Feature 022: quick add / subtract time on the box's admin page

Status: **draft**   Branch: `feat/022-quick-adjust-fix`   Area: firmware
Backlog rows: owner bug report 2026-10-24 (no spec row)   Release: firmware (3.12.2)

## Intent
On the box's admin page (the dashboard) the **Quick time adjust** panel has a phone chooser, a minutes box and the *Add* and
*Subtract* buttons. The owner presses one and the browser shows "This page can't be reached" instead of the dashboard, and the
owner cannot tell whether the phone's time changed. The owner wants the buttons to work, answer straight away, and say truthfully
what happened. Serves rule 1: time is never wrong, and the owner is never told a change happened when it did not.

## What I know (read from the code, not run)
- The form posts to `/add_time` (`esp32_firmware/include/WebDashboardTemplate.h` lines 86 to 110). `handleAddTime`
  (`esp32_firmware/src/WebServerApi.cpp`) checks the login, refuses an unpaired phone, then calls `sendAddTime`
  (`esp32_firmware/src/DeviceNetwork.cpp` line 27) **inside the web request**. For each phone `sendAuthenticated` makes two calls
  (a challenge, then the signed `/add_time`), each with a 1000 ms limit. With ALL, that is up to about 2 s per phone, one phone
  after another, while the box serves nothing else. After that the handler answers `303` to `/`.
- Whatever the cause, the message "Changed time by +N min" is set **before** anyone knows the phone answered, so today it can
  say "Changed" for a phone that was never reached.
- The *player account* buttons (`+ Time`, `- Time` on a QR card, `/api/accounts/adjust`) are a different path and are not part of
  this report.
- **The cause is not known.** Three hypotheses, in the order I would test them: (H1) the request is held so long, with phones slow
  or off, that the browser gives up or the box's own watchdog restarts it, so the page is gone; (H2) the box restarts inside the
  handler (a crash); (H3) the reply sends the browser somewhere it cannot reach. `Location: /` is relative, which argues against
  H3. Group 1 must tell these apart before any fix is written, and if the cause is not H1 this spec is changed first.

## Invariants touched
- Money and time: an owner adjustment is a time change on a phone with `amount=0` and its own transaction id (`tx-adj-...`). Kept:
  one id per press, the phone credits that id once (a repeated send never adds twice), and an adjustment still goes only to a
  paired, active slot. Subtract never needs to go below zero: the phone's own clamp is unchanged. **Not touched:** payments, the
  coin queue, expiry.
- Only owner-signed software: not touched. The signed phone call (challenge, v_sig) is unchanged.

## Constraints
- The box <-> phone message does not change.
- The dashboard page is generated (`WebAssets.h`, `python3 scripts/embed_web.py`); never edit it by hand.
- A press must never block the box's other work (coin pulses, the other pages) for more than a moment.
- Firmware code needs a higher `PISO_FW_VERSION` or boxes ignore it.

## Success criteria
1. **Pressing Add or Subtract answers the dashboard within 2 seconds** with 8 paired phones, any of them off or slow (tested with a
   pure function for the worst-case time the request is held; red first: today it is 8 x 2 s, 16 s).
2. **The adjustment is sent after the answer**, one phone at a time, through the box's existing background worker, each with its
   own transaction id.
3. **The page tells the truth.** Right away: "Sending +N min to ... ". On the next load of the dashboard: per phone "done", or
   "the phone did not answer, nothing changed" (never "Changed" for a phone not reached). A phone that did not answer is
   not retried silently: the owner presses again (a fresh press makes a fresh id).
4. A press for an unpaired phone still says "Not changed" and sends nothing (as today).
5. Host tests for the pure parts pass with the sanitizers; the firmware compiles in CI for all four boards.
6. **Hardware check RS47** is added to `docs/REAL_WORLD_TESTING.md` (one phone, ALL, a phone switched off, the box restarting
   must not happen) and stays unticked until run.

## Out of scope
The QR card `+ Time` / `- Time` buttons, the phone side, any new setting, undoing an adjustment.

## Owner decisions (please answer; the answers decide H1, H2 or H3)
1. Which firmware is the box on, and did these buttons ever work? (the Check-up or the box's footer shows the version)
2. Does it fail for **one phone**, for **ALL**, or both? How many phones are paired, and are all switched on?
3. What exactly does the browser say (for example `ERR_CONNECTION_TIMED_OUT`, `ERR_CONNECTION_RESET`, `refused`), and what
   address is in the address bar after pressing? If you can, the box's serial log (or Diagnostics page) around a press.
4. Is it fine that the page says "Sending..." first and shows the result on the next load, instead of waiting for the phone?
