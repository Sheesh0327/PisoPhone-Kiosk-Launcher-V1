# FINAL DESIGN SPEC, Track PRO: "Navy Band, Gold Peso", behaviour-hardened

Covers the PisoWiFi portal, coin box dashboard, router console and website setup page.

## 0. Decision and grafts

**Winner: Direction 1 as the visual skeleton.** It is the only option with a signature that survives cropping the logo, and the owner's hard requirements are "brand recognizable immediately" and "not AI-looking". Direction 1 loses on behaviour and accessibility, so those come from the others:

- **D2:**
  - short token names
  - `<details>` voucher entry that works without JS
  - `aria-disabled` plus a visible reason
  - the clock is never `aria-live`
  - 8-second "still connecting" help
  - 1/2/4/8 s reconnect backoff
  - per-coin "Coin received" feedback
  - console "banner only when something is wrong"
  - deltas in words
  - a text summary under the chart
  - the `/api/sales` contracts
  - sales paging
- **D3:**
  - the familiar words (Insert Coin, Pause, Resume, Voucher, Rates, Add time)
  - the internet-down auto-pause banner
  - the voucher throttle policy
  - the pause rule table
  - the "Almost out" nudge
  - the 58 mm slip
  - the implementation order
- **Judges' fixes:**
  - The Insert Coin button is `#go.coin`. `#ion` is the hidden online card, so never assign it to a button.
  - Keep "Endurance" and "You are online".
  - Gold is not overloaded.
  - Vouchers must be reprintable.

## 1. Principles

1. **Signature on every surface:** a navy brand band, then a 3px signal line, then the answer. The gold ₱ chip appears on hero amounts only.
2. **Answer before controls.** The portal leads with time left and state, the box with cash in the box, the console with sales today.
3. **One colour, one meaning.**
   - Blue is the action.
   - Teal is live or connected.
   - Gold is money emphasis or attention.
   - Red is a problem.
   - **Paused** is shown as a gold-tinted panel with a 1px gold border, never a solid gold fill.
   - At most **one solid gold element per screen**.
4. **The gradient is a mark.** It appears only on the signal line and the logo art. It is never on text, buttons, bars, glows or backgrounds.
5. **Words before colour.** Every state has a text chip. Nothing relies on colour or an icon alone.
6. **Familiar words, short copy, no jargon.** Sentence case only. No uppercase, no italics, no letter-spacing, no emoji.
7. **Cheap by construction.** System fonts, solid fills, tonal elevation. Only dialogs get a shadow. Ambient animation is banned.

## 2. Tokens

Names are short on purpose, to save portal bytes. They are shared by `base.css`, `portal.css`, `site.css` and the console's inline CSS, and Compose mirrors them.

**Dark theme.** The portal is always dark. Owner tools default to the OS setting plus a manual toggle.

| Token | Value | Use |
|---|---|---|
| `--bg` | #050d22 | page |
| `--band` | #0a1b45 | brand band, navy in both themes |
| `--s1` | #0c1a3a | card |
| `--s2` | #132552 | dialog, hover, selected, flash |
| `--tx` | #eef3ff | text, 15.4:1 on s1 |
| `--mu` | #97a6c7 | muted, 7.0:1 on s1 |
| `--ln` | #1f3361 | decorative dividers only |
| `--ln2` | #6f84b3 | input and outline-button borders, 4.6:1 on s1 |
| `--br` | #1d63d6 | filled buttons, white on it 5.5:1 |
| `--lk` | #5b9bff | links, chart bars, active tab, countdown bar, 6.2:1 |
| `--teal` | #16c9a4 | signal line, live dot, connected bar. Ink on it is #052e2a |
| `--gold` | #ffb81c | peso chip, almost-out bar. Ink on it is #0b1530, 10.4:1 |
| `--ok` | #4be3b4 | success text, 10.6:1 |
| `--bad` | #ff8a8a | error text, 7.6:1 |
| tints | `rgba(r,g,b,.14)` | state backgrounds, always with a text label |
| scrim | `rgba(5,13,34,.7)` | modal overlay. The old brown scrim is deleted |

**Light theme (owner tools and website).** The band stays #0a1b45.

