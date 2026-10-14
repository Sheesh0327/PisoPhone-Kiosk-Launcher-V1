# Dashboard static files

`portal.css`, `portal-core.js`, `portal-modals.js`, `superadmin.js`, `ota.js`, `adminqr.js` and `jsqr.js` are the dashboard's CSS and JavaScript (`adminqr.js` is the owner's QR login; `jsqr.js` is the vendored jsQR QR reader, see `website/js/VENDORED.md`).
The box serves them gzip-compressed from flash at `/assets/<name>?v=<version>`, and browsers keep them cached until
the content changes.

After editing anything here run `python3 scripts/embed_web.py` and commit the regenerated
`esp32_firmware/include/WebAssets.h` (CI fails if it is out of date). Do not put `{TAGS}` in these files: values that
differ per box (MAC address, secret, chip, version) reach them through `window.PISO_CFG` / `window.PISO_OTA`, which
the pages set in a small inline script. The HTML pages themselves stay in `include/WebDashboard*.h` and
`SuperAdminTemplate.h` and are streamed from flash with their `{TAGS}` filled in.
