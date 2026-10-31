# Feature 056: voice message from the box's admin page to a rented phone

Status: **draft; owner decisions D1 to D9 answered (2026-10-31); waiting for the owner's approval of the changed spec and plan**   Branch: `ccr-6f20d511-8e6cu1` (this session's branch; the usual name would be `feat/056-voice-message`)   Area: firmware + app (one box <-> phone message, so both sides change in one feature; the router is not touched)
Backlog rows: none (owner request 2026-10-31)   Release: firmware (higher `PISO_FW_VERSION`) and app (higher build); the web files are regenerated

## Intent
A shop owner standing at the box's admin page wants to say something to one renter ("your time is almost over", "please bring the phone back") without walking over.
Every paired phone on the admin page gets a mic button. The owner **holds it and speaks** (up to 15 seconds). On release the mic turns into a **paper plane**
that means "recorded, ready to send to this phone". Tapping the plane sends the message; the phone plays it at once, with a "Message from the shop" banner.
The microphone is the owner's browser's. The phone never records anyone and the app gets no microphone permission.
**The box never stores the message, not even for a moment.** When the plane is tapped, the browser streams the clip to the box and the box passes it on to the phone piece by piece as it arrives
(owner decision D9). The box holds one small piece at a time, never the whole clip, and keeps no copy.

