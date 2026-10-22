# Feature 006: the wheel button for everyone (visitors who have not paid see it and the odds)

Status: **done on the computer** (all criteria passed, see `validation.md`; open: PR #, CI, release 1.5.15, hardware check RS34). Approved by the owner 2026-10-22   Branch: `feat/006-wheel-button`   Area: router (portal program, wheel page)
Backlog rows: none (owner error report 2026-10-22, `specs/replans/2026-10-22.md`)   Release: router (raise `setup/RELEASE` to 1.5.15)

## Intent
With the wheel on, a visitor who has not paid sees no wheel button and, since the wheel replaces the mini-game, nothing to play either. The owner turned the wheel on and found no button. The wheel button should be there for everyone, like the old mini-game button; the odds should be readable by anyone; **spinning** still needs paid time, enforced by the router as before. This serves the owner's wish for a livelier portal without weakening project rule 1.

## What the owner chose (2026-10-22)
Nobody had paid on the test phone. For visitors who have not paid: **a Spin the wheel button, the odds shown, spinning needs time** ("Buy Wi-Fi time first" with Spin disabled).

## Invariants touched
- Money and time (rule 1): **not weakened.** `/wheel/spin` still refuses a device without paid time, a paused session and every bet rule, on the router; the new "no time" answer of `/wheel/state` changes nothing about time. No change to `wheel_spin.rs`, the journal, the limits or the ledger.
- Only owner-signed software: not touched (router update signed as usual).

## Constraints
1. With the wheel off (or on without the owner's confirmation) nothing about the wheel is shown or answered, as in 005 (the page button, `/wheel`, `/wheel/state`, `/wheel/spin`).
2. With the wheel on: the coin page (idle view) and the "You are online" screen (final view) show **Spin the wheel** to every device; the old mini-game stays hidden while the wheel is on (005 owner answer 3).
3. `/wheel/state` answers 200 for a device with no paid time, with the table, the odds and `can_spin: false` and a short reason; it shows no time-dependent bet size (`max_bet` 0). `/wheel/spin` is unchanged and still refuses with NO_SESSION.
4. The wheel page shows the odds panel and the wheel picture to everyone, the line "Buy Wi-Fi time first, then you can spin" and a disabled Spin button when the device has no time (and the existing messages for paused, no spins left and the wait). No auto-spin, same as 005.
5. Children (005 owner answer 2): the odds panel is always shown; this feature adds no age note.
6. The router program keeps using only `sha2`, `hmac`, `p256`. No change to the coin box, the phone app, the box protocol or the revenue ledger.

## Success criteria
1. With the wheel on and a device that has paid nothing, the coin page state carries `wh: true` (idle) and the final view carries it too; with the wheel off both say false/absent. Tested in `cargo test` (view) and `test_wheel.py` (over the WebSocket, including a real coin payment).
2. `/wheel/state` for a device with no time: 200, table and odds present, `can_spin: false`, `max_bet` 0; with the wheel off: 404 as before. `/wheel/spin` for a device with no time: still refused, time and `wheel.log` unchanged. Tested in `cargo test` and `test_wheel.py`.
3. Browser test: an unpaid visitor sees the Spin the wheel button on the coin page, opens the wheel page, sees the odds and "Buy Wi-Fi time first", and Spin is disabled; after time is added the same page lets them spin (button enabled after a reload). The "You are online" screen shows the button.
4. The mini-game button is still hidden while the wheel is on and shown when it is off (existing test `test_game_browser.py` and `test_wheel_browser.py` stay green).
5. The console tells the owner what visitors see ("everyone sees the button and the odds; spinning needs paid time").
6. Existing tests untouched and green (`test_flow.py`, console tests, `test_wheel_browser.py` updated where it expected no button for an unpaid device), `cargo fmt --check`, `clippy`, `cargo test`, router setup tests, `check_security_rules.sh`, `build_piso_setup.py --check`; `setup/RELEASE` 1.5.15, CHANGELOG, manual, RS34 text updated (hardware check not run).

## Out of scope
Changing the odds, limits, the responsibility gate, the journal or the ledger; removing the mini-game code; an age note; other pages.

## Owner decisions
1. Approve this spec and plan.
2. Release: merge to `beta`, then `main` (1.5.15 publishes at 0%), then your 100% order as for 1.5.14. OK?
