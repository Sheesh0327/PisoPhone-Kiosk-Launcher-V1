# Feature 037: one front door, and one question fewer

Status: **approved by the owner 2026-10-29 (D33, D34, D35 answered below).** Branch: `feat/037-setup-front-door`   Area: firmware + setup
Epic: `specs/spec_03_overhaul.md` feature 037 (decisions D9, D10, D20)   Release: firmware + router

## Intent
Setting up a shop has four beginnings (`website/install.sh`, `setup/pisophone_setup.py`, `website/start/`, the Check-up) and
asks a question it can answer itself. With boxes now sold and routers bring-your-own (epic section 0), the **box's own page is
the first thing every customer sees and the only surface the owner controls**. This feature makes it the single place that says
where you are and what to do next, and removes the product question.

It does **not** remove the computer. That is feature 038. 037 makes the remaining steps correct and findable; it does not make
them unnecessary.

## Correction to the epic, found while writing this spec (read this first)
The epic says the box's page "hands them the router command **pre-filled**". **That is not possible, and after this feature
there would be nothing to pre-fill anyway.** Three facts from the code:

1. **The gateway key is generated on the router, not the box.** `setup/src/98-setup-flow.sh:130` runs
   `secret GW_KEY 64 hex`, and `provision_box()` (`setup/src/50-coin-box.sh:118-137`) then pushes the new admin password and
   that key **to** the box over HTTP (`/save`, then `/api/gateway/config`). The box is passive: it receives. It cannot print a
   key it has not been given.
2. **The installer takes no user-facing parameters any more.** After feature 041, `website/install.sh` reads only
   `PISO_*` test hooks. The one remaining user-settable value is `SHOP_PRODUCT`, passed in the environment.
3. **This feature removes that one.** So the one-liner becomes a **constant string**, identical for every shop.

So "pre-filled" is the wrong idea. The box page's value is not carrying a secret; it is **being the one place that knows and
shows the state of the setup**, which it genuinely can, because it is the only part present in every configuration and it is
told when a router pairs with it. The epic's 037 paragraph must be corrected to say that before this feature is approved.

## Invariants touched
- **Money and time:** not touched. No coin, pricing, ledger, queue or payment code changes. The box's existing arm/ack path and
  `SETUP_REQUIRED` behaviour are unchanged. Criterion 11 proves it.
- **Only owner-signed software:** not touched. The page shows a command; it installs nothing and fetches nothing new. The
  command it shows must be a **literal constant in the firmware**, never assembled from anything the page is told, so that a
  compromised dashboard session cannot change where an owner is sent (criterion 9).

## Part A — the product question goes away (epic D9)
Today `product_choice()` (`setup/pisophone_setup.py:206-217`) asks PisoWiFi / PisoPhone / both, and `shop_product()`
(`setup/src/10-settings.sh:41-46`) resolves `wifi | both`, default `both`. `setup/src/40-files.sh:119` writes the answer into
`coinslot.conf`, and `98-setup-flow.sh:157` unsets it for every command except the installer.

**The change: stop *asking*. Do not stop *reading*.** That distinction is the whole risk in part A.

- **A new install** always sets up the rental-phone Wi-Fi. No question, no `--product` prompt.
- **A router already set up with `SHOP_PRODUCT=wifi` keeps behaving exactly as it does today**, on this update and every later
  one. An update that silently added a hidden SSID to a live shop's radio would be changing a running shop without asking,
  which `CLAUDE.md` section 6 forbids. `shop_product()` keeps reading the stored value; only the *prompt* and the
  *environment override on a fresh install* go.
- `--product` / `--phone-only` on `pisophone_setup.py`: keep them accepted but undocumented for one release, so a script or a
  written note that still passes them does not fail. Remove in a later release. Say so in `CHANGELOG.md`.

**One thing the epic asserts without evidence and this feature must measure.** The epic says the cost of an always-created
hidden SSID is "one unused SSID and a password". Feature 010 introduced `SHOP_PRODUCT=wifi` partly for constrained routers.
Before part A ships, measure on the owner's 360 T6M: the extra SSID's effect on free memory, and whether the 2.4 GHz radio
reports any limit. If it costs anything real, part A stops and the owner decides (criterion 2).

## Part B — the box page is the front door (epic D10)
The box already serves a dashboard, already has its own AP, and already links to `pisophone.pages.dev/?mac=<mac>`
(`esp32_firmware/src/WebDashboardComponents.cpp:80`). Part B adds a **Set up this shop** view that answers, in order:

1. **Where am I?** The box's own state, which it alone knows:
   - admin password still the published default → the `SETUP_REQUIRED` banner already exists; say what to do about it;
   - firmware version, box id and the address the page is being read on;
   - how many phones are paired.
2. **Is a router paired?** The box knows this and nothing else does from here: `/api/gateway/config` sets its gateway key and
   answers `"configured":true` (`setup/src/50-coin-box.sh:136`). So the page can say **"no router has paired with this box
   yet"** or **"a router paired at <time>"**. Today an owner learns this only by running the Check-up on a computer. This is
   the single most useful thing part B adds and it costs almost nothing.
3. **What do I do next?** The one-liner, as a constant, with a copy button, and in one sentence what it will do. Plus the
   prerequisite feature 041 made mandatory: **the router needs internet for the first setup** — the same wording as
   `website/start/shop.html:24`, so the two never disagree.
4. **Where else can I go?** Links to the Start-here pages and the Check-up — as routes out of this page, not rival beginnings.