| Token | Value |
|---|---|
| `--bg` | #f6f8fc |
| `--s1` | #fff |
| `--s2` | #eef2fa |
| `--tx` | #0b1530 |
| `--mu` | #4a5878 (7.1:1) |
| `--ln` | #d5ddec |
| `--ln2` | #7184ad (3.75:1) |
| `--lk` | #1750b8 |
| `--ok` | #0b7a5a |
| `--bad` | #b42318 |
| `--warn` | #8a5a00 (5.9:1) |

**Type** (stack: `system-ui,-apple-system,"Segoe UI",Roboto,sans-serif`; weights 400/600/700; `tabular-nums` on all figures):

| Role | Size / line / weight |
|---|---|
| display (time left) | 44/48/700 |
| kpi (owner tools) | 28/32/700 |
| title | 20/28/700 |
| headline | 17/24/600 |
| body | 16/24/400 (admin 14/20) |
| label | 14/20/600 |
| caption | 12/16/500 |

The wordmark is "TechNet" at 18/800 in solid white, followed by the product name at 18/500 in `--mu`. It has no gradient.

**Spacing:** 4/8/12/16/24/32/48, with a 16px gutter. **Radii:** 6 (inputs, chips), 10 (buttons, cards), 999 (status pills) only. **Elevation:** tonal (bg, s1, s2). Dialogs and menus alone get `0 8px 24px rgba(0,0,0,.45)`.

**Peso chip** (hero amounts only):
`.pe{display:inline-block;width:1.2em;height:1.2em;border-radius:50%;background:#ffb81c;color:#0b1530;font-weight:800;font-size:.8em;line-height:1.2em;text-align:center;margin-right:.3em}`

- Use it in the coin window `#lpes`, KPI tiles, and sales totals.
- Rates lists and table rows use a plain "₱" glyph (`&#8369;10`, as `test_flow` pins it).

**Old-WebView rules (portal):**
- Set literal `body{background:#050d22;color:#eef3ff}` before any `var()`.
- Use `rgba()` only. No 4 or 8-digit hex.
- No flex `gap` (use margins), no `clamp`, `inset`, `aspect-ratio`, `:is()`.
- Write `padding:14px` first, then `padding:max(14px,env(safe-area-inset-left))` as a second declaration.
- No inline `style=`. Move existing ones to classes.
- Selectors are class-based, one level deep.

## 3. Components and states

1. **Brand band:** 48px (box 56px). A 28–32px logo from `brand.png`, then the wordmark. Hotspot or box name sits right-aligned in caption size, only if the owner set one. The 3px signal line sits below. Its fallback is `background:#1d63d6;background:linear-gradient(90deg,#1d63d6,#16c9a4)`.
2. **Status chip:** pill, 12/600, tint plus 1px border plus a text label.
   - Connected: teal dot + ok tint.
   - Paused: gold tint + gold border.
   - No time: neutral.
   - Almost out: gold tint.
   - Waiting for coin: brand tint.
   - Offline: bad tint.
3. **Status card:** `--s1`, 1px `--ln`, r10, padding 16. It holds the chip, the figure, a caption and a 6px solid bar.
4. **Buttons:**
   - Primary is 52px, `--br` fill, white 16/700, r10. It darkens on press.
   - Secondary is 48px, a 1px `--ln2` outline.
   - Text link is a 48px tap area.
   - Disabled buttons use `aria-disabled="true"`, stay focusable, and always have a visible reason line nearby.
   - A button's sub-line is a **separate caption element under it**, not inside it. This keeps the `.coin` text equal to "Add more time", which a test checks.
