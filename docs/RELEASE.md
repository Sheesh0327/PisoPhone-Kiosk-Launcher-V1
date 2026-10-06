# Release checklist (beta → main → shops)

Everything the software checks by itself runs in CI on every push (`docs/DEPLOY.md`). This page is what only the owner
can do, in order, before a build goes to shops.

## 1. Owner keys (once; on your own computer, never in CI or a cloud session)
- [ ] `python3 scripts/make_owner_keys.py`, commit the public key it writes into `esp32_firmware/include/LicensePubKey.h`
      (`docs/KEYS.md`). Until then boxes accept **unsigned firmware updates** and the old shared-secret license keys; the
      production firmware builds in CI show a warning about it.
- [ ] `python3 scripts/superadmin_credentials.py keygen --private <file>` for `SuperAdminPubKey.h`
      (`docs/api/superadmin-credentials.md`). Until then remote super-admin password sync stays off.

## 2. Hardware and real-world tests
- [ ] Pass 1 and 2 of `docs/REAL_WORLD_TESTING.md` on `beta` (box, phones, router), including the new items R3, R5, RS11
      and RS12. Log every failure in its Issue log.

## 3. Publish
- [ ] Merge `beta` into `main` (only now). CI builds and signs the production APK; phones on the stable channel update
      themselves.
- [ ] Firmware: bump `PISO_FW_VERSION`, build each chip, sign with `scripts/sign_firmware.py`, copy the images and their
      `.manifest.json` to `website/update/` on `main` (`docs/KEYS.md`, "Signed firmware updates"). `firmware.json` still
      announces 3.0.301 with no image until this is done, so the box's one-click update has nothing to install yet.
- [ ] Router: copy `setup/piso-setup.sh` from `main` to each router and run `./piso-setup.sh update`.

## 4. After `main` serves the new website (timed follow-up)
- [ ] The box's **Install & Provision** links still carry the box's secret in the query string (`?secret=`), which reaches
      the web server's logs and the browser history. The website now also reads it after `#` (never sent to a server) and
      removes it from the address bar. Once that website is live on `main`, switch the links to `#secret=` in
      `esp32_firmware/web/portal-modals.js` (two links) and `esp32_firmware/src/WebDashboardComponents.cpp`, run
      `python3 scripts/embed_web.py`, and release that firmware. (Doing it earlier would break provisioning: the links
      always open the production site.)
