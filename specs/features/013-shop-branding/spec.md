# Feature 013: Shop branding on the phone screens (colours, shop name, title)

Status: **approved** (owner, 2026-10-23)   Branch: `feat/013-shop-branding`   Area: app, firmware
Backlog rows: none (owner request 2026-10-23)   Release: app + firmware

> Branch note: `beta` is not present in the session that wrote this, so the work sits on `claude/serene-feynman-ycyr6r`
> until the owner grafts it onto `feat/013-shop-branding` cut from `beta`.

## Intent
A shop owner resells PisoPhone to clients who want the rental phones to look like *their* shop, not like a TechNet demo. Today
every phone in every shop is identical: three fixed backdrop colours (`BackdropTint`), a hardcoded launcher palette
(`LauncherThemeColors`) and the product name "PisoPhone" in the lock-screen header. There is no shop name anywhere in the app.

This feature lets the owner pick one of several built-in colour palettes, set the shop's name (shown as a running marquee on the
lock and home screens), set the header title, and choose how busy the screen's animation is. **All four are set in one place:
the box's own admin page.** The colours and the animation code ship inside the one signed APK; the coin box holds only the
choice and the names and hands them to each phone, so a shop is branded once and every phone follows.

**The division of labour, as a rule for this feature and any later one:** the box stores configuration — short text and
numbers — and the phones do all the rendering, following what the box says. No picture, font or animation asset ever lives on
the box.

**One thing is not brandable: the maker's credit.** A permanent "by TechNet Solutions" line sits at the bottom of the lock and
home screens, with its own small animation, and no palette, name, title or density setting can remove it, hide it or still it.
It is part of the signed app, not configuration, so the box cannot switch it off either.

It serves **project rule 2** (only owner-signed software is installed): the palettes are compiled into the signed APK, and the
box stores only short settings, so nothing new is ever downloaded or installed on a phone to change how it looks.

## Invariants touched
- **Money and time (a payment recorded first, credited once, nothing lost on a power cut): not touched.** No payment, credit,
  expiry, deduction or queue code changes. The timer, the rate line, the coin feedback (coin rain, sparkles, speech) and
  `TimeLevel` keep their behaviour and their colours: money gold, warning red and success teal are *not* brandable.
- **Only owner-signed software: kept.** No new download, OTA or install path. The palettes live in the APK, which is signed and
  published as it is today. The box gains three short settings in NVS and sends them over the existing authenticated, encrypted
  `/config` push; the signed message contract (`protocol/gen_vectors.py` fixtures) does not change.

## Constraints
1. **The box stores settings, never files.** The four settings are short text: a palette id, the shop name, the header title
   and an animation density. No image, logo, font or animation asset is ever uploaded to, stored on or served by the coin box,
   and the box never renders anything for a phone. Total NVS cost **under 512 bytes measured** (NVS is 20 KB in total,
   `esp32_firmware/partitions_encrypted.csv`, and already holds the payment queue, accounts, the revenue vault, phone profiles
   and orphan payments; NVS charges per-entry overhead, so four short strings cost more than their characters).
1b. **The admin page is the only place these are set.** They are typed in the box's admin (dashboard) config page, like the
   phone profiles and the rate are today. The phone's owner vault shows what the phone received, read-only, and never edits it:
   one source of truth for a shop, and no way for a customer-facing phone to disagree with the box.
2. **Both sides change in one change** (`CLAUDE.md` section 6). Only *optional* url-encoded params are added to the existing
   authenticated `/config` route (`KioskHttpServer.kt:306`), carried by a `/config` push of their own: the box's push params
   buffer is 256 bytes (`AuthRequest` in `Config.h`) and the old push already carries the name, slot and PIN in it, so the old
   push at `DeviceManager.cpp:88` and `WebServerConfig.cpp:377` is left byte-for-byte as it is and the branding follows it in a
   second push (on pairing, on a branding save, and after a config save). *Corrected 2026-10-23: the first wording appended the
   params to the old push, where a long name would have been cut in the middle of an escape.* A live box
   with a new app, and a new box with a live app, both keep working: the phone reads the params with `has`/`optString` as it
   already does for `device_name`, `price` and `minutes`, and an old box simply sends none.
3. **`docs/design/GUI_SPEC_PISOPHONE_KIDS.md` section 10 (performance).** 0 fps at rest, no `Modifier.blur`, no alpha panels
   over the art, **at most 16 particles**, everything stops when the screen is not RESUMED. The marquee is the one moving thing
   added, and it obeys the existing thermal and battery ladder (below).
