Add one row to the table in `ledger/negative-constraints.md` BEFORE writing the fix:

| date | task | model | error_class | traceback_hash | constraint_violated | resolved |
|------|------|-------|-------------|----------------|---------------------|----------|
| 2026-10-21 | feat 003 FW-03 coin_record_order_test | - | expected red test | first line of the error | "queue before revenue" not yet true | yes: header added, test passes |

error_class examples: expected red test, merge conflict, missing file, environment (tool or library missing), flake (passes on re-run), logic.
traceback_hash: the first line of the error (or a short hash of it). resolved: "no" until the same check passes; prune after 7 days.
