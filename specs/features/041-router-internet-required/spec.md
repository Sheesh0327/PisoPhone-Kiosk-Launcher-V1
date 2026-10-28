# Feature 041: require internet on the router, delete the offline package path

Status: **approved by the owner 2026-10-28 (epic D21, D25 — confirmed twice, after the consequence was put to the owner).**
Branch: `feat/041-router-internet-required`   Area: setup   Epic: `specs/spec_03_overhaul.md` feature 041
Release: router (**batched** with 033, 034 and 035 into one release — raise `setup/RELEASE`, do not run a rollout here)

## Intent
With boxes sold and routers bring-your-own (epic section 0), the documented path is: the coin box's page hands over a
one-liner, and the router downloads the installer from `pisophone.pages.dev` itself. Feature 011's offline path — where a
computer downloads the packages and copies them to the router over SSH — becomes a second way of doing the same job. The
owner has chosen to delete it so there is one install path to maintain, test and explain.

## Recorded consequence (accepted by the owner, written here so it is on the record)
- **A router with no WAN during first setup can no longer be set up at all.** Today it degrades to the computer path; after
  this it stops. That covers: replacing an old router, a modem being swapped, the ISP not yet connected, and the case RS36
  was written for (the WAN cable unplugged).
- **This discards proven, recent work.** The offline path shipped as router **1.5.18** and the owner ran **RS36 and RS37** on
  their own bench on 2026-10-23 and reported both passed.
- The owner was shown both of the above and chose deletion over keeping the code as a silent fallback.
- **Mitigation that must ship with it:** the setup pages and the box page state the requirement *before* the owner starts, not
  as an error afterwards (criterion 7). A clear requirement up front is the difference between a product rule and a bug.

## Invariants touched
- **Money and time:** not touched. This is install-time only; no coin, pricing, ledger or queue code is involved. Criterion 9
  proves the money tests still pass unchanged.
- **Only owner-signed software:** **this is the sensitive part.** The offline path carried its own verification — the router
  checked each source's `Packages` index with its own `usign` and keys, and each package's sha256, before one `opkg install`.
  Deleting it must **not** weaken what remains: the online path installs through `opkg` against the router's own configured,
  signed feeds, and the installer itself is still fetched over https and signature-checked exactly as today. Criterion 8
  proves no signature or TLS check was removed. **Never switch off a TLS check** (`CLAUDE.md` section 6,
  `scripts/check_security_rules.sh`).

## Exact scope — the files
Reference counts from `main` at `9a92c33`. **Re-grep before editing; do not trust these numbers blindly.**

| file | refs | what to do |
|---|---|---|
| `setup/src/21-local-packages.sh` | whole file, 55 lines | **delete the file**, and remove it from the build list in `tools/build_piso_setup.py` if it is named there |
| `setup/src/20-preflight.sh` | 6 | remove the copied-package branch and the two `die` messages that mention `--router-online`; the preflight keeps its ordinary "these packages are missing" check against the online path |
| `setup/src/98-setup-flow.sh` | 1 | remove `PISO_LOCAL_PKGS` from the `unset` line at ~157 |
| `setup/pisophone_setup.py` | 23 | remove `--router-online` (arg at ~6006 and the `self.router_online` member at ~4424), the bundle preparation, the SSH copy, the cleanup, and the fallback logic. **Python standard library only** — do not add a dependency while you are in there |
| `website/install.sh` | 0 direct | check `--local` handling; remove it if present (it is generated from `setup/src/`, so change the **source**, never the generated file) |
| `router/tests/test_setup.py` | 14 | remove the offline tests; keep every other test passing |
| `router/tests/test_pisophone_setup.py` | 38 | same — this is the largest block, work through it carefully |
| `router/tests/test_install.py` | 0 | confirm it still passes |

**Also update:**
```
website/start/shop.html, website/start/index.html   (state the requirement up front — criterion 7)
docs/ROUTERS.md                                     ("what else the router needs": add internet during first setup)
docs/DEPLOY.md                                      (any offline instructions)
specs/features/011-offline-setup/validation.md      (one line: part A was removed in router <release> by feature 041, with the reason. Do NOT delete the file — it is the record that the work was done and passed)
docs/REAL_WORLD_TESTING.md                          (RS36 retired with a reason, not deleted; see the plan)
CHANGELOG.md, specs/RELEASE_STATE.md                (one owner-readable line each)
```
**Part B of feature 011 stays.** Only part A (offline packages) goes. `piso-setup box-wifi-name` and the per-router coin box
network name are untouched — if you find yourself editing those, stop, you are in the wrong half.

## Constraints
- A change under `setup/src/` changes what routers install: **raise `setup/RELEASE`** and run
  `python3 tools/build_piso_setup.py --check`.
- Generated files (`setup/piso-setup*.sh` and its `.sha256`, `website/setup/*`, `website/install.sh`,
  `website/pisophone_setup.py`) are **never** edited by hand — change `setup/src/` and `setup/pisophone_setup.py` and
  regenerate.
- The setup program stays **Python standard library only** (`CLAUDE.md` section 6).
- The installer should get **smaller**: record the size before and after against the 1700 KB budget.
- BusyBox `ash` compatibility is required for anything in `setup/src/`; keep it ShellCheck-clean for POSIX sh.

## Success criteria
1. `rg -n "PISO_LOCAL_PKGS|local-packages|router-online|router_online" setup/ website/ router/tests/ tools/` returns nothing
   outside documentation and `CHANGELOG.md`.
2. `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`,
   `test_install.py`, `test_phone_only.py`, `test_setup_phone_web.py` all pass.
3. `cd tools/pisoportal && cargo test && python3 tests/test_flow.py` pass (unchanged by this feature — prove it).
4. `python3 tools/build_piso_setup.py --check` passes; `setup/RELEASE` raised.
5. A simulated first setup against a router **with** internet completes end to end in the test harness.
6. A simulated first setup against a router **without** internet **fails with a clear message naming the requirement** — not a
   traceback, not a silent hang. Quote the exact message in `validation.md`.
7. `website/start/shop.html` (and the box page text, if it carries the requirement) states "the router needs internet for the
   first setup" **before** the owner begins. Quote the wording.
8. **No signature or TLS check was removed.** `bash scripts/check_security_rules.sh` passes, and `validation.md` names each
   verification step the online path still performs (https fetch, installer signature, `opkg` against the router's own signed
   feeds).
9. The installer is **smaller**: record before and after KB against the 1700 KB budget.
10. `specs/features/011-offline-setup/validation.md` carries one line recording the removal and the reason, and the file is
    otherwise unchanged.

## Out of scope
- **Part B of feature 011** (the per-router coin box network name). Untouched.
- Anything in `tools/pisoportal/`. This is a setup-only feature.
- Making the box serve the installer over its own AP. That was offered as an alternative and not taken; if it is ever wanted
  it is a new feature with a partition-size measurement first.

## Owner decisions
All answered. **D21 = the router needs internet. D25 = delete the code, not just the documentation** — confirmed after the
consequence above was put to the owner explicitly. **D14** batches this into one router release with 033, 034 and 035.
