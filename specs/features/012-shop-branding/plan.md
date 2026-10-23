# Plan: feature 012, shop branding on the phone screens

Context manifest (constitution rule 1). Unresolved ledger entries loaded: **none** (every entry in
`ledger/negative-constraints.md` is `resolved: yes`).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `app/src/main/java/com/pisophone/kiosk/overlay/ui/LockScreenArt.kt` | backdrop canvas, `BackdropTint`, sticker band | 309 (read 1–120) | ~1.5k |
| `app/src/main/java/com/pisophone/kiosk/overlay/ui/BlockScreen.kt` | lock screen layout, `tint = BackdropTint.of(themeIndex)` (line 104, 152) | 447 (read 2 ranges) | ~0.6k |
| `app/src/main/java/com/pisophone/kiosk/ui/LauncherScreen.kt` | home screen header, bg, logo video (80–154) | 569 (read 75) | ~0.8k |
| `app/src/main/java/com/pisophone/kiosk/util/KioskSettings.kt` | direct-boot owner settings, the pattern to follow | 102 | ~1.1k |
| `app/src/main/java/com/pisophone/kiosk/ui/theme/Tokens.kt` | TechNet colour tokens and their meaning | 47 | ~0.6k |
| `app/src/main/java/com/pisophone/kiosk/server/KioskHttpServer.kt` | `/config` handler (300–314) | 447 (read 30) | ~0.4k |
| `app/src/main/java/com/pisophone/kiosk/ui/video/VideoBackgroundPolicy.kt` | the pure-rule pattern the marquee rule copies | 84 | ~0.9k |
| `esp32_firmware/src/DeviceManager.cpp` | the config push on pairing (75–95) | read 21 | ~0.3k |
| `esp32_firmware/src/WebServerConfig.cpp` | where config params are built (line 377) | grep only | ~0.1k |
| `esp32_firmware/partitions_encrypted.csv` | NVS is 20 KB; spiffs 384 KB | 14 | ~0.3k |
| `docs/design/GUI_SPEC_PISOPHONE_KIDS.md` | §5 art, §10 budgets, §11 must-not-change | read 3 ranges | ~1.2k |

Not opened (constitution rule 1 / `CLAUDE.md` section 4): `esp32_firmware/include/WebAssets.h`, `specs/spec_01_findings.json`
(queried with a script, not read), `website/update/*`, `app/schemas/*`.

## Task groups
Test first: write the test, run it, log the red result in `ledger/negative-constraints.md`, then write the code. One commit per
group. Groups 1 to 6 are app-only and need no board; groups 7 and 8 are firmware-only and need no phone.

- [ ] 1. **The palettes, as data with a contrast gate.** `ui/theme/BrandPalette.kt`: an id, a backdrop pair, three cloud
      colours and one accent per palette, `technet` first and default, plus `of(id)` falling back to `technet` on an unknown id.
      (test: `BrandPaletteContrastUnitTest` over every palette — text ≥ 4.5:1 on both backdrop colours, icons and outlines ≥
      3:1, and `of("nonsense") == technet`; red result logged: yes.) No UI touched in this group.
- [ ] 2. **Branding storage in direct-boot preferences.** `util/ShopBranding.kt` (read/write through
      `KioskSecurity.getDirectBootPrefs`, same as `KioskSettings`) with sanitising: cap the shop name at 32 characters and the
      title at 20, strip control characters, newlines and leading/trailing space, treat blank as unset, keep an unknown palette
      id out of storage. (test: `ShopBrandingUnitTest` — caps, control characters, blank, unknown id, defaults when nothing was
      ever written; red result logged: yes.)
- [ ] 3. **`/config` accepts the three settings.** `server/KioskHttpServer.kt` reads `palette`, `shop_name` and `brand_title`
      from the decrypted params and passes them on; the delegate stores them through group 2. Unknown or missing params change
      nothing (back-compat, criterion 2). (test: the existing `/config` server test extended — a push with the new params
      stores them, a push without them leaves the stored branding alone, junk and over-long values are refused or trimmed;
      red result logged: yes.)
