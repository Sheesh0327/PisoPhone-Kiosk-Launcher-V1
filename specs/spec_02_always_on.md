# Spec 02: always on (a shop that runs 24 hours a day, every day, for a year)

Status: **approved by the owner 2026-10-24 (answers in section 8)**   Written: 2026-10-23 against `main` at `3d822f6` (`beta` at `a994cd4`)
Related: `specs/spec_01_codebase_refactor.md` (R3 reliability rows are taken over here), `docs/maintainers/HARDENING_PLAN.md`
section 3, `specs/roadmap.md` phase 6, `docs/REAL_WORLD_TESTING.md` section 8 (soak).

## 1. Goal
Serves rule 1 (money and time are never wrong) over **long running time**, not single events. Today each part is tested for
one coin, one power cut, one clock jump. Nothing proves what happens after 30 days without a restart, after 100,000 ledger rows,
with flash nearly full, or when a part hangs instead of crashing. A shop that never closes must:
- keep crediting every coin exactly once after weeks of uptime, many power cuts and many restarts of any part;
- notice by itself when a part hangs, restart it safely (never with a coin in flight) and tell the owner;
- never fill the router's flash or the phone's storage with its own records;
- tell the owner, without a programmer, that something is wrong and what to do.

## 2. Non-goals
- New products or screens. Protocol changes (HARDENING_PLAN items 1 to 3) stay in roadmap phase 4.
- Security rows of spec 01 (R2) and the website rows (WEB-1, WEB-2): their own features.
- The print server (another project). Hardware changes (UPS, smart plugs) are advice in `docs/`, not code.

## 3. Invariants touched
- **Money and time:** the coin path itself is not rewritten. What changes around it: when a part may restart (only through the
  existing guards: firmware `HealthPolicy.h` + `canPerformRebootOrOta()`, router window record settled on start), and how the
  router's sales record (`revenue.csv`) is split into segments. A segment roll must keep the hash chain and the totals that
  `reconcile` compares with the box, byte for byte provable by a test. Every feature says how it checked credit, expiry and
  deduction (`CLAUDE.md` section 6).
- **Signed software:** not touched. Restarts and health checks never install anything.

## 4. Context manifest (hand-written source only)
| area | purpose | files | lines |
|---|---|---|---|
| router | portal program | `tools/pisoportal/src/{core,console,http,ledger,reconcile,wheel_gate,main}.rs` | 1794, 898, 916, ~250, -, ~90, - |
| router | monitor, services | `router/piso_monitor.sh`, `router/pisoportal.init`, `router/piso_monitor.init` | 188, 15, 15 |
| router | test harness | `tools/pisoportal/tests/{harness,fakebox}.py`, `fake_ndsctl.sh`, `test_flow.py`, `test_link_loss.py` | - |
| router | setup | `setup/src/90-updates.sh`, `96-space-backup.sh`, `60-summary.sh` | - |
| firmware | loop, health, worker | `esp32_firmware/src/main.cpp`, `include/HealthPolicy.h`, `src/WebServerAuth.cpp`, `src/WebSocketsUdp.cpp`, `src/Config.cpp`, `src/HardwareManager.cpp`, `src/Discovery.cpp`, `src/Diagnostics.cpp` | 343, ~120, 537, 522, 818, 345 |
| app | engine, service, db | `service/KioskEngine.kt`, `KioskService.kt`, `CrashReporter.kt`, `receiver/KioskWatchdogReceiver.kt`, `db/PaymentDao.kt`, `system/KioskSystemMonitor.kt` | - |
| docs | hardware checks | `docs/REAL_WORLD_TESTING.md` sections 4 and 8 | - |

## 5. Schemas
- **Router `revenue.csv`** (only if the owner says yes to decision D1): unchanged row format
  `time,plan,pesos,minutes,kind,hash`. New: at the first row of a new month the file is moved to `revenue-YYYY-MM.csv` and the
  new file starts with one row of kind `carry` whose `pesos` is the running total and whose chain starts from the old file's last
  hash. `verify`, `reconcile`, `report` read the segments in order. Older routers' single file is the first segment.
- **Router `wheel.log`:** kept to the last 256 KB (one `.1` copy). It holds no money.
- Everything else unchanged: NVS keys, the roll, the window record, voucher files, Room schema (`app/schemas/*`), protocol vectors.

