# Plan: restore firmware 3.11.0

Context manifest (rule 1). Sizes are the sums from the trial apply; open files by line range only, and never the generated ones.

| area | purpose | files | lines added | est_tokens (if read whole: do not) |
|---|---|---|---|---|
| `esp32_firmware/include`, `src` | product flag, ledger, USB Wi-Fi setup | 44 | ~1,420 | ~15k |
| `esp32_firmware/host_tests`, `envs` | host tests, product environments | 5 | ~290 | ~3k |
| `esp32_firmware/web` | dashboard changes (then `embed_web.py`) | 10 | ~650 | skip, regenerate |
| `setup/pisophone_setup.py` | product choices, USB Wi-Fi | 1 | ~1,020 | read ranges |
| `router/`, `scripts/`, `.github/` | setup tests, product-strip check, feeds | 16 | ~1,000 | ~10k |
| `tools/pisoportal/src` | ledger hardening, conflict resolution | 3 | ~30 | ~2k |

Unresolved ledger entries: none. Source commits: `git show <hash>` for `c82b40f d8d9ce4 6c6e008 4b6312d 851707c 68a7b10 2fae5ac 724d214` (the generated-file commits `eff1256`, `6ab2234` are replaced by running `embed_web.py`).

## Task groups (cherry-pick in this order; commit after each; run the group's checks)

- [ ] 1. **Environment**: install `libmbedtls-dev` (or note it cannot be done here and rely on CI), match `clang-format` 23.1.2 (`pip install clang-format==23.1.2`). Red check: run the new host tests on `beta` before the change (they do not exist yet; log the missing-file result).
- [ ] 2. `c82b40f`: setup program product choice and USB Wi-Fi setup (firmware 3.11.0). Checks: setup tests, host tests for the USB setup.
- [ ] 3. `d8d9ce4`: PisoWiFi-only choice. Checks: setup tests.
- [ ] 4. `6c6e008`: product images. Checks: `check_product_strip.py`, `embed_web.py --check`, host tests, `build_piso_setup.py --check`.
- [ ] 5. `4b6312d`: coin-counter hardening. Resolve `core.rs`/`ledger.rs` keeping feature 001's code. Checks: `cargo test` (87), `test_flow.py` (202), period-ledger host test.
- [ ] 6. `851707c`, `68a7b10`, `2fae5ac`, `724d214`: fixes and CI. Checks: host tests, `clang-format`.
- [ ] 7. Regenerate `WebAssets.h`, `build_piso_setup.py`; version lines; `CHANGELOG.md`; hardware checks in `REAL_WORLD_TESTING.md`.
- [ ] 8. Push to the feature branch (not `beta`); read the CI results (Firmware build, Firmware images, Code quality) and fix what they show.

## Release
Firmware 3.11.0 (`PISO_FW_VERSION`), router `setup/RELEASE` raised once. Nothing is published until the owner merges to `main` and approves the signing job.