3b. **The animation density is a ceiling the owner lowers, never a way to raise past the budget.** The thermal and battery
   ladder (`GUI_SPEC` section 10: MODERATE drops ambient motion, SEVERE flattens to background plus timer) always wins over the
   setting: a hot phone is calm whatever the box says. The highest density never has more ambient particles than `normal`, and no
   burst passes the 16-particle cap (`GUI_SPEC` section 5: the 16 is a burst's size). Today's behaviour
   is the `normal` level, so a shop that sets nothing sees no change.
3e. **A shop title replaces the maker's word in the header; a shop palette hides the palette button (owner, 2026-10-23).** With a
   title set, the lock-screen header shows that title alone, without the hardcoded "TechNet" before it (TechNet stays on screen
   through the permanent credit, 3c); with none set the header is "TechNet PisoPhone" as today. With a shop palette set the
   child's palette button is not drawn (it could no longer change the colours); with none set it cycles the three tints as today.
3c. **The maker's credit is not configuration.** "by TechNet Solutions" is drawn at the bottom of the lock and home screens
   from the app's own resources, with its own animation. No setting reaches it: there is no box key, no param and no vault
   switch for it, so it cannot be turned off, emptied or hidden, and a client's palette cannot make it invisible (its contrast
   is part of the palette test, criterion 4). It must not cover or crowd the lines a shop needs: the coin-box status ("Coin box
   offline. Ask the shop staff."), the rate, the card link or the owner-settings reach. It sits below them, and the
   panel-centred layout order `GUI_SPEC` section 11 pins (status row, panel, rate and button, card link, footer) is unchanged:
   the credit is added under that footer, displacing none of it.
3d. **Density never removes feedback that money arrived.** The coin rain, the sparkle on a coin, the "time added" pop-over and
   the spoken confirmation play at every density, including `off`. Density governs *ambient* motion only — the drifting clouds,
   the turning rays, the sticker wiggle, the rising bubbles, the idle twinkle. A customer must always see that their coin
   counted (project rule 1).
4. **`GUI_SPEC` section 11 (must not change).** `TimeLevel`, `VideoBackgroundPolicy`, `LockScreenPresence`, the banner triggers,
   `AdminMaintenanceMode.suspendOverlays`, the strings asserted by `KioskStatusToastUnitTest` and `CoinSpeechUnitTest`, and the
   panel-centred layout order (status row, panel, rate and button, card link, footer). The marquee is added inside the header
   band, above that order, and the existing art (clouds, rays, stickers, mascot, coins) is kept exactly.
5. **Readable before unlock.** The lock screen draws in direct boot, so the branding is stored in the device-protected
   preferences (`KioskSecurity.getDirectBootPrefs`, as `KioskSettings` already does), never in credential-protected storage.
6. **No contrast a client can break.** Only built-in palettes, each checked by a test (criterion 4). Free hex colours are out.
7. **English strings in `strings.xml`** (`GUI_SPEC` section 6 also asks for a `values-fil` counterpart; the module has none for any
   string, so a folder holding only this feature's strings was removed 2026-10-23 on the owner's decision. That rule is unmet across
   the whole module and is its own task, not this feature's). The shop name itself is owner
   data, not a string resource, and is never translated.
8. Generated files are regenerated, never hand-edited (`CLAUDE.md` section 4): if the box dashboard page under
   `esp32_firmware/web/` changes, `python3 scripts/embed_web.py` regenerates `include/WebAssets.h`.
9. The shop name is owner-entered text drawn on screen: it is length-capped and stripped of control characters and newlines
   before storage, and it is never used as a file name, a URL or a shell argument.

## Success criteria
1. **The admin page holds the settings.** The box's admin config page has a *Shop branding* section with four fields —
   palette (a list of the built-in ids), shop name, header title, animation density (`off`, `low`, `normal`, `high`). Saving
   stores them in NVS; they survive a box restart; a bad value is refused with a reason and changes nothing. Firmware host test
   plus a serial and browser check on the board (RS38).
2. **The box pushes them, and nothing old breaks.** The settings ride the existing authenticated `/config` push on pairing and
   on save. `protocol/fixtures` and the signing vectors are byte-identical; a phone build without this feature ignores the new
   params (host test asserting the pushed parameter string, and the app's `/config` test fed a push with unknown params).
3. **The phone remembers, and shows it before unlock.** After `/config`, the four settings are in the device-protected
   preferences; after a reboot with the box offline the lock screen still draws the shop's palette, title and marquee. Unit
   test on the storage, plus a Roborazzi golden of the branded lock screen.
4. **No palette can hide the text, the credit included.** A unit test over *every* built-in palette: primary text on the
   backdrop (top and bottom colour) is at least 4.5:1, icons and outlines at least 3:1 — the `GUI_SPEC` section 2 and 9
   numbers — and the maker's credit at least 4.5:1 on both. A palette that fails does not compile into the product (the test is
   the gate).
5. **The marquee obeys the ladder, and `off` stills it.** A pure function, tested like `VideoBackgroundPolicy`: it scrolls only at
   density `low`, `normal` or `high` and at thermal NONE or LIGHT, with the screen RESUMED and in front, animations on, not in power save, and not below 15% battery while draining.
   Otherwise the shop name stands still, centred, and stays fully readable. Unit test over the cases.
   *Added 2026-10-23 (owner): at density `off` the name stands still too, because `off` means "the picture stands still". The
   maker's credit is the one deliberate exception: its sheen follows only the ladder, never the density, because the owner said
   the credit holds "regardless of the settings or animation density". The marquee and the credit differ on purpose; do not
   "fix" either to match the other.*
6. **An unbranded phone looks exactly like today.** With nothing ever set (and with the box offline before the first push):
   palette `technet`, title "PisoPhone", no marquee row at all. The existing lock-screen and launcher Roborazzi goldens differ
   from today only by the credit line at the bottom (the footer and everything above it sit one line higher, nothing else
   changes); they are re-recorded once with that change and the owner looks at the difference pictures. *Corrected 2026-10-23:
   the first wording, "byte-identical, no re-record", could not hold together with a credit that is always on screen.*
7. **Money and time screens are untouched.** `./gradlew testDebugUnitTest` passes with no changes to the tests that pin
   `TimeLevel`, `VideoBackgroundPolicy`, `LockScreenPresence`, the toast and speech strings, or the payment paths.
8. **The box stays a money device, not a file store.** No new upload endpoint, no file written to spiffs, no rendering done on
   the box, and the four settings together take under 512 bytes of NVS (host test printing the measured sizes).
9. **Nothing moved that must not move.** `bash scripts/check_security_rules.sh` passes; `protocol/gen_vectors.py` output
   unchanged; `esp32_firmware/host_tests/protocol_contract_test.cpp` passes unchanged.
10. **Releases raised.** `PISO_FW_VERSION` raised (3.12.0) and the app build number raised; no router or setup release
    (`setup/RELEASE` untouched), because nothing a router installs changes.
11. **The density levels do what they say, and the ladder still wins.** A pure function maps (density, thermal status, battery,
    power save, animations on) to how many stickers wiggle, how many bubbles and sparkles run, and whether the clouds and rays
    move. Unit-tested: `off` has no ambient motion at any temperature; `normal` reproduces today's counts exactly; `high` never
    has more ambient particles than `normal` and no burst passes 16; MODERATE drops ambient motion and SEVERE flattens the art at *every* density including `high`; and at
    every density — `off` included — a coin still brings the coin rain, the sparkle, the pop-over and the spoken confirmation.
12. **The credit cannot be removed by configuration.** A test drives every combination the box can send — every palette, every
    density including `off`, an empty shop name, an empty title, junk values, nothing set at all — and in each case the
    "by TechNet Solutions" line is present, readable (criterion 4) and animating under the ladder rule of criterion 11. A
    screenshot golden at `off` shows it, and a code check asserts there is no key, param or vault switch that addresses it.
    It never overlaps the coin-box status, the rate, the card link or the owner-settings reach.

## Out of scope
- **Logo images.** No logo in this feature, and when one comes it ships in the APK or is picked on the phone — the box never
  stores or serves image files (constraint 1).
- Photo or video wallpapers; replacing or hiding the existing art (clouds, rays, stickers, mascot, coin rain).
- Free hex colours, a palette editor, or a per-client APK build in CI.
- Branding any surface other than the lock screen and the home screen: the card-scan screen, the time-added pop-over, the
  floating pill and the owner vault keep TechNet colours.
- The PisoWiFi portal and the router console (a separate product, a separate spec).
- Translating the shop name, and fixing the pre-existing fact that the child's palette button choice
  (`KioskStateManager.themeIndex`) is not saved across restarts.
- **Editing the branding on a phone.** The owner vault shows what the phone received, read-only; the box's admin page is the
  only place to change it (constraint 1b).
- Per-phone branding inside one shop: the settings are the shop's, and every paired phone gets the same ones.
- Pushing branding to several phones from one phone (QR copy), and setting branding at provisioning time.

## Owner decisions
Answered by the owner on 2026-10-23 (interview in session `session_01PnBTKntvzuDSiiFRyAffPn`):
- Branded surfaces: lock screen and home screen only.
- What is brandable: colours, shop name as a running marquee, header title text. Logo: left out of this feature.
- Colour reach: backdrop tints plus one accent colour; money gold, warning red and success teal stay fixed.
- Mechanism: one signed APK carrying several built-in palettes; the setup picks one.
- Marquee: scrolls, and stands still when the phone is hot or low on battery.
- Source of truth: the coin box holds the palette choice and the names; the box saves settings only, never files.
- Where they are set: the ESP32 admin page, and only there (owner, 2026-10-23).
- An animation density setting is part of this feature (owner, 2026-10-23): the box stores the level, the phones render it.
- A permanent "by TechNet Solutions" credit with its own animation sits at the bottom, whatever the settings or the density
  (owner, 2026-10-23).
- The split for this and later features: configuration on the box, all rendering in the app, following the box (owner).
- Fallback: last known branding from direct-boot storage, today's TechNet look when nothing was ever set.
- Verification: spec, plan and code written here; the owner (or a session with the Android SDK) compiles and runs the gates.

**All six proposals below were approved as written by the owner on 2026-10-23** ("your proposals, approved"), including
question 5's reading of `normal` (today's screen stays the default) and question 6's three details: the credit is text, its
sheen sweeps about every 6 seconds, and its motion — not the words — pauses with everything else on a hot or nearly flat phone.
They are kept here in full as the record of what was approved:
1. **The palette list.** I propose six: `technet` (today's Dagat blue, the default), `dagat`, `tropiko`, `ginto` (the two other
   tints today), plus `rosas` (pink) and `berde` (green). Each is a backdrop pair, three cloud colours and one accent. Approve
   the names and the count, or give me the client colours you actually need.
2. **Length caps.** Shop name 32 characters, header title 20 characters. Longer names are refused in the box dashboard with a
   reason. Say if a client's name needs more.
3. **Where the marquee sits.** My proposal: on the lock screen, one line in the header band under the title (above the sticker
   band, so no sticker is ever drawn over it); on the home screen, one line under the clock and date. Confirm, or name another
   place.
4. **The header title default.** Stays "PisoPhone" when the owner sets nothing, so a phone always identifies itself unless a
   client deliberately renames it. Confirm.
5. **The density levels.** I propose four, with `normal` the default and today's screen:
   - `off` — the picture stands still. Clouds, rays, stickers and bubbles do not move; a coin still animates.
   - `low` — 4 stickers instead of 10, no bubbles, no ambient twinkle; slow cloud drift only.
   - `normal` — exactly today: 10 stickers wiggling, 12 bubbles, 20 sparkles, turning rays, the 60 s loop.
   - `high` — the liveliest the budget allows: `normal`'s counts (20 sparkles, 12 bubbles, never more) with a faster drift and
     ray turn (a 40 s loop instead of 60 s). *Corrected 2026-10-23, found during implementation and accepted by the owner: the first wording, "raised to the
     16-particle cap", contradicted `normal`'s 32 ambient particles today; the 16 is the size of one burst, not of the ambient
     count.*

   Note on `normal`: the design spec (`GUI_SPEC` sections 5 and 10) asks for *less* than today's code does — it wants the ray
   rotation, the sticker wiggle and the 60 s loop cut, and 6 stickers not 10. I kept `normal` as today's behaviour so an
   existing shop sees no change, which means `low` is close to what the design spec actually asked for. Say if you would rather
   `normal` become the design spec's calmer version and today's busier screen become `high` — that is a one-line change now and
   an owner-visible change on every live phone later.
6. **The credit: three details, and one place where I did not follow you literally.**
   - *What it is.* My proposal: the words "by TechNet Solutions" as text, no image — nothing new to bundle, it scales on any
     screen, and it is readable at the small size a footer line wants. Say if you want the drawn logo instead; then name the
     file you want used, because there is no TechNet wordmark in `app/src/main/res/` today (only the mascot and the stickers).
   - *Its animation.* My proposal: a slow sheen that sweeps across the words about every 6 seconds — one moving element, no
     particles, nothing the 16-particle budget notices. Say if you pictured something else (a gentle pulse, a glow, the accent
     colour cycling).
   - *Where I did not follow you literally.* You said "regardless of the settings or animation density", and that is exactly
     how I wrote it: **no setting can remove, hide or still it.** But I let its motion pause with everything else when the
     phone reaches thermal MODERATE or drops under 15% battery while draining. The ladder is not an owner setting — it is there
     so a cheap phone does not cook itself or die mid-rental — and a credit that keeps animating on a hot phone is the one
     moving thing left to blame. The words stay on screen the whole time either way; only the sheen stops. **Confirm, or tell
     me to animate it always and I will.**
