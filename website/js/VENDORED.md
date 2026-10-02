# Vendored code in `website/js/`

| File | What it is | Size | SHA-256 |
|---|---|---|---|
| `yume-chan-bundle.js` | ESM bundle of the Tango/yume-chan WebUSB ADB libraries, built with esbuild (the `node_modules/@yume-chan/...` path comments show this) | 139579 bytes | `15bb40e373392d8b1e3522915ae90f5de7f55697fb70fea7751733e7be1d6cfe` |
| `webadb_manager.js` | PisoPhone's own code (not vendored): installs the APK and provisions a phone over WebUSB | | |

## What is inside `yume-chan-bundle.js`
Packages, from the bundle's own path comments (number of source modules in brackets):
`@yume-chan/adb` [40], `@yume-chan/stream-extra` [23], `@yume-chan/struct` [10], `@yume-chan/adb-daemon-webusb` [5],
`@yume-chan/async` [4], `@yume-chan/no-data-view` [4], `@yume-chan/event` [3], `@yume-chan/adb-credential-web` [1].
It exports `Adb`, `AdbWebCredentialStore` (as `AdbCredentialWeb`), `AdbDaemonTransport` and `AdbDaemonWebUsbDeviceManager`.

## What is NOT known
The bundle carries no version numbers and the repository never recorded them, so **the exact upstream versions are
unknown**. Upstream: <https://github.com/yume-chan/ya-webadb> (npm scope `@yume-chan`). The bundle was committed on
2026-09-29 (`e0a8599`).

## Rule
The hash above is checked in CI (`python3 scripts/check_vendored.py`). If you replace the bundle, update this file in
the same commit and add the exact package versions you built from. To rebuild it from source with pinned versions:
create a small `package.json` with exact versions of the packages above, commit its lockfile, bundle with
`esbuild --bundle --format=esm`, and record the versions in the table. Until then treat the file as opaque: do not edit
it by hand.

## Scope
The WebADB flow is only for installing the app and the first Wi-Fi/IP provisioning of a phone. Nothing in it should
carry secrets beyond the per-box key that provisioning already hands to the phone.
