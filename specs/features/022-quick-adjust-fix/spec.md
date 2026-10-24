# Feature 022: quick add / subtract time on the box's admin page (through the router console)

Status: **draft (cause found, owner answers recorded 2026-10-24; waiting for approval)**   Branch: `feat/022-quick-adjust-fix`   Area: **router**
Backlog rows: owner bug report 2026-10-24 (no spec row)   Release: router 1.5.20

## Intent
The owner opens the coin box's admin dashboard from the **link inside the router's console** (the "box view"). Pressing **Add** or
**Subtract** on the *Quick time adjust* panel, for one phone or for ALL, shows **"refused: this request came from another page"**
instead of changing the time. The owner wants the buttons to work. Serves rule 1 (time is never wrong) and the owner's day-to-day use.

## Cause (found by reading the code; not yet run against a real browser)
- That text is printed by the **router** (`tools/pisoportal/src/boxview.rs` line 127), not by the box. The box view refuses any
  request that is not a GET when `same_site_post` (line 101) finds the browser's **Origin** different from the **Host**.
- The same file tells the browser, for every box page it shows, `Referrer-Policy: no-referrer` (`frame_policy`, line 48). With that
  policy a browser sends **`Origin: null`** on a **plain HTML form post**. The Quick time adjust form is exactly that
  (`esp32_firmware/include/WebDashboardTemplate.h` line 86, `<form action="/add_time" method="POST">`). `null` never equals the Host,
  so the router refuses it. The dashboard's other changes are sent by script (`fetch`), which carries the real Origin, so they work.
- That is why only this form fails, for one phone and ALL alike, on every firmware, and "never worked" through the console.
- (Opened directly by the box's IP address, the box's own guard `OriginCheck.h` accepts the same-origin post, so the buttons are
  expected to work that way; this is a prediction, checked by the host tests, not by a browser.)

## Invariants touched
- Money and time: **not touched.** The adjustment itself (a signed `/add_time` to the phone with its own `tx-adj-` id, credited once)
  is unchanged; this feature only lets the request reach the box.
- **Security: the guard stays strict.** A hidden form on another website must still be refused: a different Origin, a foreign `null`
  from a sandboxed page, a missing Host. The fix changes what the *box's own pages* send, never "allow when unsure".
- Only owner-signed software: not touched except that the router program changes, so it is released and signed the normal way.

## Constraints
- The router program uses only `sha2`, `hmac` and `p256`. The box page is not changed (it lives in firmware); this fix is entirely in the
  router, so it also works with every box already in a shop.
- `setup/RELEASE` is raised (a change under `tools/pisoportal/` changes what routers install); generated files by `tools/build_piso_setup.py`.
- No page the console shows may be allowed to leak the console's address to another site: the policy must stay as strict as today
  for everything except the box's own same-site posts (`same-origin`).

## Success criteria
1. **The failing request is a test, red first.** `tools/pisoportal/tests/` (a new test or in `test_console.py`): a plain form post
   through the box view with `Origin: null` is refused today, and the page headers say `Referrer-Policy: no-referrer`.
2. **The box pages are sent with `Referrer-Policy: same-origin`** (the browser then sends the real Origin on a same-origin post and
   nothing to other sites). The Add / Subtract post with Origin `http://<router>:<console port>` and the same Host is **accepted**
   and reaches the box with the box's own address as Origin (as the proxy already rewrites it).
3. **Attacks still fail.** The router test keeps (and adds) refusal cases: a different Origin, `Origin: null` on a post (a foreign
   page), an `https://` Origin, no session. All still refused.
4. `cargo test`, clippy, rustfmt, `python3 tests/test_flow.py`, `test_console.py` and the router setup tests pass; the footprint budget holds;
   `build_piso_setup.py --check` passes.
5. **Hardware check RS47** is added to `docs/REAL_WORLD_TESTING.md` (open the dashboard from the console, press Add and Subtract for one
   phone and for ALL, and a phone switched off) and stays unticked until run.

## Out of scope
- The firmware: moving the sends out of the web request (the box holds the page up to 2 s per phone) and the dashboard saying "Changed"
  before the phone answers. Those are real but separate (Owner decision 2: **later feature**).
- The QR card `+ Time` / `- Time` buttons, the phone side, anything about money.

## Owner decisions
1. Answered 2026-10-24: you open the dashboard from the **link inside the router console**. This is what pointed to the router.
2. Answered 2026-10-24: the slow/blocking sends and the "Changed" wording are **a later feature**, not this one. (If you want the
   "Changed" wording made truthful soon, say so: it is a small firmware change of its own, feature 025.)
3. Release: this ships as **router 1.5.20**. Router 1.5.19 is published at 0% and has not been raised; my proposal is to raise
   **1.5.20** to 100% instead, once the hardware checks RS44 to RS47 are done. Do you agree?
