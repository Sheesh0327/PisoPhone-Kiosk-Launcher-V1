# Plan: 062-business-front-page

Context manifest (constitution rule 1):

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `website/index.html` | Business front page (`#restrictedView`) and coin-box phone setup tool (`#mainView`) | 346 | 6,069 |
| `website/css/site.css` | Stylesheet for `website/index.html` | 158 | 2,412 |
| `website/manual.html` | Printable owner's manual (remove DIY `pisophone_setup.py` download/run steps) | 446 | 61,208 (base64 art skipped) |
| `website/_headers` | Cloudflare Pages headers and CSP | 55 | 438 |
| `website/_redirects` | Cloudflare Pages redirects (add `/start` and `/start/*` -> `/`) | 8 | 100 |
| `website/README.md` | Website directory documentation | 47 | 908 |
| `tools/build_piso_setup.py` | Setup script builder (stop writing `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`) | 104 | 1,502 |
| `scripts/tests/test_site_page.py` | Playwright browser test for `website/index.html` (front page + phone setup tool) | 146 | 2,163 |
| `scripts/tests/test_manual.py` | HTML/link/content check for `website/manual.html` | 48 | 407 |
| `router/tests/test_install.py` | Installer test (remove assertion on `website/install.sh` at line 283) | 285 | 4,798 |
| `router/tests/test_pisophone_setup.py` | Setup program test (remove assertion on `website/pisophone_setup.py` at line 33) | 1,030 | 19,155 |
| `.github/workflows/quality.yml` | CI quality workflow (remove `website/install.sh`, `website/pisophone_setup.py`, `test_start_pages.py`) | 397 | 5,319 |
| `.github/workflows/router-program.yml` | Router build workflow (stop staging removed `website/` setup copies) | 162 | 2,173 |
| `.github/workflows/firmware-images.yml` | Firmware build workflow (stage merged images in `$RUNNER_TEMP/flash` instead of committing `website/flash/`) | 229 | 3,282 |
| `.gitattributes` | Remove linguist-generated entries for deleted `website/` setup/flash files | 41 | 434 |
| `README.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `docs/*.md`, `setup/README.md`, `tools/README.md`, `scripts/README.md`, `.github/README.md`, `specs/*.md` | Update links to removed `website/` paths so `scripts/check_links.py` passes | - | - |

Unresolved ledger entries loaded: none affecting `website/`.

## Task groups
Small, ordered, each finished and checkable on its own. Test first.

- [x] 1. **Remove public setup files and `website/start/*`; update generator and CI workflows.** Add checks in `scripts/tests/test_site_page.py` (or a dedicated website structure check) asserting that `website/pisophone_setup.py`, `website/install.sh`, `website/setup`, `website/flash` and `website/start` do not exist and that `website/_redirects` redirects `/start/*` to `/`. See it fail, log in `ledger/negative-constraints.md`, then remove those files and `scripts/tests/test_start_pages.py`, update `tools/build_piso_setup.py`, `website/_headers`, `website/_redirects`, `.gitattributes`, `.github/workflows/{quality,router-program,firmware-images}.yml`, and the two duplicate-copy assertions in `router/tests/test_install.py` and `router/tests/test_pisophone_setup.py`. (red result logged: yes)
- [x] 2. **Business front page in `website/index.html` and `website/css/site.css`.** Extend `scripts/tests/test_site_page.py` to test visiting `/index.html` without a coin box link (and with an invalid link): `#restrictedView` shows the TechNet PisoHub business front page (product overview for PisoPhone and PisoWiFi, key selling points for shop owners, existing owner access instructions for `http://admin.hub` / `http://10.0.0.10`, contact/order section, link to `manual.html`, and no links to `pisophone_setup.py`, `install.sh` or `start/`), while visiting with a valid box link still opens `#mainView`. See the test fail, log in `ledger/negative-constraints.md`, update `website/index.html` and `website/css/site.css`, and verify both `test_site_page.py` and `test_provisioning.js` pass. (red result logged: yes)
- [x] 3. **Remove DIY setup steps and links from `website/manual.html`.** Update `scripts/tests/test_manual.py` to assert that `website/manual.html` never links to or instructs downloading/running `pisophone_setup.py` or `install.sh`. See it fail, log in `ledger/negative-constraints.md`, update chapter 2, chapter 3, chapter 12 and the Quick reference in `website/manual.html` for pre-assembled units, and see `test_manual.py` pass. (red result logged: yes)
- [x] 4. **Update documentation links and validate all gates.** Update references to removed `website/start/`, `website/flash/`, `website/setup/`, `website/install.sh`, `website/pisophone_setup.py` and `scripts/tests/test_start_pages.py` across `README.md`, `website/README.md`, `setup/README.md`, `tools/README.md`, `scripts/README.md`, `.github/README.md`, `docs/README.md`, `docs/DEPLOY.md`, `docs/RELEASE.md`, `CONTRIBUTING.md`, `CLAUDE.md` and `specs/` so `python3 scripts/check_links.py` reports 0 dead links. Fill `validation.md` and update `specs/STATUS.md`. (red result logged: no)

## Release
Site-only change (deployed to `https://pisophone.pages.dev` from `website/` when merged to `main`). `setup/RELEASE`, `PISO_FW_VERSION` and the Android app version are not raised. `python3 tools/build_piso_setup.py --check` must pass.
