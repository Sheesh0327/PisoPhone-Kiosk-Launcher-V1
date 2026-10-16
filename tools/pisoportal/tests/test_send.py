#!/usr/bin/env python3
"""Guests send a file to the owner: the coin page offers the button only when the key file exists; the upload page, in a real browser, sends a
file that arrives at a fake print server byte for byte with the key and the device; limits, wrong types, a down print server and a
cross-site page are handled; a router without the key refuses everything."""
import glob, http.server, json, os, shutil, threading, time, urllib.error, urllib.request
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None

KEYTXT = "s" * 40
got = []
reply = {"status": 200, "body": '{"success":true,"id":"in_1"}'}


class Fake(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        data = self.rfile.read(n)
        got.append((self.path, dict(self.headers), data))
        body = reply["body"].encode()
        self.send_response(reply["status"])
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=srv.serve_forever, daemon=True).start()
FAKE_PORT = srv.server_address[1]


def post(env, body, name="Essay.pdf", ip=None, headers=None):
    h = {"X-Filename": urllib.request.quote(name)}
    h.update(headers or {})
    req = urllib.request.Request(f"http://127.0.0.1:{env.PP}/send/upload", data=body, headers=h, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=20)
        return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


# ---- a router without the key: nothing is offered, nothing is accepted ----------------------------------------------------------------
off = Env(offset=90, SEND_HOST="127.0.0.1", SEND_PORT=FAKE_PORT)
try:
    check(off.start(), "the portal starts (no key file)")
    check(urllib.request.urlopen(f"http://127.0.0.1:{off.PP}/ping").read() == b"ok", "up")
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{off.PP}/send")
        check(False, "the page must not exist without a key")
    except urllib.error.HTTPError as e:
        check(e.code == 404, "no key file: /send is 404")
    code, j = post(off, b"%PDF-1.4 x")
    check(code == 404 and j.get("error") == "OFF" and not got, f"and an upload is refused: {code} {j}")
finally:
    off.close()

# ---- a router with the key ---------------------------------------------------------------------------------------------------------------
env = Env(offset=91, SEND_HOST="127.0.0.1", SEND_PORT=FAKE_PORT, SEND_MAX_MB=1, SEND_PER_HOUR=3)
open(f"{env.data}/send.key", "w").write(KEYTXT + "\n")
try:
    check(env.start(), "the portal starts (with the key file)")
    env.nds_client("aa:bb:cc:00:00:01")
    html = urllib.request.urlopen(f"http://127.0.0.1:{env.PP}/send").read().decode()
    check("Send a file" in html and "1048576" in html and "%MAX" not in html, "the upload page is served with the limit filled in")

    pdf = b"%PDF-1.4\n" + bytes(range(256)) * 40
    code, j = post(env, pdf, name="My Resume.pdf")
    check(code == 200 and j.get("ok") is True, f"a file is accepted: {code} {j}")
    path, hdr, data = got[-1]
    check(path == "/api/inbox/upload" and data == pdf, "and arrives byte for byte at the print server's inbox")
    check(hdr.get("X-Router-Key") == KEYTXT and hdr.get("X-Device") == "aa:bb:cc:00:00:01", f"with the key and the device: {hdr.get('X-Device')}")
    check(urllib.request.unquote(hdr.get("X-Filename", "")) == "My Resume.pdf", f"and the name: {hdr.get('X-Filename')}")

    n = len(got)
    code, j = post(env, b"x" * (1024 * 1024 + 1))
    check(code == 413 and j.get("error") == "TOO_BIG" and len(got) == n, f"too big is refused before anything is sent on: {code} {j}")
    code, j = post(env, b"")
    check(code == 413 and len(got) == n, f"an empty upload is refused: {code}")
    code, j = post(env, b"abc", headers={"Origin": "http://evil.example"})
    check(code == 403 and len(got) == n, f"another site's page cannot send files from a guest's phone: {code}")
    code, j = post(env, b"abc", name="../../etc/passwd.pdf")
    check(code == 200 and urllib.request.unquote(got[-1][1]["X-Filename"]) == "passwd.pdf", "paths in a name are dropped")

    reply.update(status=415, body='{"success":false,"error":"File extension \'.exe\' is not supported"}')
    code, j = post(env, b"MZ", name="virus.exe")
    check(code == 422 and j.get("error") == "REFUSED" and "not supported" in j.get("msg", ""), f"the print server's no is shown: {code} {j}")
    reply.update(status=200, body='{"success":true}')

    code, j = post(env, b"abc")   # the third file this hour from this device (3 allowed: two earlier ones were accepted)
    code2, j2 = post(env, b"abc")
    check(code2 == 429 and j2.get("error") == "RATE", f"a device may send only a few files an hour: {code} then {code2}")

    # ---- the print server is down ----
    env.stop()
    env.extra["SEND_PORT"] = 1
    env.start()
    code, j = post(env, b"abc")
    check(code == 502 and j.get("error") == "DOWN", f"print server down: {code} {j}")
    env.stop()
    env.extra["SEND_PORT"] = FAKE_PORT
    env.start()

    # ---- the coin page and the upload page in a real browser ----
    if sync_playwright is None:
        print("skipped: playwright is not installed (browser part)")
    else:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
        with sync_playwright() as pw:
            br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
            ctx = br.new_context(viewport={"width": 390, "height": 780}, has_touch=True)
            pg = ctx.new_page()
            errors = []
            pg.on("pageerror", lambda e: errors.append(str(e)))
            pg.goto(f"http://127.0.0.1:{env.PP}/?fas=ABC")
            pg.wait_for_selector("#v-idle", state="visible", timeout=8000)
            pg.wait_for_selector("#snd", state="visible", timeout=5000)
            check("Send a file" in pg.text_content("#snd"), "the coin page offers Send a file")
            check(pg.get_attribute("#snd", "target") is None, "the button is a plain link (the sign-in window of a phone opens no new tabs)")
            pg.click("#snd")
            pg.wait_for_url("**/send")
            up = pg
            up.on("pageerror", lambda e: errors.append(str(e)))
            up.wait_for_load_state()
            check(up.url.endswith("/send") and up.is_disabled("#go") and up.input_value("#lnk") == f"http://127.0.0.1:{env.PP}/send" and "neverssl.com" in up.text_content("#alt"),
                  "the button opens the upload page in the same window, Send disabled until a file is chosen, with the address for a normal browser")
            up.click("#cp")
            check(up.text_content("#cp").startswith(("Copied", "Press and hold")), f"Copy link answers: {up.text_content('#cp')}")
            tmpf = f"{env.tmp}/Homework.pdf"
            body = b"%PDF-1.4\nbrowser test\n" * 50
            open(tmpf, "wb").write(body)
            time.sleep(0.2)
            reply.update(status=200, body='{"success":true}')
            before = len(got)
            # 3 per hour for this device are used up by the tests above, so raise the allowance by using another device address
            env.macs["127.0.0.1"] = "aa:bb:cc:00:00:07"
            env.write_arp()
            up.set_input_files("#f", tmpf)
            check(not up.is_disabled("#go") and "Homework.pdf" in up.text_content("#pk"), "choosing a file shows its name and enables Send")
            up.click("#go")
            up.wait_for_function("document.getElementById('msg').textContent.length > 0 && document.getElementById('msg').textContent != 'Sending...'", timeout=10000)
            check("Sent!" in up.text_content("#msg") and len(got) == before + 1 and got[-1][2] == body, f"Send delivers the file and says so: {up.text_content('#msg')}")
            check(not errors, f"no script errors: {errors}")
            br.close()

finally:
    env.close()
    srv.shutdown()
finish()
