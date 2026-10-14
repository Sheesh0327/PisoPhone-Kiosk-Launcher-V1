# FINAL SPEC: PisoPhone kids screens (Track KIDS)

**Winner:** Direction 1, "Halo, Coin, Edge". Two of three judges ranked it first, and it is the only direction that makes the brand recognisable in a screenshot. Judge 3 ranked Direction 2 first on engineering, so Direction 2's discipline is grafted in. That means static art, one ambient effect, the `redeem(code)` seam, a single Banner component and the unit-tested formatter. Direction 3's accessibility fixes are grafted in too.

## 1. Principles
1. **Mascot first.** The mascot sits in a fixed 168dp stage at the top of the panel, in the same place on every kids screen. Nothing is drawn over it.
2. **One gradient, one gold.**
   - The blue-to-teal gradient appears only on the Halo disc and the logo asset (and the two backdrop-art exceptions in section 5, which is why gradient is not strictly "only" two places).
   - Gold means money, minutes, rewards and the LOW time level.
   - Buttons are solid. Text is never gradient.
3. **Answers, not jargon.**
   - Say "₱5 = 30 min", never "5.00 PHP / unit".
   - IP and hardware text move behind the admin PIN.
   - Labels are sentence case and short.
4. **Rest by default, burst on events.** There are 0 fps between events, exactly one ambient effect, and everything is thermal-gated.
5. **One opaque panel.** It replaces the three stacked translucent layers. The art shows around it, so the GPU stops blending.
6. **Colour is never the only signal.** Every state has a word or an icon as well.

## 2. Tokens
These live in `ui/theme/Tokens.kt` as `object TechNet`. It replaces `Color.kt` ("Obsidian & Emerald"), and the 57 `Color(0x…)` literals become references. CSS vars use the same hex values.

**Colour**

| Token | Hex | Use / contrast |
|---|---|---|
| bg | `#050D22` | Screen |
| surface | `#0C1A3A` | Panel |
| surfaceHi | `#132552` | Cards, banners, sheets |
| brand | `#1D63D6` | Primary fill. White on it 5.5:1 |
| brandEdge | `#0F3F94` | 4dp edge |
| brandFg | `#5B9BFF` | Links, progress, icons (6.2:1 on surface) |
| haloFrom / haloTo | `#2F7BFF` / `#16C9A4` | Halo, 135° |
| teal / tealEdge / onTeal | `#16C9A4` / `#0B8F75` / `#052E2A` | Success, Done. onTeal on teal 6.9:1 |
| gold / goldEdge / onGold | `#FFB81C` / `#B07A00` / `#0B1530` | onGold on gold 10.4:1, gold on surface 9.9:1 |
| text | `#EEF3FF` | |
| textMuted | `#97A6C7` | 7:1 on surface |
| line | `#1F3361` | Decoration only. Never the sole boundary of an interactive control |
| lineStrong | `#6B82B8` | Outlines, inputs, outline buttons. 4.5:1 (changed from `#4A5F8F`, which measured 2.72:1) |
| ok | `#4BE3B4` | |
| warn | `#FFB81C` | Same as gold, deliberately |
| danger | `#FF5A5F` | Fills, dots, borders, edges |
| dangerText | `#FF8A8A` | Danger text, 7.6:1 |
| dangerEdge | `#B3262B` | |

- Use pure `#FFFFFF` on brand fills.
- Scrim is black at 60%, used on sheets only.
- `TimeLevel` colours: OK = text, LOW = gold, CRITICAL = dangerText for text and danger for borders and fills.

**Type**
- Font: bundle Nunito ExtraBold/Bold subset (digits, ₱, Latin, about 25 KB), with a system SansSerif W800 fallback.
- Use `tnum` on all numbers.
- Numbers must never use the fallback if the font fails to load. A missing font must not crash.

| Token | Size / line | Weight | Use |
|---|---|---|---|
| displayXL | 56/60 | W800 | Live ₱ total while paying |
| display | 40/44 | W800 | Rate card, expanded timer |
| title | 24/30 | W800 | |
| headline | 18/24 | W700 | |
| body | 16/24 | W500 | |
| label | 16/20 | W800 | Buttons |
| caption | 12/16 | W600 | Minimum anywhere, admin included |
| pillTimer | 14/16 | W700 | |
| ringSeconds | 20/24 | W800 | |

