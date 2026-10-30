# Feature 053: the admin console as a professional product: grouped sidebar, sleek modern look

Status: **approved 2026-10-30 (owner answered the five decisions; no group started)**   Branch: `feat/053-console-layout`   Area: router (the console page is built into the portal program)
Backlog rows: owner request after seeing a competitor's console; no findings row   Release: router (raises `setup/RELEASE`; goes through `beta`, `CLAUDE.md` section 5)

## Intent
The shop owner opens the console many times a day, mostly on a phone. Today it is seven tabs in one row at the top: it already ran out of room on a
phone (the 2026-10-30 clipping fix) and it has no place to grow. The owner wants a layout like a professional product: a left sidebar with the
screens grouped by what the owner is doing, a calm modern look, and the same speed. This is a layout and look change only: what each screen shows
and does stays the same. Serves the goal "an owner who is not a programmer can run the shop".

## Invariants touched
- Money and time: **not touched.** No payment, credit, expiry or ledger code. The same numbers are shown from the same calls; the money panel
  (feature 036) and the update line (044) keep their text and meaning.
- Only owner-signed software: **kept.** The console's own script stays the built-in `console-ui.js` or a newer copy the owner signed
  (`scripts/sign_console_ui.py`, signed text and api number unchanged). No new place loads code; no new outside request (no web fonts, no CDN,
  icons are inline SVG).

## Constraints
- Only the files of the page: `tools/pisoportal/src/console.html`, `console-ui.js` (version 11 to 12, in group 2 only: tile icons, the customer count, the empty state, and day labels thinned to the chart's width; the icons carry their own width and height so the new script is safe on a page from 1.5.32), and the tests that name the old tab bar. The menu, the title and the current-screen mark live in the page's own small inline script, so any signed script, old or new, drives the layout.
  The generated router programs and setup files are rebuilt by CI on `beta`, never by hand.
- **Every element id and every `data-t` value stays.** The page and the script are released separately (the website may hold a signed script
  older or newer than the page): the new page must work with the signed v10 script that is on the website today, and the new script must work
  with an older page. Both are tested.
- Sign-in, session cookie, same-origin and `X-Piso` rules are unchanged; the login screen only gets the new look.
- Room: the page-size guard (`test_console.py`, now 24,000 bytes) may be raised only as far as the layout needs, the portal program stays under
  its 1,200 KB budget for mipsel (1,091 KB today; measure before and after), and `console-ui.js` stays small.
- No new framework, no build step: plain HTML, CSS and the same small script.
- Works on a phone first (390 px wide), then tablet (768) and computer (1280). Light and dark both (today the page follows the device and has a toggle).

## What changes (proposal; the owner decides in "Owner decisions")
- **Computer and tablet (900 px and wider):** a fixed left sidebar with the shop product name at the top and the screens in groups:
  **Overview** (Overview, Sales) · **Management** (Customers, Rental phones or Coin box, Vouchers) · **System** (Branding, Settings, which still holds Prices).
  A group appears only if it has screens. The current screen is marked. The top bar has the name of the current screen and the light/dark button. **Advanced** (the router's own LuCI page) and **Sign out** sit at the bottom of the sidebar
  (they were in the top bar; on a phone the top bar had no room for them next to the menu button).
- **Phone** (today a scrolling bottom bar of seven small labels; the Customers table also cuts off its Disconnect button, see `evidence/group0-findings.md`): the top bar has a menu button that opens the same sidebar as a panel from the left; it closes when a screen is chosen, on a tap outside, or on Esc.
  No row of tabs, so nothing is clipped whatever the font.
- **Look:** one set of design tokens (spacing 4/8/12/16/24, one radius scale, one type scale), calmer cards with a title row, small inline icons beside
  the screen names and on the overview tiles, tables with a sticky header and zebra-free hairlines, clear empty states ("No customers right now"),
  buttons in one size set (40 px tall, 32 px for table actions), visible focus, 44 px touch targets on phone.
- **Not changed:** what each screen contains, the order of its content, button labels that tests or the manual quote, the Branding screen's own preview.

## What a visitor without the condition sees
- Not signed in: only the sign-in card, as today. No sidebar, no screen names, nothing of the shop.
- A shop with PisoWiFi only: the phone screen is called "Coin box" (as today, `@PHTAB@`); the sidebar uses the same word.
- An older router that does not know a call (the "run piso-setup update" message): the screen still opens and shows that message inside the new layout.
- The website's older signed script (v10): the sidebar works and every screen opens; it just lacks the v11 extras (Branding wiring is in v11).

## Success criteria
1. At 1280 px the console has a left sidebar with the groups above; every existing screen opens with one click; the current screen is marked (`aria-current`).
2. At 390 px there is no sideways scrolling on any of the seven screens (sign-in included), the menu button opens and closes the sidebar panel (choice, outside tap, Esc), and every button is at least 40 px tall.
3. Keyboard: Tab reaches the menu, the sidebar items and the page in a sensible order; focus is visible; Esc closes the panel and puts focus back on the menu button.
4. Light and dark: text and button contrast is at least 4.5 : 1 in both, measured by a script over the real page, not by eye.
5. All earlier console tests pass with only selector edits for the old tab bar (`test_console`, `test_console_brand`, `test_console_frame`, `test_console_prices`: listed in `evidence/group0-findings.md`); `test_money_panel` and `test_versions_panel` (they read `#mny` and `#upd`) and `scripts/tests/test_console_ui.py` and `test_console_loader.py` pass unchanged; no behaviour assertion is weakened.
6. Compatibility: the new page runs with the signed v10 script that is on the website today and with the built-in v12 (a loader test serves each); every screen opens and the sidebar marks the current one. The v12 script must also run on the page from release 1.5.32 (no sidebar): it starts and every screen opens (tile icons are 16 px by their own attributes).
7. Sign-in and session behaviour: `test_console.py`'s authentication checks pass unchanged (wrong password, no cookie, wrong origin, missing `X-Piso`).
8. No outside request: the page loads with every host except the router itself blocked; fonts are the system fonts.
9. Size: the page-size guard and the mipsel program budget in the plan hold, with the numbers recorded in `validation.md`.
10. Evidence: before and after screenshots of the seven screens at 1280, 768 and 390 px, light and dark, in `evidence/` and shown to the owner.
11. Real router and real phone: the owner opens the console on the Qihoo 360 T6M from a phone and a computer (new hardware check **RS64**); not run here.

## Out of scope
- New screens or new functions (Network, speed limits, Gaming Priority, SQM, remote access, GCash or sub-vendo screens of the competitor): each its own spec.
- Splitting Prices out of Settings (needs a new screen and a script change; later).
- Changing the guest Wi-Fi page, the box's own admin page, the phone app or the website.
- A new colour theme or the shop's branding palette on the console (the console stays the owner's tool, not the customer's page).
- Raising the router release above 0% (the owner's step, `CLAUDE.md` section 5).

## Owner decisions
Answered by the owner on 2026-10-30; the proposal above stands unchanged.
1. **Groups and names:** Overview (Overview, Sales), Management (Customers, Rental phones / Coin box, Vouchers), System (Branding, Settings). No Network group until a feature gives it content.
2. **Phone navigation:** a menu button that opens a side panel.
3. **Look:** keep the calm black-and-white look; better spacing, cards and icons, no new colour.
4. **Prices:** stay under Settings for now.
5. **Light or dark:** follows the device, with the toggle, as today.
