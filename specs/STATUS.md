# Status board

Updated: 2026-10-21, against `beta` at `2d40ad3` plus the ktlint pin. Row-level detail is in `specs/spec_01_findings.json`.
"Fixed" means the test passes on a computer. Nothing below has run on a real box, phone or router.

## Spec 01: codebase refactor and correctness pass

| Package | State | What is done | Not tested / open |
|---|---|---|---|
| R1 money and time | **done in code** | Firmware FW-01, 02, 03, 05, 06 (3.11.1). App APP-01, 02, 03, 05, 06, 08, 09, 10. Router RT-01 to 07 and RT-14 (1.5.14). | App not compiled or run (no Android SDK). Firmware not compiled with PlatformIO. Hardware checks RS28 to RS32 not run. **APP-04: owner decision.** |
| R2 signing and release | **done except pinning and two owner items** | CI-1, 2, 4, 7, 9; ST-1, 3, 4; SC-1, 2, 3, 7. AC2.1 to AC2.6 checked 2026-10-21: the signing, security-rule, release-feed, firmware-publish, workflow and setup-tool tests pass; `verify_release.py` says 0 FAIL, 1 WARN (router.json older than setup/RELEASE, expected on beta). | Open: ST-6, CI-3 (pip hashes, image digests, dated nightly), CI-8 (ktlint pinned by hash; Code quality run on that commit not yet seen). **ST-2 and SC-4: owner decision.** |
| R3 reliability | **in progress** | Router 1.5.15: RT-10, RT-18, RT-08, RT-26, RT-22 fixed with tests that failed first (test_setup.py 295 checks, cargo 89, test_flow 202, all 0 failures). | Not tried on a router (RS33). Open: RT-09, RT-17 (next), then the firmware, app, setup, CI and website rows. |
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
R2 pinning (ST-6, CI-3) needs hash and digest lookups that are best done in CI. Otherwise start R3 with the router rows (RT-10, RT-18, RT-17, RT-08/26, RT-22), or wait for your answer on ST-2 and SC-4.