**Spacing and shape**
- Spacing: 4, 8, 12, 16, 24, 32, 48dp. Gutter and panel padding are 16.
- Max content column is 420dp.
- Radii: 8 (chips, inner boxes), 16 (cards), 20 (buttons), 28 (panel and sheets), full (pills).
- Elevation is tonal only: bg, then surface, then surfaceHi. No blur shadows.
- Edge = solid shape offset 4dp, drawn with `drawBehind`.

**Backdrop tints** (replace the 8 neon themes)
- Dagat (blue), Tropiko (teal) and Ginto (gold-tinted navy).
- They change the backdrop only. Text, button and panel colours never change.
- Persist `themeIndex`, and map old stored values 0–7 to `index % 3`.
- Delete the `% 3` cycling in `KioskOverlayCoordinator` and the duplicated theme pairs in `BlockScreen.kt` and `FloatingPill.kt`.

## 3. Components

**`PisoButton`** (new file)
- Variants: Primary (brand), Success (teal), Gold (gold-outline), Tonal (surfaceHi), Outline (lineStrong), Danger.
- Sizes: Large 64dp, Medium 56dp, Small 48dp.
- The face moves down 3dp and the edge shrinks from 4dp to 1dp over 80ms on press.
- Disabled state: surfaceHi fill, muted text, no edge, plus visible reason text elsewhere.
- Minimum touch target is 48dp. Every button has a text label.

**`MascotView(state)`**
- Plain Compose. Transforms go through `graphicsLayer {}` lambdas only. No Rive or Lottie.
- States: Idle, Listening, Cheer, Worried, Sleepy, ThankYou.
  - Idle: wave or blink every 3–6 s (the single ambient effect).
  - Listening: a held 6° tilt.
  - Cheer: hop 400ms (-16dp, overshoot) plus squash, with a 600ms sparkle burst of at most 16 particles.
  - Worried: one shake per second at 60 s or less, plus a sweat-drop vector.
  - Sleepy: desaturated "z", static. Used for offline and paused.
  - ThankYou: wave for 3 s.
- Blink needs eye coordinates from the owner. Until then Idle is a wave or tilt only (transforms fallback). Request the source art regardless.
- The 103 KB hero leaves the `LockScreenArt` canvas and is decoded once, only here. It is sized at 144dp for the hero, 40dp for banners and 24–32dp for the pill and expanded header.

**`Halo`**
- A 168dp disc with a 135° gradient built once via `drawWithCache`. The mascot overlaps its top edge by 8dp.
- In the paying state it carries the countdown ring: an arc on a cached `Path`, redrawn once per second, with the seconds in a 20sp badge.
- The ring turns warn under 5 s.
- Do not use `Modifier.blur`.

**`Panel`**: surface, radius 28, 1dp line border, 16dp padding.

**`LockUp` header** (40dp)
- The logo asset (28dp), "TechNet" in white W800 and "PisoPhone" in teal.
- A "Phone 3" chip (surfaceHi, radius 8) on the right.
- Admin shows an "Owner mode" chip instead.

**`RateCard`** (surfaceHi, radius 16)
- Shows up to 3 rate rows from config, each with a gold coin glyph and "₱5 = 30 min" in display gold.
- Caption: "More coins, more time."
- Rows are 56dp. Format ₱ to minutes through one pure `formatRate()` function, with a unit test.
- Under 60 min show "30 min". At 60 min or more show "1 hr 30 min".

**`Banner`** (new file, one component)
- 56dp, surfaceHi, radius 16, with a 4dp left bar in the state colour, a mascot or icon, 14sp text and an optional inline 48dp action.
- It absorbs the battery, slot-expired and arena banners as variants, so their existing triggers stay.

**`Pill`**
- 36dp visible, 48dp hit area, surface fill, 1dp border.
- Contents: a 24dp mascot face, a level icon and MM:SS (H:MM:SS over one hour).
- Level icons: a dot for OK, an hourglass for LOW, an alert for CRITICAL. This is the non-colour cue.
- The timer is its own `TimerText` composable.

**`StatusFooter`**
- A dot plus text, outside the panel.
- Connected: ok dot, "Coin box connected".
- Offline: warn dot, "Coin box offline. Ask the shop staff."
- Bottom-left: a sound toggle and the backdrop-tint palette button (48dp each, alpha 0.8, labelled). Bottom-right: the admin icon (48dp). Admin keeps its current gesture.

**`VoucherSheet`**: a bottom sheet (see section 7).

## 4. Screens (360×800 portrait baseline)

