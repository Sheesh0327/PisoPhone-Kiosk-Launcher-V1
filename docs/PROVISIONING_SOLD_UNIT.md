# Hardening a box you sell (Secure Boot V2 + flash encryption)

**Who needs this:** only boxes that leave your hands. Boxes in your own shops stay on the normal
build: they are protected by the per-box secret and signed OTA (see `KEYS.md`). **Hardening is an option, never a
requirement (owner rule D30):** the legacy, field-proven build stays the default and stays selectable, and nothing in this
guide changes it.

**What it buys:** a buyer cannot read the firmware or the stored secrets with a flash reader, and cannot boot
firmware you did not sign. **What it costs:** the eFuse steps below can **never be undone**. A mistake bricks the
board. Do it on a spare board first and only then on units for sale.

> **Status of this guide.** Written from the ESP-IDF documentation and the behaviour of `espefuse.py`/`espsecure.py`.
> It has **not yet been run on a board**. *(Rewritten 2026-10-28 for D28: you now burn only the two keys and the bootloader's
> first boot does the rest, which is the flow ESP-IDF documents. Sources: ESP-IDF v5.4.2 `docs/en/security/flash-encryption.rst`,
> `secure-boot-v2.rst`, and the bootloader source in `components/bootloader_support/` at the same tag. Still not run on a board.)* Steps marked **GATE** must pass on a sacrificial board before the
> procedure is used on a unit you sell. Record the result in the table at the end.

> **Command spelling.** The commands below use the underscore names (`burn_key`, `sign_data`, `read_flash`). esptool 5.x
> (what `pip install esptool` gives today) renamed them with dashes (`burn-key`, `sign-data`, `read-flash`, option `--aes-xts`)
> and keeps the underscore forms working with a deprecation warning that says they will go in the next major release.
> If a command is "not found", try the dash form. *(Checked 2026-10-28 against esptool v5.1.0 `docs/en/migration-guide.rst`
> and v4.10.0; the commands themselves were not run.)*

## 0. Decisions made here

| Question | Decision |
|---|---|
| Which chip | **ESP32-C3** for sold units. Original ESP32 supports Secure Boot V2 only from chip revision 3 (check with `esptool.py chip_id`); older revisions only have the weaker V1. Do not sell those hardened. The **ESP32-C3** needs chip revision **v0.3 or newer** for Secure Boot V2: check the revision `esptool.py chip_id` prints before you spend a board. *(Added 2026-10-28 against ESP-IDF v5.4.2, `secure-boot-v2`, "Secure Boot v2 is available for ESP32-C3 from chip revision v0.3 (ECO3) onwards".)* |
| Secure Boot key | New RSA-3072 key, **separate from the firmware-signing key**. Offline, backed up twice. Losing it means no more updates for hardened units; leaking it means anyone can sign firmware for them. |
| Flash encryption mode | **Release** (keys can never be read back, UART flashing of plain firmware stops). Development mode can be undone, so it proves nothing about the finished unit. |
| Download mode | **Secure download mode** (`ENABLE_SECURITY_DOWNLOAD`), set by the bootloader on its first boot. Fully disabling download mode (`DIS_DOWNLOAD_MODE`) leaves the owner no way back except a signed OTA; only choose it once OTA recovery has been proven on a hardened board. **Never burn it by hand, and never build with `CONFIG_SECURE_DISABLE_ROM_DL_MODE`** (section 1): that option is the only thing that makes the bootloader burn `DIS_DOWNLOAD_MODE`. *(Changed 2026-10-28 for D28. Checked in ESP-IDF v5.4.2 `components/bootloader_support/src/esp32c3/secure_boot_secure_features.c`: with `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE` it calls `esp_efuse_enable_rom_secure_download_mode()`, which burns only `ENABLE_SECURITY_DOWNLOAD` (`components/efuse/esp32c3/esp_efuse_fields.c`). `DIS_DOWNLOAD_MODE` is burned only under `CONFIG_SECURE_DISABLE_ROM_DL_MODE`. In `components/bootloader/Kconfig.projbuild` the secure choice is the default in Release mode.)* |
| JTAG | `DIS_USB_JTAG` (stops the USB port switching to JTAG) and `DIS_PAD_JTAG` (pad JTAG, permanent) are set **by the bootloader** on first boot, together with the software JTAG-disable bits; you do not burn them. Both names are right for the C3. The bootloader does not touch `DIS_USB_SERIAL_JTAG` or `DIS_USB_SERIAL_JTAG_DOWNLOAD_MODE` (they are not in its list), and you must not burn them unless you mean to remove the USB serial port as well; that is a separate decision. *(Changed 2026-10-28 for D28, checked in ESP-IDF v5.4.2 `esp32c3/secure_boot_secure_features.c` and `esp32c3/flash_encryption_secure_features.c`. Clarified 2026-10-28 against esptool v4.10.0 / v5.1.0 `esp32c3.yaml` and ESP-IDF v5.4.2 `security-features-enablement-workflows`; the effect of the two USB-serial fuses is reasoned from their esptool descriptions, not tested.)* |

