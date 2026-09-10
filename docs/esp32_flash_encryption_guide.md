# ESP32 Production Flash Encryption Guide (PlatformIO)

This directory contains two distinct PlatformIO build environments:

1. **`esp32-c3-dev` / `esp32dev-dev` (DEFAULT)**
   - Used for development, testing, and debugging.
   - Flash encryption is **DISABLED**.
   - You can flash, erase, and re-flash freely via USB.

2. **`esp32-c3-production-encrypted` / `esp32dev-production-encrypted` (PRODUCTION)**
   - Used ONLY when you are 100% ready for final device deployment.
   - Uses `partitions_encrypted.csv` with encryption flags on the application partition.
   - Configured to lock down flash memory so the firmware binary and master cryptographic secrets cannot be read using external hardware readers.

---

## How to Switch to Production Encrypted Mode in VS Code

### Step 1: Select the Environment in PlatformIO

In VS Code:
1. Open the **PlatformIO** icon on the left sidebar (the alien icon 👽).
2. Look at the **PROJECT TASKS** tree view.
3. Collapse `env:esp32-c3-dev` (Development).
4. Expand **`env:esp32-c3-production-encrypted`** (or `env:esp32dev-production-encrypted` if using standard ESP32).
5. Click **Build** to verify the production build compiles cleanly.

Alternatively, from the terminal inside `esp32_firmware/`:
```bash
# Build for ESP32-C3 Production
pio run -e esp32-c3-production-encrypted

# Or build for standard ESP32 DevKit Production
pio run -e esp32dev-production-encrypted
```

---

## Step 2: One-Time Hardware Key Setup (Burning eFuses)

To permanently activate hardware flash encryption on a new ESP32 board:

1. Connect the ESP32 board via USB.
2. Open a terminal and generate a random 256-bit key on your host PC:
   ```bash
   espsecure.py generate_flash_encryption_key flash_encryption_key.bin
   ```
3. Burn the key into the ESP32 eFuse block (Replace `COM3` / `/dev/ttyUSB0` with your serial port):
   ```bash
   espefuse.py -p COM3 burn_key flash_encryption_key1 flash_encryption_key.bin FLASH_CRYPT
   ```
4. Enable Flash Encryption on boot:
   ```bash
   espefuse.py -p COM3 burn_efuse FLASH_CRYPT_CNT 1
   ```

---

## Step 3: Flash the Firmware (Complete Flash Wipe)

`platformio.ini` is configured with `board_upload.erase_flash = yes`, which automatically forces `esptool` to perform a **full chip erase** (wiping NVS, partitions, WiFi settings, and prior code) before flashing the new firmware binary.

Upload the compiled production firmware using PlatformIO:
```bash
pio run -e esp32-c3-production-encrypted -t upload
```

Alternatively, to manually perform a complete wipe via `esptool.py` before flashing:
```bash
# 1. Manually erase full chip
esptool.py --port COM3 erase_flash

# 2. Upload firmware
pio run -e esp32-c3-production-encrypted -t upload
```

On the very first boot, the ESP32 hardware bootloader will automatically encrypt all internal program flash in-place. From this point forward, the firmware binary, memory, and master keys are unreadable to external physical tools.
