# Feature 061: Box <-> phone signatures that cannot stand in for each other

Status: **draft, waiting for the owner's approval**   Branch: `feat/061-protocol-hardening`   Area: firmware and app together (one protocol change, `CLAUDE.md` section 6)
Backlog rows: HARDENING items 1, 2, 3 (`docs/HARDENING.md`)   Release: firmware **and** app (neither alone completes it; each is safe alone, see Constraints)

## Intent
Three weaknesses on the kiosk network between a coin box and its rental phones, found by the 2026-10-17 audit and re-read against the code on 2026-11-01:
1. **An acknowledgement and a coin proof are the same signature.** The box proves a coin with `HMAC(secret, "v1:<device>:<tx>:<pesos>:<ts>")` (`esp32_firmware/src/WebServerAuth.cpp:137`, `app/.../network/CoinProof.kt`). The phone acknowledges a coin with the same text (`KioskHttpServer.kt:301`), and the box accepts it taking `ts` **from the acknowledgement** (`WebServerAuth.cpp:186-191`, `WebSocketsUdp.cpp:503-513`). Whoever holds a coin proof can therefore build a valid acknowledgement. An acknowledgement removes the coin from the box's retry queue for good.
2. **`seconds` in a coin proof is not signed.** `CoinProof.secondsPlausible` caps it at 3,600 seconds per peso (the normal rate is far lower), so a tampered status answer can grant up to that, not unlimited.
3. **The heartbeat reply is not signed.** The phone acts on `lockdown`, `slot_expired`, `status`, `price`, `minutes`, `acct` and `acct_bal` in the reply body (`Esp32HeartbeatBodyHandler.kt`). Anything answering at the box's address can lock a phone or change the price and minutes it believes. (Only `admin_pin` is encrypted.)
**How serious, honestly:** each needs someone **on the kiosk network** answering in place of the box or the phone, which is the owner's own network (guest Wi-Fi is separate). I found no remote way in. The audit rated these medium. This is defence in depth for money and time, not an active incident, and the fix touches the payment-acknowledgement path, so it is built carefully and last.
Serves rule 1 (money and time are never wrong) and the audit's top open money items.

## Invariants touched
- **Money and time: touched, carefully.** The condition under which the box deletes a queued payment (acknowledgement check) and the way a phone trusts a coin (proof check) change. How they are kept: a payment is still recorded first, credited once by transaction id, acknowledged only after crediting succeeded; **old and new signatures are accepted side by side until a per-device latch closes the old one**, so no payment is refused or stranded during the mixed-version months; contract tests replay recorded old-format messages against new code in both directions.
- Only owner-signed software: not touched.

