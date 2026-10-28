# Feature 043, group 0: findings and recommendation

Written 2026-10-28. Paper only: no code, no hardware, nothing built. `partitions_encrypted.csv` and `PISO_FW_VERSION` are untouched.
The owner decides D27 after reading this.

## How this was checked, and its limits

`docs.espressif.com` is blocked here, so (as in `specs/features/040-sold-box-hardening/efuse-review.md`) I read documentation **sources** and code at
release tags through the proxy. **Verified** = I read it in the source named. **Reasoned** = my inference, not stated by a source.
Prefixes: **IDF5** = `https://github.com/espressif/esp-idf/blob/v5.4.2/`, **IDF4** = `https://github.com/espressif/esp-idf/blob/v4.4.8/`,
**ARD** = `https://github.com/espressif/arduino-esp32/blob/2.0.17/`, **PLAT** = `https://github.com/platformio/platform-espressif32/blob/v7.1.3/`.

Which Arduino core the boxes run: `esp32_firmware/envs/*.ini` pin `platform = espressif32 @ 7.1.3`; `PLAT platform.json` asks for
`framework-arduinoespressif32 ~4.20017.0`, which is **Arduino-ESP32 2.0.17 (ESP-IDF 4.4.x)**. I read the 2.0.17 repository and ESP-IDF **v4.4.8**
files for the IDF underneath it. The exact 4.4 patch inside 2.0.17 is not something I could confirm, and I did not run a build to see which package
PlatformIO resolves. The v5.4.2 documents describe a newer IDF than the one in the app; where that matters I say so.

## 1. The over-the-air answer (what the owner is waiting on)

**Question:** a hardened box in a shop is offered the next signed image from the feed. What happens to its NVS?

**Short answer: nothing in the image or the feed says which kind of box it is for, so today the outcome is decided by what the image's NVS code
expects. With the stock build there is no way to make the image expect encrypted NVS at all (section 3); and a hardened box would not boot an ordinary
feed image for a different reason (section 2) before NVS comes into it.**

What the code does (all **verified** by reading it):

- **What reaches the feed.** `.github/workflows/firmware-images.yml` builds six `*-dev*` environments only (`esp32-c3-dev`, `esp32dev-dev`, and their
  `-phone`/`-wifi` variants), copies each `firmware.bin` to `ota/<product>/firmware-<chip>.bin`, and on `main` runs
  `scripts/publish_firmware_ota.py --product full|phone|wifi`. The `*-production-encrypted*` environments are built only when the manual run has
  `bundle` on, and go only into the owner zips on the `firmware-bundles` branch. The feed is keyed by **product** and **chip**; no part of it is keyed by
  hardened or not.
