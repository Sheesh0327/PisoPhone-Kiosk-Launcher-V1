# Validation: 021 Window record at arm (AO-11)

Run in this cloud container (x86-64 Linux, fake box, fake ndsctl). Nothing ran on a real router; the power cut is simulated (kill -9, and a half-written last row).

| # | Success criterion | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | `test_window_record.py`: record while armed; a coin hidden from the router is credited once after a kill; an empty window leaves no record. Red first | `python3 tests/test_window_record.py` | **pass** | 14 checks, 0 failures. **Red before the change**: "FAIL: the coin the router never saw is credited after the restart" (nothing credited, no record while the box was armed). Logged in `ledger/` |
| 2 | `test_soak.py --strict`: 0 stranded in 70 kills including 20 power cuts, every coin once | `python3 tests/test_soak.py --strict` (240 windows, 10.7 min) | **pass** | 429 pesos put in = **429 credited**, 0 stranded in 70 kills (before the change: 4 stranded by 3 kills in the same run), `verify`: `OK 260 429`; 10 checks, 0 failures; memory 3.2 -> 4.0 MB, threads 8, files 11 |
| 3 | `cargo test`, `test_flow.py`, `test_link_loss.py` | | **pass** | cargo 156 passed; test_flow 202 checks (one check updated: since this feature the window that has just opened has its own record on flash, so "open is empty" became "only its own record, no `.rec`"); link_loss 16 |
| 4 | Hardware check added | `docs/REAL_WORLD_TESTING.md` | **added, not run** | RS45 |

## Not tested
A real power cut on a real router (the filesystem's real flush behaviour, the box's real timing): RS45. The quick-and-dirty cost estimate "two tiny writes per empty window" was not measured on flash. Coins that are already stranded on a box from before this change stay there (the box keeps them; `reconcile` shows the difference).

## Money and time
Credit is still once per window id: recovery (`recover()`) is unchanged and credits what the box holds for the window, then acknowledges. Only the moment the record is written changed (when the box is armed, synced, once; an empty window removes it). Checked by the new test, by the soak (0 stranded in 70 kills; every coin once, 40 devices' paid time as expected), and by `test_flow.py` (202 checks) and `test_link_loss.py`.
