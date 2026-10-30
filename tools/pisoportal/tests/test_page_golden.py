#!/usr/bin/env python3
"""Today's guest page, state by state (feature 049, group 0). The page the portal serves with no branding set, and what a customer
sees in each state (the visible text and the visible controls), are kept in golden/page_states.json. Feature 049 must leave all of it
unchanged on a router where nobody published any branding (success criterion 1). `--update` rewrites the golden file: only for a
change the owner asked for, never to make a branding change pass.

CHROME=/path/to/chromium python3 test_page_golden.py [--update]"""
import glob
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

GOLDEN = os.path.join(HERE, "golden", "page_states.json")
UPDATE = "--update" in sys.argv

# what the portal sends the page in each state (the keys the page's view() reads)
IDLE = {"t": "state", "s": "idle", "left": 0, "plan": "", "vc": False, "vs": [], "sd": False, "cooldown": 0, "busy": 0}
PAID = dict(IDLE, left=7200, plan="endurance", used_mb=0, slow=0, pm=1, pn=0, pl=2, pw=0)
ARMED = {"t": "state", "s": "armed", "plan": "endurance", "pulses": 0, "minutes": 0, "remaining": 20, "total": 30, "v": False}
FINAL = {"t": "state", "s": "final", "plan": "endurance", "online": True, "left": 10800, "pulses": 5, "minutes": 180}
CASES = {
    "idle_new_customer": IDLE,
    "idle_with_voucher_choice": dict(IDLE, vc=True),
    "idle_with_time_left": PAID,
    "idle_another_customer_paying": dict(IDLE, busy=7),
    "idle_cooldown": dict(IDLE, cooldown=30),
    "paused": dict(PAID, s="paused", pm=1, pl=1, by=0),
    "starting": {"t": "state", "s": "starting", "plan": "hyper", "v": False},
    "armed_no_coin": ARMED,
    "armed_with_coins": dict(ARMED, pulses=5, minutes=180),
    "closing": {"t": "state", "s": "closing", "plan": "endurance", "pulses": 5, "v": False},
    "final_online": FINAL,
    "final_paid_not_online_yet": dict(FINAL, online=False),
    "final_voucher": dict(FINAL, vcode="ABCDE-FGHJK", vexp=1900000000),
    "empty_window": {"t": "state", "s": "empty"},
    "busy": {"t": "state", "s": "busy", "retry": 7},
    "cooldown": {"t": "state", "s": "cooldown", "retry": 30},
    "mismatch": {"t": "state", "s": "mismatch", "plan": "hyper", "left": 600},
    "error": {"t": "state", "s": "error", "e": "NO_ANSWER"},
}

SNAP = """() => {
  const seen = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const controls = [...document.querySelectorAll('button,a.btn,summary,label,input[type=text],input:not([type])')].filter(seen)
      .map(e => (e.id || e.getAttribute('for') || '') + '|' + (e.innerText || e.placeholder || '').trim());
  return {text: document.body.innerText, controls};
}"""


def tidy(text):
    text = re.sub(r"\d{1,2}:\d{2}(:\d{2})?", "#:##", text)          # running clocks
    return [ln.strip() for ln in text.splitlines() if ln.strip()]


def answering(reply):
    """A fake /ws that answers whatever the page says (its hello) with one state."""
    return lambda ws: ws.on_message(lambda _m: ws.send(reply))


env = Env(offset=140)
try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    base = f"http://127.0.0.1:{env.PP}"
    served = [urllib.request.urlopen(f"{base}/?fas=ABC").read() for _ in range(2)]
    check(served[0] == served[1], "the page is the same bytes every time it is fetched")
    got = {"page_sha256": hashlib.sha256(served[0]).hexdigest(), "page_bytes": len(served[0]), "states": {}}
    for asset in ("brand.png", "mascot.png"):
        got[asset + "_sha256"] = hashlib.sha256(urllib.request.urlopen(f"{base}/{asset}").read()).hexdigest()
    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        ctx = br.new_context(viewport={"width": 360, "height": 740}, locale="en-US", timezone_id="UTC")
        for name, state in CASES.items():
            print('  state', name, flush=True)
            pg = ctx.new_page()
            pg.route_web_socket("**/ws", answering(json.dumps(state)))
            pg.goto(f"{base}/?fas=ABC")
            pg.wait_for_timeout(700)
            snap = pg.evaluate(SNAP)
            got["states"][name] = {"text": tidy(snap["text"]), "controls": snap["controls"]}
            pg.close()
        br.close()
    if UPDATE:
        os.makedirs(os.path.dirname(GOLDEN), exist_ok=True)
        with open(GOLDEN, "w") as f:
            json.dump(got, f, indent=1, ensure_ascii=False, sort_keys=True)
            f.write("\n")
        print(f"wrote {GOLDEN}")
    else:
        with open(GOLDEN) as f:
            want = json.load(f)
        check(got["page_sha256"] == want["page_sha256"], f"the page served with no branding is today's page ({got['page_bytes']} bytes, was {want['page_bytes']})")
        for asset in ("brand.png", "mascot.png"):
            check(got[asset + "_sha256"] == want[asset + "_sha256"], f"{asset} is today's picture")
        for name in CASES:
            check(got["states"][name] == want["states"].get(name), f"state {name}: what the customer sees is unchanged")
        check(set(want["states"]) == set(CASES), "the golden file and the test list the same states")
finally:
    env.stop()
finish()
