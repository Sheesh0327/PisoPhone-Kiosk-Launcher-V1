#!/usr/bin/env python3
"""The dashboard's background polls (esp32_firmware/web/portal-core.js) in a real browser, with the box's answers mocked: a box that
keeps answering keeps the page signed in, one stray "not signed in" answer does not sign it out, and two in a row do (one bar, with a way
back in, and no more polling). Needs Playwright (pip install playwright; playwright install chromium); set CHROME to use a Chromium you
already have.  Run: python3 scripts/tests/test_portal_session_ui.py"""
import json, os, sys

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "esp32_firmware", "web")
PAGE = "<!doctype html><html><body><div id='live_devices_container'></div><div id='accounts_container'></div></body></html>"
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


answers = {"status": [], "accounts": []}   # scripted: the next status codes; empty = 200
calls = {"status": 0, "accounts": 0}


def handle_route(route):
    path = route.request.url.split("://", 1)[1].split("/", 1)[1].split("?")[0]
    if path == "":
        return route.fulfill(status=200, content_type="text/html", body=PAGE)
    key = {"api/status": "status", "api/accounts": "accounts"}.get(path)
    if key is None:
        return route.fulfill(status=200, content_type="application/json", body="{}")
    calls[key] += 1
    code = answers[key].pop(0) if answers[key] else 200
    if code == 401:
        return route.fulfill(status=401, content_type="application/json", body='{"error":"login"}')
    body = {"devices": [], "wifi": {"connected": True}} if key == "status" else {"accounts": [], "max": 10, "cards_ready": True}
    route.fulfill(status=200, content_type="application/json", body=json.dumps(body))


def fresh(page):
    answers["status"].clear(); answers["accounts"].clear()
    calls["status"] = calls["accounts"] = 0
    page.goto("http://box.test/")
    page.add_script_tag(content=read("portal-core.js"))


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get("CHROME") or None, args=["--no-sandbox"])
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.clock.install()
    page.route("**/*", handle_route)

    fresh(page)
    page.clock.run_for(20000)
    check(page.query_selector("#session_lost") is None and calls["status"] >= 6, "a box that keeps answering keeps the page signed in")

    fresh(page)
    answers["status"][:] = [200, 401, 200]
    page.clock.run_for(20000)
    check(page.query_selector("#session_lost") is None and calls["status"] >= 6, "one stray 'not signed in' answer is not a sign-out")

    fresh(page)
    answers["status"][:] = [200, 401, 401]
    page.clock.run_for(12000)
    check(page.query_selector("#session_lost") is not None, "two in a row are")
    check("Sign in again" in page.inner_text("#session_lost") and page.get_attribute("#session_lost a", "href") == "/signin?next=/",
          "and the bar says so, with a way back in")
    seen = calls["status"]
    page.clock.run_for(30000)
    check(calls["status"] == seen and len(page.query_selector_all("#session_lost")) == 1, "polling stops and the bar is not repeated")

    fresh(page)
    answers["accounts"][:] = [401]
    answers["status"][:] = [200, 200, 200, 401]   # (the status poll runs every 3 s, the account poll every 15 s)
    page.clock.run_for(16000)
    check(page.query_selector("#session_lost") is not None, "the status and account polls count together")

    check(not errors, "no script errors: " + "; ".join(errors))
    browser.close()

print(f"{checks} checks, {failures} failed")
sys.exit(1 if failures else 0)