- **What a box checks before taking an image.** `src/OtaSecurity.cpp` (via `include/FwManifest.h`): a signature over
  `pisophone-fw-v1|<chip>|<version>|<sha256>|<size>`, the chip name equal to this chip, and the version **newer** than `PISO_FW_VERSION` (older only with
  the owner's admin QR, `allow_downgrade`, `OtaSecurity.cpp:62-72`). `src/WebServerModule.cpp:278-290` adds only the ESP image magic and the chip id in the
  image header (`include/OtaCheck.h`). Nothing in the manifest, the header check or the version compare says "for a hardened box" or "for an ordinary one".
  So `PISO_FW_VERSION` plus chip plus product decide, as the question guessed.
- **What an update touches.** The upload goes through the Arduino `Update` class (`WebServerModule.cpp:264`, `Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)`):
  it writes only the next app slot. It never writes the partition table, the bootloader or the NVS partition. So an update cannot change an NVS's
  format; the **new code** is what meets the old NVS on the next boot.
- **Flash encryption is not the problem for the app slot.** IDF5 `docs/en/security/flash-encryption.rst`, "OTA Updates": writes to encrypted partitions
  through `esp_partition_write` are encrypted automatically; a plaintext app image can be sent over the air.

### 1a. Why "an image that expects encrypted NVS" is destructive (verified, IDF4 `components/nvs_flash/src/nvs_page.cpp`)

NVS encryption is XTS-AES over every entry **except the page headers** (IDF4 `components/nvs_flash/Kconfig`, `NVS_ENCRYPTION` help text). When a page
is loaded, `Page::mLoadEntryTable()` (about line 548 on) reads each written entry and, if its CRC does not match, **erases the entry in flash**
(`if (item.crc32 != item.calculateCrc32()) { err = eraseEntryAndSpan(i); ...`, about line 670). An entry read with the wrong key, or with no key,
fails that CRC. So a mismatch between the NVS format and the code does not just fail to read; **the loader destroys the entries**:

- plaintext NVS read by a secure-init image: every entry fails its CRC and is erased (the secret, accounts, queued payments);
- encrypted NVS read by plain init (which is what `initArduino()` does, section 3): the same.

The second case is not hypothetical, because of section 3.

### 1b. What actually happens to a hardened box offered an ordinary feed image today

**Reasoned, not tested; this is a feature 040 matter, found while answering 043.**

- A Secure Boot V2 box runs only images that carry a **signature block** made with the owner's offline Secure Boot key. IDF5
  `docs/en/security/secure-boot-v2.rst` line 117: the image "is not only verified on every boot but also on each over the air (OTA) update. If the
  currently selected OTA app image cannot be verified, the bootloader will fall back and look for another correctly signed application image"; and
  `flash-encryption.rst` line 871: OTA is allowed "provided that the new app is signed correctly with the Secure Boot signing key".
- The feed images are signed only as a **manifest** (the owner's firmware-signing key, in CI). They carry no Secure Boot block, and the Secure Boot key is
  deliberately never in CI (`docs/PROVISIONING_SOLD_UNIT.md` section 3: "Hardened boxes check both").
- The stock Arduino libs are built with `# CONFIG_SECURE_BOOT is not set` (ARD `tools/sdk/esp32c3/sdkconfig` line 83), so the **app** does not check the
  block when it finishes the upload. *Reasoned:* `Update.end()` succeeds, the dashboard shows SUCCESS, the box restarts, and the **bootloader** then
  refuses the new slot and boots the old one. The update silently never takes effect; the NVS is not touched.

**Consequence for the question:** an ordinary feed image cannot hurt a Secure-Boot-hardened box's NVS, because it would not run. But that is luck, not
a design, and it means **hardened boxes currently have no working update path from the feed at all**. 040 has to settle that (for example: the owner
signs a hardened image offline with the Secure Boot key and publishes it to a separate feed; or appends a block to the one feed image, which an
unhardened box would, *reasoned*, simply ignore). Whichever the owner picks, 043's choice must survive it:

- if hardened boxes get a **separate** image, 043 may use a build that only hardened boxes run;
- if one image goes to **both** kinds (the cheapest feed), 043 **must** use a design where one image reads both NVS formats.

I recommend designing for the second, because it needs no new feed, no new publish step and no new "which image for which box" check in the box.

## 2. Can `Preferences` read an encrypted NVS? (approach A, verified)

`Preferences::begin()` (ARD `libraries/Preferences/src/Preferences.cpp:30-52`) calls `nvs_open()` on the default partition, or
`nvs_flash_init_partition(label)` plus `nvs_open_from_partition()` when a label is given. It never chooses how NVS was initialised, so it **would** work
against an encrypted partition if NVS had been brought up with the secure init. The blockers are elsewhere:

1. **`nvs_flash_secure_init*` is not in the stock library.** IDF4 `components/nvs_flash/src/nvs_api.cpp`: `nvs_flash_secure_init_partition` and
   `nvs_flash_secure_init` exist only `#ifdef CONFIG_NVS_ENCRYPTION`. IDF4 `Kconfig`: `NVS_ENCRYPTION ... depends on SECURE_FLASH_ENC_ENABLED`. The
   stock Arduino libs have `# CONFIG_SECURE_FLASH_ENC_ENABLED is not set` (ARD `tools/sdk/esp32c3/sdkconfig:84`, and the same line in
   `tools/sdk/esp32/sdkconfig`). So on the build the boxes run, the function **is not compiled in**; the app cannot call it.
2. **`initArduino()` runs the plain init first and erases on error.** ARD `cores/esp32/esp32-hal-misc.c:249-256`: `nvs_flash_init()`, and if that
   returns `NO_FREE_PAGES` or `NEW_VERSION_FOUND` it **erases the whole NVS partition** and inits again. This runs before `setup()`. On an encrypted
   NVS, the plain init loads pages and, per 1a, erases every entry. So even with a key partition and the right data, the stock core would wipe it on boot.
3. **A custom sdkconfig is the only way.** `NVS_ENCRYPTION=y` needs the app libs rebuilt: feature 040's own draft
   (`secure_boot_env.draft.ini`) does that with the `pioarduino` platform, which is "a different Arduino core and ESP-IDF than the espressif32 @ 7.1.3 the
   real boxes run ... building the APP with it would change every box". 040 deliberately uses it for the **bootloader only**.
4. **With `NVS_ENCRYPTION=y`, a missing `nvs_keys` partition is a hard failure, not a fallback.** IDF4 `nvs_api.cpp` `nvs_flash_init()`: with the option
   on and no `nvs_keys` partition it logs "CONFIG_NVS_ENCRYPTION is enabled, but no partition with subtype nvs_keys found" and returns `ESP_FAIL`. An
   existing box has no such partition (`min_spiffs.csv`), so such an image on an existing box has **no NVS at all** until rolled back. Nothing is erased
   by that path (`ESP_FAIL` is not one of the two errors `initArduino` erases on), *reasoned*, but the box cannot read its secret, accounts or queue.
5. **HMAC scheme (key in eFuse, no key partition) does not rescue it.** IDF5 `nvs_encryption.rst` documents it, but it still goes through
   `nvs_flash_secure_init`, so point 1 applies; and `SOC_HMAC_SUPPORTED` is defined for the C3 and **not** for the classic ESP32 (IDF4
   `components/soc/{esp32c3,esp32}/include/soc/soc_caps.h`), and the repo ships both chips.

Not established: whether a static constructor or an early hook could run the secure init before `initArduino()`. I did not look for one: it would be
a hack around the core in the one place where a mistake erases money, and point 1 (the function is not compiled in) stops it anyway.

## 3. The comparison

| | **A. ESP-IDF NVS encryption** | **B. Encrypt the secret values in the application** |
|---|---|---|
| Works on the stock build the boxes run | **No** (section 2, points 1-2) | Yes: `MBEDTLS_AES_C=y`, `MBEDTLS_GCM_C=y`, hardware AES on, in both chips' stock sdkconfig |
| What must change first | Move the app to a custom-sdkconfig Arduino/IDF build (a platform migration for every box) | A new 4 KB partition, in `partitions_encrypted.csv` only |
| Over-the-air | The NVS format becomes a property of the image build. The ordinary image cannot be the hardened one; needs two image families, two feeds, and a "for whom" check the box does not have | One image: a value is encrypted or it is not, and the code can tell. No change for existing boxes |
| Existing box taking the update | Needs the old and new NVS formats side by side, or a migration of live money data | Nothing is converted. The ordinary path stays plaintext, byte for byte as today |
| Queued payments | Inside the encrypted NVS: every payment is exposed to the init/migration risk (1a) | **Not touched** (`PaymentQueueManager`, `OrphanPayments` stay as they are) |
| What it protects | All of NVS, including the queue, totals, profiles | Only the named secret values (below) |
| New crypto in firmware | None written by us | Yes: AES-256-GCM through mbedtls, about 60 lines, host-testable |
| Where the key lives | `nvs_keys` partition, flash-encrypted | A key partition, flash-encrypted (same trust anchor) |

## 4. Recommendation: **B**, with the key in a flash-encrypted partition

**Why.** A cannot be done on the build the boxes run, and the way to make it possible (a different Arduino/IDF) is a platform migration whose risk lands
on every shop's money; it also puts the OTA problem of section 1 on the owner. B reaches the goal the spec actually states, the secrets, with no
change to any existing box and no change to the queue.

**What B is.**

1. A 4 KB partition `boxkey` (`data`, a custom subtype in 0x40-0xFE, flag `encrypted`) at **0x11000**, in `partitions_encrypted.csv` only. The gap
   0x11000-0x20000 is free (spec "What was found" 3). The ordinary tables do not get it, so existing boxes are unchanged.
2. The box **is hardened** only when both are true: the partition exists **and** flash encryption is on (the API is `esp_flash_encryption_enabled()`;
   not checked in the IDF 4.4 headers, see "could not establish"). Otherwise the code takes today's plaintext path unchanged. This is deliberate:
   with flash encryption off, a key in flash protects nothing, so the code does not pretend.
3. On a hardened box the first boot makes a random 256-bit key, writes it to `boxkey` (the write is encrypted by flash encryption), **reads it back and
   compares**, and only then encrypts any value. The key is written once and never rewritten.
4. The secret values are stored as `enc1:` + base64(`iv(12) | ciphertext | tag(16)`) using AES-256-GCM, with the NVS key name as the additional
   authenticated data (so a value cannot be moved under another name). A value without the `enc1:` prefix is read as plaintext and, on a hardened box,
   re-saved encrypted. One image reads both.
5. The values: `shared_secret`, `admin_pw`, `setup_ap_pass`, `wifi_pass`, `kiosk_wifi`, `gw_key`, `legacy_key` (`Config.cpp:355-374`,
   `GatewayCoinslot.cpp:15`). **That is seven, not "the shared secret and the gateway key" the spec names;** the admin password and the shop Wi-Fi
   password are plaintext in NVS too (`Config.cpp:609`, `:640`). Wi-Fi driver credentials are not an extra: `main.cpp:237` sets `WiFi.persistent(false)`.

**How a hardened box takes an update without losing its NVS.** Under B the NVS format never changes, so any image can read it; the update touches
only the app slot (section 1). The one rule B adds: **a hardened box must never run an image older than the first B-aware one**, because an older
image would read `enc1:...` as the secret. That holds because every hardened box is flashed fresh with a B-aware image (no hardened box exists yet; the
owner should confirm), so there is no older image on it to roll back to; the only way to go older is the owner's downgrade
(`OtaSecurity.cpp:62-72`), which needs the admin QR. Group 4 will write that as a test and as a line in `PROVISIONING_SOLD_UNIT.md`.

**What it costs.**

- **New crypto in money-adjacent firmware.** It is one standard primitive (mbedtls GCM, already in the stock libs), not a new library; it is not
  `CredCrypto.h` (that is HMAC, base64/hex and ECDSA verification only, section "What was found" 4 in the spec).
- **Not everything is protected.** The queued payments, revenue totals, profiles and the accounts file stay readable in NVS (SPIFFS is covered
  by flash encryption). `docs/PROVISIONING_SOLD_UNIT.md` must say "credentials are protected; the box's revenue figures and queue are not" (criterion 8).
- **A lost key is a lost secret.** If `boxkey` is erased or corrupted on a hardened box, its encrypted values cannot be read and the box is, in effect,
  unprovisioned (its phones and router would have to be paired again). It cannot lose a payment (the queue is separate), but it is a real failure
  mode; group 2 must define what the box does (refuse to start with a clear message, never silently regenerate a key over existing `enc1:` values).
- **Rollback to an older image on a hardened box is unsafe** (above).
- **Random numbers.** The key must come from the hardware RNG at a time it is truly random (in ESP-IDF, `esp_fill_random` is, as I understand it, only
  guaranteed random while Wi-Fi or Bluetooth is running, or with the bootloader RNG source on). *Reasoned, not checked in source:* generate it after Wi-Fi
  is up, or from `mbedtls_ctr_drbg` seeded by the entropy source; group 2 must check which.
- **A practice board has no flash encryption**, so the encrypting path would not run there. The host test and a bench step with a debug override
  cover the logic; the real check is criterion 4 on a hardened board.
- **Effort:** one new header with a host test, changes in `Config.cpp` and `GatewayCoinslot.cpp` (the NVS reads and writes of seven values), one
  partition line, bench steps. No change to the queue, the phone protocol, the router or the feed.

**An alternative I considered and do not recommend:** keep the secrets themselves in the encrypted raw partition, with no crypto code at all (flash
encryption does the work). It writes less crypto, but the partition then needs its own atomic update scheme (two slots, CRC, sequence), because a
power cut between an erase and a write would destroy the box secret. That is a small NVS written from scratch in a box that already has a good one.
B keeps NVS's atomic updates and writes the key once.

**Also fine, cheaper, and honest:** sell hardened boxes **without** at-rest protection of credentials and reword the promise (criterion 8). That is D26's
"alternative". It costs nothing in code and risks nothing in the field, but it means a buyer with a flash reader gets the per-box secret and the shop's
Wi-Fi password. I recommend B only because the owner wants the promise kept.

## 5. What I could not establish

- **The exact Arduino package PlatformIO resolves** for `espressif32 @ 7.1.3` (read from `platform.json` as `~4.20017.0`), and the exact IDF 4.4 patch inside
  Arduino-ESP32 2.0.17 (I read v4.4.8). Section 2 point 1 rests on the 2.0.17 `sdkconfig` in the Arduino repo being the one the libs were built from.
- **Whether `esp_flash_encryption_enabled()` is available in that IDF's headers** and what it reads on a C3 in Release mode. Group 2 must check before
  using it.
- **Whether the `esp_partition_*` write to an `encrypted` custom-subtype partition has an alignment rule that fits a 32-byte key** (writes to encrypted
  flash are, as I understand it, 16-byte aligned; IDF5 `flash-encryption.rst` says OTA writes via `esp_partition_write` are encrypted automatically but I did not
  find the alignment rule in the files I read). A 4 KB partition written as one aligned block avoids the question; verify on a board.
- **Section 1b is reasoned only:** that an unsigned (no Secure Boot block) feed image makes `Update.end()` succeed and the bootloader then fall back to
  the old slot. It is consistent with IDF5 `secure-boot-v2.rst` lines 117 and 131 and with the stock app having no `CONFIG_SECURE_BOOT`, but nobody has
  run it. It belongs on feature 040's practice board: send a plain feed image to a Secure-Boot board and see what the dashboard and `ota_image_state` say.
- **That no hardened box exists in the field.** The spec says boxes are "going to be sold"; I did not read `specs/RELEASE_STATE.md` as told. If one
  exists, it holds plaintext secrets and needs the one-time migration of B step 4 plus a decision on how it gets a `boxkey` partition (it cannot get one
  over the air; the partition table is not part of an update).
- **Whether `make_firmware_bundles.py` and its test accept an extra partition** (they check every partition flagged encrypted and boot_app0 against
  otadata). Group 1 will find out.
- **What queued-payment records contain.** I did not open them; if they hold a credential, B would not cover it.
- **Anything run on hardware.** Nothing here was built or flashed.

## 6. Sources read

- ESP-IDF v5.4.2, `docs/en/api-reference/storage/nvs_encryption.rst`, `docs/en/security/flash-encryption.rst` (lines 748, 776, 836, 871, 909), `docs/en/security/secure-boot-v2.rst` (lines 117, 131).
- ESP-IDF v4.4.8, `components/nvs_flash/src/nvs_api.cpp`, `src/nvs_page.cpp`, `Kconfig`, `include/nvs_flash.h`; `components/soc/esp32c3/include/soc/soc_caps.h`, `components/soc/esp32/include/soc/soc_caps.h`.
- Arduino-ESP32 2.0.17, `cores/esp32/esp32-hal-misc.c`, `libraries/Preferences/src/Preferences.cpp`, `tools/sdk/esp32c3/sdkconfig`, `tools/sdk/esp32/sdkconfig`.
- platform-espressif32 v7.1.3, `platform.json`.
- This repository: `.github/workflows/firmware-images.yml`, `scripts/publish_firmware_ota.py` (header), `esp32_firmware/src/OtaSecurity.cpp`, `src/WebServerModule.cpp` (OTA handler), `include/OtaCheck.h`, `include/FwManifest.h`, `include/CredCrypto.h`, `src/Config.cpp` and `src/GatewayCoinslot.cpp` (NVS keys only, by grep), `src/AccountStorage.cpp` (mount), `docs/PROVISIONING_SOLD_UNIT.md`, `specs/features/040-sold-box-hardening/{spec,efuse-review,secure_boot_env.draft.ini}`.
