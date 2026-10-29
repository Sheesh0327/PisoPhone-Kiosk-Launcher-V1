# Validation: feature 034, remove the mini-game

Branch `feat/034-remove-minigame`, base `0ed9803` (feature 033 merged). Run 2026-10-28 in a cloud container (x86_64 Linux, Rust, Python 3, headless Chromium).
Commits: groups 2+3 (delete and de-reference, one compiling commit), 4 (customer page), 5+6 (tests, console, manual, workflow, docs, changelog).

## Before and after
| | before (base `0ed9803`) | after |
|---|---|---|
| `cargo test` | 131 passed, 0 failed | **125 passed**, 0 failed, 0 ignored, no warnings |
| `tests/test_flow.py` | 223 checks, 0 failures | 216 / 0 |
| `tests/test_console.py` | 116 / 0 (recorded by 033) | 110 / 0 |
| `tests/test_browser.py` | not run before (Python Playwright was missing; the test skips itself) | 65 / 0, including 12 new `034` checks |
| other portal tests (`pricing_flow`, `link_loss`, `console_loader`, `console_prices`, `console_frame`, `send`, `window_record`, `soak --quick`) | not recorded | 9 / 16 / 8 / 13 / 7 / 24 / 14 / 6, all 0 failures |
| `router/tests/test_setup.py` | 460 / 0 | 460 / 0 |
| `test_pisophone_setup.py` | 232 / 0 | 232 / 0 |
| `test_setup_web.py` | 115 / 0 | **139 / 0** (see note) |
| `test_setup_tools.py` | 78 / 0 | **87 / 0** (see note) |
| `bash scripts/check_security_rules.sh` | all passed | all passed |
| `router/tests/test_footprint.py` | passes | `8 budgets, 0 failures` (the `game.html` line is removed) |
| program, `cargo build --release`, x86_64, this container | **896,208 bytes** (978,992 before 033) | **876,936 bytes** (19,272 smaller, 2.2%; 102,056 smaller than before 033 and 034 together) |
| `tools/pisoportal/bin/pisoportal-mipsel` (committed, built by CI) | 1,116,948 bytes | **not rebuilt here**, still 1,116,948 (see "Not tested") |
| deleted source | `game.rs` 11,091 bytes + `game.html` 7,022 bytes + `test_game_browser.py` | gone |

Note on the two setup counts: I installed the Python `playwright` package to run the browser test. `test_setup_web.py` and `test_setup_tools.py` skip their browser checks when it is missing, which is how the 115 and 78 baselines were taken. With it present (and `CHROME=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome`) they run 24 and 9 more checks, all passing. Nothing under `setup/` was changed.

