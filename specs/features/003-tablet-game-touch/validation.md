# Validation: tablet game touch (feature 003)

Branch `feat/003-tablet-game-touch`. Status: **code done and unit-checked; the real test is the tablet (group 6, not run).**

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Touch works in an installed game and a built-in game on the tablet | owner on the tablet | **not run** | RS check below |
| 2 | No "Untrusted touch" message for the game while unlocked | `adb logcat -d \| grep -i untrusted` on the tablet | **not run** | |
| 3 | A test fails before the fix and passes after: hidden lock window is non-obscuring and non-touchable; shown one is touchable and opaque | `LockWindowSpecUnitTest` compiled with kotlinc 2.0.21 and run with JUnit 4.13.2 on a plain JVM | pass | before (hidden window alpha 1.0, today's behaviour): `AssertionError: expected:<0.0> but was:<1.0>`, `Tests run: 2, Failures: 1`; after: `OK (2 tests)` |
| 4 | Locked state still blocks touches | the shown-case test (touchable, focusable, alpha 1) and the unchanged flags for the shown window | pass (unit level) | same run; on a device: RS check |
| 5 | Gradle unit tests and ktlint | `ktlint` 1.5.0 (sha256 checked) on the 3 changed files | ktlint pass; **Gradle tests not run** (no Android SDK here) | ktlint exit 0 |
| 6 | Hardware checks in `docs/REAL_WORLD_TESTING.md`, not run | file | added | |

## Gates
- [x] `ktlint` on the changed files
- [x] `bash scripts/check_security_rules.sh`
- [ ] `./gradlew :app:testDebugUnitTest` and `assembleDebug`: **not run** (no Android SDK on this machine; CI builds the app on the pull request or push)
- [x] No generated file touched; no Room schema, protocol or format change

## Not tested
- Anything on the tablet. The hypothesis (Android 12+ treats the hidden full-screen overlay as covering the game because the window alpha stays 1.0) is **unproven**; the unit test proves only that the window parameters are now what the hypothesis needs.
- The built-in game case, which the hypothesis does not obviously explain.
- That the app compiles with the change (CI will build it).

## Money and time
Not touched: no change to credit, expiry, deduction or the payment queue. The change only sets the lock window's own alpha (0 when hidden); flags for hidden (not touchable, not focusable) and shown (touchable, focusable) are the same as before.
