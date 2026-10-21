# Negative constraints ledger

Failures seen while working, logged before any fix is attempted (constitution rule 6). Prune entries older than 7 days or once resolved.
Earlier entries from the first run are kept on branch `claude/sleepy-gauss-rn31gl` (`ledger/negative-constraints.md`).

| date | task | model | error_class | traceback_hash | constraint_violated | resolved |
|------|------|-------|-------------|----------------|---------------------|----------|
| 2026-10-21 | feat 001 port of e827c98 | - | merge conflict | - | patch assumed 4b6312d (3 hunks in core.rs, ledger.rs) | yes: patch side taken, nothing needed from 4b6312d; builds and 87 tests pass |
| 2026-10-21 | feat 002 group 4 (6c6e008) | - | missing file in old work | AttributeError | `scripts/check_product_strip.py` calls `embed_web.assets_for`, which does not exist: `scripts/embed_web.py` was never committed with 6c6e008; running the old generator overwrote the product-aware `WebAssets.h` | fixing in feature 002 |
