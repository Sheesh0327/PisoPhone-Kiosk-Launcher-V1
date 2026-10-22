#!/usr/bin/env python3
"""The wheel page as a game (feature 007), in a real browser: one labelled slice per slot with the wins spread between the losses, the odds explained from the router's
numbers, readable labels (contrast), the odds table also without script, a stop that is always on a slice of the router's result (and never near an edge), the same
spin for a win and a loss, a tick sound that stays silent until the speaker button is pressed, a celebration on a win only, and the last five spins kept in the browser
only (a phone that blocks storage still works)."""
import glob
import math
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

env = Env(offset=116)
MAC = "aa:bb:cc:00:00:01"
SHOT = os.environ.get("WHEEL_SHOT_DIR")


def setup(table=None):
    now = int(time.time())
    # the default wheel keeps the default limits; the coin wheel is spun many times, so its limits are lifted
    lines = "WHEEL=on\n" + (f"WHEEL_WAIT=0\nWHEEL_SPINS_PER_DAY=500\nWHEEL_WIN_CAP=100000\nWHEEL_TABLE={table}\n" if table else "")
    Path(f"{env.data}/settings.txt").write_text(lines)
    Path(f"{env.data}/wheel-confirm.txt").write_text(f"v=1\nversion=1\nat={now}\nsession=test\n")
    Path(f"{env.data}/vouchers.txt").write_text(f"c1,0,0,0,0,3000,{now},{MAC},0,0,0,hyper,w1,10,3000,10,1\n")


def spins_logged():
    p = Path(f"{env.data}/wheel.log")
    return [l.split(",") for l in p.read_text().splitlines() if ",spin," in l] if p.exists() else []


def angle(pg):
    return float(re.search(r"rotate\(([-\d.]+)deg\)", pg.evaluate("document.getElementById('wheel').style.transform")).group(1))


