# Plan: quick add / subtract time (feature 022)

Context manifest (constitution rule 1). Unresolved ledger entries loaded: none relevant.

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `esp32_firmware/src/WebServerApi.cpp` | `handleAddTime` | 1 to 45 of the function | 600 |
| `esp32_firmware/src/DeviceNetwork.cpp` | `sendAddTime`, the blocking calls | 27 to 60 | 500 |
| `esp32_firmware/include/WebDashboardTemplate.h` | the form | 86 to 110 | 400 |
| `esp32_firmware/web/portal-modals-phones.js` | the inactive-phone check | 185 to 215 | 450 |
| `esp32_firmware/src/WebServerAuth.cpp` | `redirectHome` | 505 to 509 | 60 |

## Task groups
- [ ] 1. **Find the cause (no fix yet).** Read `sendAuthenticated`, the worker queue (feature 017) and the watchdog budget; get the
  owner's answers 1 to 3; write down which of H1/H2/H3 it is in `validation.md`. If it is not H1, stop and change this spec.
- [ ] 2. **Test first.** A pure header `AdjustPlan.h`: how long a request is held for N phones, which phones get which id, what the
  status line says for each outcome. Host test `adjust_plan_test.cpp`: red today (16 s for 8 phones), then green. (red result logged: no)
- [ ] 3. **Move the sending off the request.** `handleAddTime` validates, makes the ids, queues the sends on the existing worker and
  answers at once; the worker sends and records the outcome per phone. (files: `WebServerApi.cpp`, `DeviceNetwork.cpp`)
- [ ] 4. **Truthful status.** The "Changed" note is written only from a recorded outcome; the dashboard shows "Sending..." then
  the result. (files: `WebServerApi.cpp`, `WebDashboardTemplate.h`; regenerate `WebAssets.h` with `scripts/embed_web.py`)
- [ ] 5. Run the firmware checks (clang-format 23.1.2, the host tests with sanitizers, `check_security_rules.sh`), add RS47,
  raise `PISO_FW_VERSION` to 3.12.2, `CHANGELOG.md`, `validation.md` with the numbers.

## Release
Firmware 3.12.2 (signed by CI after it reaches `main`; the owner decides the rollout). The router and the app are not touched.
