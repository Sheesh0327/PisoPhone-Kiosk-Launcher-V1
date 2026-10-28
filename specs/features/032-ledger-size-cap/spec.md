# Feature 032: keep the revenue ledger under 1 MB

Status: **approved by the owner 2026-10-28**   Branch: `feat/032-ledger-size-cap`   Area: router
Backlog rows: AO-02 (`specs/spec_02_always_on.md`), roadmap phase 6   Release: router

## Intent
A shop that runs every day writes about 300 sale rows a day. At roughly 42 bytes a row that is about 4.6 MB a year, and a
16 MB router has under 2 MB of flash free. Left alone, the ledger eventually fills the flash and the next sale row cannot be
written: the shop keeps selling and stops recording. This feature gives the ledger a size budget (1 MB by default) and deletes
the oldest closed months to stay inside it, **without ever changing the lifetime coin total that `reconcile` compares with the
box.** It serves rule 1: money and time are never wrong.

## What is already built (read in the code on 2026-10-28, do not rebuild it)
Monthly segments landed with AO-02's first half and are live in `tools/pisoportal/src/ledger.rs`:
- `revenue.csv` is the current month. A closed month is `revenue-YYYY-MM.csv` (`segment_path`, `roll_if_new_month`).
- The first row of every new file is a **`carry` row**: `time,<last hash of the previous file>,<running coin total>,0,carry,<hash>`.
  So each file states the total of everything before it and verifies on its own.
- `scan` reads a file one line at a time (no whole-file read). `verify_all` walks the segments oldest first and checks each
  file's carry row against the previous file's last hash and coin total. `read_recent` reads newest first and stops early.
- `reconcile` compares the ledger's coin total with the box's lifetime coin count.

**Nothing deletes a closed segment.** That is the whole of this feature.

## Why the total is safe
The running total is held in the `carry` row of the **oldest file that still exists**, and that file is never deleted (only files
older than it are). Deleting a closed segment therefore cannot change the ledger's total, and `reconcile` keeps working
unchanged. This is a property of the format that already exists; this feature only relies on it.

## Invariants touched
- **Money and time:** the coin path, pricing, settling and `append_row` are not changed. Pruning runs **after** a sale row is
  safely on flash, never before one, and a pruning failure never stops a sale from being recorded (the same rule
  `append` already applies to `recover_roll` and `roll_if_new_month`). The lifetime coin total and `reconcile` are unchanged,
  proved by a test that reconciles before and after a prune.
- **Only owner-signed software:** not touched. Pruning installs nothing.

## Constraints
- The row format `time,plan,pesos,minutes,kind,hash` and the `carry` row do not change. Older routers' files must still be read.
- A deleted month must stay **detectable**. The hash chain exists so that a removed row is noticed; deliberate deletion must
  therefore leave a record, or the ledger stops proving anything. See `revenue-pruned.txt` below.
- Crypto stays `sha2`, `hmac`, `p256` (`CLAUDE.md` section 6). Pruning adds no new crypto: it reuses `chain`.
- `scan` must stay streaming; no whole-file read is introduced (AO-03).
- Never delete `revenue.csv` (the current month), and never leave zero ledger files.
- Flash writes stay small: the new record is one short line a month.

## Schema: `revenue-pruned.txt` (new, in `DATA_DIR`)
One line per deleted month, oldest first, written **before** the segment is deleted:

```
month,rows,pesos,last_hash
2026-01,8431,8431,9f2c1ab40e77
```

`pesos` is the running coin total at the end of that segment (exactly what the next file's `carry` row states; for a first-ever
segment with no carry row it is simply its own total), `last_hash` its last row's hash. (Corrected 2026-10-28: the first draft said
"own coin total", which cannot be compared with a carry row when an older file already had one.) The file is capped at 240 lines
(20 years, about 10 KB); past that the oldest lines are dropped and one `earlier,<rows>,<pesos>,<last_hash>` summary line stands
for all of them: the dropped rows added up, and the running total and last hash of the newest dropped month.

`verify_all` gains one check: when the oldest surviving file starts with a `carry` row **and** `revenue-pruned.txt` is not empty,
that carry row's previous-hash and total must equal the `last_hash` and `pesos` of the newest record line **older than that file's
month** (a closed segment's month is in its name; the current file's is the month of its carry row). A mismatch is `Err` exactly as
a broken chain is today. A record line for a month whose file still exists (a power cut between writing the record and deleting the
file) is not older than that file, so it is skipped: it is verified normally and is not an error, and the next run finishes the
prune. When the record is empty the check does not apply: a ledger whose oldest months were removed by hand before this feature
cannot be told from one that never had them.

## Settings
- `LEDGER_MAX_KB` (default **1024**): the budget for all ledger files together (`revenue.csv` plus every `revenue-YYYY-MM.csv`).
  0 turns pruning off. Clamped to 256..16384. Read like `GAME_MINUTES`: from `DATA_DIR/settings.txt`, editable in the console's
  Settings.
- `LEDGER_KEEP_MONTHS` (default **3**): the number of closed months the owner wants kept. **Owner decision D2: the budget wins.**
  Pruning stops as soon as the files are inside the budget and otherwise deletes the oldest closed month, even when that leaves
  fewer than `LEDGER_KEEP_MONTHS`; when it does, the console and `piso-setup status` say "kept N of M months wanted". It is never
  a reason to stay over the budget. The current file and a carry-row start are never deleted; if the current file alone is over the
  budget, nothing more can be done and that is reported.

