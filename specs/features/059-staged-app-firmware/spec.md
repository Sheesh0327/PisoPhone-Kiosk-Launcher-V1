# Feature 059: Staged rollout and a way back for phone-app and box-firmware updates

Status: **draft, waiting for the owner's approval**   Branch: `feat/059-staged-app-firmware`   Area: app, firmware, setup, ci, scripts (several: see Owner decisions 1; the groups are ordered so each can ship alone)
Backlog rows: A-F9 (`docs/HARDENING.md`, "Open risks from the audit" 10; `docs/ARCHITECTURE.md` section 11 "Rollouts")   Release: firmware, app, router (the box-update script in `setup/src/91-box-update.sh`), site

## Intent
A router update goes to 10% of routers first and the owner widens it. A phone update or a box firmware update goes to **every** device the next time it checks. The app is published **automatically** when a change reaches `main` (`build-apk.yml`: "chore: publish stable APK build N"). If one build misbehaves, every rental phone in every shop has it within hours, and nothing tells the owner. The box already rolls back a firmware that does not start; the phone has no way back at all. This feature gives the owner the same control over phones and boxes that exists for routers, and makes a failed box update visible.
Serves rule 1 (a bad phone build can stop sales everywhere at once) and the goal that the owner runs a shop without reading logs.

## Invariants touched
- Money and time: **kept, not changed.** No payment, credit, expiry or queue code changes. A staged rollout only decides **when** a device takes a new build; an old build keeps working with the new box and router (the live protocol does not change here).
- Only owner-signed software: **kept.** The APK is still verified by the phone (sha256, certificate digests, allowed URL: `KioskUpdateManager`), the firmware image still carries the owner's signature, and the router's signed manifest stays as it is. The new `rollout` number is **not** a security field (see Constraints).

## What exists today (checked 2026-11-01)
- Router: `rollout` percent inside the signed manifest; per-router bucket = first 8 hex digits of `SHA-256("pisophone-rollout|<router id>")` mod 100 (`tools/pisoportal/src/update.rs`); the owner widens by signing again.
- Box firmware: dual OTA partitions, automatic rollback (`esp32_firmware/src/OtaRollback.cpp`), per-chip signed image in `website/update/firmware.json` (fields: version, build, chips{url, sha256, size, sig}); fetched by the box dashboard and by the router (`setup/src/91-box-update.sh`). **No rollout field.**
- Phone app: `website/update/app.json` (versionCode, versionName, channel, signatureChecksum, sha256, size, releaseDate); `KioskUpdateManager.autoUpdate` runs only for a device-owner app, only when idle, with an attempt limit (`AutoUpdatePolicy.mayAttempt`). **No rollout field, no rollback.**

## Constraints
- **Old devices must keep working.** A feed without `rollout` means 100 (today's behaviour), so a new feed is harmless to old apps and old firmware, and the new app and firmware are harmless against an old feed.
- `rollout` is an integer 0 to 100 and is **unsigned** in `app.json` (which has no signature) and in `firmware.json` (whose signatures cover each image, not the whole file). Changing the signed formats is out of scope. A forged `rollout` can only make an update earlier or later, never install anything unsigned; I state this plainly because the router's rollout is signed and this one would not be.
- The bucket function is the router's, byte for byte, with test vectors shared by Rust, Kotlin and C++ (`protocol/` has the pattern: `gen_vectors.py` and `box_phone_v1.json`). The phone's key is its device id, the box's key is its own id.
- A staged value never forces a downgrade and never skips the existing checks (channel, allowed URL, certificate, signature, idle).
- Firmware: a new `PISO_FW_VERSION` is needed or boxes ignore it. App: a higher `versionCode`.
- The box <-> phone message contract does not change (`CLAUDE.md` section 6).

## Success criteria
1. **Same bucket everywhere.** Shared vectors (`protocol/`) give the same bucket in Rust, Kotlin and C++ for ids including empty, non-ASCII and 64-character ones; the three test suites read the same file.
2. **Firmware staging.** A box with bucket >= `rollout` does not take the new image (dashboard and router path), reports "waiting for its turn" in its update state, and takes it once `rollout` passes its bucket. Missing `rollout` means 100. Host test (`esp32_firmware/host_tests/`) and `scripts/tests/test_publish_firmware.py` cover it.
3. **Publishing.** `publish_firmware_ota.py` and the `firmware-images.yml` signing run take a rollout input (default 10, like the router's flow in `docs/RELEASE.md`) and a later run with a higher value widens it without changing the images; a push alone never changes a widened value.
4. **App staging.** A phone with bucket >= `rollout` does not auto-update and the owner's own "install now" still works (a deliberate tap is never staged). `KioskUpdateManager` unit tests cover: missing field, 0, 10, 100, a malformed value (treated as 100? see Owner decisions 3), and that the channel/URL/hash checks still run.
5. **App publishing.** A new stable build is published at the rollout the owner chose (Owner decisions 2) and widened by a separate, approved workflow run. `verify_release.py` reports the current app and firmware rollout and warns when a widened value is below 100 for more than 7 days ("stuck stage").
6. **A failed box update is seen.** After the router installs a firmware on its box (`91-box-update.sh`), it checks that the box comes back reporting the new version within 5 minutes; if not, the state is `failed`, the monitor sends the owner a message once, and the router does not retry that version until a newer one is published. (The box has already gone back to its old image by itself.) Test with the existing fake box (`router/tests/`).
7. **Money and time untouched:** `git diff` shows no change under the payment, credit, queue, ledger or roll code; the existing test suites for them pass unchanged.
8. **Documents:** `docs/RELEASE.md` (how to stage and widen app and firmware), `docs/ARCHITECTURE.md` section 11 (the "Rollouts" limit rewritten), `CHANGELOG.md`, and one hardware check in `docs/REAL_WORLD_TESTING.md`.

## What a device that is NOT in the current stage sees
Nothing changes for it: it keeps running the build it has, nothing is shown on the lock screen, and the box dashboard shows its update state as "waiting for its turn" (as the router console already does). A customer never sees any of this. The owner's own tap on "install now" is never blocked by staging.

## Out of scope
- Rolling a **phone** back to an older APK (see group 7 and Owner decisions 4: offered, not assumed).
- Fleet-wide health reports. The website is static and cannot receive them; "health" here is what the router can see of its own box.
- Signing `rollout` (a signed-format change for firmware and app feeds).
- Per-shop control from a phone or a web console.

## Owner decisions
1. **One feature or two?** This crosses app, firmware, setup and CI. Recommendation: keep one spec and one approval, but build and release firmware first (groups 1 to 4), then the app (groups 5 and 6), so each ships on its own.
2. **Default stage for a new app build.** Today it goes to everyone. Recommendation: publish at 10%, then widen by hand like the router. Alternative: publish at 100% as now and only gain the ability to hold. Your shops decide how much delay you accept for app fixes.
3. **A malformed `rollout`:** treat as 100 (update) or 0 (hold)? Recommendation: 0 (hold). A broken feed should stop updates, not push them.
4. **Phone rollback** (group 7): keep the previous APK on the phone and reinstall it silently when the new build crashes repeatedly in its first hour, as device owner. Recommendation: **yes, as the last group**, after the staged rollout has run for a release or two. It needs a hardware check on a real device-owner phone and I will not claim it works before then.