**Height budget:** status 24, panel 16 pad + header 40 + stage 168 + title block 54 + rate card about 88 + caption 16 + primary 64 + secondary row 48 + 16 pad, then footer 48. This is about 720dp including insets, so about 80dp of slack. Under 640dp height, or in landscape, the panel content scrolls and the stage shrinks to 120dp. Landscape is two columns: stage 40% on the left, panel on the right.

**A. Locked (idle)**, top to bottom:
1. Fake status row, 24dp, 12sp.
2. Panel with header (LockUp and Phone chip).
3. Stage: Halo and mascot, Idle.
4. Title "Insert a coin!" (24sp, centred), with body "Insert a coin to unlock this phone."
5. RateCard, then one caption line with a lock icon: "Phone locks when time ends. Sign out of your accounts."
6. Primary button 64dp, "Ready, insert coin", with a 12sp subline "Tap first, then insert your coin".
7. Row of two 48dp outline buttons: "Scan card" (calls `AccountController.startScan`) and "Enter code".
8. StatusFooter.

**B. Inserting coins (`isWaiting`)**
- The mascot is Listening. The Halo carries the countdown ring.
- Title "Insert coin now". After the first coin: "Coin in!".
- The RateCard becomes a live total: "₱0" in displayXL gold and "= 0 min" in 20sp white. The digits tick up over 300ms.
- A 6dp teal bar sits under the total, with "12s" at the right. It steps once per second with a 150ms tween.
- Primary button:
  - Before any coin: "Cancel", outline, 56dp.
  - After the first coin: teal "Done · ₱5", 64dp. The seconds live in the bar and ring, not the button.
- Each coin triggers:
  - a SoundPool clink
  - a haptic tick
  - a Cheer hop
  - 9 falling coins, over at most 1.2 s
  - the counter tick-up
  - an extended window
- After 8 s with no coin, show a caption: "Push the coin in slowly. Still stuck? Ask the shop."
- An error shows a danger chip, "Coin not detected. Try again.", and the mascot holds Listening.
- The countdown pause logic stays driven by the existing state. Only presentation changes.

**C. Success.** The mascot Cheers with a gold "30 min loaded!" for 1.2 s. A 200ms crossfade then leads to the launcher. The mp4 logo loop stays, gated by `VideoBackgroundPolicy`.

**D. Time running**
- Collapsed: the Pill. The 5 s long-press still opens the admin PIN.
- Expanded (300dp, surfaceHi panel), in order:
  1. Header with a 40dp mascot, the alias in sentence case ("Game space", 18sp) and "Time left".
  2. Timer at display 40sp, tabular, in `TimeLevel` colour, with a 6dp progress bar in brandFg (turns gold or danger by level).
  3. Gold-outline "Add coin" (56dp), which runs flow B in-panel.
  4. A 2-column grid of 56dp tonal buttons: Home, Close app, Clear RAM (the label replacing "Turbo boost"), Sign out.
  5. Volume and brightness sliders, kept visible with 48dp thumbs. RAM becomes one caption line.
- Sign out opens a confirm dialog naming what is lost: "End session? You will lose 12:30." Buttons: "Keep playing" (primary) and "End session".
- The 5 s long-press on the expanded timer also still opens the PIN.

**E. Time almost up** (no full-screen takeover; bottom Banner with the Worried mascot)
- At 300 s: one soft ding, and a banner "5 minutes left" with an "Add time" action. Auto-dismiss after 6 s. The pill turns gold with the hourglass.
- At 60 s: banner "1 minute left! Insert coin for more time." with an "Add time" action. It is persistent. The pill is danger, and the mascot is Worried. The pill scales 1.0 to 1.06 over 200ms once per tick, driven by state, not an infinite transition, and limited to the last 60 s.
- At 10 s: banner re-appears, with a beep.
- Battery, slot-expired and arena banners use the same component, with a warn or danger side bar.

**F. Time's up.** The lock screen returns with a ThankYou wave for 3 s: "Thank you! Come back soon!". Then Idle. `LockScreenPresence.enter()` and `leave()` calls keep their current timing.

**G. Admin (PIN)**
- Background is surface, with no art and no mascot animation. The LockUp header carries "Owner mode".
- PIN dots are 16dp and keys are 64dp tonal squares. A wrong PIN shows "Wrong PIN" inline in dangerText.
- After unlock, cards in this order: This phone (with sales, section 7), Coin box (connected, last coin time), Session and rate, Settings (video background, sound, volume, pause), Hidden apps, Sleep and battery, Diagnostics, Lock now, Exit kiosk.
- IP, controller address, slot and hardware details go in a collapsed "Technical" row.
- Destructive actions open a confirm dialog that names the object.

