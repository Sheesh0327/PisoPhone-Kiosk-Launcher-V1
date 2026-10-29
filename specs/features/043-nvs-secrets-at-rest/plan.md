# Plan: feature 043, the box's secrets must not be readable on a sold box

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | how to read |
|---|---|---|
| `specs/features/040-sold-box-hardening/efuse-review.md` | item 14 and its citations: why NVS is not covered | read items 14 and 2 |
| `esp32_firmware/partitions_encrypted.csv` | the partition table and the free gap | 15 lines, read in full |
| `esp32_firmware/src/Config.cpp` | the box secret and the admin password in NVS | **818 lines — grep for `Preferences`, `putString`, `putBytes` and read only those** |
| `esp32_firmware/src/PaymentQueueManager.cpp` | queued payments in NVS (the money) | grep for the NVS calls |
| `esp32_firmware/src/GatewayCoinslot.cpp` | the router gateway key | grep |
| `esp32_firmware/include/CredCrypto.h` | what crypto already exists (verification only) | read the header comment and the function list |
| `.github/workflows/firmware-images.yml` | how the ordinary and production images are built and published | read lines ~60-115 and the publish job |

Read nothing else. Do not read `WebAssets.h` or anything else generated.

## Task groups
**Group 0 is the whole risk of this feature. Do not skip it, do not do it quickly, and do not start group 1 until the owner
has seen its answer.**

- [ ] **0. Decide the approach, and answer the over-the-air question.** Read how `firmware-images.yml` builds and publishes,
      and establish: if a hardened box is in a shop and the feed offers it the next signed image, what happens to its NVS?
      Then compare approach A (ESP-IDF NVS encryption) and B (encrypt the secret values in the application), say which and
      why, and write the answer into `spec.md` under criterion 1. **Stop and report. No code.**
- [ ] **1. Fix the misleading partition flag.** Remove `encrypted` from the `nvs` line in `partitions_encrypted.csv` and add
      a comment saying why NVS cannot be flash-encrypted, citing the review. Criterion 2. This is safe and worth doing
      whatever group 0 decided. (test: the partition-table test in `scripts/make_firmware_bundles.py`'s test, if it covers it)
- [ ] **2. The pure logic, test first.** Whatever approach 0 chose, the decision part goes in `include/` with a host test in
      `esp32_firmware/host_tests/`. Write the test, run it, see it fail, log the red result in `ledger/`, then fix.
      Criterion 3.
- [ ] **3. Wire it in.** The smallest change to the NVS users that the approach needs. Touch `PaymentQueueManager` and
      `OrphanPayments` **last and most carefully**: they hold money.
- [ ] **4. The upgrade path.** An existing box's plaintext NVS must still be read by the ordinary build. Criterion 5 is the
      one that decides whether this ships. Write the test for it before the code that needs it.
- [ ] **5. Bench steps.** Add the raw-flash-dump check (criterion 4) and the queued-payment check (criterion 6) to
      `specs/features/040-sold-box-hardening/bench.md`, so they run with GATE 1 rather than as a separate trip to the board.
- [ ] **6. Gates and release.** Criterion 7: raise `PISO_FW_VERSION`, `bash scripts/check_security_rules.sh`,
      `clang-format -i` with `esp32_firmware/.clang-format`, the firmware host tests. Criterion 8: correct the promise in
      `docs/PROVISIONING_SOLD_UNIT.md` or make it true.

## Release
Firmware. `PISO_FW_VERSION` raised. **Read the rule in `spec.md` ("THE RULE THAT MUST NOT BE BROKEN") before publishing
anything**: if the chosen approach makes an image that an existing box cannot safely take, it must not go into the ordinary
over-the-air feed at all, and the spec must say how hardened boxes are updated instead.

## Hardware check
A new hardware check (next free RS number — RS53, RS54 and RS55 are taken by features 032, 040 and 041): on a hardened board,
a raw flash dump of the NVS region does not contain the box secret or the gateway key; and an existing box updated to the
ordinary build keeps its secret, accounts and queued payments. Both are written into `bench.md` by group 5.
