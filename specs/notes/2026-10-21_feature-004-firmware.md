# Handoff: feature 004 (firmware money rows), 2026-10-21

Start the next chat with `specs/templates/session-start.md`; last line: `Task: continue from specs/notes/2026-10-21_feature-004-firmware.md`.

## Where things are
- Branch `feat/004-firmware-money`, pushed, tip `e74a753`. Cut from `beta` (`b76f909`); it also contains the replan branch `docs/replan-2026-10-21` (merged in, docs only; also pushed on its own).
- **No pull request is open.** Nothing was pushed to `beta`. Code quality on `beta` at `b76f909` was green. PR #96 (feature 003) is merged.
- Cache this chat: 98.4% hit; the problem was length (67 calls), not the cache.

## Done
- Replan written (`specs/replans/2026-10-21.md`), `STATUS.md` and `RELEASE_STATE.md` brought up to date, ledger pruned.
- Feature 004 (spec approved by the owner, reset pin waits 30 s then resets): FW-03, FW-02, FW-01, FW-05, FW-06 each in its own commit, test first; firmware 3.11.1; RS28 to RS31 added to `docs/REAL_WORLD_TESTING.md`; `validation.md` filled; backlog rows set to `fixed`.
- Checks green on the computer: full `esp32_firmware/host_tests/run.sh` (exit 0, includes `coin_record_order`, `coin_hw_apply`, `restart_guard`, `coin_credit`, new `idle_coin`), `clang-format` 23.1.2 on touched files, `check_security_rules.sh`, `embed_web.py --check`.

## Decisions
- Reset pin: waits up to 30 s after the 5 s hold for a safe moment, then resets anyway (owner, 2026-10-21).
- FW-06 got a new helper (`IdleCoin.h`) and test because the old commit `eeae494` had none; `eeae494` was split into one commit per row; old code reformatted for clang-format 23.1.2.
- The "red" result for four tests is a build failure (helper header missing), not an assertion failure; call sites were checked by reading the diff. Said so in `validation.md`.

## Open
- **Firmware build not run** (no PlatformIO here): open a PR into `beta` when the owner asks; read Code quality and `firmware-images.yml` before calling 004 done. Do not push to `beta` while a needed Code quality run is in flight.
- Hardware checks owed, not run: RS28 to RS31 (004), RS32 (router 1.5.13), RS33 (tablet, feature 003; its cause is a hypothesis).
- The BUSY reply to reboot/update still says "Unpersisted transactions in RAM" even when the reason is a coin in flight (left as in the old commit).
- Owner decisions still open: ST-2, SC-4, ST-8, DEAD-1, APP-04, roadmap 0.1, tablet model and Android version for 003.
- `RELEASE_STATE.md` lists router 1.5.13, firmware 3.11.0 and the 003 change as on `beta`, not on `main`; add firmware 3.11.1 when 004 is merged or released.

## Next step
Owner reviews `specs/features/004-firmware-money/validation.md`, asks for the PR into `beta`; then `/sdd-replan` and feature 005 (R1 app rows APP-01 to 10, needs CI for the Android build; or R2 signing and CI fixes). Old tested work for both is still on `claude/sleepy-gauss-rn31gl` (tip `748add9`): keep it.

## Slow tests
`cd esp32_firmware/host_tests && TMPDIR=/tmp/claude-0 ./run.sh > /tmp/claude-0/ht/all.log 2>&1 &` then read the last lines (needs `libmbedtls-dev`, python `cryptography`, `clang-format==23.1.2`). A single test: `g++ -std=c++17 -include limits.h -include sys/param.h -Wall -Wextra -Werror -fsanitize=address,undefined -o /tmp/x NAME_test.cpp && /tmp/x`.