5. **Segmented plan control:** two text tabs, **HyperSpeed | Endurance**, 44px, with a 2px `--lk` underline on the selected one. The radios are kept (`input[value=endurance]`). One caption line describes the selected plan.
6. **Rates list:** two columns, 44px rows, 1px `--ln` separators. Tier is on the left ("₱10"), duration on the right ("24 hrs"), tabular. There are no tier cards.
7. **Voucher block:** `<details><summary>Have a voucher?</summary>`. The summary is a 48px tap target. It opens without JS and can be pre-opened with `?v=1`. Inside are a 48px monospace input (20px) and a "Use code" button, plus an inline message line.
8. **Message blocks:** inline tint panels. Errors use `role=alert` and success uses `role=status`. Each has one sentence and at most one action.
9. **Dialog:** `--s2`, with the shadow and scrim above. It names the subject ("Void 12 unused codes in batch B-0042?"). It traps focus, closes on Esc, and returns focus to the trigger. It replaces every `confirm()`.
10. **KPI tile:** 1px `--ln` border, `--s1`, no shadow. The label is 12px `--mu`, the value is 28px, and a caption sits below.
11. **Tables:** 40px rows, sticky header, money right-aligned and tabular, text state chips.
12. **Bar chart:** one colour, `--lk`. The value shows on tap and on the latest bar. A text summary line sits under it ("Best day: Sat, ₱340"). There are no donut charts.
13. **Loading:** `--s2` blocks, no shimmer. **Empty:** left-aligned text plus a button. **Error:** a plain sentence with Retry.

## 4. PisoWiFi portal (`page.html`, `base.css`)

Layout is one column, max-width 420, left-aligned. Only the time figure is centred.

**Header (all views):** the brand band reads "TechNet PisoWiFi". The "SOLUTIONS" subline and aurora are removed.

**`#v-conn` (loading):**
- Chip "Connecting" and the text "Getting your connection ready." There is no spinner.
- After 3 s add "This is taking a little longer."
- After 8 s show "Still connecting. Check you are on the TechNet Wi-Fi." with a Reload button.
- `?w=1` self-reload stays.

**`#v-idle`, no time**, top to bottom:
1. Chip "No time yet".
2. 88px `/mascot.png` (keep `src="/mascot.png"`) beside the title "Buy Wi-Fi time" and one line of copy.
3. Plan tabs and the one-line description.
4. **`#go.coin` "Insert Coin"**, 52px, with a caption under it, e.g. "₱1 = 6 min", that follows the selected plan.
5. Rates list, plain "₱".
6. Voucher `<details>`.
7. Footer.

This puts the button within about 280px of the top, so it stays visible in a short captive-portal window. The plan choice and the rate caption sit directly above and below it.

**`#v-live`, coin window** (the existing ids stay):
- Chip "Waiting for coin".
- `#lsub` first reads "Getting the coin slot ready", then **"Insert coin now"**. This string is pinned.
- `#lpes` shows the total with a gold peso chip: 44px, muted at ₱0, `--ok` once money is in. Below it is "= 12 min".
- A 6px `--lk` bar drains at `width 1s linear`. `#lcd` shows "Insert within 24 s".
- One button at a time: outline **Cancel**, which becomes filled **Done** (`#ldone`) after the first coin.
- Each coin shows a "Coin received" chip and a `role=status` line "+₱1 added".
- The slot is locked to one user. If it is busy, an inline warn block reads "Coin slot is busy. Try again in 30s." with a disabled Retry until 0.
- The suspend rule for repeated Insert Coin presses without paying follows the voucher-style throttle: 3 empty attempts, then a 5-minute lock.

**`#v-final` and the running state:**
- Chip "Connected". When the card is visible, `#ion` stays the hidden-by-default card ("You are online · time left").
- Time is `h:mm:ss` in `#fleft`, set with `textContent` each second and **never aria-live**.
- Caption "Use by Tue 3:40 PM" and a 6px teal bar of time remaining.
- Primary `.coin` reads exactly **"Add more time"** (full width). Below it is an outline **"Pause time"** button with a caption line such as "2 pauses left".
- Under that sits the Voucher `<details>`. The copy line reads "Leaving the Wi-Fi does not stop your clock. Tap Pause first."
- **Almost out:** at under 5 minutes the chip becomes gold "Almost out", the bar turns gold, and the caption reads "Less than 5 min left". The primary button is unchanged. This is the one solid gold element on the screen.
- Keep the string "You are online" (not "You're online"). A CSS-drawn check in a teal ring marks arrival. The mascot is not shown here.

**Paused view:**
- Gold-tint panel with a gold border and the chip "Paused".
- The time is frozen and dimmed to `--mu`.
- The caption depends on who paused: "Paused by you" or "Paused: internet is down" (see pause rules). It then says "Your time is saved. Use by Tue 3:40 PM; unused time after that is lost."
- Primary **"Resume time"**. It is disabled and reads "Resuming…" until the server confirms. "Add more time" stays as the secondary button.

