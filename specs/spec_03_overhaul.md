# Spec 03: the overhaul (make it simpler, provable, and quick to set up)

Status: **draft, waiting for the owner**   Written: 2026-10-28 against `main` at `9a92c33` (router 1.5.23, firmware 3.12.4, app 547)
Related: `specs/spec_02_always_on.md` (most of its reliability rows are now built, see section 2), `specs/roadmap.md` phases 6-8.

## 1. Goal
The product is already stricter than the competition on the thing that matters most — money correctness — and weaker on the two
things that decide whether a shop owner buys it: **how long setup takes** and **how obvious it is when something is wrong**.
Meanwhile the codebase carries features that are switched off, unproven, or duplicated.

This epic does three things, in this order:
1. **Remove** what is off, unproven or duplicated. This costs nothing and buys back the capacity to verify what remains.
2. **Close the two real gaps** left after spec 02: the console does not show that the money agrees, and nothing notices when the
   app, firmware and router feeds drift out of step.
3. **Make setup short enough to demo**: fewer questions, one front door, and eventually no computer at all.

The measure of success for the whole epic is not a feature count. It is: **fewer source files, fewer owed hardware checks per
release, and a first setup a shop owner finishes without being talked through it.**

## 2. What is already built (read in the code on 2026-10-28 — do NOT rebuild these)
`specs/STATUS.md` and `specs/RELEASE_STATE.md` are behind the code. Confirmed present:

| was | now | where |
|---|---|---|
| AO-01 hung portal never restarted | **built** | `router/piso_monitor.sh` `check_portal`: `/ping`, restart after N failures, one alert, a note when it answers again |
| AO-06 alerts need Telegram | **built** | `piso_monitor.sh` `note()` / `alert()` write every alert to `/etc/coinslot.d/alerts.txt` |
| AO-07 AuthWorker can hang unnoticed | **built** | `esp32_firmware/include/WorkerLiveness.h`, used at `src/main.cpp:154` |
| AO-05 nothing watches free flash | **built** | `piso_monitor.sh` `check_space` (1 MB and 256 KB alerts) |
| AO-02 ledger grows without bound | **in flight** | monthly segments built in `ledger.rs`; the size cap is feature 032 |

**First task of any feature under this epic: re-confirm its finding in the code before writing a line.** This epic was written
against `9a92c33`; if the finding is already fixed, say so, mark the feature `wont-fix` with the evidence, and stop.

## 3. Non-goals
- Any change to the coin path, pricing, settling, the box↔phone protocol, or the signing and release chain. Those are correct and
  expensive to disturb. Protocol work stays in roadmap phase 4.
- New customer-facing features of any kind. This epic only removes, surfaces, or shortens.
- The print server (another project).

## 4. Invariants (every feature under this epic restates how it keeps them)
- **Money and time are never wrong.** No feature here may change how a coin is recorded, credited or priced. Removing a feature
  must never remove time a customer already paid for, and never alter `revenue.csv` or its totals.
- **Only owner-signed software is installed.** Untouched throughout.

## 5. The features, in order

Each is one branch, one area, one chat. **Do them in this order**; later ones assume earlier ones landed.

| # | name | area | why it is here | release |
|---|---|---|---|---|
| **033** | Remove the spinning wheel | router | 5 Rust files + `wheel.html` (~99 KB source), 57 references across `core/http/config/main/console`, 5 test files. It is **off on every router**, has **never run on hardware** (RS34 never run), and its warning text has **never had a legal review** (roadmap 8.3). In the Philippines a paid spin for a prize is PAGCOR-adjacent. It is the largest block of unproven complexity in the product. | router |
| **034** | Remove the mini-game | router | `game.rs` + `game.html`, 50 references. The wheel was built to *replace* it, and feature 005's own notes record removing it as agreed and never done. With 033 landed it is the last of the two. | router |
| **035** | Say which routers are actually supported | docs + setup | Five CPU families ship; **only `mipsel` has ever run on real hardware**. Claiming five is five times the surface that cannot be verified. Demote `mips`, `armv7`, `x86_64` to "untested" in the docs and warn at setup; keep building them. | router (docs only) |
| **036** | The site status panel | router | Two real gaps left after spec 02: (a) `reconcile` agreement is computed and alerted on, but a shop owner cannot *see* it — it is the one claim no competitor can make, and it is invisible; (b) nothing notices when the app, firmware and router feeds drift (the build-495-vs-firmware-3.12.0 skew, whose workaround was "do not fill in Shop branding on any box"). | router |
| **037** | Setup asks fewer questions | setup | `SHOP_PRODUCT` is asked when it can be inferred; there are four setup entry points (`website/install.sh`, `pisophone_setup.py`, the Start-here pages, the Check-up). Every question removed is a support call avoided. | router + site |
| **038** | Phone-led setup (spec only this cycle) | app + firmware + setup | The decisive competitive move: no computer, no SSH, no Python. The owner's phone already has the app, a camera and Wi-Fi. **Specify it, do not build it yet** — it is worth building only on a base whose hardware checks are current. | none yet |

