# Feature 014: Router soak harness: measure first

Status: **draft (waiting for the owner's approval)**   Branch: `feat/014-router-soak-harness`   Area: router (tests and docs only)
Backlog rows: AO-10 (`specs/spec_02_findings.json`); proves AO-02, AO-03, AO-04 with numbers   Release: none

## Intent
Spec 02 asks for a router that runs 24 hours a day for a year. Today nobody knows how the portal's memory, threads, open files and
data files behave over thousands of coin windows, kills and power cuts. This feature builds one repeatable test, `test_soak.py`,
that runs the real `pisoportal` against the fake coin box and fake `ndsctl`, and writes a report. It changes nothing in the
router. Its numbers decide the size of feature 015. Serves rule 1 (money and time are never wrong) over long running time.

## Invariants touched
- Money and time: **not touched.** No router program change, so coins are credited exactly as before. The soak itself is a money
  check: it counts every coin it puts in and compares with what the ledger and the paid time show.
- Only owner-signed software: not touched. No `setup/RELEASE` raise, no generated file, nothing installed anywhere.

## Constraints
- Files changed: `tools/pisoportal/tests/test_soak.py` (new), `specs/**`, `docs/REAL_WORLD_TESTING.md` only if a line is needed
  (section 8 is not moved or renamed). Nothing under `src/`, `setup/`, `router/` (except tests), `bin/`, `website/`.
- Builds on `tests/harness.py`, `fakebox.py`, `fake_ndsctl.sh`; those are not changed, or only by a change that every other test
  still passes with.
- The portal binary is the one `cargo build --release` makes for this machine (x86), as for `test_flow.py`.
- Default run under 15 minutes; `--quick` under 2 minutes. Not added to `.github/workflows` in this feature.

## Spec 02 edit made here (AC1 as written cannot be met without changing the program)
AC1 of the epic asks for 30 simulated days at about 300 windows a day, with "simulated time (clock steps, not sleeping)". The
program reads the real clock and real `Instant`s in many places; making time steppable is a router program change, which this
feature must not make. Measured on this machine (2026-10-23): the shortest honest coin window is **2.1 s** (1 s first wait, a
coin at 0.2 s, 1 s idle wait, 0.85 s for the fake box to drain), and the portal runs one window at a time. So 9,000 windows would
take about 5.3 hours.
**Choice (the owner's instruction for this case):** do not change the program; run as many real windows as fit the budget, in real
time with shortened waits (`COIN_*` settings the program already has), and report **growth per window and per simulated day**,
where one simulated day = 300 windows. The default run is about 330 windows (about 1.1 simulated days); `--windows N` runs more
(for example `--windows 9000` is the 30-day equivalent, about 5 hours, run by hand or overnight). What this gives up: a leak or
growth that needs wall-clock time (a timer that fires hourly, `check_ledger` once an hour, day rollovers) is not exercised;
only per-window and per-restart effects are. That is stated in the report's header, not hidden.

## Success criteria
1. `python3 tools/pisoportal/tests/test_soak.py` (default) runs the real portal for about 330 coin windows with the fake box and
   `ndsctl`, among 40 devices, in under 15 minutes, and prints a report; `--quick` does about 40 windows in under 2 minutes.
2. The run includes `--kills` (default 50) `kill -9` of the portal, some in the middle of a window, and `--cuts` (default 20)
   simulated power cuts (kill -9, then a half-written last row added to the files that are appended to, the way a cut write
   looks; every file the program writes is `fsync`ed, so nothing else is lost). The portal is started again each time.
3. Every coin put in is credited exactly once: the sum of `pesos` in `revenue.csv` equals the coins put in, no coin twice, the
   ledger verifies (`pisoportal verify`), and the paid time of each device equals what its coins bought. A mismatch is reported
   with the window, the kill that preceded it and the coin counts, and the run exits 1.
4. The report lists, every 25 windows (about 2 simulated hours at 300 a day): the portal's RSS, threads and open files; the size
   of `revenue.csv`, `wheel.log`, the roll (`vouchers.txt`), the voucher files and every other file under `DATA_DIR`, at the start and
   at the end; coins put in against credited; kills and power cuts done; growth per window and per simulated day for each file.
5. Resource pass rule: in the longest stretch with no restart (the last half of the run has no kills), RSS, threads and open files
   at the end are not more than 10% above the middle of that stretch (threads and files: or +2). File growth is **reported, not
   judged** (the expected red result for AO-02 to AO-04).
6. Machine-readable result: `--json PATH` writes the same report as JSON, so 015 can compare before and after.
7. Router program unchanged: `git diff beta -- tools/pisoportal/src setup router/*.sh router/*.init tools/pisoportal/bin` is empty.

## Out of scope
- Any router change (015, 016), the monitor, `check_ledger`, Telegram, flash space. The wheel spinning (the wheel is off in every
  shop; `wheel.log` is measured over restarts only, and a spin load is left to 015 if the owner turns the wheel on).
- Wall-clock effects over days (see the spec edit). Real hardware (RS40 to RS43 are feature 019).
- Adding the soak to CI.

## Owner decisions
None needed for 014. (D1 to D3 belong to feature 015.)
