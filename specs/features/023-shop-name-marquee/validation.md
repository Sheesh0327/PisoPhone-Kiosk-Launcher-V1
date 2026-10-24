# Validation: feature 023 (the shop name as a bold running band)

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Always present: no name shows "PisoPhone" | `MarqueeTextUnitTest` (null, empty, blanks) | pass | CI build-apk unit tests green (runs 534 re-record, push run after the golden commit) |
| 2 | It runs, even for a short name | `MarqueeTextUnitTest` (repeats, running line wider than any screen); `basicMarquee` with spacing in `BrandMarquee.kt` | pass (rules) / not seen moving | unit tests; motion not seen: pictures are still frames |
| 3 | Bold and beautiful: 28 sp, band >= 56 dp, gradient, glow, shine; contrast >= 4.5:1 for six palettes | `MarqueeTextUnitTest` (sizes, contrast for all six palettes) | pass | unit tests; goldens `lock_*.png`, `launcher_*.png` re-drawn by CI and read: big glowing PisoPhone band on both screens |
| 4 | Stands still safely (hot, low battery, power save, animations off, density off) | `MarqueeRule` unchanged and its existing tests; the still branch of `BrandMarquee` wraps to 2 lines | pass (unchanged rules) | existing `MarqueeRule` tests green |
| 5 | The rest of the screen is intact | goldens re-recorded by CI on the merged branch | pass at a glance | timer, rate, coin button and card link stay on screen in `lock_idle.png`; owner to look at the rest |
| 6 | Cost | not measured here | not tested | hardware check RS48 added, unticked |

## Gates
- [x] ktlint 1.5.0 and detekt 1.23.8 with the CI jars; CI lint, compile and unit tests (build-apk)
- [x] goldens recorded by CI, not by hand (the old lock and home goldens were removed so CI draws them again)

## Not tested
Not run on a phone: the running motion, the shine, battery and heat (RS48). The goldens are still pictures.

## Money and time
Not touched: display only.
