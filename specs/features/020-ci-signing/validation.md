# Validation: 020 Sign from the approved commit

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | The test passes on the new workflows, fails on the old | `python3 scripts/tests/test_workflow_signing.py` | **pass** | red on `beta`'s files: 4 findings (`firmware-images.yml:204`, `publish-router.yml:101, 111, 117`); green on the changed files: "ok: 8 workflows, no signing step follows a git reset --hard" |
| 2 | Part of the security rules | `sh scripts/check_security_rules.sh` | **pass** | `security rules: all passed` |
| 3 | YAML parses; script arguments exist | `yaml.safe_load` of both; `--setup`, `--out-dir`, `--src`, `--key`, `--product`, `--images` found in the parsers | **pass** | |
| 4 | Code quality green | the PR's checks | see the PR | |

## Not tested
**The workflows themselves.** They run only on `main` (after the owner's approval in `production-signing`) and on `beta` (without secrets) and cannot be run in a cloud session: the worktree step, the "built from this commit" check (the manifest's `commit` field is written by `firmware-images.yml` line 97 as `$GITHUB_SHA`) and the signing calls have never run. The first run on `main` after the merge is the test; read it.

## Money and time
Not touched.
