#!/usr/bin/env python3
"""The console's Branding screen (feature 049, group 6): who may use it, that a draft never reaches customers, Publish and Restore, the preview
that cannot start a coin window, and a brand pack installed from the console. HTTP checks first, then the screen in a real browser.
The router is the real program built with the cargo feature test-owner-key, so a pack signed with a throwaway key installs; the program that
ships never trusts that key. Needs `pip install cryptography` and cargo; PISO_TEST_TARGET_DIR keeps the test build between runs.

CHROME=/path/to/chromium python3 test_console_brand.py"""
import base64
import glob
import hashlib
import http.client
import json
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    print("skipped: the cryptography package is not installed")
    sys.exit(0)
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
TOOL = os.path.join(ROOT, "scripts", "make_brand_pack.py")
tmp = tempfile.mkdtemp(prefix="console-brand-test-")
target = os.environ.get("PISO_TEST_TARGET_DIR") or os.path.join(tmp, "target")
built = subprocess.run(["cargo", "build", "--release", "--locked", "--features", "test-owner-key", "--target-dir", target],
                       cwd=os.path.join(ROOT, "tools", "pisoportal"), capture_output=True, text=True, check=False)
if built.returncode != 0:
    print(built.stdout[-800:] + built.stderr[-1500:])
    print("FAIL: the test build of the router program failed")
    sys.exit(1)
os.environ["PISOPORTAL"] = os.path.join(target, "release", "pisoportal")
from harness import Env, check, finish

