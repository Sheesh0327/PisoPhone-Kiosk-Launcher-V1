# Handoff: end of the long session (2026-10-21)

Written under constitution rule 5 (the chat was far past 20 turns). Start the next chat with `specs/templates/session-start.md`; last line: `Task: continue from specs/notes/2026-10-21_session-end.md`.

## On `beta` now (all merged by the owner's order)
- Feature 001: router 1.5.13, RT-01 to RT-07 and RT-14 (money and time across power cuts and a wrong clock). Not tried on a router (RS32).
- Feature 002: firmware 3.11.0 (product images, USB Wi-Fi setup, coin-counter hardening) + ST-1 + the missing `embed_web.py` generator + product-strip check in Code quality. Not tried on a box. Merge commit `60c8eaf`.
- The SDD workflow: `constitution.md` 1.2.0, `specs/` (GUIDE, templates, features, STATUS, RELEASE_STATE), `.claude/skills/sdd-*`, `scripts/cache_report.py`.
- CI on `beta` at `60c8eaf`: Code quality green after one re-run (the first failure was a connection reset in `test_flow.py`, a flake); firmware images, router program and APK build green.

## Open
- **PR #96 (feature 003, APP-27, tablet game touch)** is open and **not merged**. Its app build ("Unit tests", "Android Lint" in run 493) and the security scan were still running at the end; everything else was green or skipped. The merge was ordered "if CI is green", so it was not done. Check the two jobs; if green, merge into `beta`.
- Feature 003's cause is a **hypothesis** (hidden lock window kept window alpha 1.0; Android 12+ blocks touches to other apps' windows under it). The fix is only proven at unit level. **Owner must run hardware check RS33 on the tablet** with the dev APK and `adb` (commands in `specs/features/003-tablet-game-touch/spec.md`). If touch is still dead, the adb output decides the next spec edit. Built-in games are not explained by the hypothesis.
- Not compiled anywhere yet: the PisoPhone-only and PisoWiFi-only environments passed in the firmware images workflow; the app change in #96 waits on run 493.
- Owner decisions still open: ST-2 (signed flash manifest), SC-4 (`scripts/apk_production_cert.txt` empty), ST-8, DEAD-1, APP-04, roadmap 0.1 (legacy boxes), tablet model and Android version for feature 003.
- `specs/RELEASE_STATE.md` is stale (still says router 1.5.12, firmware 3.9.0 and is dated 2026-10-18); refresh it when the next release goes out.
- Feature 004 candidates: firmware money rows FW-01, 02, 03, 05, 06 (firmware 3.11.1, tested code on branch `claude/sleepy-gauss-rn31gl`); the rest of R1 app rows (needs the Android SDK); R2 pinning (ST-6, CI-3, CI-8). Do a trial apply first (the `sdd-plan` skill says how).
- Keep branch `claude/sleepy-gauss-rn31gl` (tip `748add9`): it still holds the tested firmware and app fixes not yet brought back.

## Facts that cost time
- Code quality only runs on pull requests into `beta`/`main` and on pushes to them, not on `feat/*` pushes. A push to `beta` cancels a running one.
- The router setup test (`test_setup.py`) takes minutes: run it in the background. Host tests need `libmbedtls-dev`; format with `clang-format==23.1.2`.
- No Android SDK in the cloud session: the app cannot be compiled or unit-tested there. CI's "Build and Deploy APK" does it.
- The hit rate of the prompt cache in this long chat was 99.5%; the cost came from chat length (see `specs/GUIDE.md` part B).
