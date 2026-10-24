# Validation: 014 Router soak harness

Run on: this cloud container (x86-64 Linux, 4 cores), 2026-10-23/24, program built with `cargo build --release` from the unchanged
sources. The numbers below are from `test_soak.py` runs, copied from its report and its `--json` file.

## What the default run did
`python3 tools/pisoportal/tests/test_soak.py` (seed 1): **240 coin windows** (1 to 3 coins each, 429 pesos) among 40 devices in
**10.5 minutes**; the portal was started **71 times**: **70 `kill -9`** of which **20 were power cuts** (kill, then a half-written
last row on `revenue.csv` and `wheel.log`), some before a window and most in the middle of one. The last 120 windows ran in one
undisturbed process. `--quick` (36 windows, 8 kills, 3 power cuts) takes 95 s.

## Criteria

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | default run: real portal, fake box, fake `ndsctl`, under 15 min; `--quick` under 2 min | `python3 tests/test_soak.py`; `--quick` | **pass** | default 10.5 min, 9 checks, 0 failures; quick 95 s, 6 checks, 0 failures |
| 2 | 50 kill -9 and 20 power cuts, portal restarted each time | report line "kill -9 70 (power cuts 20)" | **pass** | `kill -9 70 (power cuts 20)`, `process starts 71`; the check "the planned kills and power cuts were done" passes |
| 3 | every coin credited exactly once (or stranded and reported); ledger verifies; paid time per device | the report | **pass, with one finding** | 429 pesos put in = **425 credited + 4 stranded**; `pisoportal verify`: `OK 256 425` (256 lines, 425 pesos; the half-rows the power cuts left never count in the total); every device that paid holds exactly 6 minutes per credited peso |
| 4 | report: RSS, threads, fds every 25 windows; file sizes start and end; coins in vs credited; kills | the report and `--json` | **pass** | tables below |
| 5 | last half, no kills: RSS, threads, fds within 10% of the middle | `resource_verdicts` in the JSON | **pass** | RSS middle 3,810 kB, end 3,908 kB (limit 4,191); threads 9 / 9 (limit 11); fds 9.5 / 9 (limit 11.5). RSS was still rising at the end of this short run; the long run shows it levels off, see "Memory" |
| 6 | `--json PATH` | the file was written and read for these tables | **pass** | |
| 7 | router program unchanged | `git diff beta -- tools/pisoportal/src setup router tools/pisoportal/bin .github website` | **pass** | 0 lines (the only non-spec file changed besides the new test is `tests/fakebox.py`, +6 lines, opt-in) |

## Resources of the portal, per 25 windows (default run)
| window | process starts so far | windows in this process | RSS kB | threads | open files |
|---|---|---|---|---|---|
| 0 (start) | 1 | 0 | 3,188 | 7 | 8 |
| 25 | 13 | 1 | 3,464 | 9 | 11 |
| 50 | 29 | 0 | 3,380 | 7 | 8 |
| 75 | 46 | 2 | 3,600 | 9 | 9 |
| 100 | 60 | 1 | 3,568 | 9 | 10 |
| 125 | 71 | 6 | 3,696 | 9 | 9 |
| 150 | 71 | 31 | 3,780 | 9 | 9 |
| 175 | 71 | 56 | 3,840 | 9 | 10 |
| 200 | 71 | 81 | 3,876 | 9 | 9 |
| 225 | 71 | 106 | 3,908 | 9 | 9 |
| 240 (end) | 71 | 121 | 3,916 | 8 | 9 |

