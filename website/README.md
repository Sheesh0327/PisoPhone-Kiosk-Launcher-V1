# The PisoPhone website (`website/`)

Published at https://pisophone.pages.dev by Cloudflare Pages from this folder (`_headers` and `_redirects` are read by
Cloudflare). It holds no secrets and has no user accounts.

## What is here
- `index.html`: the phone setup page, reached from a coin box's **Set up a phone** link (`?mac=…&secret=…` or the
  same values after `#`). It shows the QR setup code (the main way) and, as the fallback, a USB cable setup. It can also
  remove the kiosk from a phone (admin PIN). Without a box's link it shows a "restricted" notice instead.
  - `js/provisioning.js`: checks the box's link and builds the QR code's content; `js/qrcode.js` draws the code (vendored, MIT).
  - `js/webadb_manager.js`: the USB fallback. It loads the ADB library (`js/yume-chan-bundle.js`) only when it is used.
- `flash/`: the coin box firmware images (one merged image per chip, `manifest.json` with a sha256 for each), built by CI. The
  setup program downloads them, checks them and writes them to the box over USB. There is no flasher page any more.
- `pisophone_setup.py`: the setup program, a copy of `setup/pisophone_setup.py` (kept equal by `tools/build_piso_setup.py`).
- `install.sh` and `setup/`: the one-line router installer and the router setup file it downloads (built from `setup/`).
- `update/`: the phone app (`app-release.apk`, `app.json`), the coin box firmware and `firmware.json`, and the router's
  signed update feed (`router.json`, `router-setup.sh`). Its contents are published by CI, and the owner's signing keys
  are described in `docs/KEYS.md` and `docs/RELEASE.md`.
- `css/site.css`: the one stylesheet (warm paper look, blue accent, follows the device's light/dark setting). It matches the coin box's own pages (`esp32_firmware/web/portal.css`); no build step, no third-party code.
- `js/VENDORED.md`: where each third-party file came from, and its checksum.