def under_pointer(pg):
    """The slice under the pointer: (its mult, how far into the slice the pointer is, 0 to 1)."""
    n = pg.locator("#wheel path.sl").count()
    step = 360 / n
    under = (-angle(pg)) % 360
    i = int(under // step)
    return int(pg.locator("#wheel path.sl").nth(i).get_attribute("data-mult")), (under - i * step) / step


try:
    setup()
    check(env.start(), "the portal starts")
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        errors = []

        def page(ctx):
            pg = ctx.new_page()
            pg.on("pageerror", lambda e: errors.append(str(e)))
            return pg

        # ---- the default wheel, as a game ----------------------------------------------------------------------------------------------------
        ctx = br.new_context(viewport={"width": 360, "height": 740}, has_touch=True)
        ctx.add_init_script("window.__ac=0;(function(){var A=window.AudioContext||window.webkitAudioContext;if(A){window.AudioContext=function(){window.__ac++;return new A()};window.webkitAudioContext=window.AudioContext}})()")
        pg = page(ctx)
        pg.goto(f"http://127.0.0.1:{env.PP}/wheel")
        pg.wait_for_function("document.querySelectorAll('#wheel path.sl').length > 0", timeout=8000)
        check(pg.locator("#wheel path.sl").count() == 20, "the default table is drawn as 20 equal slices")
        mults = pg.evaluate("Array.from(document.querySelectorAll('#wheel path.sl')).map(function(p){return +p.getAttribute('data-mult')})")
        labels = pg.evaluate("Array.from(document.querySelectorAll('#wheel text')).map(function(t){return t.textContent})")
        check(sorted(mults) == [0] * 13 + [2] * 5 + [3, 5], f"13 lose, 5 x2, 1 x3, 1 x5 on the wheel: {sorted(mults)}")
        check(labels.count("LOSE") == 13 and labels.count("x2") == 5 and labels.count("x3") == 1 and labels.count("x5") == 1, "every slice is labelled with what it pays")
        wins = [i for i, m in enumerate(mults) if m]
        gaps = [(wins[(j + 1) % len(wins)] - wins[j]) % 20 for j in range(len(wins))]
        check(min(gaps) >= 2 and max(gaps) - min(gaps) <= 1, f"the wins are spread between the losses: {gaps}")
        check(pg.evaluate("document.querySelectorAll('#rim circle').length") > 20, "the rim has its lights")
        rows = pg.evaluate("Array.from(document.querySelectorAll('#odds tr')).map(function(r){return r.textContent})")
        check(len(rows) == 5 and "LOSEyour bet is lost13 of 20 (65%)" in rows[1].replace(" ", "") or "13 of 20 (65%)" in rows[1], f"the odds table lists every row with its chance: {rows[1]}")
        check("Chance to win: 35%" in pg.text_content("#avg") and "90%" in pg.text_content("#avg"), "the odds in one line: " + pg.text_content("#avg"))
        check("about 7 win and 13 lose" in pg.text_content("#why") and "90 minutes of every 100" in pg.text_content("#why"), "the odds in words: " + pg.text_content("#why"))
        check("bet 1 min: lose -1, x2 +1, x3 +2, x5 +4" in pg.text_content("#ex"), "the worked example for the current bet: " + pg.text_content("#ex"))
        pg.click("#chips button[data-b='5']")
        check("bet 5 min: lose -5, x2 +5, x3 +10, x5 +20" in pg.text_content("#ex") and pg.text_content("#bv").strip() == "5 min", "a quick-bet chip sets the bet and the example follows: " + pg.text_content("#ex"))
        check("1 to 10 min" in pg.text_content("#lim") and "keep 5 min" in pg.text_content("#lim") and "10 spins a day" in pg.text_content("#lim") and "60 min" in pg.text_content("#lim"), "the limits in force are written out: " + pg.text_content("#lim"))
        ratio = pg.evaluate("""(function(){function lum(c){var v=[1,3,5].map(function(i){var x=parseInt(c.slice(i,i+2),16)/255;return x<=.03928?x/12.92:Math.pow((x+.055)/1.055,2.4)});return .2126*v[0]+.7152*v[1]+.0722*v[2]}
          var worst=99,ps=document.querySelectorAll('#wheel path.sl'),ts=document.querySelectorAll('#wheel text');
          for(var i=0;i<ps.length;i++){var a=lum(ps[i].getAttribute('fill')),b=lum(ts[i].getAttribute('fill')),r=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);if(r<worst)worst=r}return worst})()""")
        check(ratio >= 4.5, f"every label has at least 4.5:1 contrast against its slice (worst {ratio:.1f}:1)")
        check(pg.evaluate("window.__ac") == 0, "no sound context is made until the speaker button is pressed")
        pg.click("#snd")
        check(pg.evaluate("window.__ac") == 1 and pg.text_content("#snd") == "Sound on" and pg.get_attribute("#snd", "aria-pressed") == "true", "the speaker button turns the tick sound on")
        pg.click("#snd")
        check(pg.text_content("#snd") == "Sound off", "and off again")
        check(pg.evaluate("getComputedStyle(document.getElementById('go')).minHeight") != "0px" and pg.evaluate("document.documentElement.scrollWidth") <= 360, "the page fits a 360 px phone without sideways scrolling")
        if SHOT:
            os.makedirs(SHOT, exist_ok=True)
            pg.screenshot(path=os.path.join(SHOT, "wheel-360x740.png"), full_page=True)
        pg.close()
        # no script: the odds are still in the page
        nj = br.new_context(java_script_enabled=False)
        npg = nj.new_page()
        npg.goto(f"http://127.0.0.1:{env.PP}/wheel")
        check(npg.locator("#odds tr").count() == 5 and "13 of 20 (65%)" in npg.text_content("#odds") and "1 of 20 (5%)" in npg.text_content("#odds"), "without script the odds table is still in the page")
        nj.close()

        # ---- a fair coin wheel: landing, the same spin for a win and a loss, the celebration, the recent spins ---------------------------------------
        env.stop()
        setup("2x1 0x1")
        check(env.start(), "the portal restarts with a two-slice wheel")
        rc = br.new_context(viewport={"width": 360, "height": 740}, reduced_motion="reduce")
        rp = page(rc)
        rp.goto(f"http://127.0.0.1:{env.PP}/wheel")
        rp.wait_for_function("document.querySelectorAll('#wheel path.sl').length === 2", timeout=8000)
        rp.evaluate("window.__f=0;new MutationObserver(function(m){m.forEach(function(x){if(document.getElementById('pin').classList.contains('f'))window.__f++})}).observe(document.getElementById('pin'),{attributes:true,attributeFilter:['class']})")
        bad = edge = 0
        seen = set()
        for k in range(12):
            before = len(spins_logged())
            rp.click("#go")
            check(wait_until(lambda: len(spins_logged()) == before + 1, 8), f"spin {k + 1} reached the router")
            rp.wait_for_function("document.getElementById('res').textContent.indexOf('You bet') === 0 && !document.getElementById('go').disabled", timeout=8000)
            mult = int(spins_logged()[-1][5])
            seen.add(mult)
            under, within = under_pointer(rp)
            bad += under != mult
            edge += not (0.0999 <= within <= 0.9001)
            time.sleep(0.15)
        check(bad == 0, "the wheel always stops on a slice of the router's result (12 spins, reduced motion)")
        check(edge == 0, "and always inside the middle 80% of that slice")
        check(rp.evaluate("window.__f") == 0 and rp.locator(".c").count() == 0, "reduced motion: no pointer flicks and no celebration")
        check(rp.locator("#rec span").count() == 5, "the last five spins are listed on this phone: " + rp.text_content("#rec"))
        rp.reload()
        rp.wait_for_function("document.querySelectorAll('#rec span').length === 5", timeout=8000)
        check(True, "and are still there after a reload")
        rc.close()

        mc = br.new_context(viewport={"width": 360, "height": 740})
        mp = page(mc)
        mp.goto(f"http://127.0.0.1:{env.PP}/wheel")
        mp.wait_for_function("document.querySelectorAll('#wheel path.sl').length === 2", timeout=8000)
        check(mp.evaluate("getComputedStyle(document.getElementById('wheel')).transitionDuration") == "4.2s", "the wheel turns for the same 4.2 s whatever the result")
        mp.evaluate("window.__f=0;new MutationObserver(function(m){m.forEach(function(x){if(document.getElementById('pin').classList.contains('f'))window.__f++})}).observe(document.getElementById('pin'),{attributes:true,attributeFilter:['class']})")
        took = {}
        party = {}
        for k in range(14):
            if 0 in took and 2 in took:
                break
            before = len(spins_logged())
            t0 = time.time()
            f0 = mp.evaluate("window.__f")
            mp.click("#go")
            mp.wait_for_function("document.getElementById('res').textContent.indexOf('You bet') === 0", timeout=12000)
            dt = time.time() - t0
            mult = int(spins_logged()[-1][5])
            took[mult] = dt
            party[mult] = mp.locator(".c").count()
            check(mp.evaluate("window.__f") - f0 >= 8, f"the pointer flicks as the wheel passes the slices ({mp.evaluate('window.__f') - f0} flicks)")
            under, within = under_pointer(mp)
            check(under == mult, "and it stops on the router's result")
            mp.wait_for_function("!document.getElementById('go').disabled", timeout=8000)
        check(0 in took and 2 in took, f"both a loss and a win were seen: {sorted(took)}")
        if 0 in took and 2 in took:
            check(abs(took[0] - took[2]) < 0.8, f"a win and a loss take the same time ({took[2]:.1f} s and {took[0]:.1f} s)")
            check(party[2] > 10 and party[0] == 0, f"the celebration plays on a win ({party[2]} bits) and not on a loss ({party[0]})")
        mc.close()

        # a phone that blocks storage still works
        bc = br.new_context(viewport={"width": 360, "height": 740})
        bc.add_init_script("Object.defineProperty(window,'localStorage',{get:function(){throw new Error('blocked')}})")
        bp = page(bc)
        bp.goto(f"http://127.0.0.1:{env.PP}/wheel")
        bp.wait_for_function("document.querySelectorAll('#wheel path.sl').length === 2", timeout=8000)
        check(not bp.is_visible("#rec") and not bp.is_disabled("#go"), "with storage blocked the wheel still opens and nothing is listed")
        bc.close()
        check(not errors, f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
