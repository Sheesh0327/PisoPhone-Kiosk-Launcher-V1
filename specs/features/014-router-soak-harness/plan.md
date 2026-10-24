# Plan: 014 Router soak harness

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none relevant (ledger entries are about earlier features).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `specs/spec_02_always_on.md` | the epic: AO rows, AC1, decisions | 132 | 5000 |
| `tools/pisoportal/tests/harness.py` | `Env`, `Cust`, `wait_until`, `check` | 306 | 4500 |
| `tools/pisoportal/tests/fakebox.py` | fake coin box (control file) | 154 | 2500 |
| `tools/pisoportal/tests/fake_ndsctl.sh` | fake openNDS | 49 | 900 |
| `tools/pisoportal/tests/test_link_loss.py` | pattern of a test with recovery | 66 | 1200 |
| `tools/pisoportal/src/core.rs:940-1130` | `run_window`: the window timings | 190 | 3000 |
| `tools/pisoportal/src/ledger.rs` | append (fsync), verify | ~250 | partly |

## Task groups
- [x] 0. Spec and plan; AO-01 to AO-10 into `specs/spec_02_findings.json` (AO-10 takes feature 014).  (files: `specs/`; test: JSON loads; red result: n/a)
- [x] 1. `test_soak.py` skeleton: build/start, 40 devices (ARP file, `nds_client`), one coin window per loop, the ledger and paid-time
      accounting, `--quick`, `--windows`, `--json`.  (test: 10 windows credit 10 coins; red result: n/a, new harness)
- [x] 2. Samples every 25 windows (`/proc/<pid>/status` VmRSS and Threads, `/proc/<pid>/fd`), file sizes under `DATA_DIR`, growth per
      window and per simulated day, the resource pass rule.  (test: report fields present)
- [x] 3. Kills and power cuts: scheduled by a seeded random (`--seed`), some in the middle of a window; restart; wait for recovery; the
      torn half-row; the coin accounting with a settle step at the end.  (test: `--quick --kills 5 --cuts 3` passes)
- [ ] 4. Full default run in the background; read the last lines only. Fill `validation.md` with the numbers. Run `cargo test`,
      `tests/test_flow.py`, `router/tests/test_monitor.py`, `scripts/check_security_rules.sh`.
- [ ] 5. Feature 015 spec and plan only (no code), STATUS row, handoff note.

If a mismatch shows a coin lost or doubled by the program, stop, do not fix it here: write it up in `validation.md`, in the ledger
and tell the owner (money first).

## Release
None. Nothing raised, nothing regenerated.
