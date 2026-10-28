# Feature 040: prove the hardening of a box that leaves your hands (GATE 1)

Status: **approved by the owner 2026-10-28 (epic D18). Runs first, in parallel with the deletions (epic D24).**
Branch: `feat/040-sold-box-hardening`   Area: firmware (bench work)   Epic: `specs/spec_03_overhaul.md` feature 040
Release: **none** — this feature ships no software.

## Intent
The owner is going to sell assembled ESP32-C3 coin boxes (epic D17). `docs/PROVISIONING_SOLD_UNIT.md` says a box that leaves
your hands needs Secure Boot V2 and flash encryption, and states plainly that its **GATE 1 has never passed on a board**.
Without it, anyone with a flash reader can read a sold box's per-box secret, and anyone can boot firmware you did not sign.
**Secure boot cannot be retrofitted remotely**, so a box sold unhardened stays unhardened for its life. This feature proves
the procedure on a sacrificial board and records the result. **No box is sold until it passes.**

## Why this is a feature and not a task
It gates revenue rather than code, it has an irreversible step (eFuses), and its evidence belongs in the repository next to
the procedure it proves. It writes **no source code** and produces **no release**.

## Invariants touched
- **Money and time:** not touched. No source change, no release. A hardened board must still keep its coin queue across an
  update, which is part of GATE 1 and is checked explicitly (criterion 5).
- **Only owner-signed software:** this feature *strengthens* it. The secure-boot key is **new, RSA-3072, separate from the
  firmware-signing key**, generated offline and backed up twice, and **never** enters the repository, a cloud session or CI
  (`docs/KEYS.md`, `CLAUDE.md` section 1). Losing it means no more updates for hardened units; leaking it means anyone can
  sign firmware for them.

## Hard safety rules
- **Run every step on a sacrificial board first.** The eFuse burns in `PROVISIONING_SOLD_UNIT.md` section 4 can never be
  undone. A mistake bricks the board permanently.
- **Do not burn eFuses on any box that is in a shop, or on the owner's own working boxes.** Only a board bought to be
  destroyed.
- **Do not commit any key, `.pem`, `.bin` key file, or eFuse summary containing key material.** `scripts/check_security_rules.sh`
  fails the build if a private key is committed; do not work around it.
- **Flash encryption mode is Release**, not Development — Development can be undone and therefore proves nothing.
- Stop at the first step whose result does not match the guide and write down what happened. A half-understood eFuse step is
  the one that costs a board.

## Scope — what this feature does
1. Resolve the **Arduino caveat** in `PROVISIONING_SOLD_UNIT.md` section 1: the prebuilt Arduino libraries PlatformIO fetches
   are built *without* `CONFIG_SECURE_BOOT` and `CONFIG_SECURE_FLASH_ENC_ENABLED`. Pick one of the guide's two routes
   (`pioarduino` with `custom_sdkconfig`, or build the bootloader once with ESP-IDF and commit only `bootloader.bin` under
   `esp32_firmware/bootloader/`, never the key) and **prove it builds**.
2. Keep `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y` and `CONFIG_APP_ROLLBACK_ENABLE=y`. The firmware relies on them
   (`OtaRollback.cpp`). A bootloader without them still boots, and nothing in the build says so — the dashboard's
   `ota_image_state` is how you tell (it must read `pending_verify` for about a minute after an OTA, then `valid`).
3. Flash the signed build to a spare board with **no eFuses burned** and run GATE 1.
4. Only once GATE 1 passes: run the eFuse steps on the sacrificial board and confirm a hardened board still updates.
5. Record every result in the table at the end of `PROVISIONING_SOLD_UNIT.md`.

## Success criteria
Each becomes a line in `validation.md`, with the actual console output.
1. A bootloader with `CONFIG_SECURE_BOOT_V2_ENABLED`, `CONFIG_SECURE_FLASH_ENC_ENABLED`,
   `CONFIG_SECURE_FLASH_ENCRYPTION_MODE_RELEASE` and `CONFIG_SECURE_UART_ROM_DL_MODE` **builds**, by whichever of the guide's
   two routes was chosen. Say which route and why.
2. Both rollback options are enabled in that build. Show the sdkconfig lines.
3. The board **boots** the signed app under that bootloader with no eFuses burned.
4. A **signed OTA succeeds** on that board.
5. The box's **coin queue survives the update**: queue a payment, update, confirm it is still there and is credited exactly
   once afterwards. This is the `CLAUDE.md` section 6 money check for this feature — say exactly how you checked it.
6. A **deliberately bad update is reverted** (`docs/REAL_WORLD_TESTING.md` item H4), and `ota_image_state` was observed going
   `pending_verify` → `valid` after a good one. If it never shows `pending_verify`, the bootloader has no rollback: **stop,
   GATE 1 has failed.**
7. Only after 1-6: the eFuse steps run on the sacrificial board, and it still boots, runs a signed OTA and keeps its queue.
8. Secure download mode is on (`ENABLE_SECURITY_DOWNLOAD`); `DIS_DOWNLOAD_MODE` is **not** burned, so there is a way back
   until OTA recovery is proven on a hardened board. JTAG disabled (`DIS_USB_JTAG`, `DIS_PAD_JTAG`).
9. The result table in `docs/PROVISIONING_SOLD_UNIT.md` is filled in, including the board, chip revision
   (`esptool.py chip_id`) and date.
10. `bash scripts/check_security_rules.sh` passes and `git status` shows **no key material** of any kind.
11. A short note in `docs/KEYS.md` saying where the secure-boot key lives and that it is separate from the firmware-signing
    key. **No key value, no path inside anyone's machine.**

## Out of scope
- Any source change, any release, any change to the normal (unhardened) build used in the owner's own shops.
- Per-unit flash-encryption key management at production scale (one key per unit, `MAC → key` in the password manager). That
  is a procedure for when boxes actually ship; this feature only proves the procedure works at all.
- Anything about the coin acceptor, the enclosure or assembly.

## Owner decisions
All answered. **D18 = blocking before any sale. D24 = runs first, in parallel with the deletions.** If GATE 1 fails, boxes do
not ship; report what failed and stop rather than working around it.
