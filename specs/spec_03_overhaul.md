# Spec 03: the overhaul (make it simpler, provable, and quick to set up)

Status: **decisions D1 to D32 answered (D32 on 2026-10-29); 033, 034, 035, 039, 041 built and released as router 1.5.26 at 0%; 040 and 043 open (replan 2026-10-29)**   Written: 2026-10-28 against `main` at `9a92c33` (router 1.5.23, firmware 3.12.4, app 547)
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

| # | name | area | release |
|---|---|---|---|
| **033** | Remove the spinning wheel | router | router |
| **034** | Remove the mini-game | router | router |
| **035** | Say which routers are actually supported | docs + setup | router (docs only) |
| **036** | The site status panel: money agreement only (version skew split out as 044, D35) | router | router |
| **044** | Version skew warning (router and box firmware against the feeds; phone apps later) | router + firmware | router + firmware; spec when its turn comes |
| **037** | Setup asks fewer questions, one front door | setup + router | router + site |
| **038** | Phone-led setup | app + firmware + router | app + firmware + router |
| **039** | Triage the owed hardware checks | docs | none |
| **040** | Prove the hardening of a box that leaves your hands (GATE 1) | firmware (bench) | none |
| **041** | Require internet on the router: delete the offline package path | setup | router |
| **042** | Phone provisioning, documented end to end | docs + site | site |
| **043** | The box's secrets must not be readable on a sold box | firmware | firmware (sold units) |

Specs 033, 034, 040, 041 and 043 are **written in full** (`specs/features/033-remove-wheel/`, `034-remove-minigame/`). 035 to 039 have their
design decided in section 6 and get their full spec **when their turn comes** — a spec written three features early is stale by
the time it runs, which is exactly what happened to spec 02's AO-01, AO-05, AO-06 and AO-07 (section 2).

## 6. The decided design of each feature
All of this was decided with the owner on 2026-10-28. A feature that contradicts its paragraph here must change this epic first.

### 033 Remove the spinning wheel  (owner: remove)
Full spec written. ~99 KB of source, 57 call sites, 5 test files. Owner data (`wheel-confirm.txt`, `wheel.log`) is kept, not
deleted; `/wheel*` becomes 404; RS34 is retired with a reason, not silently dropped.

### 034 Remove the mini-game  (owner: remove)
Full spec written. Unlike the wheel it is **on by default**, so `CHANGELOG.md` says plainly that the free game is gone. A
`GAME_MINUTES` line already in a router's `settings.txt` is left in place and ignored.

### 0. The business model this epic now serves  (owner, 2026-10-28)
**You sell assembled ESP32-C3 coin boxes. Routers are bring-your-own.** The box is where the product's value is (the coin ISR
and pulse guard, the durable payment queue, signed OTA with rollback, card accounts); the router software is free and the
router is a commodity you would make no margin on. This avoids router inventory, per-model images, bricked-router support and
the GPL distribution question, while still giving you a controlled device.

Three consequences run through the features below:
- `docs/ROUTERS.md` stops being advice and becomes **the customer contract** (035).
- The box's own page stops being a convenience and becomes **the product's main surface** (037, 038).
- A box that leaves your hands must be hardened, and that has never been proved on a board (040). **This gates revenue, not
  code.**
- Hardening does not actually cover the box's secrets today (043). Found while reviewing 040; see below.

Already pointing this way in the code: the box dashboard builds `https://pisophone.pages.dev/?mac=<mac>`
(`esp32_firmware/src/WebDashboardComponents.cpp:80`), `website/install.sh` is already the one-liner from that site,
`website/start/` already has the pages, and `docs/PROVISIONING_SOLD_UNIT.md` already opens with "only boxes that leave your
hands". The plan is the direction the repository was already taking.

### 035 Say which routers are actually supported  (owner: demote three, keep building them, name one to buy)
**Only `mipsel` is supported (D32, 2026-10-29: aarch64 is untested too; the first draft of this paragraph called aarch64
supported).** `mips`, `aarch64`, `armv7` and `x86_64` are **untested**: `docs/ROUTERS.md` gains a support column, and the installer **warns and proceeds** on an untested family —
it never refuses. CI keeps building all five, so the door stays open and a family can be promoted the day one runs in a shop.
Because routers are now bring-your-own, this document is the customer contract: it also **names one recommended model the
customer can order**, so most buyers land on known-good hardware and most support conversations never start. You do **not**
stock or resell that router — that would put you back into the inventory this model exists to avoid.

