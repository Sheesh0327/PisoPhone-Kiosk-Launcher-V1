# Group 0 findings: proof before code (feature 056, 2026-10-31)

Done on a computer only (headless Chrome 153 with a fake microphone; the app's NanoHTTPD 2.3.1 jar from Maven Central; this container). No box, phone or real microphone was used. The probe scripts are in `group0-probes/` and the browser run is in `mic_probe_output.txt`.
Rerun: `node mic_probe.js <this computer's LAN address>` (a LAN address, not 127.0.0.1: loopback counts as secure and would hide the problem), `node frame_try.js`, `node flag_try.js`; for the phone's server `java -cp nanohttpd-2.3.1.jar NanoProbe.java` (and `server` mode with `halfway.py`).

## (a) The browser microphone

| Case | What the page sees | Result |
|---|---|---|
| T1 Plain `http://192.0.2.2:port`, no setting (today's box page) | `isSecureContext` false, **`navigator.mediaDevices` is `undefined`** | **No recording possible.** This is the problem the spec describes. |
| T2 Same address after the Chrome setting `--unsafely-treat-insecure-origin-as-secure=http://192.0.2.2:port` | `isSecureContext` true, `getUserMedia` ok | Recording works. |
| T3 Setting made, the owner presses Block | `NotAllowedError: Permission denied` | Needs its own message. |
| T4 Setting made, no microphone on the device | `NotFoundError: Requested device not found` | Needs its own message. |
| T5 `http://127.0.0.1:port` with no setting | secure, ok | Only for tests: loopback is secure. |
| Frame, other port, `allow=""` (how the router console frames the box page, `console.html:102`; the frame address is built from the box's own port in `console-ui.js:76`) | `NotAllowedError: Permission denied` | **Confirms the spec: the console's Phones tab cannot record.** |
| Frame, other port, `allow="microphone"` | ok | Shows what a later router change would need (out of scope). |
| Frame on the **same** origin with `allow=""` | ok | Not our case; noted so nobody repeats my first wrong reading of this. |

Facts to build on in group 4:
- **Error names tell the cases apart for the owner's message:** `mediaDevices` undefined = the address is not allowed to use the microphone (show the Chrome setting steps); `NotAllowedError` = blocked (if `window.top !== window` say "open the box page on its own"; otherwise "allow the microphone for this page"); `NotFoundError` = no microphone.
- **The Chrome setting only works with the full Chrome, not the headless shell, and needs its own user-data-dir** (found in `flag_try.js`: the default headless launch ignored it). So a browser test of the *real* flag needs `chromium.launchPersistentContext(dir, executablePath=full chrome, args=['--headless=new', '--unsafely-treat-insecure-origin-as-secure=...'])`. **The browser tests in group 4 should not need it**: serve the test page from `127.0.0.1` (secure) with `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream`, and fake the three blocked cases (no `mediaDevices`, `NotAllowedError`, `NotFoundError`) with an init script; T1, T3 and T4 above are the proof that the real browser behaves that way.
- **Raw capture works and is small.** `MediaRecorder` gave `audio/webm;codecs=opus`, 40,980 bytes for 3 s (about 205 KB for 15 s, and the container differs by browser), so the page should not send what `MediaRecorder` makes. An `AudioWorklet` gave the raw samples (3.01 s, 132,736 samples at the context's 44,100 Hz; the fake device's peak was 1.009, so the encoder must clamp), and `OfflineAudioContext` resampled them to 8 kHz in 4 ms: **24,080 samples = 12,040 ADPCM bytes for 3 s, so about 60 KB for 15 s**, as the spec says. Use the `AudioWorklet` (loaded from a blob) and a `ScriptProcessor` fallback for browsers without it.
- **The Chrome setting names one exact address.** The box has no local name (no mDNS in the firmware; checked), so the owner types its IP address, and a box whose address changes breaks the setting. The manual must say: give the box a fixed address in the router first. (Added to the spec's constraints.)

## (b) Where the page code lives
- `portal-core-phones.js` (238 lines) is appended to `portal-core.js` only for the products with phones (`scripts/embed_web.py`, `assets_for(True)`); comment lines starting with `//` are dropped. **Decision: the voice code goes into `portal-core-phones.js`, the styles between `/*@phones*/` and `/*@end*/` in `portal.css`. No new file, `embed_web.py` is not changed.** The PisoWiFi-only image cannot contain it. `python3 scripts/check_product_strip.py` and `embed_web.py --check` pass today (both run before any change).
- **The phone list is redrawn every 3 seconds, whole:** `portal-core-end.js:2` polls `fetchDeviceStatus` every 3000 ms and the hook at `portal-core-phones.js:196` sets `container.innerHTML = ROW_HEAD + html`. A 15 s hold spans up to five redraws and each one destroys the button under the finger. So in group 4: the press and release are caught on `document` (not on the button), the recording state lives per slot in a `window` object next to `window.locating`, and `deviceRow` draws the icon from that state.
- Also for group 4: stop the long-press menu and text selection on Android (`touch-action: none`, `user-select: none`, a `contextmenu` handler) or the hold will be interrupted.
- **Group 2 note (for the PisoWiFi-only image):** `check_product_strip.py` fails on the words `phone`, `app`, `account`, `profile`… in anything the wifi image compiles. The new C++ headers (`VoiceClip.h`, `VoiceFrames.h`, `VoiceRelay.h`) must be listed in the wifi product's `skip_headers` and their includes and routes sit inside `#if PISO_HAS_PHONES`, like `PhoneProfiles.h`.
- Browser tests follow `scripts/tests/test_portal_*_ui.py` (Python Playwright, the real JS files loaded with the box's answers mocked, "skipped" when Playwright is missing, run from `.github/workflows/quality.yml` next to line 245). Group 4 adds `scripts/tests/test_portal_voice_ui.py` and that CI line.

## (d) The phone's server (NanoHTTPD 2.3.1, the version in `app/build.gradle.kts:153`, started with `SOCKET_READ_TIMEOUT` = 5 s as in `KioskEngine.kt:340`)

| Question | Observed |
|---|---|
| A body of known length (60,000 bytes, sent in 1,436-byte pieces like a box relaying an upload), read from `session.inputStream` | **Works.** All 60,000 bytes arrive, SHA-256 identical. |
| The same body with `Transfer-Encoding: chunked` | **Not supported.** The server did not understand it (no `Content-Length`, the read waited and timed out after 5 s). The box must send a known `Content-Length`; frames have a fixed overhead, so the length is known up front (the plan assumed this). |
| `session.parseBody()` on a raw `application/octet-stream` body | It returns the body as a **text** string (`postData`, 60,000 chars). Binary data is not safe through it. **The voice route must read `session.inputStream` itself** (and count bytes against the cap). |
| The sender closes after 30,000 of 60,000 bytes | The read ends at once with 30,000 bytes: the phone can see the clip is incomplete and throw it away. |
| The sender goes silent after 30,000 bytes (a Wi-Fi drop, the socket stays open) | The read fails with a `SocketTimeoutException` **after 5 s**. A stalled clip is dropped in about 5 s. |
| Long query strings (the signed envelope `payload=...&hmac=...` is in the URL today) | 8,000, 8,100, 9,000 and 20,000 characters all answered 200 (curl). **I wrongly believed there was an 8 KB header limit; there is none in this range.** |

Consequences for groups 1 to 3 (not spec changes):
- The existing envelope can be reused for a `/voice` request: the signed `payload`/`hmac` stay in the URL (so `serve()` runs its present HMAC, decrypt, freshness and replay checks before the route, `KioskHttpServer.kt:160-221`), and the **frames travel in the body**. Tested: a `POST` with a 60,000-byte octet-stream body and `?payload=...&hmac=...` in the URL reaches `serve()` with both parameters parsed (3 and 3,000 character payloads), also when the route never reads the body, and the next request is served normally. Group 3's unit test repeats this in the app's own test setup.
- The request is one signed envelope plus a body, so a repeat (tap the plane again) is a new envelope and passes the replay guard.
- The box's present sender (`sendAuthenticated`, `WebServerAuth.cpp:157-168`) only does `HTTPClient::GET` with the parameters in the URL and a 256-byte cap. The streamed body needs a separate sender in group 2 (a `WiFiClient` writing the request head and then the frames). Not changed here.

## (c) Hardware sheet for the owner (becomes RS66 onward in group 5; unticked)
What a computer cannot give. The first can be done **today**; the others need firmware that does not exist yet.
- **RS66 (now, no new software): baseline memory of the real box.** Open the box's Tools, Diagnostics (`/api/diagnostics` shows `heap.free` and `heap.largest_block`). Write down both: (a) idle, (b) right after a coin payment has been delivered to a phone, (c) while a phone is armed for coins and waiting. Also say which board it is (ESP32 or ESP32-C3). The relay will hold one small frame (a few KB) above the firmware's own floor (`Diagnostics.cpp:267` refuses at a largest block under 24,000 bytes), so these numbers say how much room there is.
- **RS67 (after group 2 is flashed): memory and money during a relay.** The same two numbers before, **during** and after a 60 KB message passes through; they must stay flat (within one frame). A coin inserted during a message is delivered and credited once. The 15 s watchdog does not fire. How long a 15 s message takes end to end.
- **RS68 (after group 4): browsers.** Android Chrome and desktop Chrome with the setting (and the box at a fixed address): hold, release, plane, send, hear it. Without the setting: the page says why. An iPhone: the page says it cannot record. The same page inside the router console's Phones tab: the page says to open it on its own.

**Decision rule, as the plan says:** if the real box's free memory drops during a relay by more than one frame, or a coin pulse or payment is delayed, **stop and ask the owner**. Until RS66 is read, the memory reserve in group 2 is the firmware's 24 KB floor plus one frame. Because there is no relay yet, the "during" half of the rule can only be judged at RS67, after groups 1 and 2 (the host tests in those groups prove the bound; RS67 proves it on the chip).

## What group 0 changes
- Spec: one added constraint (the box needs a fixed address because the Chrome setting names it) and a note that group 0's evidence is in this folder.
- Plan: group 0 ticked; the browser tests in group 4 use a secure test page plus faked blocked cases (not the Chrome setting); `scripts/tests/test_portal_voice_ui.py` is named; group 2 lists the `skip_headers` rule.
- Nothing in the money path or any shipped file was changed.