## 6. Work packages
Confidence: **confirmed** = read in the code on 2026-10-23; **reported** = from spec 01 or HARDENING_PLAN, not re-read.

### Findings new in this spec (AO = always on)
| id | conf. | where | problem | fix | test | release |
|---|---|---|---|---|---|---|
| AO-01 | confirmed | `router/piso_monitor.sh` `do_tick` (line ~96) | The monitor checks the box, the ledger, grief and the report, but **not the portal itself**. A hung (not crashed) `pisoportal` is not restarted by procd: customers cannot pay until someone reboots. (HARDENING item 8, roadmap 6.1) | Each tick: `/ping` the portal with a 5 s limit; after 3 failures in a row restart it with the init script and alert once; alert again when it is back. Never restart twice within 10 min. | `router/tests/test_monitor.py`: fake portal that hangs; 3 ticks restart once; 4th tick alerts nothing new. | router |
| AO-02 | confirmed | `tools/pisoportal/src/ledger.rs` `append` | `revenue.csv` on flash (`/etc/coinslot.d`) is append-only and never split. At ~50 bytes a row and 300 rows a day it grows about 5 MB a year; routers with 16 MB of flash have under 2 MB free (feature 010). When flash is full the next sale row cannot be written. | Decision D1. Monthly segments (section 5), checked by `verify` across segments. | `cargo test`: 3 months of rows, roll, chain and totals equal to one big file; a torn row at the roll; a power cut between move and first `carry` row. | router |
| AO-03 | confirmed | `ledger.rs` `verify_torn` (`read_to_string`) | `verify` reads the whole file into RAM every hour (`check_ledger` in the monitor). At 100,000 rows that is about 5 MB on a router with 64 MB RAM and MIPS speed. (spec 01 RT-16) | Stream the file line by line; with segments only the current one is re-read, closed segments keep a stored last hash and total (RT-16's incremental verify). | `cargo test`: 100k rows verify under 50 ms after the first (x86), peak memory below 64 KB extra. | router |
| AO-04 | confirmed | `tools/pisoportal/src/wheel_gate.rs` `log` | `wheel.log` grows without bound. | Cap at 256 KB, keep one `.1`. | `cargo test`. | router |
| AO-05 | confirmed | `piso_monitor.sh` | Nothing watches free flash after setup. | Each hour: `df /overlay`; alert under 1 MB and under 256 KB (once each a day); the portal never stops recording coins because of it. `piso-setup status` and the Check-up show the same. | `test_monitor.py` with a fake `df`. | router |
| AO-06 | confirmed | `piso_monitor.sh` | Every alert needs Telegram. A shop without Telegram is never told anything. | Write each alert also to `/etc/coinslot.d/alerts.txt` (last 20 lines, a small file); the console front page and the setup program's Check-up show them. | `test_monitor.py`, `test_console.py`. | router |
| AO-07 | confirmed | `esp32_firmware/src/WebServerModule.cpp:51`, `WebServerAuth.cpp:71` | The AuthWorker task (sends signed acks to phones) is not on the task watchdog and nothing checks it is alive. If it hangs, phones stop getting credit while the box looks healthy; payments stay queued (no loss) but customers wait. | The worker bumps a counter each pass; `loop()` checks it every 10 s. No progress for 60 s while the queue is not empty: log, and let `HealthPolicy` restart the box when `canPerformRebootOrOta()` allows. Pure rule in `include/` with a host test. | new `host_tests/worker_liveness_test.cpp`. | firmware |
| AO-08 | confirmed | `app/src/main/java/com/pisophone/kiosk/db/PaymentDao.kt` | `payment_receipts` is never pruned (only `coin_events` keeps 500). It is the exactly-once record, so pruning it is dangerous; growth is small (about 1 MB a year a phone) but unmeasured. | **Measure only** in this spec: log the row count in the health snapshot. No pruning without decision D4. | unit test of the snapshot. | app |
| AO-09 | confirmed | `tools/pisoportal/src/console.rs:795-806`, `boxview.rs:207-211` | One thread per console / box-view connection, no cap. 200 idle sockets from a guest exhaust memory on a 64 MB router. (spec 01 RT-09; `http.rs:394` already uses `http::Limits`) | Reuse `http::Limits` (cap and read timeouts) on both listeners. | `test_console.py`: 200 idle sockets, `/ping` and the coin page still answer. | router |
| AO-10 | confirmed | no test exists | No long-run test: memory, threads, open files and file sizes over weeks of simulated use are unknown. | A soak harness (feature 014) on top of `tests/harness.py` and `fakebox.py`. | the harness itself (section 7). | none |

### Rows taken over from spec 01 (R3 and R6), unchanged in meaning
| ids | what | prior work |
|---|---|---|
| RT-17 | `recover()` on its own thread, so a dead box does not delay fair use and `check_clock` | none |
| RT-10, RT-18, RT-08, RT-26, RT-22 | atomic nft reload; console port closed for `0.0.0.0`; strict JSON reader; unescaped strings; `..` refused in backup import | `claude/sleepy-gauss-rn31gl@748add9` (cherry-pick, do not rewrite) |
| FW-04, FW-07 | one switched-off phone does not stall acks for others; non-blocking WebSocket handshake | none |
| FW-08, FW-10, FW-11, FW-12, FW-13, FW-14 | pulse reset and master clock under lock; refuse to arm without a boot counter; params length; 512-byte ACK; pure `parseAckBody()` | none |
| FW-17, FW-18 | beacon every 15 s, unicast probe replies, reused `/api/status` buffer: the largest free block must not shrink over 24 h | none |
| APP-01, 02, 03, 05, 06, 08, 09, 10 | the app's money rows | `claude/sleepy-gauss-rn31gl@748add9` (tests written, never compiled) |
| APP-21, APP-13, APP-14, APP-15 | one update download; one Wi-Fi join at a time; less polling | none |

### Features (one area each, in this order)
| # | name | area | rows | release |
|---|---|---|---|---|
| 000 | **Step 0, not a feature:** bring `main`'s 7 commits (goldens fix, `LauncherScreen.kt`, app builds 518/519) into `beta` and correct `specs/STATUS.md` and `specs/RELEASE_STATE.md` (they still say the app is build 495). Needs D0. | docs + merge | - | none |
| 014 | Router soak harness: measure first | router tests | AO-10 | none |
| 015 | Router always on: the monitor watches the portal and the flash, bounded files, alerts without Telegram | router | AO-01 to AO-06, AO-09, RT-17 | router 1.5.19 |
| 016 | Router hardening rows from spec 01 | router | RT-10, RT-18, RT-08, RT-26, RT-22 | the same 1.5.19 |
| 017 | Coin box always on | firmware | AO-07, FW-04, 07, 08, 10 to 14, 17, 18 | firmware 3.12.1 |
| 018 | Phone always on | app | APP-01 to 10 (not 04), APP-21, 13 to 15, AO-08 | app build |
| 019 | Hardware soak checks written and run | docs | RS40 to RS43 | none |

Why this order: 014 proves the problems with numbers before anything changes and becomes the regression gate for 015 and 016.
015 is the largest everyday risk (a hung portal, a full flash). 017 needs CI to compile (no PlatformIO registry in a cloud
session). 018 needs a machine with the Android SDK; in a cloud session only its spec and plan can be written.

**Size budget:** the installer is 1635 of 1700 KB and its source 144 of 150 KB (STATUS, feature 011). 015 and 016 must report the
new sizes in `validation.md`; if a budget would be passed, stop and ask the owner (do not raise a budget yourself).

## 7. Acceptance criteria
- **AC1 (014)** `python3 tools/pisoportal/tests/test_soak.py --days 30` runs the real portal program on x86 with the fake box and
  fake `ndsctl`, with simulated time (clock steps, not sleeping), about 300 coin windows a day, 50 random `kill -9` of the portal
  and 20 simulated power cuts (kill + the files' unsynced tail cut). It finishes in under 15 minutes and writes a report: RSS,
  threads and open files of the portal every simulated hour; size of every file under `DATA_DIR`; coins put in vs credited.
  **Pass:** every coin credited exactly once; RSS, threads and open files do not grow from day 2 to day 30 (within 10%).
  File growth is reported, not judged, in 014 (it is the red result for AO-02 to AO-04).
- **AC2 (015)** the same soak passes with: every file under `DATA_DIR` bounded except closed revenue segments; a hung portal
  (SIGSTOP) restarted within 4 minutes, once, with one alert; `alerts.txt` holds it.
- **AC3 (015)** a revenue roll proved equal to the single-file chain (section 5) in `cargo test` and `test_flow.py`, including a
  torn row and a power cut in the middle of the roll; `piso-setup reconcile` gives the same answer before and after a roll.
- **AC4 (017)** `host_tests/run.sh` passes with the new liveness and ACK tests; CI's firmware build is green; `PISO_FW_VERSION`
  raised once.
- **AC5 (018)** `./gradlew :app:testDebugUnitTest` passes on the owner's machine or in CI; `app/schemas/*` unchanged.
- **AC6 (019)** `docs/REAL_WORLD_TESTING.md` gains: **RS40** 72 h with one box, one router and two phones, the power cut every
  6 h by a timer plug, coins counted by hand vs credited; **RS41** modem off for 2 h during trading; **RS42** the box's Wi-Fi
  access point rebooted 10 times; **RS43** after 72 h: router `free`, `df /overlay`, the box's largest free block, the phone's
  battery temperature. The owner runs them; results go into each feature's `validation.md`.
- **Every feature:** the checks of its area (`CONTRIBUTING.md` table, `CLAUDE.md` section 3) and
  `bash scripts/check_security_rules.sh`; say plainly what could not be run.

## 8. Owner decisions (recommended answer first)

**Owner's answers, 2026-10-24:** D0 = the agent merges `main` into `beta` itself (not via a pull request), only when no Code
quality run on `beta` is in flight and taking `main`'s app goldens; D1 = monthly segments, never deleted automatically (the
recommended answer); D2 = yes, 3 failed `/ping`, at most once in 10 minutes; D3 = `alerts.txt` in the console and the Check-up;
D4 = no pruning, measure only; D5 = the agent opens pull requests into `beta` and fixes CI until green, **the owner merges**;
D6 = not asked, the recommended answer stands (advice in `docs/` only). Build now, in this order: 018 (app money rows), 015 + 016
(router 1.5.19), 017 (firmware 3.12.1), then the signing rows CI-1 and CI-2 (spec 01 R2) as feature 020. The fake-box fix
(HARDENING_PLAN item 3, a protocol change) is **later, its own spec**. No release, no merge to `main`, no rollout: the owner does those.

**Owner's answers, 2026-10-24 (after feature 014):** Q1 = the monitor runs on every router with the portal; without Telegram it
sends nothing and only checks, restarts and writes `alerts.txt`. AO-11 (a coin stranded by a kill or power cut before the router
notices it) = **its own router feature 021**: write the window's record to flash, synced, when the box is armed; a test that
fails first (`test_soak.py --strict`) and a power-cut hardware check; it ships in the same router 1.5.19 as 015 and 016.
Approved now: the spec and plan of 015 with D1, D2, D3 and Q1 as answered here.

- **D0** May the agent merge `main` into `beta` (step 0)? *Recommended: yes, on a branch `chore/sync-main-into-beta`, the owner
  merges it.* Until then every feature branch starts from a `beta` that lacks the app's goldens fix.
- **D1** Split `revenue.csv` by month? *Recommended: yes, closed months stay on flash; when free flash is under 256 KB the owner is
  alerted and the setup program's Owner tools download and then delete the oldest months. Never deleted automatically.*
- **D2** May the monitor restart a hung portal by itself? *Recommended: yes, after 3 failed `/ping` a minute apart, at most once in
  10 minutes; an open coin window is settled from its record on start, as today.*
- **D3** Alerts without Telegram: *recommended: `alerts.txt` shown in the console and the Check-up (AO-06).*
- **D4** Prune `payment_receipts` on the phone? *Recommended: no, measure for 3 months first.*
- **D5** May the agent open pull requests into `beta` (not merge) so that Code quality runs? CI does not run on `feat/*` pushes, so
  without this the firmware and app features cannot be compiled from a cloud session. *Recommended: yes.*
- **D6** Phones on the charger 24/7 swell their batteries. *Recommended: advice in `docs/` (a charge-limit setting or a timer plug)
  and the battery temperature in RS43; no app change in this spec.*

## 9. Definition of done
Every row `fixed`, `verified-on-hardware`, `wont-fix` (reason) or `owner-decision`; RS40 to RS43 run by the owner and written in
`validation.md`; `CHANGELOG.md`, `specs/STATUS.md` and `specs/RELEASE_STATE.md` updated after each release. The AO rows are copied
into `specs/spec_02_findings.json` (format `specs/templates/finding-row.json`) by feature 014's first task group.
