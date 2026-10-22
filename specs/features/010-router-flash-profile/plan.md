# Plan: feature 010, router flash profile and setup check

Status: **brought in line with the corrected spec (2026-10-22, after review). The plan the owner approved earlier was written before the owner's answers; the owner accepted every proposed answer to the spec's open questions (round 4); this version needs the owner's approval again. No code until then.**

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none checked yet (`ledger/negative-constraints.md` to be read at the start of the work).

| file | purpose | lines read | est_tokens |
|---|---|---|---|
| `constitution.md` | agent rules | 75 | 1,100 |
| `CLAUDE.md` | project rules | 75 | 1,300 |
| `specs/README.md` | the loop | 54 | 700 |
| `specs/STATUS.md` | the board | 42 | 1,000 |
| `specs/RELEASE_STATE.md` | what is live (5-9) | 9 | 600 |
| `specs/features/008-wheel-install-option/spec.md` | flash-size rule, 16 MiB threshold | 1-40 | 900 |
| `setup/src/20-preflight.sh` | package list (lines 35-55) | 20 | 300 |
| `setup/src/96-space-backup.sh` | flash and memory checks (grep only) | 10 lines | 200 |
| `tools/pisoportal/Cargo.toml` | `[features]` (only `test-owner-key` today) | 20 | 150 |
| `tools/pisoportal/src/main.rs`, `http.rs`, `console.rs` | where the wheel, `/game` routes and `boxview::start` (`console.rs:787`) are wired in | grep, ranges | 1,500 |
| `tools/pisoportal/src/wheel_spin.rs` | the spin journal and `recover` (lines 1-50, 210-230) | ranges | 800 |
| `tools/pisoportal/src/roll.rs` | the roll file and its power-cut test (1-25, 116-130, 415-430) | ranges | 600 |
| `tools/build_piso_setup.py` | one program per installer, per CPU family | 1-60 | 900 |
| `.github/workflows/router-program.yml`, `publish-router.yml` | how programs are built, signed and published | ranges | 2,000 |
| `scripts/verify_release.py`, `router/tests/test_footprint.py` | feed checks and size budgets | ranges | 1,000 |
| `setup/pisophone_setup.py` | `--phone-only` (line 10, 3461 on) and the answers flow (200-250) | ranges | 1,500 |

## Task groups
Small, ordered, each finished and checkable on its own. Test first: see the test fail and log it in `ledger/negative-constraints.md`. One commit per task group. Each group names the spec criteria it proves.

