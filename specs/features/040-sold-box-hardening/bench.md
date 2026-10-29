# Bench script: prove a sold box can be hardened and still updates (feature 040, GATE 1)

For you, standing at a board. Follow it top to bottom. The procedure itself is `docs/PROVISIONING_SOLD_UNIT.md`; this
is the same thing in the order you do it, with what good looks like and when to stop. Nothing here was run by the person
who wrote it: **no step below has ever been tried on a board.** If a result does not match, you stop and write down what you saw.

## Read this first

1. **Use a sacrificial board.** A board you bought to be destroyed (an ESP32-C3, the same module you plan to sell). **Never
   run any of this on a box that is in a shop, and never on your own working boxes.** Part C can never be undone.
2. **Nothing irreversible happens until criteria 3, 4, 5 and 6 have all passed on a board with no eFuses burned** (parts A and B).
   Part C is the first irreversible thing. If you are not sure part A fully passed, do not start part C.
   *(Changed 2026-10-28 for D28.)* **You burn only the two keys. The irreversible moment is the first boot of the hardened bootloader (C5 step 6), not any `espefuse` command.**
   The two key burns (C5 steps 2 and 3) are permanent too, but the board still boots plain firmware after them; the first boot is what turns everything on.
3. **Two boards are better than one**: board 1 for part A (it stays unburned), board 2 for part C (it is destroyed). Board 1 can be
   used again for part A after a bad run, because nothing is burned on it.
4. **No key goes in git, ever.** Not the secure-boot key, not the flash-encryption key, not an eFuse summary that shows key bytes.
   Work in a folder outside the repository. `bash scripts/check_security_rules.sh` fails if a private key is committed; never work around it.
5. **The secure-boot key is NEW, RSA-3072, and SEPARATE from the firmware-signing key** (the one that signs OTA updates and the router).
   Do not reuse one for the other. Back the new one up twice, offline.
6. **Do not publish anything.** The test updates in part A are uploaded by hand to the one board. Never copy them into `website/update/`
   and never commit a raised `PISO_FW_VERSION`: customers' boxes would try to install them. Build the test versions in a scratch copy of the repository.

## Why the order is not what the guide says (read once)

