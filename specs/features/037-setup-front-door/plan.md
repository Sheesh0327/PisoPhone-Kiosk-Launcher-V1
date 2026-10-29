# Plan: feature 037, one front door and one question fewer

Context manifest (constitution rule 1). Unresolved ledger entries loaded: load them first (rule 6).

| file | purpose | how to read |
|---|---|---|
| `setup/pisophone_setup.py` | `product_choice()` at 206-217, and `--product` / `--phone-only` at ~6006 | **6000+ lines: grep only, never read whole** |
| `setup/src/10-settings.sh` | `shop_product()`, `check_product()`, `store_product()` | lines 41-46 |
| `setup/src/40-files.sh` | writes `SHOP_PRODUCT` into `coinslot.conf` | lines 115-130 |
| `setup/src/98-setup-flow.sh` | the phone Wi-Fi decision and the `unset` at 157 | lines 100-160 |
| `setup/src/50-coin-box.sh` | `provision_box()`: proves the router pushes the key to the box | lines 118-137 |
| `esp32_firmware/src/WebDashboardComponents.cpp` | the dashboard; line 80 already builds the site link | grep, then the surrounding function |
| `esp32_firmware/web/` | the dashboard's real sources (`WebAssets.h` is generated) | the file the new view goes in |
| `website/start/shop.html` | line 24: the internet-required wording to reuse verbatim | that line only |

Read nothing else. Never open `esp32_firmware/include/WebAssets.h`.

## Task groups
Parts A, B and C are independent. **A is the only one that can hurt a running shop**, so it is test-first and gated.

- [ ] **0. Confirm the correction and get the owner's answer.** Re-read the spec's "Correction to the epic". Verify in the code
      that `GW_KEY` is generated at `98-setup-flow.sh:130` and pushed by `provision_box()`, and that `install.sh` takes no
      user-facing parameter. If any of that has changed, **stop and report** — the feature's shape depends on it.
      D33 must be answered before part B. (no commit)
- [ ] **1. Part A, the guard first.** Write criterion 3 before changing anything: a fixture router with `SHOP_PRODUCT=wifi`
      updates and still has no rental-phone Wi-Fi. **See it pass on today's code**, so you know it is a real guard and not a
      test that passes because the feature is absent. Log it in `ledger/`.
- [ ] **2. Part A, stop asking.** Remove the prompt from `product_choice()`; always create the phone Wi-Fi on a fresh install.
      Keep `shop_product()` reading the stored value. Keep `--product` / `--phone-only` working but out of `--help`.
      Criterion 1, 3, 4. Group 1's guard must still pass.
- [ ] **3. Part A, the measurement (criterion 2).** This needs the owner's router. Write the exact commands into
      `specs/features/040-sold-box-hardening/bench.md`'s style as a short note in this feature's folder, for the owner to run:
      free memory and radio state with and without the extra SSID. **Part A does not ship until the number is in
      `validation.md` (owner D34: this is a gate, not a note).**
- [ ] **4. Part B, the pure logic.** What the view should say for each state (default password or not, router paired or not,
      phones paired) as a pure function in `esp32_firmware/include/` with a host test. Criteria 5, 6. Test first.
- [ ] **5. Part B, the page.** Add the view to `esp32_firmware/web/`, then `python3 scripts/embed_web.py`. The one-liner is a
      literal constant (criterion 9). Reuse `website/start/shop.html:24`'s wording verbatim and add the test that compares the
      two strings (criterion 7). Measure the asset growth (criterion 8).
- [ ] **6. Part C, documentation only (owner D35).** Record the one-SKU decision. **Do not change
      `scripts/make_firmware_bundles.py`** — the sale zip changes when boxes actually ship, not now.
- [ ] **7. Docs and gates.** Criterion 13 (`setup/MANUAL.md`, `docs/ROUTERS.md`, `website/start/`), then criterion 12: the
      firmware host tests, the four router suites, `check_security_rules.sh`, `build_piso_setup.py --check`, `clang-format`,
      and raise both `PISO_FW_VERSION` and `setup/RELEASE`. Criteria 10 and 11 are greps — run them and paste the output.

## Release
**Firmware and router.** `PISO_FW_VERSION` raised (parts B and C) and `setup/RELEASE` raised (part A). Generated files are
rebuilt, never hand-edited: `scripts/embed_web.py` for the box's assets, `tools/build_piso_setup.py` for the installer, CI for
`tools/pisoportal/bin/`.

Note the release pairing: the box page (firmware) and the setup change (router) go out together. A box on older firmware with a
newer router is fine — it simply has no new view — but say so in `CHANGELOG.md` so an owner who updates one and not the other
is not surprised.

## Hardware check
A new check (next free RS number; RS53-RS57 are taken by features 032, 040, 041 and 035): on a real box and the owner's 360
T6M — the box page shows "no router paired" before setup and "paired" after; the one-liner on the page is the one that works;
a fresh install creates the rental-phone Wi-Fi with no question asked; and a router that was set up as PisoWiFi-only still has
no rental-phone Wi-Fi after the update. The last of those is criterion 3 on real hardware and is the one that matters.
