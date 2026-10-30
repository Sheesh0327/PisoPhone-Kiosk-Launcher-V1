# Finished features: why they were built and how they were judged

One entry per finished feature that no longer has open hardware checks: its intent and success criteria. The full spec, plan and validation evidence of each are in git history (`git log -- specs/features/NNN-name`, last present at commit `aba69c8`). Features that still have an unticked check in `docs/REAL_WORLD_TESTING.md`, and 040 onward, stay as folders next to this file.

## 001-router-money-time

**Feature 001: router money and time**  
Status: **implemented, waiting for owner review** (approved 2026-10-21, base option (a))

**Intent.** A shop owner's Wi-Fi customers must get exactly the time they paid for, also after a power cut or a router that starts with a wrong clock. Today the router program can delete a paid session, give free time, report a false "LEDGER WAS CHANGED", lose a voucher file, or credit a late coin zero. This serves project rule 1 (money and time are never wrong).

**Success criteria.**
1. Each of RT-01 to RT-07 and RT-14 has a test that fails on `beta` as it is today and passes after the change (ids in test names).
2. `cargo test` passes; `tools/pisoportal/tests/test_flow.py` passes against a release build, including two late-coin rounds and a torn ledger tail.
3. After the change no `fs::rename` call remains outside `util.rs`.
4. `python3 tools/build_piso_setup.py --check` and `bash scripts/check_security_rules.sh` pass; `setup/RELEASE` is raised once.
5. `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` still pass.
6. Hardware check RS32 (router clock jump with a voucher) is in `docs/REAL_WORLD_TESTING.md`, marked not run.
7. `CHANGELOG.md` says in owner terms what changed.

## 002-firmware-3-11-0

**Feature 002: restore firmware 3.11.0 (products, USB Wi-Fi setup, coin-counter hardening)**  
Status: **implemented, waiting for CI and owner review** (approved 2026-10-21: one feature, setup choices in scope)

**Intent.** The old `beta` carried firmware 3.11.0 and the work that goes with it; deleting `beta` removed it from the line of work. The owner wants it back before the firmware money fixes (feature 003, which sit on top of it). What 3.11.0 gives the shop owner: - A coin box image for each product: PisoPhone only, PisoWiFi only, or the full one, built from the same source, with a signed update feed and flash image for each (`6c6e008`). - Setup program choices "PisoPhone only (no router)" and "PisoWiFi only", and USB Wi-Fi setup of a new board with a new admin password (`c82b40f`, `d8d9ce4`). - Coin-counter hardening: a period ledger so revenue totals survive, and related portal changes (`4b6312d`). - Fixes that belong to them (`851707c`, `68a7b10`, `2fae5ac`) and a CI fix for loopback rate limiting and LF web assets (`724d214`). Serves rule 1 (the coin count and revenue stay right) and the goal that a shop owner can set up without handling keys.

