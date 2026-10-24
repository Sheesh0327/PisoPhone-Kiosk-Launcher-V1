# Plan: 020 Sign from the approved commit

Unresolved ledger entries loaded: none.
| file | purpose | lines | est_tokens |
|---|---|---|---|
| `.github/workflows/firmware-images.yml:160-235` | the firmware publish job | 75 | 1,500 |
| `.github/workflows/publish-router.yml:40-140` | the router publish job | 100 | 2,000 |
| `scripts/sign_router.py`, `scripts/sign_console_ui.py`, `scripts/publish_firmware_ota.py` (argument parsers and defaults) | which paths are relative to what | 60 | 1,000 |
| `scripts/tests/test_workflow_signing.py` | the check | 54 | 900 |

## Task groups
- [x] 1. The test first: `test_workflow_signing.py` fails on the old workflows (4 findings), passes on the new.
- [x] 2. The two workflow changes from `f8367b2` (without CI-4 and CI-9), checked against the scripts' defaults and argument parsers.
- [x] 3. The test runs in `scripts/check_security_rules.sh`.
- [ ] 4. Open the pull request into `beta`; fix CI until green. The owner reads the first `main` run.

## Release
None.
