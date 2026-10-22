#!/usr/bin/env python3
"""The spinning wheel in a real browser (headless Chromium). Owner side: the responsibility warning is a blocking dialog, the Turn on button stays
disabled until the exact phrase is typed (lower case, an extra space or half the phrase do not count), Cancel leaves the wheel off, a wheel that pays
back more than 100% is refused with its reason. Customer side: the wheel page shows the odds before any spin, a spin ends with the result in words and the
router's time left, a double tap spends one spin, the wait between spins shows, and nothing spins by itself."""
import glob
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

CP = 18420
env = Env(offset=105)
tmp = env.tmp
Path(f"{tmp}/ubus").write_text('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
Path(f"{tmp}/setup.conf").write_text("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf")
MAC = "aa:bb:cc:00:00:01"
PHRASE = "I TAKE RESPONSIBILITY"


def bought():
    return int(env.roll()[MAC][5])


def bought_rows():
    return env.roll()


def wheel_state():
    return env.get("/wheel/state")


try:
    check(env.start(), "the portal starts")
    now = int(time.time())
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        errors = []

        # ---- the owner's console ---------------------------------------------------------------------------------------------------
        pg = br.new_page(viewport={"width": 1100, "height": 900})
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.goto(f"http://127.0.0.1:{CP}/")
        pg.wait_for_selector("#login:not(.hide)", timeout=8000)
        pg.fill("#pw", "router-pass")
        pg.click("#gobtn")
        pg.wait_for_selector("#app:not(.hide)", timeout=8000)
        pg.click('#tabs button[data-t="set"]')
        pg.wait_for_function("document.getElementById('whs').textContent.length > 0", timeout=8000)
        check("off" in pg.text_content("#whs"), "Settings says the wheel is off: " + pg.text_content("#whs"))
        check(pg.input_value("#wtb") == "0x13 2x5 3x1 5x1" and pg.input_value("#wmb") == "10" and pg.input_value("#wsp") == "10", "the default wheel and limits are listed")
        check("90.0%" in pg.text_content("#wav") and "35.0%" in pg.text_content("#wav"), "and its average return and win chance: " + pg.text_content("#wav"))
        check(not pg.is_visible("#whoff") and pg.is_visible("#whon"), "only Turn on... is offered")
        check("every visitor" in pg.text_content("#whc") and "spinning needs paid time" in pg.text_content("#whc"), "the console says what visitors see while the wheel is on")

        pg.click("#whon")
        check(pg.is_visible("#wdlg") and "Responsibility warning" in pg.text_content("#wdt"), "Turn on... opens the responsibility warning")
        text = pg.text_content("#wdlg")
        check("licence" in text and "children included" in text and "same for everyone" in text and "turn the wheel off at any time" in text, "the warning has its four points")
        check(pg.is_disabled("#wdyes") and pg.locator("#wdlg button").count() == 2, "Turn on is disabled and the dialog has no OK button, only Cancel and Turn on")
        for typed in ["i take responsibility", "I TAKE RESPONSIBILITY ", " I TAKE RESPONSIBILITY", "I TAKE", "I TAKE  RESPONSIBILITY"]:
            pg.fill("#wcf", typed)
            check(pg.is_disabled("#wdyes"), f"{typed!r} does not enable the button")
        # the page cannot be reached behind the dialog
        check(pg.evaluate("document.getElementById('wdlg').matches(':modal')"), "the dialog is modal (the rest of the page is inert)")
        pg.fill("#wcf", PHRASE)
        check(not pg.is_disabled("#wdyes"), "the exact phrase enables the button")
        pg.click("#wdno")
        check(not pg.is_visible("#wdlg") and wheel_state()[0] == 404, "Cancel closes it and the wheel is still off")
        pg.click("#whon")
        check(pg.input_value("#wcf") == "" and pg.is_disabled("#wdyes"), "opened again, the box is empty and the button disabled")
        pg.keyboard.press("Escape")
        check(not pg.is_visible("#wdlg") and wheel_state()[0] == 404, "Escape also leaves it off")

        # a bad wheel is refused with its reason; a fair one and short waits are saved while it is off
        pg.fill("#wtb", "2x3 0x1")
        check("above 100%" in pg.text_content("#wav"), "a wheel above 100% is flagged as you type: " + pg.text_content("#wav"))
        pg.click("#whsv")
        pg.wait_for_function("document.getElementById('whm').textContent.length > 0", timeout=5000)
        check("more than 100%" in pg.text_content("#whm"), "and refused by the router with the reason: " + pg.text_content("#whm"))
        pg.fill("#wtb", "2x1 0x1")
        pg.fill("#wwt", "9")
        pg.click("#whsv")
        pg.wait_for_function("document.getElementById('whm').textContent == 'Saved.'", timeout=5000)
        check(json.loads(env.get("/wheel/state")[1] or "{}").get("error") == "OFF", "saved, and the wheel is still off")

        pg.click("#whon")
        pg.fill("#wcf", PHRASE)
        pg.click("#wdyes")
        pg.wait_for_function("document.getElementById('whs').textContent.indexOf('Wheel on since') === 0", timeout=5000)
        check("responsibility confirmed" in pg.text_content("#whs") and not pg.is_visible("#wdlg") and pg.is_visible("#whoff") and not pg.is_visible("#whon"),
              "the exact phrase turns it on: " + pg.text_content("#whs"))

        # ---- a visitor who has paid nothing (feature 006): the button, the odds, a Spin that cannot be pressed ------------------------
        ctx = br.new_context(viewport={"width": 390, "height": 780}, has_touch=True)
        cp = ctx.new_page()
        cp.on("pageerror", lambda e: errors.append(str(e)))
        cp.goto(f"http://127.0.0.1:{env.PP}/")
        cp.wait_for_selector("#v-idle", state="visible", timeout=8000)
        cp.wait_for_selector("#wh", state="visible", timeout=5000)
        check("Spin the wheel" in cp.text_content("#wh") and not cp.is_visible("#gm"), "the coin page offers the wheel to a visitor who has paid nothing, and not the mini-game")
        cp.click("#wh")
        cp.wait_for_url("**/wheel")
        cp.wait_for_function("document.querySelectorAll('#odds tr').length > 1", timeout=8000)
        check(cp.locator("#odds tr").count() == 3 and "100%" in cp.text_content("#avg"), "the unpaid visitor reads the odds: " + cp.text_content("#avg"))
        check(cp.is_disabled("#go") and "Buy Wi-Fi time first" in cp.text_content("#res"), "Spin cannot be pressed and the page says why: " + cp.text_content("#res"))
        check(cp.is_disabled("#p") and cp.is_disabled("#m"), "and the bet buttons are disabled")
        check(bought_rows() == {}, "nothing was spent")

        # the same visitor pays with real coins: the 'You are online' screen offers the wheel too
        env.nds_client(MAC)
        env.set_box(busy=False, coins_at=[0.4, 0.8, 1.2])
        cp.goto(f"http://127.0.0.1:{env.PP}/")
        cp.wait_for_selector("#v-idle", state="visible", timeout=8000)
        cp.click(".coin")
        cp.wait_for_selector("#ldone", state="visible", timeout=10000)
        cp.wait_for_function("document.getElementById('lpes').textContent.indexOf('3')>=0", timeout=10000)
        cp.click("#ldone")
        cp.wait_for_selector("#v-final", state="visible", timeout=10000)
        check("You are online" in cp.text_content("#fon"), "the visitor paid and is online")
        cp.wait_for_selector("#wh", state="visible", timeout=5000)
        check("Spin the wheel" in cp.text_content("#wh"), "and the 'You are online' screen has the Spin the wheel button")

        # ---- a customer ----------------------------------------------------------------------------------------------------------
        Path(f"{env.data}/vouchers.txt").write_text(f"c1,0,0,0,0,60,{now},{MAC},0,0,0,hyper,w1,10,60,10,1\n")
        cp.close()
        cp = ctx.new_page()
        cp.on("pageerror", lambda e: errors.append(str(e)))
        cp.goto(f"http://127.0.0.1:{env.PP}/wheel")
        cp.wait_for_function("document.getElementById('left').textContent !== '-'", timeout=8000)
        check(cp.locator("#odds tr").count() == 3, "the odds panel lists every slot before any spin")
        check("same for everyone" in cp.text_content("body") and "100%" in cp.text_content("#avg") and "50%" in cp.text_content("#avg"), "and the average return and the win chance: " + cp.text_content("#avg"))
        check(cp.text_content("#left") in ("59", "60") and cp.text_content("#spl") == "10", "time left and spins left come from the router")
        check(cp.text_content("#bv").strip() == "1 min", "the bet starts at the smallest: " + cp.text_content("#bv"))
        for _ in range(4):
            cp.click("#p")
        check(cp.text_content("#bv").strip() == "5 min" and "-5 min" in cp.text_content("#what"), "the bet goes up and the page says what is at stake: " + cp.text_content("#what"))
        cp.wait_for_timeout(5000)
        check(bought() == 60, "nothing spins by itself")
        cp.dblclick("#go")
        cp.wait_for_function("/^You bet 5 min/.test(document.getElementById('res').textContent)", timeout=12000)
        res = cp.text_content("#res")
        m = re.match(r"You bet 5 min and (won|lost) 5 min\. Time left: (\d+) min\.", res)
        check(bool(m), "the result is in words: " + res)
        now_bought = bought()
        check(now_bought == (65 if m and m.group(1) == "won" else 55), f"the router's time changed by exactly the bet: {now_bought}")
        check(Path(f"{env.data}/wheel.log").read_text().count(",spin,") == 1, "a double tap made one spin")
        left = int(cp.text_content("#left"))
        check(abs(left - now_bought) <= 1 and left == int(m.group(2)), f"the page's time left matches the router's: {left} / {now_bought}")
        check(cp.text_content("#spl") == "9", "spins left went down by one")
        check(cp.is_disabled("#go") and "Wait" in cp.text_content("#go"), "the wait between spins shows on the button: " + cp.text_content("#go"))
        cp.wait_for_function("!document.getElementById('go').disabled", timeout=14000)
        check(cp.text_content("#go").strip() == "SPIN", "and the button comes back after the wait")
        check(cp.evaluate("document.getElementById('wheel').style.transform").startswith("rotate("), "the wheel was turned")

        # the coin page: the wheel button, no mini-game
        cp.goto(f"http://127.0.0.1:{env.PP}/")
        cp.wait_for_selector("#wh", state="visible", timeout=8000)   # (the purchase above still shows as the 'You are online' screen for two minutes)
        check("Spin the wheel" in cp.text_content("#wh") and not cp.is_visible("#gm"), "the coin page offers the wheel and not the mini-game")

        # ---- turned off from the console ----------------------------------------------------------------------------------------------------
        pg.click("#whoff")
        pg.wait_for_function("document.getElementById('whs').textContent.indexOf('off') >= 0", timeout=5000)
        cp.wait_for_selector("#wh", state="hidden", timeout=5000)
        check(wheel_state()[0] == 404, "off takes effect at once, on the coin page too")
        check(not errors, f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
