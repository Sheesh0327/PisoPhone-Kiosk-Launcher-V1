#!/usr/bin/env python3
"""The coin box's firmware updates itself through the router: a web server plays the website (firmware.json and the image), another
plays the box (diagnostics, status, the signed-manifest endpoint and the upload), and the real setup file does the updating.
Run with:  python3 router/tests/test_boxupdate.py   (needs: curl)"""
import hashlib, http.server, json, os, re, shutil, subprocess, sys, tempfile, threading

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = f"{ROOT}/setup/piso-setup.sh"
tmp = tempfile.mkdtemp()
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


# ---- the website ----------------------------------------------------------------------------------------------------------------
IMAGE = os.urandom(300_000)
www = f"{tmp}/www/update"
os.makedirs(www)


def publish(version="3.6.0", image=IMAGE, chips=("esp32c3",), tamper=False):
    data = {"version": version, "build": 1, "changelog": "Faster coin counting", "chips": {}}
    for chip in chips:
        data["chips"][chip] = {"url": f"{site}/update/firmware-{chip}.bin", "sha256": hashlib.sha256(image).hexdigest(), "size": len(image), "sig": "SIGNATURE-" + chip}
        open(f"{www}/firmware-{chip}.bin", "wb").write(image[:-1] + b"X" if tamper else image)
    open(f"{www}/firmware.json", "w").write(json.dumps(data, indent=2))


class Site(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=f"{tmp}/www", **k)

    def log_message(self, *a):
        pass


site_srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Site)
threading.Thread(target=site_srv.serve_forever, daemon=True).start()
site = f"http://127.0.0.1:{site_srv.server_address[1]}"


# ---- the coin box -----------------------------------------------------------------------------------------------------------------
class Box:
    version, chip, image_state = "3.5.2", "esp32c3", "valid"
    phones_active = False
    manifest_status, manifest_msg = 200, ""
    upload_status = 200
    restarts = True            # False: it takes the file but does not come back on the new version
    answers = True
    log = []
    manifests = []
    uploads = []


class BoxHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authed(self):
        import base64
        want = "Basic " + base64.b64encode(b"admin:boxpw").decode()
        if self.headers.get("Authorization") != want:
            self.reply(401, '{"status":"error"}')
            return False
        return True

    def do_GET(self):
        if not Box.answers:
            self.connection.close()
            return
        if not self.authed():
            return
        Box.log.append("GET " + self.path)
        if self.path == "/api/diagnostics":
            self.reply(200, json.dumps({"firmware": Box.version, "chip": Box.chip, "ota_image_state": Box.image_state}))
        elif self.path == "/api/status":
            self.reply(200, json.dumps({"devices": [{"slotNum": 1, "active": Box.phones_active}, {"slotNum": 2, "active": False}]}))
        else:
            self.reply(404, "{}")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        if not self.authed():
            return
        Box.log.append("POST " + self.path)
        if self.path == "/api/ota/manifest":
            from urllib.parse import parse_qs
            q = {k: v[0] for k, v in parse_qs(body.decode()).items()}
            Box.manifests.append(q)
            if Box.manifest_status != 200:
                self.reply(Box.manifest_status, json.dumps({"status": "error", "message": Box.manifest_msg}))
            else:
                self.reply(200, '{"status":"ok"}')
        elif self.path == "/update":
            boundary = re.search(r"boundary=(.+)", self.headers["Content-Type"]).group(1).encode()
            part = [p for p in body.split(b"--" + boundary) if b'name="update"' in p][0]
            file_bytes = part.split(b"\r\n\r\n", 1)[1].rsplit(b"\r\n", 1)[0]
            Box.uploads.append(file_bytes)
            if Box.upload_status != 200:
                self.reply(Box.upload_status, "OTA blocked: unpersisted transactions in RAM", "text/plain")
            else:
                self.reply(200, "SUCCESS", "text/plain")
                if Box.restarts and Box.manifests:
                    Box.version = Box.manifests[-1]["version"]
        else:
            self.reply(404, "{}")


box_srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), BoxHandler)
threading.Thread(target=box_srv.serve_forever, daemon=True).start()
BOX_ADDR = f"127.0.0.1:{box_srv.server_address[1]}"

