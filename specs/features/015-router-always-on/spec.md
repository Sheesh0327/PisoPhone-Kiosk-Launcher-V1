# Feature 015: Router always on

Status: **draft: waiting for the owner (decisions D1, D2, D3 below), then for approval of the spec and plan. No code is written.**
Branch: `feat/015-router-always-on` (from `beta`)   Area: router   Release: router **1.5.19** (shared with feature 016)
Backlog rows: AO-01, AO-02, AO-03, AO-04, AO-05, AO-06, AO-09, RT-17 (`specs/spec_02_findings.json`, `specs/spec_01_findings.json`);
AO-11 only if the owner says so (question Q2)
Epic: `specs/spec_02_always_on.md` (section 6, features table; AC2 and AC3 in section 7)

## Intent
A shop that never closes needs a router that keeps taking coins for a year without anyone logging in. Feature 014 measured the
router (`validation.md` there): memory, threads and open files stay flat over 240 coin windows with 70 kills, **but `revenue.csv`
grows without limit (42 bytes a window, 12.5 KB a simulated day at 300 windows, about 4.6 MB a year)**, nothing watches the portal
itself, nothing watches free flash, and an alert needs Telegram. This feature makes the router notice and survive those things:
restart a hung portal safely, bound its own files, watch the flash, and tell the owner even without Telegram. Serves rule 1
(money and time are never wrong) over long running time.

## Measured by 014 (the numbers this plan is built on)
| what | measured (240 windows, 70 kill -9 of which 20 power cuts, fake box, x86) | meaning for this feature |
|---|---|---|
| `revenue.csv` | 0 to 10,026 B: **41.8 B a window**, 12.5 KB per 300 windows, **about 4.6 MB a year**, never shrinks | AO-02 is real. A 16 MB router has under 2 MB free (feature 010): flash is full in under a year of trading |
| the roll `vouchers.txt` | 4,171 B for 40 devices (about 100 B each), flat after the first devices | bounded by the number of devices; no work |
| `wheel.log` | not created (the wheel is off in every shop) | AO-04 is not shown by a measurement, only by reading `wheel_gate.rs`; 015 needs a spin load in its test |
| RSS | 3.2 MB at start, 4.1 MB after 200 windows, then 4.18 MB at window 1,000 of a clean run (0.06 kB a window) | warm-up, **not a leak**; no row needed |
| threads / open files | 7 to 9 / 8 to 11, no growth | AO-09 (one thread per console connection) is about a guest opening hundreds of sockets, not about normal use: the test must open them |
| a torn last row after a power cut | stays in the file as a stray line (19 of them in 256 lines) | `verify` still gives the right total; the roll in AO-02 must keep that rule |
| coins | 429 put in = 425 credited + 4 stranded by a kill (AO-11) | see Q2 |

## Invariants touched
- **Money and time:** the coin path is not rewritten. What changes around it: how `revenue.csv` is split (monthly segments, section 5 of the epic: the hash chain and the totals `reconcile` compares with the box must be byte-for-byte provable equal to one big file, `cargo test` and `test_flow.py`, including a torn row and a power cut in the middle of the roll); when the monitor may restart the portal (D2: only after 3 failed `/ping` a minute apart, at most once in 10 minutes, and an open coin window is settled from its record on start, as today); `recover()` moves to its own thread (RT-17) so a dead box does not hold up a customer. Each task group says how credit, expiry and deduction were checked.
- **Only owner-signed software:** not touched. Restarting a service and alerts install nothing. The new router program and monitor script go out through the normal signed router release.

## Owner decisions (waiting for the owner; recommended answer first)
- **D1: split `revenue.csv` by month?** *Waiting for the owner.* Recommended: **yes**. Closed months stay on flash as `revenue-YYYY-MM.csv`; when free flash is under 256 KB the owner is alerted and the setup program's Owner tools download and then delete the oldest months; **never deleted automatically.** Without D1 the file keeps growing and AO-02 and AO-03's second half are dropped (only the streaming read of AO-03 stays).
- **D2: may the monitor restart a hung portal by itself?** *Waiting for the owner.* Recommended: **yes**, after 3 failed `/ping` a minute apart, at most once in 10 minutes; an open coin window is settled from its record on start, as today. Without D2 the monitor only alerts.
- **D3: alerts without Telegram?** *Waiting for the owner.* Recommended: **`/etc/coinslot.d/alerts.txt`** (the last 20 lines, a small file) shown on the console front page and in the setup program's Check-up.