**Internet down:** a banner with a gold left border reads "Internet is down. Your time is paused." The server auto-pauses all sessions after more than 60 s of outage and resumes them when the internet returns.

**Connection lost (`#v-msg`):** a bad-tint bar reads "Connection lost. Reconnecting." It retries at 1, 2, 4 and 8 s and disables the buttons meanwhile. A timeout back to idle says "No coin received."

**Footer:** "TechNet Solutions · Need help? [contact]" in 12px muted.

## 5. Pause and resume (PisoWiFi)

**Protocol.** Extend the existing `state` message. Do not add a parallel message.
- Client sends `{"t":"pause"}` and `{"t":"resume"}`.
- Server sends `{"t":"state","s":"paused",...}` as a new `s` value beside `idle|starting|armed|closing|final|error|busy`.
- Added fields on the existing states:
  - `left`: remaining seconds
  - `by`: use-by epoch
  - `pl`: pauses left
  - `pr`: pause reason, `u|net|away`
  - `pw`: seconds until pause is allowed again

**Server rules** (owner-editable, defaults):

| Rule | Default |
|---|---|
| Pauses per session | 10 (0–20; 0 hides the button) |
| Minimum time left to pause | 2 min |
| Wait before pausing again | 60 s |
| Auto-pause on internet down | more than 60 s |
| Auto-pause when the device is gone | off in phase 1 (owner setting, 3 min) |
| Resume | manual |

**State handling:**
- Pause closes the openNDS rule (deauth) and stores the remaining seconds.
- Resume reauths and sets `ends_at = now + remaining`.
- Build on the existing roll fields `pu`/`pa`/`rp` (pauses used, paused at, remaining at pause).
- Use-by expiry is 24 hours.

**Disabled reasons** appear as the caption: "Pause needs 2 min left", "No pauses left", "You can pause again in 40s".

**Copy consistency.** While device-gone auto-pause is off, keep "Leaving the Wi-Fi does not stop your clock. Tap Pause first." If the owner turns it on, change the line to "…auto-pauses after 3 min."

## 6. Voucher codes