## Invariants touched
- Money and time (a payment recorded first, credited once, nothing lost on a power cut): **not touched, but the box's memory and CPU are shared with the payment code.** The voice relay never changes credit, expiry, deduction or the payment queue. It holds only one small piece of the clip at a time
  (no whole-clip buffer, so the box's free memory stays flat while a message passes), runs one at a time, and is refused (the owner sees why) rather than risk a restart. The clip is never stored on the box, so a power cut loses nothing but an unsent clip.
- Only owner-signed software (OTA, signing, keys): **not touched.** No key is added. The clip is encrypted and signed with the phone's existing box key, like every other box -> phone message.

## What the owner sees (the contract for the admin page)
Each **paired phone** row (online or offline) has a mic button in its actions. The states of one row:

| State | What the row shows | How it gets there |
|---|---|---|
| Ready | mic icon | default |
| Recording | mic turns red, a counter runs 0 to 15 s | press and hold the mic |
| Recorded | **paper plane** (and a small cross, and the length, for example "7 s") | release, or 15.0 s reached (it stops by itself, as if released) |
| Sending | plane greyed, "Sending..." | tap the plane |
| Sent | "Sent" for 2 seconds, then back to the mic | the phone accepted the message |
| Failed | the plane stays, a red line says why (see "What a visitor without the condition sees") | the phone or the box refused or did not answer |

- A press shorter than half a second is an accident: nothing is kept, the mic stays, and a hint says "Hold the mic while you speak".
- The small cross beside the plane throws the recording away and brings back the mic. To record again the owner discards first.
- The recording stays **in the browser**. The box stores nothing until the plane is tapped, and then only while it passes the clip on. Closing or reloading the admin page drops unsent clips (said once in the owner's manual).
- The page refreshes its phone list every few seconds. A refresh must never interrupt a recording, drop a recorded clip or flip an icon back (state is kept per phone, not in the redrawn row).

## What a visitor without the condition sees (every "no" case, said in words)
| Condition | What the owner sees |
|---|---|
| Empty slot, or a "Wants to connect" row | No mic (nothing to send to). |
| Paired phone whose app is too old to know voice messages (it does not report the capability) | A **greyed mic**; pressing it says "Update the app on this phone to send voice messages". |
| Phone is **offline** (the dashboard says Offline) | The mic works and the clip can be recorded. Tapping the plane still tries (the status may be a few seconds old). If the phone does not answer: "<phone> did not answer. The message is still here: tap the plane to try again." Nothing is kept on the box. |
| The browser blocks the microphone (the page is plain `http://`, or the owner said no, or no mic exists) | The mic is shown; pressing it says in plain words why and what to do (the one-time Chrome setting, see Constraints). Nothing is recorded. |
| The box page is opened inside the router console's "Phones" tab (the console frames it with `allow=""`, which also blocks the microphone) | Same message, plus "Open it on its own" (the console already has that link). Changing the router console is out of scope. |
| The box is short of memory, or already passing another clip on | "The box is busy, try again in a few seconds." The clip stays in the browser. |
| The phone stops answering, or the connection drops, while the clip is on its way | "The message was cut off. <phone> did not get all of it. Tap the plane to send it again." The phone throws away a clip that did not arrive whole and plays nothing of it. |
| The signed-out admin | Same as every other admin call: the page asks to sign in again; the clip stays. |
| A PisoWiFi-only box | No phones, no mic, no new code: the files stay out of that image (`scripts/check_product_strip.py`). |

## Constraints
- **Browser microphone (owner answer D1):** browsers allow the microphone only on `https://` or `localhost`. The box serves plain `http://` on the shop LAN and gets no HTTPS (TLS on the coin box is a risk to the money code). So recording works where the owner has made a one-time
  setting in Chrome on the device they use: `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, add the box's address (`http://<box address>`), restart Chrome. iPhone browsers have no such setting and cannot record; the page says so. Group 0 proves this on real devices before anything else is built; the owner's manual gets the steps.
- **Wire format:** one fixed format so the page, the box and the phone cannot disagree: 8 kHz, mono, 4-bit IMA ADPCM, at most 15.0 s (about 60 KB), with a small header (format version, sample count, length of the audio). The page converts whatever the browser records into it; the phone decodes it itself and plays the samples (it does not rely on the phone's codecs). Known-answer vectors go in `protocol/` and both sides must reproduce them (`protocol/gen_vectors.py`, `protocol_contract_test.cpp`, `ProtocolContractUnitTest.kt`).
- **Streamed, in numbered frames (owner decision D9):** the box cannot sign a clip it has not seen to the end, and it must not keep the clip, so the clip travels as a sequence of small frames. Each frame is encrypted with the phone's box key and carries its own number and signature, which also covers the frame before it;
  the last frame says how many frames and samples there were. The box encrypts and signs one frame at a time and sends it on at once; the phone puts the frames together in its own memory and **plays only after the last frame has checked out** (a missing, repeated, reordered, altered or extra frame throws the whole clip away). The browser never holds a key. The exact framing (frame size, the signed fields, whether the phone's request carries a fixed length or chunked transfer) is decided in group 1, checked by the same kind of known-answer vectors, and the spec is changed first if it needs anything not written here.
- **Security:** only a signed box can make a phone play anything. The phone applies the same checks it applies to every other box message: encrypted and signed with the box key, a fresh time, accepted once (`ReplayGuard`, `RequestFreshness`), and the clip's length must match its header and stay within 15.0 s and a byte cap. It counts bytes as they arrive and stops at the cap, so a sender cannot make it hold more. The box side is admin only (`checkAdminAuth`), accepts only a request made by its own dashboard (`OriginCheck.h`, as the other POSTs do), and only to a phone configured on this box (as `handleApiLocate` does).
- **Box memory (owner decision D9):** the box **never holds the whole clip and never stores it**. It works on one frame at a time (a few KB: read a piece of the browser's upload, encrypt and sign it, write it to the phone's open connection, release it), so the most it ever holds for a clip is one frame plus its overhead, however long the clip is. The relay runs one at a time, starts only if the largest free block stays above a small reserve
  (the number comes from group 0's measurement on a real box, in the style of the `Diagnostics.cpp` check), frees its frame buffer on every path, and feeds the watchdog while it works (the same way the firmware upload does). If the phone is slow the box waits for it for at most a few seconds per frame and about 10 seconds in all, then gives up with the "cut off" answer. Nothing about it may delay the coin pulse counting, the payment queue or an acknowledgement; if group 0 cannot show that, the feature stops and the owner decides.
- **Early answer for an unreachable phone:** before the browser uploads anything, the page asks the box whether the phone answers and the box is free (a tiny request that returns at once), so a phone that is off or a busy box is reported in under a second and the clip is not sent for nothing.
- **Phone playback (owner answer D3):** plays at once, on the lock screen or over a game, with a banner "Message from the shop" for as long as it plays; one message at a time (a new one replaces the one playing); played once, then deleted; no replay, no history. It never touches the time left, the session, credit or the coin slot. The existing countdown and closing speech keep their priority (the message waits until they end).
- **Phone permissions:** no `RECORD_AUDIO` or any new permission in the app's manifest.
- **Capability flag:** the phone tells the box it can play voice messages (a field in the heartbeat the phone already sends; the box keeps it per phone and lists it in `/api/devices`). A phone that never sends it counts as "too old". The box and phone changes ship together, in either release order without harm: new firmware with old apps shows greyed mics; new apps with old firmware simply are never sent anything.
- **Generated files:** `esp32_firmware/include/WebAssets.h` only through `python3 scripts/embed_web.py`. New page code goes in the phone files (`portal-core-phones.js`, `portal.css` between `/*@phones*/` and `/*@end*/`).
- **Message contract:** box <-> phone changes on both sides in this one feature (`CLAUDE.md` section 6).
- **Releases:** firmware needs a higher `PISO_FW_VERSION` or boxes ignore it; the app needs a higher build number. No router file is touched, so `beta` is not needed and `setup/RELEASE` stays.

## Success criteria
1. **Hold and release (browser test):** holding the mic on a paired phone's row starts a recording; releasing it turns the mic into a paper plane with the length; holding to 15 s stops the recording by itself at 15.0 s. A press under 0.5 s keeps nothing and shows the hint. The cross discards.
2. **Refresh safe (browser test):** while recording, and while a recorded clip waits, the page's automatic refresh of the phone list leaves the icon, the counter and the clip unchanged. Several rows can each hold a clip.
3. **Rows (browser test):** a paired online phone, a paired offline phone and a paired phone that reports voice support each show the mic; an empty slot and a "Wants to connect" row show none; a paired phone without the capability shows a greyed mic with the "Update the app" message; a PisoWiFi-only image carries none of this code (`check_product_strip.py`).
4. **Blocked mic:** with no secure context, with permission denied and with no microphone, pressing the mic shows the plain message and records nothing (browser test with the three cases faked).
5. **Wire format:** the page's encoder, the Python reference and the phone's decoder produce the same bytes and the same samples for the known-answer clips (silence, a tone, a 15.0 s clip, the shortest clip). A clip over 15.0 s, a header that lies about its length, a wrong version and an empty clip are refused by the phone and by the box. Frames: the phone plays a clip put together from the vector's frames and throws away one with a missing, repeated, reordered, altered or extra frame, or without its last frame.
6. **Security (host tests):** the phone plays nothing for an unsigned, wrongly signed, stale, replayed or mis-sized message; the box refuses a signed-out request, a request from another site's page and an address that is not a configured phone, and sends nothing to such a phone.
7. **Relay, streamed (host tests and a fake phone):** a good clip reaches the phone byte for byte; **the relay core never holds more than one frame at a time, however long the clip is (a test with the largest clip counts the most bytes it ever held)**; a second relay while one runs, a low-memory box, an unreachable phone (answered by the early check, before any upload) and a phone that stops halfway (answered "cut off", with the phone discarding the part) each give their own plain error; the frame buffer is freed on every path; the box has stored nothing and holds nothing after the answer.
8. **Phone playback (Robolectric unit tests):** a good message plays once and shows the banner; a second one replaces the first; the countdown speech is not cut off; the file or buffer is deleted after playing or after a failure; time left, session state and the coin slot are unchanged by it (a before-and-after check).
9. **Money and time:** the existing `coin_*`, `payment`, `protocol_contract`, `push_params` host tests and the app's payment and session unit tests pass unchanged, and a host test shows the relay refuses before its small reserve is touched.
10. **Capability and releases:** a phone that sends the capability gets a normal mic; one that does not gets the greyed one. The app's manifest has no new permission. `PISO_FW_VERSION` and the app build are raised; `python3 scripts/embed_web.py --check` passes; `bash scripts/check_security_rules.sh` passes.
11. **Hardware (owed, not a computer check):** group 0's browser findings, and a real box, a real phone and a real admin browser: record, send, hear it; offline phone; a coin inserted while a clip is relayed; free memory before, during and after, which must stay flat during a message (the proof that nothing is held). Added to `docs/REAL_WORLD_TESTING.md` as RS66 onward, unticked.

## Out of scope
- Sending to **all phones at once**, a reply from the phone, a replay button, a history, text messages.
- Delivering to an offline phone later; the box keeping a clip, even for a moment (the owner chose: say it failed, keep the plane; D2, D9).
- The browser sending straight to the phone and bypassing the box (the owner chose to go through the box, D9: the box is the one place both can always reach, and it keeps the keys).
- HTTPS on the box; recording through the phone's own recorder app.
- Voice messages from the router console, or the console's "Phones" tab (the frame blocks the microphone; the router is not changed).
- Recording on the phone; any microphone permission for the app.
- Any change to prices, credit, expiry, the payment queue, the coin slot or the router.

## Owner decisions
Answered 2026-10-31 (asked in the planning chat):
- **D1** Microphone access: the browser's own microphone with a one-time Chrome setting (not HTTPS on the box, not the phone's recorder app).
- **D2** Offline phone: the send fails with a plain message and the plane stays; nothing is stored on the box.
- **D3** On the phone: plays at once, with a banner, on the lock screen or over a game.
- **D4** Rows: paired phones only, online or offline; a greyed mic with "Update the app" when the app is too old.
- **D5** The owner's "voice off" setting and quiet hours do **not** stop a voice message. It is a live message from the owner; it plays at the phone's current voice volume, and a phone at volume zero stays silent (the banner still shows). Approved as proposed.
- **D6** Re-recording: a small cross beside the plane discards the clip. Approved as proposed.
- **D7** A send is **not** refused while a coin slot is armed; the relay refuses only when memory is short or another clip is passing. Group 0 measures on a real box, and the owner is told if it shows a problem. Approved as proposed.
- **D8** iPhone admins cannot record (no browser setting exists); accepted, and the manual says so. Approved as proposed.
- **D9** The audio path: the browser streams the clip to the box and the box passes it on to the phone piece by piece, **storing nothing and never holding the whole clip**. Not the browser straight to the phone. Answered in the chat of 2026-10-31 (it replaced the first draft, where the box held the clip in memory while relaying it).

Open: none.