- [ ] 4. **The marquee rule and the marquee.** `overlay/ui/MarqueeRule.kt`: a pure `shouldScroll(...)` taking thermal status,
      power save, battery percent, charging, animations on, screen in front — the shape of `VideoBackgroundPolicy.shouldPlay`.
      `overlay/ui/BrandMarquee.kt` draws one line: scrolling when the rule says so, centred and still otherwise, never
      clipped. (test: `MarqueeRuleUnitTest` over the ladder cases incl. battery -1 and charging at 10%; red result logged: yes.)
- [ ] 5. **Lock screen wiring.** `BlockScreen.kt` takes the branding; `LockScreenArt` takes the palette's colours instead of
      `BackdropTint` (old stored theme numbers keep cycling the three built-in tints when the shop has no palette set); the
      header shows the title and, under it, the marquee inside the header band, above `BAND_TOP_*` so no sticker is drawn over
      it. Clouds, rays, stickers, mascot and coins unchanged. (test: `LockScreenScreenshotTest` — the existing default golden
      stays byte-identical, one new golden for a branded phone; red result logged: yes.)
- [ ] 6. **Home screen wiring.** `LauncherScreen.kt`: `currentTheme` reads the palette (bg and accent only; gold, red and teal
      stay), the marquee under the clock and date row. The logo video, its poster and `VideoBackgroundPolicy` untouched.
      (test: a launcher Roborazzi golden, default unchanged plus one branded; red result logged: yes.)
- [ ] 7. **The box stores the settings.** NVS keys `brand_pal`, `shop_name`, `brand_title` beside the existing
      `ph_profiles`/`slot_prof` pattern (`src/PhoneProfileStore.cpp`), loaded on boot, with the same caps and sanitising as
      group 2 and a *Shop branding* section in the dashboard config page. (test: a host test under
      `esp32_firmware/host_tests/` — store, reload, caps, bad palette id, and the three together under 128 bytes; red result
      logged: yes.) If the dashboard page lives under `esp32_firmware/web/`, regenerate `include/WebAssets.h` with
      `python3 scripts/embed_web.py` — never hand-edited.
- [ ] 8. **The box pushes the settings.** Add the three optional params to the push built in `DeviceManager.cpp:88` and to the
      params in `WebServerConfig.cpp:377`, so pairing and every save reach the paired phones. (test: host test asserting the
      pushed parameter string with and without branding set, and that `protocol/gen_vectors.py` output and
      `host_tests/protocol_contract_test.cpp` are unchanged; red result logged: yes.)
- [ ] 9. **Owner-facing paperwork.** Hardware checks **RS36** (branding set on the box reaches a phone; it survives a phone
      reboot with the box off; the marquee stands still on a hot phone) and **RS37** (an old phone build paired to the new box,
      and a new phone build paired to a live 3.11.1 box, both keep working) added to `docs/REAL_WORLD_TESTING.md`;
      `CHANGELOG.md` entry in the owner's words; `specs/STATUS.md` row; `validation.md` filled with evidence.

## Release
- `esp32_firmware/include/FirmwareVersion.h`: `PISO_FW_VERSION` **3.11.1 → 3.12.0** (boxes ignore firmware that is not higher).
- App build number raised by the usual app release step; the APK is signed and published as it is today.
- **No router or setup release**: `setup/RELEASE` is untouched, nothing under `setup/src/` or `tools/pisoportal/` changes.
- Regenerated, never hand-edited: `esp32_firmware/include/WebAssets.h` (`python3 scripts/embed_web.py`) if the dashboard page
  changed; `website/update/*` and `website/flash/*` by CI as usual.

## Gates, and what cannot run here
Run before any push (`CONTRIBUTING.md` table, constitution rule 7): `./gradlew testDebugUnitTest`, ktlint, detekt, the
Roborazzi screenshot tests, the firmware host tests, and always `bash scripts/check_security_rules.sh`.
**This container has no Android SDK** (`ANDROID_HOME` unset, no SDK on disk) and no ESP32 toolchain, so the Gradle tests,
ktlint, detekt and the screenshot goldens **cannot be run or recorded here**, and neither can the firmware host tests. Every
app and firmware group therefore lands unverified from this session: `validation.md` will say so plainly per criterion, and the
owner (or a session with the SDK) runs them before anything reaches `beta`.
