# Feature 029: keep Google's apps (YouTube, Chrome, Maps, Photos...) on the rental phones

Status: **urgent fix, owner asked 2026-10-24 ("Don't disable or hide important apps from Google like YouTube etc.", directly on main)**   Area: app   Release: app

## Problem
After the first sign-in, `AppVisibilityPolicy.apply` switched off (device-owner `setApplicationHidden`) every pre-installed app with a launcher
icon that was not on its keep list. YouTube, Photos, Maps, Gmail, Drive, YouTube Music and the other Google apps were on neither list, so
customers could not use them.

## Fix
- Google's apps for customers (`com.google.android.youtube`, `com.android.chrome`, `com.google.android.gm`, the Google app, and the
  `com.google.android.apps.` family: Maps, Photos, Drive, Docs, YouTube Music ...) are kept visible: never disabled, never hidden.
  Google's own settings and device-management apps (Wellbeing, Pixel tips, device health, setup, work, safety, privacy, carrier) stay out of sight.
  What the phone needs in the background (the default SMS app, home, dialer, keyboard...) is still only hidden, whatever its name.
- Phones already cleaned up: once, the service brings back the Google apps that were switched off and shows again those that were hidden
  (`reinstateGoogleApps`, a flag keeps it to once).
- Not changed: the idle wipe of app data (`SocialApps`) is a separate privacy rule and still runs; say if it should stop for YouTube and Chrome.

## Success criteria
1. `decide` keeps the Google apps visible, keeps hiding the Google settings/device apps and the phone's critical apps (unit tests).
2. Money, time and the box protocol are not touched. Hardware check RS53 (a phone that was cleaned up before: YouTube is back after the update) stays unticked.

## Not tested
Not run on a phone. Which pre-installed Google apps a given phone has, and whether Android lets a switched-off app come back by itself, is
the phone's business: the check is on a real device.
