#!/usr/bin/env python3
"""The dashboard's slot-license box (esp32_firmware/web/portal-modals.js: redeemSlotLicense, focusSlotLicense) in a real browser with the box's
answers mocked: an empty field is not sent, the pasted key is sent to /api/license exactly once, a refusal is shown in the box's own words and
leaves the page alone, and an accepted key says how many phones the box can pair now and reloads the page.
Needs Playwright (pip install playwright; playwright install chromium); set CHROME to use a Chromium you already have.
Run: python3 scripts/tests/test_portal_license_ui.py"""
import json, os, sys
from urllib.parse import parse_qs

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "esp32_firmware", "web")
PAGE = """<!doctype html><html><body><div id="licensebox"><input id="lic_token"><button id="go" onclick="redeemSlotLicense()">Add slot</button></div>
<button id="locked" onclick="focusSlotLicense()">locked</button></body></html>"""
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


posts = []
answer = {"status": 403, "body": {"success": False, "error": "KEY_REFUSED", "message": "This key was made for another box. Check the box code."}}


def handle(route):
    req = route.request
    path = req.url.split("://", 1)[1].split("/", 1)[1].split("?")[0]
    if path == "":
        route.fulfill(status=200, content_type="text/html", body=PAGE)
    elif path == "api/license" and req.method == "POST":
        posts.append(parse_qs(req.post_data or ""))
        route.fulfill(status=answer["status"], content_type="application/json", body=json.dumps(answer["body"]))
    else:
        route.fulfill(status=200, content_type="application/json", body="{}")


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get("CHROME") or None, args=["--no-sandbox"])
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.route("**/*", handle)
    page.goto("http://box.test/")
    page.add_script_tag(content=read("portal-core.js"))
    page.add_script_tag(content=read("portal-modals.js"))

    page.click("#go")
    page.wait_for_timeout(200)
    check(not posts and "paste the license key" in page.text_content("#toast").lower(), "an empty field sends nothing and says what to do")

    page.fill("#lic_token", "  PSLOT1.aabbcc001122.0000000a.c2ln  ")
    page.click("#go")
    page.wait_for_timeout(300)
    check(len(posts) == 1 and posts[0].get("token") == ["PSLOT1.aabbcc001122.0000000a.c2ln"], "the pasted key is sent once, without the spaces around it")
    check("another box" in page.text_content("#toast"), "a refusal is shown in the box's own words")
    check(page.input_value("#lic_token").strip() != "", "and the pasted key stays in the field")

    answer.update(status=200, body={"success": True, "cap": 3, "paired": 2, "free": 2, "licensed": 1, "max_cap": 12, "price": 500, "mac": "aa:bb"})
    with page.expect_navigation(timeout=5000):
        page.click("#go")
    check(len(posts) == 2, "an accepted key reloads the page")

    page.goto("http://box.test/")
    page.add_script_tag(content=read("portal-core.js"))
    page.add_script_tag(content=read("portal-modals.js"))
    page.click("#locked")
    check(page.evaluate("document.activeElement && document.activeElement.id") == "lic_token", "a locked slot takes the owner to the key field")
    check(not errors, f"no script errors: {errors}")
    browser.close()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
