# Validation: 057 one package-manager layer (opkg and apk)

Validated on a computer on 2026-10-31. Not run on any real router.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | With a fake `opkg` the installer output and the openNDS install are as today | `python3 router/tests/test_setup.py` | pass | 481 checks, 0 failures (479 without busybox: the same count as the unchanged tree) |
| 2 | With a fake `apk` the same installs happen with apk's commands; each function has a test | `python3 router/tests/test_pkgmgr.py` | pass | 56 checks, 0 failures: update, is-installed, available, dry run, install, remove for opkg and apk; an apk router installs openNDS, curl, ca-bundle, jsonfilter with `apk add` and never calls opkg |
| 3 | With neither: openNDS present finishes with the "no package manager" report; openNDS missing stops and changes nothing | `test_pkgmgr.py`, `test_install.py`, `test_pisophone_setup.py` | pass | 56 / 49 / 261 checks, 0 failures; the refusal says "no package manager ... openNDS is not installed ... Nothing was changed" and nothing is downloaded, moved or run |
| 4 | `install.sh` and the setup program no longer refuse apk; their tests are updated | `test_install.py` (also under BusyBox ash), `test_pisophone_setup.py`, `test_phone_only.py`, `test_setup_phone_web.py` | pass | 49 / 261 / 51 / 34 checks, 0 failures |
| 5 | `check_security_rules.sh` fails a forbidden flag and passes the real tree | `python3 scripts/tests/test_package_flags.py`; `bash scripts/check_security_rules.sh` | pass | 9 checks, 0 failures; "security rules: all passed" |
| 6 | Documents | `docs/ROUTERS.md`, `setup/README.md`, `CHANGELOG.md`, RS70 | pass | written; the new tests run in `.github/workflows/quality.yml` |
| 7 | Image type from fixtures; label and reason in the summary and `piso-setup status` | `test_pkgmgr.py` | pass | official OpenWrt (opkg and apk feeds), ImmortalWrt fork, wrong feed host, a host that only ends in the letters of openwrt.org, one foreign feed among official ones, a failing kernel probe, no release file, no feeds, unknown distribution: all as specified; the status and the summary show the "Image:" line |
| 8 | Run on the owner's custom image | RS70 | **not run** | waits for the owner's router and for the owner's pasted facts (image-facts.md part B) |

Also run against the real package managers in a container (no network, not a router): the layer on official OpenWrt 23.05.5 and 24.10.8 (real opkg) and 25.12.1 (real apk 3.0.5): manager, is-installed, dry run (installed and unknown package), feed hosts and free space answered correctly; `preflight_packages` passes and `install_packages` stops at the update with "opkg/apk update failed (no internet or DNS?)" naming the right manager. Evidence: `image-facts.md`.

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md`: router add-on and setup: `cargo test` (193 passed), `python3 tests/test_flow.py` (216 checks, 0 failures) in `tools/pisoportal`; `test_setup.py` 481, `test_pisophone_setup.py` 261, `test_setup_web.py` 141, `test_setup_tools.py` 78, `test_setup_phone_web.py` 34, `test_phone_only.py` 51, `test_install.py` 49, `test_selfupdate.py` 89, `test_monitor.py` 60, `test_boxupdate.py` 47, `test_footprint.py` 12 budgets 0 failures (after raising one budget, see the spec), all 0 failures
- [x] `bash scripts/check_security_rules.sh`: all passed
- [x] Python: `mypy` on the two new test files: no issues. `ruff check` on the two new files: import order fixed; the rest (open() without a context manager, subprocess.run without check) is the style of the existing test files. The edited existing files have more ruff findings than before (124 to 129 across the three files; mypy unchanged at 8 for the setup program), in the same style as their surroundings; nothing was fixed that was not mine. `pytest -x` was not run: these tests are plain scripts, run as above.
- [x] Generated files regenerated, `python3 tools/build_piso_setup.py --check` passes; `scripts/verify_release.py`: 0 FAIL, 1 WARN (router.json is still 1.5.34 until the release is signed)
- [x] `shellcheck -s sh setup/piso-setup.sh.in`: two findings, both in lines this feature did not write (SC2002 at the network address read, SC2015 at the Wi-Fi name check); the layer has none

## Not tested
- **No real router.** Not the owner's ImmortalWrt router, not any apk router, not an image with no package manager. The container images have no network, no kernel of their own and no ImmortalWrt (Docker Hub rate-limited that pull). RS70 was added to `docs/REAL_WORLD_TESTING.md`.
- The image type `official` and `fork` answers are tested from fixture files and a fake dry run; the real kernel-fit probe could not pass in the container (no package lists).
- Router release 1.5.35 is **not built through `beta`** (CI builds the programs and the setup files there; the program inside the signed file has not been decoded). Nothing is released.

## Money and time
Not touched: no change under the portal program (`tools/pisoportal/src`), the payment path, the coin window, the ledger or the box. `cargo test` and `test_flow.py` pass unchanged.