## 1. Build problem to solve first (the Arduino caveat) — GATE 1

The ESP32 Arduino libraries that PlatformIO downloads are prebuilt **without** `CONFIG_SECURE_BOOT` and
`CONFIG_SECURE_FLASH_ENC_ENABLED`. The bootloader inside them is therefore not a secure-boot bootloader, and the
app does not know flash encryption is on. Pick one route and prove it:

1. **`pioarduino` platform with `custom_sdkconfig`** (preferred if it builds): set
   `CONFIG_SECURE_BOOT=y`, `CONFIG_SECURE_BOOT_V2_ENABLED=y`, `CONFIG_SECURE_BOOT_V2_RSA_ENABLED=y`
   (that one follows from the one before on the C3, but check it is there), `CONFIG_SECURE_FLASH_ENC_ENABLED=y`,
   `CONFIG_SECURE_FLASH_ENCRYPTION_MODE_RELEASE=y` and `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE=y`.
   Sign offline: `CONFIG_SECURE_BOOT_BUILD_SIGNED_BINARIES` is **not** set, so `CONFIG_SECURE_BOOT_SIGNING_KEY` is not set either.
   These must **not** be set: `CONFIG_SECURE_DISABLE_ROM_DL_MODE` (it makes the bootloader burn `DIS_DOWNLOAD_MODE`, see section 0),
   `CONFIG_SECURE_BOOT_ALLOW_JTAG`, `CONFIG_SECURE_BOOT_V2_ALLOW_EFUSE_RD_DIS`, `CONFIG_SECURE_BOOT_INSECURE`,
   `CONFIG_SECURE_FLASH_REQUIRE_ALREADY_ENABLED`. With these options the bootloader does the whole hardening **on its first boot**
   (section 4, step 6). *(Changed 2026-10-28 for D28. The old list had `CONFIG_SECURE_UART_ROM_DL_MODE=y`: that name is the title of a
   choice in ESP-IDF's menu, not a setting, so it never appears in an sdkconfig and the check in `bench.md` B2 could never have found it. The setting
   is `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE`. Checked in ESP-IDF v5.4.2 `components/bootloader/Kconfig.projbuild`: `choice SECURE_UART_ROM_DL_MODE`
   with `SECURE_ENABLE_SECURE_ROM_DL_MODE` as the Release-mode default, `SECURE_BOOT_V2_RSA_ENABLED` defaulting to yes once V2 is on,
   `SECURE_BOOT_BUILD_SIGNED_BINARIES` defaulting to yes, and the "insecure" options depending on Development mode or `SECURE_BOOT_INSECURE`; and
   `docs/en/security/secure-boot-v2.rst`, "How To Enable Secure Boot v2" steps 1 to 10 and "How To Enable Signed App Verification" step 5 on manual signing.
   Whether a bootloader built with `BUILD_SIGNED_BINARIES` off needs any further setting is reasoned, not read: the first build will show it.)*
2. **Build the bootloader once with ESP-IDF** using that sdkconfig and commit only the resulting
   `bootloader.bin` under `esp32_firmware/bootloader/` (never the key). The app can stay on the stock Arduino build
   if a signed app boots under that bootloader.

Whichever route: keep `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` and `CONFIG_APP_ROLLBACK_ENABLE=y` (the stock Arduino
libraries have both, and the firmware relies on them: `OtaRollback.cpp`). A bootloader built without them still boots,
but a bad update is then never reverted, and nothing in the build says so. The dashboard's diagnostics show
`ota_image_state`; after an update it reads `pending_verify` for about a minute and then `valid`. If it never leaves
`valid` (it never shows `pending_verify` after an OTA), the bootloader has no rollback.

