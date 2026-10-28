# Hardening a box you sell (Secure Boot V2 + flash encryption)

**Who needs this:** only boxes that leave your hands . Boxes in your own shops stay on the normal
build: they are protected by the per-box secret and signed OTA (see `KEYS.md`).

**What it buys:** a buyer cannot read the firmware or the stored secrets with a flash reader, and cannot boot
firmware you did not sign. **What it costs:** the eFuse steps below can **never be undone**. A mistake bricks the
board. Do it on a spare board first and only then on units for sale.

> **Status of this guide.** Written from the ESP-IDF documentation and the behaviour of `espefuse.py`/`espsecure.py`.
> It has **not yet been run on a board**. Steps marked **GATE** must pass on a sacrificial board before the
> procedure is used on a unit you sell. Record the result in the table at the end.

> **Command spelling.** The commands below use the underscore names (`burn_efuse`, `sign_data`, `read_flash`). esptool 5.x
> (what `pip install esptool` gives today) renamed them with dashes (`burn-efuse`, `sign-data`, `read-flash`, option `--aes-xts`)
> and keeps the underscore forms working with a deprecation warning that says they will go in the next major release.
> If a command is "not found", try the dash form. *(Checked 2026-10-28 against esptool v5.1.0 `docs/en/migration-guide.rst`
> and v4.10.0; the commands themselves were not run.)*

## 0. Decisions made here

| Question | Decision |
|---|---|
| Which chip | **ESP32-C3** for sold units. Original ESP32 supports Secure Boot V2 only from chip revision 3 (check with `esptool.py chip_id`); older revisions only have the weaker V1. Do not sell those hardened. The **ESP32-C3** needs chip revision **v0.3 or newer** for Secure Boot V2: check the revision `esptool.py chip_id` prints before you spend a board. *(Added 2026-10-28 against ESP-IDF v5.4.2, `secure-boot-v2`, "Secure Boot v2 is available for ESP32-C3 from chip revision v0.3 (ECO3) onwards".)* |
| Secure Boot key | New RSA-3072 key, **separate from the firmware-signing key**. Offline, backed up twice. Losing it means no more updates for hardened units; leaking it means anyone can sign firmware for them. |
| Flash encryption mode | **Release** (keys can never be read back, UART flashing of plain firmware stops). Development mode can be undone, so it proves nothing about the finished unit. |
| Download mode | **Secure download mode** (`ENABLE_SECURITY_DOWNLOAD`). Fully disabling download mode (`DIS_DOWNLOAD_MODE`) leaves the owner no way back except a signed OTA; only choose it once OTA recovery has been proven on a hardened board. |
| JTAG | Disable with `DIS_USB_JTAG` (stops the USB port switching to JTAG) and `DIS_PAD_JTAG` (pad JTAG, permanent). Both names are right for the C3. Do **not** also burn `DIS_USB_SERIAL_JTAG` or `DIS_USB_SERIAL_JTAG_DOWNLOAD_MODE` unless you mean to remove the USB serial port as well; that is a separate decision. *(Clarified 2026-10-28 against esptool v4.10.0 / v5.1.0 `esp32c3.yaml` and ESP-IDF v5.4.2 `security-features-enablement-workflows`; the effect of the two USB-serial fuses is reasoned from their esptool descriptions, not tested.)* |

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

