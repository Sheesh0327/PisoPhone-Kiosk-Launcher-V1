#!/usr/bin/env python3
"""The four layouts of the branded guest page (feature 049, group 5), in a real browser at 360 by 740: every layout in every palette, with the
longest texts a shop can type. In each one the payment controls must be on the page, visible and able to start a coin window, nothing may spill
sideways, the credit must show, and every line of text must read at 4.5 to 1 against what is behind it. All 18 page states are walked in every layout.

CHROME=/path/to/chromium python3 test_page_layouts.py"""
import base64
import glob
import hashlib
import json
import os
import shutil
import sys

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

import test_page_golden_cases as G

TINY_PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
LAYOUTS = ["classic", "banner", "compact", "poster"]
PALETTES = ["technet", "dagat", "tropiko", "ginto", "rosas", "berde"]
ACCENTS = ["", "#ff7a1a", "#101010", "#ffffff", "#16c9a4", "#ff0000"]
LONG = {"name": "W" * 32, "tagline": "M" * 60, "welcome": "Welcome " + "w" * 140, "contact": "0917" + "9" * 70, "hours": "H" * 60, "promo": "P" * 120,
        "rules": "\\n".join(["No illegal downloads or anything like that at all." for _ in range(11)])}

# contrast of every piece of text against what is behind it, measured in the browser
CONTRAST = """() => {
  const rgb = s => { const m = s.match(/[\\d.]+/g).map(Number); return [m[0], m[1], m[2], m.length > 3 ? m[3] : 1]; };
  const hexv = s => { s = s.trim(); return [1, 3, 5].map(i => parseInt(s.slice(i, i + 2), 16)); };
  const lum = c => { const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }; return .2126 * f(c[0]) + .7152 * f(c[1]) + .0722 * f(c[2]); };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
  const root = getComputedStyle(document.documentElement);
  const behind = e => {
    for (; e; e = e.parentElement) {
      const cs = getComputedStyle(e);
      if (cs.backgroundImage.includes('gradient')) return [hexv(root.getPropertyValue('--band')), hexv(root.getPropertyValue('--bg'))];
      const c = rgb(cs.backgroundColor);
      if (c[3] > 0) return [c.slice(0, 3)];
    }
    return [[0, 0, 0]];
  };
  const out = [];
  for (const sel of ['header b', 'header span', 'header em', '.lang', '.promo', '.rules', '.rules summary', '.foot', '.credit', '.hd p', '.hd span', '.btn.coin', '#gos', '.tiers .mut']) {
    const e = document.querySelector(sel);
    if (!e || !(e.offsetWidth || e.offsetHeight)) continue;
    const fg = rgb(getComputedStyle(e).color);
    out.push([sel, Math.min(...behind(e).map(b => ratio(fg.slice(0, 3), b)))]);
  }
  return out;
}"""

CHECK = """() => {
  const seen = e => !!e && !!(e.offsetWidth || e.offsetHeight);
  const go = document.getElementById('go');
  const r = go.getBoundingClientRect();
  const lb = document.querySelector('.lang').getBoundingClientRect();
  const over = [...document.querySelectorAll('header b,header span,header em,header img')].filter(seen).some(e => {
    const rs = e.tagName === 'IMG' ? [e.getBoundingClientRect()] : (() => { const g = document.createRange(); g.selectNodeContents(e); return [...g.getClientRects()]; })();
    return rs.some(r => r.width && r.left < lb.right && r.right > lb.left && r.top < lb.bottom && r.bottom > lb.top);
  });
  return {over, w: document.documentElement.scrollWidth, go: seen(go), pk: seen(document.querySelector('#pk .tiers')), seg: seen(document.querySelector('#pk .seg')),
          credit: seen(document.querySelector('.credit')), name: seen(document.querySelector('header b')), lang: seen(document.querySelector('.lang')),
          top: document.elementFromPoint(r.left + r.width / 2, Math.min(r.top + r.height / 2, innerHeight - 2))};
}"""


