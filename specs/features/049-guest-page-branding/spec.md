# Feature 049: branding on the guest Wi-Fi page

Status: **validated 2026-10-30 (criteria 1 to 10 pass; 11 is hardware, RS63); merged to `main` the same day and router 1.5.31 signed at 0%; not done until the owner signs off the Filipino wording and RS63 passes, then raises the rollout**   Branch: `feat/049-shop-branding` (from `main`; a router change, so it reaches `main` through `beta`, `CLAUDE.md` section 5)   Area: router
Backlog rows: none (owner request 2026-10-30; decisions D1 to D11 in `specs/spec_05_shop_branding.md`)   Release: router

## Intent
A shop owner who bought a PisoWiFi router wants the page their customers see to say *their* shop, in *their* colours and words, with
their logo. Today the page is fixed "TechNet PisoWifi" (logo, mascot and navy theme are compiled into the router program). This feature
lets the shop owner set name, tagline, welcome text, palette and accent colour, layout, contact line, house rules, opening hours and a promo
banner in English and Filipino, preview it, and publish it from the router console; and lets the owner (not the shop owner) give a shop a
logo through a signed brand pack. It serves both rules: it never touches the money path, and artwork only comes from software the owner signed.

## Invariants touched
- Money and time: **not touched.** `core.rs`, `roll.rs`, `ledger.rs` and `boxlink.rs` do not change. The price list, plan buttons, coin counter,
  Done button and result screens are *not brandable*: no setting can hide, move or restyle them out of use (the page is the only way to pay).
- Only owner-signed software: **kept.** Texts and colours are configuration. Images are accepted only from a pack signed with the owner key (the same
  P-256 check as router updates). Nothing a pack or a setting holds is ever run: it is data that is displayed.

## What a visitor sees (3b: every condition, stated)
| Case | The customer sees |
|---|---|
| No branding was ever published (every router on day one) | **Exactly today's page** (TechNet header, mascot, navy theme), byte for byte of behaviour; no change after the update (D11) |
| Branding published, no logo pack | The shop name as text in the header instead of "TechNet PisoWifi"; no TechNet logo is shown as if it were the shop's (Q2) |
| Branding published, valid logo pack | The shop's logo and name; the page title and the home-screen icon use them |
| A pack is damaged, unsigned or too large | It is refused at install; the previous logo (or none) stays. The page never shows a broken image |
| Filipino chosen, but a text has no Filipino version | That text in English |
| Any text field left empty | That block is not shown (no empty frame) |
| Idle page (before paying) | Promo banner, plans, house rules, contact line, opening hours, all as set |
| Coin window, counting, result, error, "other plan" screens | The shop header and colours; the payment controls unchanged; contact line in the footer. House rules and promo are idle-page only |
| Unpaid visitor vs paid visitor | The same shop page; a paid visitor sees the same header on the "you are online" result |
| Always, every case | A small locked "by TechNet Solutions" credit (D2). It is drawn by the program, not by a setting, a pack or any text |

