# eFuse review of `docs/PROVISIONING_SOLD_UNIT.md` (feature 040)

Reviewed 2026-10-28. Nothing here touched hardware; no command in this note or in the guide was run.

## How this was checked, and its limits

- `docs.espressif.com` is blocked in this cloud session (proxy 403, name does not resolve), so the rendered Programming Guide could not be read.
  I read the **documentation source files of the same pages** in Espressif's own repository at the release tag **ESP-IDF v5.4.2**
  (`github.com/espressif/esp-idf`, tag `v5.4.2`, `docs/en/security/`). These are the files the website is built from, but
  the `{IDF_TARGET_...}` placeholders and `.. only::` blocks were read by hand for ESP32-C3, not rendered. Where a sentence
  depends on that, I say so.
- Tool names and arguments were checked in the **esptool source** at tags **v4.10.0** (last 4.x) and **v5.1.0**
  (`github.com/espressif/esptool`; `espefuse/efuse_defs/esp32c3.yaml`, `espefuse/efuse/`, `espsecure/`, `docs/en/`).
- URLs below are the stable-tag source paths. Prefix: `https://github.com/espressif/esp-idf/blob/v5.4.2/docs/en/security/`
  = **IDF**, and `https://github.com/espressif/esptool/blob/v5.1.0/` = **ET5**, `.../v4.10.0/` = **ET4**.
- "Verified" below means: I read it in those sources. "Reasoned" means my inference, not stated by a source.

## Verdict on step 5 (the primary question)

**Wrong.** Burning `SPI_BOOT_CRYPT_CNT 7` while the flash still holds plain images would, as the previous session feared, leave
a board that cannot boot. The guide's own step 6 ("the bootloader encrypts the flash in place on first boot") describes a
different workflow from the one step 5 starts, so the two steps contradicted each other.

Verified:

1. `SPI_BOOT_CRYPT_CNT` is the flag the chip reads to decide whether flash holds ciphertext. IDF `flash-encryption.rst`,
   "Possible Failures": "Once flash encryption is enabled, the `SPI_BOOT_CRYPT_CNT` eFuse value will have an odd number of
   bits set. It means that all the partitions marked with the encryption flag are expected to contain encrypted ciphertext."
   The first failure listed is a plaintext bootloader: the ROM cannot load it and prints `invalid header: 0x...` repeatedly.
   esptool agrees: the C3 field is described as "Enables flash encryption when 1 or 3 bits are set and disables otherwise"
   (ET5 `espefuse/efuse_defs/esp32c3.yaml`; values 1 and 7 = "Enable", 0 and 3 = "Disable").
