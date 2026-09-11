# PisoPhone Hardware Provisioning Utility (`website/`)

This directory contains the single-purpose WebUSB provisioning and deprovisioning utility for **PisoPhone**.

## Architecture & Security Boundary

- **Private Utility**: This website is not a public portal and has no user accounts, logins, or purchase flows.
- **Hardware-Gated Access**: Access is restricted strictly to requests originating from the physical PisoPhone ESP32 Coin Slot Box portal (containing valid `mac` and `ip` parameters). Direct public access is rejected.
- **Key Modules**:
  - `index.html`: Unified single-page WebUSB installer and deprovisioner.
  - `js/webadb_manager.js`: WebUSB ADB driver and provisioning orchestration.
  - `js/yume-chan-bundle.js`: Pre-bundled WebUSB ADB protocol runtime.
  - `app-release.apk` / `update/`: Binary APK packages flashed directly to client Android phones.
