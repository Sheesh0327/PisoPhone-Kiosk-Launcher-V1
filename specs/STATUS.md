# Status board

Updated: 2026-10-21, against `beta` at `2d40ad3`. Row-level detail is in `specs/spec_01_findings.json`.
"Fixed" means the test passes on a computer. Nothing below has run on a real box, phone or router.

## Spec 01: codebase refactor and correctness pass

| Package | State | What is done | Not tested / open |
|---|---|---|---|
| R1 money and time | **done in code** | Firmware FW-01, 02, 03, 05, 06 (3.11.1). App APP-01, 02, 03, 05, 06, 08, 09, 10. Router RT-01 to 07 and RT-14 (1.5.14). | App not compiled or run (no Android SDK). Firmware not compiled with PlatformIO. Hardware checks RS28 to RS32 not run. **APP-04: owner decision.** |
| R2 signing and release | **mostly done** | CI-1, 2, 4, 9; ST-1; SC-1; SC-3. `test_workflow_signing.py` (AC2.4) and `test_security_rules.py` pass on beta. | SC-4 waits for the owner to fill `scripts/apk_production_cert.txt`. Open: SC-2, SC-7, CI-3, CI-7, CI-8, ST-3, ST-4, ST-6. **ST-2: owner decision.** Not yet confirmed: AC2.2 and AC2.5 tests, `verify_release.py` 0 FAIL. |
| R3 reliability | open | | 22 rows |
| R4 dead and sloppy code | open | | 29 rows. DEAD-1 and ST-8 wait for the owner. |
| R5 overengineering | open | | Needs owner approval per item |
| R6 efficiency | open | | Includes owner decisions (repo size, Gradle) |

## Roadmap (`specs/roadmap.md`)

| Phase | State |
|---|---|
| 0 decisions | 0.1 open: are any boxes still in legacy mode? |
| 1, 2 hardware checks | owed, not run |
| 3 money correctness | overlaps Spec 01 R1; items 3.1 to 3.4 still to be mapped to rows |
| 4 to 8 | not started. 7.1 is blocked outside this repo. |

## Next step
Resume R2: confirm AC2.2, AC2.3, AC2.5, then close the open R2 rows one small change at a time.