**Empty, error and loading states**
- Loading ("Getting ready…"): static Idle mascot, three dots that step every 400ms, disabled button.
- Offline: Sleepy mascot, disabled button "Coin box offline", nothing animates.
- Box unreachable on the sales card: "Box not reachable, last seen 10:42".

## 5. Backdrop art (`LockScreenArt`, slimmed)
- Clouds, rays and stickers draw into one cached layer. Brushes are built via `drawWithCache`, with no per-frame `listOf` or `Brush`. The three translucent layers become one static scrim.
- Rays stay as static art. Cut the ray rotation, the 45 s ray pulse, the sticker wiggle and the 60 s `rememberInfiniteTransition`.
- Stickers: keep 6 (down from 11), arranged around the stage and never behind the mascot. Keep the dropped 5 resources until the layout is approved.
- Wake intro (once, 2.5 s): stickers pop in staggered at 300ms each, then hold still.
- Sparkles and coin rain appear only on events.
- The single ambient effect is the mascot's Idle wave or blink. This resolves the Direction 1 conflict between "one ambient effect" and its several scheduled effects.

## 6. Copy guidelines
- Short English, sentence case, no emoji, no all-caps labels.
- All text is English. No Filipino (Tagalog) text appears in the product.
- Every string goes in `strings.xml`, with a `values-fil` counterpart. Do not mix two languages in one label.
- Prices always read "₱N = M min", with a gold coin glyph.
- Errors are specific but neutral: "Coin not detected. Try again." For vouchers use only "Code not accepted" (generic, so codes cannot be probed).

## 7. New features

**Pause and resume (owner toggle, default off)**
- Enabled in Admin > Settings. It follows the PisoWiFi pause rules: pause cap, max pause length and a pauses-left count.
- When enabled, the expanded panel shows a warn-outline "Pause (1 left)" button under the timer. A disabled Pause shows its reason in text.
- A paused phone shows the locked layout with a Sleepy mascot, a gold "Paused · 12:40 saved" chip, the caption "Phone goes back to the shop in 5:00", and a brand "Resume" button. "Add coin" becomes secondary.
- The "Paused" chip is the same gold chip the portal uses.
- Open question for the owner: does a paused rental phone stay in the customer's hands?

**Vouchers ("Enter code" button)**
- It opens a bottom sheet (surfaceHi, radius 28, scrim) with one 56dp monospace field, `XXXXX-XXXXX`.
  - The dash is inserted automatically.
  - Capitalisation is forced to characters, and paste is accepted.
  - O becomes 0, and I or L become 1.
  - The button reads "Use code".
- The UI calls one interface: `redeem(code)` returning `Redeemed(minutes) | Rejected | Throttled(seconds) | Offline`.
  - Redeemed: the sheet closes, the mascot Cheers and a gold "Added 30 min" shows with the counter ticking up.
  - Rejected: "Code not accepted", in dangerText.
  - Throttled: "Too many tries. Wait 1:00."
  - Offline: "Coin box offline. Ask the shop staff."
- Validation and generation live on the router or box. Ship the sheet behind the interface with a stub, and wire the real API after the portal.

**Sales (admin only; nothing on kid screens)**
- The "This phone" card has two tiles, Today ₱ (gold, 24sp, tabular) and Sessions, with "vs yesterday" as a caption delta.
- Below is a 7-bar Canvas chart. Bars are solid brandFg, today's bar is gold, and weekday letters at 12sp sit below.
- It shows the same figures as the ESP32 Overview and adds no phone-side data model. The full dashboard stays in the router console and box dashboard.
- A "Mark collected" action (with a confirm dialog naming the amount) is deferred to the console reconcile card.

## 8. Motion

| Motion | Duration |
|---|---|
| Press | 80ms |
| State change | 150ms ease-out |
| Screen or sheet | 200ms |
| Counter tick | 300ms |
| Hop | 400ms |
| Sparkle burst | 600ms |
| Blink | 300ms |
| Coin shower | at most 1.2 s |
| Wake intro | 2.5 s, once |

- If `ANIMATOR_DURATION_SCALE == 0`, everything snaps with no movement.
- Animate only through `graphicsLayer` lambdas, `offset {}` and `drawBehind`.