**GATE 1 passes when:** the box boots, runs a signed OTA, keeps its coin queue across the update, **and reverts a
deliberately bad update (`docs/REAL_WORLD_TESTING.md`, item H4)**, on a spare board with *no* eFuses burned, running the
bootloader the boxes already use (it has the same two rollback options). The hardened bootloader cannot be tried on an unburned
board: its first boot is the irreversible step (section 4), so the same checks are repeated on it afterwards, on the practice board
(`bench.md`, part C). *(Changed 2026-10-28 for D28 and spec 040 criterion 3; ESP-IDF v5.4.2 `secure-boot-v2.rst` step 10: "The second stage bootloader will enable Secure Boot on the chip".)*

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
   Then check both files carry your key, and write the digest down for step 3 of section 4:
   ```bash
   espsecure.py signature_info_v2 bootloader_signed.bin     # prints the public key digest of the signature block
   espsecure.py signature_info_v2 app_signed.bin
   espsecure.py digest_sbv2_public_key --keyfile secure_boot_signing_key.pem --output sb_digest.bin
   xxd -p sb_digest.bin                                      # the same digest, as hex: all three must agree
   ```
   *(Added 2026-10-28 for D28. The first-boot bootloader checks that the **app** matches the digest in eFuse, but it does not compare the
   **bootloader's own** signature with it (ESP-IDF v5.4.2 `components/bootloader_support/src/secure_boot_v2/secure_boot.c`, `check_and_generate_secure_boot_keys`,
   the "Using pre-loaded public key digest in eFuse" branch). A bootloader signed with the wrong key would therefore be accepted on first boot and then refused
   by the chip on the next one: a dead board. `signature_info_v2` is in `secure-boot-v2.rst` and prints the key digest (esptool v5.1.0 `espsecure`);
   that its printed digest equals the file `digest_sbv2_public_key` writes is reasoned from `secure-boot-v2.rst` ("Compare the SHA-256 hash digest of the public key
   embedded in the bootloader's signature block with the digest(s) saved in the eFuses"), not run. If they do not match by eye, stop.)*
4. This is separate from `sign_firmware.py`, which signs the OTA *manifest*. Hardened boxes check both: the
   manifest signature in the app and the Secure Boot signature in the bootloader.

## 4. Per-unit procedure (the irreversible moment is the first boot in step 6)

*(Rewritten 2026-10-28 for D28. Before, you burned the two keys **and** `SECURE_BOOT_EN`, the `DIS_*` fuses and `ENABLE_SECURITY_DOWNLOAD` by hand,
and the bootloader then encrypted the flash. ESP-IDF documents that as neither of its two flows, so this guide no longer does it. Now you burn **only the two keys**; the
Release-mode bootloader does everything else on its first boot. This is the flow of ESP-IDF v5.4.2 `docs/en/security/flash-encryption.rst`, "Using Host Generated Key" and "Release Mode",
combined with `secure-boot-v2.rst`, "How To Enable Secure Boot v2". The bootloader behaviour below was read in its source at the same tag; nothing was run on a board.)*

Work on one board at a time, powered from USB, with the board's MAC written on it.

**What the bootloader does by itself on first boot** (so you do not do it, and must not): it burns `SECURE_BOOT_EN`; it revokes the secure-boot digest slots
you did not fill (`SECURE_BOOT_KEY_REVOKE1` and `2`); it switches the chip to secure download mode (`ENABLE_SECURITY_DOWNLOAD`); it burns `DIS_PAD_JTAG`, `DIS_USB_JTAG`, the software
JTAG-disable bits, `DIS_DIRECT_BOOT`, `DIS_DOWNLOAD_ICACHE` and `DIS_DOWNLOAD_MANUAL_ENCRYPT`; it write-protects further read-protection of eFuses (`WR_DIS_RD_DIS`);
it encrypts the flash in place; and it sets `SPI_BOOT_CRYPT_CNT` (in Release mode straight to **7**, which is "enabled"; ESP-IDF's prose says "the first bit", the source writes all three,
and any odd value means encrypted). It does **not** burn `DIS_DOWNLOAD_MODE` (section 0).
*(Sources, ESP-IDF v5.4.2: `components/bootloader_support/src/esp32c3/secure_boot_secure_features.c`, `.../esp32c3/flash_encryption_secure_features.c`, `.../secure_boot_v2/secure_boot.c`
(`Revoking empty key digest slot`), `.../flash_encryption/flash_encrypt.c` (`Setting CRYPT_CNT for permanent encryption`, release branch), `docs/en/security/secure-boot-v2.rst`
"Key Revocation" note: "the bootloader will intentionally revoke the unused digest slots while enabling secure boot". The 7 against ESP-IDF's prose "1" is a case where the source and the page disagree; the source wins, and the
5 and 6 checks below accept either.)*

1. **Read the chip, change nothing:**
   ```bash
   python3 scripts/sold_unit_check.py fuses --port /dev/ttyUSB0
   ```
   Expect: all of `SECURE_BOOT_EN`, `SPI_BOOT_CRYPT_CNT`, `DIS_USB_JTAG`, `ENABLE_SECURITY_DOWNLOAD` clear, and no key block in use. If any is set
   the board has been touched before: stop and use a fresh one. (ESP-IDF's own first step: "a device with default flash encryption eFuse settings".)
2. **Burn the flash-encryption key** (cannot be read back afterwards; keep your copy). This must happen **before** the first boot: if no valid key is in eFuse
   the bootloader makes a random one that nobody, you included, can ever read (`flash-encryption.rst`, "Using Host Generated Key": "Before the first encrypted boot, burn the key"; `flash_encrypt.c`,
   "Generating new flash encryption key"). Do not add `--no-read-protect`, `--no-write-protect` or `--no-protect-key`: the bootloader refuses a pre-loaded key that is not read-protected and
   write-protected ("Invalid key state, check read&write protection for key and keypurpose") and the board does not finish its first boot.
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY0 flash_encryption_key.bin XTS_AES_128_KEY
   ```
   The board still boots normally after this (the chip only treats flash as ciphertext once `SPI_BOOT_CRYPT_CNT` is odd).
3. **Burn the Secure Boot key digest** (the C3 has room for three digests; use `BLOCK_KEY1`; leave the other two empty):
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY1 sb_digest.bin SECURE_BOOT_DIGEST0
   ```
   (`sb_digest.bin` is the file from section 3, step 3. The bootloader accepts a digest burned this way: "Using pre-loaded public key digest in eFuse", `secure_boot.c`; the digest key stays readable and is only
   write-protected, which it needs to be: `espefuse` docs `burn-key-cmd.rst`. Burning it is optional in ESP-IDF's flow, because the bootloader would burn the digest of its own signature itself; you do it so that **your**
   digest, not whatever the flashed bootloader carries, is the one in the chip.)
4. **Look, while you still can.** This is the last moment `espefuse.py` works, because it stops working once secure download mode is on (section 5):
   ```bash
   espefuse.py -p /dev/ttyUSB0 summary
   ```
   Read every name. `BLOCK_KEY0` must show purpose `XTS_AES_128_KEY` and be read- and write-protected (its value shows as unreadable; that is right); `BLOCK_KEY1` must show `SECURE_BOOT_DIGEST0`, write-protected, readable.
   **Everything else must still be clear**: `SECURE_BOOT_EN`, `SPI_BOOT_CRYPT_CNT`, `SECURE_BOOT_KEY_REVOKE0/1/2`, `ENABLE_SECURITY_DOWNLOAD`, `DIS_DOWNLOAD_MODE`, all `DIS_*`. If a name is missing on your version, stop and look it up rather than guessing.
5. **Erase and write the signed images, plain, without letting the chip restart** — **GATE 2:** this is as far as you can check before the irreversible moment:
   ```bash
   esptool.py -p /dev/ttyUSB0 erase_flash
   esptool.py -p /dev/ttyUSB0 --after no_reset write_flash <offsets and signed files from the bundle>   # bootloader_signed.bin, partition table, app_signed.bin, and the rest
   ```
   Good: `Hash of data verified.` for every file. `--after no_reset` (esptool 5.x: `no-reset`) matters: esptool's default is to reset the chip when it finishes (esptool v4.10.0 and v5.1.0 `--after`, default
   `hard_reset`), and a reset here would be the first boot. The keys are burned first, in steps 2 and 3, for the same reason: any restart after the images are in place is the first boot, so the keys must already be in. GATE 2 passes when
   step 4's summary was as described, section 3's three digests agree, and every file verified. **You cannot "try boot" the hardened bootloader first**: its first boot is step 6.
   *(The reset-after-flash default and the order are my reasoning from esptool's option list and from the bootloader source: nothing in ESP-IDF's pages says it. Secure Boot is not switched on until a valid partition table
   and app image are present: `secure-boot-v2.rst` step 10, note. So flash all of them.)*
6. **THE FIRST BOOT: this is the moment that can never be undone.** Have the serial monitor open (115200) before you start. Power-cycle the board (unplug and replug USB; do not hold the BOOT button). The bootloader now does everything listed above, and
   encrypts the flash in place, which takes about a minute on the C3. **Do not remove power for at least two minutes.** If power is cut during the pass the flash is corrupted and needs plain images written again, and if it happens after
   `SECURE_BOOT_EN` is set that is only possible with images signed with your key (`flash-encryption.rst`, "Flash Encryption Process" and the warning under it; `secure-boot-v2.rst` note "if reset or powered down during the first boot, it will start the process again").
   With the bootloader log at Info level you should see, in this order (read from the v5.4.2 source; the exact wording may differ slightly): `Secure boot digests already present`, `Using pre-loaded public key digest in eFuse`, `Revoking empty key digest slot (1)...` and `(2)...`,
   `blowing secure boot efuse...`, `Enabling Security download mode...`, `Disable hardware & software JTAG...`, `Secure boot permanently enabled`, `Checking flash encryption...`, `Using pre-loaded flash encryption key in efuse`,
   `Setting CRYPT_CNT for permanent encryption`, `Flash encryption completed`, then a restart into your app. If it says `Generating new flash encryption key`, step 2 did not take: **stop, unplug**, and write down everything on the screen.
   If the log is quiet because the log level is low, judge by section 5.
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
command cannot give the answer. Read the result from `get_security_info` instead (changed 2026-10-28 for D28: after the first boot of section 4 step 6 this is the **only** way to read the fuses;
the `espefuse.py summary` of step 4 was your last look). It must show: `Secure Boot: Enabled`; `Secure Boot Key Revocation Status` listing Key1 and Key2 as revoked (and **not** Key0, which is yours);
`Flash Encryption: Enabled` with `SPI_BOOT_CRYPT_CNT` odd (expect `0x7`; `0x1` is also fine); `Icache in UART download mode: Disabled`; and `JTAG: Permanently Disabled`.
*(Names from esptool v5.1.0 `esptool/cmds.py`, `get_security_info`; what a real C3 prints is not known yet, so compare by eye on the practice board and stop on any difference.)*
That `get_security_info` answers at all also tells you `DIS_DOWNLOAD_MODE` was **not** burned: that fuse would switch the chip's download mode off completely (reasoned from its esptool description,
"Set this bit to disable download mode"). If it does not answer, the way back is gone: write that down and do not sell the unit. `sold_unit_check.py` needs a fix for
this before GATE 3 can be scripted (see `efuse-review.md`, item 7); until then, read it by eye.
Also dump the **NVS region** (`0x9000`, 0x5000 bytes) and search it for the box's secret
(`python3 scripts/sold_unit_check.py flash nvs_dump.bin --find "<text>"`). **Expect it to appear in plain**:
ESP-IDF says the `nvs` partition cannot be flash-encrypted because the NVS library is not directly compatible with flash
encryption (v5.4.2 `flash-encryption`, "Encrypted Partition Flag"; *noted 2026-10-28*). If it does, the fix is NVS encryption (`nvs_flash_secure_init`
with a key partition), a firmware change that must be made before selling hardened units.

Then check from the dashboard: a signed OTA update works, an unsigned one is refused, the coin queue survives a
power cut, and a factory reset still works.

## 6. If it goes wrong

- Boot loop after the first boot (step 6): the bootloader or app was not signed with the key whose digest you burned. The board
  cannot be recovered; this is why you practise first, and why section 3 compares the digests before you flash.
- Power cut during the first boot: see section 4, step 6. Write down what the screen said; the board may be recoverable only with plain images signed with your key, or not at all.
- Lost the Secure Boot key: units keep running but can never take an update again.
- A customer's unit fails: it cannot be re-flashed by serial except with images encrypted for *that* unit's key.
  Ship a replacement board rather than promising repair.

## 7. Practice log (fill in before selling)

| Date | Board / revision | GATE 1 | GATE 2 | GATE 3 | NVS plaintext? | Notes |
|---|---|---|---|---|---|---|
| | | | | | | |
