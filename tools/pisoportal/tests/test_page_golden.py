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
import pathlib
import shutil
import sys
import urllib.request

from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

from test_page_golden_cases import CASES, GOLDEN, SNAP, answering, tidy

UPDATE = "--update" in sys.argv

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
    # the voucher file the page saves (a small page the customer keeps) is today's too
    with sync_playwright() as pw2:
        chrome2 = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br2 = pw2.chromium.launch(executable_path=chrome2, args=["--no-sandbox"]) if chrome2 else pw2.chromium.launch()
        pg2 = br2.new_context(viewport={"width": 360, "height": 740}).new_page()
        pg2.route_web_socket("**/ws", answering(json.dumps(CASES["final_voucher"])))
        with pg2.expect_download(timeout=8000) as dl:
            pg2.goto(f"{base}/?fas=ABC")
        voucher = dl.value
        text = pathlib.Path(voucher.path()).read_text(encoding="utf-8")
        check(voucher.suggested_filename == "TechNet-PisoWiFi-voucher-ABCDE.html" and "<b>TechNet PisoWiFi voucher</b>" in text and "<title>PisoWiFi voucher</title>" in text and "Join the PisoWiFi network" in text,
              f"the saved voucher file is today's: {voucher.suggested_filename}")
        br2.close()
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
