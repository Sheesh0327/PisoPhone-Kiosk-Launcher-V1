# Feature 043: the box's secrets must not be readable on a sold box

Status: **draft, needs the owner. Blocking before any box is sold, alongside feature 040.**
Branch: `feat/043-nvs-secrets-at-rest`   Area: firmware   Epic: `specs/spec_03_overhaul.md` feature 043
Release: firmware (**sold units only** — see "The rule that must not be broken")

## Intent
The owner is going to sell assembled boxes (epic D17), and feature 040 hardens them with Secure Boot V2 and flash encryption
so that "a buyer cannot read the firmware or the stored secrets with a flash reader"
(`docs/PROVISIONING_SOLD_UNIT.md`, opening). **That promise is not kept today.** Flash encryption does not cover the NVS
partition, and NVS is exactly where the secrets are. A buyer with a flash reader could read a hardened box's per-box shared
secret and the router gateway key, which is the whole thing hardening exists to prevent.

## What was found (read in the code on 2026-10-28)
1. **ESP-IDF does not flash-encrypt NVS.** The NVS library is not directly compatible with flash encryption; protecting NVS
   needs **NVS encryption**, which is a separate mechanism with its own key partition. (Found during the feature 040 eFuse
   review: `specs/features/040-sold-box-hardening/efuse-review.md`, item 14, cited against ESP-IDF v5.4.2 sources.)
2. **`esp32_firmware/partitions_encrypted.csv` marks `nvs` as `encrypted`.** That flag is ignored for an NVS partition, so the
   file reads as though the secrets were protected when they are not. This is the most dangerous part of the finding: anyone
   checking the partition table would reasonably conclude the job was done.
3. **There is room.** The table leaves 0x11000-0x20000 unused (60 KB) between `phy_init` and `ota_0`, so a 4 KB `nvs_keys`
   partition fits with nothing else moving. `otadata` must stay at 0xe000 and `ota_0` must stay 64 KB aligned, and both do.
4. **`CredCrypto.h` is not at-rest encryption.** Despite the name it is HMAC, base64/hex and ECDSA *verification* helpers; its
   own comment says the name is historical. Nothing encrypts a secret before it goes into NVS.
5. **What is in NVS:** `Config.cpp` (the per-box secret and the admin password), `GatewayCoinslot.cpp` (the router gateway
   key), `PaymentQueueManager.cpp` and `OrphanPayments.cpp` (queued payments), `AccountStorage.cpp` (a marker),
   `PhoneProfileStore.cpp`, `Diagnostics.cpp`.

## THE RULE THAT MUST NOT BE BROKEN
**No image that an existing box can take over the air may expect encrypted NVS.** An existing box's NVS holds plaintext. An
image that reads it as encrypted would lose the box's secret, its queued payments and its accounts — money destroyed in the
field by an update. This is a rule 1 violation and the single way this feature could do real harm.

Today's separation helps and must be preserved: the `*-production-encrypted` environments are built **only on a manual
workflow run with "bundle" on** and shipped as owner zips (`.github/workflows/firmware-images.yml`,
`scripts/make_firmware_bundles.py`), while the over-the-air feed is built from the ordinary environments.

**This creates the central unresolved question, which group 0 must answer before any code is written:** a hardened box in a
shop still needs signed updates. If the feed serves an image built without NVS encryption, a hardened box that takes it may be
unable to read its own NVS. Either the feed must be able to serve a matching image to hardened boxes, or the chosen approach
must be one where the same image works on both. **Do not start group 1 until this is answered and written down.**

## Two candidate approaches — group 0 chooses, with evidence
**A. ESP-IDF NVS encryption.** Add an `nvs_keys` partition, enable NVS encryption, initialise NVS with the secure variant.
Standard and documented. Risks: Arduino's `Preferences` calls the ordinary init, so this may need more than a build flag; and
it is the approach most likely to break the over-the-air question above.

**B. Encrypt the few secret values in the application, before they reach NVS.** Leaves the partition table and every other
NVS user alone, and one image can read both (a value is encrypted or it is not, and the code can tell). Risks: it is new
crypto in money-adjacent firmware; `CLAUDE.md` section 6 limits what may be used; and the key has to live somewhere a flash
reader cannot reach, which is its own question.

Group 0 names the choice **and the reason**, in this spec, before any code.

## Invariants touched
- **Money and time:** `PaymentQueueManager` and `OrphanPayments` keep queued payments in NVS. Whatever is chosen must not lose
  a queued payment, on a new box or across an update, and must not change how a payment is credited. Criterion 6 proves it.
- **Only owner-signed software:** not weakened. No change to signing, the manifest check or rollback. If approach A changes
  how the image is built, say exactly how the feed stays signed and how a hardened box knows an image fits it.

## Constraints
- Crypto stays `mbedtls` as already used (`CredCrypto.h`) — no new library (`CLAUDE.md` section 6).
- The box <-> phone contract is not touched; no protocol change, so `protocol/gen_vectors.py` and the contract test are
  untouched. If you find yourself editing them, stop: you are outside this feature.
- `partitions_encrypted.csv`'s misleading `encrypted` flag on `nvs` is corrected **whatever approach is chosen** (criterion 2)
  — a comment saying why, so nobody re-adds it.
- Firmware code needs a higher `PISO_FW_VERSION` or boxes ignore it (`CLAUDE.md` section 4).
- Pure logic goes in `include/` with a host test, as the rest of the firmware does.

## Success criteria
1. **Group 0's answer is written into this spec**: the approach, the reason, and how a hardened box takes over-the-air updates
   without losing its NVS. Nothing else in this list may be attempted first.
2. `partitions_encrypted.csv` no longer claims `nvs` is `encrypted`, and carries a comment saying why it cannot be.
3. A host test in `esp32_firmware/host_tests/` covers the new pure logic (whatever it is), and `pio test` / the host test
   runner passes.
4. On a **new** hardened box, the per-box secret and the gateway key are **not** readable in a raw flash dump. Shown by
   `esptool.py read_flash` of the NVS region and a search for the known value. This is a bench step; write it into
   `specs/features/040-sold-box-hardening/bench.md` so it runs with the rest of GATE 1.
5. An **existing** box updating to the ordinary (non-hardened) build of this firmware keeps its secret, its admin password,
   its accounts and its queued payments. Proved on a board, and the rule above means this is the criterion that decides
   whether the feature may ship at all.
6. **Money:** a payment queued before the change is still credited exactly once after it. Say how you checked
   (`CLAUDE.md` section 6).
7. `PISO_FW_VERSION` raised; `bash scripts/check_security_rules.sh` passes; `clang-format` clean.
8. `docs/PROVISIONING_SOLD_UNIT.md`'s opening promise ("a buyer cannot read the firmware or the stored secrets") is true, or
   its wording is corrected to say exactly what is and is not protected.

## Out of scope
- Feature 040's eFuse work. This feature assumes it.
- `scripts/sold_unit_check.py` moving to `esptool get_security_info` (flagged by the eFuse review; its own small change).
- Encrypting SPIFFS or the app: flash encryption already covers those.
- Any change to what the box stores, or to the phone or router.

## Owner decisions
- **D26 — does this block the first sale?** Proposed: **yes.** Selling a box advertised as hardened whose secrets are readable
  is worse than selling an honestly unhardened one. The alternative is to sell with the limitation written down plainly.
- **D27 — approach A or B?** Group 0 recommends with evidence; the owner picks, because B means new crypto in firmware that
  handles money and A has the over-the-air risk.