- [ ] 0. **Owner gate.** Answers to the open questions 1 to 6: done (spec, round 4: all proposed answers accepted). Left: the owner approves the spec and this plan again. If group 2 ends in "park the small build", groups 5 to 8 are dropped and the spec says so.
- [ ] 1. **Baseline (no code change)** (criterion 1). On the branch from `beta`: `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal`; `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_footprint.py`; `setup/test_setup_phone_web.py`, `test_phone_only.py`; `bash scripts/check_security_rules.sh`. Record counts and last lines in `validation.md`.
- [ ] 2. **Is the small build worth it?** (criterion 4, open question 6). No product code. Build the program once with the wheel, the mini-game and the coin-box view cut out by hand in a scratch copy, for one CPU family, and write the saving in KB in `validation.md` next to the package sizes (`opkg` sizes for `opennds curl ca-bundle jsonfilter libmicrohttpd-no-ssl kmod-nft-bridge`) and the 8 to 10 MB writable flash. With no 16 MB router yet this is an estimate, and says so. Report it to the owner, who decides: build the small image (groups 5 to 8) or park it.
- [ ] 3. **Flash measurement, tests first** (criterion 2). In `router/tests/test_setup.py`, through `PISO_MTD_FILE`: 8 MB and 16 MiB are small; 32 MB and 128 MB are large; a 16 MB sample with overlapping `firmware`/`kernel`/`rootfs` is small (a sum of partitions would say large: this is the case that must fail first); a whole-chip `fullflash` partition is used when listed; no `/proc/mtd` with a disk over 128 MiB is large; no `/proc/mtd` with a smaller disk, an unreadable or odd file are small.
- [ ] 4. **Flash measurement, code** (criterion 2). A shell function in `setup/src/` (in `96-space-backup.sh` or a new numbered file) with the rule from the spec (whole-chip partition, else the largest end offset); print the size and the decision in the setup summary. Raise `setup/RELEASE` here (the first change under `setup/src/`). Regenerate with `python3 tools/build_piso_setup.py`; `--check` must pass.
- [ ] 5. **The `small` Cargo feature, tests first** (criteria 5 and 6). Add `small = []` to `tools/pisoportal/Cargo.toml`. With `cfg(not(feature = "small"))` leave out the wheel (`wheel*.rs`, `wheel.html`, its routes, the recent-spins view), the mini-game (`game.rs`, `game.html`, the `/game` routes in `http.rs`) and the coin-box view (`boxview.rs`, its start in `console.rs`, the Rental phones tab). Tests: `cargo test` and `cargo test --features small` both pass; on the small build the removed routes answer 404 and the console has no Rental phones tab; a listing check shows the removed files are not embedded. Coin, payment, roll, pause, endurance, HyperSpeed, vouchers and the ledger tests pass on both builds. `cargo fmt`, `cargo clippy` for both builds.
- [ ] 6. **A spin cut short by the switch** (criterion 3, open question 3). Tests first in `tools/pisoportal` and `router/tests/test_setup.py`: with the wheel on, or with a `.spin` file in `<data folder>/wheel/` not marked done, the switch to the small image is refused with a plain reason; after the wheel is off and `recover` has finished or voided every open spin, the switch goes ahead; the roll's minutes change once or not at all. Then the code, as the owner's answer to question 3 says. The existing roll power-cut tests pass on both builds.
- [ ] 7. **Delivery and signing** (criterion 10, open question 1). As the owner chooses; for the proposed (b): `router-program.yml` builds the small program per CPU family into `tools/pisoportal/bin/` (CI commits it, never by hand); `build_piso_setup.py` makes the small installers; `publish-router.yml` signs and publishes them in the same feed with their own entries; `verify_release.py` checks them (0 FAIL); `test_footprint.py` gets budgets for them. The installer chooses the small or the full file by the measurement of group 4, and, per open question 2, an installed router only switches on a new install or an explicit re-run. Tests in `router/tests/test_setup.py` and `test_setup_tools.py` for the choice; a wrong-size or unsigned file is refused.
- [ ] 8. **Size proof** (criterion 4, open question 5). CI writes the small and full program sizes per family and the installer sizes; they go in `validation.md` with the saving. The free flash left on a real 16 MB router stays "not run" until the owner has one (or an image-builder build, if the owner chose it).
- [ ] 9. **The setup choice** (criterion 7). Tests first in `router/tests/test_pisophone_setup.py`, `setup/test_phone_only.py`, `test_setup_phone_web.py`: the setup program on the computer asks PisoWiFi, PisoPhone or both with no default; a non-interactive run without the choice stops with a plain message; PisoPhone only goes the existing `--phone-only` way and touches no router; PisoWiFi and both go the router install as today. Then the code in `setup/pisophone_setup.py`; regenerate `website/pisophone_setup.py` with `build_piso_setup.py`.
- [ ] 10. **Docs and the hardware check** (criterion 8). `docs/REAL_WORLD_TESTING.md`: a new check for a 16 MB router (flash measured right, small image installed, a coin, a pause, HyperSpeed, a power cut keeps the time, no wheel, no Rental phones tab, no mini-game), marked not run. `docs/ROUTERS.md` gets the measured sizes. `CHANGELOG.md` and `website/manual.html` say what a small router does not have.
- [ ] 11. **Validate** (criterion 9). Fill `validation.md` (each criterion, the gates from `CONTRIBUTING.md`, "Not tested", "Money and time"), update `specs/STATUS.md` and the feature row. Pull request into `beta` only when the owner asks; no merge to `main` and no release without the owner's order.

## Release
- Router: `setup/RELEASE` raised once above `1.5.16`, by group 4 (the first change under `setup/src/` or `tools/pisoportal/`).
- Firmware: not touched; `PISO_FW_VERSION` stays.
- App: not touched.
- Generated files (`tools/pisoportal/bin/*`, `setup/piso-setup*.sh`, `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`): regenerated by the tools and CI only.
- Publishing: "router release X signed and published (0%)" by CI on `main` only when the owner asks (`CLAUDE.md` section 5), small and full together. Raising to 100% is a separate step, the owner's.
