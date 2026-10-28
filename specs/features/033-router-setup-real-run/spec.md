# Feature 033: the router installer in a real shop

Status: **approved by the owner 2026-10-28 (spec, plan and Q1 to Q3); in progress**   Branch: `feat/033-router-setup-real-run` (from `origin/main` plus the epic commit, as for 032)   Area: router (`setup/install.sh`, `setup/src/*.sh`, `router/piso_monitor.sh`, the setup program's answer line)
Backlog rows: SU-03, SU-11, SU-06b, SU-05, SU-07, SU-08 (`specs/spec_03_findings.json`; SU-08 may close `wont-fix` on RS57 (c) evidence)   Release: router (`setup/RELEASE` 1.5.23 -> 1.5.24; CI signs and publishes at 0%, the owner raises it)

## Intent
The router side of the one-click setup has never run end to end on real hardware. This feature fixes what a desk review says a real run will hit
on the router: the computer must get a new address by itself after the move to 10.0.0.1 (Linux, macOS, the Armbian box), a coin box must be
paired to the right router when two are set up side by side, a router without opkg or whose package sources moved must say so in plain words,
a modem plugged in after the setup must not clash in silence, and the end of the setup must not be cut by its own SSH restart. It serves both
rules: a box paired to the wrong router credits the wrong shop (money), and the router software installed is still only what the owner signed.

## Invariants touched
- Money and time: **pairing (SU-11) decides which box a router trusts with its gateway key.** The fix only narrows it (the board this program
  just wrote); it never pairs a box that would not have been paired before. The payment queue, the coin window record, credit and expiry are
  not touched. Checked by a router test with a wrong station joining first.
- Only owner-signed software: not weakened. `install.sh` still checks `piso-setup.sh` against its sha256, and the router release is signed and
  published by CI as always. `setup/RELEASE` goes up because the installed code changes.

## Constraints
- Raise `setup/RELEASE` (1.5.23); regenerate with `python3 tools/build_piso_setup.py` (never by hand); `--check` must pass.
- **Size:** the installer is 1639 of 1700 KB and the installed command (`piso-setup.sh.in`) is **144 of 150 KB**, so about 6 KB are left
  (`router/tests/test_footprint.py`). Every fix is written small; shell shared between rows goes in one function. Raising a limit is the owner's
  decision (Q2); the test must stay green.
- The setup must still run on BusyBox `ash` (no bashisms; `shellcheck -s sh` clean as in CI).
- Answers line: `BOX_EXPECT_MAC` is added **at the end** of `ANSWER_ORDER` (`setup/pisophone_setup.py:96`) and of `setup_command`'s reads (spec 03
  section 5); an older router setup ignores it and an older program sends none, so the router then accepts any box as today. The program-side
  change is one line plus its test, because the program is feature 032's area: it goes in 033 so that the two sides change together.
- No change to the box <-> phone message contract, the payment queue, `revenue.csv` or the portal program (`tools/pisoportal/` untouched).

## What the owner and the router do without the condition (rule 3b)
- **SU-11** no `BOX_EXPECT_MAC` (an older program, a manual `piso-setup pair`, *Do not write it* ticked): any box that joins is paired, as today.
  With it: a station with another MAC is **not paired**, the log says so once ("another coin box joined: <mac>; waiting for <expected>"), and the
  wait goes on until the expected box joins or the wait ends. There is no "pair it anyway" in the setup; `piso-setup pair` by hand still takes any box.
- **SU-03** a router whose LAN ports are not named `lan1`, `lan2` ... (an older switch of the swconfig kind): nothing is bounced and the setup prints what it prints
  today (replug the cable); Windows keeps its own `ipconfig /renew`. **Edited while building (2026-10-28):** swconfig is not bounced in this feature, because it cannot be tested here and a wrong port toggle on such a switch could cut the router's own CPU port; RS57 (a) shows whether the 360T6M is DSA.
- **SU-06b** a router with opkg sees no change. A router without opkg stops at the start of `install.sh` with a message, before any download.
- **SU-05** a WAN address that does not clash sees no change. A clash found *after* the setup (a modem plugged in later) is an alert and a line in
  `piso-setup status`; it does not stop or change anything.
- **SU-07** a failing `opkg update` on a router whose ping works names the release and `docs/ROUTERS.md`; with no internet the message is as today.
- **SU-08** if the SSH session survives the access restart (RS57 (c) decides) nothing changes and the row closes `wont-fix`.

## Success criteria
Each becomes a line in `validation.md`; ids follow spec 03 section 7.
1. AC2.1 After the move, a fake `ip` (DSA) sees every `lanN` port set down and then up inside the same background job as `network restart`, and nothing else (no WAN port, bridge, CPU interface or VLAN); a router with no `lanN` ports is left alone (SU-03).
2. AC2.2 A station with another MAC that joins first is not paired; the expected one is; with no `BOX_EXPECT_MAC` the first one is. The program sends `BOX_EXPECT_MAC` (upper case) only when it wrote the board itself (SU-11).
3. AC2.3 No `opkg`: `install.sh` and preflight stop with the message, nothing moved, downloaded or changed (SU-06b; the program side is 032's SU-06a).
4. AC2.4 WAN 10.1.2.3/8, 10.0.30.7/24 and 10.0.0.5/24 are refused by the netmask rule; a WAN that comes up later in the LAN's range raises the alert and shows in `piso-setup status` (SU-05).
5. AC2.5 A failing `opkg update` with a working ping names the release and `docs/ROUTERS.md`; with no ping the old message (SU-07).
6. AC2.6 With the access restart sending HUP to the session, the state is `DONE ...` and the summary is sent (SU-08), or SU-08 is closed `wont-fix` with the RS57 (c) evidence.
7. AC2.7 `setup/RELEASE` raised, generated files rebuilt, `--check` passes, `test_footprint.py` green. `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal` and the eight router test files pass; `check_security_rules.sh` passes; ShellCheck clean.
8. AC2.8 RS57 (a, b, c) added to `docs/REAL_WORLD_TESTING.md`, unticked; RS17, RS20, RS21, RS36 are run with it. The owner runs them before this feature merges (D8); after the merge `verify_release.py` says 0 FAIL.

## Out of scope
032 rows, SU-16 (feature 034). Full apk support. Changing which boxes `piso-setup pair` accepts by hand. The portal program, the payment path, the box firmware.

## Owner decisions
Spec 03 D1 to D8 are answered. For this feature:
Answered by the owner on 2026-10-28 (all three as recommended):
- **Q1** `docs/ROUTERS.md` lists only models someone has set up, each with the OpenWrt release used (today the 360T6M).
- **Q2** Make the code smaller first; the 150 KB limit is raised only with the owner's word (at most 10 KB).
- **Q3** SU-08 is decided by evidence: test first; fix only if the setup is cut, else `wont-fix` with the RS57 (c) result.