## Constraints
- **Not in a setting:** the credit, the payment controls, the price list, the result texts' meaning. Settings are validated, not trusted.
- **Text safety:** owner texts are inserted as plain text only (no HTML, no links, no scripts). Limits (to confirm, Q4): shop name 32, tagline 60, welcome 160, contact 80, house rules 600, opening hours 60, promo 120 characters, per language. Control characters are removed.
- **Colours:** a palette from a fixed list plus one accent colour (`#rrggbb`). The program derives button text, hover and disabled colours itself and refuses or adjusts an accent that cannot give text a contrast of at least 4.5:1 (the console tells the owner).
- **Images:** PNG or JPEG only (**no SVG**: it can carry scripts), at most 64 KB and 512 by 512 pixels each, checked by their file signature and size, never by name. A pack holds a logo and one optional picture, so 128 KB at most in all (the file itself at most 300 KB with its base64).
- **The pack:** `pisophone-brand-v1`, signed with the owner key. The key never enters the repository, a cloud session or CI (`docs/KEYS.md`): a new owner tool `scripts/make_brand_pack.py` runs on the owner's computer only. The pack is not bound to one router (D5): a copied pack works on another router. Accepted.
- **Who may do what:** a shop owner signs in to the console (the router's root password, as now) and edits texts, palette, accent, layout, languages; the console also accepts a pack file (it is signed, so the shop owner cannot forge one). The owner may also install a pack with `piso-setup brand-pack <file>` over SSH.
- **Draft and published are separate:** edits go to a draft; the live page changes only on Publish, atomically (write, fsync, rename, `util::write_atomic`). Restore default look removes the published branding (the logo pack stays installed but unused).
- **Preview** is served on the console port, signed-in only, from the draft; in preview the page cannot start a coin window.
- **Survives updates and sysupgrade:** branding lives under `DATA_DIR` (`/etc/coinslot.d`), which updates keep; `piso-setup update` must not wipe it.
- **Size:** the router budgets in `router/tests/test_footprint.py` hold today at program 1027 of 1200 KB (mipsel), `page.html` 25 of 28 KB, `setup/piso-setup.sh.in` 148 of 150 KB. The setup script has almost no room: the pack work goes in the Rust program (`pisoportal brand-install`), the shell only calls it. `page.html` will need more room (Q5).
- **Public interface for feature 050:** `GET /brand.json` on the portal port (no sign-in; it holds only what guests already see: name, tagline, accent, contact, logo address). Its shape is fixed in plan group 2 and tested.
- The generated router files are rebuilt, never edited by hand; `setup/RELEASE` is raised; the release reaches `main` through `beta` and the embedded program is decoded and checked before anyone raises the rollout.

## Success criteria
1. With no branding set, the page served is the same as today's for every state (golden-file test of the idle, armed, counting, final, error and mismatch views).
2. A published name, tagline and welcome text appear on the idle page in English and in Filipino, switch with the customer's choice, and fall back to English per text.
3. A script or HTML tag typed into any text field is shown as plain text and never executed (tests with payloads in every field, in both languages).
4. An accent colour that fails the contrast rule is refused or corrected; the five palettes and the layouts all keep text at 4.5:1 or better (a computed test over every palette and layout).
5. The payment controls and price list are present, visible and clickable in every layout and palette (browser test over all of them at 360 by 740).
6. A pack with a good signature installs; a pack with a changed byte, a wrong key, an SVG, an oversize image or a bad signature is refused with a reason and changes nothing.
7. Draft edits do not change the live page; Publish does, atomically; Restore default look brings today's page back; preview cannot start a coin window.
8. The locked credit is present in every state and every layout and cannot be removed by any combination of settings (test).
9. Branding and the logo survive `piso-setup update` and a router reboot.
10. No file of the money path changed (`git diff --stat` shows none of `core.rs roll.rs ledger.rs boxlink.rs voucher.rs reconcile.rs`); `cargo test`, `test_flow.py`, `test_footprint.py`, the setup tests and `check_security_rules.sh` pass.
11. Hardware: owner's checks on a real router and a real phone (new rows in `docs/REAL_WORLD_TESTING.md`): Android and iPhone captive-portal sheet shows the branding; the saved voucher file and the page icon; a pack installs from the console.

## Out of scope
Printed items (feature 050), anything on the rental phones or the coin box (feature 051), custom CSS or HTML per shop, SVG logos, a pack bound to one router, languages beyond English and Filipino, editing the built-in price list wording, removing the credit (never), clickable links in texts.

## Owner decisions
The answers D1 to D11 are in the epic. Q2 to Q7 were answered on 2026-10-30 with "go with your recommendations"; Q1 was decided from screenshots (plan group 0).
- **Q1 Layouts.** *Answered 2026-10-30 from the screenshots:* **all four**: A Classic, B Banner, C Compact, D Poster. **Default: A Classic.** The candidates are drawn in `evidence/layout-candidates.html`; the built layouts must match them in structure, not necessarily in every pixel.
- **Q2 Header without a logo.** *Answered:* show the shop name alone as text. The TechNet logo is never shown as the shop's.
- **Q3 House rules.** *Answered:* shown before paying; **no tick box** and no change to the payment flow.
- **Q4 Text limits.** *Answered:* as in Constraints (shop name 32, tagline 60, welcome 160, contact 80, house rules 600, opening hours 60, promo 120, per language).
- **Q5 Budgets.** *Answered:* raise the `page.html` budget in `router/tests/test_footprint.py` from 28 to 40 KB (the program stays at 1200 KB; it holds 1027 KB today and the page is part of it); keep the setup script's 150 KB untouched by doing the pack work in the Rust program.
- **Q6 Filipino wording.** *Answered:* I draft it; the owner reads it before the release is raised. Recorded as a gate in `validation.md`: the release stays at 0% until the owner has signed the Filipino text off.
- **Q7 Pack upload in the console.** *Answered:* yes. The shop owner may upload a pack the owner sends them (it is signed), and the owner may use `piso-setup brand-pack` over SSH.
