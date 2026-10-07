# PisoPhone Hardware Provisioning Utility (`website/`)

This directory contains the single-purpose WebUSB provisioning and deprovisioning utility for **PisoPhone**.

## Architecture & Security Boundary

- **Private Utility**: This website is not a public portal and has no user accounts, logins, or purchase flows.
- **Hardware-Gated Access**: Access is restricted strictly to requests originating from the physical PisoPhone ESP32 Coin Slot Box portal (containing valid `mac` and `ip` parameters). Direct public access is rejected.
- **Key Modules**:
  - `index.html`: the phone setup page: a QR setup code (main way: Android's own device-owner setup, no cable) and the USB cable fallback (WebUSB ADB), plus removing the kiosk from a phone.
  - `js/provisioning.js`: the box's link (checked once), the QR code's content; `js/qrcode.js` draws it (vendored, MIT).
  - `js/webadb_manager.js`: the USB fallback (loads the ADB library only when used).
  - `js/yume-chan-bundle.js`: Pre-bundled WebUSB ADB protocol runtime.
  - `app-release.apk` / `update/`: Binary APK packages flashed directly to client Android phones; `update/firmware*` for the coin box and `update/router.json` + `router-setup.sh` for the routers (signed with the owner key, docs/RELEASE.md).
