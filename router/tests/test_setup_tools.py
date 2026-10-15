#!/usr/bin/env python3
"""Tests of the owner tools inside setup/pisophone_setup.py (they replaced the owner console): QR time cards, the admin QR code, the phone
recovery code and the key files. The codes must be exactly what the boxes and the app check, so they are verified here with the
repository's own format code (scripts/card_format.py, make_admin_qr.py, make_recovery_code.py) and, when installed, a QR reader.
Run with:  python3 router/tests/test_setup_tools.py"""
import csv, http.client, importlib.util, json, os, re, stat, sys, tempfile, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, f"{ROOT}/scripts")
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)
import card_format, make_admin_qr, make_recovery_code  # noqa: E402

tmp = tempfile.mkdtemp()
os.environ["PISOPHONE_HOME"] = os.path.join(tmp, "home")
os.environ["PISOPHONE_KEYS"] = os.path.join(tmp, "keys")
os.makedirs(os.environ["PISOPHONE_KEYS"])
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def problem(fn, *a, **kw):
    try:
        fn(*a, **kw)
    except ps.ToolError as e:
        return str(e)
    return None


try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec, rsa
except ImportError:
    print("skipped: the cryptography package is not installed")
    sys.exit(0)

# ---- keys ------------------------------------------------------------------------------------------------------------------
check("Import your key" in (problem(ps.load_key, "card") or ""), "no card key yet: the page is told to import it")
card_key = ec.generate_private_key(ec.SECP256R1())
pem = card_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
ps.import_key("card", text=pem.decode())
path = ps.key_path("card")
check(os.path.isfile(path) and (os.name == "nt" or stat.S_IMODE(os.stat(path).st_mode) == 0o600), "the imported key is saved, readable by the owner only")
check(ps.key_fingerprint(ps.load_key("card")) == ps.key_fingerprint(card_key), "and it is the same key")
check("already is a key" in (problem(ps.import_key, "card", text=pem.decode()) or ""), "an existing key is not overwritten by accident")
ps.import_key("card", text=pem.decode(), replace=True)
check(os.path.isfile(path + ".old"), "replacing keeps the old key as .old")
rsa_pem = rsa.generate_private_key(65537, 2048).private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
check("P-256" in (problem(ps.import_key, "admin", text=rsa_pem.decode()) or ""), "a key of another kind is refused")
check("not a usable key" in (problem(ps.import_key, "admin", text="hello") or ""), "so is text that is no key")
check("No file" in (problem(ps.import_key, "admin", path=os.path.join(tmp, "nope.pem")) or ""), "and a path with no file")
kfile = os.path.join(tmp, "my.key")
open(kfile, "wb").write(pem)
ps.import_key("owner", path=kfile)
check(os.path.isfile(ps.key_path("owner")), "a key can be imported from a file on this computer")
made = ps.create_key("admin")
check(ps.key_fingerprint(ps.load_key("admin")) == ps.key_fingerprint(made), "a new key can be made when none exists")

# ---- time cards: the same codes the box checks ------------------------------------------------------------------------------
card_key = ps.load_key("card")
r = ps.make_cards("AA:BB:CC:DD:EE:FF", 14, 3)
check((r["first"], r["last"], r["sheets"]) == (1, 14, 2) and len(r["cards"]) == 14, f"14 cards are two sheets, numbers 1 to 14: {r['first']}-{r['last']}")
check(all(card_format.verify(card_key.public_key(), text) for _s, text in r["cards"]), "every card verifies with the repository's own card format code")
box, serial, seconds, _sig = card_format.parse(r["cards"][3][1])
check((box, serial, seconds) == ("aabbccddeeff", 4, 10800), f"a card names its box, number and seconds: {box} {serial} {seconds}")
check(all(os.path.isfile(p) for _l, p in r["files"]) and len(r["files"]) == 3, "three printable pages are written")
fronts = open(r["files"][0][1], encoding="utf-8").read()
backs = open(r["files"][1][1], encoding="utf-8").read()
both = open(r["files"][2][1], encoding="utf-8").read()
check(fronts.count('class="card"') == 14 and fronts.count("<svg") == 14 and fronts.count('class="sheet"') == 2, "the fronts: 14 cards with a QR code each on 2 sheets")
check(backs.count('class="back"') == 14 and both.count('class="sheet"') == 4, "the backs: 14 logo sides; both sides: front, back, front, back")
check("No. 00001" in fronts and "No. 00014" in fronts and "3-hour play card" in fronts, "numbers and hours are printed")
check("<script" not in fronts.lower(), "the printable pages hold no script")
ledger = ps.read_card_ledger(r["ledger"])
check([int(x["serial"]) for x in ledger] == list(range(1, 15)) and all(x["box"] == "aabbccddeeff" and x["seconds"] == "10800" for x in ledger), "the ledger lists every card once")
r2 = ps.make_cards("aa-bb-cc-dd-ee-ff", 5, 1.5, note="market day")
check((r2["first"], r2["last"]) == (15, 19), f"the next batch continues the numbering: {r2['first']}-{r2['last']}")
all_serials = [int(x["serial"]) for x in ps.read_card_ledger(r["ledger"])]
check(len(all_serials) == len(set(all_serials)) == 19, "no card number is ever used twice")
check(ps.read_card_ledger(r["ledger"])[-1]["note"] == "market day" and "1.5-hour" in open(r2["files"][0][1], encoding="utf-8").read(), "note and hours of the batch")
r3 = ps.make_cards("112233445566", 1, 1)
check((r3["first"], r3["last"]) == (1, 1), "another box has its own numbers")
check(ps.known_boxes() == [{"box": "112233445566", "printed": 1, "sold": 0, "last": 1}, {"box": "aabbccddeeff", "printed": 19, "sold": 0, "last": 19}],
      f"the boxes printed for before are known: {ps.known_boxes()}")
