#!/usr/bin/env python3
"""The console's price editor in a real browser (headless Chromium): the list shows, a line is edited and saved, a bad list is refused with its reason,
what is typed survives the page's refresh, and going back to the settings file asks first."""
import glob, os, shutil, sys, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

CP = 18320
env = Env(offset=70)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf")
try:
    check(env.start(), "the portal starts")
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        pg = br.new_page(viewport={"width": 1280, "height": 900})
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.goto(f"http://127.0.0.1:{CP}/")
        pg.wait_for_selector("#login:not(.hide)", timeout=8000)
        pg.fill("#pw", "router-pass")
        pg.click("#gobtn")
        pg.wait_for_selector("#app:not(.hide)", timeout=8000)
        pg.click('#tabs button[data-t="set"]')
        pg.wait_for_selector("#rows-e .tr", timeout=8000)
        check(pg.locator("#rows-e .tr").count() == 4 and pg.locator("#rows-h .tr").count() == 3, "the starting lines are listed (4 Endurance, 3 HyperSpeed)")
        check(pg.input_value("#pro") == "6" and pg.input_value("#edn") == "5" and pg.input_value("#eup") == "2", "pro-rata minutes and the speed limits (in Mbps) are shown")
        check("settings file" in pg.text_content("#prn"), "it says the prices are still the settings file's: " + pg.text_content("#prn"))

        # type a change: it must survive the page's own refresh (every 4 seconds)
        pg.locator("#rows-e .tp").first.fill("2")
        pg.wait_for_timeout(4800)
        check(pg.locator("#rows-e .tp").first.input_value() == "2", "what is typed is not overwritten by the refresh")

        # a list with no 1-peso line is refused, with the reason
        pg.click("#prs")
        pg.wait_for_function("document.getElementById('prm').textContent.length > 0", timeout=5000)
        check("1-peso" in pg.text_content("#prm") and "Not saved" in pg.text_content("#prm"), "a bad list is refused with its reason: " + pg.text_content("#prm"))

        # fix it: 1 peso = 20 minutes, add a 50-peso line, drop the 20-peso line, limit upload to 3 Mbps
        pg.locator("#rows-e .tp").first.fill("1")
        pg.locator("#rows-e .tm").first.fill("20")
        pg.click("#add-e")
        pg.locator("#rows-e .tp").last.fill("50")
        pg.locator("#rows-e .tm").last.fill("4320")
        check(pg.locator("#rows-e .tx").last.text_content() == "72 hours", "a line shows its time in hours: " + pg.locator("#rows-e .tx").last.text_content())
        pg.locator("#rows-e .rm").nth(3).click()
        pg.fill("#eup", "3")
        pg.click("#prs")
        pg.wait_for_function("document.getElementById('prm').textContent.indexOf('Saved') === 0", timeout=5000)
        check(pg.locator("#rows-e .tr").count() == 4 and "console" in pg.text_content("#prn"), "saved: the list is shown as stored, marked as changed in the console")
        page = env.get("/")[1]
        check("<span>&#8369;50</span><span>72 hrs</span>" in page and "<span>&#8369;1</span><span>20 min</span>" in page, "the customer page follows")

        # a reload shows what was saved
        pg.reload()
        pg.wait_for_selector("#app:not(.hide)", timeout=8000)
        pg.click('#tabs button[data-t="set"]')
        pg.wait_for_selector("#rows-e .tr", timeout=8000)
        check(pg.input_value("#eup") == "3" and pg.locator("#rows-e .tp").last.input_value() == "50", "a reload shows the saved prices")

        # going back asks first
        pg.click("#prr")
        pg.wait_for_selector("#dlg[open]", timeout=3000)
        check("settings file" in pg.text_content("#dp"), "going back to the settings file asks first")
        pg.click("#dyes")
        pg.wait_for_function("document.getElementById('prm').textContent.indexOf('Done') === 0", timeout=5000)
        check(pg.input_value("#eup") == "2" and pg.locator("#rows-e .tr").count() == 4 and "settings file" in pg.text_content("#prn"), "and the starting prices are back")
        check(not errors, f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
