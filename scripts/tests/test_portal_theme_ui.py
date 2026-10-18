#!/usr/bin/env python3
"""The coin box page's light/dark theme and its embedded view (esp32_firmware/web/portal.css and portal-core.js) in a real browser: the button flips
the theme and remembers it, a theme in the address is used without being saved, and shown inside the router console's frame (same host, console port)
the page hides its own header and sign-out, takes the console's theme from its message and ignores messages from anywhere else. Needs Playwright
(pip install playwright; playwright install chromium); set CHROME to use a Chromium you already have.  Run: python3 scripts/tests/test_portal_theme_ui.py"""
import os, sys

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "esp32_firmware", "web")
BOX = ("<!doctype html><html><head><link rel='stylesheet' href='/assets/portal.css'><script src='/assets/portal-core.js'></script></head><body>"
       "<header class='topbar'><div class='topbar-in'><div class='brand'>brand</div><nav class='tabs'><button class='tab'>Overview</button></nav>"
       "<div class='topbar-end'><button class='themebtn' onclick='toggleTheme()'>t</button><a href='/logout'>Sign out</a></div></div></header></body></html>")
CONSOLE = "<!doctype html><html><body><iframe id='f' src='http://box.test:81/?embed=1&theme=dark'></iframe></body></html>"
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


def read(name):
    with open(os.path.join(WEB, name), encoding="utf-8") as f:
        return f.read()


def route(r):
    url = r.request.url.split("://", 1)[1]
    host, _, path = url.partition("/")
    path = path.split("?")[0]
    if path == "assets/portal.css":
        return r.fulfill(status=200, content_type="text/css", body=read("portal.css"))
    if path == "assets/portal-core.js":
        return r.fulfill(status=200, content_type="application/javascript", body=read("portal-core.js"))
    if host == "box.test" and r.request.url.endswith("/console"):   # the console and the box page are one host, two ports (the router's console and its box page)
        return r.fulfill(status=200, content_type="text/html", body=CONSOLE)
    return r.fulfill(status=200, content_type="text/html", body=BOX)


chrome = os.environ.get("CHROME")
with sync_playwright() as pw:
    br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch(args=["--no-sandbox"])
    ctx = br.new_context(viewport={"width": 1100, "height": 700}, color_scheme="light")
    ctx.route("**/*", route)
    pg = ctx.new_page()
    errors = []
    pg.on("pageerror", lambda e: errors.append(str(e)))

    # on its own: follows the device until the button is used; the choice is kept; ?embed=1 means nothing outside a frame
    pg.goto("http://box.test/?embed=1")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") is None, "on its own it follows the device (no theme chosen)")
    check(not pg.evaluate("document.documentElement.classList.contains('embed')"), "?embed=1 does nothing outside the console's frame")
    check(pg.is_visible(".brand") and pg.is_visible(".topbar-end"), "on its own the page shows its brand and sign-out")
    pg.click(".themebtn")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") == "dark", "the button turns a light device dark")
    check(pg.evaluate("getComputedStyle(document.body).backgroundColor") == "rgb(9, 9, 11)", "and the page is dark")
    pg.click(".themebtn")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") == "light" and pg.evaluate("localStorage.getItem('piso_theme')") == "light", "the button turns it light again and remembers the choice")
    pg.goto("http://box.test/")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") == "light", "the choice is still there on the next visit")
    pg.goto("http://box.test/?theme=dark")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") == "dark" and pg.evaluate("localStorage.getItem('piso_theme')") == "light",
          "a theme in the address is used without being saved over the owner's choice")
    pg.goto("http://box.test/?theme=evil\"><script>")
    check(pg.evaluate("document.documentElement.getAttribute('data-theme')") in ("light", None), "an unknown theme in the address is ignored")

    # inside the console's frame
    fresh = ctx.new_page()
    fresh.on("pageerror", lambda e: errors.append(str(e)))
    fresh.goto("http://box.test/console")
    frame = None
    for _ in range(40):
        frame = next((f for f in fresh.frames if f.url.startswith("http://box.test:81/")), None)
        if frame and frame.query_selector(".topbar"):
            break
        fresh.wait_for_timeout(150)
    check(frame is not None and frame.evaluate("document.documentElement.classList.contains('embed')"), "in the console's frame the page takes the embedded view")
    check(frame.evaluate("document.documentElement.getAttribute('data-theme')") == "dark", "and the theme from the address")
    check(not frame.is_visible(".brand") and not frame.is_visible(".topbar-end") and frame.is_visible(".tab"), "the embedded view hides the brand, theme button and sign-out and keeps the tabs")
    fresh.evaluate("document.getElementById('f').contentWindow.postMessage({piso:'theme',theme:'light'}, 'http://box.test:81')")
    fresh.wait_for_timeout(300)
    check(frame.evaluate("document.documentElement.getAttribute('data-theme')") == "light", "the console's message changes the theme")
    fresh.evaluate("document.getElementById('f').contentWindow.postMessage({piso:'theme',theme:'nonsense'}, 'http://box.test:81')")
    fresh.wait_for_timeout(200)
    check(frame.evaluate("document.documentElement.getAttribute('data-theme')") == "light", "a message with an unknown theme changes nothing")
    frame.evaluate("window.postMessage({piso:'theme',theme:'dark'}, '*')")
    fresh.wait_for_timeout(300)
    check(frame.evaluate("document.documentElement.getAttribute('data-theme')") == "light", "a message that did not come from the console's page is ignored")
    check(frame.evaluate("localStorage.getItem('piso_theme')") is None, "the console's theme is not saved on the box page")
    check(not errors, f"no script errors: {errors}")
    br.close()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
