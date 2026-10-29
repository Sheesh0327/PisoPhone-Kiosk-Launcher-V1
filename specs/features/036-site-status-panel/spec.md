# Feature 036: the site status panel (Part A: money agreement on the console front page)

Status: **draft (waiting for the owner's approval; three decisions below change the epic)**   Branch: `feat/036-site-status-panel`   Area: router (console)
Backlog rows: epic `specs/spec_03_overhaul.md` feature 036, D5 to D8   Release: router (next number after 1.5.26). **No firmware release, no app release in Part A.**

## Intent
A shop owner looking at the router console cannot see whether the coin box and the sales record agree. Today they see "Records OK", which only
says the sales file's hash chain is intact. The comparison already exists and runs: `pisoportal reconcile` (`tools/pisoportal/src/reconcile.rs`),
once an hour from `router/piso_monitor.sh` (`check_ledger`) and from Telegram `/reconcile`. Only a *mismatch* (the router recorded more than the
box counted) ever reaches the owner, as one alert line. This feature puts the comparison on the console front page, always, with both numbers and the
reason for any gap (D7, D8). It serves rule 1: money is never wrong, and the owner can see that it is.

## What was found (re-read in the code on 2026-10-29, as the epic requires)
1. **The finding stands.** `console-ui.js` line 35 draws tiles `Coin box` (answering or not), `Records` (`ledger_ok`: chain intact) and `Sales detail`. Nothing
   shows box count against ledger. `reconcile::run` is never called from `console.rs`.
2. **The epic's example is only half right.** It says the box being ahead is normal because it also counts rental-phone coins. `reconcile::run` reads
   `lifetime_wifi_pulses` first (Wi-Fi coins only) and falls back to `lifetime_pulses` (all coins) only when the box does not report the first.
   So on current firmware (it reports `lifetime_wifi_pulses` and `lifetime_phone_pulses`, `WebServerGateway.cpp:100-102`) **a box ahead of the ledger is not
   explained by phones**: it is either a coin being paid for right now, or a coin counted with no sale recorded. Today `reconcile` says nothing in that case
   (it flags only `ledger > box`). The panel must say the right thing for each kind of box; whether to *alert* on it is Q3.
3. **The epic's design for version skew rests on a wrong premise** (D5: "the box learns the expected versions from the signed feed it already fetches").
   The box does not fetch the feed. The owner's *browser* does (`esp32_firmware/web/ota.js:120`) and hands the box a signed manifest. The phone heartbeat
   carries battery, charging, name, restart count and time left, not its app version (`WebServerTelemetry.cpp`), and the router link (`boxlink`) reports no
   router version and no firmware version. See Part B.
4. **One caution on the data.** The router link signs `state:armed_remaining:pulses:lifetime:slot_free:ready_in:per_coin`
   (`WebServerGateway.cpp:96`). `lifetime_wifi_pulses` and `lifetime_phone_pulses` are in the answer but **outside the signed facts**. The panel only
   displays; no money moves on it, and `reconcile` already depends on the same field. Recorded, not fixed here.

## Invariants touched
- Money and time: **not touched.** The panel reads the ledger and asks the box for its status (a read the console already makes). It never writes a ledger
  row, never changes `reconcile.state` (the hourly monitor stays its only writer) and never credits or prices anything. Proof in criteria 4 and 8.
- Only owner-signed software: not touched.

## Constraints
- `pisoportal reconcile` output and exit codes (0, 1, 3, 4) do not change; `piso_monitor.sh` keeps working unchanged.
- The panel costs the box at most **one extra `status` call per minute** per router (a cache), however many consoles are open. The ESP32 serves phones too.
- Router program limits: only `sha2`, `hmac`, `p256`. Console text in plain English, one language, like the rest of the console.
- A change under `tools/pisoportal/` raises `setup/RELEASE`; generated files are regenerated, never edited (`CLAUDE.md` section 4). Installer stays under 1700 KB.

## What each visitor sees (the states; the owner reads the panel, so "without the condition" means the cases where the numbers are not there)
Panel position: a full-width line directly under the earnings header, above the tiles, on every load. Both numbers are always shown when known.

| # | Situation | Panel says | Tone |
|---|---|---|---|
| 1 | box and sales record agree | "Money agrees. Coin box counted P1,247, sales record P1,247." | green |
| 2 | box ahead, and the box reports Wi-Fi coins separately (current firmware) | "Coin box counted P1,310, sales record P1,247. Difference P63: coins counted but not yet in the record. Normal for a minute while a customer is paying; if it stays, tell your installer." | gold, never red |
| 3 | box ahead, old firmware (only the all-coins count) | "Coin box counted P1,310 (includes rental-phone coins), sales record P1,247. Difference P63 = rental phones. Normal." | green |
| 4 | box behind the record (the real mismatch) | "MISMATCH. Sales record P1,300, coin box counted P1,247. The record has P53 the box did not count." | red |
| 5 | box's count restarted since the last check (revenue was collected) | the numbers since the restart, plus "The box's count restarted when its revenue was collected; compared from here." | green |
| 6 | box not answering, or its firmware is too old to report a count | "Coin box is not answering. Sales record P1,247. The comparison resumes when the box is back." | gold |
| 7 | the sales record fails its check | "The sales record was changed: line N is wrong." (the existing wording) | red |
| 8 | no sales yet | "No sales recorded yet. Coin box counted P0." | neutral |
| 9 | PisoPhone-only shop (no router console exists) | not applicable: no panel, no change | |

## Success criteria
1. A pure function maps (box count kind, box count, ledger since base, restart flag, box reachable, ledger ok) to one of rows 1 to 8 with the numbers and the
   reason. Unit tests (names `f036_*`) cover every row; they fail first (logged in `ledger/`).
2. `/api/overview` gains a `money` object (`state`, `ledger`, `box`, `diff`, `kind`, `note`); `tests/test_console.py` checks each state against `fakebox.py`
   (extended with the wifi and phone counters and a "restarted" and "silent" mode).
3. The front page draws the panel for every state with both numbers; `tests/test_browser.py` checks the text for rows 1, 2, 4, 6, in light and dark and at phone width.
4. Reading the panel never changes `reconcile.state` (bytes identical after 50 overview polls) and calls the box at most once per 60 seconds (the fakebox counts calls).
5. `pisoportal reconcile` prints the same lines and returns the same exit codes as before (the existing `test_flow.py` checks pass unchanged).
6. A counter restart (row 5) is a note, not a mismatch; the second poll after it shows row 1.
7. Row 2 versus row 3 is decided by which count the box reported, never by the size of the gap.
8. `git diff` of `core.rs`, `ledger.rs` (write paths), `pricing.rs`, `voucher.rs` is empty: no payment code touched. `cargo test` and the seven setup test files pass; `check_security_rules.sh` passes.
9. `setup/RELEASE` raised, generated files rebuilt, `build_piso_setup.py --check` passes; hardware check **RS58** added to `docs/REAL_WORLD_TESTING.md` (a real router and box: panel agrees after coins; after collecting revenue the restart note; box unplugged shows row 6).

## Part B: version skew (D5, D6): NOT in this spec until the owner chooses
Warn only, never block (D6) stays. The *where* has to be decided again (Q1), because the box cannot know what it never sees. Three designs:
- **B1, the router holds the expected set.** The router already reads `router.json`. It would also read `firmware.json` and `app.json` (public, same host). It needs the box's firmware version (a new field in the link answer: router and box only, no phone change) and, for phones, their app version (the phone heartbeat would have to carry it: **a box <-> phone contract change, both sides, new vectors, app and firmware releases**). Warn on the console and in `alerts.txt`. Without the phone part it covers router and box only.
- **B2, the owner's browser holds it.** The box's dashboard already fetches the feed. It could compare the box, and phones the box has seen with versions (same heartbeat change), and show a line. Nothing on the console, nothing in `alerts.txt` (the browser is not always open), so it fails the epic's "always visible".
- **B3, router and box only (recommended first step).** Compare router and box firmware to the feeds; leave phone app versions for later. One small router change plus one link field; a firmware release only to add the field.

## Out of scope
A sales history page (offered in the epic, not taken); changing any alert rule (Q3); the box's own dashboard; Telegram wording; PisoPhone-only shops; making the wifi counter part of the signed facts (a firmware and link contract change; recorded as a follow-up).

## Owner decisions
- **Q1: split the feature?** Recommended: 036 is Part A only (router, one release); version skew becomes its own feature after you pick B1, B2 or B3. This changes the epic's 036 paragraph, D5 and section 5 (036 would no longer be "router + firmware").
- **Q2: how fresh?** Recommended: live, with a 60-second cache, so the panel is current without hammering the box. Alternative: show what the hourly monitor last stored (always up to one hour old, zero extra box calls).
- **Q3: box ahead on current firmware** (row 2). Recommended: show it only. Alternative: also write one line to `alerts.txt` when the gap lasts more than an hour (changes `piso_monitor.sh`).
- Defaults I took, tell me if wrong: the panel is a full-width line above the tiles, not a tile; row 2 is gold, never red; the word "MISMATCH" is used only for row 4.
