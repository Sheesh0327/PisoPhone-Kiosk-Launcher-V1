# Spec 01: codebase refactor and correctness pass

Status: **backlog epic.** Its work packages are done one feature at a time (`specs/features/`). Row status is in `specs/spec_01_findings.json`; the board is `specs/STATUS.md`. The text below is the original draft and is not rewritten.
Written: 2026-10-21, against `beta` at `7adbd17`.
Related: `specs/mission.md`, `specs/roadmap.md`, `docs/HARDENING.md` (items there are referred to as **H1–H17**).

## 1. Goal

Find and fix four kinds of problem across the whole repository, in this order:

1. **Broken logic.** Code that can get money, time or signing wrong.
2. **Sloppy code.** Dead code, copied helpers, swallowed errors, magic numbers.
3. **Inefficient choices.** Work repeated on every tick or request, CI that does the same thing many times, large committed copies.
4. **Overengineering.** Layers, paths and modes that add risk without a user.

The two project rules decide every trade-off (`CLAUDE.md` section 1): **money and time are never wrong**, and **only software the
owner signed is installed**. A refactor that does not serve one of the four goals above is out of scope.

## 2. Non-goals

- The items already tracked in `HARDENING_PLAN.md` section 3 (H1–H17). This spec links to them where a finding touches one, but does not re-plan them. Roadmap phases 3–6 own them.
- New features, UI changes, new languages or libraries (`specs/techstack.md`, "Rules for adding to this stack").
- Editing generated files (`CLAUDE.md` section 4). Every fix goes to the source and is regenerated.
- The print server (separate project).

## 3. Context manifest

Constitution rule 1. Line counts are hand-written source only. Generated files are excluded and were not read.

| area | purpose | files | lines | est_tokens |
|---|---|---|---|---|
| `app/src` | Kotlin kiosk app | 170 | 22,506 | ~225k |
| `esp32_firmware/src` + hand-written `include` | coin box firmware | ~110 | ~13,800 | ~140k |
| `esp32_firmware/web` | box dashboard (incl. vendored `jsqr.js` 10,050) | 10 | 11,972 | ~120k |
| `esp32_firmware/host_tests` | firmware host tests | 40 | 2,822 | ~28k |
| `tools/pisoportal` | Rust router program + tests | 38 | 10,234 | ~100k |
| `router/` (+ `router/tests`) | router shell scripts and Python tests | ~20 | ~4,300 | ~43k |
| `setup/src` | router installer sources | 16 | 1,984 | ~20k |
| `setup/pisophone_setup.py` | setup program (one file) | 1 | 5,030+ | ~50k |
| `scripts/` (+ tests) | owner and release tools | ~35 | ~3,500 | ~35k |
| `.github/workflows` | CI | 8 | 1,450 | ~15k |
| `website/` (hand-written) | pages, start pages, non-vendored JS | ~12 | ~3,300 | ~33k |

Negative constraint ledger (constitution rule 6): `ledger/negative-constraints.md` does not exist yet, so there are no unresolved entries to load. Create it before work starts.

## 4. How the findings were gathered and how sure they are

Four read-only audits were run, one per area. No code was compiled or run during the audits, except one Python import that reproduced ST-1. I checked four findings against the source myself: **FW-03, APP-06, ST-1 and CI-1** are marked **confirmed**. The other findings are marked **reported** and must be re-read before work starts. Findings the auditors were unsure of are marked **(U)**.

Rule for every finding: **before the fix, write a test that fails. After the fix, the same test passes.** Where only hardware can show the defect, add a numbered check to `docs/REAL_WORLD_TESTING.md` instead, and say plainly that it was not run.

## 5. Data schemas

### 5.1 Finding record (the work tracker)

