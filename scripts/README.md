# Owner scripts

**Prefer a web page?** `python3 scripts/owner_console.py` opens a console on your own computer with a dedicated page for every owner
script below (keys and codes, time cards, releasing, checks): a form for its options, a Run button, the output, and the files it made
(QR pictures shown, PDFs to download). Your keys are never uploaded: a page asks for the key file's path and the script reads it from
disk. The console listens on 127.0.0.1 only and needs the secret in the address it prints. The scripts still work from a terminal.

# Scripts: what each one is for

Run everything from the repository root. **Find yours by who runs it and when**, not by name.

| I want to... | Run this |
|---|---|
| **Check a release before merging `beta` into `main`** | `python3 scripts/verify_release.py` (0 FAIL means the feeds are signed and installable) |
| Check the repository's own rules | `sh scripts/check_security_rules.sh` |
| Record results of a real-world test session | `python3 tools/checklist/build.py out.html` (the tickable page), then `python3 tools/checklist/apply.py results.json` (writes the results into `docs/REAL_WORLD_TESTING.md`) |
| Make my signing key (once) | `python3 scripts/make_owner_keys.py` |
| Print QR time cards | `python3 scripts/make_card_key.py` (once), then `python3 scripts/make_cards.py --box <MAC> --count 48` |
| Remove PisoPhone from a phone with no cable | `python3 scripts/make_recovery_code.py` |

## Run by CI (you rarely run these by hand)
| Script | What it does | Called by |
|---|---|---|
| `embed_web.py` | Packs the dashboard files in `esp32_firmware/web/` into `include/WebAssets.h`. After editing anything in `web/`, run it and commit the header; `--check` fails when it is out of date. | `quality.yml` |
| `publish_firmware_ota.py` | Copies the built box firmware into `website/update/`, signs it, and writes `firmware.json` (version, build, checksums, signatures). `--json-only` rewrites only `firmware.json` after you signed by hand. Warns when the firmware changed but `PISO_FW_VERSION` did not. | `firmware-images.yml` (on `main`) |
| `sign_router.py` | Signs the router setup file so every router installs it: writes `router.json` and `router-setup.sh`. | `publish-router.yml` |
| `check_published_apk.py` | Checks that the APK on the website is the one `app.json` describes (size, checksum, signing certificate). | `quality.yml`, `verify_release.py` |

## Owner tools (your own computer; the private keys never go in CI or a cloud session)
| Script | What it does |
|---|---|
| `make_owner_keys.py` | Creates the one key that signs firmware and router updates, and prints what to commit (`--github-secret` for the CI secret). |
| `sign_firmware.py` | Signs one firmware image by hand (CI does this for you on `main`; use this for a manual update). |
| `sign_router.py` | Same as above for a router release, with `--private` and `--rollout` for a staged rollout. |
| `make_admin_qr.py` | Makes the owner's admin QR code (and the key behind it the first time): scan it on a box's login page to be super admin. No password to publish (`docs/api/superadmin-qr.md`). |
| `make_card_key.py`, `make_cards.py` | The separate card key, and printing a batch of QR time cards for one box. |
| `make_recovery_code.py` | A recovery QR: scanned on a phone's lock screen it removes PisoPhone from that phone. |

Not run directly: `owner_key.py` (shared key loading for the signing tools) and `card_format.py` (the QR card format).

## Checks and diagnostics (read-only)
| Script | What it does |
|---|---|
| `verify_release.py` | Before a release: same owner key in firmware and router; every firmware image, the router file and the app feed match their checksums and verify; and **a box installs firmware only if `PISO_FW_VERSION` is higher** (same for `setup/RELEASE` on routers). |
| `check_security_rules.sh` | The repository's security rules: no private key or factory password committed, TLS certificate checks never switched off without a stated reason, the coin-slot API keeps requiring signed calls, a capped number of `runBlocking`, and the router setup keeps verifying what it downloads. CI runs it. |
| `check_vendored.py` | Fails if a vendored third-party file changed without its record in `website/js/VENDORED.md`. |
| `sold_unit_check.py` | For hardened boxes you sell: shows the security eFuses and tells whether a flash dump is encrypted (`docs/PROVISIONING_SOLD_UNIT.md`). |
| `gateway_client.py` | Talks to a box's router API from a PC (`arm`, `status`, `release`): for testing, and the model for the router side. |

## Tests
Run all with the commands in `CONTRIBUTING.md`; CI runs them for the parts a change touches.
- `scripts/tests/` is where tests of the scripts and the website live: `test_publish_firmware.py`, `test_check_published_apk.py`, `test_sold_unit_check.py`, `test_recovery_code.py`, `test_make_cards.py`, `test_site_page.py` (the provisioning page in a browser), `test_portal_orphans_ui.py` (the box dashboard's undelivered-payments dialog), `test_provisioning.js`.
- Box firmware: `sh esp32_firmware/host_tests/run.sh` (pure-logic tests on a PC, no board needed).
- Router: `router/tests/`, the router program in `tools/pisoportal/tests/` and `cargo test`.
- The real-world test page: `tools/checklist/test_page.py` and `test_apply.py`.

## Other places that hold scripts
| Where | What |
|---|---|
| `tools/checklist/` | `build.py` (tickable test page from `docs/REAL_WORLD_TESTING.md`) and `apply.py` (writes the results back). |
| `tools/build_piso_setup.py` | Builds `setup/piso-setup.sh`, the single file copied to a router, from `setup/src/` and the router program. |
| `setup/` | The setup program (`pisophone_setup.py`, also served from `website/`: a test keeps both copies identical) and the router installer. |
| `.github/scripts/sync_test_issues.py` | Turns Issue log rows into GitHub issues (the "Real-world test issues" workflow). |