for bad, why in ((dict(box="12:34"), "box address"), (dict(box="AA:BB:CC:DD:EE:FF", count=0), "1 to 600"), (dict(box="AA:BB:CC:DD:EE:FF", count=601), "1 to 600"),
                 (dict(box="AA:BB:CC:DD:EE:FF", count="x"), "whole number"), (dict(box="AA:BB:CC:DD:EE:FF", hours=0), "between"),
                 (dict(box="AA:BB:CC:DD:EE:FF", hours=300), "between")):
    kw = dict(box="AA:BB:CC:DD:EE:FF", count=2, hours=1)
    kw.update(bad)
    check(why in (problem(ps.make_cards, **kw) or ""), f"refused: {bad}")
check(len(ps.read_card_ledger(r["ledger"])) == 19, "refused batches reserve no numbers")

# the QR codes on the cards decode to the signed text (when a QR reader is installed)
try:
    import zxingcpp
except ImportError:
    zxingcpp = None
if zxingcpp:
    def raster(m, scale=6, quiet=4):
        n = len(m) + 2 * quiet
        w = n * scale
        data = bytearray([255]) * (w * w)
        for y, row in enumerate(m):
            for x, v in enumerate(row):
                if v:
                    for dy in range(scale):
                        for dx in range(scale):
                            data[((y + quiet) * scale + dy) * w + (x + quiet) * scale + dx] = 0
        return zxingcpp.read_barcodes(__import__("numpy").frombuffer(bytes(data), dtype="uint8").reshape(w, w))
    bad = [s for s, text in r["cards"] if not (lambda out: out and out[0].text == text)(raster(ps.qr_matrix(text)))]
    check(not bad, f"every card's QR code reads back as its signed text (failed: {bad})")
else:
    print("note: zxing-cpp is not installed, the QR read-back check is skipped")

# ---- the admin QR code and the recovery code --------------------------------------------------------------------------------
admin = ps.load_key("admin")
check(ps.admin_qr_text(admin) == make_admin_qr.payload_for(admin), "the admin QR text is what the box's page expects")
back = make_admin_qr.key_from_payload(ps.admin_qr_text(admin))
check(ps.key_fingerprint(back) == ps.key_fingerprint(admin), "and it carries the same key")
page = ps.make_admin_qr()
check(page["svg"].startswith("<svg") and os.path.isfile(page["path"]) and "Scan admin QR" in open(page["path"], encoding="utf-8").read(), "the admin QR page is made")
check(os.name == "nt" or stat.S_IMODE(os.stat(page["path"]).st_mode) == 0o600, "and it is private")
owner = ps.load_key("owner")
now = int(time.time())
text = ps.recovery_text(owner, now + 600)
prefix, expiry, sig = text.split(".")
check(prefix == "PISOREC1" and int(expiry) == now + 600, "the recovery code has the app's shape")
import base64
owner.public_key().verify(base64.urlsafe_b64decode(sig + "=" * (-len(sig) % 4)), make_recovery_code.message(int(expiry)), ec.ECDSA(__import__("cryptography.hazmat.primitives.hashes", fromlist=["SHA256"]).SHA256()))
check(True, "its signature verifies over the message the app checks")
rec = ps.make_recovery_qr(30)
check(rec["minutes"] == 30 and "SCAN A RECOVERY CODE" in open(rec["path"], encoding="utf-8").read(), "the recovery page says how to use it")
check("1 to 1440" in (problem(ps.make_recovery_qr, 0) or "") and "1 to 1440" in (problem(ps.make_recovery_qr, 5000) or ""), "its time is limited")

# ---- through the page's server ----------------------------------------------------------------------------------------------
class FakeCore:
    def preflight(self):
        return []


system = {"kind": "linux", "family": "debian", "name": "Test", "arch": "x86_64", "root": False, "sudo": False}
session = ps.Session(FakeCore(), "main", os.path.join(tmp, "out"), system, install_deps=lambda emit: emit("x"))
server = ps.make_server(session, "123456", "127.0.0.1", 0)
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_address[1]
cookie = ""


def call(method, path, body=None):
    global cookie
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"X-Piso": "1", "Content-Type": "application/json"}
    if cookie:
        h["Cookie"] = cookie
    conn.request(method, path, json.dumps(body) if body is not None else None, h)
    resp = conn.getresponse()
    data = resp.read()
    if resp.getheader("Set-Cookie"):
        cookie = resp.getheader("Set-Cookie").split(";")[0]
    try:
        return resp.status, json.loads(data)
    except ValueError:
        return resp.status, data


