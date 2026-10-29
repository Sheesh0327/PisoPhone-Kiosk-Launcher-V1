# Roadmap

High-level implementation order, in small phases. Each phase should be one change, or a few small ones, that can be finished and checked on its own. The order follows the mission: money first, then safety, then convenience.

The phases come from the open items in `docs/HARDENING.md` (section 3, and the hardware checks in section 4), `docs/REAL_WORLD_TESTING.md`, and `CLAUDE.md` (section 2).

Items marked **Owner decision** need the owner's answer before work starts.

## Phase 0: decisions

- **0.1 Owner decision:** are any boxes still in legacy mode? This decides item 9 (phones accepting the public legacy key).

## Phase 1: hardware checks on the live router

These checks are owed before the router work can be called proven. Run them on a test site or the owner's own router. The live release is router 1.5.12 (`CLAUDE.md`). `HARDENING_PLAN.md` section 4 still names 1.5.5, so use the live version.

- **1.1** A normal day: phone payment, Wi-Fi payment (one coin, several coins, a top-up), a voucher, a pause and a resume.
- **1.2** Power cut: router off for 10 minutes, on with the modem off, pay for Wi-Fi, then modem on. Paid minutes must survive the clock sync.
- **1.3** Box link lost during a window: coin in, box off for 40 s right after Done, box on. The coin is credited once.
- **1.4** Guest isolation (R4): a paying guest phone cannot reach the router's admin ports or the modem's pages; normal websites work.
- **1.5** Interrupted setup: Ctrl-C during `piso-setup pair` leaves the box network as it was and openNDS running.
- **1.6** Box settings: a bad PIN is refused with a reason; relay type and port survive a restart.
- **1.7** Relay test: switches itself off after 10 s.
- **1.8** Updates: a router takes the next release by itself; a box takes its firmware update from the dashboard.

## Phase 2: hardware checks on the phone and new firmware

Firmware is 3.10.0 (`CLAUDE.md`). Check the features below on it.

- **2.1** Phone app: paid time starts at Done, not at coin insert.
- **2.2** Phone app: the power menu appears only inside an admin maintenance window.
- **2.3** Phone app: the pill's Force close app button closes the app.
- **2.4** Firmware 3.7.0–3.9.0 features on a real box (phone profiles, the box page's light/dark button) still hold on 3.10.0.
- **2.5** Phone app without the `kioskmanager.local` fallback: a phone finds the box on a real network.

## Phase 3: money correctness (box and router)

Each item is one small fix with a test that fails before and passes after.

- **3.1** Box: store the account id with each queued payment (item 4).
- **3.2** Router: store the price list in the open-window record, so recovery prices coins at the old prices (item 5).
- **3.3** Router: keep the window id on the ledger row and check it, to stop a double credit or missing sale around a power cut (item 6).
- **3.4** Router: re-check fair use under the file lock before granting, so a pause cannot lift the 1 kbit/s limit (item 7).

## Phase 4: protocol changes (box and phone together)

Each step changes both sides in one change, with test vectors (`protocol/gen_vectors.py`, `esp32_firmware/host_tests/protocol_contract_test.cpp`).

- **4.1** Give the coin acknowledgement its own prefix, separate from the coin proof (item 1).
- **4.2** Add the `seconds` field of the status coin path to the signed message (item 2).
- **4.3** Sign the heartbeat replies (`lockdown`, empty account) and require the discovery HMAC on the HTTP probe (item 3).
- **4.4** Narrow the replay windows on signed phone calls and enforce one account per slot on the box (item 15).

## Phase 5: security

- **5.1** Reproducible router build: rebuild on `main` in the signing job and compare (item 11).
- **5.2** Pin the APK certificate fingerprint in the setup page and the setup program (item 12).
- **5.3** Move the admin QR key out of the HTTP page; sign in through the owner's own app (item 13).
- **5.4** Move `RELEASES_TOKEN` to the protected environment and add the missing key patterns to the security rules (item 14).

## Phase 6: reliability

- **6.1** Router monitor checks the portal's `/ping`, restarts it after 3 failures and raises an alert (item 8).
- **6.2** Per-device limits so one guest cannot block others (voucher guessing limit and empty windows) (item 16).
- **6.3** Router 1.5.7 narrow firewall rule for the modem's addresses is verified on a real router (Phase 1, check 1.4).

## Phase 7: setup simplification

- **7.1** Print-server pairing by a code instead of copying `send.key`. Specified in `docs/api/print-pairing.md`. Needs `/api/inbox/pair` on the print server, a separate project. **Blocked outside this repo.**

## Phase 6b: the overhaul and selling boxes (spec 03, added at the 2026-10-29 replan)

Spec 03 (`specs/spec_03_overhaul.md`) now drives the order. Done: 033, 034, 035, 039, 041 (router 1.5.26 at 0%). Open, in this order:

- **6b.1** Hardware session 1 (box money rows) first, then session 3 (router, RS57); raise router 1.5.26 only after session 3 (owner's step).
- **6b.2** 040 on the bench (GATE 1) and 043 (approach B, group 0 rewritten); **D29**, the update path of a hardened box, needs a feature before any box is sold hardened.
- **6b.3** 036 site status panel (money agreement and version skew), then 037 and 038 (fewer setup questions, phone-led setup), 042 (phone provisioning guide). Each gets its spec when its turn comes.

Phases 1 and 2 are superseded by the ordered sessions in `docs/REAL_WORLD_TESTING.md` (feature 039). Phase 6.1 (monitor restarts a hung portal) and the Spec 02 items were built in features 014 to 021.

## Phase 8: commercial readiness

- **8.1** Confirm who holds the signing keys and that the owner-only steps in `docs/RELEASE.md` are written down.
- **8.2** Licensing as in `docs/LICENSING.md` (2 free slots, P500 per extra slot) is tested on a real box (RS27). D3 and D23: leave it alone; but see the open question about the 3.12.4 feed changelog in `specs/STATUS.md`.
- **8.3** Legal and liability terms, reviewed outside this repo.

## Cleanup (any time, low risk)

- Known and skipped on purpose (`CLAUDE.md`): firmware dead code (needs a firmware release); Android `MainActivity` `isLockTaskActive`, unused constructor parameters, stale lint-baseline entries, `device_admin.xml`, legacy launcher icons, `ACTION_FACTORY_RESET`, duplicate `getLocalIpAddress`.