`docs/PROVISIONING_SOLD_UNIT.md` says to test GATE 1 with the hardened bootloader and no eFuses. A bootloader built with flash encryption
and Secure Boot V2 switched on **starts the irreversible steps by itself on its first boot** (it encrypts the flash and burns eFuses; this is how
ESP-IDF's Release mode is documented to work: `secure-boot-v2.rst` step 10 and `flash-encryption.rst`, "Release Mode"). So it cannot be a "no eFuses burned" test. This script therefore proves updates, the
money check and rollback first on the bootloader the boxes already use (it has the same two rollback options), and then repeats the
same checks on the hardened bootloader in part C, on the sacrificial board. The spec says the same (`spec.md`, scope 3). Confirm that edit before you start.

## What you need

- A PC with `pip install esptool` (gives `esptool.py`, `espefuse.py`, `espsecure.py`) and a USB cable that carries data.
- A coin acceptor wired to the board (or whatever you use for the H-series checks), and one rental phone that is already set up with the app.
- The release firmware files: run the workflow "Coin box images for the setup program" by hand with **bundle** on, and download
  `PisoPhone-firmware-release-<version>.zip` from the branch `firmware-bundles` (plain files and offsets, no keys). Note its version, call it **V**.
- A scratch checkout of the repository for building test versions. Do not push from it.
- A notebook or the table at the end of this file. Write down every result as you go, with the real console text.

## Part A. GATE 1 on board 1, no eFuses burned (criteria 3, 4, 5, 6)

**A1. Read the chip. This changes nothing.**
```bash
esptool.py -p /dev/ttyUSB0 chip_id
python3 scripts/sold_unit_check.py fuses --port /dev/ttyUSB0
```
Good: `Chip is ESP32-C3`, and write down the revision it prints. In the fuse list, `SECURE_BOOT_EN`, `SPI_BOOT_CRYPT_CNT`, `DIS_USB_JTAG` and
`ENABLE_SECURITY_DOWNLOAD` are all clear (0 / false).
**STOP** if any of them is set: the board was touched before. Use a fresh one.

**A2. Write the release firmware, plain. Nothing is encrypted or signed for the chip yet.**
```bash
python3 -m esptool --chip esp32c3 --port /dev/ttyUSB0 --baud 460800 erase_flash
python3 -m esptool --chip esp32c3 --port /dev/ttyUSB0 --baud 460800 write_flash 0x0 esp32-c3-production-encrypted-merged.bin
```
(Use the merged file or the offsets that come with the bundle. If the names differ from the above, trust the bundle's own README.)
Good: `Hash of data verified.` at the end, and the board restarts. Nothing in `fuses` has changed (run A1's second command again to be sure).

**A3. Criterion 3: it boots.** Open the serial monitor (115200). Good: the box starts, joins or opens its set-up Wi-Fi, and its dashboard opens.
Open `/api/diagnostics` on it. Good: the version is **V**, and `ota_image_state` reads `valid` (or `undefined` on a first flash; write down which).
**STOP** on a restart loop, or if the dashboard never comes up.

**A4. Check that this build only accepts signed updates (this is K2 in `REAL_WORLD_TESTING.md`).** On the dashboard's update page, upload any
firmware file **without** its signature manifest (or with a manifest you edited). Good: it is refused.
**STOP** if the unsigned update is accepted. This build has no owner key in it, so "a signed update works" (A6) proves nothing.

**A5. Build the two test versions in your scratch checkout (never commit these).**
- **V+1**, a good image: raise `PISO_FW_VERSION` in `esp32_firmware/include/FirmwareVersion.h` by one patch number, build `esp32-c3-production-encrypted`.
- **V+2**, a bad image: raise it by two, and build with `PLATFORMIO_BUILD_FLAGS="-D PISO_TEST_REFUSE_CONFIRM=1"`. It can never pass its start-up check (H4b).

Sign each with your **firmware-signing key** (`docs/KEYS.md`, "By hand"):
```bash
python3 scripts/sign_firmware.py --private YOURKEY.pem --chip esp32c3 --image <path>/firmware.bin
```
That writes `firmware.bin.manifest.json` next to the image. Keep the two pairs apart and labelled. The key stays on your own machine.

**A6. Criterion 5, first half: put a payment in the queue that cannot be delivered.** This is the money check; do it before the update.
1. On the dashboard, note the lifetime coins and today's revenue, and that the Review list and the red "payments could not be delivered" banner are empty.
2. Arm the phone, then switch its app off (or unpair it) so it will not acknowledge.
3. Put one coin in. Good: the coin is counted once, and it is waiting in the queue (as in M3a in `REAL_WORLD_TESTING.md`).
4. Write down the box time, the coin value, and the revenue shown.

**A7. Criteria 4 and 6 (good half): a signed update works and is put on trial.** On the update page choose the **V+1** image and its manifest.
Watch the serial log and `/api/diagnostics`:
- the log says `New firmware is on trial`; `ota_image_state` reads **`pending_verify`**;
- after about a minute the log says `New firmware confirmed` and the state reads `valid`;
- the version is now **V+1**. Power-cycle: it is still V+1.

**STOP, GATE 1 HAS FAILED, burn nothing:** if `ota_image_state` never shows `pending_verify` after a good update. That means the bootloader has no rollback,
and a bad update would never be reverted. Write down exactly what you saw. Do not go on to part B or C. (Spec criterion 6.)
**STOP** also if the signed update is refused (criterion 4 failed), or the box does not come back.

**A8. Criterion 5, second half: the queue survived and is credited once.** After A7, before anything else:
1. The dashboard still shows the waiting payment: same coin, same amount, **not** twice, and the banner or Review list matches what you wrote in A6.
2. Switch the phone's app back on / re-pair it. Good: the phone is credited **once** (the minutes of one coin, not two), the box's revenue and lifetime coins went up by **one** coin in total
   (compare with A6), and the Review list no longer shows it.
3. Wait two minutes and look again: still one credit, no second delivery.
Also pull the power for 5 seconds once, with a second unacknowledged coin queued, and check that coin is there after the restart and credited once. (RS30 is the same idea.)
**STOP** if a payment is gone, shows twice, or is credited twice. That is the failure the whole project is built to prevent: write down the exact numbers and tell the developer. Do not burn anything.

**A9. Criterion 6 (bad half): a bad update is reverted (H4b).** Update to the **V+2** image. Good, after about 5 minutes:
the log says `restoring the previous firmware`; the box restarts on **V+1**; `/api/diagnostics` shows `ota_last_update_rolled_back: true`, and the restart cause is `ota-rollback`.
**STOP, GATE 1 HAS FAILED** if the box stays on V+2, or never comes back on V+1.

If A1 to A9 all pass, **GATE 1 has passed on the stock bootloader.** Record it in the table. Part B and C come next; they still prove nothing until part C.

## Part B. Build the hardened bootloader (criteria 1 and 2) (no board needed, no eFuses)

This has **not** been built by anyone yet. A cloud session tried and could not reach the build tools (`validation.md` has the exact errors).
Do it on your own PC with internet.

**B1. Route 1 first** (`pioarduino` with `custom_sdkconfig`). The draft is `secure_boot_env.draft.ini` next to this file. Copy it into a scratch checkout's
`esp32_firmware/envs/` and run:
```bash
cd esp32_firmware
pio run -e esp32-c3-secure-boot-draft
```
Good: it builds and writes a `bootloader.bin`. If it does not build, or it builds but B2 fails, use route 2 (build the bootloader once with ESP-IDF using the same sdkconfig lines)
and commit **only** `bootloader.bin` under `esp32_firmware/bootloader/`, never a key. Say in `validation.md` which route and why.
Do not move the draft into the real `envs/` until it has built: CI and the firmware bundles read every file there.

**B2. Criterion 2: prove both rollback options (and the security options) are really in the build.** Find the sdkconfig PlatformIO wrote (usually
`.pio/build/esp32-c3-secure-boot-draft/` or the project's `sdkconfig.*` file) and run:
```bash
grep -E "^CONFIG_(SECURE_BOOT=|SECURE_BOOT_V2_ENABLED=|SECURE_BOOT_V2_RSA_ENABLED=|SECURE_FLASH_ENC_ENABLED=|SECURE_FLASH_ENCRYPTION_MODE_RELEASE=|SECURE_ENABLE_SECURE_ROM_DL_MODE=|BOOTLOADER_APP_ROLLBACK_ENABLE=|APP_ROLLBACK_ENABLE=)" <that sdkconfig file>
grep -E "SECURE_BOOT_SIGNING_KEY|SECURE_BOOT_BUILD_SIGNED_BINARIES" <that sdkconfig file>
grep -E "^CONFIG_(SECURE_DISABLE_ROM_DL_MODE|SECURE_BOOT_ALLOW_JTAG|SECURE_BOOT_V2_ALLOW_EFUSE_RD_DIS|SECURE_BOOT_INSECURE|SECURE_FLASH_REQUIRE_ALREADY_ENABLED)=" <that sdkconfig file>
```
Good: all eight lines show `=y`; the second command prints nothing set (signed offline, not in the build); the third prints **nothing at all**.
*(Changed 2026-10-28 for D28: the old list had `CONFIG_SECURE_UART_ROM_DL_MODE=y`, which is a menu title and never appears in an sdkconfig; the setting is `CONFIG_SECURE_ENABLE_SECURE_ROM_DL_MODE`. The third command is new. **`CONFIG_SECURE_DISABLE_ROM_DL_MODE=y` would make the bootloader burn `DIS_DOWNLOAD_MODE`,
which removes your way back: if it shows, STOP.** ESP-IDF v5.4.2 `components/bootloader/Kconfig.projbuild` and `components/bootloader_support/src/esp32c3/secure_boot_secure_features.c`; reasoning and the full list are in `docs/PROVISIONING_SOLD_UNIT.md` section 1.)*
**STOP** if either rollback line is missing or `is not set`: the bootloader would boot and never revert a bad update, and nothing else in the build tells you. Fix the config; do not go on.
Paste these lines into `validation.md` as the evidence for criterion 2 (and the empty third result as evidence for criterion 8).

**B3.** The bootloader must fit before the partition table at `0x8000`. A build that complains about size, or a `bootloader.bin` larger than about 28 KB, is a stop: tell the developer, because the layout would have to change.

## Part C. The hardening, on board 2 (criteria 7 and 8)

*(Rewritten 2026-10-28 for D28: you burn **only the two keys**; the first boot of the hardened bootloader does the rest. Same flow as the guide, `docs/PROVISIONING_SOLD_UNIT.md` section 4. If this part and the guide ever disagree, stop and fix one of them.)*

**Only start this when: part A passed in full (including A7's `pending_verify` and A8's money check and A9's revert) AND part B produced a bootloader with all eight lines.**
Say it out loud: *"This board will be destroyed if I get a step wrong."* It is the sacrificial one.

**C1. Make the key and the digest on your own PC, offline, outside the repository.** (These are the commands; nobody has run them for you.)
```bash
espsecure.py generate_signing_key --version 2 --scheme rsa3072 secure_boot_signing_key.pem
espsecure.py generate_flash_encryption_key flash_encryption_key.bin
```
Back up both files twice, offline, now. If you lose the first, hardened boxes can never take an update again. `git status` in the repository must stay clean of them.

**C2. Sign the hardened bootloader and the app (V+1 image from A5; this signing is separate from `sign_firmware.py`), make the digest, and compare.**
```bash
espsecure.py sign_data --version 2 --keyfile secure_boot_signing_key.pem --output bootloader_signed.bin bootloader.bin
espsecure.py sign_data --version 2 --keyfile secure_boot_signing_key.pem --output app_signed.bin firmware.bin
espsecure.py digest_sbv2_public_key --keyfile secure_boot_signing_key.pem --output sb_digest.bin
espsecure.py signature_info_v2 bootloader_signed.bin
espsecure.py signature_info_v2 app_signed.bin
xxd -p sb_digest.bin
```
Good: the key digest printed for the bootloader, the one for the app, and `sb_digest.bin` are the **same**. **STOP** if they differ: the first-boot bootloader only checks the *app* against the digest in the chip, so a
bootloader signed with the wrong key is accepted on first boot and refused on the next, which is a dead board (guide section 3, step 3).

**C3. Read board 2 and change nothing** (same as A1). All four security fuses must be clear.

**C4. The ordering question is closed (2026-10-28, D28).** The earlier worry was right: the old guide burned `SPI_BOOT_CRYPT_CNT 7` while the flash was still plain, which would have left a board that cannot boot.
That is gone, and so are the hand-burned `SECURE_BOOT_EN`, the `DIS_*` fuses and `ENABLE_SECURITY_DOWNLOAD`: the owner decided (D28) to use the flow ESP-IDF documents. Sources: ESP-IDF v5.4.2 `flash-encryption.rst` ("Flash Encryption Process", "Using Host Generated Key", "Release Mode")
and `secure-boot-v2.rst` (steps 1 to 10, "Key Revocation"); `specs/features/040-sold-box-hardening/efuse-review.md`, items 1, 2, 3, 8 and 7 (`espefuse.py` stops working once secure download mode is on), and its D28 note.

**C5. The steps, in this order** (each prints a summary; **read it before you confirm**):
1. Read the board (C3). All four security fuses clear, no key block in use.
2. Burn the flash-encryption key (cannot be read back, you hold the only copy). **Before** the first boot, and with the tool's default protections (never `--no-read-protect`, `--no-write-protect`, `--no-protect-key`):
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY0 flash_encryption_key.bin XTS_AES_128_KEY
   ```
3. Burn the secure-boot digest:
   ```bash
   espefuse.py -p /dev/ttyUSB0 burn_key BLOCK_KEY1 sb_digest.bin SECURE_BOOT_DIGEST0
   ```
4. **Your last look.** `espefuse.py` stops working once secure download mode is on, so this is the only `summary` you will get:
   ```bash
   espefuse.py -p /dev/ttyUSB0 summary
   ```
   `BLOCK_KEY0`: purpose `XTS_AES_128_KEY`, read- and write-protected. `BLOCK_KEY1`: `SECURE_BOOT_DIGEST0`, write-protected, readable. **Everything else clear**: `SECURE_BOOT_EN`, `SPI_BOOT_CRYPT_CNT`, `SECURE_BOOT_KEY_REVOKE0/1/2`,
   `ENABLE_SECURITY_DOWNLOAD`, **`DIS_DOWNLOAD_MODE`**, every `DIS_*`. If a name does not exist on your version, stop and look it up. Never guess one. **Do not burn anything else. In particular not `SECURE_BOOT_EN`, not `SPI_BOOT_CRYPT_CNT`, not `ENABLE_SECURITY_DOWNLOAD`,
   not `DIS_DOWNLOAD_MODE`**: the bootloader does the first three itself, and the last would remove your way back (criterion 8).
5. Erase, then write the signed images **without a reset afterwards** (GATE 2): `esptool.py -p /dev/ttyUSB0 erase_flash`, then `esptool.py -p /dev/ttyUSB0 --after no_reset write_flash <offsets and signed files>` (esptool 5.x: `no-reset`). Good: `Hash of data verified.` for each.
   The chip must not restart before you mean it to: a restart now **is** the first boot. (That is why the keys come first.)
6. **THE IRREVERSIBLE MOMENT: the first boot.** Say it out loud: *"This board will be destroyed if I get this wrong."* Serial monitor open at 115200 first. Unplug and replug USB (no BOOT button held). **Do not remove power for at least two minutes.**
   The bootloader burns `SECURE_BOOT_EN`, revokes digest slots 1 and 2, switches to secure download mode, burns the JTAG and `DIS_*` fuses, encrypts the flash in place (about a minute) and **then** sets `SPI_BOOT_CRYPT_CNT` (it ends at `0x7` in Release mode; any odd value is right).
   Read-from-source log lines to expect are in guide section 4, step 6. **STOP and unplug** if you see `Generating new flash encryption key` (step 2 did not take) or `Invalid key state`.

**STOP and write down everything on screen** if the board does not boot after step 6 (or never reaches the `Flash encryption completed` stage). This is the "boot loop" case in the guide's section 6; the board cannot be recovered. That is why it is the sacrificial one.

**C6. Criterion 7: repeat A3 to A9 on the hardened board.** Updates now come only through a signed OTA or an image you encrypted on the PC (guide step 7). Good:
the box boots; a **signed** OTA to a newer version works; `ota_image_state` goes `pending_verify` then `valid`; the queued payment from a coin put in before the update is still there and credited once;
a deliberately bad update is reverted (rebuild V+2/V+3 from V+1+, and sign again). **If `pending_verify` never shows, or the bad update is not reverted: GATE 1 has failed on the hardened bootloader.** Boxes do not ship. Report what failed and stop.

**C7. Criterion 8 and the lock-down check** (the guide's GATE 3):
```bash
esptool.py -p /dev/ttyUSB0 read_flash 0x20000 0x2000 app_dump.bin    # refusing is fine in secure download mode
python3 scripts/sold_unit_check.py flash app_dump.bin                # must say: ciphertext
esptool.py -p /dev/ttyUSB0 get_security_info      # espefuse.py cannot run in secure download mode; sold_unit_check.py fuses will fail here
```
Good: `ciphertext`; `Secure Boot: Enabled`; `Secure Boot Key Revocation Status` lists Key1 and Key2 revoked and **not** Key0; `Flash Encryption: Enabled` with `SPI_BOOT_CRYPT_CNT` `0x7` (any odd value is right); JTAG permanently disabled; `Icache in UART download mode: Disabled`.
`get_security_info` answering at all means download mode is still on in its secure form, so `DIS_DOWNLOAD_MODE` was **not** burned (criterion 8); if it does not answer, write that down, the way back is gone. *(Changed 2026-10-28 for D28; names from esptool v5.1.0 `get_security_info`, what a real C3 prints is not known: compare by eye and stop on any difference.)*
Also dump the NVS region (`0x9000`, `0x5000`) and search it for the box's secret
(`python3 scripts/sold_unit_check.py flash nvs_dump.bin --find "<text>"`). **Expect the text to appear in plain**: ESP-IDF says the `nvs` partition cannot be flash-encrypted (v5.4.2 `flash-encryption.rst`, "Encrypted Partition Flag"). Until NVS encryption lands (feature 043) the box's credentials are readable on a hardened unit, and that is a firmware change needed before any sale.
Save only **text results** (not the dumps) in `validation.md`; no key bytes.

## What you owe the repository afterwards (group 5 and 6)

Fill the table in `docs/PROVISIONING_SOLD_UNIT.md` (board, chip revision from A1, date), `validation.md` (real console text, never a summary that shows key bytes), a short note in `docs/KEYS.md`
saying where the secure-boot key lives and that it is separate from the firmware-signing key (no key value, no path on your machine), and a line in `REAL_WORLD_TESTING.md` under the next free RS number
(RS54 and RS55 are taken). Then `bash scripts/check_security_rules.sh` and read `git status` and `git diff --stat` for any key material before you commit.

## Your notes

| Step | Date | Board | Chip revision | What the screen said | Pass / stop |
|---|---|---|---|---|---|
| A1 | | | | | |
| A3 | | | | | |
| A4 | | | | | |
| A7 | | | | | |
| A8 | | | | | |
| A9 | | | | | |
| B2 | | | | | |
| C6 | | | | | |
| C7 | | | | | |
