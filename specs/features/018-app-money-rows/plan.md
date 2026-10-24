# Plan: 018 Phone app money rows

Unresolved ledger entries loaded: none for the app.
| file | purpose | lines | est_tokens |
|---|---|---|---|
| `git show b6c3c38` | the prior work (9 files, +110 -31) | 140 | 2,500 |
| `service/KioskEngine.kt:733-800` | health monitor loop (AO-08 hook) | 70 | 1,200 |
| `db/PaymentDao.kt` | add `countReceipts()` | 25 | 400 |

## Task groups
- [ ] 1. Cherry-pick `b6c3c38` (APP-01, 02, 03, 05, 06, 08, 09, 10 with their tests); applies cleanly to `beta` at `c5c8837`. Tests came with it (written before the fix on that branch; **not compiled or run there**: CI is the gate).
- [ ] 2. AO-08: `PaymentDao.countReceipts()`, `PaymentRepository.receiptCount()`, `service/HealthSnapshot.kt` (pure line and due-check), a once-an-hour log line in the health monitor, `HealthSnapshotTest`.
- [ ] 3. Open the pull request into `beta`; read CI; fix every red check until green.

## Release
None by the agent. When the owner releases, the app build number is raised by CI's publish job.
