# Handoff: spec 03, the setup in a real shop: execution of features 032, 033, 034 (2026-10-28)

Branch `ccr-ecc45ac1-xsa1o8` at `120f716` (pushed: yes). Based on `main` at `2591448` (router 1.5.23, firmware 3.12.4). PR: none.
`beta` is at `16686ba`, behind `main` on purpose (owner's rule of 2026-10-24 in `specs/RELEASE_STATE.md`: green features merge
straight into `main`).

## Done
- `specs/spec_03_setup_real_run.md` (the epic: 18 rows, sections 6 and 7 hold every fix and acceptance criterion),
  `specs/spec_03_findings.json` (one row per item, all `open`), a row and a next step in `specs/STATUS.md`. Docs only.
- Baseline, all green: `router/tests/` `test_setup` 458, `test_pisophone_setup` 232, `test_setup_web` 115, `test_offline_setup` 98,
  `test_setup_tools` 78, `test_install` 48, `test_phone_only` 39, `test_setup_phone_web` 31 checks; `check_security_rules.sh` passed.

## Decisions (owner, 2026-10-28; spec 03 section 8)
D1 refuse OpenWrt 25.x (apk) clearly, full apk support later. D2 detect two 192.168.1.x networks, README advises the phone hotspot.
D3 the router bounces its LAN ports after the move. D4 pair only the board this program just wrote. D5 download early at Start,
ask to unplug the router cable when the website is unreachable. D6 firmware: a never-joined box takes new Wi-Fi details.
D7 order 032, 033, 034, one branch and one PR each. D8 each feature waits for the owner's bench run (RS56, RS57, RS58) before merging.

## Open, in order
0. **The epic is a draft.** If the owner has not approved it, show the summary and stop. Ask once which base to branch from:
   recommended `origin/main` plus `git cherry-pick 120f716` (the epic files), since `beta` is behind `main`.
1. **032 setup program** (`/sdd-plan`, folder `specs/features/032-setup-program-real-run/`): rows SU-18 first (the saved log helps
   every bench run), then SU-09, SU-10, SU-06a, SU-01, SU-02, SU-04, SU-15, SU-17, SU-21; SU-14 is decided by RS56 (b) only.
   Release: none for routers; the website's copy is rebuilt with `python3 tools/build_piso_setup.py` (`--check` must pass; confirm
   with `git status` that no router file changed, else raise `setup/RELEASE`). Add RS56 to `docs/REAL_WORLD_TESTING.md`.
2. **033 router installer** (`033-router-setup-real-run`): SU-03, SU-11, SU-06b, SU-05, SU-07, SU-08 (test first; may close
   `wont-fix` on RS57 (c) evidence). Raise `setup/RELEASE` (1.5.23 now), regenerate, keep `router/tests/test_footprint.py` green
   (the installer was near its size limit in feature 015). Add RS57; RS17, RS20, RS21, RS36 run with it.
3. **034 coin box firmware** (`034-box-wifi-retry`): SU-16. Raise `PISO_FW_VERSION`; host test that a box that joined once or has a
   payment answers `already-set-up`; the serial hello change is in spec 03 section 5. Add RS58.
- Owner decisions waiting: approval of the epic; base branch; Q1 (supported routers list, at 033); Q2 (PisoPhone-only box address, after RS58).
- Not tested: anything on a real router, box, phone, Windows or macOS computer. Every row is a desk finding.

## Facts found that the specs only hint at
- SU-09: `Core.__init__` sets the ssh password to `""` (`pisophone_setup.py:4426`); only `do_search` -> `saved_login` loads the saved
  one (`pisophone-router-password.txt`, written by `install()` before the router changes). The page tests use a fake `Core`
  (`test_setup_web.py:202`), so the real ssh path has no test today: model it in the fake.
- SU-11: the box's `PISO-BOX mac=` is its Wi-Fi station MAC in capitals (`esp32_firmware/src/main.cpp:228-235`), the same form
  `box_station` reads from `iwinfo` (`50-coin-box.sh:9`). The box restarts after writing: reopen the port as `provision_new_box`
  does (`pisophone_setup.py:4221-4250`). `BOX_EXPECT_MAC` goes at the end of `ANSWER_ORDER` (`:96`) and of `setup_command`'s reads.
- SU-03: the move runs detached at `setup/install.sh:109`; bounce the ports inside that same background job. Find out on the
  360T6M whether it is DSA (`lan1`..`lan4` in `/sys/class/net`) or swconfig; support both.
- SU-02: firmware downloads are cached in `flash_cache_dir()` (`fetch_image`, `:2845`); the router packages cannot be fetched before
  the router is read (`build_offline_bundle`, `:2113`), hence the unplug prompt.
- SU-06a: `parse_router_feeds` already sees `@@APK yes` (`:1971-1974`), but `install()`'s `fall_back` then tries the online way.

## Slow or special steps
- Run `router/tests/*.py` in the background and read the last lines (all eight together take a few minutes).
- Python gate (constitution rule 7): `ruff check`, `mypy`, `pytest -x` on touched files; all three are installed here.
  `clang-format` is installed; `pio` is not (say so for 034). Router program: `cargo test`, `python3 tests/test_flow.py` in `tools/pisoportal`.
- Log every red test in `ledger/negative-constraints.md` (table form). Do not push to `beta`, open a PR, merge or release unless asked.

## Next prompt
Use `specs/templates/session-start.md`; last line: `Task: continue from this note: specs/notes/2026-10-28_spec-03-setup-real-run.md`.
