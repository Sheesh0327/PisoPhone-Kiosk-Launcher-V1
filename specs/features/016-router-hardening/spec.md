# Feature 016: Router hardening rows from spec 01

Status: **approved** (owner, spec 02 section 8)   Branch: `feat/016-router-hardening`   Area: router   Release: router 1.5.19 (with 015 and 021)
Backlog rows: RT-10, RT-18, RT-08, RT-26, RT-22 (`specs/spec_01_findings.json`; prior work `claude/sleepy-gauss-rn31gl@74c39e2`, brought back by applying its source changes, not by rewriting)

## Intent
Five small router defects found in spec 01 and already fixed and tested on another branch (never on `beta`): the firewall tables are replaced in one step (no moment with no isolation rules), the owner console's ports are closed to the guest Wi-Fi when the router address cannot be read, the router's settings file is read by a strict JSON reader, strings in it are unescaped (a console password with a quote is no longer cut), and a backup import refuses any path with `..`. Serves rule 2 and the safety of a shop that runs every day.

## Invariants touched
- Money and time: **not touched** (no payment, ledger or window code).
- Only owner-signed software: not touched; the backup import becomes stricter (a path with `..` is refused).

## Constraints
- Branches from feature 015 (they share `util.rs`, `console.rs` and `test_setup.py`); merge 015 first. The router release 1.5.19 is raised once, by 015.
- Installer size budget: the installer is 1,639 of 1,700 KB, the installed command 144 of 150 KB. This feature adds a few lines to `setup/src/96-space-backup.sh` and `router/piso_isolation.sh`; the sizes are reported in `validation.md`. If a budget would be passed: stop and ask the owner.
- Generated files are never edited by hand: `python3 tools/build_piso_setup.py`.

## Success criteria
1. `router/tests/test_setup.py` RT-10, RT-18 and RT-22 checks pass; `cargo test` rt08 and rt26 tests pass.
2. All router gates of `CLAUDE.md` section 3 pass; sizes reported.
3. `build_piso_setup.py --check` passes; `check_security_rules.sh` passes.

## Out of scope
Everything else in spec 01 (R2 signing rows are feature 020).

## Owner decisions
None open.