0. **Get the files without PlatformIO:** run the workflow "Coin box images for the setup program" by hand (Actions tab, Run workflow, branch of your
   choice, **bundle** on). It builds the dev and the production environments, checks every partition table (boot_app0 must fit inside otadata,
   every partition flagged encrypted, the app where the offsets say) and puts two zips on the branch `firmware-bundles`:
   `PisoPhone-firmware-dev-<version>.zip` and `PisoPhone-firmware-release-<version>.zip` (plain files, offsets, SHA256SUMS, and this guide; no keys).
   The branch holds one commit that every run replaces.
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
5. **Lock the doors** (read each summary line before confirming). **Do NOT burn `SPI_BOOT_CRYPT_CNT` here.**
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_efuse SECURE_BOOT_EN
   espefuse.py -p /dev/ttyUSB0 burn_efuse DIS_USB_JTAG 1 DIS_PAD_JTAG 1 DIS_DIRECT_BOOT 1 DIS_DOWNLOAD_MANUAL_ENCRYPT 1
   espefuse.py -p /dev/ttyUSB0 burn_efuse ENABLE_SECURITY_DOWNLOAD
   ```
   - *Corrected 2026-10-28 against ESP-IDF v5.4.2 (see `specs/features/040-sold-box-hardening/efuse-review.md`, items 1 and 2).*
     This step used to burn `SPI_BOOT_CRYPT_CNT 7` while the flash still held plain images. That counter **is** the "flash is
     encrypted" flag: once an odd number of its bits is set, the chip treats the flash as ciphertext, so a plain bootloader
     is unreadable and the board does not boot (ESP-IDF `flash-encryption`, "Possible Failures": `invalid header`). On the
     first boot the **bootloader** encrypts the flash and **then** sets the counter itself (`flash-encryption`, "Encryption
     process", steps 2 to 7), and in Release mode it write-protects it. Burning the counter by hand is only right in
     ESP-IDF's other, all-by-hand workflow, where the flash is erased and only images you already encrypted on the PC are
     written (`security-features-enablement-workflows`, "Enable Flash Encryption Externally"). This guide uses the
     bootloader-does-it route, so you must not burn it.
   - Several names in one `burn_efuse` are **name value pairs**, so each needs its `1`. Without the values the tool refuses to
     run, and it refuses before burning. A single name alone (as in the first and third lines) is fine for these one-bit eFuses.
     *(Checked in the espefuse source, v4.10.0 and v5.1.0.)*
   - **After `ENABLE_SECURITY_DOWNLOAD` is burned, `espefuse.py` stops working on this chip** ("Secure Download Mode is
     enabled. The tool can not read eFuses.") and `esptool` can only write flash. Keep it last, and do not expect
     `espefuse.py summary` to show you the result of the first boot. Use `esptool.py get_security_info` for that (section 5).
   - **Not established:** ESP-IDF documents two complete routes, everything done by the first-boot bootloader, or everything
     done by hand with images encrypted on the PC. The mix in this step (you burn the lines above, the bootloader then
     encrypts) is not shown in either, and is what the practice board is for. Stop if the board does not behave as step 6 says.
   - Name checks: run `espefuse.py summary` first (before this step; it cannot run afterwards). Names differ between chips and
     `esptool` versions. All the names above exist for the C3 in esptool v4.10.0 and v5.1.0. If one is missing on your
     version, stop and look it up rather than guessing.
6. Power-cycle. On first boot the bootloader encrypts the flash in place (about a minute on the C3) and **only then** burns
   `SPI_BOOT_CRYPT_CNT`; it sets the **first** bit, so it reads **1**, not 7 (the tools show both as "Enable"). In Release mode
   it also write-protects the counter. Do not remove power: if power is cut during the pass the flash is corrupted and needs
   plain images written again (ESP-IDF `flash-encryption`, "Flash Encryption Process" and the warning under it).
   *(Corrected 2026-10-28 against ESP-IDF v5.4.2.)*
7. From now on, firmware is installed only through signed OTA, or by writing images you **encrypted on the PC**:
   ```bash
   espsecure.py encrypt_flash_data --aes_xts --keyfile flash_encryption_key.bin --address 0x20000 \
       --output app_enc.bin app_signed.bin
   ```

## 5. Verification — GATE 3 (this is what "done" means)

```bash
esptool.py -p /dev/ttyUSB0 read_flash 0x20000 0x2000 app_dump.bin    # may be refused in secure download mode: that is fine
python3 scripts/sold_unit_check.py flash app_dump.bin                # must say: ciphertext
esptool.py -p /dev/ttyUSB0 get_security_info                         # works in secure download mode
python3 scripts/sold_unit_check.py fuses --port /dev/ttyUSB0 --expect-locked    # will fail on a locked unit, see below
```
*(Corrected 2026-10-28 against esptool v4.10.0 / v5.1.0 and ESP-IDF v5.4.2.)* `sold_unit_check.py fuses` runs
`espefuse.py summary`, and `espefuse.py` refuses to run once secure download mode is on, so on a finished unit that last
command cannot give the answer. Read the result from `get_security_info` instead: it must show Secure Boot enabled,
flash encryption enabled (count odd), secure download enabled, and JTAG disabled. `sold_unit_check.py` needs a fix for
this before GATE 3 can be scripted (see `efuse-review.md`, item 7); until then, read it by eye.
Also dump the **NVS region** (`0x9000`, 0x5000 bytes) and search it for the box's secret
(`python3 scripts/sold_unit_check.py flash nvs_dump.bin --find "<text>"`). **Expect it to appear in plain**:
ESP-IDF says the `nvs` partition cannot be flash-encrypted because the NVS library is not directly compatible with flash
encryption (v5.4.2 `flash-encryption`, "Encrypted Partition Flag"; *noted 2026-10-28*). If it does, the fix is NVS encryption (`nvs_flash_secure_init`
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
