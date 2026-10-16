#!/usr/bin/env python3
"""Tests of the owner's admin QR login: scripts/make_admin_qr.py (the key, the QR picture), the browser code in
esp32_firmware/web/adminqr.js (SHA-256 and ECDSA P-256 signing, written for plain HTTP pages where crypto.subtle is missing) and
the QR reader jsqr.js, against a stand-in for the box that checks signatures the way the firmware does (esp32_firmware/include/
AdminQrAuth.h). Needs: pip install cryptography qrcode pillow; node; and playwright for the browser part.
Run:  python3 scripts/tests/test_admin_qr.py"""
import base64, glob, hashlib, http.server, json, os, random, subprocess, sys, tempfile, threading, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import make_admin_qr as qr
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

WEB = os.path.join(ROOT, "esp32_firmware", "web")
tmp = tempfile.mkdtemp()
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


# ---- the script: payload, key file, header ------------------------------------------------------------------------------------
key = ec.generate_private_key(ec.SECP256R1())
text = qr.payload_for(key)
check(text.startswith("PISOADMIN1.") and len(text.split(".")) == 4 and len(text) < 160, f"the payload is short enough for a small QR code: {len(text)}")
check(qr.key_from_payload(text).private_numbers().private_value == key.private_numbers().private_value, "the payload gives the key back")
for bad in ("", "hello", "PISOADMIN2." + text.split(".", 1)[1], text[:-3], text.replace("PISOADMIN1.", "PISOADMIN1.A")):
    try:
        qr.key_from_payload(bad)
        check(False, f"refused: {bad[:30]!r}")
    except (ValueError, Exception):
        check(True, f"refused: {bad[:30]!r}")
other_pub = ec.generate_private_key(ec.SECP256R1()).public_key().public_numbers()
parts = text.split(".")
mixed = ".".join(parts[:2] + [qr.b64u(other_pub.x.to_bytes(32, "big")), qr.b64u(other_pub.y.to_bytes(32, "big"))])
try:
    qr.key_from_payload(mixed)
    check(False, "a private number with someone else's public point is refused")
except ValueError as e:
    check("damaged" in str(e), "a private number with someone else's public point is refused")

header = os.path.join(tmp, "SuperAdminPubKey.h")
qr.write_pubkey_header(key.public_key(), header)
check(qr.header_matches(key.public_key(), header) and not qr.header_matches(ec.generate_private_key(ec.SECP256R1()).public_key(), header),
      "the firmware header holds exactly this key's public half")
body = open(header).read()
check("SUPER_ADMIN_PUBKEY_LEN = sizeof" in body and "clang-format off" in body, "the header has the names the firmware uses")
sig = qr.sign(key, "00" * 16, "AA:BB:CC:DD:EE:FF")
key.public_key().verify(base64.b64decode(sig), qr.signed_message("00" * 16, "AA:BB:CC:DD:EE:FF").encode(), ec.ECDSA(hashes.SHA256()))
check(True, "the reference signature verifies")

# the command line: first run makes the key and the picture, the next run draws the same picture from the same key
env = dict(os.environ)
key_file, picture = os.path.join(tmp, "k.key"), os.path.join(tmp, "card.png")
run = lambda *a: subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "make_admin_qr.py"), "--private", key_file, "--out", picture, *a],
                                capture_output=True, text=True, env=env)
real_header = os.path.join(ROOT, "esp32_firmware", "include", "SuperAdminPubKey.h")
saved_header = open(real_header).read()
try:
    r = run()
    check(r.returncode == 0 and os.path.exists(key_file) and os.path.exists(picture) and "commit it" in r.stdout, "first run: a key and a picture: " + r.stdout + r.stderr)
    check(oct(os.stat(key_file).st_mode & 0o777) == "0o600" and oct(os.stat(picture).st_mode & 0o777) == "0o600", "both files are private")
    first = open(key_file, "rb").read()
    r = run()
    check(r.returncode == 0 and open(key_file, "rb").read() == first and "WARNING" not in r.stdout and "New key" not in r.stdout, "second run: the same key, no warning: " + r.stdout)
    r = run("--new")
    check(r.returncode == 0 and open(key_file, "rb").read() != first and os.path.exists(key_file + ".old"), "--new: a new key, the old one kept")
    open(real_header, "w").write(saved_header)   # (the runs above rewrote the firmware header: put it back)
    r = run()
    check("WARNING" in r.stdout, "a header that holds another key is warned about")
finally:
    open(real_header, "w").write(saved_header)

