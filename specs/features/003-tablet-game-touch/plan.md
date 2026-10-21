# Plan: tablet game touch

Context manifest (rule 1): read line ranges only.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `app/src/main/java/com/pisophone/kiosk/overlay/KioskOverlay.kt` | lock screen overlay: window flags, show/hide (lines ~160-260) | 449 | ~1.5k for the range |
| `app/src/main/java/com/pisophone/kiosk/overlay/FloatingPillOverlay.kt` | the pill's window (lines ~45-95) | small | ~0.8k |
| `app/src/main/java/com/pisophone/kiosk/overlay/TimeAddedPopOver.kt` | the other overlay (lines ~30-45) | small | ~0.4k |
| `app/src/main/java/com/pisophone/kiosk/util/AppLauncher.kt`, `security/KioskPolicyManager.kt` | how games are launched and allowed | read ranges around `setLockTaskPackages` | ~2k |
| `app/src/test/**` (overlay or session tests) | where the new test goes | find with `git grep` | ~1k |

Unresolved ledger entries: none. Not readable here: Android SDK is probably missing (earlier app work could not compile).

## Task groups
- [ ] 1. **Diagnose (owner + agent).** Owner runs the commands in `spec.md` on the tablet (and once on a phone) and pastes the output. Agent reads it, confirms or replaces the hypothesis, writes the finding into `spec.md`. **No code before this.**
- [ ] 2. **Red test.** A unit/Robolectric test of the lock overlay's window parameters when hidden and when shown (or of the pure function that computes them: extract `lockWindowParams(visible)` so it can be tested without a device). It must fail on today's code (window alpha 1.0 when hidden). Log the red result in `ledger/negative-constraints.md`.
- [ ] 3. **Fix.** If the hypothesis holds: when hidden, set the window's own alpha to 0 (and keep `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`); when shown, alpha 1 as today; stop swallowing the `updateViewLayout` error silently (log and retry once). Prefer one function used by both the add path and the update path (they duplicate the flag code today). If the capture shows another cause, change the plan first.
- [ ] 4. **Locked-state test.** With time at zero the shown lock screen still blocks touches (flags/alpha as today).
- [ ] 5. **Gates and evidence.** `./gradlew :app:testDebugUnitTest`, ktlint, `check_security_rules.sh`; if the SDK is missing say "not compiled, not run". `validation.md`, hardware checks in `docs/REAL_WORLD_TESTING.md` (not run), `CHANGELOG.md` in owner terms.
- [ ] 6. **Owner try-out.** Build from CI (dev APK on `beta`) on the tablet: touch in an installed game, a built-in game, then let time run out (locked again), and the pill. Report with `adb` output if anything is wrong.

## Release
App only (the next CI build number). No firmware, router or setup change. Nothing reaches `main` unless the owner asks.
