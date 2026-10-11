#!/usr/bin/env python3
"""The dashboard's "payments not delivered" banner and dialog (esp32_firmware/web/portal-core.js, portal-modals.js) in a real
browser, with the box's answers mocked: the banner names the count and the money and goes away by itself, the dialog lists each
payment with its reason in plain words, "Handled" and "Mark all handled" post the right request, a box that sends no such data
shows nothing, and text that came from the network (a transaction or phone id) is shown as text and never run.
Needs Playwright (pip install playwright; playwright install chromium); set CHROME to use a Chromium you already have.
Run: python3 scripts/tests/test_portal_orphans_ui.py"""
import json, os, sys

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

WEB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "esp32_firmware", "web")
PAGE = """<!doctype html><html><body>
<div id="banners"></div>
<div id="orphans_modal" class="modal-overlay" style="display:none"><div id="orphans_body"></div><button id="orphans_all_btn">all</button></div>
</body></html>"""

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


state = {
    "unhandled": 3, "unhandled_pulses": 21, "unlisted": 1, "unlisted_pulses": 4, "total": 4,
    "entries": [
        {"seq": 4, "time_s": 1790000000, "pulses": 10, "credit_seconds": 6000, "age_s": 86400, "owner": 1, "reason": "expired",
         "handled": False, "tx_id": "b1-4-deadbeef", "target": "DEV-A"},
        {"seq": 3, "time_s": 0, "pulses": 7, "credit_seconds": 0, "age_s": 0, "owner": 3, "reason": "not-queued",
         "handled": False, "tx_id": '<img src=x onerror="window.PWNED=1">', "target": "<b>x</b>"},
        {"seq": 2, "time_s": 1789990000, "pulses": 4, "credit_seconds": 2400, "age_s": 100, "owner": 1, "reason": "cleared",
         "handled": True, "tx_id": "b1-2-aa", "target": "DEV-B"},
    ],
}
posts = []


def handle_route(route):
    req = route.request
    path = req.url.split("://", 1)[1].split("/", 1)[1].split("?")[0]
    def respond(obj):
        route.fulfill(status=200, content_type="application/json", body=json.dumps(obj))
    if path == "":
        route.fulfill(status=200, content_type="text/html", body=PAGE)
    elif path == "api/payments/orphans":
        respond(state)
    elif path == "api/payments/orphans/handle":
        body = req.post_data
        posts.append(body)
        if body == "seq=4":
            state["entries"][0]["handled"] = True
            state["unhandled"], state["unhandled_pulses"] = 2, 11
        if body == "all=1":
            for e in state["entries"]:
                e["handled"] = True
            state.update(unhandled=0, unhandled_pulses=0, unlisted=0)
        respond({"status": "ok"})
    else:
        respond({})


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get("CHROME") or None, args=["--no-sandbox"])
    page = browser.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.on("dialog", lambda d: d.accept())
    page.route("**/*", handle_route)
    page.goto("http://box.test/")
    page.add_script_tag(content=read("portal-core.js") + "\n;window.__updateBanners = updateBanners;")
    page.add_script_tag(content=read("portal-modals.js"))

    def banners(data):
        page.evaluate("d => window.__updateBanners(d, [])", data)

    banners({"orphans": {"unhandled": 3, "pulses": 21}, "wifi": {"connected": True}})
    text = page.text_content("#orphan_banner") if page.query_selector("#orphan_banner") else ""
    check("3 payments" in text and "₱21" in text and "could not be delivered" in text, "banner names the count and the money")
    banners({"orphans": {"unhandled": 1, "pulses": 5}, "wifi": {"connected": True}})
    text = page.text_content("#orphan_banner")
    check("1 payment " in text and "1 payments" not in text, "singular wording")
    banners({"orphans": {"unhandled": 0, "pulses": 0}, "wifi": {"connected": True}})
    check(page.query_selector("#orphan_banner") is None, "banner is removed when nothing is waiting")
    banners({"wifi": {"connected": True}})
    check(page.query_selector("#orphan_banner") is None, "a box that sends no orphans field shows nothing and nothing breaks")

    banners({"orphans": {"unhandled": 3, "pulses": 21}, "wifi": {"connected": True}})
    page.click("#orphan_banner button")
    page.wait_for_selector("#orphans_body .kv")
    check(page.eval_on_selector("#orphans_modal", "el => getComputedStyle(el).display") == "flex", "Review opens the dialog")
    body = page.inner_text("#orphans_body")
    check("₱10" in body and "worth 1:40:00 of time" in body and "DEV-A" in body and "b1-4-deadbeef" in body,
          "an entry shows the money, what it was worth, the phone and the id")
    check("did not confirm it for 24 hours" in body and "may still have reached the phone" in body, "each reason is explained in plain words")
    check("time unknown" in body, "a payment recorded without a clock time says so")
    check("1 older payment" in body and "₱4" in body, "payments that no longer fit are still accounted for")
    check(len(page.query_selector_all("#orphans_body .btn")) == 2 and len(page.query_selector_all("#orphans_body .tag.ok")) == 1,
          "two Handled buttons and one already-handled tag")
    check(page.evaluate("() => window.PWNED") is None and page.query_selector("#orphans_body img") is None,
          "hostile text in an id or phone name is never run")
    check("<img src=x onerror=" in body, "hostile text is shown as plain text")

    page.click("#orphans_body .btn")
    page.wait_for_function("() => document.querySelectorAll('#orphans_body .tag.ok').length === 2")
    check(posts and posts[0] == "seq=4", "Handled posts the payment number")
    page.evaluate("() => window.handleAllOrphans()")
    page.wait_for_function("() => document.querySelectorAll('#orphans_body .btn').length === 0")
    check(len(posts) > 1 and posts[1] == "all=1", "Mark all handled posts all=1")
    check(page.eval_on_selector("#orphans_all_btn", "el => el.style.display") == "none", "Mark all handled hides when nothing is waiting")
    check("no longer fit" not in page.inner_text("#orphans_body"), "the unlisted note goes away")
    check(not errors, "no script error on the page: %s" % errors)
    browser.close()

print("test_portal_orphans_ui: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
