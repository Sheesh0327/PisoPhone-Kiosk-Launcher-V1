"""The owner console: every tool has a page, only the printed secret opens it, forms become the right command lines, and a script runs."""
import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import owner_console as oc  # noqa: E402

fails = 0
n = 0


def check(ok, what):
    global fails, n
    n += 1
    if not ok:
        fails += 1
        print("FAIL:", what)


def call(port, path, method="GET", cookie=None, body=None, headers=None, host=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method, data=body)
    if cookie:
        req.add_header("Cookie", cookie)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if host:
        req.add_header("Host", host)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


urllib.request.install_opener(urllib.request.build_opener(NoRedirect))
server, console = oc.make_server(0)
port = server.server_address[1]
threading.Thread(target=server.serve_forever, daemon=True).start()
cookie = f"piso_console={console.token}"

check(server.server_address[0] == "127.0.0.1", "the console listens on this computer only")
check(call(port, "/")[0] == 403, "no secret, no page")
check(call(port, "/enter?t=wrong")[0] == 403, "a wrong secret is refused")
code, _b, h = call(port, f"/enter?t={console.token}")
check(code == 303 and "piso_console=" in (h.get("Set-Cookie") or "") and "HttpOnly" in h.get("Set-Cookie", ""), "the right secret becomes an HttpOnly cookie")
check(call(port, "/", cookie=cookie, host="evil.example")[0] == 403, "an unknown Host name is refused (DNS rebinding)")
code, body, h = call(port, "/", cookie=cookie)
check(code == 200 and all(s["title"].encode() in body for s in oc.SCRIPTS), "the front page lists every tool")
check("default-src 'none'" in h.get("Content-Security-Policy", ""), "a strict content security policy")
for s in oc.SCRIPTS:
    code, body, _h = call(port, f"/p/{s['name']}", cookie=cookie)
    check(code == 200 and s["title"].encode() in body and b"<form" in body, f"{s['name']} has its own page with a form")
check(call(port, "/p/nope", cookie=cookie)[0] == 404, "an unknown tool is 404")
check(call(port, "/run/sold_unit_flash", "POST", cookie=cookie, body=b"{}")[0] == 403, "a run without the page's header is refused (CSRF)")
check(call(port, "/run/sold_unit_flash", "POST", body=b"{}", headers={"X-Piso": "1"})[0] == 403, "a run without the cookie is refused")

# every script a page names exists
check(all(os.path.isfile(os.path.join(oc.HERE, s["file"])) for s in oc.SCRIPTS), "every page runs a script that exists")
covered = {s["file"] for s in oc.SCRIPTS}
owner_tools = {"make_admin_qr.py", "make_card_key.py", "make_cards.py", "make_owner_keys.py", "make_recovery_code.py", "sign_firmware.py",
               "sign_router.py", "publish_firmware_ota.py", "sold_unit_check.py", "verify_release.py", "check_published_apk.py"}
check(owner_tools <= covered, "every owner script has a page: " + str(owner_tools - covered))

# command lines
cmd = oc.build_command(oc.BY_NAME["make_cards"], {"box": "AA:BB:CC:DD:EE:FF", "count": "12", "hours": "", "flip": "short", "shift_x": "-0.5"})
check(cmd[2:] == ["--box", "AA:BB:CC:DD:EE:FF", "--count", "12", "--flip", "short", "--back-shift-x-mm", "-0.5"], "a form becomes the right command line: " + str(cmd))
cmd = oc.build_command(oc.BY_NAME["make_admin_qr"], {"private": "", "new": True})
check(cmd[2:] == ["--new"], "a checkbox becomes its flag: " + str(cmd))
for bad, why in ((("make_cards", {"box": "--out", "count": "1"}), "a value cannot pose as an option"),
                 (("make_cards", {"count": "1"}), "a required field must be filled"),
                 (("make_cards", {"box": "AA", "count": "many"}), "a number must be a number"),
                 (("make_cards", {"box": "AA", "count": "1", "flip": "diagonal"}), "a choice must be one of the choices"),
                 (("sign_firmware", {"private": "k\nx", "image": "i", "chip": "esp32"}), "no line breaks in a value")):
    try:
        oc.build_command(oc.BY_NAME[bad[0]], bad[1])
        check(False, why)
    except ValueError:
        check(True, why)
cmd = oc.build_command(oc.BY_NAME["make_cards"], {"box": "AA", "count": "1", "evil": "--x", "key": "~/k.pem"})
check("--x" not in cmd and cmd[cmd.index("--key") + 1] == os.path.expanduser("~/k.pem"), "only the page's own fields are used, and ~ is expanded")

# a real run: a flash dump check (needs nothing installed)
with tempfile.TemporaryDirectory() as d:
    dump = os.path.join(d, "dump.bin")
    with open(dump, "wb") as f:
        f.write(os.urandom(4096))
    code, body, _h = call(port, "/run/sold_unit_flash", "POST", cookie=cookie, headers={"X-Piso": "1", "Content-Type": "application/json"},
                          body=json.dumps({"dump": dump, "find": "PisoPhone"}).encode())
    j = json.loads(body)
    check(code == 200 and "rc" in j and "output" in j and j["output"], "a tool runs and its output comes back: " + str(j)[:200])
    code, body, _h = call(port, "/run/sold_unit_flash", "POST", cookie=cookie, headers={"X-Piso": "1"}, body=b'{"dump": ""}')
    check("please fill this in" in json.loads(body).get("error", ""), "a missing required field is explained")

# files: only what a run offered can be downloaded
with tempfile.TemporaryDirectory() as d:
    secret = os.path.join(d, "secret.txt")
    open(secret, "w").write("private")
    check(call(port, "/file/../../etc/passwd", cookie=cookie)[0] == 404 and call(port, "/file/abc", cookie=cookie)[0] == 404, "an arbitrary path cannot be downloaded")
    fid = console.offer(secret)
    check(call(port, f"/file/{fid}", cookie=cookie)[0] == 200 and call(port, f"/file/{fid}")[0] == 403, "an offered file downloads, with the cookie only")
server.shutdown()
print(f"{n} checks, {fails} failures")
sys.exit(1 if fails else 0)
