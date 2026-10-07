#!/usr/bin/env python3
"""The provisioning page (website/index.html) in a real browser, served with the site's own Content-Security-Policy
(website/_headers): it renders with the built stylesheet, runs no third-party code, raises no CSP violation or script
error, and takes the box's secret out of the address bar while keeping it for the install (fragment and query links).
Needs Playwright (pip install playwright; playwright install chromium). Run: python3 scripts/tests/test_site_page.py"""
import functools, glob, http.server, os, sys, threading

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
    br.close()
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
