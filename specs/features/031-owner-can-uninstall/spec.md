# Feature 031: the owner can remove apps from a rental phone

Status: **urgent fix, owner asked 2026-10-24 ("allow the owner to remove apps, the current version prevents uninstallations")**   Area: app

## Problem
`applyStrictKioskPolicies` added the device policies `DISALLOW_UNINSTALL_APPS` and `DISALLOW_APPS_CONTROL` for good, so nobody, the owner
included, could uninstall an app (Settings, Apps showed Uninstall greyed out).

## Fix
Like the Wi-Fi settings, the two restrictions follow the admin maintenance window: lifted while the window is open (the owner signed in with the
admin PIN and opened Settings), back as soon as it ends (`applyAppRemovalRestriction`, called from the strict policies and from
`applyMaintenanceAccess` when the window opens or closes). Customers still cannot remove or stop apps.

## Not changed
Apps the kiosk itself needs (the kiosk app is the device owner and cannot be uninstalled while it is). Google's apps are kept visible by feature 029;
removing one is now the owner's choice.

## Not tested
Not run on a phone. Hardware check RS55 added, unticked.
