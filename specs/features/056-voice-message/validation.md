# Validation: 056 voice message from the box's admin page to a rented phone

Nothing is built yet. Every line below is "not run" until its task group is done (`plan.md`).

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Hold and release: recording, paper plane, 15.0 s stop, accident rule, cross | browser test (Playwright, fake microphone) | not run | |
| 2 | A refresh of the phone list never disturbs a recording or a waiting clip | browser test | not run | |
| 3 | Which rows get a mic, a greyed mic, or none; nothing in the PisoWiFi-only image | browser test; `python3 scripts/check_product_strip.py` | not run | |
| 4 | Blocked microphone: plain message, nothing recorded (three cases) | browser test with the cases faked | not run | |
| 5 | Wire format and frames: page encoder, Python reference and phone decoder agree; bad clips and bad frame sets refused | `protocol/` vectors; `voice_clip_test.cpp`; Kotlin unit test; browser test | not run | |
| 6 | Security: unsigned, stale, replayed and mis-sized messages play nothing; signed-out, foreign-site and unknown-address requests refused | host tests; Kotlin unit tests | not run | |
| 7 | Relay, streamed: byte-for-byte delivery; never more than one frame held (longest clip); busy, low memory, unreachable phone and a stalled or dropped phone each give their own error; buffer freed; nothing stored | host tests with a fake phone | not run | |
| 8 | Phone playback: plays once with the banner, replaces, waits for the countdown, deletes the clip, leaves time and session alone | Robolectric unit tests | not run | |
| 9 | Money and time: existing coin, payment, protocol and session tests unchanged and green; relay refuses before its reserve is touched | `esp32_firmware/host_tests/run.sh`; `./gradlew testDebugUnitTest` | not run | |
| 10 | Capability flag; no new manifest permission; versions raised; generated files current; security rules pass | manifest diff; `embed_web.py` check; `bash scripts/check_security_rules.sh` | not run | |
| 11 | Hardware: real box, phone and browser (RS66 onward) | owner's bench run | not run | |

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md` (firmware, app, web)
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check`, `mypy`, `pytest -x` on `protocol/gen_vectors.py` and any other touched script
- [ ] Generated files regenerated, `--check` passes

## Not tested
Not filled yet. Expected: no ESP32 build (no PlatformIO), no phone, no real browser on a shop LAN, possibly no Android SDK. Hardware checks RS66 onward go into `docs/REAL_WORLD_TESTING.md`.

## Money and time
Not touched by design (no credit, expiry, deduction or payment-queue code changes). Proof to fill in: the unchanged host and unit tests, and the relay's refusal before its memory reserve (criterion 9).
