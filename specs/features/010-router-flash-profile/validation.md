# Validation: feature 010, fit the full PisoWiFi and a PisoWiFi-only shop

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Baseline before any change (2026-10-22, `beta` code) | commands of criterion 1 | pass | `cargo test --locked --release`: 141 passed. `tests/test_flow.py`: 202 checks, 0 failures (needs `cargo build --locked --release` first; it runs `target/release/pisoportal`). `test_setup` 288, `test_pisophone_setup` 164, `test_setup_web` 107, `test_setup_tools` 78, `test_setup_phone_web` 31, `test_phone_only` 39, `test_install` 30, `test_selfupdate` 60, `test_footprint` 10 budgets: all 0 failures. `check_security_rules.sh`: all passed. |

## Criterion 10: does the full install fit? (estimate, mipsel, 2026-10-22, nothing measured on a router)
Method: each file cut into 4 KB pieces, each piece compressed with zlib level 6 (as jffs2 stores it); sizes of the files as built on `beta` (router 1.5.16). The packages' `Installed-Size` could **not** be read: `downloads.openwrt.org` is blocked by this session's network policy (403), so that column is empty and must be filled by the owner (`opkg info opennds curl ca-bundle jsonfilter libmicrohttpd-no-ssl` on the router, or the package index).

| On flash | Raw | Estimated on flash | Kept? |
|---|---|---|---|
| `/usr/bin/pisoportal` (mipsel) | 1090.8 KB | 570.2 KB | yes |
| `/usr/sbin/piso-setup` (installer without its program) | 125.6 KB | 54.3 KB | yes |
| 5 small files (`piso-monitor.sh`, 2 init scripts, `pisoportal_status.sh`, `50-piso-isolation`) | 16.9 KB | 8.0 KB | yes |
| **Our files, total** | 1233 KB | **about 633 KB** | |
| `/root/piso-setup.sh` (whole installer, today never deleted) | 1616.2 KB | 828.9 KB | **no: in RAM, deleted after a good install (decisions 3, 4)** |
| Packages `opennds curl ca-bundle jsonfilter libmicrohttpd-no-ssl` | - | not measured | yes |
| Data folder `/etc/coinslot.d` | grows with sales | not measured | yes |
| `/root/piso-update-backup` | about one old program and installer | not measured | until the update has proven itself |

Reading: with the installer copy kept off flash, our own files cost about 0.6 MB on flash. Even if the five packages together took 1 MB (a guess, not a reading), the total is about 1.6 MB against 6 to 8 MB writable on a 16 MB router (some boards about 4 MB): at least 4 MB would stay free, above the 2 MB rule (decision 7), and above 2048 KB + 1700 KB for an update on flash (decision 8) on the 6 to 8 MB routers. **Verdict: it should fit; on a 4 MB board it is tight until the packages are read.** The saving from decisions 3 and 4 is about 0.8 MB on flash by this method (1.6 MB if the flash were not compressed). Owed on the 360 T6GS: `df -k /overlay` before and after (hardware check, criterion 11).

## Gates (constitution rule 7)
- [ ] Area checks from `CONTRIBUTING.md`
- [ ] `bash scripts/check_security_rules.sh`
- [ ] Python: `ruff check`, `mypy`, `pytest -x` on touched files
- [ ] Generated files regenerated, `--check` passes

## Not tested
Say plainly what was not run (no device, no board, no Android SDK) and which hardware checks were added to `docs/REAL_WORLD_TESTING.md`.

## Money and time
How credit, expiry, deduction or the payment queue were checked (or "not touched").
