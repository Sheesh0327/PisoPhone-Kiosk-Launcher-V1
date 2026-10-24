# Feature 023: the shop name as a bold running marquee

Status: **draft (owner decisions recorded; waiting for approval to build)**   Branch: `feat/023-shop-name-marquee`   Area: app
Backlog rows: owner request 2026-10-24 (extends feature 013)   Release: app

## Intent
The shop owner wants the shop's name to be the most noticeable branding on the rental phones: a running marquee on the **lock screen
and the home screen**, big and beautifully styled, so every customer sees whose shop it is. A phone whose owner set no shop name
shows the plain default **"PisoPhone"** instead of an empty gap. Serves the owner's brand; touches no money or time.

## What is there today (feature 013, read from the code)
- `BrandMarquee.kt` (53 lines): the name in **14 sp**, one line, `basicMarquee`. A name that **fits the width does not move**; only
  an over-long name scrolls. With no name set it draws **nothing** (no row at all).
- `MarqueeRule.kt`: the name scrolls only on a cool phone with the screen in front, animations on, not in power save, battery not
  low; otherwise it stands still and centred. Density `off` stills it too.
- It sits in the header band on the lock screen (`BlockScreen.kt` lines 251 and 265) and under the title on the home screen
  (`LauncherScreen.kt` line 215). The name comes from the box (`ShopBranding.kt`, at most 32 characters, sanitized).
So today the owner sees a small, mostly still line, which is not what was asked for.

## Invariants touched
- Money and time: **not touched** (display only: no credit, expiry, deduction or queue code changes).
- Only owner-signed software: not touched.

## Constraints
- Nothing the customer needs may be hidden or pushed off a small screen: the timer, the rate, the coin button, the card link and the
  box status stay fully visible on the smallest phone in the golden set (the check feature 013 already has).
- The name is still owner-typed text: the same sanitising as 013 (no new input path, never a file name, URL or command).
- The box -> phone message does not change (the name and the "off" word already travel in the branding push).
- A phone that is hot, nearly flat, in power save or has animations off must not be made worse: the ladder in `MarqueeRule` stays
  (it only draws less), see Owner decision 2.
- Screenshot goldens: recorded by the CI build on the feature branch (never by hand), looked at by the owner before merge.

## Success criteria
1. **Always present.** With a shop name set, the marquee shows it; with none set (or only blanks) it shows **"PisoPhone"**, in the
   same styling. Tested for both (red first: today a blank name draws nothing).
2. **It runs.** While the rule allows, the text moves continuously and seamlessly: the name repeated with a gap so the band is
   never empty, **even when the name would fit** the width (red first: today a short name stands still).
3. **Bold and beautiful.** The band is a distinct strip across the full width with the palette's colours (gradient fill on the
   text, a soft glow, a slow shine), the text at least **28 sp** and bold (today 14 sp), the band at least **56 dp** high; the lock
   screen and the home screen use the same composable. A test checks, for all six palettes, that the text colour against the band
   has a contrast of at least 4.5 : 1.
4. **Stands still safely.** When the rule says stop (hot, low battery, power save, animations off, density `off`, screen not in
   front) the name stands still, centred, whole and readable (a long name wraps to two lines; never cut).
5. **The rest of the screen is intact.** The existing goldens for the six palettes and both screens are re-recorded by CI; the
   difference pictures show the band and nothing else moved off screen (checked by the owner).
6. **Cost.** One animation for the whole band, stopped when the screen is not RESUMED; not measured on a phone here, so
   hardware check **RS48** (a cheap phone for an hour on the lock screen: temperature and battery) is added and stays unticked.

## Out of scope
A logo or picture, sound, the "by TechNet Solutions" credit, the header title text and the device-type label (feature 024), changes to
the box's admin page (the shop name field exists), per-phone names.

## Owner decisions (answered 2026-10-24)
1. No shop name set: **the band shows "PisoPhone"** (the band is always there).
2. The safety rule **stays**: the name stands still, centred and whole, on a hot, nearly flat or power-saving phone, with animations off, or at density `off`.
3. Short names **scroll too** (the name repeats with a gap so the band is never empty).
4. Colours **follow the chosen palette** (the default palette: gold on dark).
5. Speed: about 60 dp per second (not answered; the proposal stands unless you say otherwise).
Order: built together with feature 024 in one app build, after 022.
