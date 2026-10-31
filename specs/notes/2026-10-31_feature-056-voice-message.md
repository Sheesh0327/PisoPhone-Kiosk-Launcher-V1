# Handoff: feature 056, voice message (2026-10-31)

Task: owner asked for a spec, then to build it all (groups 1 to 6 of `specs/features/056-voice-message/plan.md`), asking only when unsure. Branch `ccr-6f20d511-8e6cu1`.
Done and pushed: spec approved (D1 to D9), group 0 (evidence in `evidence/group0-findings.md`), group 1 (clip format, frames, vectors; box and phone sides; Python reference).
Next: group 2 (box: streamed relay, early `ready` check, capability flag), then 3 (phone), 4 (admin page), 5 (docs, RS66 to RS68), 6 (validate).

Decisions that are not obvious from the spec:
- Frames: 1024 clip bytes per frame, each chained by its mac to the one before and to the signed request's hmac; the signed request (payload and hmac in the URL, as every box message) announces the clip size, so the body length is known (NanoHTTPD 2.3.1 has no chunked requests). Format is in the docstring of `protocol/gen_voice_vectors.py`.
- The box's `Framer` (`esp32_firmware/include/VoiceFrames.h`) encrypts in place in one 1093-byte buffer; it holds the key, the last mac and a counter only.
- The phone's voice route must read `session.inputStream` itself (not `parseBody`) and compare Content-Length with `VoiceFrameAssembler.lengthMatches`.
- Group 2 must add `VoiceClip.h`, `VoiceFrames.h`, `VoiceRelay.h` to the wifi product's `skip_headers` in `scripts/check_product_strip.py` and keep the routes inside `#if PISO_HAS_PHONES`.
- The device list is redrawn every 3 s (`portal-core-end.js:2`): the admin page catches press and release on `document`, state per slot.

Checks that work in the container: `python3 -m pytest -x protocol/test_voice_vectors.py`; `sh esp32_firmware/host_tests/run.sh` (needs `apt-get update && apt-get install libmbedtls-dev`); the pinned formatter `pip install clang-format==23.1.2`;
the Kotlin voice tests via `evidence/local-tools/`. ktlint 1.5.0 cannot be run here (the sandbox refused to execute the downloaded binary); keep lines under 140 characters and let CI check.
Not run anywhere: the ESP32 build, any phone, the Android unit tests, hardware (RS66 to RS68 are written down in `evidence/group0-findings.md`, unticked).
Open questions for the owner: none yet.
