#!/usr/bin/env python3
"""The console's Rental phones tab in a real browser (headless Chromium): it shows the coin box's own page (passed through one port above the
console, with the router's box login) inside the console, the console's content security policy allows exactly that frame, and the console's
light/dark switch reaches the page in the frame."""
import base64, glob, http.server, os, shutil, sys, threading
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

CP = 18390
env = Env(offset=95)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/leases", "w").write("")
open(f"{tmp}/route", "w").write("Iface\tDestination\tGateway\tFlags\neth9\t00000000\t0101A8C0\t0003\n")
open(f"{tmp}/dev", "w").write(" face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n  eth9: 1000 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0\n")
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")

PAGE = b"""<!doctype html><html><head><title>Coin box</title></head><body><h1 id="h">Coin box dashboard</h1><p id="t">no theme yet</p>
<script>
document.getElementById('t').textContent = 'start:' + (new URLSearchParams(location.search).get('theme') || '-');
addEventListener('message', function (e) { if (e.data && e.data.piso === 'theme') document.getElementById('t').textContent = 'theme:' + e.data.theme; });
</script></body></html>"""


class Stub(http.server.BaseHTTPRequestHandler):   # the coin box: its page needs the admin login
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/api/gateway/challenge"):
            body, ctype = b'{"nonce":"00112233445566778899aabbccddeeff"}', "application/json"
        elif self.headers.get("Authorization") != "Basic " + base64.b64encode(b"admin:box-pass-123").decode():
            self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
        else:
            body, ctype = PAGE, "text/html"
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Frame-Options", "DENY"); self.end_headers(); self.wfile.write(body)


stub = http.server.ThreadingHTTPServer(("127.0.0.1", CP + 10), Stub)
threading.Thread(target=stub.serve_forever, daemon=True).start()
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", DHCP_LEASES=f"{tmp}/leases", ROUTE_FILE=f"{tmp}/route", NETDEV_FILE=f"{tmp}/dev",
                 PISO_SETUP_CONF=f"{tmp}/setup.conf", GW_BOX=f"127.0.0.1:{CP + 10}")

try:
    check(env.start(), "the portal starts")
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        pg = br.new_context(viewport={"width": 1280, "height": 800}).new_page()
        errors, blocked = [], []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: blocked.append(m.text) if "Content Security Policy" in m.text or "frame" in m.text.lower() and "refused" in m.text.lower() else None)
        pg.goto(f"http://127.0.0.1:{CP}/")
        pg.wait_for_selector("#pw", state="visible", timeout=8000)
        pg.fill("#pw", "router-pass"); pg.click("#gobtn")
        pg.wait_for_selector("#tabs", state="visible", timeout=8000)
        pg.click("#tabs button[data-t=ph]")
        pg.wait_for_selector("#boxf", state="visible", timeout=5000)
        frame = None
        for _ in range(40):
            frame = next((f for f in pg.frames if f.url.startswith(f"http://127.0.0.1:{CP + 1}/")), None)
            if frame and frame.query_selector("#h"):
                break
            pg.wait_for_timeout(250)
        check(frame is not None and frame.text_content("#h") == "Coin box dashboard", "the Rental phones tab shows the coin box's page in a frame (the router added the box login, the box's framing refusal is gone)")
        check("embed=1" in frame.url and ("theme=light" in frame.url or "theme=dark" in frame.url) and frame.text_content("#t").split(":")[0] in ("start", "theme"), "the frame is asked for the embedded view with the console's theme: " + frame.url + " " + frame.text_content("#t"))
        pg.click("#th")
        pg.wait_for_function("document.querySelector('#boxf').contentWindow !== null", timeout=3000)
        want = pg.evaluate("document.documentElement.dataset.theme")
        for _ in range(20):
            if frame.text_content("#t") == f"theme:{want}":
                break
            pg.wait_for_timeout(150)
        check(frame.text_content("#t") == f"theme:{want}", f"the console's light/dark switch reaches the page in the frame: {frame.text_content('#t')} (wanted {want})")
        check(not errors and not blocked, f"no script errors and nothing refused by the content security policy: {errors} {blocked}")
        pg.click("#tabs button[data-t=over]")
        check(not pg.is_visible("#boxf"), "another tab hides the frame")
        br.close()
finally:
    env.close()
    stub.shutdown()
finish()