**The recommended model is the Qihoo 360 T6M** (D31): the only router the program has ever run on. The rule behind that is
the owner's: recommend only hardware that has actually been tested, and let the list grow as models are proven rather than
ahead of the evidence. 035 must say honestly what "tested" covers — the program runs on it under ImmortalWrt 23.05.0, but
RS35 has never been run, so not every feature has been checked on it — and must not imply a model is supported because it
appears in the compatibility table.

### 036 The site status panel  (owner: warn only; always visible; show both numbers)
**Changed 2026-10-29 (D35 to D37, `specs/features/036-site-status-panel/spec.md`): 036 is the money agreement panel only; the version-skew bullet below moves to feature 044 and its "the box holds it" design is withdrawn (the box never fetches the feed). On current firmware a box ahead of the record is not rental phones (D37).** The original text follows.
Two gaps in one feature, both on surfaces the owner already looks at.
- **Version skew.** The **coin box** holds the expected set, because it is the only part present in every configuration and it
  already sees phones (heartbeat) and the router (boxlink). It learns the expected versions from the signed feed it already
  fetches. On skew it **warns and never blocks**: a line on the box dashboard, on the router console, and in `alerts.txt`. It
  never refuses a coin and never refuses to pair. A shop mid-update keeps earning.
- **Money agreement, always on the console front page.** A permanent line showing **both numbers and the reason for any gap**:
  "Box 1,310. Wi-Fi ledger 1,247. Difference 63 = rental phone coins. Normal." The box being ahead is normal (it also counts the
  rental phones' coins) and its lifetime count restarts when the owner collects, so the panel must teach those two cases rather
  than flag them. Only a box *behind* the ledger is a real mismatch.
- No history page in this feature (it was offered and not taken); the alert path already covers a mismatch over time.

### 037 Setup asks fewer questions, one front door  (owner: always create the phone Wi-Fi; the box's page is the front door)
- **The product question goes away.** Every router gets the hidden rental-phone Wi-Fi whether or not phones ever arrive, because
  the cost is one unused SSID and a password. `SHOP_PRODUCT` stops being asked. Routers already set up are not changed.
- **The box's own page is the single front door.** Power the box, join `PisoCoinBox-xxxx`, and the box's page walks the owner
  through setup and hands them the router command **pre-filled**. `website/install.sh`, `pisophone_setup.py`, the Start-here
  pages and the Check-up all stay, but they become routes into that flow rather than four separate beginnings.

### 038 Phone-led setup  (owner: add it, keep the computer path; the phone drives the router over HTTP)
The decisive competitive move: no computer, no SSH, no Python. The owner's phone already has the app, a camera and Wi-Fi.
- **Added, not substituted.** `pisophone_setup.py` stays for recovery and for anything the phone cannot do. Nothing that works
  today stops working.
- **The phone drives the router over HTTP**, through a small setup endpoint the router exposes **during first setup only**,
  which closes when setup finishes. No SSH client in the app, and the router's root password never reaches the phone.
- Build it only on a base whose hardware checks are current — which is why 039 exists and why this is last.

### 037 (addition) the box page is the product surface
With boxes sold and routers bring-your-own, the box's page is the first thing every customer sees and the only surface you
control. It hands over the pre-filled router one-liner. **One full firmware build ships on every box** — what it serves is
decided by setup, not by the image, so there is one SKU, one update feed and no wrong-variant returns. The `-phone` and
`-wifi` variants in `esp32_firmware/envs/products.ini` stay for your own builds; they are not sold.

### 039 Triage the owed hardware checks  (owner: yes, as its own feature)
RS28-RS52 are the real gap between this product and ones proven by attrition. This feature writes no code: it reads every owed
check, marks the ones later releases made obsolete, and reduces the rest to **one ordered list the owner can work through a
session at a time**. Output is `docs/REAL_WORLD_TESTING.md` and nothing else.

### 040 Prove the hardening of a box that leaves your hands  (owner: blocking before any sale)
`docs/PROVISIONING_SOLD_UNIT.md` requires Secure Boot V2 and flash encryption on sold units and says plainly that its **GATE 1
has never passed on a board**; the eFuse steps are irreversible and a mistake bricks the unit. The build environments already
exist (`esp32-c3-production-encrypted`). This feature runs the guide on a **sacrificial board**, records the result in the
guide's own table, and writes nothing else. **No box is sold until it passes.** Secure boot cannot be retrofitted remotely, so
an unhardened box sold today stays unhardened for its life.

### 041 Require internet on the router: delete the offline package path  (owner: delete, confirmed twice)
Feature 011's offline path is removed: `setup/src/21-local-packages.sh`, the `usign`-per-source verification, `--local` /
`PISO_LOCAL_PKGS`, `--router-online` and the fallback logic. The setup pages state that the router needs internet.
**Recorded consequence, accepted by the owner:** a router with no WAN during first setup can no longer be set up at all, where
today it degrades to the computer path. That is the case of replacing an old router, a modem being swapped, or the ISP not yet
connected. This also discards work that shipped as router 1.5.18 and passed RS36 and RS37 on the owner's own bench on
2026-10-23. The owner was shown this and chose deletion; it is written here so the decision is on the record rather than
discovered later.

### 043 The box's secrets must not be readable on a sold box  (found 2026-10-28, blocking before any sale)
`docs/PROVISIONING_SOLD_UNIT.md` opens by promising that "a buyer cannot read the firmware or the stored secrets with a flash
reader". **That promise is not kept.** Flash encryption does not cover the NVS partition, and NVS is where the per-box shared
secret, the admin password and the router gateway key live. Worse, `esp32_firmware/partitions_encrypted.csv` marks `nvs` as
`encrypted` — a flag ESP-IDF ignores for NVS — so the table reads as though the job were done.

Found during the feature 040 eFuse review (`specs/features/040-sold-box-hardening/efuse-review.md`, item 14, cited against
ESP-IDF v5.4.2) and confirmed in this repository: `CredCrypto.h` is verification helpers, not at-rest encryption, and there is
a free 60 KB gap in the partition table if a key partition is needed.

Two approaches are on the table (ESP-IDF NVS encryption, or encrypting the few secret values in the application); the feature's
group 0 picks one with evidence, because the choice turns on a question nobody has answered yet: **how a hardened box in a shop
takes a signed over-the-air update without being left unable to read its own NVS.** No image that an existing box can take over
the air may expect encrypted NVS — an existing box's NVS is plaintext, and such an update would destroy its secret, its
accounts and its queued payments.

**Follow-up from the same review, not part of 043:** `scripts/sold_unit_check.py` calls `espefuse.py`, which stops working once
`ENABLE_SECURITY_DOWNLOAD` is burned; it needs to use `esptool get_security_info` instead. Small, its own change.

### 042 Phone provisioning, documented end to end  (owner: document it, the customer does it)
Device owner needs a factory reset plus QR or NFC provisioning, and it is the hardest step in the product. This feature writes
the end-to-end guide and has the box page produce the provisioning QR, so the customer has one page to follow. You do not sell
phones and you do not provision them as a service.

## 7. Owner decisions — all answered 2026-10-28
| id | question | answer |
|---|---|---|
| D1 | the spinning wheel | **remove** (feature 033) |
| D2 | the mini-game | **remove** (feature 034) |
| D3 | slot licensing | **leave exactly as it is.** It is live firmware governing pairing in real shops; the risk of touching it outweighs the complexity it costs today. Revisit when there is a reseller. Not in this epic. |
| D4 | the five CPU families | **demote three to untested, keep building all five** (feature 035) |
| D5 | where the expected version set lives | **the coin box** (feature 036) |
| D6 | what happens on version skew | **warn only, never block** (feature 036) |
| D7 | how prominent the money agreement is | **always on the console front page** (feature 036) |
| D8 | what the panel shows when the box is legitimately ahead | **both numbers and the reason** (feature 036) |
| D9 | the product question | **always create the phone Wi-Fi; stop asking** (feature 037) |
| D10 | the single front door | **the box's own page** (feature 037) |
| D11 | phone-led setup: replace or add | **add; keep the computer path** (feature 038) |
| D12 | how far the phone reaches into the router | **HTTP to a first-setup-only endpoint** (feature 038) |
| D13 | when 033 starts | **after feature 032 merges** (section 8) |
| D14 | how 033-035 are released | **one router release for all three** (section 8) |
| D15 | the owed hardware checks | **a triage feature** (feature 039) |
| D16 | `STATUS.md` and `RELEASE_STATE.md` being wrong | **fix at the next replan**, not here (section 9) |
| D17 | what you sell | **assembled boxes, ready to mount**; routers are bring-your-own |
| D18 | hardening a sold box | **blocking: GATE 1 passes before any sale** (feature 040) |
| D19 | phone provisioning | **documented, the customer does it** (feature 042). Not sold, not a service. |
| D20 | which firmware ships on a sold box | **one full build for everything**; variants are not sold (037) |
| D21 | the router's internet during setup | **required; the offline path is deleted** (feature 041) |
| D22 | `docs/ROUTERS.md` | **name one recommended model to buy**, do not stock it (035) |
| D31 | which model to recommend | **The tested one: Qihoo 360 T6M** (board `360,360t6m`, MT7621, mipsel, 16 MB NOR + 128 MB NAND), answered 2026-10-28. It is the only router the program has ever run on, on ImmortalWrt 23.05.0 (r27285). The owner's rule is to recommend only hardware that has actually been tested, so the list grows as models are proven, never ahead of the evidence. **Caveat to record in 035:** RS35 on that router has still never been run, so "tested" means the program runs on it, not that every feature has been checked |
| D23 | slot licensing, now that boxes are sold | **still leave it alone**; revisit once boxes are selling |
| D24 | where GATE 1 sits | **first, in parallel with the deletions** (section 8) |
| D25 | what "drop the offline path" meant | **actually delete the code** (feature 041), confirmed after the consequence was put to the owner |
| D26 | does the NVS finding block the first sale? | **No, answered 2026-10-28 by D30.** Both paths are supported and the owner picks; a box sold on the legacy build simply has the guide's promise corrected to say what is and is not protected (043 criterion 8) |
| D27 | NVS: approach A or B | **B, answered 2026-10-28.** A cannot be done on the build the boxes run: `nvs_flash_secure_init` is not compiled into the stock Arduino libraries (`CONFIG_SECURE_FLASH_ENC_ENABLED` is off) and `initArduino()` runs the plain init, which **erases** entries whose CRC fails, before `setup()`. Evidence: `specs/features/043-nvs-secrets-at-rest/group0-findings.md` |
| D30 | **hardening is an option, never a forced migration** | **Answered 2026-10-28.** The flash-encrypted path is kept correct and buildable, but the **legacy build that has actually run on real hardware stays the default and stays selectable by the owner.** Approach B already works this way: encryption engages only when the key partition exists *and* flash encryption is on; otherwise the code takes today's plaintext path, byte for byte unchanged. No feature under this epic may make hardening compulsory, and no release may drop the legacy path |
| D26-old | (superseded, kept for the record) | **was open.** Proposed: yes — selling a box advertised as hardened whose secrets are readable is worse than selling an honestly unhardened one (feature 043) |
| D29 | hardened boxes have no working update path from the feed | **open, and the most serious.** A Secure Boot V2 box runs only images carrying a Secure Boot signature block; feed images carry a manifest signature only, and the Secure Boot key is never in CI. A hardened box would report a successful update and then silently boot the old image. Found by 043's group 0; it belongs to 040. **D30 reduces its urgency** (a legacy box updates normally today), but it must be solved before any box is sold hardened |
| D32 | is `aarch64` supported? | **No, answered 2026-10-29.** Only `mipsel` (the Qihoo 360 T6M's family, the only one the program has run on) is supported; `aarch64` joins `mips`, `armv7` and `x86_64` as untested. Built in feature 035 (`docs/ROUTERS.md`, `check_arch` warns and proceeds for all four, never refuses). Overrides the first draft of the 035 paragraph in section 6 |
| D33 | slot licensing and the 3.12.4 feed text | **Answered 2026-10-29: licensing stays (confirms D3 and D23); the feed text is wrong.** `website/update/firmware.json` says "Slot licensing removed: all 10 phone slots are available" but `SlotLicense.h` still has `FREE_SLOTS = 2`. The signed feed is not edited; the next firmware release carries an accurate changelog line |
| D34 | branches | **Answered 2026-10-29:** no `beta`; a green feature branch merges straight into `main`; CI publishes the router at 0% and only the owner raises it. `CLAUDE.md` section 5 rewritten to say so (the frozen constitution still says "from `beta`" in rule 8; it is read as `main` until its next version) |
| D35 | split 036? | **Yes, answered 2026-10-29.** 036 is the money agreement panel only (router, no firmware or app release). Version skew moves to **feature 044**, starting from design B3 (router and box firmware against the feeds; phone app versions later). Reason: the box never fetches the signed feed (the owner's browser does, `ota.js`) and phones do not report an app version, so D5's design cannot work as written. D6 (warn only) and D7, D8 stand |
| D36 | how fresh the money panel is | **Live with a 60-second cache, answered 2026-10-29** (at most one extra box call a minute) |
| D37 | box ahead of the record on current firmware | **Show it only, answered 2026-10-29.** The box reports Wi-Fi coins on their own, so a box ahead is not rental phones; the panel says so (gold, never red) and no alert is added |
| D28 | the guide's "mixed" eFuse route | **Answered 2026-10-28: drop the hand burns, use the documented flow.** The operator burns **only the two keys** (the flash-encryption key and the Secure Boot digest). The Release-mode bootloader sets `SECURE_BOOT_EN`, the `DIS_*` fuses, secure download mode and `SPI_BOOT_CRYPT_CNT` itself on first boot, and revokes the unused digest slots. The whole procedure becomes one flow ESP-IDF documents, with three fewer irreversible operator steps. The irreversible moment is now the **first boot of the hardened bootloader**, which is what feature 040's corrected criterion 3 already says |

## 8. Sequencing and releases
- **033 does not start until feature 032 (the ledger size cap) is merged.** Both touch `tools/pisoportal`; 032 is almost all
  `ledger.rs` and 033 is `core/http/config/main/console`, but two sessions in one crate means an avoidable conflict. A deletion
  loses nothing by waiting.
- **033, 034 and 035 ship as one router release.** Merge all three to `main`, then cut a single release: one rollout, one
  `verify_release.py`, one set of checks instead of three. They are all deletions and they test well together.
- **041 joins the same release** as 033, 034 and 035: it is a setup change with no overlap with the deletions, and batching it
  keeps the rollout count down. It carries its own hardware check (a first setup on a router with internet, and a stated,
  accepted failure on one without).
- **040 runs first and in parallel.** It needs bench time and a sacrificial board, not a coding session, and nothing in the
  epic depends on it — but nothing ships to a customer until it passes.
- **043 blocks the first sale alongside 040**, and its bench steps are written into 040's `bench.md` so they run on the same
  trip to the board. Its group 0 is a paper decision and can start at once; its code must not start before the owner has seen
  that decision.
- **036 is a firmware release as well as a router one** (the box holds the expected version set), so it is on its own.
- **039 has no release at all** and can be done any time, including in parallel with the deletions, because it touches only
  `docs/REAL_WORLD_TESTING.md`.

## 9. What this epic deliberately does NOT do
- **Slot licensing**: D3, left alone.
- **`boxview.rs`** (the proxy that frames the box's admin page in the console): probably more code than a plain link needs, but
  nothing is broken. Revisit at a replan.
- **Thinning the spec system.** `STATUS.md`, `RELEASE_STATE.md`, `roadmap.md`, `REPLAN.md`, `notes/` and `findings.json` overlap
  and are hand-maintained; section 2 of this epic exists only because two of them were wrong. D16: this is a process change for
  `/sdd-replan`, not a feature. Do it at the next replan, and decide there what is maintained by hand and what is generated.

## 10. Gates for every feature under this epic
`CLAUDE.md` section 3, in full. For the router features: `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal`,
plus `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`, `test_setup_tools.py`, and always
`bash scripts/check_security_rules.sh`. A change under `tools/pisoportal/` raises `setup/RELEASE`. Generated files are never
edited by hand (`CLAUDE.md` section 4).

## 11. How this is measured when the epic closes
| measure | at `9a92c33` | target |
|---|---|---|
| Rust source files in `tools/pisoportal/src/` | count at start | minus 7 (033 + 034) |
| Router test files | count at start | minus 6 |
| Features shipped but never run on hardware | the wheel (RS34), among others | the wheel is gone, not pending |
| Owed hardware checks | RS28-RS52, unordered | one ordered list, obsolete ones closed (039) |
| Questions asked during a first setup | count at start (037 group 0) | fewer, written down |
| Can an owner see that the money agrees? | no | yes, on the console front page, with both numbers |
| Secrets readable from a sold box's flash | **yes** (NVS is not covered) | no, or the promise is corrected to say what is protected (043) |