> **Changed in router 1.3.4.** The owner no longer makes batches and nothing is printed: the batch form, the code listing and the A4 / 58 mm slip layouts below
> (the "Generation (router)" batch form, "Printing", and the console's Create batch / Print / CSV parts) are gone. A customer buys one voucher with coins by
> choosing "Get a voucher code" at payment; it is booked as income when the coins are counted (kind `vbuy`), redeeming adds no revenue row, and the console
> lists issued vouchers and cancels unused ones. Code format, derivation, throttling and the redeem messages below still apply.

**Entry (portal):**
- Input attributes: `autocapitalize=characters autocomplete=off spellcheck=false maxlength=11`.
- JS uppercases, strips spaces and dashes, maps O to 0 and I/L to 1, and inserts a dash after 5 characters. Paste and the Enter key submit.
- Client sends `{"t":"redeem","c":"ABCDEFGHJK"}`.
- Server replies `{"t":"rd","ok":1,"min":60}` or `{"t":"rd","e":"bad|used|exp|wait","w":sec}`.
- Messages:
  - Sending: "Checking…" (button disabled)
  - Success: "Added 1 hr. Enjoy!" (the card flashes `--s2` for 150 ms)
  - "Code not accepted. Check the letters and try again."
  - "This code was already used."
  - "This code has expired."
  - "Too many tries. Try again in 0:45."
- Throttle per device: 5 failures, then 1 minute, doubling to a 15-minute cap. Add a delay when the router sees more than 30 failures a minute.

**Generation (router).**
- Codes are 10 characters of Crockford Base32, which is 50 bits.
- **Reprintable without plaintext storage.** Derive each code as `base32(HMAC-SHA256(server_secret, "v1" || batch_salt || index))[..10]`.
  - The 16-byte batch salt comes from `/dev/urandom`.
  - This needs no new crates (`sha2` and `hmac` are already there) and no `OsRng` dependency.
  - Check for collisions at generation.
- Store the batch record (salt, count, minutes, price, plan, shelf life, allow-pause, created) and per-code state.
- The owner can re-expand and reprint codes behind the console login at any time.
- If the owner rejects this, the fallback is "codes shown once at creation", and the UI must say so plainly.
- Redemption is atomic. The sale is booked at redemption with `kind="voucher"` and a `plan` in `revenue.csv`. Voiding is a reversal and nothing is deleted.
- The ledger has no channel column. Do not add one. If a column must be added, change `ledger::append`'s `.nth(5)` hash lookup to `.last()` first.

**Printing.**
- `@media print`, A4 3×8 slips with a mini band, a monospace code, price, time, expiry and the portal address.
- A 58 mm thermal single-slip layout is selectable, because many shops already have 58 mm receipt printers.

## 7. Router console (`console.html` + signed `console-ui.js`)

- **Shell:** the brand band reads "TechNet PisoHub · Console" with Advanced and Sign out on the right, then the signal line. Tabs are an underline row: **Overview, Customers, Sales, Vouchers, Phones**.
  - The old "Money" tab is renamed Sales. Grep `t-money` and "Money" in the tests and update them.
  - Under 720px the same tabs become a fixed 56px bottom bar with text labels.
- **Login:** 360px card, no gradient. A left-aligned 88–96px mascot and a password field with a **Sign in** button. The error reads "Wrong password. 3 tries left."
- **Overview:**
  - A banner appears only when something is wrong ("Internet is down since 2:15 PM").
  - Tiles: Sales today (delta in words: "Up ₱40 from yesterday"), Customers online, and Internet (an Up/Down chip). A fourth tile, Coin box, is a chip, and when offline it shows last-known values with "as of 9:42 AM".
  - Then "Today by hour" bars, the last 5 sales, and a collapsed "System" `<details>` (records, uptime, memory, internet chart).
  - Empty state: "No sales yet today."
- **Customers:** columns are Device, State chip (Connected / Paused / No time), Time left, "Pauses 2/10", and Actions. The actions are Add time, Pause/Resume and Disconnect, and each opens a dialog that names the device. Empty state: "Nobody is connected."
- **Sales:**
  - Range tabs: Today, 7 days, 30 days, Custom.
  - Tiles: Total ₱, Sales, Average sale, and Change vs previous period.
  - One bar chart (hours for Today, days otherwise) with the text summary line.
  - "By source" (Coin / Voucher, and Phone only when the ledger has such records) and "By plan" tables, each with ₱, share %, and a thin proportion bar.
  - Transactions table: Time, Source chip, Device, Plan, Min, ₱. It shows 50 rows, then "Show more", plus **Export CSV**.
  - Loading shows "–" in the tiles. Error: "Could not load sales." with Retry.
  - New endpoints: `GET /api/sales?from&to&g=hour|day` (exposes `plan` and `kind`; the hash column stays hidden) and `/api/sales.csv`. `/api/revenue` stays unchanged.
- **Vouchers:**
  - Form: Count (1–500), Minutes (chips for 30 min, 1 hr, 3 hr, 1 day), Price ₱, Plan, Shelf life (30 days), Allow pause toggle. Button **Create batch**.
  - After creation: Print, Copy and CSV buttons.
  - Batch table: Batch, Created, Unused, Used, Void. Rows expand to the codes with chips Unused / Active / Used / Expired / Void.
  - Void opens a dialog that names the batch or code.
  - Empty state: "No voucher batches yet. Create one to sell time without the coin slot."
  - `POST /api/vouchers`, `POST /api/vouchers/void` and `GET /api/vouchers` all need `X-Piso: 1`.
- **Phones:** read-only table with Phone, State chip, Time left, ₱ today.
- **Signed UI:** raise `PISO_UI_VERSION`, update the fixtures in `test_console_loader.py`, run `scripts/sign_console_ui.py`, and mirror to `website/console/ui.js`. `console.html` stays under 20,000 B. The CSP stays as it is, and pinned JSON shapes are untouched.

## 8. ESP32 coin box dashboard

- **Shell:** a sticky 56px brand band reads "TechNet PisoHub". On the right are a chip (Online / Offline) with the Wi-Fi signal as a detail line, and Sign out. The signal line is below. Underline tabs follow: **Overview, Sales, Settings, Tools, Vendor**. On phones the same tab buttons become a fixed bottom bar through CSS only.
- **Do not fold Vendor into Tools.** Keep `switchTab('tab-superadmin')` and the tests that pin it. Keep `{TAGS}`, `PISO_CFG` and every `switchTab('tab-…')` handler.
- **Overview:**
  - Three flat tiles: "In the coin box" (chip ₱, "since last collection"), "Since power-on" (₱) and "Phones in use" ("3 of 8").
  - Below: the per-slot table, then the Phones table (Phone, State chip, Time left, Add time / Deduct time), then "Add or remove time", then Player accounts collapsed in `<details>`.
  - Remove the 85-word developer paragraph. "How to make cards" becomes a link in Tools.
  - Empty state: "No phones paired yet. Open Tools, then Phone slots."
  - Two failed polls show the bad banner "Box not responding", or "Box lost the router. Last data 10:42."
  - `#session_lost` and the orphan banners are unchanged.
- **Sales tab:** counters only, because the box has no trusted clock.
  - A per-slot totals table, coin denomination counts, and the "Collections" log built on the existing 16-record ring in `RevenueLedger.h`. It shows the stored amount and reason, not wall-clock dates.
  - "Counted cash" input that shows the difference from the expected amount.
  - A line reads "Daily reports: open the router console."
  - **"Mark collected" is vendor-only today.** Before opening it to shop owners, confirm the trust model with the owner and add a new reason code. Until then, show it only to vendor sessions.
  - Lazy-load `sales.js` (about 6 KB raw, about 2.3 KB gzipped). Add it to `ASSETS` in `scripts/embed_web.py`, change `esp32_firmware/host_tests/web_assets_test.cpp` from `WEB_ASSET_COUNT == 9` to `10`, regenerate and commit `WebAssets.h`.
- **Settings:** Wi-Fi, admin password, coin rate. If the default password is still set, show a gold chip "Change the default password". Hardware pins go under a collapsed "Advanced".
- **Cleanup:** drop `--serif`, h2 icons, gradients, shadows, the brown scrim and the paper-theme leftovers. `portal.css` target is 15 KB raw or less (it is 19.3 KB now). Flash is not the constraint, since OTA slots are 1.75 MB, so this is a speed goal.

## 9. Website setup page (`website/`)

- Navy band with the logo and "TechNet PisoPhone Setup", then the signal line. A "Coin box found" chip sits in the band (or "Not found" when it isn't).
- The body is 560px and follows the OS light or dark theme, with a toggle.
- Hero: a 72–96px mascot beside the title "Set up your PisoPhone". No hero banner image.
- Order: found chip, then a 3-row list (Coin box, Slot, Address), then Set up / Remove tabs. Then the Wi-Fi password with one hint sentence and the long text in a "Why?" `<details>`. Then the QR / USB underline tabs, at most 4 numbered steps (28px circles), and one 52px primary button per step.
- The restricted view shows the 96px mascot and 3 steps. Errors are inline with `role=alert`.
- Footer: "TechNet Solutions, Philippines".

## 10. Copy guidelines

- English, sentence case, at most one sentence per message, and say what to do next.
- Keep the familiar nouns: Insert Coin, Pause, Resume, Voucher, Rates, Add more time, Sales, Customers.
- All text is English. No Filipino (Tagalog) text appears in the product.
- Amounts are "₱10" with a plain peso sign. Durations are "24 hrs", "6 min". Money is never red or green on its own.
- Error tone is neutral and non-accusing. Voucher errors may name "used" and "expired" because the codes are too long to guess.

## 11. Motion

- 150 ms ease-out for state changes (chip swap, card flash, button press).
- The coin countdown and time bars use `width 1s linear`.
- No ambient animation, no skeleton shimmer.
- `prefers-reduced-motion` sets all durations to 0.

## 12. Accessibility

- **Contrast:** text is at least 7:1 on cards (tx 15.4, mu 7.0, lk 6.2, ok 10.6, bad 7.6). White on `--br` is 5.5:1. Ink on gold is 10.4:1. Control borders are 3:1 or better (`--ln2` is 4.6:1 dark and 3.75:1 light). Light-theme text is above 5:1.
- **Targets:** 48px minimum, primary 52px, 8px apart.
- **Semantics:**
  - The ticking clock is never `aria-live`.
  - Coin and voucher outcomes use `role=status`.
  - Errors use `role=alert`.
  - Blocked controls use `aria-disabled` plus a visible reason.
  - Dialogs trap focus and return it.
  - The voucher `<details>` works without JS.
  - Focus rings are 2px `--lk`.
- **Never colour alone:** every state has a text chip.

## 13. Performance budgets

- **Portal.** `page.html` + `base.css` render to 15,977 B today against the 16,000 limit that `test_flow.py` pins.
  - Savings from CSS: about 1.3 KB by removing aurora, button glow, card gradient, gradient text and the inline styles.
  - Features add about 1.1 KB for pause plus voucher.
  - That nets to about 15.8 KB, so extra levers are mandatory:
    - minify whitespace in the CSS and JS at build or serve time (about 0.7–1 KB)
    - generate the rate rows from compact data in JS
    - trim the audio helpers `say()`, `tone()` and `ding()` (about 1.1 KB) only if the owner agrees to drop sounds
  - **Target ≤ 15,000 B rendered.** Keep the test limit at 16,000.
  - Gate every step on the `test_flow` size check. No extra requests, no webfonts, no images beyond the mascot and logo.
- **Console:** `console.html` under 20,000 B, with logic in the signed `console-ui.js`.
- **Box:** `portal.css` at most 15 KB raw, `sales.js` at most 9 KB raw and 3 KB gzipped.

## 14. Must NOT change (pinned by tests)

- **Portal ids and views:** `#v-conn`, `#v-idle`, `#v-live`, `#v-final`, `#v-msg`, `#go` (class `coin`), `#ion` (the hidden online card with inline `display:none`), `#lcd`, `#lpes`, `#lsub`, `#ldone`, `#fleft`, and `input[value=endurance]`.
- **Portal strings:** "You are online", "Add more time" (exact text in `.coin` when online), "time left", "Insert coin now", "HyperSpeed", "Endurance", `&#8369;10`, "24 hrs", `src="/mascot.png"`.
- **Behaviour:** `?w=1` self-reload. `test_flow.py` size limit stays at 16,000.
- **Box:** `{TAGS}`, `PISO_CFG`, `switchTab('tab-…')` handlers including `tab-superadmin`, `#session_lost`, the orphan banner ids, and the ids that the session and orphan UI tests use. After adding `sales.js`, update `WEB_ASSET_COUNT` to 10 and regenerate `WebAssets.h`.
- **Console:** the pinned JSON shapes (`/api/revenue` `today`, `total`, `week`). All POSTs send `X-Piso: 1`. CSP unchanged. Re-sign the UI and mirror to `website/console/ui.js`.
- **Website:** `#mainView`, `#restrictedView`, `#qrPanel`, `#usbPanel`, `#wifiPass`, `#qrBox[data-payload]`, "Wi-Fi password", "Finish over USB", "USB cable".
- **Unverified:** I did not check the full list of pinned selectors in `test_browser.py` and the box tests. Run those suites after each step.

## 15. Implementation order

1. Tokens and brand band in `base.css`, `portal.css`, `site.css` and the console CSS.
2. Portal restyle at ≤ 15,000 B, with the existing tests green.
3. Router: pause/resume `state` extension and `redeem`, then the portal UI for both.
4. `/api/sales` and the voucher endpoints, then the console Overview, Customers, Sales and Vouchers tabs.
5. Box dashboard cleanup, then `sales.js`.
6. Website reskin.

## 16. Open items for the owner

1. Voucher printing: A4 3×8, 58 mm, or both (the spec ships both).
2. Pause limits: 10 pauses and a 24-hour validity are suggested defaults.
3. Should device-gone auto-pause default to off (the spec's choice) or on?
4. Should shop owners be able to "Mark collected" on the box, or stay vendor-only?
5. Should owner tools default to dark or to the OS setting (the spec uses the OS setting)?
6. Approval for the deterministic HMAC-derived voucher codes, which make reprinting possible.

Files checked for this spec:
- `/home/user/PisoPhone-Kiosk-Launcher-V1/tools/pisoportal/src/page.html`
- `/home/user/PisoPhone-Kiosk-Launcher-V1/tools/pisoportal/src/console.html`
- `/home/user/PisoPhone-Kiosk-Launcher-V1/esp32_firmware/host_tests/web_assets_test.cpp`