def publish(brand_dir, layout, palette, accent, long_texts):
    name = "Aling Nena's Net Cafe" if not long_texts else LONG["name"]
    t = {"tagline": "Mabilis. Mura. Malinis.", "welcome": "Welcome to our shop!", "contact": "0917 123 4567", "hours": "Open 8am to 10pm", "promo": "Happy hour: double time",
         "rules": "No illegal downloads.\\nBe kind."}
    if long_texts:
        t = {k: v for k, v in LONG.items() if k != "name"}
    lines = [f"name={name}", f"palette={palette}", f"layout={layout}"] + ([f"accent={accent}"] if accent else []) + [f"{k}_{lang}={v}" for k, v in t.items() for lang in ("en", "fil")]
    with open(f"{brand_dir}/published.txt", "w") as f:
        f.write("\n".join(lines) + "\n")


env = Env(offset=170)
try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    base = f"http://127.0.0.1:{env.PP}"
    page_url = f"{base}/?fas=ABC"
    brand_dir = f"{env.data}/brand"
    os.makedirs(brand_dir)
    sha = hashlib.sha256(TINY_PNG).hexdigest()
    for slot in ("logo", "picture"):
        with open(f"{brand_dir}/{slot}-{sha[:12]}.png", "wb") as f:
            f.write(TINY_PNG)
    with open(f"{brand_dir}/images.txt", "w") as f:
        f.write(f"logo logo-{sha[:12]}.png {sha}\npicture picture-{sha[:12]}.png {sha}\n")
    with open(G.GOLDEN) as f:
        golden = json.load(f)

    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        ctx = br.new_context(viewport={"width": 360, "height": 740}, locale="en-US", timezone_id="UTC")

        def open_page(state):
            sent = []

            def on_ws(ws):
                def on_msg(m):
                    try:
                        sent.append(json.loads(m))
                    except ValueError:
                        pass
                    ws.send(json.dumps(state))
                ws.on_message(on_msg)
            pg = ctx.new_page()
            pg.route_web_socket("**/ws", on_ws)
            pg.goto(page_url)
            pg.wait_for_timeout(500)
            return pg, sent

        n = 0
        for layout in LAYOUTS:
            for i, palette in enumerate(PALETTES):
                for long_texts in (False, True):
                    accent = ACCENTS[(i + LAYOUTS.index(layout)) % len(ACCENTS)]
                    publish(brand_dir, layout, palette, accent, long_texts)
                    pg, sent = open_page(G.CASES["idle_new_customer"])
                    what = f"{layout}/{palette}/{accent or 'palette accent'}/{'longest texts' if long_texts else 'normal texts'}"
                    c = pg.evaluate(CHECK)
                    check(c["go"] and c["pk"] and c["seg"], f"{what}: coin button, plan tabs and price list are on the page")
                    check(c["credit"] and c["name"] and c["lang"], f"{what}: the credit, the name and the language button show")
                    check(not c["over"], f"{what}: the language button covers none of the heading")
                    check(c["w"] <= 360, f"{what}: nothing spills sideways ({c['w']} px)")
                    pg.click("#go", timeout=3000)
                    pg.wait_for_timeout(150)
                    check(any(m.get("t") == "start" for m in sent), f"{what}: tapping the coin button starts a window")
                    pg.close()
                    pg, _ = open_page(G.CASES["idle_new_customer"])
                    bad = [(sel, round(r, 2)) for sel, r in pg.evaluate(CONTRAST) if r < 4.5]
                    check(not bad, f"{what}: all text reads at 4.5:1 or better {bad}")
                    pg.close()
                    n += 1
        print(f"  {n} layout and palette combinations")

        # every state, every layout: the same controls as the unbranded page
        for layout in LAYOUTS:
            publish(brand_dir, layout, "dagat", "", False)
            for name, state in G.CASES.items():
                pg, _ = open_page(state)
                snap = pg.evaluate(G.SNAP)
                new = ("|Filipino", "|English", "|House rules", "|Mga patakaran")  # the language button, and in the compact layout the folded rules
                controls = [c for c in snap["controls"] if c not in new]
                check(controls == golden["states"][name]["controls"] and "|Filipino" in snap["controls"], f"{layout}/{name}: the same controls as the unbranded page, and the language button")
                check(pg.is_visible(".credit") and pg.evaluate("document.documentElement.scrollWidth") <= 360, f"{layout}/{name}: credit visible, no sideways scroll")
                pg.close()
        br.close()
finally:
    env.stop()
finish()