**Success criteria.**
1. All eight commits are on the feature branch and the tree builds: `cargo test` passes (87), `embed_web.py --check` and `build_piso_setup.py --check` pass, `check_security_rules.sh` passes.
2. Firmware host tests pass (`esp32_firmware/host_tests/run.sh`, with mbedtls) and `clang-format` is clean with the CI version.
3. `scripts/check_product_strip.py` passes (no word of the other product in a product's image).
4. CI builds all firmware environments (`firmware-images.yml`, "Firmware build") green on the branch's push.
5. Router and setup tests pass: `router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_phone_only.py`, `test_setup_phone_web.py`.
6. `PISO_FW_VERSION` is 3.11.0 and nothing else raises it; `setup/RELEASE` raised once.
7. Hardware checks for 3.11.0 (USB Wi-Fi setup on a factory board, product images, period ledger) are in `docs/REAL_WORLD_TESTING.md`, marked not run.
8. `CHANGELOG.md` says in owner terms what changed.

## 003-tablet-game-touch

**Feature 003: touch works inside games on tablets after time is granted**  
Status: **implemented on the branch, waiting for the tablet try-out** (approved 2026-10-21)

**Intent.** A customer on a tablet inserts coins, gets time, opens a game and **cannot touch it**: the home screen answers, the floating pill answers, the game does not. The customer has paid and cannot use the time. This serves project rule 1 (a paid-for minute must be usable), without weakening the lock when no time is left.

**Success criteria.**
1. On the owner's tablet, with time granted, **touch works in an installed game and in a built-in game**, checked by hand and by an `adb` capture (below). Hardware check, owner runs it.
2. `adb logcat` shows no "Untrusted touch" / occlusion message for the game while unlocked (or the capture names the real cause, and the fix addresses that cause).
3. A unit or Robolectric test fails before the fix and passes after: when the lock screen is hidden, its window parameters make it **non-obscuring and non-touchable** (window alpha 0 or the window not present), and when shown, touchable and opaque as today.
4. Locked state test: with the lock screen shown, window flags still block touches (no regression).
5. `./gradlew :app:testDebugUnitTest` and `ktlint` pass (needs the Android SDK; say so if not run).
6. Hardware checks added to `docs/REAL_WORLD_TESTING.md` and marked not run: touch in a game after time is granted; touch is blocked again when time runs out; pill unaffected; a phone is unaffected.

## 004-firmware-money

**Feature 004: firmware money rows (a coin is never lost, double counted or mis-credited)**  
Status: **implemented on the branch, waiting for CI and owner review** (approved 2026-10-21; reset pin waits up to 30 s, then resets, confirmed by the owner)

**Intent.** The coin box is where every peso is first counted. Five rows say a coin or its credit can still go wrong on firmware 3.11.0: a coin dropped while Settings is saved is lost (FW-02); a power cut between "revenue counted" and "payment queued" leaves revenue for a payment the phone can never collect (FW-03); reboot, update or reset can strike while a coin is being counted (FW-01); a big "minutes per coin" setting can wrap into a wrong credit (FW-05); a coin at an idle slot is only logged, not kept (FW-06). Serves project rule 1.  The fixes already exist, written test first, on branch `claude/sleepy-gauss-rn31gl` as `beba862` (FW-03), `5ebf443` (FW-02) and `eeae494` (FW-01, 05, 06). This feature brings them back, it does not rewrite them. A trial apply onto `beta` (b76f909) was done without the ledger and the checklist doc: every source and test file applies cleanly; the only conflicts are `ledger/negative-constraints.md` and `docs/REAL_WORLD_TESTING.md` (hand-merged: add RS28 to RS31 and keep RS32, RS33).

**Success criteria.**
1. `coin_record_order_test`, `coin_hw_apply_test`, `restart_guard_test` and `coin_credit_test` each **fail on today's `beta` code and pass with the fix**; the failing result is shown, not assumed.
2. Orphan-on-idle (FW-06) has a test that fails before and passes after (the old commit lists "3.11.1 batch"; if it has none, one is written first).
3. The whole `esp32_firmware/host_tests/run.sh` passes (needs `libmbedtls-dev`, python `cryptography`).
4. `clang-format --dry-run --Werror` clean on the touched files; `bash scripts/check_security_rules.sh` passes.
5. `pio run -e esp32-c3-dev` and `-e esp32dev-dev` build (if PlatformIO is available here; otherwise CI's firmware images workflow, and `validation.md` says which).
6. `PISO_FW_VERSION` is 3.11.1.
7. Hardware checks RS28 to RS31 are in `docs/REAL_WORLD_TESTING.md`, marked not run, next to RS32 and RS33.
8. `STATUS.md` and the backlog rows move to `fixed` (not `verified-on-hardware`).

## 008-wheel-install-option

**Feature 008: the wheel is an install option, offered only on routers with more than 16 MB of flash**  
Status: **parked** (2026-10-22: the owner will write a separate spec for this; the facts below are kept for it; no questions answered, no code)

**Intent.** "During setup, there should be options to add the spinning wheel game into the installation if the storage of the router is above 16 MB." Small routers (16 MB flash) are tight on room; the wheel adds to what the router stores (program, page, console code, and files it writes: `wheel.log`, the spin journal, daily counts). The owner wants the installer to **offer** the wheel only where the router has the room, and not to put it on small routers.

**Success criteria.**
1. Flash measurement (pure function, tested in `router/tests/test_setup.py` with `/proc/mtd` samples): 8 MB, 16 MB (exactly 16 MiB: not above), 32 MB, 128 MB, no `/proc/mtd` with a big disk, no `/proc/mtd` with a small overlay, unreadable file; each gives the right answer.
2. Installer: on a large router the question is asked, `yes` writes `WHEEL_ALLOWED=yes`, `no` writes `no`; a non-interactive run (the setup program's answers, `--yes`) uses the stated answer or the default; on a small router the question is not asked and `no` is written; the summary shows the measured flash and the decision.
3. Router program (`cargo test`, `test_wheel.py`): `WHEEL_ALLOWED=no` makes the wheel off whatever else is set (switch on, confirmation on record), the console JSON says "not available on this router", the button and `/wheel*` are absent/404, the mini-game stays; `yes` or a missing key behaves as today (review question 4 for updated routers).
4. Computer setup program: the question is shown for a large router and not for a small one (web and text paths), the answer reaches the installer (`test_pisophone_setup.py`, `test_setup_web.py`).
5. Update path: an update on a small router sets `WHEEL_ALLOWED=no` (review question 4); on a large router keeps the owner's earlier answer; never changes the wheel's switch or confirmation record.
6. `piso-setup wheel` (status / allow / deny) works and is in `piso-setup help` and the manual; `shellcheck` clean; footprint test green; existing router tests green; `build_piso_setup.py --check`; `setup/RELEASE` raised once; CHANGELOG, manual, hardware check added (not run).

## 009-thin-jackpot

**Feature 009: a rarer jackpot, drawn as a thin slice to scale (x5 becomes the jackpot)**  
Status: **approved** (owner, 2026-10-22, with the proposed default numbers; to be built after router 1.5.15 is live)

**Intent.** "Make the x5 multiplier smaller so it serves as the jackpot." The owner chose (2026-10-22): **a rarer jackpot, drawn thin to scale**: x5 becomes about 1 in 39, and its slice on the wheel is half as wide as the others, so the picture shows the real chance. The shop keeps about the same average return.

**Success criteria.**
1. `cargo test`: parse and `to_text` round trip with `t`; chances and return computed over sizes (the proposed default gives 15/39 win, 1/39 jackpot, 89.7%); a seeded draw over a large run lands within a tight range of those chances (the thin row about half as often as a normal slot); the return and cap rules refuse what they refused before; a saved old-format table gives exactly the chances it gave.
2. The existing wheel tests stay green (`test_wheel.py`, `test_wheel_visitors.py`, `test_wheel_browser.py`); tables without `t` produce the same wheel and odds as before.
3. Layout (node): slice angles sum to 360; each slice's angle is proportional to its size; the proposed default gives 19 slices of 18.46 degrees and one of 9.23; wins spread between losses and the thin jackpot is not next to the x3; a thin slice is half the width of a normal one in every table.
4. Browser: the default wheel has 20 slices, exactly one half-width and labelled x5; the odds table says "1 of 39 (2.6%)" for x5; for a fixed table the stop is inside the middle 80% of a slice of the result's row (including a thin one); a win and a loss still turn the same way.
5. Console: saving `0x12 2x6 3x1 5x1t` works and shows its chances; a table with a mark on a row above the cap or above 100% return is refused with the reason.
6. Router setup tests, `cargo fmt/clippy`, footprint test, `check_security_rules.sh`, `build_piso_setup.py --check`; `setup/RELEASE` raised once; CHANGELOG, manual, RS34 wording (hardware check not run).

## 011-offline-setup

**Feature 011: setting up a router in a real shop: no internet needed, no coin box mix-ups**  
Status: **approved by the owner (part A 2026-10-22, part B 2026-10-23; merged into one feature 2026-10-23). Built and validated on a computer 2026-10-23; every criterion passes except 14 (hardware: RS36, RS37), which is not run. See `validation.md`. Not `done` until the owner has had the hardware checks run and merged it.**

**Intent.** **Part A.** Today the router must have internet during its first setup. A shop owner who sets up the router with the setup program on a computer (before the ISP has connected the modem, or with a flaky line) is stopped. With this feature the computer, which has internet, downloads the installer and the OpenWrt packages the router needs, copies them to the router's RAM, the router checks them, and the setup runs without the router going online. If anything cannot be prepared, the setup says so and uses today's path.  **Part B.** The owner sets up router 2 and coin box 2 while router 1 already runs nearby. Box 2 sometimes joins router 1 instead of router 2 and the pairing fails or is unreliable. The cause is Wi-Fi, not the modem or the internet: during pairing a new box only knows the name built into its firmware, and an older router 1 still broadcasts that same name. This part gives every router's coin box network a name of its own (also on routers set up before per-router names existed), and makes the pairing say what to do when another router is in the way. Nothing here may cost a customer money or time.

**Success criteria.**
1. **Baseline:** the checks for the area this feature touches (setup and router, `CLAUDE.md` section 3) pass on the branch before any change; counts recorded in `validation.md`. That is `router/tests/test_setup.py`, `test_install.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, `test_setup_phone_web.py`, `test_phone_only.py`, plus `bash scripts/check_security_rules.sh` and `python3 tools/build_piso_setup.py --check`. The Rust side (`cargo test`, `tools/pisoportal/tests/test_flow.py`) is **not** in this baseline: this feature touches no Rust (owner, 2026-10-23). Already measured on `beta` `b1db87f` (2026-10-23, architect session): `test_setup` 379, `test_install` 39, `test_pisophone_setup` 189, `test_phone_only` 39 — 0 failures each, matching 010's recorded counts; security rules passed; `--check` up to date. The four browser-backed files were not run there (no Playwright installed), so run them in the execution session or record them as skipped with the reason.

### Part B: the coin box network's own name (built first)
2. **The command** [`router/tests/test_setup.py`, stub `box_curl`, `uci`, `wifi`]. On a router whose box network is `PisoCoinBox` (an old router with stored shared names, and a newer router with `BOX_WIFI_ROTATED` 0): it says the box will be off its network for up to four minutes and coins are kept, asks (a terminal) or accepts `--yes`; sends the box `wifi_ssid=PisoCoinBox-<id>` and a new `wifi_pass` in one request; then sets `wireless.box_ap.ssid` and `.key`, `BOX_SSID_NAME`, `BOX_WIFI_ROTATED 1`; keeps the MAC lock; waits for the box (`box_up`). On a router that already has its own name: "already has its own name", nothing changed. Without a paired box (`BOX_MAC` empty) or with the box not answering before it starts: it stops with the reason and changes nothing. The name it sends is checked before sending (fact B4: the box checks nothing).
3. **Failure before the switch changes nothing** [`test_setup.py`]. When the box refuses the request (HTTP not 200/302/303): `wireless.box_ap.*`, `BOX_SSID_NAME`, `BOX_WIFI_ROTATED` and `BOX_WIFI_PASS_NEW` are exactly as before (this also fixes fact B7 for `piso-setup rotate-box-wifi`). When the box does not come back after the switch: the router keeps the new name and key, and the message tells the owner to power-cycle the box and run `piso-setup status`.
4. **Telling the owner** [`test_setup.py`, `test_pisophone_setup.py`]. With the box network called `PisoCoinBox`: `piso-setup status` and the summary show the status line of "Names"; the setup program's Check-up shows it with the command. With its own name: no such line. **How the Check-up learns the name (added 2026-10-23, group 4; the spec was silent and the Check-up only probes ports):** `run_checkup` takes an optional `box_net` function; `Core.checkup` passes one that runs the read-only `uci -q get wireless.box_ap.ssid` on the router over the SSH login `Core.ssh` already holds for the session (the one `Core.login` set; it never asks for a password). When the router does not answer on port 22, or the login fails, or the name is its own, the Check-up has no such line and is otherwise unchanged. The line is a new item named "Coin box network" with a new state `warn` (shown as "Notice", not "Problem": the shop works, the name only risks a mix-up) [`test_pisophone_setup.py`].
5. **Pairing tips** [`test_setup.py`]. The pairing tips (60 s and 150 s) and the "did not join" failure message include the pairing tip of "Names". The pairing itself is unchanged (it must keep using `PisoCoinBox`, the name new boxes know).
6. **Phones untouched** [`test_setup.py`]. The command changes no `wireless.kiosk_*` setting, no `KIOSK_SSID_NAME` and no `KIOSK_PASS`.

### Part A: the first setup without the router's internet
7. **Reading the router** [`test_offline_setup.py`]. `read_router_feeds` reads `/etc/openwrt_release`, `/etc/opkg/distfeeds.conf`, `opkg list-installed`, `/etc/opkg.conf` (`check_signature`), the presence of `usign` and `/etc/opkg/keys/*`, and `MemAvailable`, in one SSH command. An apk router (no `/etc/opkg/distfeeds.conf`, `apk` present), no `check_signature`, no `usign` or no keys each give "offline setup not possible: <reason>".
8. **Resolving packages** [`test_offline_setup.py`, fixture indexes]. `resolve_packages` returns the missing packages of fact A1 and their dependencies in install order: alternatives (`a | b`) take the one already installed, else the first; `Provides` satisfy a dependency (for example `libcurl`'s SSL library); already-installed packages are skipped; `libmicrohttpd-no-ssl` only when no `libmicrohttpd*` is installed; a dependency on a `kernel` version different from the router's, or a package not in any source, gives "offline setup not possible: <package> <reason>". Fixtures: a 23.05-like ramips/mt7621 set and a 24.10-like set, each with curl's SSL alternatives.
9. **Building the bundle** [`test_offline_setup.py`, local HTTP server]. `build_offline_bundle` downloads from the router's own source URLs (`http` and `https`) each needed `.ipk`, checks its size and sha256 against `Packages` on the computer too, saves each used source's `Packages` (unpacked from `Packages.gz` when only that is served) and `Packages.sig`, and the installer for the router's CPU (the same file choice as `install.sh`) with its `.sha256`, and `install.sh`. A download that fails or does not match: "offline setup not possible: <file> <reason>".
10. **Pushing the bundle** [`test_offline_setup.py`, stub SSH]. `push_offline_bundle` writes each file into `/tmp/piso-offline/` with one SSH command per file (`cat > <file>.part && mv <file>.part <file>`, the file's bytes on standard input; no `scp`, no `tar`), checks all sha256 on the router with `sha256sum` in one more command, and is skipped when the same bundle is already there (the second run at 10.0.0.1 after the address move). If `MemAvailable` is under 24000 KB plus the bundle's size: the fallback (decision A2).

## 014-router-soak-harness

**Feature 014: Router soak harness: measure first**  
Status: **draft (waiting for the owner's approval)**

**Intent.** Spec 02 asks for a router that runs 24 hours a day for a year. Today nobody knows how the portal's memory, threads, open files and data files behave over thousands of coin windows, kills and power cuts. This feature builds one repeatable test, `test_soak.py`, that runs the real `pisoportal` against the fake coin box and fake `ndsctl`, and writes a report. It changes nothing in the router. Its numbers decide the size of feature 015. Serves rule 1 (money and time are never wrong) over long running time.

**Success criteria.**
1. `python3 tools/pisoportal/tests/test_soak.py` (default) runs the real portal for 240 coin windows with the fake box and
   `ndsctl`, among 40 devices, in under 15 minutes, and prints a report; `--quick` does 36 windows in under 2 minutes.
2. The run includes `--kills` (default 50) `kill -9` of the portal, some in the middle of a window, and `--cuts` (default 20)
   simulated power cuts (kill -9, then a half-written last row added to the files that are appended to, the way a cut write
   looks; every file the program writes is `fsync`ed, so nothing else is lost). The portal is started again each time.
3. Every coin put in is credited exactly once, **or stranded by a kill and reported as such** (see "Spec edit 2"): the sum of `pesos` in `revenue.csv` equals the coins put in, no coin twice, the
   ledger verifies (`pisoportal verify`), and the paid time of each device equals what its coins bought. A mismatch is reported
   with the window, the kill that preceded it and the coin counts, and the run exits 1.
4. The report lists, every 25 windows (about 2 simulated hours at 300 a day): the portal's RSS, threads and open files; the size
   of `revenue.csv`, `wheel.log`, the roll (`vouchers.txt`), the voucher files and every other file under `DATA_DIR`, at the start and
   at the end; coins put in against credited; kills and power cuts done; growth per window and per simulated day for each file.
5. Resource pass rule: in the longest stretch with no restart (the last half of the run has no kills), RSS, threads and open files
   at the end are not more than 10% above the middle of that stretch (threads and files: or +2). File growth is **reported, not
   judged** (the expected red result for AO-02 to AO-04).

## 016-router-hardening

**Feature 016: Router hardening rows from spec 01**  
Status: **approved** (owner, spec 02 section 8)

**Intent.** Five small router defects found in spec 01 and already fixed and tested on another branch (never on `beta`): the firewall tables are replaced in one step (no moment with no isolation rules), the owner console's ports are closed to the guest Wi-Fi when the router address cannot be read, the router's settings file is read by a strict JSON reader, strings in it are unescaped (a console password with a quote is no longer cut), and a backup import refuses any path with `..`. Serves rule 2 and the safety of a shop that runs every day.

**Success criteria.**
1. `router/tests/test_setup.py` RT-10, RT-18 and RT-22 checks pass; `cargo test` rt08 and rt26 tests pass.
2. All router gates of `CLAUDE.md` section 3 pass; sizes reported.
3. `build_piso_setup.py --check` passes; `check_security_rules.sh` passes.

## 018-app-money-rows

**Feature 018: Phone app money rows**  
Status: **approved** (owner, spec 02 section 8, 2026-10-24)

**Intent.** The phone must credit every coin exactly once and lock when time is up, for months without a restart. Eight money and time defects in the phone app were fixed and tested on another branch but never compiled. This feature brings that work back by cherry-picking (not rewriting) and adds one measurement, AO-08: the number of rows in `payment_receipts` is written to the log once an hour, so the owner can see how fast it grows (decision D4: measure, no pruning).

**Success criteria.**
1. The cherry-picked unit tests (`PaymentRepositoryUnitTest`, `KioskHttpServerUnitTest`, `SessionRulesTest`) pass in CI.
2. New `HealthSnapshotTest` passes: the log line names the receipt count and is produced at most once an hour.
3. `app/schemas/*` unchanged (`git diff beta -- app/schemas` empty).
4. CI "Code quality" (ktlint, detekt, unit tests) green on the head commit.
5. `bash scripts/check_security_rules.sh` passes.

## 020-ci-signing

**Feature 020: Sign from the approved commit (CI-1, CI-2)**  
Status: **approved** (owner, spec 02 section 8, 2026-10-24)

**Intent.** After the owner approves a release in the protected `production-signing` Environment, the signing loop moves its working tree to the newest commit on `main` (`git reset --hard origin/main`) and then runs the signing scripts from there. A commit pushed after the approval could therefore change the script that signs. The signing scripts must run from a copy of the approved commit (`$GITHUB_SHA`), and the router approval must cover every file that is signed or that decides what is signed. Serves rule 2: only software the owner signed is installed.

**Success criteria.**
1. `python3 scripts/tests/test_workflow_signing.py` passes on the changed workflows and **fails on the old ones** (red first).
2. The test is part of `scripts/check_security_rules.sh` (so it runs in the always-on "Security rules" job).
3. YAML of both workflows parses; the arguments passed to the signing scripts exist in their argument parsers.
4. CI "Code quality" green.

## 025-fresh-page-first-view

**Feature 025: a fresh page never sees the last result first**  
Status: **done on the branch (CI result pending)**

**Intent.** A customer's page on the PisoWiFi portal opens a WebSocket and then says `hello`, with `reset: true` when it wants a fresh start. The portal must not show that page the previous window's result (the `final` screen: pulses and minutes of a window that is over) before the reset is applied. Today, now and then, it does. Serves rule 1 as far as the screen goes: the customer is never shown a result that is not theirs to see now.

**Success criteria.**
1. **A deterministic test fails first.** With a result waiting for the address, a page connects (handshake done), the portal state changes
   (another customer's page connects) and *then* the page sends `hello` with `reset`: today the page's first message is the old `final`.
   The test requires that the page receives **nothing before its `hello`** and that its first message after it is `idle`. (red result logged)
2. The fix: the view-sending thread sends nothing until the `hello` has been handled (the pings still go out), and nothing is missed after it.
3. `test_flow.py` passes 202/202 **ten times in a row**; `test_console.py`, `cargo test`, clippy, rustfmt and the router setup tests pass.
4. `setup/RELEASE` is raised to 1.5.21, generated files rebuilt (`tools/build_piso_setup.py --check`), `CHANGELOG.md` entry, `validation.md`.

## 026-usb-setup-boot-loop

**Feature 026: a new board must not crash in a loop (PisoPhone-only USB setup)**  
Status: **approved (owner, 2026-10-24: "badly needed fix ... apply to main and release immediately")**

**Intent.** The PisoPhone-only installation on a Windows computer stops after the software is written: "No PisoPhone software answered on the board". The owner's serial log shows why: a board with no Wi-Fi saved restarts in a loop (boot #93, #94, reset reason PANIC) with `assert failed: tcpip_send_msg_wait_sem ... (Invalid mbox)`, so it can never answer the setup program. The same happens with 3.12.0 and 3.12.1.

**Success criteria.**
1. With no Wi-Fi saved, the board starts the Wi-Fi stack (station mode, no connection attempt) before the servers open their ports: the line is in
   `startWifiConnect()`. (Checked by reading, clang-format, the host tests and the four firmware builds in CI; **the crash itself can only be seen on a board**.)
2. **Hardware check RS50** (a new or erased ESP32-C3, write the PisoPhone-only image, the board answers `PISO-HELLO` and the setup program finishes) is added and
   stays unticked until the owner runs it; the owner is the first test.
3. `PISO_FW_VERSION` is 3.12.2; CHANGELOG entry; ledger row.

## 033-remove-wheel

**Feature 033: remove the spinning wheel**  
Status: **approved by the owner 2026-10-28 (epic D1 = remove). Starts only after feature 032 is merged (epic D13).**

**Intent.** The spinning wheel is **off on every router**, has **never run on real hardware** (RS34 in `docs/REAL_WORLD_TESTING.md` was never run), and its responsibility-warning text has **never had a legal review** (roadmap 8.3). In the Philippines a paid spin for a prize sits close to gambling regulation. It costs 5 Rust files plus `wheel.html` (~99 KB of source), 57 references in other files, and 5 test files. Removing it deletes the largest block of unproven complexity in the product and makes the legal question moot. It can be restored from git history if a market for it ever appears.

**Success criteria.**
Each becomes a line in `validation.md` with real command output.
1. `rg -n "wheel|WHEEL" tools/pisoportal/src/ router/ setup/src/` returns **only** the two places the other criteria require (corrected 2026-10-28, the spec contradicted itself): the
   `WHEEL=` guard test in `config.rs` (criterion 7) and the one-time notice that reads `wheel-confirm.txt` and writes `alerts.txt`
   (criterion 13). Every other match is a leftover and fails. Documentation and `CHANGELOG.md` may still mention it historically.
2. `cd tools/pisoportal && cargo test` passes, with no wheel tests remaining and no test merely `#[ignore]`d.
3. `cd tools/pisoportal && python3 tests/test_flow.py` passes.
4. `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` pass.
5. `python3 tools/pisoportal/tests/test_soak.py` runs to completion with no wheel step.
6. `router/tests/test_footprint.py` passes with updated expectations, and **the program is smaller than before**: record the
   before and after byte sizes of `pisoportal-mipsel` in `validation.md`.
7. A test still asserts that no generated setup script contains `WHEEL=`.
8. The customer page has no Spin button and no odds text in any state: idle, paid, paused, final. Prove it by the browser test
   that remains, or by a new assertion in `test_flow.py`.
9. The console has no wheel panel and `GET /api/wheel` returns 404. Proven in `tests/test_console.py`.

## 034-remove-minigame

**Feature 034: remove the mini-game**  
Status: **approved by the owner 2026-10-28 (epic D2 = remove). Starts only after 033 is merged.**

**Intent.** The free mini-game (`/game`) was meant to be replaced by the spinning wheel, and feature 005's own notes record removing it as agreed by the owner and never done. With the wheel gone (feature 033) it is the last of the two: `game.rs` + `game.html` (~18 KB of source), 50 references, and two browser test files, for a feature nobody has asked for. Removing it finishes the clean-up the wheel work started and leaves the customer page doing one thing: selling time.

**Success criteria.**
1. `rg -n -i "game|GAME_" tools/pisoportal/src/ router/ setup/src/` returns nothing but unrelated words (check each hit by hand)
   **and the one place that must keep the key: the test of criterion 11** (a `GAME_MINUTES=10` line in a fixture `settings.txt`).
   Documentation and `CHANGELOG.md` may still mention it historically.
2. `cd tools/pisoportal && cargo test` passes; no game tests remain and none is `#[ignore]`d.
3. `cd tools/pisoportal && python3 tests/test_flow.py` passes.
4. `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py` pass.
5. `tests/test_console.py` passes with no `GAME_MINUTES` setting. `GET /game` returns the ordinary customer page (a captive portal
   answers every unknown address that way, so it cannot be 404); `GET /game/tick` and `POST /game/tick` are checked against the real
   behaviour and written down in `validation.md`.
6. The customer page renders correctly in all six states with no game button and no gap. Prove it with the browser test or a
   new assertion, and **say which states you actually checked**.
7. `router/tests/test_footprint.py` passes; record the `pisoportal-mipsel` size before and after.
8. `bash scripts/check_security_rules.sh` passes.
9. `python3 tools/build_piso_setup.py --check` passes; `setup/RELEASE` raised.

## 035-supported-routers

**Validation: say which routers are supported (feature 035, with two jobs handed in alongside)**  


**Intent.** 

**Success criteria.**

