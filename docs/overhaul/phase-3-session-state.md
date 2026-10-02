# Phase 3: Android session state machine

Android only; no firmware change. Install the APK from this branch's `/update` folder.

## What changed
- New `SessionState` (named states) and `SessionRules` (pure transition functions) in `service/SessionRules.kt`. Every place that used to read or write the raw numbers 0 to 3 for the paid session now calls it: the engine, the ESP32 and server coordinators, the supervisor tick, state restore, the overlays and the coin-sync loop.
- The number is still what is sent to the ESP32 in heartbeats and saved across reboots, so nothing on the wire or on disk changed.
- `SessionRulesTest` checks every transition. Most tests restate the old inline logic literally and compare it for every state, which is what makes this behaviour-preserving. Others name the Phase 1 money bugs (a coin must not relock an unlocked phone, admin time must not drop an armed slot, expiry must not lose a coin being inserted).

Behaviour is intended to be identical to the previous build. Unit tests run in CI; the app itself cannot be run here.

## Hardware test (full rental flow)
1. Locked phone: press Insert Coin, insert a coin: time is added and the phone unlocks.
2. While unlocked, press ADD TIME and insert another coin: time is added and the phone stays unlocked.
3. Press ADD TIME, then leave the slot alone until the arming countdown ends: the phone stays unlocked with the time it had.
4. Let paid time run out while ADD TIME is armed: the phone locks and the slot stays armed; a coin still counts.
5. Admin: add time on a locked phone (unlocks), deduct to zero (locks), lock session, admin bypass.
6. Make a second phone press Insert Coin while the first is paying: it shows the busy message and its state does not change.
7. Reboot the phone mid-session: remaining time returns and the screen state matches (unlocked, or unlocked with the slot armed).

## Rollback
Revert the Phase 3 commit; no data format changed.
