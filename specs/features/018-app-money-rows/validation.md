# Validation: 018 Phone app money rows

Rows: APP-01, 02, 03, 05, 06, 08, 09, 10 (cherry-picked from `claude/sleepy-gauss-rn31gl`, commit `b6c3c38`) and AO-08.

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | The cherry-picked unit tests pass | CI: `Build and Deploy APK to Website` runs `testDebugUnitTest` and builds the app | **pass in CI** | the CI job built and published dev APK builds 523 and 524 from this branch's commits, which only happens after the unit tests pass (see the run on the PR) |
| 2 | `HealthSnapshotTest` passes | same job | **pass in CI** | tests: line text, once an hour, a clock that went back, `logIfDue` once then quiet |
| 3 | `app/schemas/*` unchanged | `git diff origin/beta -- app/schemas` | **pass** | empty |
| 4 | Code quality green on the head commit | the PR's `Code quality` run | see the PR | first run failed on ktlint (condition-wrapping) and detekt (LongMethod x2, LargeClass); fixed, see below |
| 5 | `check_security_rules.sh` | local | **pass** | `security rules: all passed` |

## What CI found, and what changed
The first Code quality run failed in 30 seconds: ktlint `condition-wrapping` in `KioskSessionSupervisor`; detekt `LongMethod` for `KioskSessionSupervisor.start` and `PaymentRepository.creditPayment`, and `LargeClass` for `KioskEngine` (the engine was at the limit; the APP-03 lock plus my log line pushed it over). Fixed by moving the APP-01 expiry decision into `lockDue` (supervisor companion) and the APP-10 callback guard into `notifyApplied`, and by moving the hourly receipt-count log out of `KioskEngine` to where the watchdog's health check reaches `KioskService`. The CI's own ktlint 1.5.0 and detekt 1.23.8 jars (same checksums) were then downloaded and run locally: both clean.

## Not tested
- Nothing was run on a phone, and no hardware check was added: the app money rows are owed on a real phone with a real box (insert a coin and let the time run out; kill the app during a credit; a box that does not take the ack).
- The Gradle build and unit tests were not run locally (no Android SDK); CI is the gate.
- APP-04 (refuse coins until restore) stays an owner decision; APP-21, 13 to 15 are a later app feature.

## Money and time
Each fix is listed in the spec with the failure it closes; the unit tests that came with them pin them (credited once, ALREADY_APPLIED on a repeat, a missing amount is 0 pesos, lock at zero with nothing held, the atomic arming countdown). AO-08 only reads a count and logs it. No credit, expiry or deduction logic was added in this feature beyond the cherry-picked fixes.