## When pruning runs
Only at a month roll, inside `append`, immediately after `roll_if_new_month` succeeds and **after** the new sale row is written.
A month cannot by itself exceed a 1 MB budget (about 20,000 rows, roughly 66 days at 300 sales a day), so a monthly check is
enough and keeps the common `append` path untouched.
The budget is checked at the roll, with the new month almost empty, so the files fit the budget right after a roll and the current
month then grows on top: the most the ledger can hold between two rolls is **the budget plus one month of rows** (about 380 KB
for 300 sales a day, so about 1.4 MB, still inside the 2 MB of flash). Criterion 1 is measured right after a roll. Errors are swallowed exactly as `roll_if_new_month`'s are.

Order of operations, so that any power cut is safe:
1. Compute the total size of all ledger files. While it is over the budget and more than `LEDGER_KEEP_MONTHS` closed segments
   remain, pick the oldest closed segment.
2. Append its line to `revenue-pruned.txt` and `fsync` it.
3. Delete the segment.
A cut between 2 and 3 leaves a record for a file that is still there, which the rule above tolerates and the next roll finishes.
A cut before 2 changes nothing.

## Success criteria
Each becomes a line in `validation.md`.
1. `cargo test`: 24 months of rows written, budget 1024 KB, `LEDGER_KEEP_MONTHS=3`, a month small enough that 3 months fit. All
   ledger files together are under 1024 KB afterwards, `revenue.csv` still holds the current month, and at least 3 closed
   segments remain.
2. `cargo test`: the lifetime coin total from `verify_all` is **identical** before and after pruning, and equals the sum of
   every row ever written. `reconcile` against a fake box with that lifetime count passes both before and after.
3. `cargo test`: `verify_all` returns `Ok` on a pruned ledger, and `Err` when a segment is deleted **without** its
   `revenue-pruned.txt` line (deliberate deletion is recorded; silent deletion is still caught).
4. `cargo test`: a power cut between writing the record and deleting the file leaves the ledger verifying `Ok`, and the next
   roll completes the prune. A cut during the month roll itself still behaves as it does today (`recover_roll`).
5. `cargo test`: `LEDGER_MAX_KB=0` deletes nothing. A busy shop (3 months need more than the budget) is pruned to the budget and
   reports fewer months than `LEDGER_KEEP_MONTHS` (D2). A current file that alone exceeds the budget deletes every closed
   segment, never `revenue.csv`, and reports that the budget could not be reached.
6. `cargo test`: `read_recent` and the console report return the same rows for a retained period before and after a prune.
7. `cargo test`: `revenue-pruned.txt` stays at or under 240 lines over 30 simulated years, and the summary line keeps the
   dropped count and total.
8. `cargo test`: peak extra memory during a prune of 24 segments stays under 64 KB (no whole-file read, AO-03).
9. `python3 tests/test_flow.py` and `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`,
   `test_setup_tools.py` pass unchanged.
10. `bash scripts/check_security_rules.sh` passes.
11. The console front page and `piso-setup status` show: months kept, months pruned, and the lifetime total — so an owner can
    see that older detail is gone and the total is not.
12. A router updated from 1.5.x with one single `revenue.csv` and no segments is pruned correctly on its first month roll and
    keeps its total (upgrade path from before segments existed).
13. `setup/RELEASE` raised; the installer stays inside its budget (`python3 tools/build_piso_setup.py --check`).

## Out of scope
- Any change to the coin path, pricing, settling, vouchers or the box link.
- `wheel.log` (AO-04, its own row) and the phone's `payment_receipts` (AO-08).
- Sending pruned segments anywhere before deletion (see D4), and any new backup mechanism.
- Monthly segmentation itself: it already exists.

## Owner decisions
- **D1 — the budget.** **Answered: 1024 KB.** At the measured 42 bytes a window that is roughly 2.5 months for a busy shop
  (300 sales a day) or about 8 months for a quiet one (100 a day). Accept 1024 KB, or name another number.
- **D2 — budget or history, which wins?** **Answered: (a), the budget wins.** `LEDGER_KEEP_MONTHS` is a floor only when the
  budget allows it; when they conflict the 1024 KB budget is kept and fewer months are retained.
  (original question kept for the record:) These conflict: 3 months of a busy shop is about 1.1 MB, already over 1 MB. Either
  (a) the budget wins and a busy shop keeps about 2.5 months, or (b) `LEDGER_KEEP_MONTHS` wins and a busy shop's ledger is
  allowed past 1 MB. Proposed: **(a)**, with `LEDGER_KEEP_MONTHS=3` as a floor only for shops quiet enough to afford it.
- **D3 — is deleting sale rows acceptable to you at all?** **Answered: yes** (implied by the owner declining the export in D4
  and choosing the budget in D2, 2026-10-28). Per-sale detail older than the budget is permanently gone; totals, monthly
  figures and `reconcile` survive. This deletes per-sale detail permanently. The lifetime total,
  the monthly totals and `reconcile` all survive, but a row from a deleted month cannot be produced again. If your record-keeping
  (BIR or otherwise) needs per-sale detail for longer than the budget allows, say so and the answer is D4 instead of this
  feature. I am not qualified to judge that and have not treated it as settled.
- **D4 — offer a download before deleting?** **Answered: no, not wanted.** Out of scope. A console button that saves the closed segments to the owner's computer, so detail
  is kept off the router. Not in this feature unless you ask; it is the natural answer if D3 is a problem.