**Specs 033 and 034 are written in full** (`specs/features/033-remove-wheel/`, `034-remove-minigame/`). 035 to 038 are specified
here at the level above and get their own full spec **when their turn comes** — writing them now guarantees they are stale when
they run, which is exactly what happened to spec 02's AO-01, AO-06 and AO-07 (section 2).

## 6. What is deliberately NOT in this epic, and why
- **Slot licensing** (`SlotLicense.h`, `getDeviceCap`, P500 keys, the 12-slot cap, grandfathering). It is revenue-protection
  machinery for revenue that does not exist yet, so it looks like a deletion candidate — but it runs in live firmware and
  governs whether a phone may pair. Removing it changes behaviour on boxes in shops. **Owner decision D3** below.
- **`boxview.rs`** (the reverse proxy that frames the box's admin page inside the console). Well built; probably more code than a
  plain link to the box's address would need. Not worth the churn until someone complains. Revisit at the next replan.
- **Thinning the spec system itself.** `STATUS.md`, `RELEASE_STATE.md`, `roadmap.md`, `REPLAN.md`, `notes/` and `findings.json`
  overlap and are maintained by hand; section 2 of this epic exists only because two of them were wrong. Worth fixing, but it is a
  process change for a replan (`/sdd-replan`), not a feature.

## 7. Owner decisions (needed before 033 starts)
- **D1 — the wheel: remove, or keep and finish?** Removing it drops ~99 KB of source and 5 test files, and closes the legal
  question (roadmap 8.3) by making it moot. Keeping it means paying for the legal review and running RS34 before it can ever be
  switched on. **Proposed: remove.** It can be brought back from git history if a market for it appears.
- **D2 — the mini-game: remove with the wheel, or keep as the only free thing on the portal?** Removing both leaves a paying
  customer with nothing to do while waiting. **Proposed: remove**, because it is 18 KB of source and two browser test files for a
  feature nobody has asked for; but this is a product call, not an engineering one.
- **D3 — slot licensing: leave alone, stop enforcing, or remove?** Three options: (a) leave it exactly as it is (safest,
  proposed); (b) keep the code but raise `FREE_SLOTS` so it never refuses in practice; (c) remove it. (b) and (c) change live
  boxes' pairing behaviour and need their own feature and hardware check.
- **D4 — architectures: demote three to "untested" in public?** It is an honesty change that may cost sales to people with a
  TP-Link. **Proposed: yes** — an unsupported router that fails in a shop costs more than the sale was worth.

## 8. Gates for every feature under this epic
`CLAUDE.md` section 3, in full. For the router features that is: `cargo test` and `python3 tests/test_flow.py` in
`tools/pisoportal`, plus `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`,
`test_setup_tools.py`, and always `bash scripts/check_security_rules.sh`. A change under `tools/pisoportal/` raises
`setup/RELEASE`. Generated files are never edited by hand (`CLAUDE.md` section 4).

## 9. How this is measured when the epic closes
| measure | at `9a92c33` | target |
|---|---|---|
| Rust source files in `tools/pisoportal/src/` | count at start | minus 7 (033 + 034) |
| Router test files | count at start | minus 6 |
| Features shipped but never run on hardware | the wheel (RS34), among others | the wheel is gone, not pending |
| Questions asked during a first setup | count at start (037 group 0) | fewer, written down |
| Can an owner see that the money agrees? | no | yes, on the console front page |
