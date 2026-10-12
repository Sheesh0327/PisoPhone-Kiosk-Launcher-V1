#!/usr/bin/env python3
"""The console page loads a newer script from the website only when it is signed by the owner key, current and for this interface (real Chromium).
The portal here is built with the test owner key (the router program never has that feature), so the test can sign."""
import base64, json, os, subprocess, sys, tempfile, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    print("skipped: playwright or cryptography is not installed")
    sys.exit(0)

ROOT = os.path.join(HERE, "..")
tmpd = tempfile.mkdtemp(prefix="console-loader-")
target = os.path.join(tmpd, "target")
b = subprocess.run(["cargo", "build", "--release", "--locked", "--offline", "--features", "test-owner-key"], cwd=ROOT, env=dict(os.environ, CARGO_TARGET_DIR=target), capture_output=True, text=True)
if b.returncode:
    print(b.stderr[-1500:])
    sys.exit(1)
import harness
harness.BIN = os.path.join(target, "release", "pisoportal")

key = ec.generate_private_key(ec.SECP256R1())
open(f"{tmpd}/owner.b64", "w").write(base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode())
CP = 18370
env = Env(offset=70)
t = env.tmp
open(f"{t}/ubus", "w").write("#!/bin/sh\necho '{ \"ubus_rpc_session\": \"abc\" }'\n")
os.chmod(f"{t}/ubus", 0o755)
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{t}/ubus", PISOPORTAL_TEST_OWNER_KEY=f"{tmpd}/owner.b64")


def signed(js, version=2, api=1, expires=None, tamper=None):
    data = js.encode()
    sha = hashlib.sha256(data).hexdigest()
    exp = expires if expires is not None else int(time.time()) + 86400
    sig = key.sign(f"pisophone-console-ui-v1|{version}|{api}|{exp}|{sha}".encode(), ec.ECDSA(hashes.SHA256()))
    return {"version": version, "api": api, "expires": exp, "sha256": sha, "sig": base64.b64encode(sig).decode()}, (tamper or js)


import hashlib
V2 = "window.PISO_UI_VERSION = 2; document.title = 'remote v2';"


def page_with(pw, manifest, js):
    """Opens the console twice (the verified script is kept for the next visit) and returns the title and the version of the second visit."""
    ctx = pw.new_context()
    pg = ctx.new_page()
    pg.route("https://pisophone.pages.dev/console/ui.json", lambda r: r.fulfill(status=200, content_type="application/json", body=json.dumps(manifest), headers={"Access-Control-Allow-Origin": "*"}))
    pg.route("https://pisophone.pages.dev/console/ui.js", lambda r: r.fulfill(status=200, content_type="application/javascript", body=js, headers={"Access-Control-Allow-Origin": "*"}))
    pg.goto(f"http://127.0.0.1:{CP}/")
    pg.wait_for_function("window.PISO_UI_VERSION > 0", timeout=8000)
    pg.wait_for_timeout(1500)
    first = pg.evaluate("window.PISO_UI_VERSION")
    pg.reload()
    pg.wait_for_function("window.PISO_UI_VERSION > 0", timeout=8000)
    pg.wait_for_timeout(500)
    out = (first, pg.evaluate("window.PISO_UI_VERSION"), pg.title())
    ctx.close()
    return out


try:
    check(env.start(), "the portal starts")
    with sync_playwright() as p:
        chrome = os.environ.get("CHROME") or "/opt/pw-browsers/chromium"
        br = p.chromium.launch(executable_path=chrome if os.path.exists(chrome) else None, args=["--no-sandbox"])
        pg0 = br.new_page(); errors = []
        pg0.on("pageerror", lambda e: errors.append(str(e)))
        pg0.goto(f"http://127.0.0.1:{CP}/"); pg0.wait_for_selector("#login", state="visible", timeout=8000)
        check(not errors and pg0.evaluate("window.PISO_UI_VERSION") == 1, f"with no website copy the built-in script runs (version 1) and the sign-in shows: {errors}")
        pg0.close()
        m, js = signed(V2)
        first, second, title = page_with(br, m, js)
        check(first == 1 and second == 2 and title == "remote v2", f"a newer script the owner signed is kept and used on the next visit: {first} {second} {title}")
        m, js = signed(V2, tamper=V2 + " alert(1);")
        first, second, title = page_with(br, m, js)
        check(second == 1 and title != "remote v2", f"a changed script is refused: {first} {second} {title}")
        m, js = signed(V2, expires=int(time.time()) - 10)
        check(page_with(br, m, js)[1] == 1, "an expired signature is refused")
        m, js = signed("window.PISO_UI_VERSION = 1; document.title = 'same';", version=1)
        check(page_with(br, m, js)[1] == 1, "an equal or older version is not used")
        m, js = signed(V2, api=2)
        check(page_with(br, m, js)[1] == 1, "a script for another interface is refused")
        other = ec.generate_private_key(ec.SECP256R1())
        data = V2.encode(); sha = hashlib.sha256(data).hexdigest(); exp = int(time.time()) + 3600
        m = {"version": 2, "api": 1, "expires": exp, "sha256": sha, "sig": base64.b64encode(other.sign(f"pisophone-console-ui-v1|2|1|{exp}|{sha}".encode(), ec.ECDSA(hashes.SHA256()))).decode()}
        check(page_with(br, m, V2)[1] == 1, "a signature from another key is refused")
finally:
    env.close()
finish()