check(call("GET", "/api/toolfile?i=0")[0] == 401, "tool pages need the PIN")
call("POST", "/api/login", {"pin": "123456"})
status, state = call("POST", "/api/do", {"action": "tool_cards", "box": "AA:BB:CC:DD:EE:FF", "count": 3, "hours": 2, "flip": "short"})
t = state["tools"]
check(status == 200 and t["batch"] and (t["batch"]["first"], t["batch"]["last"]) == (20, 22), f"cards through the page: {t['error']} {t['batch'] and t['batch']['first']}")
names = [l for l, _i in t["batch"]["files"]]
check("Cards: QR sides" in names and "Ledger (which card is which)" in names, f"the page is given the files: {names}")
st, body = call("GET", "/api/toolfile?i=" + str(t["batch"]["files"][0][1]))
check(st == 200 and b'class="card"' in body, "a printable page can be opened")
st, body = call("GET", "/api/toolfile?i=" + str(t["batch"]["files"][-1][1]))
check(st == 200 and body.startswith(b"serial,seconds,box"), "so can the ledger")
check(call("GET", "/api/toolfile?i=999")[0] == 404 and call("GET", "/api/toolfile?i=-1")[0] == 404 and call("GET", "/api/toolfile?i=../../etc/passwd")[0] == 404, "nothing else can be fetched")
state = call("POST", "/api/do", {"action": "tool_cards", "box": "nonsense", "count": 3, "hours": 2})[1]
check(state["tools"]["error"] and "box address" in state["tools"]["error"], "a mistake is shown on the page, not as a crash")
state = call("POST", "/api/do", {"action": "tool_adminqr"})[1]
check(state["tools"]["qr"]["kind"] == "admin" and state["tools"]["qr"]["svg"].startswith("<svg"), "the admin QR through the page")
state = call("POST", "/api/do", {"action": "tool_hide"})[1]
check(state["tools"]["qr"] is None, "and it can be hidden again")
state = call("POST", "/api/do", {"action": "tool_recovery", "minutes": 15})[1]
check(state["tools"]["qr"]["kind"] == "recovery" and "15 minutes" in state["tools"]["qr"]["note"], "the recovery code through the page")
state = call("POST", "/api/do", {"action": "tool_key", "kind": "card", "mode": "import", "text": "junk"})[1]
check(state["tools"]["error"] and "not a usable key" in state["tools"]["error"], "a bad key is explained")
check(call("POST", "/api/do", {"action": "tool_key", "kind": "../x", "mode": "import"})[0] == 400, "an unknown key kind is refused")
check({k["kind"] for k in state["tools"]["keys"]} == {"card", "admin", "owner"} and all(k["found"] for k in state["tools"]["keys"]), "the page lists the three keys")
# ---- the page in a real browser ---------------------------------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None
if sync_playwright is None:
    print("note: playwright is not installed, the browser part is skipped")
else:
    import glob, shutil
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 390, "height": 800})
        problems = []
        page.on("pageerror", lambda e: problems.append(str(e)))
        page.goto(f"http://127.0.0.1:{port}/")
        page.fill("#pin", "123456")
        page.click("#pinBtn")
        page.wait_for_selector("#btnTools", state="visible")
        page.click("#btnTools")
        page.wait_for_selector("#toolsMain", state="visible")
        check(page.is_hidden("#nav") and page.is_hidden("footer") and page.inner_text("#btnTools") == "Back to setup", "Owner tools replaces the setup steps")
        check("Found" in page.inner_text("#kList"), "the keys are listed")
        page.fill("#cBox", "AA:BB:CC:DD:EE:FF")
        page.fill("#cCount", "6")
        page.fill("#cHours", "2")
        page.click("#btnCards")
        for _ in range(80):   # (the page's policy forbids wait_for_function, so poll)
            if "Made 6 cards" in page.inner_text("#cResult"):
                break
            time.sleep(0.1)
        text = page.inner_text("#cResult")
        check("Made 6 cards" in text and "2-hour" in text, f"the cards are made: {text[:80]}")
        with page.context.expect_page() as popup:
            page.click("#cResult a")
        card_page = popup.value
        card_page.wait_for_load_state()
        check(card_page.locator(".card").count() == 6 and card_page.locator(".card svg").count() == 6, "the card page opens with six QR cards")
        page.click("#btnAdminQr")
        for _ in range(80):
            if "opens super admin" in page.inner_text("#qrResult"):
                break
            time.sleep(0.1)
        check("opens super admin" in page.inner_text("#qrResult"), "the admin QR code is shown")
        page.click("#btnTools")
        page.wait_for_selector("#nav", state="visible")
        check(page.is_visible("footer"), "Back to setup brings the steps back")
        check(not page.evaluate("document.documentElement.scrollWidth > window.innerWidth"), "no sideways scrolling on a phone screen")
        check(not problems, f"no script error: {problems}")
        browser.close()
server.shutdown()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
