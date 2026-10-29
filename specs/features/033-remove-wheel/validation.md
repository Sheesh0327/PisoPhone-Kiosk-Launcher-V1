# Validation: feature 033, remove the spinning wheel

Branch `feat/033-remove-wheel`, base `6b47cb6` (feature 032 merged). Run 2026-10-28 in a cloud container (x86_64 Linux, Rust, Python 3).
Commits: groups 2+3 (delete and de-reference, one compiling commit), 4 (alerts notice), 5 (pages and tests), 6 (docs), 7 (release number and rebuilt setup files).

## Before and after
| | before (base commit) | after |
|---|---|---|
| `cargo test` | 184 passed, 0 failed | 131 passed, 0 failed, 0 ignored |
| `tests/test_flow.py` | 216 checks, 0 failures | 223 checks, 0 failures |
| `router/tests/test_setup.py` | 460 / 0 | 460 / 0 |
| `test_pisophone_setup.py` | 232 / 0 | 232 / 0 |
| `test_setup_web.py` | 115 / 0 | 115 / 0 |
| `test_setup_tools.py` | 78 / 0 | 78 / 0 |
| `test_setup_phone_web.py`, `test_phone_only.py` | not recorded before | 31 / 0 and 39 / 0 |
| `bash scripts/check_security_rules.sh` | all passed | all passed |
| program, `cargo build --release`, x86_64, this container | **978,992 bytes** | **896,208 bytes** (82,784 smaller, 8.5%) |
| `tools/pisoportal/bin/pisoportal-mipsel` (committed, built by CI) | 1,116,948 bytes | **not rebuilt here** (see "Not tested") |

Cargo test names, before vs after (sorted lists of `test ... ok`): 55 removed, 2 added, 184 - 55 + 2 = 131. **Every one of the 55 removed names contains
"wheel"**; the two added are `config::tests::no_setup_script_writes_a_wheel_switch` (replaces the old wheel-switch test, keeps the `WHEEL=` assertion) and
`util::tests::f033_a_router_that_had_the_wheel_on_is_told_once_and_its_records_stay`. test_flow gained 7 checks (criterion 8).

## Criteria
| # | Criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | no wheel in program, router/, setup/src (two allowed places) | `rg -n -i wheel tools/pisoportal/src/ router/ setup/src/` | pass | only `config.rs:299-304` (the `WHEEL=` guard test, criterion 7) and `util.rs:344-394`, `main.rs:179` (the notice, criterion 13). `rg -i wheel router/ setup/src/ \| wc -l` is 0. The spec said "nothing"; it contradicted criteria 7 and 13, so it was corrected first |
| 2 | `cargo test`, no wheel tests, none ignored | `cd tools/pisoportal && cargo test` | pass | `test result: ok. 131 passed; 0 failed; 0 ignored`; no warnings |
| 3 | `test_flow.py` | `python3 tests/test_flow.py` (after `cargo build --release`) | pass | `223 checks, 0 failures` |
| 4 | four router test files | `python3 router/tests/test_*.py` | pass | 460/0, 232/0, 115/0, 78/0 (table above) |
| 5 | soak runs to the end, no wheel step | `python3 tests/test_soak.py --quick` | pass | `6 checks, 0 failures`. Only change: it no longer cuts `wheel.log` in its power-cut step. Not run without `--quick` (the full soak is long) |
| 6 | footprint passes; program smaller | `python3 router/tests/test_footprint.py`; sizes above | pass for the test; size shown on x86_64 only | `9 budgets, 0 failures`. Smaller: 978,992 to 896,208. The `wheel.html` budget line is removed. The 1200 KB program budgets are **not lowered yet** because the committed programs are still the old ones until CI rebuilds them; lower them then |
| 7 | a test asserts no setup script writes `WHEEL=` | `cargo test no_setup_script_writes_a_wheel_switch` | pass | in the 131 |
| 8 | customer page: no Spin button, no odds text, no wheel flag in idle, paid, paused, final | new checks in `test_flow.py` | pass | `033:` checks on the idle, final and paused views (`"wh"` absent), on the page (`Spin the wheel`, `id="wh"`, `/wheel` absent), `/wheel` and `/wheel/state` return the ordinary customer page, `POST /wheel/spin` is 405 |
| 9 | console: no wheel panel, `GET /api/wheel` 404 | new checks in `test_console.py` | pass | `116 checks, 0 failures`; `/api/wheel` GET and POST are 404, console page and `/ui.js` contain no "wheel" |
| 10 | security rules | `bash scripts/check_security_rules.sh` | pass | `security rules: all passed` |
| 11 | setup files rebuilt, `setup/RELEASE` raised | `python3 tools/build_piso_setup.py` then `--check` | pass | `setup/RELEASE` 1.5.24 to 1.5.25; `--check`: "up to date" |
| 12 | money unchanged | fixture ledger, same script before and after (below) | pass | outputs byte-identical (`diff` and `cmp` empty) |
| 13 | owner's wheel data survives; one `alerts.txt` line | real binary run twice (below) + unit test | pass | sha256 of the three wheel files unchanged; one line, not two |