# ---- the browser code in node (no page): signing and hashing against Python ------------------------------------------------------
node = subprocess.run(["node", "--version"], capture_output=True, text=True)
check(node.returncode == 0, "node is available")
runner = os.path.join(tmp, "sign.js")
open(runner, "w").write("""global.window = {}; global.crypto = require('crypto').webcrypto;
require(%r);
const A = window.PisoAdminQr;
const [mode, a, b] = process.argv.slice(2);
if (mode === 'sign') console.log(A.sign(A.parsePayload(a), b));
else if (mode === 'sha') console.log(Buffer.from(A.sha256(new TextEncoder().encode(a))).toString('hex'));
else if (mode === 'parse') { try { A.parsePayload(a); console.log('ok'); } catch (e) { console.log('error: ' + e.message); } }
""" % os.path.join(WEB, "adminqr.js"))
node_run = lambda *a: subprocess.run(["node", runner, *a], capture_output=True, text=True).stdout.strip()
for s in ("", "abc", "x" * 55, "x" * 56, "x" * 63, "x" * 64, "x" * 65, "é" * 100, "pisophone-admin-v1|" + "ab" * 16 + "|AA:BB:CC:DD:EE:FF"):
    check(node_run("sha", s) == hashlib.sha256(s.encode()).hexdigest(), f"sha-256 of {len(s)} characters matches")
rng = random.Random(5)
for i in range(8):
    k = ec.generate_private_key(ec.SECP256R1())
    nonce, box = "%032x" % rng.getrandbits(128), "AA:BB:CC:DD:EE:%02X" % i
    s = node_run("sign", qr.payload_for(k), qr.signed_message(nonce, box))
    try:
        k.public_key().verify(base64.b64decode(s), qr.signed_message(nonce, box).encode(), ec.ECDSA(hashes.SHA256()))
        check(True, "browser signature verifies")
    except Exception as e:
        check(False, f"the browser's signature for key {i} does not verify: {e} {s[:40]}")
s = node_run("sign", text, "one message")
try:
    other_key = ec.generate_private_key(ec.SECP256R1())
    other_key.public_key().verify(base64.b64decode(s), b"one message", ec.ECDSA(hashes.SHA256()))
    check(False, "another key must not verify it")
except Exception:
    check(True, "another key does not verify it")
check(node_run("parse", text) == "ok", "the page accepts a good payload")
check("misread" in node_run("parse", mixed) and "not a PisoPhone" in node_run("parse", "hello"), "the page refuses a damaged payload and other QR codes")

# ---- the page in a browser: a photo of the QR code signs in --------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sync_playwright = None
    print("note: playwright / pillow are not installed: the page was not run in a browser")