# ---- a router ----------------------------------------------------------------------------------------------------------------------
bindir = f"{tmp}/bin"
os.makedirs(bindir)
msgs = f"{tmp}/telegram.txt"
open(f"{bindir}/monitor", "w").write(f"#!/bin/sh\n[ \"$1\" = send ] && {{ shift; echo \"$*\" >> {msgs}; }}\nexit 0\n")
open(f"{bindir}/ndsctl", "w").write(f"#!/bin/sh\n[ -e {tmp}/guests ] && printf '\"state\":\"Authenticated\",\\n'\nexit 0\n")
for f in ("monitor", "ndsctl"):
    os.chmod(f"{bindir}/{f}", 0o755)
inst, conf = f"{tmp}/inst/piso-setup", f"{tmp}/conf"
base = open(SCRIPT, "rb").read()
os.makedirs(os.path.dirname(inst))
open(inst, "wb").write(subprocess.run(["sed", "/^# ---- payload: the portal files/,$d"], input=base, capture_output=True).stdout)
os.chmod(inst, 0o755)


def reset(extra_conf="", **box):
    shutil.rmtree(f"{tmp}/t", ignore_errors=True)
    os.makedirs(f"{tmp}/t")
    open(conf, "w").write("GW_KEY='" + "ab" * 32 + f"'\nUPDATE_URL='{site}/update'\nBOX_ADMIN_PASS='boxpw'\n" + extra_conf)
    open(msgs, "w").close()
    if os.path.exists(f"{tmp}/guests"):
        os.remove(f"{tmp}/guests")
    Box.version, Box.chip, Box.image_state = "3.5.2", "esp32c3", "valid"
    Box.phones_active, Box.manifest_status, Box.manifest_msg, Box.upload_status = False, 200, "", 200
    Box.restarts, Box.answers = True, True
    Box.log, Box.manifests, Box.uploads = [], [], []
    for k, v in box.items():
        setattr(Box, k, v)


def run(*args, **extra):
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_CONF=conf, PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
             PISO_SUMMARY=f"{tmp}/summary", PISO_SELF_PATH=inst, PISO_TEST_NONROOT="1", PISO_MONITOR_BIN=f"{bindir}/monitor",
             PISO_MONITOR_CONF=conf, PISO_NDSCTL=f"{bindir}/ndsctl", PISO_UPDATE_ALLOW_HTTP="1", BOX_IP=BOX_ADDR, TMPDIR=f"{tmp}/t",
             PISO_BOX_POLL="1", PISO_BOX_WAIT="3")
    e.update(extra)
    return subprocess.run(["sh", inst, "box-update", *args], env=e, capture_output=True, text=True, timeout=120)


def telegram():
    return open(msgs).read()


# ---- nothing to do ----------------------------------------------------------------------------------------------------------------
reset()
publish("3.5.2")
r = run("auto")
check(r.returncode == 0 and "up to date" in r.stdout and not Box.uploads and not telegram(), "the same version: nothing happens, nothing is said: " + r.stdout + r.stderr)
publish("3.5.1")
r = run("auto")
check("up to date" in r.stdout and not Box.uploads, "an older published version is never installed (no downgrade)")
reset(version="3.9.0")
publish("3.10.0")
r = run("check")
check("3.10.0 is available" in r.stdout and not Box.uploads, "versions compare as numbers (3.10.0 is newer than 3.9.0): " + r.stdout)
reset(version="4.0.0")
publish("3.9.9")
check("up to date" in run("auto").stdout and not Box.uploads, "4.0.0 is not replaced by 3.9.9")
reset()
os.remove(f"{www}/firmware.json")
r = run("auto")
check(r.returncode == 0 and "No box firmware information" in r.stdout and not Box.uploads, "nothing published: said so, no error")
reset(answers=False)
publish("3.6.0")
r = run("auto")
check(r.returncode == 0 and "does not answer" in r.stdout and not Box.uploads, "a box that does not answer is not an error: " + r.stdout)
reset()
publish("3.6.0", chips=("esp32",))
r = run("auto")
check("not for this box's chip" in r.stdout and not Box.uploads, "a release that is not built for this chip is skipped")

