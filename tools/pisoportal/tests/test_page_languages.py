#!/usr/bin/env python3
"""English and Filipino on the branded guest page (feature 049, group 5), in a real browser. The customer's button swaps the shop's texts and the
page's own wording; the choice is remembered; going back gives the English page exactly; a text without a Filipino version stays English; and
in Filipino no state of the page is left in English (a list of common English words must not appear anywhere on it). The unbranded page has no
switch and no Filipino at all (test_page_golden.py keeps it byte for byte).

CHROME=/path/to/chromium python3 test_page_languages.py"""
import glob
import json
import os
import re
import shutil
import sys
import time

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

import test_page_golden_cases as G

BOTH = ("name=Aling Nena's Net Cafe\nlayout=classic\n"
        "tagline_en=Fast. Cheap. Clean.\ntagline_fil=Mabilis. Mura. Malinis.\n"
        "welcome_en=Welcome to our shop!\nwelcome_fil=Maligayang pagdating sa aming tindahan!\n"
        "contact_en=0917 123 4567\ncontact_fil=0917 123 4567\n"
        "hours_en=Open 8am to 10pm\nhours_fil=Bukas 8am hanggang 10pm\n"
        "promo_en=Happy hour: double time until 5pm\npromo_fil=Happy hour: doble ang oras hanggang 5pm\n"
        "rules_en=No illegal downloads.\\nBe kind.\nrules_fil=Bawal ang ilegal na download.\\nMaging magalang.\n")
PARTLY = "name=Nena\ntagline_en=Only English tagline\npromo_fil=Para lang sa Filipino\nwelcome_en=Only English welcome\n"
ENGLISH = re.compile(r"\b(the|you|your|and|are|was|with|this|that|until|can|will|then|have|please|try|again|wait|another|still|more|not|yet|insert|add|tap|done|cancel|reload|"
                     r"continue|back|buy|pick|connected|paying|left|used|received|code was)\b", re.IGNORECASE)
PAID = G.PAID
EXTRA = {
    "paid_pause_unlocks_after_paying": dict(PAID, pn=5, pm=1),
    "paid_no_pauses_left": dict(PAID, pl=0),
    "paid_little_time": dict(PAID, left=100),
    "paid_pause_wait": dict(PAID, pw=20),
    "paid_one_pause": dict(PAID, pl=1),
    "paid_almost_out": dict(PAID, left=200),
    "paid_hyper_slowed": dict(PAID, plan="hyper", used_mb=300, slow=1),
    "paused_with_deadline": dict(PAID, s="paused", by=int(time.time()) + 600, pm=1, pl=1),
    "paused_resume_soon": dict(PAID, s="paused", by=0, pm=1, pl=1),
    "voucher_list": dict(G.IDLE, vc=True, vs=[{"c": "ABCDE-FGHJK", "m": 90, "p": "endurance"}]),
    "armed_for_a_voucher": dict(G.ARMED, v=True, pulses=5, minutes=90),
    "armed_for_a_voucher_no_coin": dict(G.ARMED, v=True),
    "starting_for_a_voucher": {"t": "state", "s": "starting", "plan": "hyper", "v": True},
    "final_hours": dict(G.FINAL, minutes=150),
}
REPLIES = [{"t": "rd", "ok": True, "min": 90}, {"t": "rd", "ok": False, "e": "bad"}, {"t": "rd", "ok": False, "e": "used"}, {"t": "rd", "ok": False, "e": "exp"},
           {"t": "rd", "ok": False, "e": "plan"}, {"t": "rd", "ok": False, "e": "wait", "w": 754}, {"t": "rd", "ok": False, "e": "io"}]
PAUSE_REPLIES = [{"t": "pr", "ok": False, "e": e, "w": 12} for e in ("wait", "limit", "pesos", "little", "paying", "already", "none", "zzz")]


def answering(*replies):
    def on_ws(ws):
        ws.on_message(lambda _m: [ws.send(json.dumps(r)) for r in replies])
    return on_ws


def lines(pg):
    return [l for l in G.tidy(pg.evaluate("document.body.innerText")) if l != "by TechNet Solutions"]


