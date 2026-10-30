#!/usr/bin/env python3
"""The guest page in a shop's branding (feature 049, group 4), in a real browser. A router with published branding serves the shop's name,
colours and texts in all 18 states of the page, still shows the credit, keeps every control the unbranded page has, shows nothing the
owner typed as markup, and goes back to today's page byte for byte when the branding is taken away. The unbranded page itself is kept by
test_page_golden.py.

CHROME=/path/to/chromium python3 test_page_branded.py"""
import base64
import glob
import hashlib
import json
import os
import shutil
import sys
import urllib.request

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

sys.path.insert(0, HERE)
import test_page_golden_cases as G

TINY_PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
REPLACED = {"TechNet", "PisoWifi", "TechNet Solutions · Connect · Play · More Possibilities", "Pick a plan, insert a coin and you are online."}
SHOP = "Aling Nena's Net Cafe"
BRAND = (f"name={SHOP}\npalette=dagat\naccent=#ff7a1a\ntagline_en=Mabilis. Mura. Malinis.\nwelcome_en=Welcome to our shop!\n"
         "contact_en=0917 123 4567\nhours_en=Open 8am to 10pm\npromo_en=Happy hour: double time until 5pm\nrules_en=No illegal downloads.\\nBe kind.\n")


def fetch(url):
    r = urllib.request.urlopen(url, timeout=5)
    return r.read(), r.headers


env = Env(offset=165)
try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    base = f"http://127.0.0.1:{env.PP}"
    brand_dir = f"{env.data}/brand"
    with open(G.GOLDEN) as f:
        golden = json.load(f)

    def sha(url):
        return hashlib.sha256(fetch(url)[0]).hexdigest()

    page_url = f"{base}/?fas=ABC"
    check(sha(page_url) == golden["page_sha256"], "before anything is published the page is today's page")
    etag0 = fetch(page_url)[1]["ETag"]

    # publish: the page is built again at once, no restart
    os.makedirs(brand_dir)
    logo = f"logo-{hashlib.sha256(TINY_PNG).hexdigest()[:12]}.png"
    with open(f"{brand_dir}/{logo}", "wb") as f:
        f.write(TINY_PNG)
    with open(f"{brand_dir}/images.txt", "w") as f:
        f.write(f"logo {logo} {hashlib.sha256(TINY_PNG).hexdigest()}\n")
    with open(f"{brand_dir}/published.txt", "w") as f:
        f.write(BRAND)
    body, head = fetch(page_url)
    check(head["ETag"] != etag0 and SHOP.replace("'", "&#39;").encode() in body, "publishing changes the page and its ETag without a restart")
    check(b"/brand.png" not in body and b"/mascot.png" not in body, "the TechNet pictures are gone from a branded page")
    check(fetch(page_url)[0] == body, "the branded page is the same bytes every time")

    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        ctx = br.new_context(viewport={"width": 360, "height": 740}, locale="en-US", timezone_id="UTC")
        for name, state in G.CASES.items():
            print("  state", name, flush=True)
            pg = ctx.new_page()
            asked = []
            pg.on("request", lambda r, a=asked: a.append(r.url))
            pg.route_web_socket("**/ws", G.answering(json.dumps(state)))
            pg.goto(page_url)
            pg.wait_for_timeout(700)
            snap = pg.evaluate(G.SNAP)
            lines = G.tidy(snap["text"])
            want = golden["states"][name]
            check(SHOP in pg.inner_text("header") and "Mabilis. Mura. Malinis." in pg.inner_text("header"), f"{name}: the header has the shop's name and tagline")
            check(pg.title() == SHOP, f"{name}: the tab title is the shop's name")
            check(pg.is_visible(".credit") and "by TechNet Solutions" in lines, f"{name}: the credit is visible")
            check(snap["controls"] == want["controls"], f"{name}: the same controls as the unbranded page")
            check(all(ln in lines for ln in want["text"] if ln not in REPLACED), f"{name}: every line of the unbranded page is still there")
            check("TechNet" not in " ".join(l for l in lines if l != "by TechNet Solutions"), f"{name}: no TechNet name left besides the credit")
            check("0917 123 4567" in lines and "Open 8am to 10pm" in lines, f"{name}: contact and hours show in every state")
            check(not any(u.endswith(("/brand.png", "/mascot.png")) for u in asked), f"{name}: the page never asks for a TechNet picture")
            check(pg.evaluate("document.querySelector('header img').naturalWidth") == 1, f"{name}: the logo loads")
            if name == "idle_new_customer":
                check("Happy hour: double time until 5pm" in lines and "Welcome to our shop!" in lines, "idle: the promo and the welcome show")
                check(any(l.startswith("No illegal downloads") for l in lines) and "House rules" in lines, "idle: the house rules show")
                check(pg.evaluate("getComputedStyle(document.querySelector('.btn:not(.alt)')).backgroundColor") == "rgb(255, 122, 26)", "idle: the buttons use the shop's accent")
                check(pg.evaluate("getComputedStyle(document.body).backgroundColor") == "rgb(6, 26, 46)", "idle: the page uses the palette's colour")
            if name == "final_online":
                check("Happy hour: double time until 5pm" not in lines and "House rules" not in lines, "after paying: no promo and no rules in the way")
            pg.close()

        # whatever the owner types stays text
        evil = "</title><script>window.__x=1</script><img src=x onerror=window.__x=2>"
        with open(f"{brand_dir}/published.txt", "w") as f:
            f.write(f"name={evil}\ntagline_en={evil}\nwelcome_en={evil}\npromo_en={evil}\ncontact_en={evil}\nhours_en={evil}\nrules_en={evil}\n")
        pg = ctx.new_page()
        dialogs = []
        pg.on("dialog", lambda d: (dialogs.append(d.message), d.dismiss()))
        pg.route_web_socket("**/ws", G.answering(json.dumps(G.CASES["idle_new_customer"])))
        pg.goto(page_url)
        pg.wait_for_timeout(700)
        check(pg.evaluate("window.__x") is None and not dialogs, "typed script does not run")
        name = evil[:32]  # the shop name is limited to 32 characters, the tagline to 60: what is kept shows as text
        check(pg.title() == name, f"the title is the typed text, as text: {pg.title()!r}")
        check(name in pg.inner_text("header") and evil in pg.inner_text("#hd") and pg.is_visible(".credit"), "the typed text shows as text and the credit is still there")
        check(pg.evaluate("document.querySelectorAll('img[src=x]').length") == 0, "typed markup made no element")
        pg.close()
        br.close()

    # taking the branding away gives today's page again, byte for byte
    os.remove(f"{brand_dir}/published.txt")
    check(sha(page_url) == golden["page_sha256"], "restoring the default look serves today's page again")
finally:
    env.stop()
finish()