# ---- check, and the reasons to wait ------------------------------------------------------------------------------------------------
reset()
publish("3.6.0")
r = run("check")
check(r.returncode == 0 and "3.6.0 is available" in r.stdout and "Faster coin counting" in r.stdout and not Box.uploads, "check says what is available and uploads nothing: " + r.stdout)
open(f"{tmp}/guests", "w").close()
r = run("auto")
check("quiet moment" in r.stdout and not Box.uploads and "firmware 3.6.0 is available" in telegram(), "customers online: it waits and tells the owner: " + r.stdout + telegram())
run("auto")
check(telegram().count("firmware 3.6.0 is available") == 1, "and does not repeat it every hour")
reset(phones_active=True)
r = run("auto")
check("a phone has time running" in r.stdout and not Box.uploads, "a phone with time running: it waits")
reset("AUTO_UPDATE='0'\n")
r = run("auto")
check("automatic updates are off" in r.stdout and not Box.uploads, "auto-update off: it only tells the owner")
reset(image_state="pending_verify")
r = run("auto")
check("still checking" in r.stdout and not Box.uploads, "a box still checking its last firmware is left alone")

# ---- the update itself -------------------------------------------------------------------------------------------------------------
reset()
publish("3.6.0")
r = run("auto")
check(r.returncode == 0 and Box.uploads == [IMAGE], "the quiet-moment update hands the box the exact published image: " + r.stdout + r.stderr)
m = Box.manifests[0] if Box.manifests else {}
check(m.get("chip") == "esp32c3" and m.get("version") == "3.6.0" and m.get("sha256") == hashlib.sha256(IMAGE).hexdigest()
      and m.get("size") == str(len(IMAGE)) and m.get("sig") == "SIGNATURE-esp32c3", "the box gets the owner's signed manifest as published: " + str(m))
check(Box.log.index("POST /api/ota/manifest") < Box.log.index("POST /update"), "the manifest goes first, then the image")
check(Box.version == "3.6.0" and "installed and running" in telegram() and "BOX_UPDATE_TRIES" not in open(conf).read().replace("BOX_UPDATE_TRIES=''", ""),
      "the box is on 3.6.0, the owner is told: " + telegram())
r = run("auto")
check("up to date" in r.stdout and len(Box.uploads) == 1, "and the next hour finds nothing to do")

reset(chip="esp32")
publish("3.6.0", chips=("esp32", "esp32c3"))
run("auto")
check(Box.manifests and Box.manifests[0]["chip"] == "esp32" and Box.manifests[0]["sig"] == "SIGNATURE-esp32", "a plain ESP32 box gets its own chip's image and signature")

# ---- things that go wrong ----------------------------------------------------------------------------------------------------------
reset()
publish("3.6.0", tamper=True)
r = run("auto")
check(r.returncode != 0 and not Box.uploads and "REFUSED" in telegram(), "an image that is not the published one is never given to the box: " + telegram())
reset(manifest_status=400, manifest_msg="Update refused: signature is not valid.")
publish("3.6.0")
r = run("auto")
check(r.returncode != 0 and not Box.uploads and "signature is not valid" in telegram() and Box.version == "3.5.2", "the box refusing the signature stops it and the owner hears why: " + telegram())
reset(upload_status=400)
publish("3.6.0")
r = run("auto")
check(r.returncode != 0 and "FAILED" in telegram() and Box.version == "3.5.2", "an upload the box rejects is reported: " + telegram())
for _ in range(2):
    run("auto")
check(len(Box.uploads) == 3, "it tries three times: " + str(len(Box.uploads)))
r = run("auto")
check(len(Box.uploads) == 3 and "not tried again" in r.stdout, "then it stops until the owner runs it by hand: " + r.stdout)
Box.upload_status = 200
r = run()
check(Box.version == "3.6.0", "run by hand it still goes ahead (and then installs)")
reset(restarts=False)
publish("3.6.0")
r = run("auto")
check(r.returncode != 0 and "did not take" in telegram(), "a box that does not come back on the new version is reported: " + telegram())

# ---- the hourly timer carries it ---------------------------------------------------------------------------------------------------
check(subprocess.run(["sh", "-n", inst]).returncode == 0, "the installed command is valid shell")
check(re.search(r"box-update \[check\]", open(SCRIPT).read()) is not None, "the usage line lists box-update")

print(f"{checks} checks, {failures} failed")
sys.exit(1 if failures else 0)
