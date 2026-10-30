# Spec 04: the setup in a real shop (backlog epic)

Status: **draft: owner answers D1 to D8 in (2026-10-28); waiting for the owner to approve this epic; no feature started**   Written: 2026-10-28 against `ccr-ecc45ac1-xsa1o8` at `2591448` (= `main`, router 1.5.23)   Related: `setup/README.md`, `specs/features/ARCHIVE.md` (011-offline-setup), `docs/REAL_WORLD_TESTING.md` (RS17, RS20, RS21, RS36), spec 01 rows ST-2, ST-5, ST-7

## 0. Renumbered on 2026-10-29 (read this if an older note says 032, 033, 034 or spec 03)
This epic and its three features were written on 2026-10-28 as **spec 03** and **features 032, 033 and 034**. The same day, a different spec 03 (`specs/spec_03_overhaul.md`) and different features 032 (ledger size cap), 033 (remove the wheel) and 034 (remove the mini-game) were written and released, so the numbers collided. On the owner's order (2026-10-29) this work keeps its content and gets new numbers. Nothing about what it does changed.

| was | is now | where | what it is |
|---|---|---|---|
| spec 03 | **spec 04** | `specs/spec_04_setup_real_run.md`, `specs/spec_04_findings.json` | this epic and its backlog (SU-01 to SU-21) |
| feature 032 | **045** | `specs/features/045-setup-program-real-run/` (first pushed as branch `feat/032-setup-program-real-run`) | the setup program in a real shop |
| feature 033 | **046** | `specs/features/046-router-setup-real-run/` (first pushed as `feat/033-router-setup-real-run`) | the router installer in a real shop |
| feature 034 | **047** | `specs/features/047-box-wifi-retry/` (first pushed as `feat/034-box-wifi-retry`) | a box that never joined takes new Wi-Fi details (firmware 3.12.5) |
| RS56, RS57, RS58 | **RS60, RS61, RS62** | `docs/REAL_WORLD_TESTING.md` | the hardware checks of 045, 046 and 047 |
| router release 1.5.24 | **1.5.30** | `setup/RELEASE` | 1.5.24 went to the ledger cap; `main` was at 1.5.29 |

Merged with `main` on 2026-10-29: feature 041 (already on `main`) removed the setup without the router's internet. So **SU-02** (the early download and the "unplug the router's cable" prompt) is `wont-fix`, and its code and tests were left out of the merge. **SU-06a** (an apk router is refused) was kept and now asks the router for its package program (`check_router_packager`) instead of reading its package sources. SU-04 (a router at any address) was kept.

Old branch names are kept as they were; commits and ledger entries from before this date may still use the old numbers.

## 1. Goal
An owner who is not a programmer sets up a unit (router, coin box, phones) on the first try, in a real shop, with the
computer, modem and router they actually have. Today the one-click setup passes 1,099 checks on a computer (section 4), but
**it has never run end to end on real hardware**: RS17 (one click from a bare unit), RS20 (USB board problems), RS21 (two routers)
and RS36 (first setup with the WAN cable unplugged) are all unticked. This epic is a desk review of what a real run will hit,
ranked, with the fixes the owner chose.

It serves both project rules. **Money and time:** a coin box paired to the wrong router credits the wrong shop (SU-11).
**Only signed software:** nothing here weakens a signature check. The failures are mostly about getting the owner to a working
unit without a developer on the phone.

## 2. Non-goals
- **Full apk support (OpenWrt 25.x)**: this epic only refuses such routers clearly (SU-06). Supporting apk is its own later
  feature (owner answer D1).
- **The setup computer making its own Wi-Fi hotspot**: rejected (D2); the README advises the owner's phone hotspot instead.
- **Spec 01 rows already open**: the unsigned first-flash manifest (ST-2), PIN and local-only (ST-5), passwords in a synced folder
  (ST-7). They are not repeated here.
