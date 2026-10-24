# Feature 022: quick add / subtract time on the box's admin page

Status: **draft (corrected after the owner's answers, 2026-10-24)**   Branch: `feat/022-quick-adjust-fix`   Area: firmware
Backlog rows: owner bug report 2026-10-24 (no spec row)   Release: firmware (3.12.2)

## Intent
On the box's admin page (the dashboard) the **Quick time adjust** panel has a phone chooser, a minutes box and the *Add* and
*Subtract* buttons. The owner presses one and the browser shows **"refused: this request came from another page"** instead of
the dashboard, for one phone and for ALL, on firmware 3.12.0 / 3.12.1, and says it never worked. The owner wants the buttons to
work from the dashboard, and to be told truthfully what happened. Serves rule 1: time is never wrong, and the owner is never
told a change happened when it did not.

## What I know (read from the code, not run)
- **The refusal is the box's own cross-site guard.** `checkAdminAuth()` (`esp32_firmware/src/WebServerAuth.cpp`, `crossSiteWrite()`
  at line 369) refuses any change that is not a GET when the request's **Origin** (or, without it, **Referer**) names a different
  host than its **Host** header, or is the word `null`, with `403 CROSS_SITE` and the message above (`esp32_firmware/include/OriginCheck.h`;
  host test `origin_check_test.cpp`). The quick-adjust form is a **plain HTML form post** to `/add_time`
  (`esp32_firmware/include/WebDashboardTemplate.h` line 86), the one kind of request here the browser makes on its own. The
  dashboard's other changes are sent by script.
- So the browser's Origin and the box's Host differ, or Origin is `null`, **only for this form**. Candidates, to be told apart by the
  real header values (not yet known): (A) the dashboard is opened through an address whose host the browser reports differently
  from the Host the box sees, for example the router console's *box view* (it rewrites Origin and Referer only when it sees an
  Origin header, `tools/pisoportal/src/boxview.rs` line 64), a name instead of an IP, or a forwarded port; (B) the post is sent
  with `Origin: null` (a page that sets no-referrer, or the form sent out of a sandbox or redirect); (C) the post carries a
  Referer but no Origin and the Referer host is spelled differently (upper case, a port, a trailing dot).
- Separately, and not the reported symptom: `handleAddTime` sends the time to the phones **inside the web request**
  (`sendAddTime`, `esp32_firmware/src/DeviceNetwork.cpp` line 27, up to about 2 s per phone, one after another), and writes
  "Changed time by ..." **before** anyone knows the phone answered. That can hold the box for many seconds with ALL and can say "Changed" for
  a phone never reached.
- The player-account buttons (`+ Time`, `- Time` on a QR card, `/api/accounts/adjust`) are a different path and are not part of this.

## Invariants touched
- Money and time: an owner adjustment is a time change on a phone with `amount=0` and its own transaction id (`tx-adj-...`). Kept:
  one id per press, the phone credits that id once, and an adjustment goes only to a paired, active slot. **Not touched:**
  payments, the coin queue, expiry.
- **Security: the cross-site guard stays.** A hidden form on another website must still be refused. The fix makes the dashboard's own
  post pass (by sending it the way the guard accepts, or by teaching the guard a genuinely same-site spelling), never by weakening
  the guard to "allow when unsure". Every change is tested with the attack cases already in `origin_check_test.cpp` kept red-proof.
- Only owner-signed software: not touched. The signed phone call is unchanged.

## Constraints
- The box <-> phone message does not change. The dashboard page is generated (`WebAssets.h`, `python3 scripts/embed_web.py`); never edit it by hand.
- Firmware code needs a higher `PISO_FW_VERSION` or boxes ignore it.

## Success criteria
1. **The failing request is reproduced as data.** The exact Origin / Referer / Host the owner's browser sends for this form are
   captured (Owner decision 2) and added as a case to `origin_check_test.cpp`; it is **red** on the current guard (it is refused).
2. **The dashboard's own Add / Subtract passes the guard** in every way the owner opens the dashboard (Owner decision 1), and the
   cross-site cases still fail: a different host, `Origin: null` from a foreign page, a missing Host. Both kinds are in the
   host test, with the sanitizers.
3. **The page tells the truth about the phone.** After a press the dashboard says "Sent +N min to ...", and per phone either "done" or
   "the phone did not answer, nothing changed"; it never says "Changed" for a phone that was not reached (Owner decision 3).
4. A press for an unpaired phone still says "Not changed" and sends nothing (as today).
5. The firmware compiles in CI for all four boards; clang-format 23.1.2, the host tests and `check_security_rules.sh` pass.
6. **Hardware check RS47** is added to `docs/REAL_WORLD_TESTING.md` (open the dashboard each way the shop uses, press Add and Subtract
   for one phone and ALL, one phone switched off) and stays unticked until run.

## Out of scope
The QR card buttons, the phone side, a new setting, undoing an adjustment, moving all sends to a background worker (see Owner decision 3).

## Owner decisions
1. **How do you open the dashboard when you press these buttons?** The box's address typed in the browser, the link inside the
   router's console, a name (like `pisobox.local`), or something else? Answered 2026-10-24: firmware 3.12.x, never worked, one
   phone and ALL both fail, message "refused: this request came from another page". The how-do-you-open-it answer is still open.
2. Can you press Add once and send me what the **Diagnostics** page (or the box's serial log) shows right after? The box logs a line
   "[AUTH] Refused a change to /add_time that came from another website's page (...)" with the page it saw.
3. The "Sending ... then the result on the next load" page was approved (2026-10-24). **Should the sends also move out of the web request
   now** (the box is held up to 2 s per phone while it sends), or leave that for a later feature? My proposal: **later**, because it
   is not the reported failure and this fix touches money-adjacent code.
