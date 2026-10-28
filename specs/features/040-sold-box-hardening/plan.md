# Plan: feature 040, prove the hardening of a sold box

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | how to read |
|---|---|---|
| `docs/PROVISIONING_SOLD_UNIT.md` | the procedure this feature proves | read in full — it is the spec for the bench work |
| `docs/KEYS.md` | where keys live and what must never be committed | grep for the rules, do not read whole |
| `esp32_firmware/envs/esp32_c3_prod.ini` | the `esp32-c3-production-encrypted` build | read |
| `esp32_firmware/src/OtaRollback.cpp` | what the rollback config is relied on for | grep only |
| `docs/REAL_WORLD_TESTING.md` | item H4, the bad-update revert | grep for H4 |

**This feature needs physical hardware and cannot be completed in a cloud session.** An agent can do groups 1 and 2; groups 3
to 6 need a board on the owner's bench.

## Task groups
- [ ] **1. Build the bootloader (can be done without a board).** Resolve the Arduino caveat: try the `pioarduino`
      `custom_sdkconfig` route first, and fall back to the ESP-IDF route if it does not build. Prove criteria 1 and 2.
      Commit only `bootloader.bin` if the IDF route is used — **never a key**. (files: `esp32_firmware/envs/`,
      possibly `esp32_firmware/bootloader/`)
- [ ] **2. Write the bench script.** A step-by-step the owner can follow at the board without re-reading the whole guide:
      every command in order, what good output looks like, and the stop conditions. Put it in
      `specs/features/040-sold-box-hardening/bench.md`. **Nothing irreversible before criterion 6 has passed.**
- [ ] **3. (Owner, at the bench) GATE 1 on a spare board, no eFuses.** Criteria 3, 4, 5, 6. If 6 fails, stop here.
- [ ] **4. (Owner, at the bench) The eFuse steps on the sacrificial board.** Criterion 7 and 8. Irreversible — only after
      group 3 passed in full.
- [ ] **5. Record the evidence.** Fill the table in `docs/PROVISIONING_SOLD_UNIT.md` (criterion 9) and `validation.md`.
      Add the `docs/KEYS.md` note (criterion 11).
- [ ] **6. Gates.** `bash scripts/check_security_rules.sh`, and a careful `git status` / `git diff --stat` read to be certain
      no key material is staged (criterion 10).

## Release
**None.** No `setup/RELEASE`, no `PISO_FW_VERSION`, no app build. This feature ships nothing to any device in the field.

## Hardware check
This feature *is* a hardware check. Add it to `docs/REAL_WORLD_TESTING.md` as **RS54** ("a sold box is hardened and still
updates"), so the log records it alongside the others.
