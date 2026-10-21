# Feature 003: touch works inside games on tablets after time is granted

Status: **draft** (waiting for the owner)   Branch: `feat/003-tablet-game-touch`   Area: app
Backlog rows: APP-27 (new, `specs/spec_01_findings.json`)   Release: app (next build number)

## Intent
A customer on a tablet inserts coins, gets time, opens a game and **cannot touch it**: the home screen answers, the floating pill answers, the game does not. The customer has paid and cannot use the time. This serves project rule 1 (a paid-for minute must be usable), without weakening the lock when no time is left.

## What the owner reported (2026-10-21)
- Tablet, after coins and time granted. The PisoPhone home screen is responsive. Opening **a game**, whether an installed app or a game built into PisoPhone, leaves the game dead to touch. The floating pill still works. **Every time.**
- Must stay impossible: touches reaching apps while locked; any app other than the allowed games opening; the pill must behave as now; phones must be unchanged.
- The owner has the tablet and can run `adb`.

## Working hypothesis (not yet proven; task group 1 proves or kills it)
- `KioskOverlay.kt` (the lock screen, `TYPE_APPLICATION_OVERLAY`, full screen) hides itself when time is granted by `view.alpha = 0f` and the flags `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` (lines ~190-200 and ~236-246). It never sets the **window's** alpha (`layoutParams.alpha` stays 1.0).
- From Android 12 (the app targets API 36) the system blocks touches to a window that is covered by another app's overlay window it considers untrusted and opaque (more than 80% by default), judged on the **window** alpha, not the view's. Our own activity (the home screen) and the touchable pill are exempt; a game from another app, drawn under a full-screen invisible-by-view-only overlay, is not.
- This fits every symptom (home fine, games dead, pill fine, every time, tablet; phones may be on an older Android or differently set).
- Weak spot: it does not obviously explain a game built into PisoPhone (same app, normally exempt). Group 1 must say whether those are really dead, and why.
- Other causes to rule out in group 1: another overlay left touchable (`TimeAddedPopOver`, the arena), a lock-task or device-owner setting on the tablet, a wrong `isLockScreenShown` state, and the swallowed exception in `updateWindowFlagsAndDimensions` that could leave the old flags in place.

## Invariants touched
- Money and time: not touched (no change to credit, expiry, deduction or the payment queue). The fix is to the touch path only; say so in the PR.
- Only owner-signed software: not touched (no OTA or signing change).

## Constraints
1. **Locked means locked**: with no time left the lock screen must block every touch, as today. A test must show that.
2. Only the allowed games and apps open (the lock-task allow list is unchanged).
3. The floating pill (force close, time, admin entry) behaves exactly as today.
4. Phones: behaviour unchanged on phones. If the fix is a window property, it must be harmless on older Android versions (minSdk 26).
5. No change to Room schemas (`app/schemas/*`), the box protocol, or any stored format.
6. No new permission and no new library (`specs/techstack.md`).

## Success criteria
1. On the owner's tablet, with time granted, **touch works in an installed game and in a built-in game**, checked by hand and by an `adb` capture (below). Hardware check, owner runs it.
2. `adb logcat` shows no "Untrusted touch" / occlusion message for the game while unlocked (or the capture names the real cause, and the fix addresses that cause).
3. A unit or Robolectric test fails before the fix and passes after: when the lock screen is hidden, its window parameters make it **non-obscuring and non-touchable** (window alpha 0 or the window not present), and when shown, touchable and opaque as today.
4. Locked state test: with the lock screen shown, window flags still block touches (no regression).
5. `./gradlew :app:testDebugUnitTest` and `ktlint` pass (needs the Android SDK; say so if not run).
6. Hardware checks added to `docs/REAL_WORLD_TESTING.md` and marked not run: touch in a game after time is granted; touch is blocked again when time runs out; pill unaffected; a phone is unaffected.

## Out of scope
Changing how time is granted or deducted; the lock screen's look; new games; the box or router.

## Owner decisions
1. **Evidence first?** Recommended: yes. The first task is a diagnosis on your tablet (commands below) before any fix, because the hypothesis does not explain the built-in game. If the capture shows another cause, this spec is edited before code.
2. **Tablet details** (needed for the spec, not blocking the diagnosis): tablet model and Android version (`adb shell getprop ro.build.version.release`, `ro.product.model`).

## Diagnosis commands for the owner (task group 1; run with a game open and touch dead)
```
adb logcat -c
# open the game, tap it a few times, then:
adb logcat -d | grep -i -E "untrusted|obscur|occlu|InputDispatcher.*(block|drop)"
adb shell dumpsys window windows | grep -E -A12 "pisophone.*(Overlay|Window)"    # alpha, flags of each PisoPhone window
adb shell dumpsys input | grep -i -E "obscur|untrusted|block"
adb shell settings get global block_untrusted_touches                              # 0 off, 1 permissive, 2 block (default)
adb shell getprop ro.build.version.release
```
Paste the output (trim device ids). For comparison run the same once on a phone where games work.
