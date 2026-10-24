# Feature 027: a new PisoPhone-only phone says "refused to add <network>" and never finds the box

Status: **urgent fix, owner asked 2026-10-24 ("apply fix and merge directly to main")**   Area: app   Release: app

## Problem
On a PisoPhone-only shop the owner sets up a rental phone; the phone shows "PisoPhone refused to add <my network>" and so never reaches
the box. That line is `KioskWifi.join` (`KioskWifi.kt`): as device owner the app tries `addNetwork`/`updateNetwork` for the shop's Wi-Fi and,
when Android returns -1, gives up with `REFUSED`. A shop's own router network is often already saved on the phone (set up by hand or by
the provisioning), and Android does not let an app change a network it did not create, so the update is refused although the network is
perfectly usable.

## Not confirmed
Nothing was run on a phone. The cause above is the most likely reading of the code, not a reproduction. The fix therefore tries the other
ways in and shows why when it still fails (Android version, device owner or not), so a second report names the cause.

## Fix
When the add/update is refused: (1) if a network of that name is already saved, switch it on as it is and reconnect; (2) else, if the
network suggestion made just before was accepted, that is the way in (a device owner's suggestion needs no approval); (3) only with neither
is it `REFUSED`, and the status line then says the Android version and whether the phone is the device owner.

## Success criteria
1. `KioskWifi.afterRefused` returns JOINING for a saved network switched on, JOINING for an accepted suggestion, REFUSED only with neither (unit test).
2. Money, time and the box protocol are not touched.
3. Hardware check RS51 (a phone-only shop, a new phone, the shop's Wi-Fi) stays unticked until a phone has done it.