env = Env(offset=175)
try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    base = f"http://127.0.0.1:{env.PP}"
    page_url = f"{base}/?fas=ABC"
    brand_dir = f"{env.data}/brand"
    os.makedirs(brand_dir)

    def publish(text):
        with open(f"{brand_dir}/published.txt", "w") as f:
            f.write(text)

    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()

        def new_context(fil):
            ctx = br.new_context(viewport={"width": 360, "height": 740}, locale="en-US", timezone_id="UTC")
            if fil:
                ctx.add_init_script("try{localStorage.setItem('pp-lang','fil')}catch(e){}")
            return ctx

        def open_page(ctx, state, *more):
            pg = ctx.new_page()
            pg.route_web_socket("**/ws", answering(state, *more))
            pg.goto(page_url)
            pg.wait_for_timeout(500)
            return pg

        # ---- the switch -------------------------------------------------------------------------------------------------------------------
        publish(BOTH)
        ctx = new_context(False)
        pg = open_page(ctx, G.CASES["idle_new_customer"])
        english = lines(pg)
        check(pg.inner_text(".lang") == "Filipino" and pg.evaluate("document.documentElement.lang") == "en", "English first, and the button offers Filipino")
        check("Fast. Cheap. Clean." in english and "Welcome to our shop!" in english and "Happy hour: double time until 5pm" in english, "English texts show")
        pg.click(".lang")
        fil = lines(pg)
        check(pg.inner_text(".lang") == "English" and pg.evaluate("document.documentElement.lang") == "fil", "tapping it: Filipino, and the button offers English")
        for want in ("Mabilis. Mura. Malinis.", "Maligayang pagdating sa aming tindahan!", "Happy hour: doble ang oras hanggang 5pm", "Bukas 8am hanggang 10pm", "Mga patakaran",
                     "Bawal ang ilegal na download.", "Magpasok ng Barya", "Bumili ng Wi-Fi time", "Nagsasama-sama ang mga barya: bawat barya ay dagdag na oras", "May voucher ka ba?"):
            check(any(want in l for l in fil), f"in Filipino: {want}")
        check(not [l for l in fil if ENGLISH.search(l)], f"no English left on the idle page: {[l for l in fil if ENGLISH.search(l)]}")
        check(pg.evaluate("localStorage.getItem('pp-lang')") == "fil", "the choice is remembered")
        pg.reload()
        pg.wait_for_timeout(500)
        check(pg.inner_text(".lang") == "English" and "Magpasok ng Barya" in pg.inner_text("#go"), "after a reload the page is still Filipino")
        pg.click(".lang")
        check(lines(pg) == english, "back to English: the same page, line for line")
        check(pg.evaluate("localStorage.getItem('pp-lang')") == "en", "and that choice is remembered too")
        pg.close()
        ctx.close()

        # ---- a text without a Filipino version stays English; a Filipino-only text is hidden in English -------------------------------------
        publish(PARTLY)
        ctx = new_context(False)
        pg = open_page(ctx, G.CASES["idle_new_customer"])
        en_lines = lines(pg)
        check("Only English tagline" in en_lines and "Only English welcome" in en_lines and "Para lang sa Filipino" not in en_lines, "English: its own texts only; the Filipino-only promo is not shown")
        pg.click(".lang")
        fl = lines(pg)
        check("Only English tagline" in fl and "Only English welcome" in fl and "Para lang sa Filipino" in fl, "Filipino: the English text where there is no Filipino one, and the Filipino-only promo shows")
        pg.click(".lang")
        check(lines(pg) == en_lines, "and back again, nothing left over")
        pg.close()
        ctx.close()

        # ---- nothing left in English, state by state --------------------------------------------------------------------------------------
        publish(BOTH)
        ctx = new_context(True)
        base_controls = None
        for name, state in {**G.CASES, **EXTRA}.items():
            pg = open_page(ctx, state)
            ln = lines(pg)
            left = [l for l in ln if ENGLISH.search(l)]
            check(not left, f"Filipino, {name}: no English words left {left}")
            check(pg.is_visible(".credit") and pg.evaluate("document.documentElement.scrollWidth") <= 360, f"Filipino, {name}: credit shows, no sideways scroll")
            pg.close()
        pg = open_page(ctx, G.CASES["idle_with_time_left"])
        fil_ids = [c.split("|")[0] for c in pg.evaluate(G.SNAP)["controls"]]
        pg.close()
        ctx2 = new_context(False)
        pg = open_page(ctx2, G.CASES["idle_with_time_left"])
        check(fil_ids == [c.split("|")[0] for c in pg.evaluate(G.SNAP)["controls"]], "the same controls in both languages")
        pg.close()
        ctx2.close()

        # ---- the line that says the page cannot reach the router (after 8 seconds without a connection) -----------------------------------
        never = "window.WebSocket = function () { this.readyState = 0; this.send = function () {}; this.close = function () {}; };"  # (a connection that never opens)
        for fil in (False, True):
            c3 = new_context(fil)
            c3.add_init_script(never)
            pg = c3.new_page()
            pg.goto(page_url)
            text = pg.wait_for_function("(() => { const t = document.getElementById('conn').textContent; return /Wi-Fi/.test(t) && t; })()", timeout=14000).json_value()
            want = "Kumokonekta pa rin. Tiyaking nasa Wi-Fi ng shop ka." if fil else "Still connecting. Check you are on the shop's Wi-Fi."
            check(text == want and "TechNet" not in text, f"{'Filipino' if fil else 'English'}, the page cannot reach the router: {text!r}")
            pg.close()
            c3.close()

        # ---- the answers the page gets to a voucher code, and to a pause ------------------------------------------------------------------
        for r in REPLIES:
            pg = ctx.new_page()
            pg.route_web_socket("**/ws", lambda ws, r=r: ws.on_message(lambda m: ws.send(json.dumps(r)) if '"redeem"' in m else ws.send(json.dumps(G.CASES["idle_new_customer"]))))
            pg.goto(page_url)
            pg.wait_for_timeout(400)
            pg.evaluate("document.getElementById('vd').open=true;document.getElementById('vc').value='ABCDE-FGHJK'")
            pg.evaluate("document.getElementById('vb').click()")
            pg.wait_for_timeout(200)
            text = pg.inner_text("#vm")
            check(text and not ENGLISH.search(text), f"Filipino, answer {r.get('e', 'ok')} to a voucher code: {text!r}")
            pg.close()
        for r in PAUSE_REPLIES:
            pg = ctx.new_page()
            pg.route_web_socket("**/ws", lambda ws, r=r: ws.on_message(lambda m: ws.send(json.dumps(r)) if '"pause"' in m else ws.send(json.dumps(G.CASES["idle_with_time_left"]))))
            pg.goto(page_url)
            pg.wait_for_timeout(400)
            pg.evaluate("document.getElementById('pz').click()")
            pg.wait_for_timeout(200)
            text = pg.inner_text("#pzc")
            check(text and not ENGLISH.search(text), f"Filipino, answer {r['e']} to a pause: {text!r}")
            pg.close()
        ctx.close()
        br.close()
finally:
    env.stop()
finish()
