# Legacy acceptance evidence gaps

These are historical records without retained validation files. “Not reconstructed” means this cleanup did not locate criterion-level proof; it does not claim the original work was never tested. Hardware status comes from `docs/REAL_WORLD_TESTING.md`.

| Feature | Criterion | Result and evidence gap |
|---|---|---|
| 026 | 1: initialize the Wi-Fi stack before opening ports | Not reconstructed from a retained validation file. The former spec and plan exist in Git at `2b2bdc2^`; review CI and runtime evidence before claiming a criterion pass. |
| 026 | 2: add and run RS50 | The checklist exists but remains unchecked. An owner-reported improvement is recorded without enough detail to establish the full procedure. |
| 026 | 3: firmware version, changelog and ledger | Not independently reconstructed from a retained validation file; the historical spec and release record are the available starting points. |
| 027 | 1: refusal fallback unit test | Not reconstructed from a retained validation file; the cause was not reproduced on a phone in the spec. |
| 027 | 2: money, time and protocol unchanged | Not independently reconstructed from a retained validation file. |
| 027 | 3: RS51 | The checklist remains unchecked; no phone run is established. |
| 028 | 1: visible/hidden QR page tests | Not reconstructed from a retained validation file. |
| 028 | 2: all firmware builds and product-strip check | Not reconstructed from a retained validation file; the source spec says no box or phone run. |
| 028 | 3: money/protocol unchanged and RS52 | The unchanged-contract claim was not independently reconstructed here; RS52 remains unchecked. |
| 029 | 1: Google-app visibility unit tests | Not reconstructed from a retained validation file. |
| 029 | 2: money/protocol unchanged and RS53 | The unchanged-contract claim was not independently reconstructed here; RS53 remains unchecked. |
| 030 | No numbered success criteria in retained spec | The spec describes a unit-tested fix, but there is no retained criterion-level validation record; RS54 remains unchecked. Do not invent acceptance criteria retrospectively. |
| 031 | No numbered success criteria in retained spec | The spec describes the maintenance-window change, but there is no retained criterion-level validation record; RS55 remains unchecked. Do not invent acceptance criteria retrospectively. |

The records remain historical evidence, not approvals of additional work. If the missing evidence is needed to establish current behavior, use a new approved OpenSpec change or a documented owner-directed verification.
