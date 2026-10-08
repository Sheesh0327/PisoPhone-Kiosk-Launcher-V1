# Changelog

What changed for people who run PisoPhone sites. The phone app, the coin-box firmware and the router software are versioned
separately (app: build number `1.0.<build>`; firmware: `PISO_FW_VERSION`; router: `setup/RELEASE`). Newest first.

## Unreleased
### Phone app
- The coin button says why a coin slot could not be armed (`NOT PAIRED YET`, `BOX REFUSED PHONE`, `BOX SETUP NEEDED`,
  `BOX NOT FOUND`, `CAN'T REACH BOX`, `BOX ERROR`) instead of showing `COINSLOT BUSY` for every failure. Only a coin slot that
  really is in use by another device says busy.

## Slot licensing removal and one-tap updates (firmware 3.3.0, router 1.1.0)
### Coin box
- Slot licensing is gone: all 10 phone slots are always available. The slot license keys, the "Box code" and the "Add phone
  slots" dialog were removed. Boxes that were limited to fewer slots unlock all of them on update.
- Updates over the air: the dashboard's **Install update** installs the build published on https://pisophone.pages.dev.

### Phone app
- Removed the unused "slot license expires" warning.
- The floating pill hides while the admin is in Play Store, Settings or the package installer, so those apps accept touches.
  It returns when the admin is back on the kiosk screen or the maintenance window ends.

### Router
- `piso-setup update`, run on the router, installs the newest release the owner signed from the website (the same as
  `piso-setup self-update`). A new copy of `piso-setup.sh` run with `update` still installs its own files.

### Release process
- Merging `beta` into `main` publishes the app, the firmware (`firmware-images.yml`) and the router update
  (`publish-router.yml`). See `docs/RELEASE.md`.
