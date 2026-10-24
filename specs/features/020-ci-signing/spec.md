# Feature 020: Sign from the approved commit (CI-1, CI-2)

Status: **approved** (owner, spec 02 section 8, 2026-10-24)   Branch: `feat/020-ci-signing`   Area: ci   Release: none (takes effect at the next run of the two publish workflows on `main`)
Backlog rows: CI-1, CI-2 (`specs/spec_01_findings.json`; prior work `claude/sleepy-gauss-rn31gl@f8367b2`, its two workflow changes and its test, brought back without CI-4 and CI-9 which are not in this feature)

## Intent
After the owner approves a release in the protected `production-signing` Environment, the signing loop moves its working tree to the
newest commit on `main` (`git reset --hard origin/main`) and then runs the signing scripts from there. A commit pushed after the approval
could therefore change the script that signs. The signing scripts must run from a copy of the approved commit (`$GITHUB_SHA`), and the
router approval must cover every file that is signed or that decides what is signed. Serves rule 2: only software the owner signed is installed.

## Invariants touched
- Money and time: not touched.
- **Only owner-signed software: touched.** Nothing about the keys, their storage or the signature format changes. What changes: where the signing and publishing scripts are read from, and what the router approval fingerprint covers (now also `console-ui.js`, `sign_console_ui.py` and the workflow file).

## Constraints
- **Workflows cannot be run in a cloud session.** These two workflows run only on `main` (with the owner's approval, and `development` on `beta` without secrets), so this pull request's CI does not exercise them. The change was read against the scripts' own defaults: `publish_firmware_ota.py` writes under the *current directory* (`website/update`), so running it from the trusted copy still writes into the branch; `sign_router.py` and `sign_console_ui.py` default their output to their own tree, so the workflow now passes `--setup/--src` and `--out-dir` explicitly.
- No key, secret or Environment setting is read, written or changed by this feature.
- `cryptography` is pinned (44.0.0) in the two signing jobs; the other downloads of these workflows are unchanged.
- **The first run on `main` after this is merged is the real test.** The owner should read that run (the approval, the "trusted" worktree line, the signed files) before relying on it; if it fails, the release is blocked, not weakened.

## Success criteria
1. `python3 scripts/tests/test_workflow_signing.py` passes on the changed workflows and **fails on the old ones** (red first).
2. The test is part of `scripts/check_security_rules.sh` (so it runs in the always-on "Security rules" job).
3. YAML of both workflows parses; the arguments passed to the signing scripts exist in their argument parsers.
4. CI "Code quality" green.

## Out of scope
CI-4 (read-only test jobs in `build-apk.yml`) and CI-9 (a missing apksigner fails the run); every other R2 row; any change to keys or to `production-signing`.

## Owner decisions
Answered in spec 02 section 8 (CI-1, CI-2 as feature 020).
