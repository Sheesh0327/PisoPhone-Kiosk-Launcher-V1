# Feature 001: router money and time

Status: **implemented, waiting for owner review** (approved 2026-10-21, base option (a))   Branch: `feat/001-router-money-time`   Area: router
Backlog rows: RT-01, RT-02, RT-03, RT-04, RT-05, RT-06, RT-07, RT-14 (`specs/spec_01_findings.json`; Spec 01 section 6, package R1)   Release: router (raise `setup/RELEASE`)

## Intent
A shop owner's Wi-Fi customers must get exactly the time they paid for, also after a power cut or a router that starts with a wrong clock. Today the router program can delete a paid session, give free time, report a false "LEDGER WAS CHANGED", lose a voucher file, or credit a late coin zero. This serves project rule 1 (money and time are never wrong).

## State of the patches (checked 2026-10-21)
- A tested solution exists as commit `e827c98` (plus `66a4ad6` cargo fmt) on branch `claude/sleepy-gauss-rn31gl` (tip `748add9`). All eight rows have named tests there and `cargo test` passes (89 tests; 17 of them are these rows).
- It is **not on `beta`**: the new `beta` has no `clockjump`, no torn-tail handling, one `write_atomic`-style helper only and six direct `fs::rename` calls.
- It does **not apply cleanly**: a trial cherry-pick of `e827c98` onto `beta` conflicts in `tools/pisoportal/src/core.rs` and `ledger.rs`, because the patch sits on top of commit `4b6312d` ("Harden coin counter accuracy", about 100 lines of router changes in `boxlink.rs`, `console.rs`, `core.rs`, `ledger.rs`, `reconcile.rs`) that is also not on `main`. The generated setup files and the docs also conflict, which is expected (regenerated, not merged).
- Never run on a real router.

## Constraints
- Router program uses only `sha2`, `hmac` and `p256`; no new library.
- Formats unchanged except Spec 01 section 5.2 (a), (b), (c), (d): ledger row framing, the new `clockjump` record, `util::write_atomic`, voucher shift. The roll and ledger row text stay byte for byte.
- Generated files are regenerated (`python3 tools/build_piso_setup.py`), never merged by hand or edited. `tools/pisoportal/bin/*` are built by CI.
- Anything touching credit, expiry or deduction: say how it was checked.
- No change to firmware, app or protocol.

## Success criteria
1. Each of RT-01 to RT-07 and RT-14 has a test that fails on `beta` as it is today and passes after the change (ids in test names).
2. `cargo test` passes; `tools/pisoportal/tests/test_flow.py` passes against a release build, including two late-coin rounds and a torn ledger tail.
3. After the change no `fs::rename` call remains outside `util.rs`.
4. `python3 tools/build_piso_setup.py --check` and `bash scripts/check_security_rules.sh` pass; `setup/RELEASE` is raised once.
5. `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` still pass.
6. Hardware check RS32 (router clock jump with a voucher) is in `docs/REAL_WORLD_TESTING.md`, marked not run.
7. `CHANGELOG.md` says in owner terms what changed.

## Out of scope
RT-08 to RT-26 (package R3), firmware and app rows, the 1.5.13 coin-counter hardening as a feature of its own (only the parts the patch needs are taken; see plan task 1).

## Owner decisions
1. **How to deal with `4b6312d`?** (a) Port only the lines the RT patch needs from it (recommended, smallest), or (b) bring all of `4b6312d` first as feature 000, which also contains firmware and setup-program changes and so is larger and needs its own spec.
