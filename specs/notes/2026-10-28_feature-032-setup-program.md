# Handoff: feature 032, the setup program in a real shop (2026-10-28)

Branch `feat/032-setup-program-real-run` (from `origin/main` at `2591448`, plus the epic cherry-picked). Local commits, **not pushed**, no PR. Last code commit: `d337ba1` (group 1).
Spec and plan **approved by the owner 2026-10-28** (`specs/features/032-setup-program-real-run/`); the owner answered Q1 to Q4 as recommended (in `spec.md`). Epic: `specs/spec_03_setup_real_run.md` (D1 to D8 answered).

## Done
- Group 1, SU-18: `SetupLog` and `network_report` in `setup/pisophone_setup.py` (before `class Session`); `Session.log` and `plog` write every line to `pisophone-setup-log-<date>-<time>.txt` (0600, passwords from `Session.secrets()` written as `***`, addresses and routes first, a write failure never stops the setup). The log is listed last on the Finish page (`self.saved`) and the install error text names its path. Tests: `router/tests/test_setup_web.py` (new section "feature 032", 124 checks, 0 failures) and `test_pisophone_setup.py` (network_report, mask). Red result logged in `ledger/negative-constraints.md`. Plan box 1 ticked.
- Website copy rebuilt (`python3 tools/build_piso_setup.py`; `--check` passes); `git status` showed only `website/pisophone_setup.py` changed, no router file, so no `setup/RELEASE` raise.
- Gates for group 1: `ruff` 46 findings (baseline 47), `mypy` 8 errors (baseline 8, same ones: `serial` stubs, `make_wifi_poster`); no new ones.

## Open, in order (plan.md groups 2 to 9; 10 only if RS56 (b) fails)
2. SU-09 + SU-10 (`Core.__init__` sets the ssh password to `""` ~4426; model the router password in `FakeCore`, `test_setup_web.py:202`; refused login is exit 255 "Permission denied", message at `install()` ~2436).
3. SU-06a, 4. SU-17 + SU-21, 5. SU-04, 6. SU-01 + README "Which network?", 7. SU-02, 8. SU-15, 9. close-out (RS56 in `docs/REAL_WORLD_TESTING.md`, CHANGELOG, README, rebuild, `validation.md`, rows to `fixed` in `specs/spec_03_findings.json`, STATUS).
Test first for each, log each red result in the ledger, one commit per group. After each change to `setup/pisophone_setup.py` rebuild with `python3 tools/build_piso_setup.py` (the test "the website serves the same setup program" fails until you do).
Owner decisions waiting: none for 032. Spec 03 Q1 (supported routers, at 033) and Q2 (after RS58) are later. The feature waits for the owner's RS56 bench run before merging (D8).

## Not tested
Nothing ran on a real router, box, phone, Windows or macOS computer. `network_report` is tested only with fake commands. Playwright is not installed here, so the page in a browser was not run.

## Slow tests
Run `router/tests/test_*.py` (eight files, a few minutes) in the background and read the last lines only: `for f in test_setup test_pisophone_setup test_setup_web test_offline_setup test_setup_tools test_install test_phone_only test_setup_phone_web; do python3 router/tests/$f.py 2>&1 | tail -2; done`. Only `test_setup_web` and `test_pisophone_setup` were run for group 1: run all eight and `bash scripts/check_security_rules.sh` at group 9.
Do not push, open a PR, merge or release unless the owner asks.

## Next prompt
Use `specs/templates/session-start.md`; last line: `Task: continue from this note: specs/notes/2026-10-28_feature-032-setup-program.md`.

## Update (same day, after the owner asked to push and continue)
Branch pushed to origin. Groups 2 to 9 are done and committed; `validation.md` is filled; plan boxes 1 to 9 are ticked. Open now: the owner's RS56 bench run, then (if the owner asks) a pull request; then feature 033. The "Open, in order" list above is out of date.
