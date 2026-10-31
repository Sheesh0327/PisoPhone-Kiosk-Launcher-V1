# esp32_firmware/web: the dashboard's static files

## Layout
`portal.css`, `portal-core.js`, `portal-modals.js`, `superadmin.js`, `ota.js`, `adminqr.js` and `jsqr.js` are the dashboard's CSS and JavaScript (`adminqr.js` is the owner's QR login; `jsqr.js` is the vendored jsQR QR reader, see `website/js/VENDORED.md`).
The box serves them gzip-compressed from flash at `/assets/<name>?v=<version>`, and browsers keep them cached until
the content changes.

Do not put `{TAGS}` in these files: values that
differ per box (MAC address, secret, chip, version) reach them through `window.PISO_CFG` / `window.PISO_OTA`, which
the pages set in a small inline script. The HTML pages themselves stay in `include/WebDashboard*.h` and
`SuperAdminTemplate.h` and are streamed from flash with their `{TAGS}` filled in.

## One set of files per product (include/Product.h)
`portal-core.js` and `portal-modals.js` are the part every product has; `portal-core-phones.js` and `portal-modals-phones.js` are the
rental phones' part, and `portal-core-end.js` closes `portal-core.js`. `scripts/embed_web.py` serves them as one file each: the box with
phones (the full box and the PisoPhone-only box) gets `shared + phones + end`, the PisoWiFi-only box gets `shared + end` and nothing of the
phones. In `portal.css` the rules between `/*@phones*/` and `/*@end*/` are left out of the PisoWiFi-only box. Comments are dropped before
the files are compressed. Put anything phone related into the phone files, or the PisoWiFi-only image carries it
(`python3 scripts/check_product_strip.py` finds it). The pages themselves (`include/WebDashboard*.h`) are cut the same way with macros.

## Build
After editing anything here, pack the files into the firmware header and commit the result:
```
python3 scripts/embed_web.py
```

## Test
```
python3 scripts/embed_web.py --check           # fails when WebAssets.h is out of date
python3 scripts/check_product_strip.py         # the PisoWiFi-only image holds nothing of the phones
python3 scripts/tests/test_portal_theme_ui.py  # and the other test_portal_*_ui.py files: the dashboard's scripts in a browser
```
The browser tests need Playwright. CI runs all of these in the `firmware-style-and-tests` job of `quality.yml`.

## Generated files
`esp32_firmware/include/WebAssets.h` is made from this folder by `scripts/embed_web.py`. Never edit it by hand, and do not open it (`CLAUDE.md` section 4).

## See also
`esp32_firmware/README.md`, `website/js/VENDORED.md` (for `jsqr.js`), `docs/design/GUI_SPEC_OWNER_AND_WIFI.md` (the dashboard's look).
