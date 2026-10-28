# Validation: feature 041, require internet on the router

## Before (group 1, base commit 6e612b6, setup/RELEASE 1.5.25)
| test | result |
|---|---|
| `router/tests/test_setup.py` | 460 checks, 0 failures |
| `router/tests/test_pisophone_setup.py` | 232 checks, 0 failures |
| `router/tests/test_setup_web.py` | 115 checks, 0 failures |
| `router/tests/test_setup_tools.py` | 78 checks, 0 failures |
| `router/tests/test_install.py` | 48 checks, 0 failures |
| `router/tests/test_phone_only.py` | 39 checks, 0 failures |
| `router/tests/test_setup_phone_web.py` | 31 checks, 0 failures |

Installer size before (`setup/piso-setup.sh`): 1678991 bytes = 1639.7 KB (budget 1700 KB). Baselines from the lead (cargo 131/0, flow 223/0) matched.

## After (group 7, setup/RELEASE 1.5.26)
| test | before | after | why it changed |
|---|---|---|---|
| `test_setup.py` | 460/0 | 442/0 | offline checks removed, new ones for internet required, the messages and no copied-packages code (net -18; I did not count each side) |
| `test_pisophone_setup.py` | 232/0 | 222/0 | the offline end-to-end block is gone, the new with/without internet block is in |
| `test_setup_web.py` | 115/0 | 115/0 | |
| `test_setup_tools.py` | 78/0 | 78/0 | |
| `test_install.py` | 48/0 | 41/0 | the `--local` tests are replaced by one "a folder is never used" test |
| `test_phone_only.py` | 39/0 | 39/0 | |
| `test_setup_phone_web.py` | 31/0 | 31/0 | |
| `test_footprint.py` | - | 9 budgets, 0 failures | |
| `cargo build --release && cargo test` | 131/0 | 131 passed, 0 failed | unchanged, as the spec says |
| `python3 tests/test_flow.py` | 223/0 | 223/0 | unchanged |

`router/tests/test_offline_setup.py` was deleted together with its CI step: it tested only the deleted bundle functions.

