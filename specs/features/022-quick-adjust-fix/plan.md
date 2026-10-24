# Plan: quick add / subtract time (feature 022)

Context manifest (constitution rule 1). Unresolved ledger entries loaded: none relevant.

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `esp32_firmware/include/OriginCheck.h` | the guard's rule | 1 to 50 | 700 |
| `esp32_firmware/src/WebServerAuth.cpp` | `crossSiteWrite`, `checkAdminAuth` | 360 to 395, 505 to 509 | 450 |
| `esp32_firmware/src/WebServerApi.cpp` | `handleAddTime` | the function (about 45 lines) | 600 |
| `esp32_firmware/src/DeviceNetwork.cpp` | `sendAddTime` | 27 to 60 | 500 |
| `esp32_firmware/include/WebDashboardTemplate.h` | the form | 86 to 110 | 400 |
| `tools/pisoportal/src/boxview.rs` | the router's box view headers | 53 to 70, 100 to 110 | 400 |
| `esp32_firmware/host_tests/origin_check_test.cpp` | the guard's tests | to read in group 1 (`grep -n`) | tbd |

## Task groups
- [ ] 1. **Get the facts.** The owner's answers 1 and 2; capture the Origin / Referer / Host of the failing press; name candidate A, B or C
  in `validation.md`. If it is none of them, stop and change this spec. No fix before this.
- [ ] 2. **Test first.** Add the captured case to `origin_check_test.cpp` (red: refused today) and keep every attack case; run with the
  sanitizers. (red result logged: no)
- [ ] 3. **The fix, in the narrowest place.** One of: the form posted by script as the other dashboard changes are (so the browser sends the
  same headers), or a rule in `OriginCheck.h` for the exact spelling found. The guard stays strict for everything else.
- [ ] 4. **Truthful status.** "Sent +N min to ..." immediately; per-phone "done" / "did not answer" from the real outcome; unpaired stays
  "Not changed". (files: `WebServerApi.cpp`, `WebDashboardTemplate.h`; regenerate `WebAssets.h` with `scripts/embed_web.py`)
- [ ] 5. Checks (clang-format 23.1.2, host tests with sanitizers, `check_security_rules.sh`), RS47, `PISO_FW_VERSION` 3.12.2,
  `CHANGELOG.md`, `validation.md` with evidence, STATUS.

## Release
Firmware 3.12.2 (signed by CI after it reaches `main`; the owner decides the rollout). The router and the app are not touched
unless candidate A needs the box view changed (then a router release is a new decision for the owner).
