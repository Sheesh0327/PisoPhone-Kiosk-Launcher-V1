# Feature 030: no more "Wi-Fi: joining <network>..." toast on a phone that is already connected

Status: **urgent fix, owner reported 2026-10-24 (photo: a persistent toast "PisoPhone: Wi-Fi: joining YANEXX PISOPHONE... (now on 192.168.1.100)")**   Area: app

## Problem
A PisoPhone-only phone that has joined the shop's Wi-Fi (it has 192.168.1.100) still shows the "joining" line every minute. `KioskWifi.isOnNetwork`
could not tell it was on the kiosk network: Android hides the network's name from an app without the location permission ("<unknown ssid>"), and
the saved-network list the app falls back on is empty for a network the system saved (the setup QR code does that). So each minute the app
re-enabled the network and reconnected, and said "joining" again.

## Fix
A connected phone with an address and an unreadable name counts as on the kiosk network when it cannot be anything else: the Android 13+
allowlist holds it to that network, or the app cannot see the saved networks at all, or the saved kiosk network is the one in use
(`connectedWithoutName`, unit tested). Then the line says "connected" (shown rarely) and the phone is no longer reconnected every minute.

## Not tested
Not run on a phone. Hardware check RS54 added, unticked.