- **Not reviewed in this pass** (a later spec if a real run shows trouble): the PisoPhone-only box address on a shop router that
  has no DHCP reservation, the Android Go *Finish over USB* path, the Telegram step, the Owner tools.
- No change to the box <-> phone message contract (`protocol/`), the payment queue, credit, expiry or `revenue.csv`.

## 3. Invariants touched
- **Money and time:** pairing (SU-11) decides which box a router trusts with its gateway key. The fix narrows it to the board
  this program just wrote; it never pairs a box that would not have been paired before. The firmware change (SU-16) changes only
  the first-time setup state of a box that has **never joined a Wi-Fi and never recorded a payment**; a box that has done either
  answers `already-set-up` as today. The payment queue, credit and expiry are not touched; feature 047 must show this with a
  host test.
- **Signed software:** feature 046 changes what routers install, so `setup/RELEASE` goes up and the router release is signed and
  published by CI as always (0%, then the owner raises it). Feature 047 needs a higher `PISO_FW_VERSION` and is signed by
  `firmware-images.yml`. The first install (`install.sh` checking `piso-setup.sh` against its sha256) is unchanged.

## 4. Context manifest
Hand-written source only (`CLAUDE.md` section 4: generated files are rebuilt with `python3 tools/build_piso_setup.py`, never read).
Baseline on 2026-10-28: `test_setup` 458, `test_pisophone_setup` 232, `test_setup_web` 115, `test_offline_setup` 98,
`test_setup_tools` 78, `test_install` 48, `test_phone_only` 39, `test_setup_phone_web` 31 checks, **0 failures**. The page tests
use a fake `Core`: no real ssh, network move or USB board is exercised.

| area | purpose | file : lines | est_tokens |
|---|---|---|---|
| setup | constants (addresses, waits) | `setup/pisophone_setup.py:78-107` | 500 |
| setup | find the router, ssh, saved password, remote commands | `setup/pisophone_setup.py:1723-1916` | 2,800 |
| setup | offline bundle, push, install(), move wait | `setup/pisophone_setup.py:1918-2460` | 7,000 |
| setup | phone setup link (box admin page) | `setup/pisophone_setup.py:2544-2600` | 800 |
| setup | USB boards, esptool, firmware download, preflight | `setup/pisophone_setup.py:2707-2960` | 3,500 |
| setup | serial box link, PisoPhone-only provisioning | `setup/pisophone_setup.py:4001-4250` | 3,300 |
| setup | `Core`, `Session` (search, retry, install, phones) | `setup/pisophone_setup.py:4419-5010` | 7,500 |
| setup | service mode, `run_web` | `setup/pisophone_setup.py:5499-5560` | 900 |
| router | one-line installer, the address move | `setup/install.sh:1-188` | 2,600 |
| router | preflight, WAN clash, packages | `setup/src/20-preflight.sh:1-79`, `21-local-packages.sh:1-55` | 2,200 |
| router | access restart | `setup/src/30-router-settings.sh:151-155` | 100 |
| router | pairing, box provisioning, Wi-Fi rotation | `setup/src/50-coin-box.sh:1-252` | 4,000 |
| router | stage 2 order (password, checks, results) | `setup/src/75-checks-stage2.sh:40-96` | 900 |
| router | setup flow, answers | `setup/src/98-setup-flow.sh:1-207` | 3,000 |
| firmware | serial first-time setup | `esp32_firmware/src/SerialCli.cpp:1-60`, `SerialProvision.h` | 900 |
| website | phone QR payload | `website/js/provisioning.js:70-110` | 500 |
| docs | owner's setup guide | `setup/README.md:1-115` | 2,000 |

## 5. Schemas
- **Answers line (program -> router setup), feature 046:** `ANSWER_ORDER` (`setup/pisophone_setup.py:96`) gains one variable at
  the end, `BOX_EXPECT_MAC` (upper-case `AA:BB:CC:DD:EE:FF`, or empty). The router reads it like the others (`setup_command`). An
  older router setup file ignores it; an older program sends none and the router accepts any box, as today. Nothing else in
  the line changes.
