# Feature 060: A release cannot be signed around an old router program

Status: **approved 2026-11-01 (decisions 1 and 2 as recommended; decision 3, the CLAUDE.md edit, still waits for a separate yes)**   Branch: `feat/060-router-program-guard`   Area: ci (with scripts)
Backlog rows: HARDENING item 11 (`docs/HARDENING.md`), the incident in `specs/STATUS.md` ("Owner decisions open", 2026-10-29); CI-1, CI-2, CI-4 turn out to be fixed already (group 0)   Release: none (CI and owner tools only; no router, firmware or app release)

## Intent
Router programs (`tools/pisoportal/bin/*`) are built by CI **only on pushes to `beta`**, and `publish-router.yml` signs whatever is committed. Releases 1.5.25, 1.5.26 and 1.5.28 were signed that way and carried the 2026-10-24 program instead of the new code. Today the only protection is a manual step in `CLAUDE.md` section 5 ("decode the program inside the signed file and check it has the new text"). I did that by hand for 1.5.35 and it works, but it relies on whoever releases remembering it. This feature makes the machine refuse: a router release is not signed, and `verify_release.py` fails, when the program inside the setup files was not built from the source being released.
Serves rule 2 (only software the owner signed is installed: a signature on stale code is a signature on the wrong thing) and rule 1 (money code in `core.rs` could be silently missing from a release).

## Invariants touched
- Money and time: **not touched.** No router, box or app code changes; the check only reads files.
- Only owner-signed software: **strengthened.** Nothing is signed that the owner has not approved, and now also nothing is signed whose program does not match its source. Signing keys, the approval gate and the signed formats stay as they are.

## What exists today (checked 2026-11-01)
- `router-program.yml` writes `tools/pisoportal/bin/BUILD-INFO-<family>` with the commit it built from ("pisoportal 1.0.0 for mipsel ..., from commit 58083d9...").
- Nothing reads BUILD-INFO: not `verify_release.py`, not `publish-router.yml`.
- The five `setup/piso-setup*.sh` files embed the program as base64 after a `#@@B64 /usr/bin/pisoportal 755 <sha256>` line. A decode-and-compare check already worked by hand (this session): embedded program == committed `bin/` file, sha256 matches, new strings present.
- Signing already runs from a copy of the approved commit and the approval fingerprint covers the console script (CI-1, CI-2: fixed; `scripts/tests/test_workflow_signing.py` passes).

## Constraints
- A check, not a rebuild: no cross-compiling in the signing job (that is the full "reproducible build" of HARDENING item 11; see Out of scope).
- No new dependency: Python standard library and `git` only (house rule for scripts that run in CI).
- Must pass on the current `main` (program built from the current source) and must **not** need a deep git history in the signing job (`actions/checkout` there may be shallow).
- Never edit generated files by hand (`CLAUDE.md` section 4). The BUILD-INFO format may gain lines; the existing first line stays as it is.

## Success criteria
1. **Source match.** `scripts/check_router_program.py` (new) compares the source the program was built from with the source being released (`tools/pisoportal/src/`, `Cargo.toml`, `Cargo.lock`, and the embedded console files) and exits non-zero, naming the files, when they differ. It passes on current `main`.
2. **Embedded match.** For each of the five setup files it decodes the program, checks its sha256 against the `#@@B64` header and against the committed `bin/` file, and fails on any difference. It passes on current `main`.
3. **It would have caught the incident.** Run against the commit that signed router 1.5.28 (found with `git log`), the check **fails** and names the stale program. Recorded in `validation.md`. (If that history is not reachable in this clone, a reconstructed case from fixture files stands in and the validation says so.)
4. **Wired in twice.** `scripts/verify_release.py` calls it (a new FAIL line); `publish-router.yml` runs it before any signing step, and the run stops with a plain message ("the router program was built before the last change to the router source; push to beta, wait for CI's two commits, then release"). `test_workflow_signing.py` keeps passing and gains a check that the guard step comes before the signing step.
5. **Tests fail first.** `scripts/tests/test_check_router_program.py` covers: stale source, a program swapped in one family, a missing BUILD-INFO, a bad sha256 header, and a clean tree; its first run is red and is logged in `ledger/`.
6. **Documents.** `CLAUDE.md` section 5 and `docs/RELEASE.md` say the check exists and what to do when it refuses; the manual decode step stays as a second look. (`CLAUDE.md` is edited once, deliberately, because it names the rule; the byte-stable prefix rule means this goes in the same commit as the check.)
7. The backlog rows CI-1, CI-2 and CI-4 are marked `fixed` with the evidence (group 0).

## Out of scope
- A bit-for-bit reproducible rebuild in the signing job (HARDENING item 11 in full). It would need the Rust toolchain and the OpenWrt SDK image in a job that holds the signing approval, and a build that is actually reproducible; I have not tested that it is. Offered below as a decision.
- Changing who may sign, the `production-signing` approval, or the signed file formats.
- The firmware and app signing paths (they build in the same run that signs).

## Owner decisions
Answered 2026-11-01 with "Approve 060": decision 1 = refuse (not warn), decision 2 = no reproducible rebuild now. **Decision 3 was not answered separately: `CLAUDE.md` is not edited until the owner says yes.** The original questions follow.

1. **Should `publish-router.yml` refuse, or only warn?** Recommendation: refuse (the point is to stop the wrong release). A warn-only mode would be the same as the manual step we have now.
2. **Do you want the full reproducible-build check later?** Recommendation: not now; revisit after this guard has run for a few releases.
3. **May I edit `CLAUDE.md` section 5** (one sentence)? It is the project's first-bytes file; I will not touch it without your yes.
