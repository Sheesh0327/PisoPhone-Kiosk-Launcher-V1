# Feature 018: Phone app money rows

Status: **approved** (owner, spec 02 section 8, 2026-10-24)   Branch: `feat/018-app-money-rows`   Area: app
Backlog rows: APP-01, 02, 03, 05, 06, 08, 09, 10 (`specs/spec_01_findings.json`, prior work `claude/sleepy-gauss-rn31gl@748add9`, commit `b6c3c38`); AO-08 (`specs/spec_02_findings.json`). APP-04 stays an owner decision.   Release: app (a build only when the owner releases)

## Intent
The phone must credit every coin exactly once and lock when time is up, for months without a restart. Eight money and time defects
in the phone app were fixed and tested on another branch but never compiled. This feature brings that work back by cherry-picking
(not rewriting) and adds one measurement, AO-08: the number of rows in `payment_receipts` is written to the log once an hour, so the
owner can see how fast it grows (decision D4: measure, no pruning).

## Invariants touched
- Money and time: **touched, carefully.** APP-02/08 remove a recovery step that turned an orphan coin event into a receipt of 0 pesos
  (a coin event the phone never credited could become "already applied"); APP-05 forgets the transaction id when the box did not take
  the ack, so the coin is offered again and answers ALREADY_APPLIED (no second credit); APP-10 stops a throwing callback from
  turning a committed credit into FAILED; APP-06 makes a missing amount count 0 pesos (complimentary), as the signed path already does;
  APP-01 locks an unlocked phone with no deadline and no seconds held; APP-09 makes the arming countdown atomic; APP-03 gives the
  HTTP health probe its own lock so it cannot hold up the Done button.
- Only owner-signed software: not touched.

## Constraints
- **No Android SDK in a cloud session: nothing here has been compiled or run locally. The pull request's CI is the compile and test gate.**
- `app/schemas/*` must not change (no entity or database change; `countReceipts()` is a query only).
- AO-08 only measures: no pruning, no new screen, no change to credit logic.
- No release, no APK publish: the owner does that.

## Success criteria
1. The cherry-picked unit tests (`PaymentRepositoryUnitTest`, `KioskHttpServerUnitTest`, `SessionRulesTest`) pass in CI.
2. New `HealthSnapshotTest` passes: the log line names the receipt count and is produced at most once an hour.
3. `app/schemas/*` unchanged (`git diff beta -- app/schemas` empty).
4. CI "Code quality" (ktlint, detekt, unit tests) green on the head commit.
5. `bash scripts/check_security_rules.sh` passes.

## Out of scope
APP-04 (refuse coins until restore: owner decision), APP-21, 13 to 15 (one update download, one Wi-Fi join at a time, less polling: a later app feature), pruning `payment_receipts`, any phone UI.

## Owner decisions
D4 answered: no pruning, measure only. APP-04 stays open.