- **Serial hello (box -> program), feature 047:** `PISO-BOX fw=.. mac=.. setup=.. wifi=.. ip=..` keeps every field. `setup=` gains
  the value `joining` (Wi-Fi details saved, never joined yet) and a new field `why=` (`wrong-password`, `no-network`, `none`) is
  added at the end. `parse_status` already reads any `key=value`; an older program sees `setup=joining` as "not needed", as it
  sees `done` today. `PISO-SETUP` and `PISO-SETUP-ERR` stay byte for byte the same.
- **Setup log file, feature 045:** `pisophone-setup-log-<YYYYMMDD-HHMM>.txt` in the saved folder, private (0600), plain text; the
  passwords of the unit are replaced by `***`.
- Everything else (summary format, router settings file, `revenue.csv`, feeds, signatures) is unchanged.

## 6. Work packages
Confidence: **confirmed** = read in the code and the path traced; **reported** = follows from how the systems behave, not run;
**uncertain** = may not happen, a test or a real run decides. Rows are in `specs/spec_04_findings.json`.

| id | confidence | where (file:line) | problem | fix | test | release |
|---|---|---|---|---|---|---|
| SU-18 | confirmed | `pisophone_setup.py:4561-4571` | The install log lives only in memory (last `LOG_KEEP` lines); a failed real run leaves no evidence to send the developer. | Write every line, plus the computer's addresses and routes at the start, to the setup log file (section 5), passwords masked; the last page and the error banner link it. | unit: a run with a fake core writes the file, 0600, no password in it | site |
| SU-09 | confirmed | `pisophone_setup.py:4891-4938`, `4426` | *Try again* on the Install step after the router password was set logs in with an empty password (the session's `Ssh` was set to `""`); every retry fails the same way. Only *Back* to the Router step reloads the saved password. | Before the install, re-probe the router; when it has a password, use the saved one (`use_saved_password`), else send the owner to the Router step to type it. | page test with a core whose router changes to `password` after the first run | site |
| SU-10 | confirmed | `pisophone_setup.py:2436-2438` | A refused ssh login is reported as "the connection to the router was lost ... Try again", which sends the owner round in a loop. | Tell a login refusal (exit 255 with "Permission denied") apart and say which password is needed. | unit on `install` with a refusing fake ssh | site |
| SU-01 | reported | `pisophone_setup.py:1731-1736`, `README.md` "Which network?" | PLDT and Converge modems are at 192.168.1.1, like a factory router. The README tells the owner to keep the computer's Wi-Fi on the shop network for the phone, so the computer has two 192.168.1.x networks and may reach the modem: "No router found" or "not an OpenWrt router". | Detect two interfaces in the same range (or the router address reachable through the Wi-Fi) and stop with exact advice. README: put the computer's Wi-Fi on the **owner's phone hotspot** (a different range). (D2) | unit on the detection with fake address tables (Windows, Linux, macOS); RS60 | site |
| SU-02 | reported | `pisophone_setup.py:2814-2870`, `2335-2368` | With the router cable in, the computer usually sends its internet through the router (a cable has a better route metric than Wi-Fi), and the factory router has none (WAN unplugged, or WAN 192.168.1.x clashes with its own LAN). The coin box software and the offline bundle then fail to download: the setup without the router's internet (feature 011) breaks exactly where it is meant to help. | At Start, before a router is used, download and keep the coin box software (manifest and image for every chip), the installer files and `app.json`. Before the router packages are fetched, check the website answers; if not, the page asks to unplug the router cable for the download and plug it back in, then continues. (D5) | unit: the page asks for the unplug when the opener fails and continues after; RS36 | site |
| SU-04 | confirmed | `pisophone_setup.py:82`, `1731-1736` | Only 10.0.0.1 and 192.168.1.1 are tried. OpenWrt-based vendor firmware uses other factory addresses (GL.iNet 192.168.8.1, Cudy 192.168.10.1): the page searches for ever. | Also try the gateway the cable's DHCP lease names; probe it with the same OpenWrt check and say which address it was found at. The move to 10.0.0.1 is unchanged. | unit on `find_router` with a fake lease table | site |
| SU-06a | confirmed | `pisophone_setup.py:1934`, `1971-1974`, `2349-2358` | An apk router (OpenWrt 25.x) is offered the online way after the offline way refuses; the router then fails at `opkg update` with "no internet or DNS?". | Refuse an apk router (or one with no `opkg`) before anything is changed, naming its release and the supported ones (`docs/ROUTERS.md`). (D1) | unit: `@@APK yes` stops before any change | site |
| SU-15 | reported | `pisophone_setup.py:4804-4818`, `provisioning.js:79-94` | A rental phone downloads the app from the website over PisoKiosk, so it needs the router's internet. After a setup without the router's internet the Phones step still shows the QR code; the phone then stops at Android's generic "Can't set up device". | Before the Phones step shows the code, ask the router to reach the website (the app's address in `app.json`); if it cannot, say "plug the modem into the WAN port" and re-check. | page test with a router that has no internet | site |
| SU-17 | confirmed | `pisophone_setup.py:2814-2870` | The coin box software list and image are fetched without the program's `User-Agent` (used everywhere else because Python's own is sometimes refused). | Send `USER_AGENT` on both. | unit: the request carries it | site |
| SU-21 | confirmed | `pisophone_setup.py:5503-5534` | `--install-service` starts the file from where it was downloaded (often `~/Downloads`); moved or deleted, the setup page no longer starts at boot. | Copy the program into `/var/lib/pisophone-setup/` and start that copy. | unit on `install_service` | site |
| SU-14 | uncertain | `pisophone_setup.py:1770-1782` | On Windows 10 the built-in OpenSSH is 8.1, older than `SSH_ASKPASS_REQUIRE` (8.4): logins that need a password (a re-run, the coin test, Telegram) may hang or fail. | Decide after RS60 on a Windows 10 computer: if it fails, say so on the first page with the fix (Windows update or the OpenSSH package). | RS60 (b) | site |
| SU-03 | confirmed | `setup/install.sh:84-111`, `pisophone_setup.py:2305-2332` | After the move to 10.0.0.1 only Windows asks for a new address by itself (`ipconfig /renew`). Linux, macOS and the headless Armbian box keep their 192.168.1.x address until someone replugs the cable; after 4 minutes the setup stops. | After `network restart`, the router switches its LAN ports off for 3 seconds and on again (DSA ports with `ip link`, swconfig with the switch's own command), so every computer asks again. (D3) | router test with a fake `ip`/`swconfig`; RS61 (a) on the 360T6M with a Linux laptop and the Armbian box | router |
| SU-11 | reported | `setup/src/50-coin-box.sh:39-89` | Pairing takes the first station that joins `PisoCoinBox` with the public default password. With two freshly written boxes switched on at the bench (*Set up the next unit*), a router can pair the other unit's box and trust it with its gateway key. | The program reads the MAC the board itself reports (`PISO-HELLO` after writing) and sends it as `BOX_EXPECT_MAC` (section 5); `pair_box` admits only that MAC and says so when another station joins. With *Do not write it* ticked: any box, as today. (D4) | router test: a wrong MAC joins first and is not paired; program test: the MAC is sent; RS61 (b) | router |
| SU-06b | confirmed | `setup/install.sh:113-160`, `20-preflight.sh:67` | The router side has the same apk gap for a setup by hand (`wget ... install.sh \| sh`). | `install.sh` stops with a clear message when there is no `opkg`, before it downloads or moves anything. | `test_install.py`: no `opkg` -> message, nothing changed | router |
| SU-05 | confirmed | `setup/install.sh:84-90`, `20-preflight.sh:36-44` | The modem-range checks compare only the first three numbers of the address with 10.0.0 or 10.0.30, ignore the netmask (a 10.x.x.x/8 lease clashes too) and run only when the WAN is up at that moment: after a setup without the router's internet a modem plugged in later can clash in silence. | Compare ranges with the netmask; `piso-setup status` and the monitor check again once the WAN is up and send the same advice as an alert. | router tests with WAN 10.0.0.5/24, 10.1.2.3/8, 10.0.30.7/24 and none | router |
| SU-07 | reported | `20-preflight.sh:67` | A router whose release feeds no longer answer (an old release moved to the archive, a vendor build) fails at `opkg update` with "no internet or DNS?" although it has internet. | When `opkg update` fails while the ping works, say that the release's package sources do not answer, name the release, and point to `docs/ROUTERS.md`. | router test with a failing fake `opkg update` | router |
| SU-08 | uncertain | `75-checks-stage2.sh:83`, `30-router-settings.sh:151-155` | `restart_router_access` restarts dropbear and uhttpd **before** the `DONE` state is written and the summary is sent. If the restart ends the setup's own ssh session, the setup stops half way and the computer gets no summary. | Write the state and send the results first; restart them afterwards in the background, the way the address move already does. Test first: if the session survives on the 360T6M, the row is closed `wont-fix` with the evidence. | router test where the restart sends HUP to the session; RS61 (c) | router |
| SU-16 | confirmed | `esp32_firmware/src/SerialCli.cpp:25-29`, `pisophone_setup.py:4090-4148` | PisoPhone only: a mistyped shop Wi-Fi password is saved, the box never joins, and a new run gets `already-set-up`; the only way out is writing the software again with an erase and typing ERASE. The box also does not say why it cannot join. | Until the box has joined a Wi-Fi once **and** recorded no payment, it takes new Wi-Fi details over USB (`setup=joining`) and reports `why=` (section 5); the program offers *Change the Wi-Fi details* instead of a re-write. (D6) | host test in `esp32_firmware/host_tests` (joined once or a payment -> refused); program test; RS62 | firmware |

## 7. Acceptance criteria
Feature **045** (setup program, area setup; release: the website's `pisophone_setup.py`, rebuilt by `build_piso_setup.py`):
- AC1.1 A run with a fake core writes `pisophone-setup-log-*.txt`, mode 0600, holding every page line and the address table, and
  no generated password (SU-18).
- AC1.2 After a first run that set the router password, *Try again* logs in with the saved password and continues (SU-09); a
  refused login says which password is needed (SU-10).
- AC1.3 With two fake interfaces in 192.168.1.x the page stops before any ssh and shows the hotspot advice; the README's
  "Which network?" recommends the phone hotspot (SU-01).
- AC1.4 The coin box software, installer files and `app.json` are in the cache after the Start page; with the website unreachable
  later, the page asks for the cable to be unplugged and finishes the offline bundle after it is (SU-02).
- AC1.5 A router found only at the lease's gateway (192.168.8.1 in the test) is found and named (SU-04).
- AC1.6 `@@APK yes` stops with the release named and nothing changed (SU-06a).
- AC1.7 The Phones step does not show the QR code while the router cannot reach the app's address (SU-15).
- AC1.8 The manifest and image requests carry `USER_AGENT` (SU-17); the service starts a copy under `/var/lib/pisophone-setup` (SU-21).
- AC1.9 RS60 run by the owner on the bench (below). `python3 tools/build_piso_setup.py --check` passes.

Feature **046** (router installer and its answer line, area router; release: router, `setup/RELEASE` raised):
- AC2.1 After the move a fake `ip` sees every LAN port set down and up (SU-03); RS61 (a): a Linux laptop and the Armbian box reach
  10.0.0.1 without a replug within 60 s.
- AC2.2 A station with another MAC that joins first is not paired; the expected one is; with no `BOX_EXPECT_MAC` the first one is,
  as today (SU-11).
- AC2.3 No `opkg`: `install.sh` and preflight stop with the message, nothing moved or downloaded (SU-06b).
- AC2.4 WAN 10.1.2.3/8 and 10.0.30.7/24 are refused; a WAN that comes up later in the LAN's range raises the alert (SU-05).
- AC2.5 A failing `opkg update` with a working ping names the release (SU-07).
- AC2.6 The state is `DONE ...` and the summary is sent even when the access restart cuts the session (SU-08), or SU-08 is closed
  `wont-fix` with the RS61 (c) evidence.
- AC2.7 `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal` and the router tests in `CLAUDE.md` section 3 pass;
  `verify_release.py` 0 FAIL after the release.

Feature **047** (coin box firmware, area firmware; release: firmware, `PISO_FW_VERSION` raised):
- AC3.1 Host test: a box that never joined and has no payment takes new Wi-Fi details; one that joined once, or has a payment
  queued or recorded, answers `already-set-up` (SU-16).
- AC3.2 The hello line carries `setup=joining` and `why=` as in section 5; an old program still provisions a new box.
- AC3.3 RS62 on a spare ESP32-C3.

New hardware checks (added to `docs/REAL_WORLD_TESTING.md` by the feature that needs them; the owner runs each **before that
feature merges**, D8):
- **RS60** (045) (a) computer Wi-Fi on the shop modem at 192.168.1.1: the page stops with the advice; on the phone hotspot it
  works. (b) the same run on a Windows 10 computer: a *Try again* after the password was set logs in (SU-09, SU-14). (c) RS36
  with the router's WAN unplugged and the computer's internet through its Wi-Fi.
- **RS61** (046) (a) the move on a Linux laptop and the Armbian box without a replug. (b) two freshly written boxes on the bench:
  each router pairs its own. (c) the end of the setup over ssh: summary received, state `DONE`.
- **RS62** (047) a PisoPhone-only box given a wrong Wi-Fi password, then the right one, without an erase.
- RS17, RS20 and RS21 are run with 046 (they need the whole path).

## 8. Owner decisions
Answered by the owner on 2026-10-28 (the questions were asked in the chat):
- **D1** OpenWrt 25.x (apk): refuse clearly now (SU-06a, SU-06b); full apk support is a later feature of its own.
- **D2** Two 192.168.1.x networks: detect and stop with advice; the README recommends the owner's phone hotspot (SU-01). No
  hotspot made by the setup computer.
- **D3** Address move: the router bounces its LAN ports (SU-03). The program does not add addresses to the computer.
- **D4** Pairing: only the board this program just wrote; any box with *Do not write it* (SU-11).
- **D5** Downloads: early at Start, plus a reachability check that asks to unplug the router cable for the download (SU-02).
- **D6** PisoPhone-only wrong Wi-Fi: the box takes new Wi-Fi details until it has joined once (SU-16), a firmware release.
- **D7** Order: 045 (program), then 046 (router), then 047 (firmware). One branch and one pull request each.
- **D8** Hardware: each feature waits for the owner's bench run of its RS checks before it merges.

Still open (asked when the feature is planned, not blocking this epic):
- **Q1** (046) Which routers does the owner want listed as supported in `docs/ROUTERS.md` besides the 360T6M (recommended: only
  models someone has set up, each with the OpenWrt release used)?
- **Q2** (047) The PisoPhone-only box's address on a shop router without a DHCP reservation: investigate in its own spec if RS52
  or RS62 shows phones losing the box (recommended: yes, after RS62).

## 9. Definition of done
Every row `fixed`, `verified-on-hardware`, `wont-fix` (with the reason) or `owner-decision`; RS60 to RS62, RS17, RS20, RS21 and
RS36 ticked or logged in the Issue log; `setup/README.md`, `CHANGELOG.md`, `specs/STATUS.md` and, after each release,
`specs/RELEASE_STATE.md` updated.