## 9. Accessibility
- Body text is 16sp, and nothing is below 12sp.
- Touch targets are at least 48dp, and primary buttons are 64dp.
- Contrast: text and muted on surface at least 7:1, white on brand 5.5:1, onGold 10.4:1, onTeal 6.9:1, dangerText 7.6:1, lineStrong 4.5:1.
- State is never colour-only. Time levels use the pill icon, the banner text and the mascot state.
- Icon buttons have `contentDescription`s: sound, palette, admin.
- Fonts respect system font scale. The layout scrolls rather than clips.
- Sound is optional: the toggle is bottom-left and the admin sets the volume. Every sound has a visual twin.

## 10. Performance budgets (Unisoc T606, Mali-G57 MP1)
- 0 fps at rest. Bursts run at 60 fps for at most 600ms. Press feedback always runs at full rate.
- No `Modifier.blur` or `RenderEffect`, no animated shadows, no alpha panels over the art, and at most 16 particles.
- `isLow` and `isCritical` come from `derivedStateOf`, so the mascot recomposes once per level change.
- Stop all loops when the screen is not RESUMED (`LifecycleResumeEffect`) or when it dims.
- Thermal ladder, using `PowerManager` thermal status:

| Status | Behaviour |
|---|---|
| NONE or LIGHT | Full effects |
| MODERATE | No ambient (Idle wave stops); taps and coins still animate |
| SEVERE | Flat bg plus the timer only |

- If `VideoBackgroundPolicy.shouldPlay` is false, the art is static and there is no idle loop.
- Sound: SoundPool with 3 clips (clink, success, tap), plus a ding at 300 s and 60 s. Each is under 300ms, total about 40 KB. No music.
- Ship a Baseline Profile. Measure with Macrobenchmark `FrameTimingMetric` on a real T606 release build. Target p90 frame time under 16ms during coin and banner transitions.
- Bundled assets stay small: font about 25 KB, three sounds about 40 KB.

## 11. Must NOT change
Checked against `app/src/test`. I found no Compose UI tests, `testTag`s or `contentDescription` assertions.
- **`TimeLevel`** (`com.pisophone.kiosk.overlay.ui.TimeLevel.of`), pinned by `TimeLevelUnitTest`:
  - Enum names OK, LOW, CRITICAL.
  - 301 s or more is OK, 300 to 61 s is LOW, and 60 to 1 s is CRITICAL (0 is OK).
  - Only colours and icons attach to it.
- **`VideoBackgroundPolicy`** rules, pinned by `VideoBackgroundPolicyUnitTest`:
  - It never plays when the screen is not in front, when covered, when disabled by the admin, on low RAM or in power-save.
  - It stops below 15% battery unless charging. An unknown battery level (-1) does not stop it.
- **`LockScreenPresence.enter()`, `leave()` and `.shown`**, covered by a test. Keep the call timing.
- **`AdminMaintenanceMode.suspendOverlays`** semantics, covered by `AdminMaintenanceOverlaysUnitTest`.
- **`KioskStatusToastUnitTest` and `CoinSpeechUnitTest`** assert on toast and spoken text. Leave those message sources untouched. Restyling UI around them is fine, but re-check both tests before touching any string they use.
- **`AccountController.startScan`** and the 5 s long-press to the admin PIN, on both the pill and the expanded timer.
- **Banner triggers** for the arena, battery and slot-expired banners. Only their rendering is unified.
- **The panel-centred layout order** the owner liked: status row, panel, rate and button, card link, footer.
- **Network and protocol code** (`Esp32*`, `ProtocolContract`) is out of scope. The UI changes only presentation. New behaviour (pause, voucher redemption, sales) goes through new interfaces. Run `./gradlew testDebugUnitTest` before merge.

## 12. Implementation notes
- **Files to change:** `Color.kt` (becomes `Tokens.kt`), `Type.kt`, `BlockScreen.kt`, `BlockScreenComponents.kt`, `LockScreenArt.kt`, `FloatingPill.kt`, `FloatingPillControls.kt`, `FloatingPillAdminDialog.kt`, `SecurityVaultView.kt`, `KioskOverlayCoordinator`.
- **New files:** `PisoButton.kt`, `MascotView.kt` with `MascotState.kt`, `Halo.kt`, `Banner.kt`, `VoucherSheet.kt`, plus `values-fil/strings.xml`.
- **Needed from the owner:** mascot source art or eye coordinates for the blink; a ruling on the paused-phone question.
- **Risks:**
  - Removing the 8 themes removes a toy kids may like. The 3 backdrop tints and the sound toggle are the trade.
  - The font adds a loading path, so test the fallback.
  - The mascot is the single biggest decode (103 KB), so keep the `MascotView` bitmap cached.
