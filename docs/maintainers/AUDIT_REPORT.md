# TechNet PisoHub: pre-production audit

Branch `beta`. Written in plain English: what could go wrong, and why it matters. Nothing here is a diff review.

How this was done: three readers went through the ESP32 firmware, the Android app, and the router/website/release code. Every finding below was then
re-read by two independent checkers, one trying to reproduce it and one trying to knock it down. Where a checker could not be run (a usage limit
stopped some of them) the finding says **not independently re-checked**. Nothing was run on real hardware, so every "scenario" below is a trace
through the code, not an observed failure.

**Status column:** *Fixed* = changed on `beta` and covered by a test or CI build; *Open* = still to do (the plan is in section 6); *By design* = the
behaviour is intended and now documented.

---

## 1. Executive summary

**Verdict: nearly ready for a supervised pilot, not yet for 24/7 unattended shops.** The money path is well built: a coin is written to flash
before it is sent, the phone credits each transaction id once, and the box erases a coin only after the phone signs for it. I found no way for a
customer (someone without the kiosk Wi-Fi password) to get free time. What remains is mostly about **someone who is already on the private kiosk
network** (a shop insider, a plugged-in device, anyone with the hidden Wi-Fi password) and about **protecting the keys that sign updates**.

The checkers lowered most of the first-draft severities: only a person already on the kiosk network can use most of these, so none is "High" in
the sense of "a customer can steal today". Two things deserved fixing first and have been: the phone believed anything that answered as the box
(it now demands the box's signature on every coin), and the update-signing keys were reachable from any branch's build (they now sit behind a
protected approval step).

### Top 10 risks (after verification)

| # | Risk | Who can use it | Severity | Status |
|---|---|---|---|---|
| 1 | The phone credited time on the word of anything that answered as the box (WebSocket, status poll) | Someone on the kiosk LAN | Medium | **Fixed** (A-F1) |
| 2 | Update-signing keys (APK keystore, owner key) usable by any branch's build | Anyone who can push to the repo, or steals a token | Medium | **Fixed** (R-F1/R-F8); needs your one-time GitHub steps |
| 3 | The router believed unsigned box answers ("coins inserted") | Someone on the kiosk LAN | Medium | **Fixed** (R-F3) |
| 4 | Heartbeat signatures cover only `device id:time`, so an `ack_tx` (which erases a queued coin) or the phone's IP can be replayed, and a replay works after a box reboot | Someone who can sniff the kiosk LAN | Medium (not re-checked) | **Open** (E-H2) |
| 5 | Nothing limited a box to 6 or 12 phones | The shop owner | Business rule | **Fixed** (E-H3) |
| 6 | A phone that lost its Keystore key silently fell back to the public default key | Rare; a bug, not an attack | Medium | **Fixed** (A-F2) |
| 7 | A coin arriving while a phone is locked down was credited, acknowledged, then wiped | Timing (slot deactivated while a coin is queued) | Medium | **Fixed** (A-F6) |
| 8 | USB setup paired a hardware-fingerprint id, not the id the phone really uses | Every USB setup | Medium (setup bug) | **Fixed** (A-F4) |
| 9 | One global coin slot: scripted empty windows from rotating MAC addresses can keep real customers out | A guest on the portal Wi-Fi | Medium / Low | **Open** (R-F4) |
| 10 | The auto-update paths have no staged rollout or health check on the phone side; the manual `install.sh ... update` skipped the signature (now hands over to the signed self-update) | An operator mistake, or a hijacked website | Low | **Open** (A-F9); R-F5 fixed |

Smaller items fixed in the same pass: the box no longer hands its admin password to phones while it still runs on the old public key (E-H1), a
web page on another site can no longer make the owner's browser change the box (CSRF, E-M6), coin counters reach flash within 20 s (E-M3), the
box's phone list can no longer be filled by made-up phones (E-M5), the "Set up a phone" link keeps the box secret out of the website's logs
(E-M1), the PIN is no longer logged (A-F10).

---

## 2. Baseline (Step 0)

| Suite | Result |
|---|---|
| Firmware host tests (`esp32_firmware/host_tests/run.sh`) | passed, 27 programs (now 29 with the new box-type and origin tests) |
| Router program `cargo test` | 26 passed (now 29) |
| Router flow test `test_flow.py` | 103 checks, 0 failures (now 107) |
| Router console test `test_console.py` | 22 / 0 |
| Router browser test `test_browser.py` | 22 / 0 |
| Router setup tests `router/tests/test_setup.py` | 229 / 0 (one BusyBox-only test skipped) |
| Android unit tests | not runnable in the sandbox (Gradle cannot fetch its plugins offline); the CI run `Build and Deploy APK` "Unit tests" passed (41 test files, 240 `@Test`, no instrumented tests) |
| Firmware compile | not possible offline; CI compiles all four environments on every push and passed |

Gaps: no test drives the real phone against the real box, nothing covers the Compose lock-screen transitions, and the hardware checklist
(`docs/REAL_WORLD_TESTING.md`) has unticked items S1 to S3, H2 and R4.

---

## 3. System map and contracts

```
coin acceptor -> ESP32 box ---(Wi-Fi "PisoKiosk": AES+HMAC, port 8080)--> phone kiosk app
                    |  ^                                                   |
                    |  +--- heartbeat, coin push (WebSocket :81), status ---+
                    +---(10.0.0.x LAN, signed HTTP + signed UDP events)---> router: pisoportal -> openNDS (guest Wi-Fi)
```

### 3.1 What each part takes in, does, and gives back

| Part | Input | Processing | Output |
|---|---|---|---|
| **Box** coin input (`HardwareManager`, `CoinSlotManager`) | pulses on one pin | groups pulses into a coin after 280 ms of silence, only while the slot is armed | a payment event |
| **Box** payment queue (`PaymentQueueManager`) | payment event | writes it to flash first (20 records), retries every 10 s, erases on a signed acknowledgement; after 24 h (30 min when nearly full) a coin moves to the "orphan" list | queued coin, retries |
| **Box** delivery (`DeviceNetwork`, `WebSocketsUdp`) | queued coin | pushes over WebSocket and `/add_time` (AES-CBC + HMAC) | phone credit |
| **Box** heartbeat (`WebServerTelemetry`) | phone's `device_id`, `ts`, `sig`, `ack_tx`, state | checks the signature and clock window; erases an acknowledged coin | box's view of the phone, `admin_pin` (own key only) |
| **Box** gateway API (`WebServerGateway`) | router's signed requests (one-time nonce) | arm/status/release/ack the coin slot | JSON answer, now signed (`rsig`), plus signed UDP coin events |
| **Box** admin (`WebServerAuth`, `WebServerConfig`) | password, session cookie or admin QR | dashboard, settings, pairing, OTA (signed manifest) | pages and JSON |
| **Phone** (`Esp32ConnectionManager`, `KioskHttpServer`) | box commands, coin push, status poll | verifies, credits once per tx id into Room (`payment_receipts`), acknowledges | remaining time, lock/unlock |
| **Router** `pisoportal` | customer page (WebSocket), box answers and events | one coin window at a time; settles roll, ledger, then `ndsctl` grant, then box ack | internet access for a guest MAC |
| **Router** updater | hourly `router.json` (signed, staged rollout) | verify, install, health check, roll back | new router software; also hands the box its signed firmware |
| **CI** | pushes | build, test, sign (now in a protected environment), publish | APK, firmware, router files, update feeds |

### 3.2 Sources of truth

| Item | Owner | Notes |
|---|---|---|
| Remaining time | Phone (`elapsedRealtime` deadline in Room) | the box only copies it from the heartbeat. Time does not run while the phone is powered off (deliberate; now documented) |
| Coin credit | Box queue until the phone's signed acknowledgement; phone dedupes by tx id | |
| Device count | Box (NVS slots) and, since this pass, the signed box type | 6 purchased / 12 rental |
| Clock | Phones (the box has no RTC and no NTP) | first signed heartbeat sets it; it only moves forward, at most 5 minutes per heartbeat |
| Router revenue | Router roll + hash-chained ledger | reconciled against the box's `lifetime_pulses` |

### 3.3 Protocol vectors

`protocol/fixtures` and `protocol_contract_test.cpp` cover AES, HMAC and the account message on both sides. **Not covered by shared vectors:** the
coin-slot request string, the WebSocket `COIN_DETECTED`, the heartbeat reply. This pass adds shared vectors for the gateway answer signature
(`gateway_auth_test.cpp` and `boxlink.rs` use the same two values) and a signed-coin round trip in the app tests.

### 3.4 Where documents and code disagree

| Document says | Code does | Action |
|---|---|---|
| "The owner key is offline; a stolen GitHub account can publish nothing" (RELEASE.md, owner_key.py) | The key was a repository secret | Fixed: protected environment, text corrected |
| `sign_router.py`: "once a night" | The router checks every hour | Fixed |
| Coin window "15 s" | WebSocket arm lasts 20 s (refreshed by coins), HTTP arm 45 s, session cap 120 s | Open: ARCHITECTURE.md |
| "Device limits 6 / 12" | There were 10 slots, no limit | Fixed |
| Each box has its own generated admin password (comments in `Config.cpp`, `CredGen.h`) | Every box starts on the published default `Coinslot@Setup` until changed (coins are blocked until then) | Open: comments |
| State 4 in ARCHITECTURE.md | does not exist in the app | Open |
| `/identify` reports version "3.0" | firmware is 3.5.4 | Open |
| Gateway doc: "unacknowledged coins are discarded after 24 h" | they move to the orphan list | Open |
| REAL_WORLD_TESTING F3 "no free time after reboot" | time is paused while the phone is off; no free time is created | By design, documented |

---

## 4. Findings by layer (sorted by severity)

### 4.1 ESP32 box and protocol

| ID | Finding | Severity | Checked | Status |
|---|---|---|---|---|
| E-H2 | The heartbeat signs only `device_id:ts`. `ack_tx` (erases a queued coin), the phone's `ip` and `state` are not covered. After a box reboot its memory of seen timestamps is empty, so an old captured heartbeat is accepted again. The controller WebSocket has one global replay timestamp and accepts unsigned acknowledgements. *Scenario:* someone who can sniff the kiosk LAN replays a captured heartbeat after a power cut with `ack_tx` of a coin still queued; the coin is erased before the phone credited it. | Medium (needs LAN sniffing) | not re-checked | **Open** |
| E-H3 | No 6 / 12 phone limit. | business rule | confirmed in code | **Fixed** |
| E-H1 | On a box upgraded with Wi-Fi saved (legacy mode, public key) the heartbeat reply carried the admin password encrypted with the public key. | would be High | the checkers found it already fixed at HEAD | **Fixed** (`2b4cd32`) |
| E-M2 | A customer feeding many ₱1 coins while acknowledgements lag fills the 20-record queue; overflow coins go to the orphan list while still being pushed (a refund would pay twice). One offline phone holding 18 unacknowledged coins blocks arming for up to 30 minutes. | Medium | not re-checked | **Open** |
| E-M4 | After a reboot the first signing phone sets the box clock with no sanity check. A phone whose clock is a day ahead makes every restored queued coin look old: they all move to the orphan list. | Medium (suspected) | not re-checked | **Open** |
| E-M9 | `/config` (with the admin password), `slot_lockdown` and the arena commands go to a phone by IP address only, with no device id. After DHCP hands the address to another phone, that phone obeys. All phones of a box share one secret. | Medium | not re-checked | **Open** |
| E-M6 | No cross-site protection: a page on another website could post to the box while the owner's browser had the password cached. | Medium (suspected) | | **Fixed** (`OriginCheck.h`; links changed to confirmations or POST) |
| E-M1 | The "Set up a phone" link put the box secret and Wi-Fi password in the query string (visible in browser history and in the website host's logs). | Medium | | **Fixed** (now after the `#`) |
| E-M3 | Revenue counters reached flash only 5 s after the last coin, so continuous coins kept deferring it. | Medium | | **Fixed** (at most 20 s) |
| E-M5 | The 12-entry table of phones the box had heard from only grew; unpaired hellos could fill it and push a paired phone out. | Medium | | **Fixed** |
| E-M7 | Production builds still had the unauthenticated serial command `add <minutes>`; the published OTA images are the dev builds; no secure boot or flash encryption on them. `partitions_encrypted.csv` marks the NVS and SPIFFS partitions encrypted, which ESP-IDF may reject at runtime (the production environments compile, which is all CI proves). | Medium | not re-checked | serial command **Fixed**; the rest **Open** (question Q4) |
| E-M8 | Classic ESP32 builds read the coin acceptor on GPIO3, the serial receive pin. | Medium (hardware, suspected) | | **Open** (question Q5) |
| E-L1..L5 | Pulses outside an armed window are dropped without a record; debounce does not measure pulse width; unauthenticated slow handshake blocks the loop up to 2 s; a 4-slot login throttle that 5 IPs defeat; changing the secret strands queued coins; unvalidated pin numbers can boot-loop the box. | Low | | **Open** |

### 4.2 Android app

| ID | Finding | Severity | Checked | Status |
|---|---|---|---|---|
| A-F1 | WebSocket `COIN_DETECTED` fields and the status-poll coin list were credited without checking the box's `v_sig`. *Scenario:* someone who takes over the box's address (10.0.0.10) on the kiosk LAN pushes "coin inserted" with any seconds. | Medium (High on the first read) | confirmed twice | **Fixed** |
| A-F2 | Provisioned phone with an unreadable Keystore value fell back to the public default key. A phone that was never provisioned still uses it (legacy mode). | Medium | confirmed | provisioned case **Fixed**; legacy mode **Open** (refuse `/config admin_pin` and `/add_time` on the default key once a box runs its own key) |
| A-F4 | USB setup paired `HW-xxxx` (a hardware fingerprint) while the phone signs with its own random id, so a "paired" slot never matched the real phone. | Medium | confirmed twice | **Fixed** |
| A-F6 | `isEligible` was hard-coded `true`: a coin delivered during lockdown was credited, acknowledged and then wiped. | Medium | confirmed | **Fixed** |
| A-F3 | The 30-minute setup window plus the exported receivers allow a secret push by someone with the phone in hand. | Low | partly | **Open** |
| A-F5 | Launcher fails open: the overlay is the only lock, and HOME works before it is up after a restart. | Low | confirmed | **Open** |
| A-F7 | Replay protection is in memory only; retries are re-encrypted with a fresh IV, so up to three copies of a command pass (receipt dedup stops double credit). | Low | partly | **Open** |
| A-F9 | App auto-update has no staged rollout, health check or rollback on the phone; idle test ignores arming and card scans. | Medium (not re-checked) | | **Open** |
| A-F10 | The admin PIN was written to the log. | Low | confirmed twice | **Fixed** (a second place, the box-heartbeat sync, was found by a later review and fixed too; a CI rule now fails any log line that carries the PIN or a secret) |
| A-F11 | DEPROVISION needs no PIN while USB debugging is on. | Low | partly | **By design** (the website's "Remove from a phone" needs it) |
| A-F8 | "Power-off pauses paid time." | none | refuted by one checker, partly by the other | **By design**: time is billed only while the phone runs; wiping it on a long power cut was rejected on purpose. Documented |
| A-F12/13 | Lock-task allowlist includes gms and launchers; maintenance window uses the wall clock; `processed_tx_ids` is dead code. | Low | | **Open** |

### 4.3 Router, website, setup, release

| ID | Finding | Severity | Checked | Status |
|---|---|---|---|---|
| R-F1 | APK keystore and owner key were plain repository secrets; the APK build ran every branch's Gradle code with the keystore in its environment. | Medium (Critical on the first read; needs push access) | partly, twice | **Fixed**: build and signing are separate jobs, secrets live in the `production-signing` environment (main only, your approval) |
| R-F3 | The box's answers to the router were unsigned. | Medium | partly, twice | **Fixed** (`rsig`) |
| R-F4 | One global coin window plus a per-MAC cooldown: a script rotating MAC addresses holds the slot most of the time. `Origin: null` is accepted on the WebSocket. | Medium / Low | confirmed + partly | **Open** |
| R-F8 | Dev APKs were signed with the production keystore and shared its version counter; `app.json` is unsigned and its `url` accepted any HTTPS address. | Low | partly, twice | **Fixed** (public dev key, channel check, host allowlist) |
| R-F5 | `install.sh ... update` and `./piso-setup.sh update` skip the signature and downgrade check (a person must run them by hand). | Low | confirmed + partly | **Fixed**: on an installed router `install.sh ... update` runs the signed `piso-setup self-update`; `--branch` still installs an unsigned test file but says so. A copy of the setup file run by hand is the owner's own act and stays. |
| R-F6 | No retry limit for a failed router update; rollback restores five files; the signed text does not name the CPU family; "customers online" ignores guests who are mid-payment. | Low | partly, twice | **Open** |
| R-F7 | `lock-admin` covers ports 22/80/443 but not the console (8081) or LuCI; the console sends the root password over plain HTTP; the lockout is per IP. | Low | partly, twice | **Partly fixed**: `lock-admin` now also covers 8080, 8443 and the console port; console over HTTP and per-IP lockout remain. |
| R-F9 | A crash in the narrow gap between granting and noting a window could credit a recovered window twice. | Low | confirmed + partly | **Open** |
| R-F10..12 | GW_KEY visible in a command line and a cron minute; config temp file briefly 0644; Telegram pairing takes the first sender (a group chat would let every member command the router). | Low | not re-checked | **Open** |

---

## 5. What this means for PisoWiFi (shared code)

- The box firmware is shared: the signed answers (`rsig`), the cross-site check and the box-type limit all apply to PisoWiFi boxes. The router
  program accepts an older box that does not sign yet and starts demanding signatures after the first signed answer, so **update order is
  router (1.3.3) then box (3.5.4)**; either order keeps working.
- The 6/12 cap counts phones only. It does not limit PisoWiFi guests.
- The portal fixes already shipped in router 1.3.3 (online without a refresh, top-up while online) are unaffected.
- Not shared: the app's signed-coin check; PisoWiFi coins reach the router through the gateway API, which is why the router needed its own check.
- Open items touching both products: E-H2 (heartbeat replay is a phone-side issue only), R-F4 (PisoWiFi only), R-F7 (router only).

---

## 6. Plans (three passes)

Branch path for each pass: do it on `beta`, let CI go green, then (when you say so) merge to `pisophone-beta` and `main`. **The branch
`pisophone-beta` does not exist yet** (question Q1).

### Pass 1: ESP32 and protocol
**Done:** E-H1, E-H3, E-M1, E-M3, E-M5, E-M6, E-M7 (serial), signed gateway answers (R-F3 box side), signed coin status list.
**Order of the rest:**
1. **E-H2 heartbeat v2.** The box sends a per-boot random number `bn` in every heartbeat reply; the phone signs `hb2:id:ts:bn:ack_tx:ip` and the box
   refuses a v1 heartbeat from a phone that has used v2. Depends on app Pass 2 step 2. Interface change: new heartbeat fields `bn`, `sig2`.
2. **E-M2** check "queue nearly full" on every coin, not only on arming; refuse the arm early with a clear message.
3. **E-M4** ignore a phone clock more than a day from the box's own uptime-derived estimate until two phones agree.
4. **E-M9** add the device id to every command pushed to a phone; the phone ignores a command for another id. Interface change: `device_id` in `/config`,
   `/trigger_action`.
5. E-L1..L5 as time permits; E-M7 partition question and E-M8 pin question need your answers.
- **Test plan:** host tests for each rule (`replay_check`, a new `heartbeat_v2` test, `payment_queue`); shared vectors in `protocol/`; hardware pass: pull power
  during a coin session, replay a captured heartbeat after reboot, pair a 7th phone on a purchased box.
- **Exit criteria:** replay after reboot rejected for v2 phones; no coin lost in 50 power-cut trials; CI green on all four environments.

### Pass 2: Android app
**Done:** A-F1, A-F2 (provisioned case), A-F4, A-F6, A-F10, updater host and channel checks.
1. **A-F2 legacy case:** while a phone uses the default key, refuse `/config` with `admin_pin` and show the "move to own key" banner.
2. **Heartbeat v2** (pairs with Pass 1 step 1) and persistent replay memory (A-F7).
3. **A-F9** staged auto-update on the phone: install only on `app.json` with the phone's turn (bucket by device id), health-check the first 5 minutes,
   report failure to the box.
4. **A-F5** fail-closed launcher until the overlay is confirmed up.
5. **Kids redesign** of the lock screens per `docs/design/GUI_SPEC_PISOPHONE_KIDS.md`.
- **Test plan:** Robolectric unit tests next to each change (done for A-F1/2/6); a Compose screenshot test for each lock screen.
- **Exit criteria:** CI "Unit tests" green; a phone with the Keystore key deleted refuses the box and says why; a coin during lockdown is refused.

### Pass 3: router, website, setup, feeds, release
**Done:** protected signing (R-F1), dev key and channel check (R-F8), documentation of the key trade-offs.
1. **You, once, in GitHub:** the eight steps in `docs/RELEASE.md` section 2a (create the `production-signing` environment, add the five secrets, delete the repo-level copies).
2. **R-F5** make `install.sh ... update` hand over to the signed `self-update` when the router is already installed.
3. **R-F6** retry limit, full backup before an update, CPU family in the signed text, "guest is mid-payment" counts as busy.
4. **R-F7** `lock-admin` covers 8081/8080/8443; console login over HTTPS or localhost-only; lockout counted per account as well as per IP.
5. **R-F4** a global budget of empty windows across all MACs; refuse `Origin: null`.
6. **R-F9** journal of credited window ids; **R-F10..12** small fixes.
7. **Features:** pause/resume, voucher codes, sales dashboard (design is fixed in `docs/design/GUI_SPEC_OWNER_AND_WIFI.md`).
8. **Redesign** of the PisoWiFi portal, box dashboard, router console and setup website.
- **Test plan:** `router/tests`, `tools/pisoportal/tests` (flow, browser, console), a staged-rollout dry run, one real router update with a forced failure to see the rollback.
- **Exit criteria:** an update that fails its health check is undone and does not retry more than three times; a forged `app.json` or `router.json` is refused; voucher codes can be used once only.

---

## 7. Questions (ask rather than guess)

**Owner's answers:** Q1 no (no `pisophone-beta` branch). Q2 no (one admin password stays). Q3 no (no per-phone keys for now). Q4 yes (encrypted production
boxes will ship: the encrypted layout needs a real-board test first). Q5 answered "yes", but it asks for a pin number: **still open**. Q6 yes (paid time stays
paused while a phone is off). Q7 asked for an explanation: **still open**.

1. **Q1 Branch:** shall I create `pisophone-beta` from `beta` now, and keep the product-specific file removals that README describes?
2. **Q2 Phone PIN:** the phone's admin PIN is the box admin password, so a compromised phone gives the box's admin password. Do you want a separate
   "phone PIN" generated by the box (recommended), or keep one password?
3. **Q3 Per-phone keys:** every phone of a box shares one secret, so a phone that is opened up can imitate the others. Industry practice is a key per
   phone derived from the box secret and the phone's id. It is a large protocol change; do you want it before production or after the pilot?
4. **Q4 Production boxes:** do you ship the encrypted (secure boot + flash encryption) builds to customers, or the dev images? The encrypted partition
   layout needs a test on a real board before anyone depends on it.
5. **Q5 Coin pin:** which GPIO do your classic ESP32 boxes really use for the acceptor (GPIO3 is also the USB serial receive pin)?
6. **Q6 Time while off:** are you happy that a customer's paid time is paused while the phone is powered off (current behaviour)?
7. **Q7 Rental box license:** should the signed box-type token also be able to expire, so a rental box that is not paid for drops back to 6?
