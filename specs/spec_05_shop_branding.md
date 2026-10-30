# Spec 05: shop branding (the shop owner's name on everything a customer sees)

Status: **decisions D1 to D11 answered by the owner on 2026-10-30 (asked one at a time, none assumed); feature 049 specified, waiting for approval**   Written: 2026-10-30 against `main` at `2489b58` (router 1.5.29 live)
Related: `specs/features/013-shop-branding/` (phone palette, shop name, title: built), `specs/features/ARCHIVE.md` (023, 024)

## 1. Goal
The owner sells PisoPhone and PisoWiFi to individual shop owners, who want to feel they own the hardware: their name, colours, logo and
words on everything a customer sees. Today only the rental-phone screens have any branding (feature 013). The guest Wi-Fi page is fixed
"TechNet PisoWifi" with TechNet's logo and mascot; the printed items carry no shop identity except a name typed by hand.
Both project rules hold: branding is data, never code, and nothing here touches how a payment is recorded or credited.

## 2. Decisions (owner, 2026-10-30)
| # | Question | Answer |
|---|---|---|
| D1 | Which screens | **Guest Wi-Fi page, rental phone screens and printed items** (Wi-Fi poster, QR time cards, voucher file) |
| D2 | The "by TechNet Solutions" credit | **Always locked and small, everywhere.** No setting, pack or license removes it. (Feature 013's rule stays.) |
| D3 | Who edits | **Both:** the shop owner changes name, colours and texts; **images only through the owner** |
| D4 | Logo storage | **Router only now** (guest page); phone logo is a later feature. No picture on the coin box yet (013's rule stays) |
| D5 | How "images only through the owner" is enforced | **A signed brand pack** made on the owner's computer with the owner key; the router accepts images only from a validly signed pack. Not bound to one router |
| D6 | Depth of the guest page | **Texts, colours and a few built-in layouts.** The payment controls can never be removed, moved out of reach or restyled unreadable |
| D7 | Colours | **Curated palettes plus one custom accent colour**; the program keeps all text readable (contrast check) |
| D8 | Extra texts | **Contact line, house rules, opening hours, promo banner** (guest page and prints now; phones later) |
| D9 | Languages | **English and Filipino, the customer can switch**; the shop owner writes each text once per language |
| D10 | Where the shop owner edits | **One Branding screen in the router console** for everything. It starts guest-page only; the phone fields join it in the phone feature |
| D11 | Defaults and preview | A router with no branding looks **exactly as today**. The console has a **live preview and a Publish button**, and a Restore default look button |

## 3. Features (each its own branch, spec and release; order chosen by the owner)
| Feature | What | Release |
|---|---|---|
| **049** guest page branding | `specs/features/049-guest-page-branding/`: the Branding screen, the page, the signed brand pack, EN/FIL, layouts, `/brand.json` | router |
| 050 printed items | the Wi-Fi poster, QR cards and voucher file read the shop's branding from the router (`/brand.json`) instead of typed values | none (owner tools) |
| 051 phones | the existing phone fields (palette, shop name, title) join the Branding screen (router to box), contact and promo texts and a box-held logo on phones (needs a decision then on the logo) | firmware + app |

Specs 050 and 051 are written only when the owner starts them.
