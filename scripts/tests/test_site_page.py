#!/usr/bin/env python3
"""The provisioning page (website/index.html) in a real browser, served with the site's own Content-Security-Policy
(website/_headers): it renders with the built stylesheet, runs no third-party code, raises no CSP violation or script
error, and takes the box's secret out of the address bar while keeping it for the install (fragment and query links).
The QR setup code is decoded from the screen by an independent reader (zxing) and must be exactly what Android expects.
Needs Playwright (pip install playwright; playwright install chromium), and zxing-cpp + pillow for the QR check.
Run: python3 scripts/tests/test_site_page.py"""
import functools, glob, http.server, io, json, os, sys, threading

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "website")
CSP = next(l.split(":", 1)[1].strip() for l in open(os.path.join(ROOT, "_headers")) if l.strip().startswith("Content-Security-Policy:"))


class Site(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Content-Security-Policy", CSP)
        super().end_headers()

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Site, directory=ROOT))
threading.Thread(target=srv.serve_forever, daemon=True).start()
base = f"http://127.0.0.1:{srv.server_address[1]}/index.html?mac=aa:bb:cc:dd:ee:ff&slot=2"
secret = "abcdefghijklmnop1234"
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


with sync_playwright() as pw:
    chrome = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome")), None)
    br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
    for label, url in [("a link with the secret after #", f"{base}#secret={secret}"), ("an older box's link (secret in the query)", f"{base}&secret={secret}")]:
        pg = br.new_page()
        problems = []
        pg.on("console", lambda m: m.type == "error" and problems.append(m.text))
        pg.on("pageerror", lambda e: problems.append(str(e)))
        pg.goto(url)
        pg.wait_for_timeout(1000)
        check(not problems, f"{label}: no CSP violation or script error: {problems}")
        check(pg.is_visible("#mainView") and not pg.is_visible("#restrictedView"), f"{label}: the setup view is shown")
        check(pg.evaluate("getComputedStyle(document.body).display") == "flex", f"{label}: the built stylesheet applies")
        check(pg.evaluate("window.PisoProvisioning.params.get('secret')") == secret, f"{label}: the page keeps the secret for the install")
        check("secret" not in pg.evaluate("location.href"), f"{label}: the secret is no longer in the address bar")
        pg.close()

    # ---- the QR setup code (the main way) ----
    try:
        import zxingcpp
        from PIL import Image
    except ImportError:
        zxingcpp = None
        print("note: zxing-cpp/pillow not installed: the QR code is not decoded")
    app = {"versionCode": 340, "signatureChecksum": "q8TS5S0nKPbfuo2dGdMCLzjyM9JMvcGlx1OqEw1Ah4Y", "sha256": "0" * 64}
    pg = br.new_page(viewport={"width": 480, "height": 1100})
    problems, requested = [], []
    pg.on("console", lambda m: m.type == "error" and problems.append(m.text))
    pg.on("pageerror", lambda e: problems.append(str(e)))
    pg.on("request", lambda r: requested.append(r.url))
    pg.route("**/update/app.json", lambda route: route.fulfill(status=200, content_type="application/json", body=json.dumps(app)))
    pg.goto(f"{base}#secret={secret}")
    pg.wait_for_timeout(500)
    check(pg.is_visible("#qrPanel") and not pg.is_visible("#usbPanel"), "the QR code is the method shown first")
    check(not any("yume-chan-bundle" in u for u in requested), "the USB library is not loaded until USB is chosen")
    pg.fill("#wifiPass", "")
    pg.click("#showQrBtn")
    pg.wait_for_timeout(200)
    check(pg.is_visible("#qrError") and "Wi-Fi password" in pg.inner_text("#qrError") and not pg.is_visible("#qrResult"), "no code without the Wi-Fi password")
    pg.fill("#wifiPass", "3hC4RATnpQMJ")
    pg.click("#showQrBtn")
    pg.wait_for_selector("#qrBox svg")
    payload = json.loads(pg.get_attribute("#qrBox", "data-payload"))
    E = "android.app.extra."
    ex = payload[E + "PROVISIONING_ADMIN_EXTRAS_BUNDLE"]
    check(payload[E + "PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"] == app["signatureChecksum"] and ex["secret"] == secret and ex["slot"] == 2
          and ex["mac"] == "AA:BB:CC:DD:EE:FF" and ex["wifi_pass"] == "3hC4RATnpQMJ" and payload[E + "PROVISIONING_WIFI_HIDDEN"] is True, "the code carries the box's details: " + json.dumps(ex))
    check(payload[E + "PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"].endswith("/update/app-release.apk"), "the phone downloads this site's APK")
    if zxingcpp:
        shot = Image.open(io.BytesIO(pg.locator("#qrBox").screenshot()))
        found = zxingcpp.read_barcodes(shot)
        check(len(found) == 1 and json.loads(found[0].text) == payload, "an independent QR reader reads exactly that JSON from the screen: " + (found[0].text[:120] if found else "nothing read"))
    pg.fill("#wifiPass", "3hC4RATnpQMJx")
    check(not pg.is_visible("#qrResult") and pg.get_attribute("#qrBox", "data-payload") is None, "changing the password hides the old code")
    pg.click("#showQrBtn")
    pg.wait_for_selector("#qrBox svg")
    pg.click("#hideQrBtn")
    check(not pg.is_visible("#qrResult") and pg.inner_html("#qrBox") == "", "Hide removes the code")
    check(pg.is_visible("#finishUsbBtn") and "Finish over USB" in pg.inner_text("#qrPanel"),
          "QR + USB: the QR panel has the last step (Finish over USB: the permissions only ADB can grant)")
    pg.click("#methodUsb")
    pg.wait_for_timeout(800)
    check(pg.is_visible("#usbPanel") and not pg.is_visible("#qrPanel") and pg.is_visible("#installAppBtn"), "the USB cable is the other method")
    check(any("yume-chan-bundle" in u for u in requested), "choosing USB loads the USB library (no CSP problem)")
    check(not problems, f"QR and USB: no CSP violation or script error: {problems}")
    pg.close()

    # the site's own app.json without a signing hash: the page says so and points to USB
    pg = br.new_page()
    pg.route("**/update/app.json", lambda route: route.fulfill(status=200, content_type="application/json", body=json.dumps({"versionCode": 1})))
    pg.goto(f"{base}#secret={secret}")
    pg.fill("#wifiPass", "3hC4RATnpQMJ")
    pg.click("#showQrBtn")
    pg.wait_for_selector("#qrError:not(.hidden)")
    check("USB cable" in pg.inner_text("#qrError"), "an app published without its signing hash: the page says to use the USB cable")
    pg.close()

    # ---- the coin box flasher (flash.html) ----
    site = base.split("/index.html")[0]
    pg = br.new_page()
    problems = []
    pg.on("console", lambda m: m.type == "error" and problems.append(m.text))
    pg.on("pageerror", lambda e: problems.append(str(e)))
    pg.route("**/flash/manifest.json", lambda route: route.fulfill(status=404, body="no"))
    pg.goto(f"{site}/flash.html")
    pg.wait_for_timeout(500)
    check(pg.is_visible("#noImages") and pg.is_disabled("#flashBtn"), "flasher without published images: says so, button off")
    pg.close()
    flash_manifest = {"version": "3.2.1", "commit": "abcdef1234", "builds": [{"env": "esp32-c3-dev", "chip": "ESP32-C3", "file": "esp32-c3-dev.bin", "offset": 0, "size": 10, "sha256": "0" * 64}]}
    pg = br.new_page()
    problems, requested = [], []
    pg.on("console", lambda m: m.type == "error" and problems.append(m.text))
    pg.on("pageerror", lambda e: problems.append(str(e)))
    pg.on("request", lambda r: requested.append(r.url))
    pg.route("**/flash/manifest.json", lambda route: route.fulfill(status=200, content_type="application/json", body=json.dumps(flash_manifest)))
    # the person closes the browser's USB port window without choosing (a headless browser would keep it open)
    pg.add_init_script("Object.defineProperty(navigator, 'serial', { value: { requestPort: () => Promise.reject(new DOMException('No port selected by the user.', 'NotFoundError')) } });")
    pg.goto(f"{site}/flash.html")
    pg.wait_for_timeout(500)
    check("Firmware 3.2.1 for ESP32-C3" in pg.inner_text("#fwVersion") and pg.is_enabled("#flashBtn"), "the flasher shows the published firmware: " + pg.inner_text("#fwVersion"))
    check(pg.is_checked("#eraseFirst"), "erasing first is the default (a new or used box)")
    check(not any("esptool-bundle" in u for u in requested), "the flashing library loads only when used")
    pg.click("#flashBtn")
    pg.wait_for_selector("#terminalLog div")
    pg.wait_for_timeout(800)
    check(any("esptool-bundle" in u for u in requested) and "ERROR" in pg.inner_text("#terminalLog") and pg.is_enabled("#flashBtn"),
          "a click loads the flashing library and, with no port chosen, ends with a clear error: " + pg.inner_text("#terminalLog")[-200:])
    check(not [p for p in problems if "Content Security Policy" in p or "Refused" in p], f"the flasher runs under the site's CSP: {problems}")
    pg.close()
    br.close()
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
