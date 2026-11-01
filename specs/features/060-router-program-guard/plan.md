# Plan: 060 a release cannot be signed around an old router program

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none for ci/scripts.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| scripts/verify_release.py | add the new FAIL line (grep the router section) | ~400 | read ranges only |
| .github/workflows/publish-router.yml | where the guard runs before the signing steps (lines 40 to 120) | ~130 | 2.5k |
| .github/workflows/router-program.yml | BUILD-INFO writer (lines 105 to 135): add a source-tree line | ~140 | 2k |
| scripts/tests/test_workflow_signing.py | order check (guard before signing) | 54 | 1k |
| tools/build_piso_setup.py | the `#@@B64` format (lines 40 to 70) | ~80 | 1.5k |
| docs/RELEASE.md, CLAUDE.md section 5 | the rule in words | ~20 lines | 1k |

Never opened: `setup/piso-setup*.sh`, `tools/pisoportal/bin/*` (decoded by the script only).

## Task groups
Small, ordered, each finished and checkable on its own. Test first.

- [x] 0. **Correct the backlog.** Mark CI-1, CI-2 and CI-4 `fixed` in `specs/spec_01_findings.json` with the evidence (the workflows sign from `$GITHUB_SHA`, the fingerprint covers `console-ui.js`, test jobs are `contents: read`, `test_workflow_signing.py` passes); say CI-9 was not re-checked. (files: findings JSON, STATUS; test: `python3 scripts/tests/test_workflow_signing.py`; no red result: documentation)
- [x] 1. **Tests first for the checker** (`scripts/tests/test_check_router_program.py`, fixture mini-repos built in a temp dir): clean; source changed after build; one family's embedded program swapped; BUILD-INFO missing; sha256 header wrong. Run it red (the script does not exist). (red result logged: yes)
- [x] 2. **`scripts/check_router_program.py`**: (a) decode and compare the five embedded programs with `bin/` and their headers; (b) compare the source the program was built from with the tree being released, using the new BUILD-INFO line when present and otherwise the commit named on the first line (`git rev-parse <commit>:tools/pisoportal/src` against `HEAD:...`; if the commit is not in a shallow clone, say so plainly and exit non-zero rather than pass). (files: new script; test: group 1; red result logged: yes)
- [x] 3. **BUILD-INFO gets a source line.** `router-program.yml` adds `source: <tree ids of src, Cargo.toml, Cargo.lock>` so the check needs no history. (Takes effect at the next beta build; until then the check uses the commit line.) (files: `router-program.yml`; test: the group 1 fixtures with and without the line)
- [x] 4. **Wire it in.** `verify_release.py` calls the checker; `publish-router.yml` runs it before the signing steps; `test_workflow_signing.py` checks the order. (files: both; test: the three test scripts; red result logged: yes for the order check)
- [x] 5. **Prove it on history.** Run the checker against the commit that signed 1.5.28 in a throwaway worktree and record the FAIL (and that current `main` passes) in `validation.md`. (no code; evidence)
- [~] 6. **Documents.** `docs/RELEASE.md`, `CLAUDE.md` section 5 (only with the owner's yes), `CHANGELOG.md` (a line for the owner: "a router release can no longer be signed with an old program"). Gates: `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py`, `ruff` and `mypy` on the new Python.

Group 6 note: `docs/RELEASE.md`, `CHANGELOG.md` and `docs/HARDENING.md` are done; the `CLAUDE.md` section 5 sentence waits for the owner's separate yes (proposed wording in `validation.md`). One addition the plan did not list: `quality.yml` now runs `test_workflow_signing.py`, `test_check_router_program.py` and `test_verify_release.py` (none of the three ran in CI before).

## Release
None. CI and owner tools only: no `setup/RELEASE`, firmware or app change. Merge path for a ci/scripts change per `CLAUDE.md` section 5 (feature branch, green Code quality on the exact commit); `router-program.yml` runs only on `beta`, so the BUILD-INFO line first appears after the next router change goes through `beta`. Update `specs/STATUS.md` when merged.