Cargo test names, before vs after (sorted lists of `test ... ok`): 7 removed, 1 added, 131 - 7 + 1 = 125. **All 7 removed names are `game::tests::*`.** The one added is `util::tests::a_device_without_a_known_mac_is_counted_by_its_address`, the unchanged test of `device_key`, which the file-send upload also uses and which therefore moved from `game.rs` to `util.rs`. The other 6 game tests tested the play-time counter, which no longer exists.

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | no game in program, `router/`, `setup/src/` | `rg -n -i "game\|GAME_" tools/pisoportal/src/ router/ setup/src/` | pass | **no output at all**. The only places that keep the word are the tests (`test_flow.py`, `test_console.py`, `test_browser.py`), which must name `/game` and `GAME_MINUTES=10` to prove criteria 5, 6 and 11. The spec was corrected first (it said "nothing"; criterion 11 needs the key in a test) |
| 2 | `cargo test`, no game tests, none ignored | `cd tools/pisoportal && cargo test` | pass | `test result: ok. 125 passed; 0 failed; 0 ignored`; the 6 deleted game tests are listed above |
| 3 | `test_flow.py` | after `cargo build --release` | pass | `216 checks, 0 failures` |
| 4 | four router test files | `python3 router/tests/test_*.py` | pass | 460/0, 232/0, 139/0, 87/0 |
| 5 | console test has no `GAME_MINUTES` setting; `/game` | `python3 tests/test_console.py`, `test_flow.py` | pass | `110 checks, 0 failures`. **`GET /game`, `/game/` and `/game/tick` (also `?s=1`) return the ordinary customer page, byte-identical to `/`** (the portal answers every unknown address that way; it cannot be 404). **`POST /game/tick` returns 405**, like `POST /wheel/spin`. `GET /api/settings` has no `game_minutes`; `POST /api/settings {"game_minutes":"15"}` is `400 NOTHING` and writes nothing. The router keeps no `game*` file in its data folder |
| 6 | customer page renders in all six states with no game button and no gap | new checks in `tests/test_browser.py` (headless Chromium, the server's own state JSON served over a stubbed WebSocket) | pass | see below |
| 7 | footprint passes; record `pisoportal-mipsel` before and after | `python3 router/tests/test_footprint.py`; sizes above | pass for the test; mipsel **not rebuilt** | `8 budgets, 0 failures`. x86_64: 896,208 to 876,936. mipsel: still the committed 1,116,948; CI rebuilds it |
| 8 | security rules | `bash scripts/check_security_rules.sh` | pass | `security rules: all passed` |
| 9 | setup files rebuilt, `setup/RELEASE` raised | `python3 tools/build_piso_setup.py --check` | pass, **RELEASE not raised again** (see "Deviations") | `setup/piso-setup.sh (with its sha256) and the website's copies ... are up to date`; `setup/RELEASE` is 1.5.25 (live on `main` is 1.5.23) |
| 10 | money unchanged | same script, once with the program built from the base, once from this branch (below) | pass | outputs byte-identical (`diff` empty) |
| 11 | a `settings.txt` with `GAME_MINUTES=10` is left byte-identical | `test_console.py` (below) | pass | the file is seeded before start-up, compared byte for byte right after the program starts, and checked again after other settings were saved through the console |

### Criterion 6: which states I actually checked
All six, each rendered by the real `page.html` in headless Chromium, fed the same JSON the server sends (`View::to_json`), not a hand-made page:

| state | visible view | no game button, text or link | every shown link has a target |
|---|---|---|---|
| idle (with `sd:true`, so Send a file is shown, and the voucher box) | `v-idle` | pass | pass |
| starting | `v-live` | pass | pass |
| armed | `v-live` | pass | pass |
| closing | `v-live` | pass | pass |
| final | `v-final` | pass | pass |
| paused | `v-idle` (the paused layout) | pass | pass |

"No game" means: no element `#gm` or `#gms`, and the whole page HTML contains neither "game" nor "/game". "No dead button" means every visible `<a>` has an `href`. **The new check fails on the old page, in all six states** (`65 checks, 6 failures`, run with `git show HEAD:...page.html` put back), and passes on the new one (`65 checks, 0 failures`).

What I did **not** check: pixel layout. I did not take or compare screenshots. By construction there is no gap: the removed button was `display:none` unless the game had minutes left, and its text line `<p id="gms">` was empty, so the idle page for a router with the game switched off (`GAME_MINUTES=0`) was already exactly the layout every router now has. A router that had the game on shows the idle page a button and a line shorter.

### Criterion 10: how the money was checked (`CLAUDE.md` section 6)
A script builds a fixture `revenue.csv` of 40 hash-chained rows (10 pesos each, 400 in all; chain made the way `ledger.rs` does) and runs `pisoportal ledger`, `verify` and `reconcile` against it, once with the base program (built before any edit, kept) and once with this branch's program. No coin box is reachable, so `reconcile` answers `NOBOX` with the ledger total.
```
$ pisoportal ledger    -> Sales record: 1 month of detail kept, none deleted. Lifetime total: 400 pesos.   (exit 0)
$ pisoportal verify    -> OK 40 400                                                                         (exit 0)
$ pisoportal reconcile -> RECONCILE NOBOX (needs firmware 3.2.1 or later) ledger=400                        (exit 4)
revenue.csv sha256 84dc171e7c30393b0977a0e5bc31574311463c28c9ef05d96e47bc2403e1c77b
```
Identical before and after (`diff` empty). This does not cover a live box answering (`Ok`/`Mismatch`). No code in `reconcile.rs`, `ledger.rs`, `roll.rs`, `pricing.rs`, `voucher.rs` or the credit path was touched.

### Criterion 11: the owner's settings line
Fixture `settings.txt` = `GAME_MINUTES=10\nGAME_LIMIT_SECONDS=6\n`, written before the portal starts. In `test_console.py`: (a) after `env.start()` the file is byte-identical; (b) after the console saves other settings (voucher choice, ledger size, file send), both lines are still there and no `GAME_MINUTES=15` was written. Both pass. **This test passed the first time it ran**: the existing settings writer already keeps lines it does not know, so there was no red test to log in the ledger.

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` for the router add-on (`cargo build --release`, `cargo test`, `test_flow.py`, the four setup tests, footprint, soak `--quick`, `test_console.py` and the browser tests)
- [x] `bash scripts/check_security_rules.sh`
- [ ] Python `ruff check`, `mypy`, `pytest -x` on touched files: **not run**. The touched Python are the repo's own check scripts (`tests/*.py`), run directly
- [x] Generated files: `python3 tools/build_piso_setup.py --check` passes (nothing under `setup/src/` changed, so nothing needed rebuilding)

## Not tested
- **`pisoportal-mipsel` size after:** only x86_64 builds here; CI cross-compiles and commits the router programs. The committed programs (and the setup files that embed them) still contain the game until CI rebuilds them. The 1200 KB program budgets in `test_footprint.py` are not lowered, for the same reason as in 033.
- **Signed console script:** `website/console/ui.js` / `ui.json` still carry the old *Mini-game* settings field until `publish-router.yml` regenerates them from `src/console-ui.js`; not hand-edited. Until then an updated router served an old console page would show a box that no longer does anything.
- No router, no coin box, no phone. A router that had the game on was not available; the on-by-default behaviour is covered only by the fixture tests above.
- Pixel layout of the customer page (see criterion 6). Full-length soak (only `--quick`). `reconcile` with a live box. `test_setup_phone_web.py`, `test_phone_only.py` (nothing under `setup/` changed).
- No hardware check added: nothing here is `verified-on-hardware` and nothing needs to be (a code path that never touched money or paid time was removed).

## Deviations and spec corrections (the code never got ahead of the spec)
1. `website/console/ui.js` is not hand-editable; the sources are `src/console-ui.js` and `src/console.html` (both edited) and `src/page.html`. Spec corrected before any code.
2. Criterion 1 ("nothing") contradicted criterion 11; it now says the tests keep the key. Spec corrected.
3. `/game` cannot be 404 (criterion 5, intent text). Real behaviour recorded above: the customer page for a GET, 405 for `POST /game/tick`. Spec corrected.
4. The spec said `util.rs` had "one helper reference". It is the line `("game.rs", include_str!("game.rs"))` in the source-scanning test `rt14_no_other_file_renames_a_file_itself`; removed and the count `10` lowered to `9`. Separately, `device_key` (defined in `game.rs`) is used by the file-send upload, so it moved to `util.rs` with its test. Spec table corrected.
5. Two files the spec did not list were also wrong after the game went, so the spec's "also update" list gained them: `website/manual.html` (the *Mini-game* section and the console Settings bullet; hand-written) and `.github/workflows/quality.yml` (the one line that runs the deleted `test_game_browser.py`; without that edit CI would fail).
6. **`setup/RELEASE` was not raised again.** The spec says a change under `tools/pisoportal/` raises it, and the brief says to raise it. 033 already raised it to 1.5.25, and 1.5.25 has never been published (live on `main` is 1.5.23), and the owner's D14 says 033, 034, 035 and 041 are one release. A second bump to 1.5.26 would only skip a number. If you want 1.5.26 anyway it is one line and a rebuild; `CHANGELOG.md` and `specs/RELEASE_STATE.md` say 1.5.25.

## A wheel leftover found (reported, not fixed; belongs to a follow-up of 033)
`.github/workflows/quality.yml` lines 330-334 still run `tests/test_wheel.py`, `test_wheel_visitors.py`, `test_wheel_layout.py`, `test_wheel_browser.py` and `test_wheel_graphics.py`. None of those files exists any more (033 deleted them), so the "router add-on" job in Code quality will fail as soon as it reaches them. 033's criterion 1 only searched `tools/pisoportal/src/`, `router/` and `setup/src/`, which is why it missed `.github/`. I changed only my own line in that file (`test_game_browser.py`).

## Money and time
Not touched. The mini-game only counted play minutes against a soft limit (`GAME_MINUTES`) and never wrote `revenue.csv` or the roll; no coin, payment, price, voucher, pause, roll or ledger code changed. Proof: criterion 10 (identical `ledger`, `verify` and `reconcile` output, identical `revenue.csv` hash, on the base and on this branch). A customer in the middle of a game loses nothing: the game held no money and no paid time.
