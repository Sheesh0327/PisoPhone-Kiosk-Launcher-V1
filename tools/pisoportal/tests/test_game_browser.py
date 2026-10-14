#!/usr/bin/env python3
"""The mini-game in a real browser (headless Chromium): the coin page's button opens it in a new tab, the game runs without script errors,
only time spent playing counts, a hidden tab pauses it, and when the day's minutes are used up the game stops and says so."""
import glob, os, shutil, sys, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

env = Env(offset=80, GAME_LIMIT_SECONDS=14)
try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        ctx = br.new_context(viewport={"width": 390, "height": 780}, has_touch=True)
        pg = ctx.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.goto(f"http://127.0.0.1:{env.PP}/?fas=ABC")
        pg.wait_for_selector("#v-idle", state="visible", timeout=8000)
        pg.wait_for_selector("#gm", state="visible", timeout=5000)
        check("Play a mini-game" in pg.text_content("#gm") and "min left today" in pg.text_content("#gms"), f"the coin page offers the game with the minutes left: {pg.text_content('#gms')}")
        with ctx.expect_page() as popup:
            pg.click("#gm")
        game = popup.value
        game.on("pageerror", lambda e: errors.append(str(e)))
        game.wait_for_load_state()
        check(game.url.endswith("/game") and game.is_visible("#ov") and "Coin Catch" in game.text_content("#ot"), "the button opens the game in a new tab, with its start card")
        check(game.text_content("#tl").strip() == "0:14", "showing the minutes left: " + game.text_content("#tl"))

        game.click("#ob")
        check(not game.is_visible("#ov"), "Play starts the game")
        for i in range(10):   # sweep the basket across the screen
            game.mouse.move(40 + (i % 2) * 300, 700)
            game.wait_for_timeout(300)
        t = game.text_content("#tl").strip()
        check(t != "0:14" and t < "0:14", f"time counts down while playing: {t}")
        basket = game.evaluate("(function(){var c=document.getElementById('c'),g=c.getContext('2d'),y=Math.round(c.height-60*(c.height/innerHeight)),d=g.getImageData(0,y,c.width,1).data,i;for(i=0;i<d.length;i+=4){if(Math.abs(d[i]-29)<6&&Math.abs(d[i+1]-99)<6&&Math.abs(d[i+2]-214)<6)return true}return false})()")
        check(basket, "the canvas draws the basket")

        # a tab that is not shown stops the game and the count; coming back resumes
        game.evaluate("Object.defineProperty(document,'hidden',{configurable:true,get:function(){return true}});document.dispatchEvent(new Event('visibilitychange'))")
        check(game.is_visible("#ov") and "Paused" in game.text_content("#ot"), "a hidden tab pauses the game")
        before = game.text_content("#tl").strip()
        game.wait_for_timeout(2500)
        check(game.text_content("#tl").strip() == before, "and no time is spent while it is paused")
        game.evaluate("Object.defineProperty(document,'hidden',{configurable:true,get:function(){return false}})")
        game.click("#ob")
        check(not game.is_visible("#ov"), "Keep playing resumes it")

        # play until the day's minutes are gone: the game stops by itself and says so (lives may run out first: play again)
        deadline = time.time() + 60
        while time.time() < deadline and "Time is up" not in game.text_content("#ot"):
            if game.is_visible("#ob"):
                game.click("#ob")
            game.mouse.move(40 + int(time.time() * 3) % 300, 700)
            game.wait_for_timeout(400)
        check("Time is up" in game.text_content("#ot") and not game.is_visible("#ob"), "the game ends when the minutes are gone, with no way to play on: " + game.text_content("#ot"))
        game.reload()
        game.wait_for_selector("h1", timeout=5000)
        used = game.content()
        check("used up" in used and "<canvas" not in used, "and the game page is refused for the rest of the day")
        # the coin page now says so too
        pg.bring_to_front()
        pg.reload()
        pg.wait_for_selector("#v-idle", state="visible", timeout=8000)
        pg.wait_for_timeout(800)
        check(not pg.is_visible("#gm") and "used up" in pg.text_content("#gms"), "the coin page hides the button and says the time is used up: " + pg.text_content("#gms"))
        check(not errors, f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