## Design (the part that needs your approval)
All three use one pattern: **add a new field next to the old one; the receiver accepts either; once a receiver has seen the new form from a peer, it refuses the old form from that peer (a latch).** No flag day; old devices keep working.
1. **Acknowledgement.** The phone adds `ack_sig = HMAC(secret, "ack1:<device>:<tx>:<pesos>:<ts>")` beside the old `v_sig`. The box accepts `ack_sig`; it accepts the old `v_sig` as an acknowledgement only for a phone it has never seen send `ack_sig` (latch stored in the box's NVS per slot). The box's coin proof stays `v1:` so old phones still accept new boxes.
2. **Seconds.** The box adds `v2_sig = HMAC(secret, "v2:<device>:<tx>:<pesos>:<seconds>:<ts>")` beside `v_sig`. A phone that has seen a `v2_sig` from its box requires it from then on (latch in its prefs, per box secret); until then it accepts `v1` with the existing cap.
3. **Heartbeat reply.** The box adds the header `X-Reply-Sig: HMAC(secret, "r1:<device>:<server_time_ms>:<sha256(raw body)>")`. The phone checks it over the raw body bytes it received (no JSON re-formatting). Once a phone has seen one valid signed reply from its box it ignores `lockdown`, `slot_expired`, `status`, `price`, `minutes` and the account fields from any unsigned or invalid reply; it still reads `server_time_ms` and `auth_ok`, so a box can never be locked out by time.
**A limit I cannot remove:** a latch protects only from the first time it sees the new form. An attacker present **before** a phone's first signed reply could keep it in the old mode. Pairing a phone happens with the owner present; I will state this in `docs/ARCHITECTURE.md`.

## Constraints
- Both sides change together (`protocol/gen_vectors.py`, `esp32_firmware/host_tests/protocol_contract_test.cpp`, Kotlin contract tests), new vectors committed under `protocol/fixtures/`.
- **Every pair works:** old app + old box, old app + new box, new app + old box, new + new. This matrix is a test, not a hope (criterion 4).
- No new crypto primitive: HMAC-SHA256 with the existing box secret, as today.
- A new `PISO_FW_VERSION` and a higher app `versionCode` (nothing is ignored by old boxes or phones).
- Staged rollout (feature 059) should exist before this ships: until then both releases go to every device at once. **Sequencing recommendation: do not ship this before hardware checks RS28 to RS31 (box money rows) have passed**, so a protocol change does not stack on money behaviour nobody has seen on a real box.

## Success criteria
1. **Vectors.** `protocol/gen_vectors.py` produces `ack1`, `v2` and `r1` vectors (plus the old ones unchanged); the C++ host test and the Kotlin tests read the same files and agree byte for byte.
2. **Acknowledgement.** A box proof for a transaction, replayed as an acknowledgement, is **refused** by a box whose latch for that phone is closed, and accepted (as today) by one whose latch is open. A real `ack_sig` is accepted in both. After one `ack_sig`, the old `v_sig` form is refused for that phone. Tested on the host (firmware) with recorded messages.
3. **Seconds and heartbeat.** A status answer with `seconds` changed but the old `v_sig` kept is refused by a phone whose latch is closed. An unsigned reply carrying `lockdown` is ignored by a latched phone and still obeyed by an unlatched one. `server_time_ms` and `auth_ok` are read from either.
4. **Mixed versions.** A table-driven test runs the four old/new pairs for: a coin payment end to end, the acknowledgement, a heartbeat, a pairing, and checks the payment is credited exactly once and removed from the queue only after crediting.
5. **Exactly-once and queue.** The existing payment-queue and credit tests pass **unchanged**; a new test shows a refused acknowledgement leaves the payment queued and it is retried (not lost, not doubled).
6. **No stuck state.** A box that restarts, a phone with a cleared app data and a re-paired phone each end in a working mode (the latch is per pairing, and a re-pair resets it by design); each is a named test.
7. **Documents and hardware.** `protocol/README.md`, `docs/api/` where the fields are listed, `docs/ARCHITECTURE.md` section 3 (including the limit above), `CHANGELOG.md`, and two hardware checks (a real box with an old phone, and a real box with a new phone) in `docs/REAL_WORLD_TESTING.md`. Nothing is called done until the hardware checks pass.

## Out of scope
- Signing the status-list answers as a whole, and the discovery HMAC on the HTTP probe (the rest of audit item 3). Offered as a follow-up.
- HARDENING items 4 (account credit goes to whoever is signed in when the ack arrives), 5 (recovery prices), 15 (replay windows), 9 (legacy public key): separate rows, each its own feature.
- Any change to what a phone does with the time or the price once it has trusted them.

## Owner decisions
1. **Is this worth doing now?** It needs two releases (box and phone) and touches payment acknowledgement. The risk it removes needs an attacker on your kiosk network. Recommendation: **yes, but after the box money checks (RS28 to RS31) and after the staged rollout (feature 059)**. If you would rather not touch the payment path, items 2 and 3 can be built without item 1 (they do not touch the acknowledgement).
2. **Which of the three?** Recommendation: all three, in this order: heartbeat reply (3), acknowledgement (1), seconds (2). Item 3 is the one a stranger-in-the-room could use most directly (lock a phone); item 1 matters most to money.
3. **When may the old form be refused outright (no latch needed)?** Recommendation: never in this feature; a later cleanup once every box and phone you own has the new build. The latch already closes it per device.