Threads and open files stay at 7 to 9 and 8 to 11 (a customer's page and a window in flight each take one or two for a moment); they
do not grow. **Memory:** in the single undisturbed process of the default run RSS went from 3,696 kB (6 windows in) to 3,916 kB (121 windows in),
about +1.9 kB a window, slowing. 121 windows cannot tell a leak from allocator warm-up, so the long clean run below was made.

## Files under `DATA_DIR` (a fresh router: every file starts empty)
| file | start B | end B | growth per window | per simulated day (300 windows) | judged by this feature? |
|---|---|---|---|---|---|
| `revenue.csv` | 0 | 10,026 | 41.8 B | **12.5 KB** (about 4.6 MB a year) | no, reported: **grows without limit**, the expected red result for AO-02 and AO-03 |
| `vouchers.txt` (the roll) | 0 | 4,171 | 17.4 B | (not a rate: bounded by the 40 devices, about 100 B each; it flattens: 3,935 B at window 125, 4,171 B at 240) | no |
| `reconcile.state` | 0 | 9 | - | constant | no |
| `box_signed` | 0 | 31 | - | constant | no |
| `wheel.log` | absent | absent | - | - | **not measured**: the wheel is off in every shop, so nothing is written to it. AO-04 (it "grows without bound") is therefore **not shown by this run**; it is read in the code (`wheel_gate.rs` `log` appends one line per spin, turn on/off and restart-while-off) and needs a spin load, left to 015 |
| voucher files (`voucher_codes.txt`, `voucher_batches.txt`) | absent | absent | - | - | not used by the run (no vouchers bought) |
| `open/` | 0 | 0 files | - | - | empty at the end (every window settled) |

`revenue.csv` is the file that fills a 16 MB router's flash: at 42 B a row and 300 windows a day it is 4.6 MB a year, as the epic
estimated (it said about 5 MB). Rows a power cut tore stay in the file as lines that no one counts (the half-rows above):
`verify` reports `OK 256 425`: 256 lines for 237 real rows, so 19 stray lines from the power cuts, harmless to the total but they never go away.

## Coins in against credited
| | pesos |
|---|---|
| put in by the 240 windows | 429 |
| credited in the ledger (237 rows) | 425 |
| stranded by a kill (finding AO-11, below) | 4 (3 windows of 70 kills: 1 + 1 + 2 pesos) |
| credited twice | 0 |
| coins lost in any other way | 0 |

## Finding AO-11 (money, not fixed in this feature)
The portal writes a window's record to flash (`open/<sid>`, `core.rs` `note_open`, called at lines 1029, 1086 and 1105) when it first
**sees** a coin, not when it arms the box. A `kill -9` or power cut after the box has counted a coin and before the router has
noticed it (the box's event takes about 0.3 s, a poll up to 1 s) leaves that coin on the box with no record, so `recover()` has
nothing to credit: the customer paid and the router never credits it. In this run 3 of the 70 kills (the ones aimed into the first
half-second of a window) did it; in a shop it needs a power cut inside that fraction of a second after a coin, so it is rare, but it
is exactly the "money never wrong" rule. What exists today for the owner: the box still holds the coin and `reconcile` raises a
mismatch alert (not exercised here: the fake box counts only acknowledged coins). The soak counts such coins as stranded only when
no window record existed at the kill and none of that window's coins were credited; any other shortfall or excess fails the run.
`--strict` also fails on stranded coins (that option was **not run** with a failing result; with this seed it would exit 1).
Recorded as row AO-11 in `specs/spec_02_findings.json` with status `owner-decision` and in `ledger/negative-constraints.md`.

## Long clean run (no kills): is the memory growth a leak?
`python3 test_soak.py --windows 1000 --kills 0 --cuts 0 --period 100 --seed 3` (3.3 simulated days, 38.4 minutes, one process, 1,767
pesos in 1,000 windows, 51 customers told busy and tapping again): **all 1,767 pesos credited, `verify`: `OK 1000 1767`**.
| window | RSS kB | threads | open files | `revenue.csv` B |
|---|---|---|---|---|
| 0 | 3,244 | 7 | 8 | 0 |
| 100 | 3,984 | 9 | 9 | |
| 200 | 4,104 | 9 | 11 | 8,027 |
| 300 | 4,132 | 9 | 9 | 12,077 |
| 500 | 4,156 | 9 | 9 | 20,173 |
| 700 | 4,168 | 9 | 9 | 28,280 |
| 1000 | 4,176 | 9 | 9 | 40,435 |

RSS rises fast for the first 200 windows (the program's buffers and the page cache fill: 3.2 to 4.1 MB), then **0.06 kB a window**
(4,132 kB at window 300, 4,176 kB at 1,000): at that rate 9,000 windows add about 0.5 MB. **This is warm-up, not a leak**; threads and
open files do not move. File growth in the same run: `revenue.csv` 40.4 bytes a window (12.1 KB per simulated day), `vouchers.txt`
4.2 KB (bounded by the 40 devices). *Note:* this run was made with the first version of the paid-time check, which wrongly
failed one device (222 minutes against 228): the 38 minutes let that device's paid time run out between two purchases, which starts
a new session, as it should. The check now models expiry (`credits`), and the default and `--quick` runs above were repeated with it;
this long run was not.

## Gates (constitution rule 7)
- [x] Area checks (`CLAUDE.md` section 3, router add-on): `cargo test` in `tools/pisoportal`: **143 passed, 0 failed**.
      `python3 tests/test_flow.py` (with the changed `fakebox.py`): **202 checks, 0 failures**. `tests/test_link_loss.py`: 16 checks, 0 failures.
      `python3 router/tests/test_monitor.py`: **21 checks, 0 failures**.
- [x] `bash scripts/check_security_rules.sh`: `security rules: all passed`.
- [x] Python: `ruff check test_soak.py` (defaults): all passed; `mypy --ignore-missing-imports test_soak.py`: no issues. `pytest` does not apply (the tests are scripts, not pytest tests, like the other files in the folder). `fakebox.py` has older ruff findings that predate this change.
- [x] Generated files: none touched (`build_piso_setup.py --check` not needed: nothing under `setup/`).

## Not tested
- **Time**: the program has no fake clock, so nothing that needs wall-clock time ran: the hourly `check_ledger`, the monitor, day
  rollovers, the fair-use and clock-jump timers in their real intervals (`FAIR_INTERVAL_SECONDS` was 1 s here, 60 s in a shop).
  Growth is reported per window and per simulated day (300 windows), not over 30 real days.
- A **real** router, box, openNDS, flash or BusyBox: none. The fake box ends a window by its duration like the real one, but its
  timings (0.8 s drain, event 0.3 s after a coin) are the fake's. `wheel.log` and vouchers were not exercised.
- `--windows 9000` (the 30-day equivalent, about 6 hours) was not run; the 1,000-window run above is the longest.
- The 100,000-row ledger read time and memory (AO-03) were not measured here (a ledger of 237 rows); that is a `cargo test` in 015.

## Money and time
The router program is unchanged (criterion 7: 0 diff lines), so how coins are credited, expire and are deducted is exactly as before.
The soak is itself the check: it counts every coin it puts in (429), reads the ledger back (425 credited, `verify` clean), compares
each device's paid minutes with 6 per credited peso (every device that paid, equal), and classifies the 4 missing pesos as AO-11, not as a pass.
The only changed shared file is `tests/fakebox.py` (+6 lines, an opt-in `expire`); `test_flow.py` (202 checks) and
`test_link_loss.py` (16) still pass with it.
