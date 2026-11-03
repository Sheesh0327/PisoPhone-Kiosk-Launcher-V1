# The PisoPhone website (`website/`)

Published at https://pisophone.pages.dev by Cloudflare Pages from this folder (`_headers` and `_redirects` are read by
Cloudflare). It holds no secrets and has no user accounts.

## Layout
- `index.html`: the business front page for TechNet PisoHub, presenting PisoPhone and PisoWiFi. When opened from a coin box's **Set up a phone** link (`?mac=…&secret=…` or the
  same values after `#`), it displays the QR setup code (the main way) and, as the fallback, a USB cable setup. It can also
  remove the kiosk from a phone (no PIN: the phone accepts it while USB debugging is on).
  - `js/provisioning.js`: checks the box's link and builds the QR code's content; `js/qrcode.js` draws the code (vendored, MIT).
  - `js/webadb_manager.js`: the USB fallback. It loads the ADB library (`js/yume-chan-bundle.js`) only when it is used.
- `update/`: the phone app (`app-release.apk`, `app.json`), the coin box firmware and `firmware.json`, and the router's
  signed update feed (`router.json`, `router-setup.sh`). Its contents are published by CI, and the owner's signing keys
  are described in `docs/KEYS.md` and `docs/RELEASE.md`.
- `css/site.css`: the one stylesheet (warm paper look, blue accent, follows the device's light/dark setting). It matches the coin box's own pages (`esp32_firmware/web/portal.css`); no build step, no third-party code.
- `manual.html`: the owner's manual page (printable). `img/`: logos and pictures used by the pages.
- `console/`: the router console's page script (`ui.js`) and its signature (`ui.json`), signed with the owner key by `scripts/sign_console_ui.py`
  so routers can take a newer console without a router update.
- `js/VENDORED.md`: where each third-party file came from, and its checksum.

## Build
There is no build step: plain HTML, CSS and JavaScript. Some files here are written by other tools; see "Generated and published files".

## Test
```
node scripts/tests/test_provisioning.js
python3 scripts/tests/test_site_page.py        # the page in a browser, with the site's Content-Security-Policy
python3 scripts/tests/test_manual.py
python3 scripts/check_vendored.py
```
CI runs these, and more, in the `website` and `security-rules` jobs of `quality.yml`; the full list is there. The browser test needs
Playwright (the install line is in `quality.yml`).

## Generated and published files
Never edit these by hand (`CLAUDE.md` section 4): `update/*` (CI: `build-apk.yml`, `firmware-images.yml`, `publish-router.yml`),
`console/*` (`scripts/sign_console_ui.py`), and the
vendored `js/*-bundle.js` (`js/VENDORED.md`).

## See also
`LICENSE` (the whole repository is proprietary; vendored files keep their own licences), `docs/KEYS.md`, `docs/RELEASE.md`, `docs/DEPLOY.md`,
`setup/README.md`.
