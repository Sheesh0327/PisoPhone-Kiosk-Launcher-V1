# Negative constraints ledger

Failures seen while working, logged before any fix is attempted (constitution rule 6). Prune entries older than 7 days or once resolved.
Earlier entries from the first run are kept on branch `claude/sleepy-gauss-rn31gl` (`ledger/negative-constraints.md`).

| date | task | model | error_class | traceback_hash | constraint_violated | resolved |
|------|------|-------|-------------|----------------|---------------------|----------|
| 2026-10-21 | feat 003 LockWindowSpecUnitTest | - | expected red test | AssertionError expected:<0.0> but was:<1.0> | hidden lock window kept window alpha 1.0 (only the view was made invisible) | yes: window alpha 0 when hidden; OK (2 tests); not yet confirmed on the tablet |
