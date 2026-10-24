# Validation: 016 Router hardening rows

Rows RT-10, RT-18, RT-08, RT-26, RT-22; the source and test changes of `claude/sleepy-gauss-rn31gl@74c39e2` applied on top of feature 015. Nothing ran on a real router.

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | RT-10, RT-18, RT-22 checks in `test_setup.py`; rt08 and rt26 in `cargo test` | `python3 router/tests/test_setup.py`; `cargo test` | **pass** | test_setup 458 checks, 0 failures (451 before: 7 new checks came with the fixes); cargo test 156 passed (2 new: rt08, rt26) |
| 2 | Router gates, sizes reported | `test_pisophone_setup` 232/0, `test_setup_web` 115/0, `test_setup_tools` 78/0, `test_monitor` 37/0, `test_footprint` ok | **pass** | installer `setup/piso-setup.sh` **1,640 KB of 1,700**, installed command `piso-setup.sh.in` **145 KB of 150** (programs not rebuilt here: CI rebuilds them) |
| 3 | `build_piso_setup.py --check`, `check_security_rules.sh` | | **pass** | both clean |

## Not tested
A real router: the firewall table swap (`nft`) and the console port rules have been run only against the test fakes; the backup import against a fake root. No hardware check was added; RS44's step (a) and the isolation checks of section 9 cover a router that has this release.

## Money and time
Not touched: no payment, ledger or window code. The only code outside the shell is `util.rs` (the settings file's JSON reader) and two lines in `console.rs`.
