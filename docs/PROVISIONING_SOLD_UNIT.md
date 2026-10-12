# Hardening a box you sell (Secure Boot V2 + flash encryption)

**Who needs this:** only boxes that leave your hands . Boxes in your own shops stay on the normal
build: they are protected by the per-box secret and signed OTA (see `KEYS.md`).

**What it buys:** a buyer cannot read the firmware or the stored secrets with a flash reader, and cannot boot
firmware you did not sign. **What it costs:** the eFuse steps below can **never be undone**. A mistake bricks the
board. Do it on a spare board first and only then on units for sale.

> **Status of this guide.** Written from the ESP-IDF documentation and the behaviour of `espefuse.py`/`espsecure.py`.
> It has **not yet been run on a board**. Steps marked **GATE** must pass on a sacrificial board before the
> procedure is used on a unit you sell. Record the result in the table at the end.

## 0. Decisions made here

| Question | Decision |
|---|---|
| Which chip | **ESP32-C3** for sold units. Original ESP32 supports Secure Boot V2 only from chip revision 3 (check with `esptool.py chip_id`); older revisions only have the weaker V1. Do not sell those hardened. |
| Secure Boot key | New RSA-3072 key, **separate from the firmware-signing key**. Offline, backed up twice. Losing it means no more updates for hardened units; leaking it means anyone can sign firmware for them. |
| Flash encryption mode | **Release** (keys can never be read back, UART flashing of plain firmware stops). Development mode can be undone, so it proves nothing about the finished unit. |
| Download mode | **Secure download mode** (`ENABLE_SECURITY_DOWNLOAD`). Fully disabling download mode (`DIS_DOWNLOAD_MODE`) leaves the owner no way back except a signed OTA; only choose it once OTA recovery has been proven on a hardened board. |
| JTAG | Disable (`DIS_USB_JTAG`, `DIS_PAD_JTAG`; the C3's USB-serial JTAG too). |

## 1. Build problem to solve first (the Arduino caveat) — GATE 1

The ESP32 Arduino libraries that PlatformIO downloads are prebuilt **without** `CONFIG_SECURE_BOOT` and
`CONFIG_SECURE_FLASH_ENC_ENABLED`. The bootloader inside them is therefore not a secure-boot bootloader, and the
app does not know flash encryption is on. Pick one route and prove it:

1. **`pioarduino` platform with `custom_sdkconfig`** (preferred if it builds): set
   `CONFIG_SECURE_BOOT=y`, `CONFIG_SECURE_BOOT_V2_ENABLED=y`, `CONFIG_SECURE_BOOT_V2_RSA_ENABLED=y`,
   `CONFIG_SECURE_BOOT_SIGNING_KEY="…"` is **not** set (sign offline), `CONFIG_SECURE_FLASH_ENC_ENABLED=y`,
   `CONFIG_SECURE_FLASH_ENCRYPTION_MODE_RELEASE=y`, `CONFIG_SECURE_UART_ROM_DL_MODE=y`.
2. **Build the bootloader once with ESP-IDF** using that sdkconfig and commit only the resulting
   `bootloader.bin` under `esp32_firmware/bootloader/` (never the key). The app can stay on the stock Arduino build
   if a signed app boots under that bootloader.

Whichever route: keep `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` and `CONFIG_APP_ROLLBACK_ENABLE=y` (the stock Arduino
libraries have both, and the firmware relies on them: `OtaRollback.cpp`). A bootloader built without them still boots,
but a bad update is then never reverted, and nothing in the build says so. The dashboard's diagnostics show
`ota_image_state`; after an update it reads `pending_verify` for about a minute and then `valid`. If it never leaves
`valid` (it never shows `pending_verify` after an OTA), the bootloader has no rollback.

**GATE 1 passes when:** the bootloader built this way is signed, flashed to a spare board with *no* eFuses burned,
and the box boots, runs a signed OTA, keeps its coin queue across the update, **and reverts a deliberately bad update
(`docs/REAL_WORLD_TESTING.md`, item H4)**.

## 2. One-time setup on your PC

```bash
pip install esptool            # provides esptool.py, espefuse.py, espsecure.py
espsecure.py generate_signing_key --version 2 --scheme rsa3072 secure_boot_signing_key.pem
espsecure.py generate_flash_encryption_key flash_encryption_key.bin   # per unit, see §4
```
Keep both files offline and **out of git** (`check_security_rules.sh` fails the build if a private key is committed).
Use one flash-encryption key **per unit** so one stolen box does not open the others; store `MAC → key` in your
password manager.

## 3. Prepare the firmware

1. Build the release firmware (`pio run -e esp32-c3-production-encrypted`).
2. Sign the bootloader and the app for Secure Boot V2:
   ```bash
   espsecure.py sign_data --version 2 --keyfile secure_boot_signing_key.pem --output app_signed.bin firmware.bin
   espsecure.py sign_data --version 2 --keyfile secure_boot_signing_key.pem --output bootloader_signed.bin bootloader.bin
   ```
3. This is separate from `sign_firmware.py`, which signs the OTA *manifest*. Hardened boxes check both: the
   manifest signature in the app and the Secure Boot signature in the bootloader.

## 4. Per-unit procedure (irreversible from step 3 on)

Work on one board at a time, powered from USB, with the board's MAC written on it.

1. **Read the chip, change nothing:**
   ```bash
   python3 scripts/sold_unit_check.py fuses --port /dev/ttyUSB0
   ```
   Expect: all of `SECURE_BOOT_EN`, `SPI_BOOT_CRYPT_CNT`, `DIS_USB_JTAG`, `ENABLE_SECURITY_DOWNLOAD` clear. If any is set
   the board has been touched before: stop and use a fresh one.
2. Erase and flash the **signed** bootloader, partition table and app (plain, not yet encrypted) — **GATE 2:** the
   box boots and shows its dashboard.
3. **Burn the flash-encryption key** (cannot be read back afterwards; keep your copy):
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY0 flash_encryption_key.bin XTS_AES_128_KEY
   ```
4. **Burn the Secure Boot key digest** (the C3 has room for three; use `BLOCK_KEY1`):
   ```bash
   espsecure.py digest_sbv2_public_key --keyfile secure_boot_signing_key.pem --output sb_digest.bin
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY1 sb_digest.bin SECURE_BOOT_DIGEST0
   ```
5. **Turn it on, then lock the doors** (read each summary line before confirming):
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_efuse SECURE_BOOT_EN
   espefuse.py -p /dev/ttyUSB0 burn_efuse SPI_BOOT_CRYPT_CNT 7
   espefuse.py -p /dev/ttyUSB0 burn_efuse DIS_USB_JTAG DIS_PAD_JTAG DIS_DIRECT_BOOT DIS_DOWNLOAD_MANUAL_ENCRYPT
   espefuse.py -p /dev/ttyUSB0 burn_efuse ENABLE_SECURITY_DOWNLOAD
   ```
   (Name checks: run `espefuse.py summary` first; names differ between chips and `esptool` versions. If one is
   missing on your version, stop and look it up rather than guessing.)
6. Power-cycle. The bootloader encrypts the flash in place on first boot (about a minute on the C3). Do not
   remove power.
7. From now on, firmware is installed only through signed OTA, or by writing images you **encrypted on the PC**:
   ```bash
   espsecure.py encrypt_flash_data --aes_xts --keyfile flash_encryption_key.bin --address 0x20000 \
       --output app_enc.bin app_signed.bin
   ```

## 5. Verification — GATE 3 (this is what "done" means)

```bash
esptool.py -p /dev/ttyUSB0 read_flash 0x20000 0x2000 app_dump.bin    # may be refused in secure download mode: that is fine
python3 scripts/sold_unit_check.py flash app_dump.bin                # must say: ciphertext
python3 scripts/sold_unit_check.py fuses --port /dev/ttyUSB0 --expect-locked
```
Also dump the **NVS region** (`0x9000`, 0x6000 bytes) and search it for the box's secret
(`python3 scripts/sold_unit_check.py flash nvs_dump.bin --find "<text>"`). **If the text appears in plain**, the
NVS partition is not covered by flash encryption on this build; the fix is NVS encryption (`nvs_flash_secure_init`
with a key partition), a firmware change that must be made before selling hardened units.

Then check from the dashboard: a signed OTA update works, an unsigned one is refused, the coin queue survives a
power cut, and a factory reset still works.

## 6. If it goes wrong

- Boot loop after step 5: the bootloader or app was not signed with the key whose digest you burned. The board
  cannot be recovered; this is why you practise first.
- Lost the Secure Boot key: units keep running but can never take an update again.
- A customer's unit fails: it cannot be re-flashed by serial except with images encrypted for *that* unit's key.
  Ship a replacement board rather than promising repair.

## 7. Practice log (fill in before selling)

| Date | Board / revision | GATE 1 | GATE 2 | GATE 3 | NVS plaintext? | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
