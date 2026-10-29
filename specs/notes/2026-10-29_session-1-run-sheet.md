# Run sheet: hardware session 1, the box on the bench (2026-10-29)

Source of truth: "Session 1" in `docs/REAL_WORLD_TESTING.md` and the row text in each section. This sheet only orders the work and gives blanks for readings. Write the result into the row's tick and, for features, into the feature's `validation.md` **the same day**.

**Needs:** coin box with coin acceptor, USB cable and a computer with the setup program, one paired phone, a switched power strip, serial monitor (`pio device monitor`), a Wi-Fi the box can join. Part 1b also needs a new or erased ESP32-C3. About 3 hours for part 1a, plus a 24-hour wait inside RS46.

Before you start: box firmware version ______ (3.12.4 live)  phone app build ______ (546 live)  date ______

## Part 1a: money first, in this order
| # | Row | Pass means | Result | Reading (coins in, credited, seconds) |
|---|---|---|---|---|
| 1 | RS30 | Power cut between coin and queue: coin credited once after boot, never lost, never twice | | |
| 2 | RS28 | Settings saved during a coin drop: the full P5 is credited | | |
| 3 | RS31 | Coin at an idle slot; large rate | | |
| 4 | F8 | Unacknowledged payment survives a factory reset (leave a coin unacknowledged as in M3a: switch the phone app off after the coin) | | |
| 5 | RS29 | Reboot, update, reset refused while a slot is armed | | |
| 6 | RS46 a, b, c, d, f (evening); start e (24-hour soak) | Box keeps working; read the soak next day; g only if a 3.12.0 box exists, else say so | | |
| 7 | C3b | Collect: counter goes to P0, lifetime coins do not go down (admin QR from `scripts/make_admin_qr.py`) | | |
| 8 | C8 | Revenue counter resets after 5 minutes or on logout | | |

**Stop rule:** if any of rows 1 to 5 shows a coin lost or counted twice, stop, write exactly what happened in the Issue log, and do not continue to the next row. Money rows are the product.

## Part 1b: new or erased ESP32-C3
B9, RS50 (Windows), then §10-1 to §10-5 in order; do §10-3 straight after 11 and 12 and keep the box for session 2.

## After
- Tick or annotate each row in `docs/REAL_WORLD_TESTING.md`; add failures to the Issue log with the box's firmware version.
- Tell Claude the results; it updates `specs/STATUS.md` and the `validation.md` files. Nothing here needs a code change unless a row fails.