2. In the bootloader-driven flow the order is fixed (IDF `flash-encryption.rst`, "Flash Encryption Process", the XTS-AES-128
   variant that applies to the C3): the bootloader reads the counter as 0, enables the encryption block; **if a valid key is
   already in eFuse (burned with espefuse) it skips key generation and uses it**; encrypts the flash in place ("up to a
   minute for large partitions"); **then sets the first free counter bit** ("Odd number of bits is set"); in Release mode it
   sets the Release-mode download and JTAG eFuses (`DIS_DOWNLOAD_ICACHE`, `DIS_PAD_JTAG`, `DIS_USB_JTAG`,
   `DIS_DIRECT_BOOT`/`DIS_LEGACY_SPI_BOOT`, then `DIS_DOWNLOAD_MANUAL_ENCRYPT`) and **write-protects the counter**; then reboots.
   Pre-burning a host-generated key is documented for this flow: "**Before the first encrypted boot**, burn the key"
   (`flash-encryption.rst`, "Using Host Generated Key"). Warning there too: do not interrupt power during the first
   encryption pass or the flash is corrupted and needs plain data again.
3. The operator-burns-the-counter route also exists, but is a different, all-by-hand workflow: IDF
   `security-features-enablement-workflows.rst`, "Enable Flash Encryption Externally": **erase the flash first**, burn the key
   (step 3), burn `SPI_BOOT_CRYPT_CNT 7` (step 4), burn the other security eFuses (step 5), then **write only images that were
   encrypted on the PC** (step 7), and burn the secure-download eFuse last (step 8). There the flash never holds plaintext
   while the counter is odd. The guide's step 2 writes plain images, so that route does not apply.

So: step 5 is wrong as it stood (the counter must not be burned by the operator in this guide's route), and it is **not**
merely ambiguous wording.

Applied to the guide: the `SPI_BOOT_CRYPT_CNT 7` line is removed, with a bold warning and the reason, and step 6 now says the
bootloader sets it (to **1**, not 7).

## Items checked

| # | Item | Verdict | Where / citation | Action |
|---|---|---|---|---|
| 1 | Step 5 burns `SPI_BOOT_CRYPT_CNT` before the flash is encrypted | **Wrong** | Above (IDF `flash-encryption.rst`, "Flash Encryption Process" and "Possible Failures"; `security-features-enablement-workflows.rst`) | Line removed in the guide; inline note added. |
| 2 | Step 6 "bootloader encrypts in place on first boot" | **Right**, but only without step 5's counter burn. After the pass the counter reads 1, not 7 | IDF `flash-encryption.rst`, "Flash Encryption Process" | Step 6 reworded. `sold_unit_check.py` treats 1 and 7 alike ("Enable"), so GATE 3 is not affected by this. |
| 3 | Mixing: the guide hand-burns `SECURE_BOOT_EN`, `DIS_*` and `ENABLE_SECURITY_DOWNLOAD` and then lets the first-boot bootloader do the encryption | **Could not establish** | IDF documents either "bootloader does everything on first boot" (`flash-encryption.rst`; `secure-boot-v2.rst` steps 8-10, "the second stage bootloader will enable Secure Boot") or "operator does everything, flashes only ciphertext" (`security-features-enablement-workflows.rst`). The in-between route is in neither. No page says it fails, none says it works. | Left as is, flagged in the guide. **Question for the owner / the practice board:** either keep the guide's mix and let the practice board decide, or drop the hand burns of `SECURE_BOOT_EN`, the `DIS_*` fuses and `ENABLE_SECURITY_DOWNLOAD` and let the Release-mode bootloader set them (the bootloader sets the JTAG, `DIS_DIRECT_BOOT`, `DIS_DOWNLOAD_MANUAL_ENCRYPT` bits per item 2; secure download mode is the default on first boot in Release mode, `flash-encryption.rst`, Release Mode, "Best Practices"; `SECURE_BOOT_EN` is set by the bootloader, `secure-boot-v2.rst` step 10). That would make the whole procedure one documented flow, with only the keys burned by hand. I recommend that, but it is a change of procedure, so I did not make it. |
| 4 | Order of the secure-boot key burn relative to flash encryption | **Correct** (key first, then digest) | IDF `security-features-enablement-workflows.rst`, "Enable Flash Encryption and Secure Boot v2 Externally": flash encryption first, Secure Boot second, because Secure Boot needs the digest readable and write-protects `RD_DIS`, after which the flash-encryption key can no longer be read-protected. The guide's steps 3 then 4 match. | None. |
| 5 | `DIS_DOWNLOAD_MODE` must not be burned; `ENABLE_SECURITY_DOWNLOAD` is the right C3 name | **Correct** | The C3 eFuse list has both (ET5/ET4 `esp32c3.yaml`: `DIS_DOWNLOAD_MODE`, `ENABLE_SECURITY_DOWNLOAD` = "enable secure UART download mode"). IDF `flash-encryption.rst` (UART ROM download mode paragraph): secure download "permanently limits the available commands to updating SPI config, changing baud rate, basic flash write, and returning a summary of the currently enabled security features with the `get_security_info` command"; disabling download mode entirely is a separate, stronger choice. IDF's external workflow burns `ENABLE_SECURITY_DOWNLOAD` last, as the guide does. | None. |
| 6 | JTAG names `DIS_USB_JTAG`, `DIS_PAD_JTAG` | **Correct for the C3** | ET5/ET4 `esp32c3.yaml`; IDF `flash-encryption.rst` (C3 Release/Development lists) and `security-features-enablement-workflows.rst`. The guide's phrase "the C3's USB-serial JTAG too" was **ambiguous**: `DIS_USB_JTAG` only stops the USB port switching to JTAG; there are separate fuses `DIS_USB_SERIAL_JTAG` and `DIS_USB_SERIAL_JTAG_DOWNLOAD_MODE`. *Reasoned, not tested:* burning those would take away the USB serial port or its download mode. | Guide's JTAG row reworded to say not to burn them unless intended. |
| 7 | **New:** `espefuse.py` stops working once `ENABLE_SECURITY_DOWNLOAD` is burned, so GATE 3's `sold_unit_check.py fuses --expect-locked` (which runs `espefuse.py summary`) cannot work on a finished unit | **Wrong in the guide** (verified in source) | ET5 `espefuse/efuse/esp32c3/fields.py`: "Secure Download Mode is enabled. The tool can not read eFuses."; same text in ET4. `espefuse/efuse/base_operations.py` prints after the burn: "espefuse will not work. esptool can read/write only flash." IDF external workflow warns the same ("the espefuse tool can no longer be used to burn additional eFuses"). `esptool get_security_info` does work in secure download mode (IDF `flash-encryption.rst`; ET5 `esptool/cmds.py`). | GATE 3 and bench C7 now use `esptool.py get_security_info`. **`scripts/sold_unit_check.py` should be changed** to use it (code change, out of scope here; I did not touch it). Also `espefuse.py summary` in step 5 must be run *before* that burn. |
| 8 | **New:** unused Secure Boot digest slots should be revoked, and `RD_DIS` write-protected, when the operator burns Secure Boot by hand | **Omitted by the guide; ambiguous for this route** | IDF `security-features-enablement-workflows.rst`, Secure Boot v2 step 5B: write-protect `RD_DIS`, then burn `SECURE_BOOT_KEY_REVOKE1`/`2` for unused slots ("must happen before the device leaves the factory"). `secure-boot-v2.rst` "Key Management" says the same, and that the app revokes unused slots at start-up unless `CONFIG_SECURE_BOOT_ALLOW_UNUSED_DIGEST_SLOTS` is set; the first-boot bootloader revokes them when *it* enables Secure Boot. Which of these happens in the guide's mixed route is part of item 3. | Not changed. Decide with item 3. The practice board should show `SECURE_BOOT_KEY_REVOKE1/2` after first boot; if not, burn them before the unit ships (they must be burned before `ENABLE_SECURITY_DOWNLOAD`, since espefuse cannot run after). |
| 9 | **New:** `DIS_DOWNLOAD_ICACHE` (and `DIS_DOWNLOAD_MSPI` where present) are in IDF's list of Release-mode download-mode protections for the C3 but not in the guide's hand-burn line | **Omitted; matters only if the operator burns by hand** | IDF `flash-encryption.rst` C3 variant; `esp32c3.yaml` has `DIS_DOWNLOAD_ICACHE`. In the bootloader-driven flow the bootloader sets it (item 2). | Not changed; follows item 3. |
| 10 | `burn_efuse` with several names (guide: `DIS_USB_JTAG DIS_PAD_JTAG DIS_DIRECT_BOOT DIS_DOWNLOAD_MANUAL_ENCRYPT`) | **Wrong** (would not run) | More than one argument is read as `NAME VALUE` pairs: ET4 `espefuse/efuse/base_operations.py` (`ActionEfuseValuePair`), ET5 same file (`EfuseValuePairType`). Four names are read as two pairs and `DIS_PAD_JTAG` is rejected as a value, before anything is burned (`CheckArgValue`, `int(value, 0)`). IDF writes `EFUSE_NAME VAL EFUSE_NAME2 VAL2`. | Fixed: each name now has `1`. A harmless failure as written, but it would have stopped the operator mid-procedure at the worst moment. |
| 11 | Command and argument spelling (`burn_key`, `burn_efuse`, `sign_data`, `digest_sbv2_public_key`, `generate_signing_key --version 2 --scheme rsa3072`, `generate_flash_encryption_key`, `encrypt_flash_data --aes_xts --keyfile --address --output`, `read_flash`, `chip_id`, `get_security_info`) | **Correct in esptool 4.x; renamed in 5.x** | All of these exact forms (with underscores) appear in IDF v5.4.2 `security-features-enablement-workflows.rst`. esptool v5.x renamed commands and options to use dashes; the old spellings still work with a deprecation warning and "will be removed in the next major release" (ET5 `docs/en/migration-guide.rst`, "Commands and options use dashes"; ET5 `CHANGELOG.md`; `.py` script names also deprecated). `pip install esptool` today installs 5.x. | A note added to the guide. `scripts/sold_unit_check.py` calls `espefuse.py`, which will go away in the next major esptool: not changed here. |
| 12 | `espefuse.py burn_key BLOCK_KEY0 flash_encryption_key.bin XTS_AES_128_KEY` and `BLOCK_KEY1 ... SECURE_BOOT_DIGEST0` | **Correct** | IDF `flash-encryption.rst` ("Using Host Generated Key": any free `BLOCK_KEY0`..`BLOCK_KEY5`, purpose `XTS_AES_128_KEY`); `security-features-enablement-workflows.rst` (`burn_key BLOCK digest.bin SECURE_BOOT_DIGEST0`); ET5 `docs/en/espefuse/burn-key-cmd.rst` (a `*_DIGEST` key stays readable; an XTS key is read-protected by default). Guide's "the C3 has room for three" is loosely worded (three digest slots, six key blocks) but not wrong. | None. |
| 13 | ESP32-C3 chip revision for Secure Boot V2 | **Missing from the guide** | IDF `secure-boot-v2.rst`: "Secure Boot v2 is available for ESP32-C3 from chip revision v0.3 (ECO3) onwards." The guide only gave the original ESP32 rule. | Added to the guide's decisions table; bench A1 already records the revision. |
| 14 | "If the NVS text appears in plain, NVS is not covered … on this build" | **Misleading; expect plain** | IDF `flash-encryption.rst`, "Encrypted Partition Flag": "The `nvs` partition cannot be encrypted, because the NVS library is not directly compatible with flash encryption." NVS needs its own NVS encryption (with a key partition, which must itself be encrypted: external workflow, step 7). So on a build that has not enabled NVS encryption, the box's secret **will** be readable in the NVS region, and that is a firmware change needed before any sale. | Guide wording changed from "if" to "expect". No firmware work started. |
| 15 | Per-unit flash-encryption key kept ("store MAC → key") | **Ambiguous (a design choice, not an error)** | IDF's external workflow, step 9, says the host key "must be deleted immediately" after enabling, so the host cannot make encrypted binaries for that device. `flash-encryption.rst` says a host-generated key is "not recommended for production, unless a separate key is generated for each individual device", which the guide does. The guide keeps the keys on purpose (to re-flash a failed unit by serial); that trades some key exposure for repairability, which is the owner's call. | None. Owner decision. |
| 16 | Release mode eFuse list in the guide's §0 / §1 (`CONFIG_SECURE_UART_ROM_DL_MODE=y`) | **Correct** | IDF `flash-encryption.rst`, Release Mode: "Select UART ROM download mode (Permanently switch to Secure mode (recommended))", the default option on non-ESP32 chips. | None. |

## What I did not establish

- Whether a pre-burned Secure Boot digest (guide step 4) plus a hand-burned `SECURE_BOOT_EN` is accepted by a bootloader
  that then also does the flash-encryption first boot (item 3). Nothing in the pages says it is refused; nothing says it works.
- Whether `SECURE_BOOT_KEY_REVOKE1/2` end up set in the guide's route (item 8).
- Anything about the Arduino/PlatformIO bootloader route (guide section 1); that was not in the question.
- The exact output text of `get_security_info` on a locked C3 (item 7); read the flags it prints and compare by eye on the practice board.
- I did not render the HTML pages, so any sentence that a rendered page would show differently because of an `.. only::`
  condition is a risk. The conclusions above rest on statements that are not conditional on the target (the counter semantics,
  the first-boot order, the external workflow) and on the esptool sources.

## Decision on item 3 (added 2026-10-28, owner decision D28)

The owner decided item 3: **drop the hand burns and use the flow ESP-IDF documents.** The operator burns only the flash-encryption key (`BLOCK_KEY0`, `XTS_AES_128_KEY`) and the Secure Boot digest
(`BLOCK_KEY1`, `SECURE_BOOT_DIGEST0`). The Release-mode bootloader sets `SECURE_BOOT_EN`, the `DIS_*` fuses, secure download mode and `SPI_BOOT_CRYPT_CNT` on its first boot and revokes the unused digest slots (item 8).
`docs/PROVISIONING_SOLD_UNIT.md` section 4 and `bench.md` part C were rewritten the same day. The findings above are left as they were; what the follow-up read of the v5.4.2 **bootloader source** adds or corrects:

- **Item 8 is answered:** the unused slots are revoked by the first-boot bootloader, also when the digest was pre-burned (`components/bootloader_support/src/secure_boot_v2/secure_boot.c`, "Revoking empty key digest slot"; `secure-boot-v2.rst`, "Key Revocation" note). `CONFIG_SECURE_BOOT_ALLOW_UNUSED_DIGEST_SLOTS` only affects the app.
- **A pre-burned digest is accepted** ("Using pre-loaded public key digest in eFuse"), but the code compares only the **app** with it, not the bootloader's own signature: a wrongly signed bootloader would be accepted on first boot and refused on the next. The guide now compares the digests before flashing.
- **A pre-burned flash-encryption key must be read- and write-protected with its purpose write-protected**, or the bootloader refuses it ("Invalid key state", `flash_encrypt.c`). `espefuse burn_key` does this by default; the guide forbids the `--no-*protect*` options.
- **Correction to item 2's "reads 1, not 7":** in Release mode the source writes the counter "straight to max" (`flash_encrypt.c`, `esp_flash_encrypt_enable`, release branch), so on the C3 it ends at **7**; the ESP-IDF page says "the first available bit". Both are odd, i.e. "enabled". The guide and bench accept any odd value.
- **Secure download mode** is set by the *Secure Boot* path (`esp32c3/secure_boot_secure_features.c`, under `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE`, the Release-mode default). `DIS_DOWNLOAD_MODE` is burned only if `CONFIG_SECURE_DISABLE_ROM_DL_MODE` is chosen, so it stays unburned.
- **The guide's sdkconfig list was wrong in one name:** `CONFIG_SECURE_UART_ROM_DL_MODE` is a menu choice, not a setting; the setting is `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE`.
- **A new hazard in the old order:** esptool resets the chip after `write_flash` by default; with the hardened images in place that reset is the first boot, so the keys are now burned first and the images are written with `--after no_reset`.
- Still not run on a board. Not established: what a real C3's `get_security_info` prints, and whether the bootloader log level in the final build shows the first-boot lines.

## Answer for the bench

The earlier flag was **not** a false alarm. Step 5 is fixed. The bench is **unblocked for the sacrificial board**, but run
item 3 as an explicit question: that board is where the mixed route gets tested, and nothing may be burned on a sold unit until it has.