### Criterion 12: how the money was checked
A script builds a fixture `revenue.csv` of 40 hash-chained rows (400 pesos; chain made the way `ledger.rs` does) and runs `pisoportal ledger`, `verify`
and `reconcile` against it, once with the program built from the base commit and once with the program built from this branch. The coin box is
unreachable in both runs, so `reconcile` answers `NOBOX` with the ledger total; the figures that matter (ledger total, chain check) are exact.
```
$ pisoportal ledger  (exit 0)
Sales record: 1 month of detail kept, none deleted. Lifetime total: 400 pesos.
$ pisoportal verify  (exit 0)
OK 40 400
$ pisoportal reconcile  (exit 4)
RECONCILE NOBOX (needs firmware 3.2.1 or later) ledger=400
revenue.csv sha256 f7a3cc8948018288c31a62bd72456247a3bca9d2463f6e4bb83db8abec19b755
pesos in fixture 400
```
Identical before and after. This does not cover a live box answering (`Ok`/`Mismatch`), but no code in `reconcile.rs`, `ledger.rs` or the credit path was touched; the diff only deletes wheel code and the `wh` field.

### Criterion 13: the safe update
Fixture `DATA_DIR` with `wheel-confirm.txt`, `wheel.log`, `wheel.log.1` and an older `alerts.txt`; the release program was started twice (`serve`, 3 s each):
```
wheel-confirm.txt: OK
wheel.log: OK
wheel.log.1: OK
2026-10-01 08:00 an older alert
2026-10-28 17:20 the spinning wheel was removed in this release; your wheel.log is kept
```
The test was written first and failed (`left: 1 right: 2`, stub writing no line); logged in `ledger/negative-constraints.md`.

## Gates (constitution rule 7)
- [x] Area checks from `CONTRIBUTING.md` for the router add-on (`cargo test`, `test_flow.py`, the six setup tests, footprint, soak `--quick`, `test_console.py`)
- [x] `bash scripts/check_security_rules.sh`
- [ ] Python `ruff check`, `mypy`, `pytest -x` on touched files: **not run**. The touched Python are the repo's own check scripts (`tests/*.py`), run directly
- [x] Generated files regenerated with `tools/build_piso_setup.py`, `--check` passes

## Not tested
- **`pisoportal-mipsel` size after:** the router programs in `tools/pisoportal/bin/` are cross-compiled and committed by CI (`router-program` workflow); only x86_64 builds here. The committed programs, and so the setup files that embed them, still contain the wheel until CI rebuilds them. The x86_64 shrink is the evidence; fill in the mipsel number from the CI build.
- **Signed console script:** `website/console/ui.js` / `ui.json` are the signed copy of `src/console-ui.js` that `publish-router.yml` regenerates when they differ; they were not (and must not be) edited here. Until then the website holds the old signed script.
- No router, no coin box, no phone. A real router that had the wheel on (believed to be none) was not available; criterion 13 was run on a fixture.
- Full-length soak (only `--quick`). `reconcile` with a live box (see criterion 12).
- No hardware check added: RS34 is retired (nothing was on).

## Spec corrections made (the code never got ahead of the spec)
1. `website/console/ui.js` is not hand-editable (signed, CI-made); the real sources are `src/console-ui.js`, `src/console.html` and `src/page.html`, which also held wheel text.
2. Criterion 1 ("nothing") contradicted criteria 7 and 13; it now names the two allowed places.
3. `/wheel*` cannot be 404: the portal answers every unknown address with the customer page (a captive portal needs that). A GET returns that page, `POST /wheel/spin` is 405, the console's `/api/wheel` is 404.

## Money and time
Not touched. The wheel never wrote `revenue.csv`; no coin, payment, price, voucher, pause, roll or ledger code changed. The mini-game's `gm` value and its two `games.enabled()` gates are exactly as before except that the removed wheel half of each condition is gone (it was always false). A spin that was journalled but not applied at the moment of the update is abandoned, as the spec says; the alert line tells the owner.