GOLDEN = os.path.join(HERE, "golden", "page_states.json")
CP = 18700
FORM = {"name": "Aling Nena's Net Cafe", "palette": "dagat", "accent": "#ff7a1a", "layout": "banner", "tagline_en": "Fast. Cheap. Clean.", "tagline_fil": "Mabilis. Mura. Malinis.",
        "welcome_en": "Welcome!", "welcome_fil": "", "contact_en": "0917 123 4567", "contact_fil": "", "rules_en": "No illegal downloads.\nBe kind.", "rules_fil": "",
        "hours_en": "Open 8am to 10pm", "hours_fil": "", "promo_en": "", "promo_fil": ""}


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def png(w, h):
    raw = b"".join(b"\x00" + b"\x20\x60\xa0" * w for _ in range(h))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def new_key(name):
    key = ec.generate_private_key(ec.SECP256R1())
    pem = os.path.join(tmp, name + ".pem")
    with open(pem, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    pub = os.path.join(tmp, name + ".b64")
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    with open(pub, "w") as f:
        f.write(base64.b64encode(der).decode() + "\n")
    return pem, pub


def make_pack(pem, pub, out):
    logo = os.path.join(tmp, out + ".png")
    with open(logo, "wb") as f:
        f.write(png(96, 64))
    dest = os.path.join(tmp, out)
    r = subprocess.run([sys.executable, TOOL, "--private", pem, "--router-key", pub, "--logo", logo, "--out", dest, "--force"], capture_output=True, text=True, check=False)
    assert r.returncode == 0, r.stdout + r.stderr
    return dest


owner_pem, owner_pub = new_key("owner")
stranger_pem, stranger_pub = new_key("stranger")
os.environ["PISOPORTAL_TEST_OWNER_KEY"] = owner_pub
good_pack = pathlib.Path(make_pack(owner_pem, owner_pub, "good.brand")).read_bytes()
forged_pack = pathlib.Path(make_pack(stranger_pem, stranger_pub, "forged.brand")).read_bytes()

env = Env(offset=195)
pathlib.Path(f"{env.tmp}/ubus").write_text('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{env.tmp}/ubus", 0o755)
pathlib.Path(f"{env.tmp}/setup.conf").write_text("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{env.tmp}/ubus", PISO_SETUP_CONF=f"{env.tmp}/setup.conf")


def call(method, path, body=None, cookie=None, x_piso=True, origin=None, port=None, raw=False):
    h = {}
    if cookie:
        h["Cookie"] = cookie
    if x_piso:
        h["X-Piso"] = "1"
    if origin:
        h["Origin"] = origin
    data = None
    if body is not None:
        data = body if raw else json.dumps(body)
        h["Content-Type"] = "text/plain" if raw else "application/json"
    c = http.client.HTTPConnection("127.0.0.1", port or CP, timeout=10)
    c.request(method, path, data if raw else (data.encode() if data else None), h)
    r = c.getresponse()
    b = r.read()
    out = (r.status, dict(r.getheaders()), b)
    c.close()
    return out


def jcall(*a, **k):
    s, _, b = call(*a, **k)
    return s, (json.loads(b) if b[:1] in b"{[" else b)


try:
    check(env.start(), "the portal starts")
    env.nds_client("aa:bb:cc:00:00:01")
    base = "/?fas=ABC"
    with open(os.path.join(HERE, "golden", "page_states.json")) as f:
        today = json.load(f)["page_sha256"]

    def guest():
        return call("GET", base, port=env.PP, x_piso=False)[2]

    def guest_is_todays():
        return hashlib.sha256(guest()).hexdigest() == today

    # ---- nobody signed in gets anything --------------------------------------------------------------------------------------------------
    for m, p in [("GET", "/api/brand"), ("POST", "/api/brand"), ("POST", "/api/brand/publish"), ("POST", "/api/brand/restore"), ("POST", "/api/brand/pack"),
                 ("GET", "/brand/preview"), ("GET", "/brand/logo"), ("GET", "/brand/picture")]:
        s = call(m, p, {} if m == "POST" else None)[0]
        check(s == 401, f"{m} {p} without signing in: {s}")
    s, h, b = call("POST", "/api/login", {"password": "router-pass"})
    cookie = h["Set-Cookie"].split(";")[0]
    check(s == 200 and cookie.startswith("piso_console="), "signed in")

    # ---- the same rules as the rest of the console ---------------------------------------------------------------------------------------
    for p in ["/api/brand", "/api/brand/publish", "/api/brand/restore", "/api/brand/pack"]:
        check(call("POST", p, FORM, cookie=cookie, x_piso=False)[0] == 403, f"POST {p} needs the X-Piso header")
        check(call("POST", p, FORM, cookie=cookie, origin="http://evil.example")[0] == 403, f"POST {p} refuses a foreign origin")
    check(not os.path.exists(f"{env.data}/brand/draft.txt"), "and the refused ones saved nothing")

    # ---- the editor, the draft, and customers ---------------------------------------------------------------------------------------------
    s, j = jcall("GET", "/api/brand", cookie=cookie)
    check(s == 200 and j["live"] == "default" and j["dirty"] is False and len(j["palettes"]) == 6 and j["layouts"] == ["classic", "banner", "compact", "poster"]
          and j["limits"]["rules"] == 600 and j["logo"] is False, f"a fresh router: {str(j)[:200]}")
    check(jcall("POST", "/api/brand/publish", {}, cookie=cookie)[0] == 400, "nothing to publish yet")
    s, j = jcall("POST", "/api/brand", FORM, cookie=cookie)
    check(s == 200 and j == {"ok": True, "notes": []}, f"the form is saved as a draft: {j}")
    s, j = jcall("GET", "/api/brand", cookie=cookie)
    check(j["draft"]["name"] == FORM["name"] and j["draft"]["rules_en"] == FORM["rules_en"] and j["dirty"] is True and j["live"] == "default", "the draft comes back whole, not live")
    check(guest_is_todays(), "the draft has not reached customers")
    check(call("GET", "/brand.json", port=env.PP, x_piso=False)[2] == b'{"v":1,"branded":false}', "nor their data feed")
    s, j = jcall("POST", "/api/brand", dict(FORM, name="N" * 40, accent="#050d22", palette="technet"), cookie=cookie)
    check(j["ok"] and any("shortened" in n for n in j["notes"]) and any("accent" in n for n in j["notes"]), f"what had to change is said: {j}")
    s, j = jcall("POST", "/api/brand", {"layout": "poster"}, cookie=cookie)
    check(s == 400 and j["error"] == "BAD_FORM", "a body that is not the form is refused")
    big = {k: ("\"" * 600 if k.startswith("rules") else "\"" * 60) for k in FORM if k not in ("palette", "accent", "layout")}
    s, j = jcall("POST", "/api/brand", dict(FORM, **big), cookie=cookie)
    check(s == 200 and j["ok"], f"a form with every text at its limit, all quotes, fits: {s}")
    jcall("POST", "/api/brand", FORM, cookie=cookie)

    # ---- the preview ------------------------------------------------------------------------------------------------------------------------
    s, h, b = call("GET", "/brand/preview", cookie=cookie)
    page = b.decode()
    csp = h.get("Content-Security-Policy", "")
    check(s == 200 and "Aling Nena" in page and 'class="l-banner"' in page, "the preview is the draft on the customer page")
    check("connect-src 'none'" in csp and "frame-ancestors 'self'" in csp and "default-src 'none'" in csp, f"it may not connect anywhere: {csp}")
    check("connect();setInterval(beat" not in page and "function connect(){return;" in page, "and its own script never connects")
    check(guest_is_todays(), "viewing the preview changes nothing for customers")
    check("frame-src" in call("GET", "/", x_piso=False)[1].get("Content-Security-Policy", "") and "'self'" in call("GET", "/", x_piso=False)[1].get("Content-Security-Policy", ""), "the console page may frame its own preview")

    # ---- Publish and Restore ----------------------------------------------------------------------------------------------------------------
    check(jcall("POST", "/api/brand/publish", {}, cookie=cookie)[1] == {"ok": True}, "publish")
    g = guest().decode()
    check("Aling Nena" in g and 'class="l-banner"' in g, "customers get the branded page at once")
    s, j = jcall("GET", "/api/brand", cookie=cookie)
    check(j["live"] == "branded" and j["dirty"] is False, "the editor says it is live and in step")
    jcall("POST", "/api/brand", dict(FORM, name="Second name"), cookie=cookie)
    check("Aling Nena" in guest().decode() and "Second name" not in guest().decode(), "editing again leaves the live page alone")
    check(json.loads(call("GET", "/api/brand", cookie=cookie)[2])["dirty"] is True, "and the editor says there is an unpublished change")
    check(jcall("POST", "/api/brand/restore", {}, cookie=cookie)[1] == {"ok": True} and guest_is_todays(), "restoring brings back today's page, byte for byte")
    j = json.loads(call("GET", "/api/brand", cookie=cookie)[2])
    check(j["live"] == "default" and j["draft"]["name"] == "Second name", "and the draft is kept")

    # ---- a brand pack from the console ---------------------------------------------------------------------------------------------------
    s, j = jcall("POST", "/api/brand/pack", b"not a pack at all", cookie=cookie, raw=True)
    check(s == 400 and j["error"] == "BAD_PACK" and j["why"], f"garbage is refused with a reason: {j}")
    s, j = jcall("POST", "/api/brand/pack", forged_pack, cookie=cookie, raw=True)
    check(s == 400 and "signature" in j["why"], f"a pack signed by someone else is refused: {j}")
    s, j = jcall("POST", "/api/brand/pack", good_pack[:-5], cookie=cookie, raw=True)
    check(s == 400, "a pack with its end cut off is refused")
    check(not os.path.exists(f"{env.data}/brand/images.txt"), "and the refused ones installed nothing")
    check(call("GET", "/brand/logo", cookie=cookie)[0] == 404, "no logo yet")
    s, j = jcall("POST", "/api/brand/pack", good_pack, cookie=cookie, raw=True)
    check(s == 200 and j["ok"] and j["logo_bytes"] > 50, f"the owner's pack installs: {j}")
    s, h, b = call("GET", "/brand/logo", cookie=cookie)
    check(s == 200 and h["Content-Type"] == "image/png" and b[:4] == b"\x89PNG" and h.get("X-Content-Type-Options") == "nosniff", "the console shows the installed logo")
    check(call("GET", "/brand/logo")[0] == 401, "but not to someone who is not signed in")
    check(call("GET", "/brand/logo", port=env.PP, x_piso=False)[0] == 404, "customers do not get it while the branding is not published")
    check(json.loads(call("GET", "/api/brand", cookie=cookie)[2])["logo"] is True, "the editor knows the logo is installed")
    s, h, b = call("GET", "/brand/preview", cookie=cookie)
    check(b'src="/brand/logo"' in b, "and the preview shows it")

    # ---- the screen ---------------------------------------------------------------------------------------------------------------------------
    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        pg = br.new_page(viewport={"width": 1280, "height": 1000})
        errors, sockets, starts = [], [], []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("websocket", lambda w: sockets.append(w.url))
        pg.goto(f"http://127.0.0.1:{CP}/")
        pg.wait_for_selector("#login:not(.hide)", timeout=8000)
        pg.fill("#pw", "router-pass")
        pg.click("#gobtn")
        pg.wait_for_selector("#app:not(.hide)", timeout=8000)
        pg.click('#tabs button[data-t="brand"]')
        pg.wait_for_selector("#bpal button", timeout=8000)
        check(pg.input_value("#bn") == "Second name" and pg.locator("#bpal button").count() == 6 and pg.locator("#blay input").count() == 4, "the screen shows the draft, six palettes and four layouts")
        check(pg.locator("#bchips").text_content().startswith("Live: the standard TechNet look") and "not published" in pg.locator("#bchips").text_content(), "and says what is live and that there are unpublished changes")
        check(pg.is_disabled("#brest") and pg.is_enabled("#bpub"), "Restore is off when nothing branded is live; Publish is on")
        check(pg.locator("#bpics img").count() == 1 and pg.eval_on_selector("#bpics img", "i => i.naturalWidth") == 96, "the installed logo is shown")

        # typing: saved as a draft, the preview follows, customers do not
        pg.fill("#bn", "Mang Jun Computer Shop")
        pg.locator("#bpal button[data-p=ginto]").click()
        pg.locator("#blay input[value=poster]").check()
        pg.click('#blang button[data-l=fil]')
        pg.fill("#t-promo", "Mura ngayon!")
        pg.click('#blang button[data-l=en]')
        pg.fill("#t-promo", "Cheap today!")
        pg.wait_for_function("document.getElementById('bmsg').textContent.indexOf('Saved') === 0", timeout=5000)
        pg.wait_for_timeout(5000)  # (the screen refreshes itself every 4 seconds: what was typed must survive it)
        check(pg.input_value("#bn") == "Mang Jun Computer Shop" and pg.input_value("#t-promo") == "Cheap today!", "what is typed survives the refresh")
        check(pg.text_content("#c-name") == "22 of 32 characters", "a counter shows the limit: " + pg.text_content("#c-name"))
        pg.click('#blang button[data-l=fil]')
        check(pg.input_value("#t-promo") == "Mura ngayon!", "the Filipino text is its own")
        frame = pg.frame_locator("#bprev")
        pg.wait_for_function("(() => { const d = document.getElementById('bprev').contentDocument; return d && d.title === 'Mang Jun Computer Shop' && d.body.className === 'l-poster'; })()", timeout=8000)
        check(frame.locator("header.poster b").text_content() == "Mang Jun Computer Shop", "the preview shows the new name and layout")
        check(frame.locator(".promo").text_content() == "Cheap today!", "and the English promo")
        frame.locator(".lang").click()
        check(frame.locator(".promo").text_content() == "Mura ngayon!", "its language button shows the Filipino one")
        check(guest_is_todays(), "meanwhile customers still get today's page")

        # the preview cannot start a coin window
        frame.locator("#go").evaluate("e => e.click()")
        pg.wait_for_timeout(500)
        check(not sockets, f"the preview opened no connection: {sockets}")
        check(pg.evaluate("document.getElementById('bprev').contentDocument.getElementById('go').offsetWidth > 0"), "the coin button is on the preview, to look at, and pressing it does nothing")
        check(pg.evaluate("document.getElementById('bprev').contentDocument.getElementById('v-live').offsetWidth === 0"), "the preview did not go to a coin window screen")

        # a bad pack from the screen
        bad = os.path.join(tmp, "bad.brand")
        with open(bad, "wb") as f:
            f.write(forged_pack)
        pg.set_input_files("#bpk", bad)
        pg.wait_for_function("document.getElementById('bpkm').textContent.indexOf('Not installed') === 0", timeout=5000)
        check("signature" in pg.text_content("#bpkm"), "a forged pack is refused on the screen with its reason: " + pg.text_content("#bpkm"))
        huge = os.path.join(tmp, "huge.brand")
        with open(huge, "wb") as f:
            f.write(b"x" * 310000)
        pg.set_input_files("#bpk", huge)
        pg.wait_for_function("document.getElementById('bpkm').textContent.indexOf('too big') > 0", timeout=5000)
        good = os.path.join(tmp, "good2.brand")
        with open(good, "wb") as f:
            f.write(good_pack)
        pg.set_input_files("#bpk", good)
        pg.wait_for_function("document.getElementById('bpkm').textContent.indexOf('Installed') === 0", timeout=5000)

        # Publish asks first, then customers get it
        pg.click("#bpub")
        pg.wait_for_selector("#dlg[open]", timeout=3000)
        check("Publish" in pg.text_content("#dt") and guest_is_todays(), "Publish asks first, and nothing has changed yet")
        pg.click("#dyes")
        pg.wait_for_function("document.getElementById('bmsg').textContent.indexOf('Published') === 0", timeout=5000)
        g = guest().decode()
        check("Mang Jun Computer Shop" in g and 'class="l-poster"' in g and "Cheap today!" in g and 'data-f="Mura ngayon!"' in g, "customers now get the new page, with both languages")
        check("Live: your branding" in pg.text_content("#bchips") and "not published" not in pg.text_content("#bchips"), "the screen says so")
        check(call("GET", "/brand/logo", port=env.PP, x_piso=False)[0] == 200, "and the logo is public now")

        # Restore asks first and keeps the draft
        pg.click("#brest")
        pg.wait_for_selector("#dlg[open]", timeout=3000)
        pg.click("#dyes")
        pg.wait_for_function("document.getElementById('bmsg').textContent.indexOf('The standard page is live') === 0", timeout=5000)
        check(guest_is_todays() and pg.input_value("#bn") == "Mang Jun Computer Shop", "restoring brings back today's page and keeps what was typed")
        check(not errors, f"no script errors on the screen: {errors}")

        # a small phone: the screen still works
        pg.set_viewport_size({"width": 390, "height": 800})
        check(pg.evaluate("document.documentElement.scrollWidth") <= 390, "the screen fits a phone")
        br.close()
finally:
    env.stop()
finish()
