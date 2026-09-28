# 🎮 Phone Rental & Coin-Operated Gaming Kiosk

A turnkey, enterprise-grade Android Kiosk Launcher and Hardware Timer system designed for coin-operated gaming phones, rental stations, and arcade setups. Features hardware-level screen lockdown, ESP32 coin slot integration, smart battery protection, per-device encrypted cryptographic provisioning, and offline HMAC-SHA256 hardware licensing.

---

## 📑 Table of Contents
1. [System Architecture & Workflow](#-system-architecture--workflow)
2. [Hardware Lock & Device Provisioning](#-hardware-lock--device-provisioning)
3. [Website & Zero-Cost Deployment Architecture](#-website--zero-cost-deployment-architecture)
4. [Device Provisioning & Device Owner Setup](#-device-provisioning--device-owner-setup)
5. [ESP32 Hardware Coin Slot Integration](#-esp32-hardware-coin-slot-integration)
6. [Core Android Subsystems](#-core-android-subsystems)
   - [Lock Screen & Floating HUD Overlays](#lock-screen--floating-hud-overlays)
   - [Hardware Controls (Brightness, Volume & Memory Boost)](#hardware-controls)
   - [Battery Health & Smart Audio/TTS Alerts](#battery-health--smart-audiotts-alerts)
   - [Watchdog & Crash Recovery](#watchdog--crash-recovery)
7. [Admin Security Vault & Operator Controls](#-admin-security-vault--operator-controls)

---

## 🔄 System Architecture & Workflow

```
+-----------------------------------------------------------------------------------+
|                              1. PROVISIONING & FLASH                              |
|  Factory-Reset Device  -->  WebUSB / ADB Flasher  -->  Install APK as Device Owner |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                              2. MANDATORY ONBOARDING                              |
|  - Force Change Default Admin PIN away from 1234                                  |
|  - Generate Unique 256-bit Per-Device Secret in EncryptedSharedPreferences        |
|  - Provision Secret to ESP32 Firmware via Captive Portal (Port 80)                |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                              3. SECURE KIOSK LAUNCHER                             |
|  - System Bars Hidden & Home Button Locked (Device Owner / LockTask)              |
|  - Fullscreen Lock Overlay blocks apps until payment                               |
|  - NanoHTTPD Local Server starts on port 8080                                     |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                              4. ESP32 COIN INSERTION                              |
|  Coin dropped  -->  ESP32 requests single-use HMAC challenge                      |
|  --> HTTP GET /coin validated via SecureRandom nonce & HMAC-SHA256                |
|  --> Session Timer Starts  -->  Fullscreen Lock unlocks to Floating HUD Pill      |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                              5. ACTIVE GAMEPLAY & EXPIRY                          |
|  - Floating HUD allows Game Launch, Volume/Brightness Adjust, RAM Booster          |
|  - Timer reaches 00:00  -->  Lock Screen re-engages and minimizes active apps    |
|  - Battery monitoring triggers TTS Voice & Siren warnings if unplugged/overcharged|
+-----------------------------------------------------------------------------------+
```

---

## 🔑 Hardware Lock & Device Provisioning

The kiosk integrates a multi-layered security and hardware authorization architecture:

### 1. Per-Device Cryptographic Secret
- Each device generates its own high-entropy 256-bit cryptographically secure secret (`EncryptedSharedPreferences` backed by the Android Keystore).
- There are **no hardcoded master secrets** in the client app or firmware.
- The secret is displayed once during the mandatory initial provisioning flow so the technician can pair it with the corresponding ESP32 coin controller.

### 2. Mandatory First-Run Provisioning
- The kiosk enforces changing the default Admin PIN away from `1234` before any normal kiosk operations can proceed.
- The ESP32 captive portal similarly blocks coin processing and arming until default Wi-Fi and admin credentials (`Admin@123` / `admin`) are changed.

### 3. Hardware Lock & Activation
- Unlicensed hardware is locked down until authorized via `HardwareLockManager`.
- The ESP32 reports its MAC address, and an authorized activation key generated with `python esp32_firmware/tools/keygen.py <MAC_ADDRESS> <DEVICE_SECRET>` unlocks coin processing and burns the state into NVS.

### 4. Admin CLI Key Generator (`esp32_firmware/tools/keygen.py`)
To generate an activation key for any paired unit:
```bash
python esp32_firmware/tools/keygen.py <MAC_ADDRESS> <DEVICE_SECRET>
```
**Example:**
```text
================================================
PISOPHONE ACTIVATION CODE GENERATOR
================================================
MAC Address   : 24:D7:EB:12:34:56
Activation Code: D6246BB8520D
================================================
```

---

## 🌐 Website & Zero-Cost Deployment Architecture

You can distribute the app and automate annual renewals with **$0/month in hosting costs** using the following recommended architecture:

```
+-------------------------------------------------------------------------------------+
| GITHUB PAGES (Static Frontend - Free)                                               |
| - Serves WebUSB / WebADB interface to flash factory-reset phones over USB cable.    |
| - Provides a clean Customer Portal where users enter their Device ID.               |
| - Downloads APK from GitHub Releases (Free storage up to 2GB).                      |
+-------------------------------------------------------------------------------------+
                                         │  (POST Device ID via HTTPS)
                                         ▼
+-------------------------------------------------------------------------------------+
| CLOUDFLARE WORKER / VERCEL FUNCTION (Serverless Backend - Free Tier)                |
| - Holds the private MASTER_SECRET securely in environment variables.               |
| - Runs the AES-256 encryption algorithm.                                            |
| - Generates a 1-year expiration timestamp.                                          |
| - Returns the License Key and rendered QR Code (SVG/PNG) back to the browser.      |
+-------------------------------------------------------------------------------------+
                                         │  (Display QR Code on screen)
                                         ▼
+-------------------------------------------------------------------------------------+
| ANDROID PHONE CAMERA                                                                |
| - Customer clicks "Scan QR Code" inside the app to instantly activate license.      |
+-------------------------------------------------------------------------------------+
```

---

## 📲 Device Provisioning & Device Owner Setup

For a truly tamper-proof arcade machine where players cannot exit to Android settings, uninstall apps, or disable kiosk policies, the app should be installed as **Device Owner** on a clean, factory-reset phone.

### Step-by-Step Provisioning Guide
1. **Factory Reset** the Android phone.
2. In the initial setup wizard (Welcome Screen), **do not add a Google Account or Wi-Fi**.
3. Tap the **Build Number** 7 times in *Settings > About Phone* to unlock **Developer Options**.
4. Enable **USB Debugging**.
5. Connect the phone to your PC / WebUSB installer and run:
```bash
adb install -r -g app-release.apk
adb shell dpm set-device-owner com.pisophone.kiosk/com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver
```
6. Grant Overlay and System Settings permissions:
```bash
adb shell appops set com.pisophone.kiosk SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.pisophone.kiosk android.permission.WRITE_SETTINGS
```

---

## ⚡ ESP32 Hardware Coin Slot Integration

The Android Kiosk hosts a lightweight, local HTTP server (`NanoHTTPD`) on port **8080**. The ESP32-C3 microcontroller connects to the same local Wi-Fi / Hotspot and submits coin pulses securely.

### Security Challenge-Response Protocol
To prevent players from sending fake coin packets using network sniffing apps:
1. **Challenge Request:** ESP32 sends `GET /challenge` (or `/heartbeat_challenge`).
2. **Challenge Response:** Android app returns a unique, single-use 32-character random nonce.
3. **HMAC Signing:** ESP32 computes an HMAC-SHA256 signature of the challenge using the `sharedSecret`.
4. **Coin Registration:** ESP32 sends `GET /coin?challenge=<NONCE>&signature=<HMAC>`.
5. **Validation & Credits:** If signature matches and nonce is fresh, credits and session time are added immediately.

### ESP32-C3 Firmware Features
- **Built-in Captive Config Portal (Port 80):** Host IP, Wi-Fi credentials, and Shared Secret can be configured via any phone browser without re-flashing Arduino code.
- **Hardware Fallback Factory Reset (GPIO 2):**
  - **Ground Pin for 5 Seconds:** Shorting **GPIO 2 to Ground (GND) for 5 seconds** wipes NVS storage and restores all settings to default values at any time (no complex APs or unstable timers).
  - **Visual Confirmation:** Status LED rapidly strobes 10 times to confirm factory reset.
  - **Default Reverted Values:** SSID: `AdminSetup`, Password: `Admin@123`, Admin Web Password: `admin`, Port: `8080`.
- **Hardware Debounced Coin Pin:** Accurately reads pulses from standard arcade multi-coin selectors.
- **5-Second Watchdog Heartbeat:** Reports real-time hardware online/offline status to the Android UI.

---

## 🛠️ Core Android Subsystems

### Lock Screen & Floating HUD Overlays
- **`LockScreenOverlay.kt`**: High-priority fullscreen overlay (`TYPE_APPLICATION_OVERLAY`) that covers the entire display, notification shade, and navigation bar when session time is 0.
- **`FloatingBallOverlay.kt`**: Minimized floating circular timer badge during active play. Tapping expands a rich Game Space HUD featuring:
  - Remaining session countdown timer.
  - Insert Coin shortcut & Rate display.
  - Active battery level & Wi-Fi status indicators.
  - Memory (RAM) optimizer button.
  - Full-range Brightness and Volume sliders.

### Hardware Controls
- **True 100% Display Brightness Override:** Modern Android devices scale brightness logarithmically or use high vendor integer ranges (e.g. 0–4095). The app queries `config_screenBrightnessSettingMaximum` dynamically and feeds a direct `0.0f..1.0f` ratio into `WindowManager.LayoutParams.screenBrightness`.
- **Hardware Volume Stream Control:** Adjusts `AudioManager.STREAM_MUSIC` directly with one-tap instant mute/unmute toggle.
- **RAM Optimization:** Reads real-time `ActivityManager.MemoryInfo()` and terminates background cache processes via `killBackgroundProcesses`.

### Battery Health & Smart Audio/TTS Alerts
To protect the phone's battery longevity in commercial rental enclosures:
- **Low Battery (< 20% & Unplugged):** Android TTS speaks *"Warning: Battery low. Please connect charger."* alongside an audible multi-tone siren and repeating vibration cadence.
- **Full Battery (> 80% & Plugged):** Android TTS speaks *"Battery charging complete. Please unplug charger to protect battery health."* accompanied by a double notification chime.
- **Cable Plugged/Unplugged Feedback:** Plays instant high/low confirmation tones.

### Watchdog & Crash Recovery
- **`KioskWatchdogReceiver.kt`**: Schedules an exact periodic alarm to check service health. If `KioskService` is killed by the OS, it is immediately restarted.
- **`CrashReporter.kt`**: Intercepts uncaught exceptions and logs diagnostic stack traces locally to prevent silent app crashes.

---

## 🔒 Admin Security Vault, Hardware Lock & Anti-Clone Protection

### 🛡️ First-Install Hardware Lock (Anti-Copy Protection)
When the app is installed and launched onto your phone from AI Studio:
1. **Hardware Seal Generation:** The app computes a unique SHA-256 hardware signature derived from immutable physical identifiers (`ANDROID_ID`, `Build.BOARD`, `Build.BRAND`, `Build.DEVICE`, `Build.HARDWARE`, `Build.MANUFACTURER`, `Build.MODEL`, `Build.PRODUCT`, `Build.FINGERPRINT`).
2. **Cryptographic Binding:** It seals the hardware token into local storage.
3. **Anti-Clone / Anti-Copy Enforcement:**
   - If anyone extracts the APK (via APK Extractor, Bluetooth, SHAREit, ADB pull, or app cloning tools) and installs it on another phone, the hardware signature check fails immediately.
   - On unauthorized phones, the app displays a non-dismissible **"🔒 HARDWARE LOCK ACTIVE"** screen, showing the hardware mismatch diagnostics and blocking launcher access, coin insertion, and overlay services.
   - **Admin Re-Authorization:** The legitimate owner can re-bind the hardware signature to a replacement device at any time by entering the Master Admin PIN.

### Operator Console Features
To enter the operator menu, tap the hidden gear icon or enter the master PIN:
- **Default PIN:** `1234`
- **Configurable Parameters:**
  - **Hardware Lock Status & Re-Seal:** View bound vs current device hardware fingerprints with one-tap re-seal.
  - Price per coin & Minutes awarded per coin.
  - Idle Payment Timeout duration.
  - ESP32 Shared Secret key.
  - Battery alert percentage thresholds & TTS voice toggle.
  - UI Theme selection (Cyberpunk Neon, Emerald Green, AMOLED Dark, Crimson Red, Royal Blue).
  - Launch/Exit Kiosk LockTask mode.

---

## 📂 Project Directory Structure

The repository is organized into distinct functional modules:

```text
.
├── app/                  # Android Kiosk Application (Jetpack Compose, Room, NanoHTTPD)
│   ├── src/main/java/    # Clean architecture modules (ui, overlay, security, network, db, etc.)
│   └── src/main/res/     # Resources, drawables, strings, and device admin configurations
├── esp32_firmware/       # ESP32-C3 Master Hardware Firmware (PlatformIO / Arduino)
│   ├── src/              # Modular C++ implementation files (Config, DeviceManager, Security, etc.)
│   ├── include/          # Modular C++ headers
│   ├── envs/             # Environment configs (dev & prod for ESP32-C3 and ESP32 DevKit)
│   ├── data/             # LittleFS captive portal web assets
│   └── tools/            # Activation key generator CLI (keygen.py)
├── website/              # Web Portal, Flasher & Cloudflare Worker Backend
│   ├── js/               # WebADB driver & authentication scripts
│   ├── worker/           # Cloudflare Worker code for licensing
│   └── installer/        # Browser-based device installer
└── docs/                 # Engineering and Deployment Documentation
    ├── esp32_flash_encryption_guide.md  # Production flash encryption lockdown
    ├── esp32_security_lockdown.md       # Hardware anti-tamper security guidelines
    └── github_actions_setup.md          # CI/CD automated build instructions
```

---

## 📦 Build & Compilation Details

- **Language:** Kotlin 2.2.10
- **UI Framework:** Jetpack Compose (Material 3)
- **Local Database:** Room Database with KSP
- **Target SDK:** 36 (Android 15 ready)
- **Min SDK:** 24 (Android 7.0+)
- **Compile Verification:** Verified with zero build errors.