## Success criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | No offline words left | `rg -n "PISO_LOCAL_PKGS\|local-packages\|router-online\|router_online" setup/ website/ router/tests/ tools/ -g '!*.md'` | **pass, with the two documented exceptions** (spec note added) | Remaining: 5 lines in `router/tests/` that assert the option and variable are gone (`--router-online is refused`, `"PISO_LOCAL_PKGS" not in text`), and `website/update/*/router-setup.sh`, the CI-made signed copies of router 1.5.25 that CI replaces at release. `setup/` and the rest of `website/` and `tools/` are clean; `setup/src/21-local-packages.sh` is gone (`git rm`). |
| 2 | Seven setup test files pass | see "After" | pass | 442/0, 222/0, 115/0, 78/0, 41/0, 39/0, 31/0. `test_pisophone_setup` also has a known intermittent failure on this cloud container only: `/dev/null` is a regular file here, so a stray `/bin/bash: line 1: unalias: unsetenv: not found` lands in the fake ssh's input log (ledger entry 2026-10-23, root cause found then). It happened in several of my runs while editing (each time as the single failure) and not in the final gate run. Not caused by this feature. |
| 3 | Portal program unchanged | `cargo build --release`, `cargo test`, `python3 tests/test_flow.py` | pass | `test result: ok. 131 passed; 0 failed`, flow `223 checks, 0 failures`. `git diff 6e612b6 --stat -- tools/pisoportal` is empty. |
| 4 | Regenerated, release raised | `python3 tools/build_piso_setup.py --check`; `setup/RELEASE` | pass | `setup/piso-setup.sh (with its sha256) and the website's copies (...) are up to date`. `setup/RELEASE` 1.5.25 -> 1.5.26 (the lead reconciles the final number with feature 034). |
| 5 | First setup with internet runs end to end | `test_pisophone_setup.py`, "a first setup against a router with internet runs to its end" | pass | factory router: probe, installer download and move to 10.0.0.1, internet test, setup with every answer, summary and sheet saved; nothing copied to the router from the computer. |
| 6 | First setup without internet fails clearly | same file, both cases; `test_setup.py` for the router's own preflight | pass | Setup program (computer): **"The router has no internet, and the first setup needs it: the router downloads its software itself. Nothing was changed on the router. Plug the modem into the router's WAN port, wait a minute until the modem is online, then try again."** (after the router was already moved: "Nothing more was changed on the router."). Raised as `SetupError`, which the program prints as a message, not a traceback; the test asserts no `Traceback`, and that the setup command never ran. Router (one-liner, no computer): **"the router has no WAN address, and it needs internet for the first setup: plug the modem into the WAN port and wait a minute, then run this again"** and **"the router has no internet through the WAN port, and it needs internet for the first setup: check that the modem is online, then run this again"**; `install.sh` download failure: **"could not download <url> (the router needs internet for the first setup: plug the modem into its WAN port, wait a minute, then run this again)"**. |
| 7 | Requirement stated before the owner begins | `website/start/shop.html`, `index.html`, `setup/README.md`, `docs/ROUTERS.md` | pass | shop.html, in a warning box above the cable picture and the steps: **"Before you begin: the router needs internet for the first setup. Plug the modem into the router's WAN port and check that the modem is online. The router downloads its own software during the setup; with no internet it cannot be set up at all, and the program stops and tells you so before it changes anything on the router. Replacing a router, or changing the modem? Get the new modem online first."** Also in the "You need" line ("a modem that is online") and cabling step 3. index.html: **"... About 15 minutes. The router needs internet for the first setup."** under the *Set up a shop* link. The coin box's own page does not carry the requirement (its text is in `esp32_firmware/web`, a firmware release; nothing there mentions the one-liner), so it was not changed. |
| 8 | No signature or TLS check removed | `bash scripts/check_security_rules.sh`; `git diff 6e612b6 -- setup/install.sh setup/src` | pass | `security rules: all passed`. The diff removes verification lines only inside the deleted path (the `usign -V` check of each source's `Packages` index and the per-package sha256 and size check, all in `21-local-packages.sh`, and the `--local` copy branch of `install.sh`). What the online path still does, unchanged: (1) the installer is fetched over **https** from `pisophone.pages.dev` (`fetch` in `install.sh`; no `--no-check-certificate` or `-k` anywhere in the diff); (2) its **sha256** is fetched too and must match (`install.sh`: `want`/`have`, 64 hex digits), then it must start with `#!/bin/sh`, contain the portal payload marker and pass `sh -n`; (3) packages are installed by **`opkg update` and `opkg install`** against the router's own configured feeds, which keep `option check_signature` (the same check `opkg` always makes); (4) an installed router's later updates still go through the owner-signed release (`piso-setup update`, untouched). Honest limit: the first-setup installer is checked by the published sha256 over https, not by an owner signature; that is how it worked before and this feature does not change it. |
| 9 | Installer smaller | `wc -c setup/piso-setup.sh` | pass, small | 1678991 -> **1674043 bytes** (1639.7 KB -> 1634.8 KB), 4.8 KB smaller, budget 1700 KB (`test_footprint.py` passes). Almost all of the file is the portal programs; the offline shell code was small. Other architectures: x86_64 1648556 -> 1643608, mips 1678989 -> 1674041. |
| 10 | Feature 011's validation notes the removal | `git diff 6e612b6 -- specs/features/011-offline-setup/validation.md` | pass | One note (a blank line and one line) added under the title; nothing else in the file changed. |

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` (router add-on: cargo and flow; setup: the seven files above; `test_footprint.py`)
- [x] `bash scripts/check_security_rules.sh`: all passed
- [ ] Python: `ruff check` on `setup/pisophone_setup.py`: the count of auto-fixable findings is the same 6 as before this feature (two imports that only the deleted code used were removed so none were added; I compared the fixable counts, not every finding). `mypy` and `pytest` not run (the tests are plain scripts).
- [x] Generated files regenerated, `--check` passes

## Not tested
- No real router, modem or coin box: **RS57** (new, `docs/REAL_WORLD_TESTING.md`) is the hardware check: a first setup with internet, the page wording, and a router with the modem unplugged. Nothing of this has run on hardware.
- ShellCheck and BusyBox `ash` are not installed here; the changed shell was checked with `sh -n` and run by the test suite under dash. The change only removes code and edits message text, so no new shell construct was introduced.
- The setup pages were not drawn in a browser (playwright is not installed); the HTML change is one `div` with text.
- `ruff` findings and the `/dev/null` flake above are pre-existing.

## Spec corrections made during implementation (the spec was edited first each time)
1. `router/tests/test_offline_setup.py` and its step in `.github/workflows/quality.yml` were not in the file table: deleted.
2. `router/tests/test_install.py` was listed with 0 references but has the `--local` tests: replaced by one test.
3. `docs/REAL_WORLD_TESTING.md` has no RS36 or RS37 row (the numbers jump RS35 to RS38) and RS55 was already taken: a RETIRED RS36 line was added in the style of RS34 and the new check is RS57.
4. Criterion 1 ("returns nothing") is too strict (same lesson as feature 033).
5. `docs/DEPLOY.md` has no offline instructions: left unchanged. `specs/STATUS.md` has no 041 row: left for the batch merge.

## Money and time
Not touched. Install-time only: `git diff 6e612b6 --stat` lists setup, tests, website pages, docs and specs; nothing under `tools/pisoportal`, `esp32_firmware` or `app`. The portal's 131 unit tests and 223 flow checks, which cover credit, expiry and the ledger, pass unchanged.