**Out of scope for part B:** the page never configures anything, never talks to the router, and holds no router credential. The
box must not gain any ability to reach a router's admin interface — it is the physically exposed device in the shop, on a
published default password until setup changes it, and that trust boundary is deliberate. A `grep` for `ssh|uci|opkg` in
`esp32_firmware/src/` returns nothing today and must still return nothing after this feature (criterion 10).

## Part C — one firmware build per box (epic D20)
Every sold box ships the full build; what it serves is decided by setup, not by the image. The `-phone` and `-wifi` variants in
`esp32_firmware/envs/products.ini` stay for the owner's own builds and are **not sold**. This part is documentation plus
whatever `scripts/make_firmware_bundles.py` needs so the owner zip for sale contains the one full image. No code path in the
firmware changes.

## Constraints
- Firmware changes need a higher `PISO_FW_VERSION` or boxes ignore them (`CLAUDE.md` section 4).
- A change under `setup/src/` raises `setup/RELEASE`; generated files are never hand-edited
  (`python3 tools/build_piso_setup.py --check`).
- The box's web assets are generated: edit `esp32_firmware/web/`, then `python3 scripts/embed_web.py`. **Never** edit
  `include/WebAssets.h` (`CLAUDE.md` section 4).
- The box is an ESP32-C3 with a 20 KB NVS partition and limited RAM. The new view must add no stored state; everything it shows
  is already known. Measure the page's added bytes against the dashboard's existing budget (criterion 8).
- `setup/src/` stays BusyBox `ash` safe and ShellCheck-clean for POSIX sh.
- The setup program stays Python standard library only.

## Success criteria
1. `pisophone_setup.py` asks no product question on a fresh install, and the rental-phone Wi-Fi is always created.
   `router/tests/test_pisophone_setup.py`.
2. **Measured, not assumed:** the cost of the always-created SSID on the owner's 360 T6M (free memory before and after, and any
   radio limit reported), written into `validation.md`. If it is not negligible, part A stops and the owner decides.
3. A fixture router with `SHOP_PRODUCT=wifi` in `coinslot.conf` runs an update and **still has no rental-phone Wi-Fi**, and its
   `coinslot.conf` is byte-identical apart from the release line. `router/tests/test_setup.py`. **This is the criterion that
   decides whether part A may ship.**
4. `--product` and `--phone-only` still work, are absent from `--help`, and `CHANGELOG.md` says they go in a later release.
5. The box's new view shows: setup state, firmware version, box id, phone count, **whether a router has paired**, and the
   one-liner with a copy button. A host test covers the pure part (what the view should say for each state); the rendering is
   checked in the dashboard's existing browser test.
6. With no gateway key set, the page says no router has paired. After `/api/gateway/config` succeeds, it says one has.
   `esp32_firmware/host_tests/`.
7. The prerequisite sentence on the box page and in `website/start/shop.html` is the **same wording**. Prove it with a test
   that compares the two strings, so they cannot drift.
8. The box's web assets grow by less than 4 KB after `scripts/embed_web.py`; record before and after.
9. The one-liner is a literal constant in the firmware source. `grep` it; nothing the page is told may appear in it.
10. `grep -rnE "ssh|uci|opkg" esp32_firmware/src/` returns nothing (the box still cannot configure a router).
11. **Money:** the firmware host tests pass unchanged and no file under the coin, payment or queue path is touched. `git diff
    --name-only` shows it. Say how you checked (`CLAUDE.md` section 6).
12. `bash scripts/check_security_rules.sh`, `python3 tools/build_piso_setup.py --check`, the firmware host tests, the four
    router test suites, `clang-format` clean. `PISO_FW_VERSION` and `setup/RELEASE` raised.
13. `setup/MANUAL.md` and `docs/ROUTERS.md` no longer tell an owner to choose a product; `website/start/` points at the box
    page as the first step.

## Out of scope
- Anything that removes the computer. That is 038, and its cost depends on an unanswered question: whether the phone can drive
  the router's **existing** `ubus`/`rpcd` HTTP API rather than a custom endpoint this feature would have to install first.
  **Answer that before 038 is specced** — it is the difference between a moderate feature and a large one. The owner chose not
  to queue the investigation now (2026-10-29); it is recorded here so whoever picks 038 up finds it.
- The box configuring a router (see part B, out of scope).
- Any change to pairing, the gateway key's generation or direction, or the box↔phone protocol.
- Removing `--product` outright (one release later).

## Owner decisions
All answered 2026-10-29.
- **D33 — the corrected part B: approved.** The box page shows state, not secrets: setup state, firmware, phones paired, and
  whether a router has ever paired with this box. Inverting the pairing so the box generates the gateway key was offered and
  **rejected** — it is a protocol change on the money path for a cosmetic gain.
- **D34 — measure first, then decide.** Part A does **not** ship until criterion 2's number from the owner's 360 T6M is in
  `validation.md`. If the cost is negligible the question goes as planned; if not, the owner decides with the number in hand.
  This makes criterion 2 a gate, not a note.
- **D35 — part C changes the sale zip when boxes actually ship, not now.** Document the one-SKU decision in this feature;
  leave `scripts/make_firmware_bundles.py` alone. Nothing is gained by changing build tooling for a product that cannot ship
  until GATE 1 (feature 040) and the NVS work (feature 043) are both done. **Task group 6 is therefore documentation only.**
- **038's `ubus` spike: not queued** (owner, 2026-10-29). The question stays written in "Out of scope" for whoever specs 038.