if sync_playwright:
    BOX = "AA:BB:CC:DD:EE:01"
    state = {"nonces": {}, "logins": [], "key": key}

    class FakeBox(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def reply(self, code, body, ctype="application/json", extra=()):
            data = body.encode() if isinstance(body, str) else body
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            for k, v in extra:
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            path = urllib.parse.urlsplit(self.path)
            if path.path == "/login":
                nxt = urllib.parse.parse_qs(path.query).get("next", ["/"])[0]
                page = ('<!DOCTYPE html><html><body data-next="%s"><input id="qr" type="file" accept="image/*" capture="environment" hidden>'
                        '<p id="msg"></p><script src="/assets/jsqr.js"></script><script src="/assets/adminqr.js"></script>'
                        "<script>PisoAdminQr.bindLoginPage(document.getElementById('qr'), document.getElementById('msg'));</script>"
                        "</body></html>") % nxt.replace('"', "&quot;")
                return self.reply(200, page, "text/html")
            if path.path.startswith("/assets/"):
                name = os.path.basename(path.path)
                return self.reply(200, open(os.path.join(WEB, name), "rb").read(), "application/javascript")
            if path.path == "/api/superadmin/challenge":
                nonce = "%032x" % random.getrandbits(128)
                state["nonces"][nonce] = True
                return self.reply(200, json.dumps({"nonce": nonce, "box": BOX}))
            if path.path.startswith("/dashboard"):
                return self.reply(200, "<html><body id='dash'>dashboard</body></html>", "text/html")
            self.reply(404, "{}")

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            form = urllib.parse.parse_qs(self.rfile.read(length).decode())
            nonce, sig = form.get("nonce", [""])[0], form.get("sig", [""])[0]
            ok = False
            if state["nonces"].pop(nonce, None):
                try:   # what the firmware does: verify the signature over the signed message with the public key it carries
                    state["key"].public_key().verify(base64.b64decode(sig), qr.signed_message(nonce, BOX).encode(), ec.ECDSA(hashes.SHA256()))
                    ok = True
                except Exception:
                    ok = False
            state["logins"].append(ok)
            if ok:
                return self.reply(200, '{"status":"ok"}', extra=[("Set-Cookie", "piso_sa=abc; Path=/; HttpOnly")])
            self.reply(403, '{"status":"error","message":"This code is not accepted by this box."}')

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeBox)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def card_png(k, name):
        path = os.path.join(tmp, name)
        qr.draw_card(qr.payload_for(k), path)
        return path

    def photo_of(card_path, name, size=(3000, 2200), angle=7, blur=1.2):
        """What a phone camera would give: the printed card on a table, rotated a little, a bit soft."""
        card = Image.open(card_path).convert("RGB")
        card = card.resize((card.width * 3 // 2, card.height * 3 // 2))
        table = Image.new("RGB", size, (96, 78, 60))
        d = ImageDraw.Draw(table)
        for _ in range(400):
            x, y = random.randrange(size[0]), random.randrange(size[1])
            d.ellipse([x, y, x + random.randrange(3, 40), y + random.randrange(3, 40)], fill=(random.randrange(60, 130), 80, 55))
        rotated = card.rotate(angle, expand=True, fillcolor=(96, 78, 60))
        table.paste(rotated, ((size[0] - rotated.width) // 2, (size[1] - rotated.height) // 2))
        path = os.path.join(tmp, name)
        table.filter(ImageFilter.GaussianBlur(blur)).save(path, "JPEG", quality=80)
        return path

    chrome = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome")), None)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        page = browser.new_page(viewport={"width": 390, "height": 800})
        problems = []
        page.on("pageerror", lambda e: problems.append(str(e)))

        def attempt(file, next_url="/dashboard?x=1&y=2"):
            page.goto(f"{base}/login?next={urllib.parse.quote(next_url)}")
            page.wait_for_selector("#qr", state="attached")
            page.set_input_files("#qr", file)

        # a clean picture of the code
        attempt(card_png(key, "good.png"))
        page.wait_for_selector("#dash", timeout=15000)
        check(page.url == f"{base}/dashboard?x=1&y=2", f"a good code signs in and leads on to the page that was asked for: {page.url}")
        check(state["logins"][-1] is True, "the box accepted the signature")
        # the same, photographed on a table with a slight turn
        attempt(photo_of(card_png(key, "good2.png"), "photo.jpg"))
        page.wait_for_selector("#dash", timeout=20000)
        check(state["logins"][-1] is True, "a rotated, soft photo of the code works too")
        # a code of another key: the box says no and the page says so
        wrong = card_png(ec.generate_private_key(ec.SECP256R1()), "wrong.png")
        attempt(wrong, "/")
        for _ in range(100):
            if "not accepted" in page.inner_text("#msg"):
                break
            page.wait_for_timeout(100)
        check("not accepted" in page.inner_text("#msg") and state["logins"][-1] is False and "/login" in page.url, "another key's code is refused and the page stays: " + page.inner_text("#msg"))
        # a picture without a QR code
        blank = os.path.join(tmp, "blank.png")
        Image.new("RGB", (800, 600), "white").save(blank)
        attempt(blank)
        for _ in range(100):
            if "No QR code" in page.inner_text("#msg"):
                break
            page.wait_for_timeout(100)
        check("No QR code" in page.inner_text("#msg"), "a picture without a code says so: " + page.inner_text("#msg"))
        # a QR code that is not an admin code (a time card)
        import qrcode
        other = os.path.join(tmp, "other.png")
        qrcode.make("https://example.com/something").save(other)
        attempt(other)
        for _ in range(100):
            if "not a PisoPhone" in page.inner_text("#msg"):
                break
            page.wait_for_timeout(100)
        check("not a PisoPhone" in page.inner_text("#msg"), "another kind of QR code says so: " + page.inner_text("#msg"))
        # a next address that leaves the box is ignored
        attempt(card_png(key, "good3.png"), "//evil.example/x")
        for _ in range(150):
            if state["logins"][-1] is True and "/login" not in page.url:
                break
            page.wait_for_timeout(100)
        check(page.url == base + "/", "a next address on another site is never followed (it goes to the box's own front page): " + page.url)
        check(not problems, f"no script error: {problems}")
        browser.close()
    server.shutdown()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