Each finding is tracked as one record. Keep the records in `specs/spec_01_findings.json` once work starts. The tables in section 6 are their human view.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "RefactorFinding",
  "type": "object",
  "required": ["id", "area", "files", "category", "severity", "money_or_signing", "status", "fix", "test", "release"],
  "properties": {
    "id":       { "type": "string", "pattern": "^(APP|FW|RT|ST|SC|CI|WEB|DEAD)-[0-9]{1,2}$" },
    "area":     { "enum": ["app", "firmware", "router", "setup", "scripts", "ci", "website"] },
    "files":    { "type": "array", "items": { "type": "string", "pattern": "^[^:]+(:[0-9]+(-[0-9]+)?)?$" }, "minItems": 1 },
    "category": { "enum": ["logic", "sloppy", "inefficient", "overengineered", "dead"] },
    "severity": { "enum": ["high", "med", "low"] },
    "money_or_signing": { "type": "boolean" },
    "hardening_ref": { "type": "string", "pattern": "^H[0-9]{1,2}$" },
    "confidence": { "enum": ["confirmed", "reported", "uncertain"] },
    "status":   { "enum": ["open", "test-written", "fixed", "verified-on-hardware", "wont-fix", "owner-decision"] },
    "fix":      { "type": "string" },
    "test":     { "type": "object", "required": ["kind", "where"],
                  "properties": { "kind": { "enum": ["unit", "host", "integration", "ci", "hardware"] },
                                  "where": { "type": "string" },
                                  "fails_before": { "type": "boolean" } } },
    "release":  { "enum": ["none", "app", "firmware", "router", "site"] }
  }
}
```

### 5.2 Formats this spec changes

Only the fixes below change a stored or signed format. **Every other fix keeps all on-disk, NVS, Room and wire formats byte for byte.**

**(a) Router ledger row framing (RT-04).** The row text does not change: `<row>,<chain>\n`. What changes is how it is written:
- the whole row is built first and written with one `write_all`;
- before appending, if the file is not empty and its last byte is not `\n`, a `\n` is written first;
- `ledger::verify` treats a last line with no trailing `\n` as torn: it skips that line and reports `torn_tail=true`. It does not return BADLEDGER.

**(b) Router clock-jump record (RT-01, RT-02).** The record is a new file `/etc/coinslot.d/clockjump`, written atomically through the helper in (c):
```
v=1
detected_at=<unix seconds, synced clock>
since=<unix seconds, stale clock when the stale period began>
jump_at_mono=<monotonic seconds when the jump was detected>
delta=<seconds added>
```
`shift` moves only entries with `since <= fp < (detected_at - delta)`, so a payment made after the jump is not moved. The file is a record for the owner and for tests. Nothing reads it to decide credit except `shift`, inside the same lock.

**(c) Router durable write (RT-05, RT-06, RT-14).** There is one helper, `util::write_atomic(path, bytes)`, with this contract: write `path.tmp`, `fsync` the file, `rename` it to `path`, then `fsync` the directory. If any step fails, the helper returns an error and leaves the old file in place. Every writer listed in RT-14 uses this helper. After the change, no `fs::rename` call remains outside `util.rs`.

**(d) Voucher records under a clock jump (RT-03).** The voucher batch format stays the same. `check_clock` also shifts `created`, `expires` and `used_at` for vouchers created during the stale period, using the same window rule as (b).

**(e) Flash manifest signature (ST-2). Owner decision.** `website/flash/manifest.json` gets a `sig` field over this canonical text:
```
pisophone-flash-v1|<chip>|<version>|<sha256 lowercase hex>|<size>
```
It is signed with the owner key (the same P-256 scheme as `pisophone-fw-v1`). The setup program checks it against the public key built into the program, and refuses a manifest whose `sha256` does not match `^[0-9a-f]{64}$`.

**(f) Product in the firmware signature (SC-5). Deferred.** This changes what every box checks, so it needs a firmware release and a migration where both the old and the new signature are accepted. It is listed here so the change is known. It is not part of this spec's work.

## 6. Work packages

They run in the order R1 to R6. Each package is one or more small pull requests to `beta`. Each pull request touches **one area**, so that one release type is raised at a time (`CLAUDE.md` section 4).

### R1: Money and time correctness (high priority)

| id | conf. | where | problem | fix | release |
|---|---|---|---|---|---|
| FW-03 | confirmed | `esp32_firmware/src/DeviceNetwork.cpp:103,110` | Revenue is written before the payment is queued. A power cut between the two counts the revenue, but the phone never gets the credit. This breaks the rule "the box records the payment before anything else". | Order: enqueue, then revenue, then LED, then pushes (the order the gateway path already uses). | firmware |
| FW-02 | reported | `WebServerConfig.cpp:356` → `HardwareManager.cpp:260-273` | Every Settings save re-attaches the ISR and zeroes the pulse counter, even while a slot is armed. A coin in flight is lost. | Re-apply only when a coin-pin, guard or pulse-width field changed, and only when the slot is IDLE. Otherwise defer the change. | firmware |
| FW-01 | reported | `WebServerModule.cpp:232,312`, `WebServerConfig.cpp:146`, `HardwareManager.cpp:298-308` | Reboot, OTA, factory reset and the reset pin check only that payments are persisted. They do not check the armed slot or the pulse counter. | Add a pure `mayRestart(state)` predicate: the slot is IDLE, the ISR count is 0, and nothing is unpersisted. Use it on all four paths. For the reset pin, drain first. | firmware |
| FW-05 | reported (U) | `WebServerConfig.cpp:324-329`, `DeviceNetwork.cpp:99-101` | `minutes_per_coin` has no upper bound, and `pulses*rate*60` is computed in a 32-bit int. | Clamp the setting to 1..1440. Compute in `int64` and clamp the result. | firmware |
| FW-06 | reported | `CoinSlotManager.cpp:357-363` | A coin that arrives while the slot is IDLE is dropped with only a log line, and the log line is wrong. | Record it with `orphanRecord(..., NOT_QUEUED)`. Fix the log line. | firmware |
| RT-01 | reported, order confirmed | `fair.rs:57-62`, `core.rs:1460-1487`, `roll.rs:282-286` | The tick runs before `check_clock`. A session paid while the clock was stale can be deleted as expired before the clock shift runs. | Run `check_clock` at the start of `tick()` and in `reconnect`. Only `purge` may delete entries; read and heal paths may not. | router |
| RT-02 | reported | `core.rs:1464-1472`, `roll.rs:398-405` | `shift` also moves sessions paid after the jump, which gives free time. | Use the window rule in 5.2(b). | router |
| RT-03 | reported | `core.rs:1466-1470` | Vouchers are not shifted, so a voucher bought before a large jump can come back "expired". | 5.2(d). | router |
| RT-04 | reported | `ledger.rs:20-21` | A torn last row after a power cut makes the next row run into it, and the owner gets a false "LEDGER WAS CHANGED" alert. | 5.2(a). | router |
| RT-05, RT-06, RT-14 | reported | `core.rs:1250`, `voucher.rs:143-163,219-229` + 5 other writers | Seven temp-then-rename writers, each with different durability. Voucher files and the voucher key can be lost in a power cut. | 5.2(c). The voucher key is never overwritten once it exists and is not empty. | router |
| RT-07 | reported | `core.rs:1424-1428` | The late-coin window id is fixed (`{wid}L`), so a second late round is treated as a duplicate and its coins are acked without credit. | Use `{wid}L{boxed}`. | router |
| APP-06 | confirmed | `KioskHttpServer.kt:231` | When `amount` is missing it defaults to `1.0`, so a complimentary `/add_time` counts as a ₱1 coin. | Default to `0.0`. Check that the signed `v_sig` path still matches (amountPulses=0 only for the unsigned master path). | app |
| APP-01 | reported (U) | `KioskSessionSupervisor.kt:104-136` | An unlocked phone with deadline 0 and 0 seconds left is never locked. | When unlocked and remaining ≤ 0, lock, whatever the deadline. | app |
| APP-04 | reported (U) | `KioskStateManager.kt:167-223`, `KioskEngine.kt:271` | A failed state restore is swallowed, and the engine still accepts coins. | Return a result. The engine stays uninitialised (refuses payments) until the restore succeeds, or rebuilds the state from Room. | app |
| APP-02, APP-08 | reported | `PaymentRepository.kt:428-460` | The recovery code can turn an orphan coin event into a receipt with no credit. It is dead today, but a trap for later. | Delete both recovery steps. | app |
| APP-05 | reported | `Esp32ConnectionManager.kt:648,684-697,742` | A failed ack is swallowed and the tx id stays marked, so the phone never re-acks it in that window. | Mark the tx id only after a 2xx ack. Retry the ack on the next poll. | app |
| APP-10 | reported | `KioskEngine.kt:80-152`, `PaymentRepository.kt:163-167` | A UI callback that throws turns an APPLIED credit into FAILED. | Catch errors in the callbacks after commit. Still return APPLIED. | app |
| APP-03, APP-09 | reported | `KioskEngine.kt:396-411,323`, `KioskSessionSupervisor.kt:88-90` | Done blocks the main thread behind the health probe, and `paymentTimeout` is updated without atomic access. | Run Done on IO with its own `Mutex`. Use `MutableStateFlow.update {}`. | app |

**Acceptance criteria (R1)**
- AC1.1: Every row above has a test that fails on `7adbd17` and passes after the fix. Its id is in the test name or a comment.
- AC1.2: Firmware: the coin record order and `mayRestart` are pure functions in `include/` and covered by `host_tests`. `PISO_FW_VERSION` is raised once for the whole firmware batch.
- AC1.3: Router: `cargo test` covers `check_clock` (stale → synced with a session alive, a payment after the jump, a voucher). `test_flow.py` covers two late-coin rounds and a torn ledger tail. `setup/RELEASE` is raised.
- AC1.4: App: none of the changes alters the Room schema (`app/schemas/*` unchanged). `PaymentRepositoryUnitTest` shows that a txId is credited exactly once in every case touched.
- AC1.5: Each pull request description says how credit, expiry or deduction was checked (`CLAUDE.md` section 6), and which hardware checks were not run.
- AC1.6: New hardware checks are added to `docs/REAL_WORLD_TESTING.md`: Settings saved during a coin drop; reboot or OTA started with a slot armed; power cut between the coin and the queue; router clock jump with a voucher bought.

### R2: Signing, keys and the release pipeline (high priority)

| id | conf. | where | problem | fix |
|---|---|---|---|---|
| CI-1 | confirmed | `.github/workflows/firmware-images.yml:195-204` | After approval the job runs `reset --hard origin/main` and then runs the signing scripts from that newer tree. The version label comes from the newer tree too. | Sign from `$GITHUB_SHA`. Only the `website/` output is rebased. Fail if the artifact's commit is not `$GITHUB_SHA`. |
| CI-2 | reported | `publish-router.yml:83,116-118` | The approval fingerprint does not cover `console-ui.js`, `sign_console_ui.py` or the workflow file. | Add them to the fingerprint, or sign from `$GITHUB_SHA`. |
| ST-1 | confirmed | `setup/pisophone_setup.py:4649,4657` | `EXPECTED_OWNER_FINGERPRINT` is not defined, and `check_owner=` is the wrong keyword. With the env var unset, the Owner tools page fails with NameError. The tests set the env var to `*`, so they do not see it. | Use `EXPECTED_FINGERPRINTS["owner"]` and `check_match=False`. Narrow the `except Exception` at 4664. |
| SC-1 | reported | `scripts/make_owner_keys.py:77-85`, `scripts/check_security_rules.sh:15` | The single-line base64 key (`LS0tLS1CRUdJTi…`) passes the security rules. Ties to H14. | Add the base64-PEM and DER-PKCS8 prefixes to the rules. |
| SC-2 | reported | `make_owner_keys.py:61-74` | `--github-secret` skips the "not inside the repo" check. | Run the check first. |
| SC-3 | reported | `sign_router.py:80`, `sign_firmware.py:81` | A wrong key still signs. The bad release is only found if someone runs `verify_release.py`. | Verify each new signature against the repo's public key before writing it. |
| SC-4 | reported | `check_published_apk.py:78-85` | A dev-signed APK passes the check on `main`. | Pin the production certificate SHA-256 and compare on `main`. |
| ST-4 | reported | `pisophone_setup.py:2850-2877` | The fingerprint is checked only for the owner key. A wrong card or admin key signs without error. | Check the fingerprint for every kind. |
| ST-3 | reported | `pisophone_setup.py:2902` | A second key replacement overwrites `.old`, so the original key is lost. | Use a time-stamped backup. Refuse to overwrite an existing backup. |
| ST-2 | reported (U) | `pisophone_setup.py:2322-2376` | The first flash trusts an unsigned manifest, and the cache path uses an unchecked `sha256` value. | **Owner decision** on 5.2(e). The `sha256` check is done in any case. |
| ST-6, CI-3, CI-8 | reported | pip install, `router-program.yml:46-90`, `quality.yml:184` | Toolchains and downloads are not pinned. | Pin with `--require-hashes`, a dated nightly, image digests and a ktlint sha256. |
| CI-4 | reported | `build-apk.yml:33-34` | Test jobs have `contents: write`. | Test jobs get `contents: read`. Commits move to a separate small job. |
| CI-7, CI-9, SC-7 | reported | `publish_firmware_ota.py:188`, `build-apk.yml:391-423`, `sign_console_ui.py:44` | The same version with new bytes is only a warning. A missing `apksigner` is only a warning. The console UI signature expires without any check. | Fail on `main`. Make a missing `apksigner` fatal. `verify_release.py` checks `ui.json` expiry (more than 30 days left). |

**Acceptance criteria (R2)**
- AC2.1: `bash scripts/check_security_rules.sh` fails on a planted base64-PEM line and on a DER-PKCS8 line (`scripts/tests/`).
- AC2.2: New test: `snapshot()` with `PISOPHONE_OWNER_FINGERPRINT` **unset** returns key status `found`. The `*` override stays only in tests that need it.
- AC2.3: Signing with a key that does not match the repo's public key exits non-zero (tests for `sign_router.py` and `sign_firmware.py`).
- AC2.4: In every signing job, the files that are signed or run come from `$GITHUB_SHA`. A workflow check (a grep test in `scripts/tests/`) fails if a signing step follows a `reset --hard`.
- AC2.5: `python3 scripts/verify_release.py` reports 0 FAIL and now includes the `ui.json` expiry.
- AC2.6: No key, token or password is added anywhere. `docs/KEYS.md` and `docs/RELEASE.md` are updated in the same pull request if an owner step changes. They are not moved.

### R3: Reliability and robustness (medium priority)

| id | where | fix | test |
|---|---|---|---|
| RT-10 | `router/piso_isolation.sh:32-35,54` | Load the new table and delete the old one in **one** `nft -f` transaction. | `test_setup.py`: when the fake `nft` fails on load, the old table is still present. |
| RT-18 | `config.rs:203`, `piso_isolation.sh:80,107` | When `CONSOLE_BIND` is `0.0.0.0` or empty, block the console port for any address. | Render the ruleset with `0.0.0.0`. |
| RT-09 | `console.rs:742-748` | Reuse `http::Limits` and a 96 KB stack per console connection. | 200 idle sockets; `/ping` still answers. |
| RT-17 | `core.rs:1487-1489` | `recover()` runs on its own thread, so a dead box does not delay fair use or `check_clock`. | Fake box that never answers; time one tick. |
| RT-08, RT-26 | `util.rs:46-57`, `console.rs:560` | A strict, top-level-only flat JSON reader with proper string unescaping. | Login with `"` in the password; `{"a":"x\"b\":1","b":2}` returns `2`. |
| RT-22 | `setup/src/96-space-backup.sh:91` | Reject any `..` part in a path. Use `mktemp`. | `test_setup_tools.py`: a tar entry `etc/coinslot.d/../passwd` is refused. |
| FW-04 | `WebServerAuth.cpp:133-234` | Skip a target for 30 s after 2 failures. Do not sleep while other requests are waiting. | Hardware check: two phones, one switched off. |
| FW-07 | `WebSocketsUdp.cpp:277-292` | Read the handshake without blocking, across loop passes. | Hardware check: `nc <box> 81` held open during a coin drop. |
| FW-08, FW-10 | `HardwareManager.cpp:103-109`, `Config.cpp:162-170` | Reset under `pulseMux`. Read the master clock inside a critical section. | Host test of the clock as a pure function. |
| FW-11, FW-12, FW-13 | `Config.cpp:187`, `Config.h:113`, `WebSocketsUdp.cpp:466` | Refuse to arm when there is no boot counter. Check the params length and fail loudly. Use a 512-byte ACK document. | `tx_id_test.cpp`; maximum-length params; worst-case ACK frame. |
| FW-14 | `WebServerAuth.cpp:78-208` | A pure `parseAckBody()` in place of the `indexOf` parsing. | New `host_tests/ack_parse_test.cpp`. |
| APP-21 | `KioskUpdateManager.kt:140-149` | Set the state with compare-and-set; use a `SupervisorJob`. | Two taps start one download. |
| ST-5 | `pisophone_setup.py:4985,4767,4799` | Default `--local-only`. Per-IP lockout. **Owner decision**: a one-time PIN per run. | 6 bad logins from IP B do not lock out IP A. |
| ST-7 | `pisophone_setup.py:1912,2757` | Do not save passwords inside a OneDrive or Documents-synced folder by default. Warn the owner. | Path-choice unit test on Windows. |
| CI-5, CI-6 | `quality.yml:19-37,66-102` | Diff against the merge-base on `beta`. Add the missing website paths. | A push touching only `website/start/shop.html` starts the workflow. |
| WEB-1, WEB-2 | `website/_headers` | Remove `unsafe-inline` from `script-src` (move inline scripts out). Add CORS for `/update/phone/*` and `/update/wifi/*`. | `test_site_page.py` asserts both. |

**Acceptance criteria (R3)**: every row has the test named (or a hardware check added to `REAL_WORLD_TESTING.md`). Firmware and router rows go into the same releases as R1, not separate ones.

### R4: Dead code and sloppy code (low risk, any time)

There is no behaviour change in this package. Each item is deleted or merged, and the existing tests must still pass.

- **App**: APP-11 (one `getLocalIpAddress` in `util/`), APP-12 (one lock-and-reset, one arena activation, one config-apply path; resolve the PIN difference: **the coordinator's compare-then-write rule wins**), APP-16, APP-17, APP-18 (drop the secondary constructor, `resetSessionBlocking` and `getSessionState`), APP-19 (delete `CoinEventRepository`), APP-20 (no empty `catch`: log at debug at least), APP-22, APP-24, APP-25, and the known list in `CLAUDE.md` section 2 (APP-26).
- **Firmware**: FW-22 (`sendCloudSnapshot` and its route), FW-23 (the revenue debounce path, `flushRevenueToFlash`), FW-24 (parallel arrays become one struct; remove the unreachable branch), FW-21 (dashboard checks `res.ok`), FW-09 (fix the guard comment), FW-25 (timings in `Timing.h`).
- **Router**: RT-19 (one `ndsctl` parser, one tz function, one origin check, one HTTP GET), RT-20 (stop generating unused roll fields; keep the columns), RT-23, RT-24 (pass a hash, not `GW_KEY`, on the command line).
- **Setup and scripts**: ST-8 (one signing module shared by `pisophone_setup.py` and `scripts/make_cards.py` / `make_slot_licenses.py`; **the owner decides which limits win**: 600 or 5,000 cards, slot cap per ledger or per run), ST-11 and SC-8 (`encoding="utf-8"`, `with open`), SC-6 (`raise` instead of `assert`; a ledger lock file), SC-9, CI-10, CI-11, and the stale comment in `scripts/ci_emulator_tests.sh`.
- **Repo**: DEAD-1. Delete the untracked root `package.json` and `package-lock.json`, or add them to `.gitignore`. **Owner decision**: the constitution mentions `node_modules`.

**Acceptance criteria (R4)**
- AC4.1: All the checks for each touched area pass (`CONTRIBUTING.md` table). The detekt and lint baselines **shrink** and never grow.
- AC4.2: Searching for each deleted symbol finds no matches.
- AC4.3: There is no change to `app/schemas/*`, NVS keys, the roll or ledger formats, or the protocol vectors (`protocol/gen_vectors.py` output unchanged).

### R5: Overengineering and structure (medium risk, needs owner approval per item)

| id | proposal | precondition |
|---|---|---|
| FW-16 | Remove the `sessionTxList` fast path once every phone polls the pending list. Remove unsigned mode when H9 closes. | H9 decision; a phone app release that is live everywhere. |
| FW-15 | Drop the queue mutex (all callers are on the loop task) **or** document the single-task contract. | None. Documenting is the default. |
| RT-15 | Split `Core::run_window` (188 lines) into arm, collect, drain and settle, with pure decision functions under `#[test]`. Split `console::handle` (206 lines) by route. | R1 router tests in place first. |
| APP (large files) | Split `KioskEngine` (806 lines) into engine, account flow and health monitor. Split `Esp32ConnectionManager` (814 lines) into heartbeat, arm and transport. | R1 app tests in place first. |
| FW-26 | Split `setupWebServer` (328), `processWebSocketServer` (256), `handleSave` (194) and `authWorkerTask` (176). | FW-14 and FW-02 done first. |
| ST-9, ST-10 | Turn `pisophone_setup.py` into a package (images and `PAGE` HTML become data files; `Session` is split). `build_piso_setup.py` still emits the single file the website serves. CLI subcommands; secrets read from stdin or env, not from flags. | `build_piso_setup.py --check` and all setup tests still pass; the published single file works offline. |

**Acceptance criteria (R5)**: these are pure moves. The tests from R1 to R4 pass unchanged, with no edits to their assertions. No function over 120 lines remains in the money paths (`run_window`, `processCoinSlotSession`, `PaymentRepository`, `KioskEngine` payment code).

### R6: Efficiency (low risk)

| id | change | measurable target |
|---|---|---|
| RT-12, RT-13 | Read the roll once per tick. Heal only the MACs found in it. Cache the view per version, so a bump does not re-read the files for every page. | 20 open pages and 20 unpaid clients: `ndsctl` runs per tick ≤ number of changes; file reads per bump ≤ 2. |
| RT-16 | Verify the ledger incrementally, from a stored offset and hash. | 100k rows: a verify after the first takes under 50 ms on x86 test hardware. |
| FW-17, FW-18 | Beacon every 15 s. Answer a probe by unicast. Build `/api/status` in a reused buffer. Dashboard polls every 5 s. | Smallest free heap block after 24 h does not decrease (hardware soak). |
| FW-19 | Load `jsqr.js` only when the admin QR scan opens. | Dashboard first load is smaller by about 57 KB (gzip). |
| FW-20 | Write the period ledger at idle time; revenue is still written at once. | NVS writes per coin: 6 → 5 or fewer. |
| APP-13, APP-14, APP-15 | Guard the Wi-Fi check with an `AtomicBoolean` and rely on the callback (a 5-minute safety timer). Poll every 2–3 s while the WebSocket is open. `accountTick` runs only when someone is signed in. Cache the settings reads. | No overlapping Wi-Fi join; poll count is halved while the WebSocket is open. |
| CI (inefficiency list) | Cache the Playwright apt dependencies; one Gradle build shared by unit tests, lint and assemble; a `CHROME` env set once; firmware envs in a matrix; dependabot for pip and cargo. | Code quality wall time on a full run drops by 25% or more. |
| Gradle | Drop `kotlinx-coroutines-core`, `material-icons-core` and `compose.ui.graphics` (they come in transitively). Move `nanohttpd` into the version catalog. **Owner decision**: replace the deprecated `security-crypto`. | `assembleDebug` passes; dependency count goes down. |
| Repo size | **Owner decision**: stop committing one of the two identical copies, `setup/piso-setup*.sh` and `website/setup/*` (7.14 MB each, about 25 MB of near-duplicate payload in total). | `build_piso_setup.py --check` and `publish-router.yml` still pass. Routers still install from the same URL. |

## 7. Verification gates (all packages)

1. The checks in `CLAUDE.md` section 3 and the `CONTRIBUTING.md` table for every touched area, plus `bash scripts/check_security_rules.sh`.
2. Constitution rule 7 for Python changes: `ruff check`, `mypy` and `pytest -x` on the touched files. A failure is written to the ledger.
3. Generated files are regenerated, never edited: `embed_web.py --check` and `build_piso_setup.py --check` pass.
4. "Code quality" is green on the exact `beta` commit before anything is considered for `main`. There is no merge or release without the owner asking (`CLAUDE.md` section 5).
5. Hardware checks that were added but not run are listed in each pull request as **not tested**.

## 8. Owner decisions needed before the marked items

1. **ST-2 / 5.2(e)**: sign the first-flash manifest with the owner key? This adds a signing step to `firmware-images.yml`.
2. **ST-5**: should the setup page default to local-only and use a one-time PIN per run?
3. **ST-8**: which card and slot limits are right: 600 or 5,000 cards per batch; slot cap per ledger or per run?
4. **R6 repo size**: drop one of the two identical installer copies?
5. **Gradle**: replace `security-crypto`, which is deprecated upstream?
6. **DEAD-1**: are the root `package.json` and `package-lock.json` needed by your Obsidian or tooling setup?
7. **H9** (already open): are any boxes still in legacy mode? This blocks part of FW-16.

## 9. Definition of done

- Every finding record has status `fixed`, `verified-on-hardware`, `wont-fix` (with a reason) or `owner-decision`.
- `CHANGELOG.md` says, in owner terms, what changed in each app, firmware and router release that this spec produced.
- `CLAUDE.md` section 2 is updated after each release. The R4 cleanup items are removed from its "known and open" list.
- `HARDENING_PLAN.md` is unchanged, except to cross-link findings that touched an H item.
