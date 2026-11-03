# Feature 062: business front page and private setup files

Status: **approved**   Branch: `feat/062-business-front-page`   Area: website (plus tools, ci, scripts, docs)
Backlog rows: epic D17 (assembled boxes), `spec_01` repo-size item (`website/setup/*`)   Release: site

## Intent
The public website (`https://pisophone.pages.dev`) today hosts the initial setup files (`pisophone_setup.py`, `install.sh`, `setup/*`, `flash/*`) and step-by-step DIY setup pages (`start/*`), allowing anyone to download the installer and flash bare boards from the public site. Because TechNet Solutions sells assembled units rather than offering public DIY setup, the website must stop serving initial setup files and steps publicly and instead present a business front page for prospective buyers and existing shop owners, while keeping over-the-air update feeds (`/update/*`, `/console/*`) and the coin-box phone setup link (`/?mac=...#secret=...`) working for running shops.

## Invariants touched
- Money and time (a payment recorded first, credited once, nothing lost on a power cut): not touched.
- Only owner-signed software (OTA, signing, keys): kept. `/update/*` and `/console/*` remain untouched so deployed routers, coin boxes and phones continue verifying and installing owner-signed updates. Removing un-signed initial flash images (`website/flash/*`) and un-signed setup copies (`website/setup/*`, `website/install.sh`, `website/pisophone_setup.py`) from the public website reduces public exposure.

## Constraints
- **Condition visibility (rule 3b):**
  - A visitor opening `https://pisophone.pages.dev/` **without** a valid coin-box provisioning link (`?mac=...#secret=...` or `&secret=...`) sees the **TechNet PisoHub business front page** (`#restrictedView` in `website/index.html`):
    1. Hero and product overview of **PisoPhone** (rental Android kiosk phones with QR time cards) and **PisoWiFi** (coin-operated customer Wi-Fi through an OpenWrt router) powered by one ESP32 coin box.
    2. Key selling points for shop owners (tamper-proof money and time accounting that survives power cuts, plug-and-play assembled hardware, remote console and custom shop branding, automatic owner-signed updates).
    3. Existing shop owner note explaining how to open `http://admin.hub` or the coin box dashboard (`http://10.0.0.10`) on the shop network to manage the shop or add a rental phone, with a link to `manual.html`.
    4. Contact and ordering section to inquire about or order a TechNet PisoHub kit.
    5. No link to `pisophone_setup.py`, `install.sh`, `setup/*`, `flash/*` or `start/*`.
  - A shop owner opening `https://pisophone.pages.dev/?mac=...#secret=...` (or `&secret=...` on older firmware) **from a coin box's Set up a phone button** sees `#mainView` (the QR and USB phone setup tool) with `#restrictedView` hidden, unchanged in behavior.
- **Removed public setup files:** `website/pisophone_setup.py`, `website/install.sh`, `website/setup/*` and `website/flash/*` are removed from `website/` and are no longer generated or committed by `tools/build_piso_setup.py`, `.github/workflows/router-program.yml` or `.github/workflows/firmware-images.yml`. The local copies in `setup/` (`setup/pisophone_setup.py`, `setup/install.sh`, `setup/piso-setup*.sh`) remain in the repository for the owner.
- **Removed public setup pages:** `website/start/index.html`, `website/start/shop.html`, `website/start/phone.html` and `website/start/print.html` are removed. `website/_redirects` redirects `/start` and `/start/*` to `/` (302). `website/_headers` drops the header rules for `/flash/*`, `/install.sh`, `/setup/*` and `/pisophone_setup.py`, keeping the strict Content-Security-Policy and the `/console/*` and `/update/*` rules.
- **Owner's manual (`website/manual.html`):** stays on the site for shop owners (linked from the router console footer and front page), with DIY initial setup and `pisophone_setup.py` download instructions removed (`#setup` chapter replaced with an overview of the pre-configured unit arriving ready to plug in; references to downloading or running `pisophone_setup.py` removed).
- **Running shops unaffected:** `website/update/*` and `website/console/*` are not removed or altered. No firmware, router or app code is changed (`setup/RELEASE` and `PISO_FW_VERSION` stay unchanged).
- **No external resources:** `website/index.html` and `website/manual.html` load no external scripts, stylesheets, fonts or images, and obey `website/_headers` Content-Security-Policy.

## Success criteria
1. **No public setup files or pages in `website/`:** None of `website/pisophone_setup.py`, `website/install.sh`, `website/setup/`, `website/flash/` or `website/start/` exists in the working tree or git index.
2. **Generator and CI workflows updated:** `python3 tools/build_piso_setup.py --check` passes and writes only `setup/piso-setup.sh.in` and `setup/piso-setup*.sh` (+ `.sha256`). `.github/workflows/router-program.yml`, `.github/workflows/firmware-images.yml` and `.github/workflows/quality.yml` no longer stage, commit or require `website/setup`, `website/install.sh`, `website/pisophone_setup.py`, `website/flash` or `scripts/tests/test_start_pages.py`.
3. **Business front page without a box link:** In `scripts/tests/test_site_page.py`, opening `/index.html` with no query/fragment (or an invalid MAC/secret) shows `#restrictedView` and hides `#mainView`, renders the business front page sections (PisoPhone and PisoWiFi overview, shop owner benefits, existing owner instructions, contact/order section), contains no link or reference to `pisophone_setup.py`, `install.sh`, `setup/`, `flash/` or `start/`, and produces no CSP or console error.
4. **Phone setup tool with a box link:** In `scripts/tests/test_site_page.py` and `node scripts/tests/test_provisioning.js`, opening `/index.html?mac=aa:bb:cc:dd:ee:ff&slot=2#secret=...` (and `&secret=...`) still shows `#mainView`, hides `#restrictedView`, strips the secret from the address bar, renders the QR code and loads WebUSB on demand with 0 failures.
5. **Owner's manual cleaned of DIY setup:** `website/manual.html` passes `python3 scripts/tests/test_manual.py` and contains no link or instruction to download or run `pisophone_setup.py` or `install.sh`.
6. **Redirects and headers:** `website/_redirects` redirects `/start` and `/start/*` to `/`, and `website/_headers` has no rules for `/flash/*`, `/install.sh`, `/setup/*` or `/pisophone_setup.py`.
7. **Repository gates pass:** `bash scripts/check_security_rules.sh`, `python3 scripts/check_links.py`, `python3 scripts/check_vendored.py`, `python3 scripts/verify_release.py` and all touched Python/JS tests pass.

## Out of scope
- Changing how the local `setup/pisophone_setup.py` or `setup/install.sh` scripts work when run by the owner from the repository.
- Changing any firmware, router program, router script or Android app code.

## Owner decisions
- **D1 (answered):** Remove all initial setup files (`pisophone_setup.py`, `install.sh`, `setup/*`, `flash/*`) from `website/`, keeping only `/update/*` and `/console/*` for running shops.
- **D2 (answered):** Replace the no-link view (`#restrictedView`) in `website/index.html` with the business front page, while keeping `?mac=...#secret=...` working for "Set up a phone" from a coin box.
- **D3 (answered):** Remove `website/start/*` completely (redirect `/start/*` to `/`) and remove DIY flashing/setup links from `website/manual.html`.
- **D4 (answered):** Include product overview (PisoPhone and PisoWiFi), key selling points for shop owners, owner access note (`http://admin.hub` / coin box page), and a contact/order section on the business front page.
- **D5 (confirm with approval):** Updating the generated-files tables in `CLAUDE.md` (lines 45, 47, 67) and `CONTRIBUTING.md` (line 61) so `python3 scripts/check_links.py` passes with 0 dead links.
