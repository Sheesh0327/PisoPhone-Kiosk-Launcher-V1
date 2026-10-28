# Validation: feature 040, prove the hardening of a sold box

Written 2026-10-28 by a cloud session with **no board and a restricted network**. Only groups 1 and 2 of the plan could be
attempted. **Nothing below is a pass.** Criteria 3 to 11 are not run because they need a board on the owner's bench.

| # | Success criterion (from spec.md) | Check | Result | Evidence |
|---|---|---|---|---|
| 1 | Hardened bootloader builds, by route 1 or 2 | `pio run` on the draft environment | **not run: toolchain unreachable here** | see "Why 1 and 2 did not run" |
| 2 | Both rollback options enabled in that build | read the generated sdkconfig | **not run: nothing was built** | the lines are in `secure_boot_env.draft.ini`; they were never shown to survive a build |
| 3 | Boots signed app, no eFuses burned | bench.md part A | not run: needs a board | |
| 4 | Signed OTA succeeds | bench.md part A | not run: needs a board | |
| 5 | Coin queue survives the update, credited once | bench.md part A | not run: needs a board | |
| 6 | Bad update reverted; `pending_verify` → `valid` seen | bench.md part A | not run: needs a board | |
| 7 | eFuse steps on the sacrificial board, then 3 to 6 again | bench.md part C | not run: needs a board | |
| 8 | Secure download mode on, `DIS_DOWNLOAD_MODE` not burned, JTAG off | bench.md part C | not run: needs a board | |
| 9 | Result table in `PROVISIONING_SOLD_UNIT.md` filled | | not run: needs a board | |
| 10 | `check_security_rules.sh` passes; no key material in `git status` | | see Gates | |
| 11 | `docs/KEYS.md` note on the secure-boot key | | not run: group 5 | |

## Why 1 and 2 did not run (exact errors)
Container: Linux, Python 3.13, PlatformIO Core 6.2.0 installed with `pip install platformio`. No ESP-IDF, no `idf.py`.
- **Stock platform, `cd esp32_firmware && pio run -e esp32-c3-production-encrypted`:** stops at
  `Platform Manager: Installing espressif32 @ 7.1.3` with `HTTPClientError:`. The PlatformIO registry
  (`registry.platformio.org`, `api.registry.platformio.org`, `dl.platformio.org`, `dl.espressif.com`) answers `CONNECT tunnel failed, response 403`
  through the cloud proxy. So even the normal build cannot run here.
- **Route 1, `pio run -e probe-sb`** (a scratch copy of `esp32_firmware` with the environment now in `secure_boot_env.draft.ini`,
  platform `pioarduino/platform-espressif32 stable`, which resolved to espressif32@55.3.312): the platform installs from GitHub, then
  `Tool Manager: Installing .../arduino-esp32/releases/download/3.3.12/esp32-core-3.3.12.tar.xz` fails with
  `SSLError: CERTIFICATE_VERIFY_FAILED: self-signed certificate in certificate chain`. `curl` fetches the same URL (HTTP 200), so the proxy
  allows it; PlatformIO does not trust the proxy certificate. I set `REQUESTS_CA_BUNDLE`/`SSL_CERT_FILE` and appended the proxy CA to certifi's
  bundle once, and it failed the same way. I stopped there (timebox) and did not switch TLS checking off.
- **Route 2** needs ESP-IDF and its toolchain, which come from hosts this container cannot reach (`github.com/espressif/...` releases answer 403
  on the listing pages, `dl.espressif.com` is blocked). Not tried.
- Even a green route-1 build would still have to be inspected: PlatformIO does not warn when rollback is missing.

## Gates (constitution rule 7)
- [x] `bash scripts/check_security_rules.sh` (result recorded in the commit message of group 2)
- [ ] Area checks from `CONTRIBUTING.md`: none apply, no source changed
- [ ] Python / generated files: none touched

## Not tested
Everything that needs a board, and both build criteria. No hardware check was added to `docs/REAL_WORLD_TESTING.md` yet
(group 5); RS54 and RS55 are taken, so the new line must be the next free number.

## Money and time
Not touched: no source change, no release. The money check (criterion 5) is written as a procedure in `bench.md` and **has not run**.
