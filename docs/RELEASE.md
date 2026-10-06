# Release checklist (beta → main → shops)

Everything the software checks by itself runs in CI on every push (`docs/DEPLOY.md`). This page is what only the owner
can do, in order, before a build goes to shops.

## 1. Owner keys (once; on your own computer, never in CI or a cloud session)
- [ ] `python3 scripts/make_owner_keys.py`, commit the public key it writes into `esp32_firmware/include/LicensePubKey.h`
      **and** `tools/pisoportal/owner_key.b64` (`docs/KEYS.md`). Until then boxes accept **unsigned firmware updates** and the
      old shared-secret license keys (the production firmware builds in CI show a warning), and routers install **no** update
      from the website at all (they fail closed). Let CI build the router program once more after committing the key (it does
      so on every push), and install that setup file on each router by hand this one time: the key is inside the program.
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
- [ ] Router (details in the next section): raise `setup/RELEASE`, let CI build the setup file, sign it, publish it.

## 3a. Router updates over the website (no more copying files to routers)
Routers check `https://pisophone.pages.dev/update/router.json` every hour (each at its own minute). They install a release only
if **your key signed it**, it is **newer** than the one they run, and **their turn** in the staged rollout has come. A forged or
damaged file, a downgrade, or an update that does not start after installing (the router program's self-test, the portal process)
is refused or undone, and you get a Telegram message either way. With customers online an update waits for a quiet moment.
1. Change the router software on `beta`, test it, raise `setup/RELEASE` (for example `1.1.0`). Push; CI builds the router program
   and `setup/piso-setup.sh` (check `tools/pisoportal/bin/BUILD-INFO` shows your commit).
2. Merge `beta` into `main` when your tests pass, `git pull`.
3. On **your own computer**: `python3 scripts/sign_router.py --private ~/pisophone_license_key.pem --rollout 10 --changelog "what changed"`
   writes `website/update/router.json` and `router-setup.sh`. Commit both to `main`: Cloudflare publishes them.
4. Watch Telegram ("update: release 1.1.0 installed and running"). A router that cannot run the new release goes back by itself
   and tells you ("FAILED and was undone"). When the first routers are fine, sign the **same file again** with `--rollout 100`.
5. To withdraw a bad release: publish a fixed one with a higher version (routers never go to a lower version).
Router commands: `piso-setup self-update check` (only look), `piso-setup self-update` (install now, even with customers online; or
send `/update` in Telegram), `piso-setup auto-update off|on` (off: you are told, nothing installs by itself).
The key is built into the router program, so a stolen Cloudflare or GitHub account can publish nothing a router will install.
`./piso-setup.sh update` still works for a router that has no internet.
## 4. After `main` serves the new website (timed follow-up)
- [ ] The box's **Install & Provision** links still carry the box's secret in the query string (`?secret=`), which reaches
      the web server's logs and the browser history. The website now also reads it after `#` (never sent to a server) and
      removes it from the address bar. Once that website is live on `main`, switch the links to `#secret=` in
      `esp32_firmware/web/portal-modals.js` (two links) and `esp32_firmware/src/WebDashboardComponents.cpp`, run
      `python3 scripts/embed_web.py`, and release that firmware. (Doing it earlier would break provisioning: the links
      always open the production site.)