Questions found while planning (not in the epic; the recommended answer is mine):
- **Q1: the monitor only runs when Telegram is set up.** `setup/src/75-checks-stage2.sh:53`, `80-commands.sh:238` and `90-updates.sh:69` start `piso_monitor` only `[ -r /etc/piso-monitor.conf ]`, which exists only after `piso-setup telegram`. So AO-01, AO-05 and AO-06 would do nothing in a shop without Telegram: exactly the shop D3 is for. *Recommended: yes, run the monitor on every router with the portal; with no Telegram settings it sends nothing and only checks, restarts and writes `alerts.txt`.* This touches `setup/src/` (installer size, below).
- **Q2: AO-11, a coin stranded by a kill or power cut between the box counting it and the router noticing it** (014 found it; `specs/spec_02_findings.json`, status `owner-decision`). Not part of 015 unless the owner says so. Options: write the window's record to flash when the box is armed (one small synced write per window, `core.rs` `note_open`), or leave it (the box keeps the coin; a `reconcile` mismatch alert is the safety net). *Recommended: decide separately; it changes the coin path, so it deserves its own feature, test and hardware check.*

## Constraints
- **Installer size:** `setup/piso-setup.sh` is **1635 of 1700 KB** and `setup/piso-setup.sh.in` **144 of 150 KB** (`router/tests/test_footprint.py`; feature 011). The portal program is 1091 KB (mipsel) of 1200 KB. The monitor script, `piso-setup` changes (Check-up, Owner tools, Q1) and the program all count. `validation.md` must report the new sizes; **if a budget would be passed, stop and ask the owner; do not raise it.** Prefer shell the installer already carries over new code; keep the alert file to a few lines of shell.
- A change under `setup/src/` or `tools/pisoportal/` changes what routers install: raise `setup/RELEASE` to 1.5.19 (once, shared with 016). Generated files (`setup/piso-setup*.sh`, `website/setup/*`, `tools/pisoportal/bin/*`) are never edited by hand: `python3 tools/build_piso_setup.py`, and the `router-program` workflow builds the binaries.
- The router program uses only `sha2`, `hmac`, `p256`; no new crates. `revenue.csv` row format `time,plan,pesos,minutes,kind,hash` does not change; an older router's single file is the first segment; `verify`, `reconcile`, `report` read the segments in order.
- `wheel.log` is capped at 256 KB with one `.1` copy; it holds no money.
- BusyBox `ash` only in the monitor; `df /overlay`, `wget`/`curl` as already used. The monitor tests use fakes (`router/tests/test_monitor.py`), nothing has run on a real router.
- Shops that have the wheel off, no Telegram, or an older router file must keep working unchanged.

## What a visitor or an owner sees without the condition
Customers see nothing new on the coin page in any case. An owner **without Telegram** (and without D3) sees nothing new and is told nothing: that is today's behaviour and what D3 fixes. An owner with `alerts.txt` and no Telegram sees the alerts on the console front page and in the Check-up, and the router's own log; an owner with Telegram gets the same text on Telegram as well (the alert text is the same). A shop that does not answer D1 keeps one growing file and a flash warning only.

## Success criteria
1. **AC2 (epic):** `test_soak.py` passes with a new `--bounded` mode: every file under `DATA_DIR` is bounded (does not grow over the second half) except closed revenue segments; a hung portal (SIGSTOP) is restarted within 4 minutes, once, with one alert; `alerts.txt` holds it. (The soak is extended first, see the plan, so it fails before the fix.)
2. **AC3 (epic):** a revenue roll proved equal to the single-file chain in `cargo test` and `test_flow.py`, including a torn row and a power cut between the move and the first `carry` row; `piso-setup reconcile` gives the same answer before and after a roll.
3. AO-03: `verify` streams the file; `cargo test`: 100,000 rows verify in under 50 ms after the first run (x86) with peak extra memory under 64 KB; closed segments keep a stored last hash and total.
4. AO-04: `wheel.log` never passes 256 KB (+ `.1`); `cargo test`.
5. AO-09: 200 idle sockets on the console and the box-view listeners: `/ping` and the coin page still answer (`test_console.py`); `http::Limits` reused.
6. RT-17: with a dead box, a customer's page and `check_clock` are not delayed by `recover()`; test with the fake box off.
7. AO-01, AO-05, AO-06 in `router/tests/test_monitor.py` with a fake portal that hangs, a fake `df`, no Telegram: 3 ticks restart once, the 4th alerts nothing new; the alert appears once when it is back; under 1 MB and under 256 KB free flash each alert once a day; `alerts.txt` keeps the last 20 lines; the console front page (`test_console.py`) and the Check-up show them.
8. Sizes reported (installer, source, program) and inside the budgets, or the owner asked.
9. Gates of the area (`CLAUDE.md` section 3) and `bash scripts/check_security_rules.sh`; `build_piso_setup.py --check`; what could not be run said plainly (nothing runs on a real router: owed hardware checks RS40 to RS43 are feature 019).

## Out of scope
RT-10, RT-18, RT-08, RT-26, RT-22 (feature 016); the coin box and the phone (017, 018); AO-11 unless Q2 is answered; deleting old months automatically; a new alert channel; changing prices, passwords or Wi-Fi from the monitor (it never does).
