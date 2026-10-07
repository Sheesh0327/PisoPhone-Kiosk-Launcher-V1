#!/usr/bin/env python3
"""The coin page in a real browser (headless Chromium): one page, no reloads, WebSocket only."""
import glob, os, shutil, sys, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

MAC_A = "aa:bb:cc:00:00:01"
env = Env(offset=20)
try:
    check(env.start(), "the portal starts")
    env.nds_client(MAC_A)
    url = f"http://127.0.0.1:{env.PP}/?fas=ABC"
    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = br.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        # the phone's speech engine can freeze the page for a moment when first used: the tap must already have changed the screen
        pg.add_init_script("""(function(){window.__spoke=null;var sp=window.speechSynthesis;if(!sp)return;var orig=sp.speak.bind(sp);
          sp.speak=function(u){if(window.__spoke===null){var l=document.getElementById('v-live');window.__spoke=!!l&&l.style.display==='block';
          var t=Date.now();while(Date.now()-t<400){}}return orig(u)}})()""")
        pg.goto(url)
        pg.wait_for_selector("#v-idle", state="visible", timeout=8000)
        check(pg.is_visible(".coin") and "HyperSpeed" in pg.content() and "Endurance" in pg.content(), "the plan page shows at once with the price list")
        check(not pg.is_visible("#ion"), "nothing paid yet: no online notice")
        env.set_box(busy=False, coins_at=[1.0, 1.6, 2.2])
        pg.click(".coin")
        pg.wait_for_timeout(150)
        check(pg.is_visible("#v-live") and ("Getting the coin slot ready" in (pg.text_content("#lsub") or "") or "Insert coin" in (pg.text_content("#lsub") or "")), "the tap leaves the plan page at once: " + (pg.text_content("#lsub") or ""))
        pg.wait_for_selector("#lcd", state="visible", timeout=8000)
        check("Insert coin" in pg.text_content("#lsub"), "the countdown starts once the slot is armed: " + pg.text_content("#lsub"))
        pg.wait_for_selector("#ldone", state="visible", timeout=8000)
        check(pg.text_content("#lpes").strip() in ("₱1", "₱2", "₱3"), "coins show as the box counts them: " + pg.text_content("#lpes"))
        check(not pg.is_visible("#v-final") and env.nds_get(MAC_A)["STATE"] == "Preauthenticated", "NOT online while coins go in (the phone would close the page)")
        pg.wait_for_function("document.getElementById('lpes').textContent.indexOf('3')>=0", timeout=8000)
        t0 = time.time()
        pg.click("#ldone")
        pg.wait_for_selector("#v-final", state="visible", timeout=10000)
        check(time.time() - t0 < 4, f"Done closes the window at once ({time.time() - t0:.1f} s; the idle wait is 3 s)")
        check("You are online" in pg.text_content("#fon") and "18 min" in pg.text_content("#fleft"), "the final view says online and the time left: " + pg.text_content("#fleft"))
        check(env.nds_get(MAC_A)["STATE"] == "Authenticated", "and the device really is online")
        check(pg.evaluate("window.__spoke") is True, "the screen changed before the speech engine was touched")
        # a second payment on the same plan from the same page (Add more time)
        env.set_box(busy=False, coins_at=[0.5])
        pg.click("#fagain")
        pg.wait_for_selector("#ion", state="visible", timeout=5000)
        check("You are online" in pg.text_content("#ion") and "left" in pg.text_content("#ion"), "Add more time: the plan page shows the time left: " + pg.text_content("#ion"))
        # the other plan needs consent
        pg.click("input[value=endurance]", force=True)
        pg.click(".coin")
        pg.wait_for_selector("#v-msg", state="visible", timeout=5000)
        check("You still have" in pg.text_content("#mtext") and "HyperSpeed" in pg.text_content("#mtext"), "switching plans asks first: " + pg.text_content("#mtext"))
        pg.click("text=Add HyperSpeed time")
        pg.wait_for_selector("#v-final", state="visible", timeout=30000)
        check("6 min" in pg.text_content("#fmin") or "₱1" in pg.text_content("#fmin"), "the added window is priced: " + pg.text_content("#fmin"))
        # the portal restarts: the page says so and comes back by itself
        env.stop()
        pg.wait_for_selector("#v-conn", state="visible", timeout=8000)
        env.start()
        pg.wait_for_selector("#ion", state="visible", timeout=10000)
        check("You are online" in pg.text_content("#ion"), "the page reconnects by itself after a restart and shows the time left")
        check(not errors, f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
