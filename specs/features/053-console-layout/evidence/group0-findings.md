# Group 0: the console as it is today (2026-10-30, router program built from `main` at `4f9893b`)

`before/` holds 48 pictures: the sign-in card and the seven screens, at 1280, 768 and 390 px, light and dark, named `<width>-<theme>-<screen>.jpg`
(screens: `over` Overview, `cli` Customers, `ph` Rental phones, `sales`, `vch` Vouchers, `brand` Branding, `set` Settings). They were taken by a
script against the real portal program with sample data: 4 customers (3 online, 1 waiting), 7 days of sales, 4 vouchers, a coin box that counts
more than the record (so the money panel shows its warning). The Internet line chart is empty because the test router has no traffic.
The script is not kept in the repository; group 6 repeats it for `after/`.

## How it is built today (facts the plan rests on)
- One top bar: logo, name, "Console", then the seven-tab bar `#tabs`, the light/dark button `#th`, an **Advanced** link (`#adv`, the router's own LuCI page) and **Sign out** (`#out`).
- Phone (under 900 px): the tab bar is already fixed to the bottom of the screen as a scrolling row (the 2026-10-30 clipping fix); labels wrap to two lines ("Rental phones").
- A screen is a `<section id="t-<name>">`; `console-ui.js` shows one at a time from the `data-t` of the button clicked (`#tabs button`, line 23) and keeps the list in `TABS` (line 4).
- The page is 21.8 KB of which most is one `<style>` block and the Branding screen.

## What looks wrong today (to be fixed by the layout; nothing else changes)
1. Phone, Customers: the table is wider than the card; the **Disconnect** button is cut off at the right edge and has to be scrolled to (`390-*-cli.jpg`).
2. Phone: seven labels in a bottom row at 11 px; two-line labels; small touch targets (the buttons are 30 px tall on computers).
3. The Overview's money panel and update line are two plain boxes with the same weight as the data; the warning (gold edge) is easy to miss next to the "not checked yet" line.
4. Tiles: six cards of equal weight, no icons, "OK" in green is the only cue; their text wraps to three lines on tablet width.
5. No page title or "where am I" beyond the highlighted tab; the footer is on its own row.

## Selectors the old tests use for the tab bar (these are the only edits group 5 may make to older tests)
| file | line | selector |
|---|---|---|
| `tests/test_console.py` | 294 | the page contains `data-t="set"` (text check) |
| `tests/test_console_brand.py` | 228, 310, 311 | `#tabs button[data-t="brand"]`, `#tabs button[data-t="set"]` |
| `tests/test_console_brand.py` | 308 | `#tabs button` clipped-label check at 390 px (made for the old bar; becomes a sidebar check) |
| `tests/test_console_frame.py` | 64 to 67, 86 | `#tabs` visible; `#tabs button[data-t=ph]` text and click; `#tabs button[data-t=over]` |
| `tests/test_console_prices.py` | 33, 67 | `#tabs button[data-t="set"]` |

`test_money_panel.py` and `test_versions_panel.py` do not use the tab bar: they read `#mny` and `#upd` on the Overview, so those ids must stay. The
plan's keep-the-ids rule covers them. If the new sidebar keeps `id="tabs"` and `data-t` on its items, the click selectors above keep working unchanged
on a computer-width screen; on a phone the menu panel must be open first (the phone tests open it).
