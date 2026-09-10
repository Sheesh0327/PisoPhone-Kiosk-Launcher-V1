# Security & Hardware Lockdown Guide

To fully protect your firmware from being cloned or extracted, you must utilize the hardware security features built into the ESP32 (specifically, Flash Encryption and Secure Boot v2).

By combining Flash Encryption with the embedded Offline Licensing mechanism, even if an attacker buys a unit, they will not be able to dump the firmware or extract the `LICENSE_SECRET_KEY`.

## 1. Enabling Flash Encryption

Flash Encryption ensures that the contents of the ESP32's flash memory are encrypted at rest. When the ESP32 boots, the hardware AES engine decrypts the instructions on the fly. 

> **WARNING:** Flash encryption is a one-way process in Production mode. It burns eFuses inside the ESP32. Once locked, you can only flash new firmware if you possess the original signing keys, and you can NEVER disable Flash Encryption again on that specific chip.

### Step-by-Step

1. Open the ESP-IDF terminal.
2. Run `idf.py menuconfig`.
3. Navigate to **Security features**.
4. Set **Enable flash encryption on boot** to Enabled.
5. Select **Enable usage mode (Release)** (IMPORTANT: Release mode permanently locks the encryption keys).
6. Save and exit.
7. Build and flash the firmware: `idf.py build flash monitor`.
8. On the first boot, the ESP32 will take about 30 seconds to encrypt the flash memory. Do not interrupt power during this time.

## 2. Enabling Secure Boot v2

Secure Boot ensures that only firmware signed by your private key can boot on the device.

1. Navigate to **Security features** in `menuconfig`.
2. Enable **Enable Secure Boot in hardware**.
3. Select **Secure Boot V2**.
4. Provide the path to a signing key. If you don't have one, generate it using `espsecure.py generate_signing_key secure_boot_signing_key.pem`.
5. Build and flash the firmware. The bootloader will burn the public key hash into the ESP32 eFuses.

## 3. The Unified Master Cryptographic Secret & Offline Licensing Workflow

In this system, a **256-bit High-Entropy Master Secret Key** (`MASTER_CRYPTO_SECRET`) is unified for BOTH:
1. **Local Network Communication & Coin Spoof Prevention**: HMAC-SHA256 authentication of WebSocket & HTTP telemetry between the Android App and ESP32.
2. **Device Hardware Activation**: HMAC-SHA256 signature calculation over the ESP32's unique MAC address.

### Zero User Visibility Architecture

- **Removed from Android UI**: The secret key field has been completely removed from the Android Security Vault / Admin Console. Kiosk operators or technicians cannot view, inspect, or modify the secret key.
- **Removed from ESP32 Web Portal**: The secret key field has been removed from the ESP32 web configuration interface.
- **Hardware Performance**: The 256-bit key uses native `mbedtls` SHA-256 hardware acceleration on the ESP32 (taking < 10 microseconds per HMAC operation), putting ZERO strain on the hardware.

### Operational Steps

1. Each device gets its own secret, generated securely on the Android kiosk during initial setup and entered once during provisioning via the ESP32 captive config portal.
2. Flash the firmware onto the locked-down ESP32.
3. When a customer powers on an unlicensed unit, the Android App displays the **Device Activation Required** overlay along with the ESP32's MAC Address.
4. The customer sends you their payment, MAC Address (e.g., `12:34:56:78:90:AB`), and Device Provisioning Secret.
5. You run `python keygen.py <MAC_ADDRESS> <DEVICE_SECRET>` to generate their unique 12-character activation key (e.g., `D6246BB8520D`).
6. The customer enters the key into the app. The app sends it to the ESP32 `/activate` endpoint.
7. The ESP32 verifies the HMAC signature natively using its configured device secret. If valid, it burns `licensed = true` into its NVS and immediately enables coin processing.

## 4. ESP32 Slot Memory Expiration & Hard Lockdown Architecture

In multi-seat / per-slot licensing deployments, the ESP32 acts as the authoritative master clock and license governor for all connected Android terminals.

### Slot Expiration & Warning States

- **Nearing Expiration (<= 7 Days)**:
  - During periodic heartbeat exchanges, the ESP32 returns `"slot_warning": true` and `"slot_warning_days_left": N`.
  - The Android kiosk displays an amber banner warning the operator that the slot license is expiring soon, without interrupting kiosk gameplay or coin acceptance.
- **Hard Lockdown (Expired)**:
  - Once a slot's `expiresAt` timestamp is reached in the ESP32's NVS memory, the ESP32 flags the terminal as expired (`"slot_expired": true`).
  - **No Coin Registers**: The ESP32 immediately terminates the terminal's WebSocket connection and rejects any WebSocket arming attempts with `HTTP 423 Locked / SLOT_EXPIRED`. The coin mechanism will never register or credit coins to this terminal.
  - **Terminal Lock Screen**: The Android kiosk transitions into a persistent **Hard Lockdown Screen** (`SLOT LICENSE EXPIRED - HARD LOCKDOWN ACTIVE`), dismissing floating balls and locking out user interaction.
  - **Continuous Heartbeat Reconnect**: The locked terminal continues sending periodic heartbeats to the ESP32. As soon as the operator purchases a new slot license and the ESP32 slot memory is renewed, the ESP32 signals `"slot_expired": false`, and the Android app automatically lifts the lockdown without requiring a device reboot.
