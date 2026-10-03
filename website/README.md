# Firmware update feed

`update/firmware.json` (plus `firmware-<chip>.bin` and its signed manifest when published) is what the box dashboard's update page
downloads. `_headers` adds the CORS headers the browser needs; `_redirects` keeps old firmware URLs working. See `docs/KEYS.md`.
