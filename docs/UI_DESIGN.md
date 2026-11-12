# UI design guide

Shared visual and accessibility guidance for PisoPhone, PisoWiFi, the coin box dashboard, the router console and setup pages.
Current source and regression tests define behavior, protocol fields, supported languages and size limits. This guide does not
introduce features or authorize changes to payment, pause, voucher or release behavior.

## Palette

Navy surfaces carry the brand. Blue means action, teal means connected, gold emphasizes money or attention, and red signals a problem.
Every state also needs a text label or recognizable icon. Use existing theme tokens instead of new color literals.

### Web colors

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


### Android colors

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


## Layout and type

- Lead with the answer: time left for customers, cash or sales for owners. Put technical details in a collapsed section.
- Web surfaces use system fonts, tabular figures, solid fills and tonal elevation. Reserve shadows for dialogs and menus.
- Owner surfaces use a navy brand band and a thin blue-to-teal signal line. Keep gradients off text and controls.
- Use the existing Android typography and font fallback; a missing font must never crash the app.
- Use a 4/8/12/16/24/32/48 spacing scale and 16px/dp gutters. Customer content fits a narrow, approximately 420px/dp column.
- Web inputs use radius 6, buttons/cards 10 and pills fully rounded. Android uses 8 for chips, 16 for cards, 20 for buttons and 28 for panels/sheets.
- Phone layout keeps the status row, central panel, rate and payment button, card link and footer in that order.
- Keep customer payment screens separate from owner diagnostics and sales controls. Confirm destructive actions by naming the affected object.

## Accessibility

- Normal text needs at least 4.5:1 contrast; control outlines and meaningful icons need at least 3:1. Prefer stronger contrast for small or muted text.
- Body text is approximately 16px/sp; captions never below 12px/sp. Respect font scaling and scroll instead of clipping.
- Touch targets are at least 48px/dp, with primary buttons 52px on the web and 64dp on phones.
- Disabled controls explain why. Web controls use appropriate disabled semantics; dialogs manage focus, close on Escape and return focus to their trigger.
- Never announce a ticking clock with aria-live. Announce payment outcomes with role=status and errors with role=alert.
- Label icon buttons, provide visible keyboard focus, and give every sound a visual equivalent. Sound remains optional.
- Use short, neutral, sentence-case copy. Preserve tested strings and localization resources when changing presentation.

## Motion and performance

- Web state transitions are brief (about 150ms), without ambient animation or shimmer. Respect prefers-reduced-motion.
- Android honors disabled system animations, lifecycle, battery and thermal policies. Stop idle effects when hidden or dimmed.
- Keep the mascot and backdrop cached. Avoid blur, animated shadows, per-frame allocation and excessive particles (maximum 16).
- Mascot states communicate idle, listening, success, low time, offline and completion without requiring sound.
- Keep portal CSS compatible with older WebViews: literal fallbacks before CSS variables, rgba colors and margin-based spacing.
- Keep existing asset and response-size budgets enforced by tests; do not raise them as part of a visual cleanup.

## Compatibility checks

- Preserve portal element IDs, tested labels, payment state messages and authenticated request headers.
- Preserve Android TimeLevel thresholds, VideoBackgroundPolicy, lock-screen presence timing, overlay maintenance behavior and admin gestures.
- Keep timer and payment logic independent of rendering. Theme changes must not alter stored balances, expiry or network contracts.
- Update source assets and regenerate through the tools in `CLAUDE.md`. Signed console updates follow `docs/RELEASE.md`.
- Run the affected checks from `CONTRIBUTING.md`, including contrast and UI regression tests when changing visuals.
