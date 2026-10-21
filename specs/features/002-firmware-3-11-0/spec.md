# Feature 002: restore firmware 3.11.0 (products, USB Wi-Fi setup, coin-counter hardening)

Status: **in progress, blocked on one owner decision** (approved 2026-10-21: one feature, setup choices in scope)   Branch: `feat/002-firmware-3-11-0`   Area: firmware (with setup program, scripts and CI that belong to it)
Backlog rows: none (owner features from the old `beta`, found at the 2026-10-21 replan)   Release: firmware (3.11.0), router (`setup/RELEASE`)

## Intent
The old `beta` carried firmware 3.11.0 and the work that goes with it; deleting `beta` removed it from the line of work. The owner wants it back before the firmware money fixes (feature 003, which sit on top of it). What 3.11.0 gives the shop owner:
- A coin box image for each product: PisoPhone only, PisoWiFi only, or the full one, built from the same source, with a signed update feed and flash image for each (`6c6e008`).
- Setup program choices "PisoPhone only (no router)" and "PisoWiFi only", and USB Wi-Fi setup of a new board with a new admin password (`c82b40f`, `d8d9ce4`).
- Coin-counter hardening: a period ledger so revenue totals survive, and related portal changes (`4b6312d`).
- Fixes that belong to them (`851707c`, `68a7b10`, `2fae5ac`) and a CI fix for loopback rate limiting and LF web assets (`724d214`).
Serves rule 1 (the coin count and revenue stay right) and the goal that a shop owner can set up without handling keys.

## State of the patches (trial apply, 2026-10-21)
Cherry-picked in order onto `beta` (`5dce780`) in a throwaway worktree:
- `c82b40f`, `d8d9ce4`, `6c6e008`: apply with only generated-file conflicts (setup scripts, `CHANGELOG`, docs).
- `4b6312d`: conflicts in `tools/pisoportal/src/core.rs` and `ledger.rs` (8 hunks). Feature 001 replaced the `ledger::shift` that `4b6312d` introduced; resolved by keeping feature 001's code. `cargo test` then passed (87).
- `851707c`, `68a7b10`, `2fae5ac`, `724d214`: apply cleanly (generated files only).
- Result: about 97 files, +4,450 / -850 lines of hand-written source (firmware 1,400 +, setup program 1,000 +, router scripts and tests 600 +, scripts 350 +, web dashboard 650, host tests 235). `embed_web.py --check` passes on the result.
- Not checked yet: the ESP32 compile (needs PlatformIO or CI), the host tests (this machine lacks `libmbedtls-dev`; install it or run in CI), `clang-format` 23.1.2 as CI uses (this machine has 18.1.3, which reports 2 differences that may be version noise). On the old branch the firmware images workflow built these commits successfully in CI.
- Never run on a real box ("Compiled only on the host so far" in `6c6e008`).

## Constraints
- Generated files are regenerated, never merged by hand: `WebAssets.h` (`embed_web.py`), `setup/piso-setup*.sh` and `website/setup/*` (`build_piso_setup.py`), `website/update/*` and `website/flash/*` (CI only).
- Firmware needs a higher `PISO_FW_VERSION` (3.11.0 > live 3.10.0); a router-side change raises `setup/RELEASE` once (next after 1.5.13).
- Box <-> phone contract unchanged; `protocol/gen_vectors.py` output unchanged.
- Money and time: the full image behaves as 3.10.0 for coins and credit except the ledger hardening; say how it was checked.
- Firmware reaches live boxes only when CI publishes from `main` and the owner updates from the dashboard. This feature does not touch `main`.
- No keys or tokens; signing happens only in the protected environment.

## Success criteria
1. All eight commits are on the feature branch and the tree builds: `cargo test` passes (87), `embed_web.py --check` and `build_piso_setup.py --check` pass, `check_security_rules.sh` passes.
2. Firmware host tests pass (`esp32_firmware/host_tests/run.sh`, with mbedtls) and `clang-format` is clean with the CI version.
3. `scripts/check_product_strip.py` passes (no word of the other product in a product's image).
4. CI builds all firmware environments (`firmware-images.yml`, "Firmware build") green on the branch's push.
5. Router and setup tests pass: `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_phone_only.py`, `test_setup_phone_web.py`.
6. `PISO_FW_VERSION` is 3.11.0 and nothing else raises it; `setup/RELEASE` raised once.
7. Hardware checks for 3.11.0 (USB Wi-Fi setup on a factory board, product images, period ledger) are in `docs/REAL_WORLD_TESTING.md`, marked not run.
8. `CHANGELOG.md` says in owner terms what changed.

## Out of scope
The firmware money rows FW-01, 02, 03, 05, 06 (feature 003, version 3.11.1); re-doing the work of `4b6312d`; publishing or releasing anything.

## Owner decisions
1. **One feature or three?** Recommended: one, because the commits chain (the setup choices, the product images and the ledger share files). Alternative: split into (a) product images and setup choices, (b) coin-counter hardening, (c) fixes and CI.
2. **PisoWiFi-only and PisoPhone-only setup in scope?** Recommended yes, since `c82b40f` is also what adds the USB Wi-Fi firmware behaviour.

## Finding during implementation (2026-10-21, spec edit under constitution rule 8)
The old work is incomplete: commit `6c6e008` committed the per-product asset headers (`WebAssetsBase.h`, `WebAssetsPhones.h`, and a `WebAssets.h` that picks one by `PISO_HAS_PHONES`) and `scripts/check_product_strip.py` (which calls `embed_web.assets_for()`), but **never committed the matching `scripts/embed_web.py`**. Later old-beta commits (`eff1256`, `6ab2234`) regenerated `WebAssets.h` with the old generator, which replaced the wrapper with the single full file. So on the old `beta` the PisoWiFi-only image still carried the phone dashboard, and `check_product_strip.py` could not run.
Added to this feature: write the missing generator exactly as documented in `esp32_firmware/web/README.md` ("One set of files per product"): `shared + phones + end` for the phone products, `shared + end` for PisoWiFi-only, `/*@phones*/ ... /*@end*/` left out of the CSS for PisoWiFi-only, comments dropped; `assets_for(has_phones)`; three headers; `--check` for all three.
New success criterion 9: `python3 scripts/embed_web.py --check` and `python3 scripts/check_product_strip.py` both pass.

## Owner decision needed (new)
3. **The PisoWiFi-only image counts coins with "phone" names.** The coin-counter hardening (`4b6312d`) and the product images (`6c6e008`) were built separately; together, `check_product_strip.py` finds 69 uses of "phone" in what the PisoWiFi-only image compiles. They are revenue counters by coin source, not phone code. Options: (a) **allow-list** those names in `check_product_strip.py` for the revenue code (small change; the NVS key `tot_phone_coins` stays, so the saved totals of boxes already in shops keep working; the image then does contain the word "phone"); (b) **rename** them to a neutral name (for example "source B coins"), which touches NVS keys and so needs a migration for boxes that already saved totals; (c) split the ledger by product so the PisoWiFi-only build has no phone counter (largest). Recommended: (a), with a note in `techstack`/README that revenue counters may say "phone".
4. **ST-1 (setup program crashes on the Owner tools page: `EXPECTED_OWNER_FINGERPRINT` is not defined).** Two setup web tests fail because of it. It is a backlog row (R2, confirmed bug) with a tested fix on the old branch. Recommended: fix it inside this feature (a small change in `setup/pisophone_setup.py` plus its test), so the branch is green; otherwise leave two tests red until feature R2.
