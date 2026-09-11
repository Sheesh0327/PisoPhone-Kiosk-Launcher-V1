# PisoPhone Web Management & Provisioning Portal

A zero-driver, web-based management suite and hardware provisioning portal for the **PisoPhone Kiosk System**.

Hosted statically via **Cloudflare Pages** or **GitHub Pages**.

---

## Directory Structure

```text
website/
├── index.html              # Main Operator Dashboard & Box Registration
├── purchase.html           # Plans, Credit Top-Up & Payment Processing
├── install.html            # WebUSB / WebADB 1-Click Installer & Provisioner
├── activate.html           # Hardware License Activation & Slot Assignment
│
├── box_labels_a4.html      # Printable A4 Sheet Generator for Box Stickers & QR Codes
├── debloater.html          # Browser-based System Debloater for Factory Phones
├── deprovision.html        # Emergency Device Owner Deprovisioning & Uninstaller
├── sideload.html           # Direct Manual APK Sideload Utility
├── redirect.html           # Legacy payment route redirector
│
├── update/                  # Update directory for compiled APK & Firmware binaries
│   ├── app-release.apk     # Latest pre-built Android APK binary
│   ├── firmware.bin        # Latest compiled ESP32 firmware binary
│   └── firmware.json       # Version metadata for ESP32 Cloud OTA
│
├── js/                     # Client-side JavaScript Modules & Vendor Bundles
│   ├── auth.js             # Universal Google Auth & Operator Session Guard
│   ├── device_checker.js   # Mobile touch & viewport optimization helper
│   ├── webadb_manager.js   # WebUSB / WebADB abstraction layer for browser flashing
│   └── yume-chan-bundle.js # Vendored @yume-chan/adb WebUSB core bundle
│
├── installer/              # Dedicated Hardware-Connected ESP32 Flasher
│   └── index.html          # Lightweight zero-config flasher for local ESP32 AP
│
└── worker/                 # Serverless Cloud Backend
    ├── cloudflare_worker.js# Cloudflare Worker for authentication, licensing & payments
    └── README.md           # Worker deployment & environment variables guide
```

---

## Core Operator Flow

1. **Dashboard (`index.html`)**: Register new ESP32 Coin Slot boxes and view fleet telemetry.
2. **Credits & Plans (`purchase.html`)**: Top up operator credit balance for phone slots.
3. **Flashing & Setup (`install.html`)**: Plug Android device via USB and flash PisoPhone as Device Owner in 1 click.
4. **Activation (`activate.html`)**: Assign device to a cabinet slot and activate with signed QR license.

## Auxiliary Utilities

- **Debloater (`debloater.html`)**: Disable bloatware and background carrier apps via WebADB.
- **Deprovisioning (`deprovision.html`)**: Safely clear Device Owner permissions and uninstall PisoPhone using the Admin PIN.
- **Label Sheet (`box_labels_a4.html`)**: Generate high-resolution print-ready A4 labels with QR codes for physical arcade cabinets.
