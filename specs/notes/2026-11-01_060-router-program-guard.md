# Handoff 2026-11-01: feature 060 approved, not started; where the repository stands

Branch `claude/feature-056-package-layer-sqm-er37ac` (the session branch; it is `main` plus the spec work; merging it to `main` is for the owner to ask). Do **not** push to `beta`/`main` unless the owner says.

## Where things stand
- `main` has features 057 and 058 (router 1.5.35, signed at **0%**, `fcc0436`). `router.json` says rollout 0. The owner said they raised it to 100% but `main` showed no such commit at 2026-11-01; check `git log origin/main` and `website/update/router.json` first. RS70 and RS71 are open: nothing of 1.5.35 has run on a router.
- Specs 059, 060 and 061 are drafts in `specs/features/` (STATUS rows added). **060 is approved**; 059 and 061 wait for the owner.
- Backlog corrected: CI-1, CI-2, CI-4 are fixed on `main` (verified 2026-11-01); CI-9 not re-checked. An earlier status report that called CI-1/CI-2 open was wrong.

## Feature 060 (approved): what to build, test first (plan.md has the groups)
Owner answered by approving: refuse (not warn) in `publish-router.yml`; no reproducible rebuild now. **Not answered: the `CLAUDE.md` section 5 edit. Do not touch `CLAUDE.md` without a separate yes** (propose the one sentence in the summary instead).
1. `scripts/tests/test_check_router_program.py` first (fixture mini-repos in a temp dir): clean; source changed after build; one family's embedded program swapped; BUILD-INFO missing; bad sha256 header. See it fail, log it in `ledger/negative-constraints.md`.
2. `scripts/check_router_program.py` (standard library + git only). Decode each of the five setup files: the program sits after `#@@B64 /usr/bin/pisoportal 755 <sha256>` (base64 until the next `#@@` line); compare with the header sha256 and with `tools/pisoportal/bin/pisoportal-<family>` (mipsel is `setup/piso-setup.sh`, the others `setup/piso-setup-<family>.sh`). I did exactly this by hand on 2026-10-31 and it works (all five identical, SQM text present). For the source match: `BUILD-INFO-<family>` line 1 names the commit ("from commit <sha>"); compare `git rev-parse <commit>:tools/pisoportal/src` (and Cargo.toml, Cargo.lock) with `HEAD:...`; if the commit is not in a shallow clone, **fail with a clear message**, never pass.
3. `router-program.yml` (around lines 105 to 135): add a `source:` line (tree ids) to BUILD-INFO so no history is needed.
4. Wire into `scripts/verify_release.py` and `.github/workflows/publish-router.yml` (before any signing step); extend `scripts/tests/test_workflow_signing.py` to check the order.
5. Prove it: run the checker against the commit that signed router 1.5.28 in a throwaway worktree; it must FAIL; current `main` must pass. Record in `validation.md`.
6. Docs: `docs/RELEASE.md`, `CHANGELOG.md`; `CLAUDE.md` only with the owner's yes. Gates: `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py`, `ruff`/`mypy` on the new Python, `python3 scripts/tests/test_workflow_signing.py`.
No release is involved (CI and owner tools only).

## Things learned this session worth knowing
- Install `shellcheck` with `pip install shellcheck-py` before pushing shell changes (CI ran it; one finding cost a CI round). Python Playwright: `pip install playwright`; run browser tests with `CHROME=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome`. BusyBox cannot be installed here; CI is its first run.
- Merging to `main` for a router change: `beta` first (CI builds the programs there), then merge `beta` into `main` in a separate worktree **taking `main`'s `website/update` and `website/flash`** (beta carries CI "dev APK" entries that would replace the stable app). Sizes: installer 1,758 of 1,800 KB, installed command 173 of 175 KB, console script 31 of 33 KB.
- The GitHub MCP tools disconnect now and then; reload them with ToolSearch (`select:mcp__github__actions_get,...`). There is no `gh`.

## Slow tests
Router: `python3 router/tests/test_*.py` (about 6 minutes). Portal: `cd tools/pisoportal && cargo test && cargo build --release && python3 tests/test_flow.py` (about 2 minutes; `test_flow` needs the release build and flaked once, 1 run in about 3 per STATUS.md). Console browser tests: `tests/test_console_layout.py` and `test_console_brand.py` build a test-key program (10 minutes, set `PISO_TEST_TARGET_DIR`).

## Owner's open items (not mine to decide)
RS28 to RS31 (box money checks) first, then RS57 to RS71; 059 and 061 approvals; CLAUDE.md edit for 060; whether 1.5.35 should be at 100% before RS70/RS71.
