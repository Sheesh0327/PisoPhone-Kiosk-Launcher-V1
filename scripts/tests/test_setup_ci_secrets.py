#!/usr/bin/env python3
"""Tests of scripts/setup_ci_secrets.py with a throw-away keystore (made here, for the test only) and fake GitHub and
CircleCI servers. Needs keytool (a Java JDK).   python3 scripts/tests/test_setup_ci_secrets.py"""
import base64, importlib.util, io, json, os, shutil, subprocess, sys, tempfile, threading, types
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


GOOD_GH, RELEASES_GH, CCI = "github_pat_" + "A" * 70, "github_pat_" + "B" * 70, "CCIPAT_" + "c" * 40
store = {"envvars": {}, "requests": []}


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        store["requests"].append(("GET", self.path))
        auth, cci = self.headers.get("Authorization", ""), self.headers.get("Circle-Token", "")
        if self.path == "/gh/repos/Sheesh0327/PisoPhone-Kiosk-Launcher-V1":
            return self.reply(200 if auth in (f"Bearer {GOOD_GH}", "Bearer ghp_classic000000000000000000000000000000") else 404, {})
        if self.path == "/gh/repos/Sheesh0327/PisoPhone-Releases":
            return self.reply(200 if auth == f"Bearer {RELEASES_GH}" else 404, {})
        if self.path == "/cci/project/gh/Sheesh0327/PisoPhone-Kiosk-Launcher-V1":
            return self.reply(200 if cci == CCI else 401, {"slug": "x"})
        if self.path.endswith("/envvar"):
            return self.reply(200, {"items": [{"name": n, "value": "xxxx" + v[-4:]} for n, v in store["envvars"].items()]})
        self.reply(404, {})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        store["requests"].append(("POST", self.path))
        if self.headers.get("Circle-Token") != CCI:
            return self.reply(401, {"message": "no"})
        store["envvars"][body["name"]] = body["value"]
        self.reply(201, {"name": body["name"]})


server = HTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f"http://127.0.0.1:{server.server_port}"
os.environ.update(PISO_GITHUB_API=base + "/gh", PISO_CIRCLECI_API=base + "/cci")
spec = importlib.util.spec_from_file_location("sc", f"{ROOT}/scripts/setup_ci_secrets.py")
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

# every keytool call is recorded: no password may ever be on its command line
calls = []
real_run = subprocess.run
def recording_run(argv, *a, **kw):
    calls.append(list(argv))
    return real_run(argv, *a, **kw)
sc.subprocess.run = recording_run

tmp = tempfile.mkdtemp()
tool = sc.find_keytool()
JKS, SP, KP = f"{tmp}/release.jks", "storePass-123456", "keyPass-654321"
r = real_run([tool, "-genkeypair", "-keystore", JKS, "-storetype", "JKS", "-alias", "upload", "-keyalg", "RSA", "-keysize", "2048",
              "-validity", "100", "-dname", "CN=Test", "-storepass", SP, "-keypass", KP], capture_output=True, text=True)
check(r.returncode == 0, "the test keystore was made: " + r.stderr)
sha = sc.cert_sha256(tool, JKS, "upload", SP)
check(len(sha) == 64, "the certificate's SHA-256 is read")
check(sc.qr_checksum("00" * 32) == "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "the QR form of a hash")
check(sc.aliases(tool, JKS, SP) == ["upload"], "the aliases in the keystore")
check(sc.check_key_password(tool, JKS, "upload", SP, KP) and not sc.check_key_password(tool, JKS, "upload", SP, "wrong-pass1"),
      "the key password is checked")


def run(answers, secrets_, published, **opts):
    store["envvars"].clear()
    sc.published_checksum = lambda: published
    it, it2 = iter(answers), iter(secrets_)
    args = types.SimpleNamespace(new_keystore=False, dry_run=False, allow_classic_token=False, replace_key=False)
    args.__dict__.update(opts)
    out, old = io.StringIO(), sys.stdout
    sys.stdout = out
    err = None
    try:
        sc.run(args, read=lambda p="": next(it), read_secret=lambda p="": next(it2))
    except sc.Stop as e:
        err = str(e)
    finally:
        sys.stdout = old
    return err, out.getvalue()


mine = sc.qr_checksum(sha)
err, out = run([JKS, ""], [SP, KP, GOOD_GH, RELEASES_GH, CCI], mine)
check(err is None, f"the existing keystore, checked and uploaded: {err}")
v = store["envvars"]
check(set(v) == {"KEYSTORE_BASE64", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "GITHUB_PUSH_TOKEN", "RELEASES_TOKEN"}, "every variable is set: " + str(sorted(v)))
check(base64.b64decode(v.get("KEYSTORE_BASE64", "")) == open(JKS, "rb").read(), "KEYSTORE_BASE64 is the keystore file")
check(v.get("KEY_ALIAS") == "upload" and v.get("STORE_PASSWORD") == SP and v.get("KEY_PASSWORD") == KP, "alias and passwords")
check(all(s not in out for s in (SP, KP, GOOD_GH, RELEASES_GH, CCI)), "no secret is shown")
check(not any(s in " ".join(c) for c in calls for s in (SP, KP)), "no password on a keytool command line")
check("the key the published app is signed with" in out, "it says the key matches the published app")

err, _ = run([JKS, ""], [SP, KP, GOOD_GH, RELEASES_GH, CCI], "Un0usqLMwnmeaowrY2g21EgRVWe4bTRDLUi2RH12WbE")
check(err and "NOT the one the published app is signed with" in err and not store["envvars"], "another key than the published app's: refused, nothing uploaded")
err, out = run([JKS, ""], [SP, KP, GOOD_GH, "", CCI], "Un0usqLMwnmeaowrY2g21EgRVWe4bTRDLUi2RH12WbE", replace_key=True)
check(err is None and store["envvars"].get("KEY_ALIAS") == "upload" and "replaces the published app's key" in out,
      "--replace-key: a fresh start with another key is accepted, and said: " + str(err))
err, _ = run([], [], mine, new_keystore=True, dry_run=True)
check(err and "--dry-run cannot be used with --new-keystore" in err, "a dry run never makes a key")
err, _ = run([JKS], ["wrongpass123"], mine)
check(err and "keystore password is wrong" in err and not store["envvars"], "a wrong keystore password")
err, _ = run([JKS, ""], [SP, "wrong-key-pw", ], mine)
check(err and "key password is wrong" in err, "a wrong key password")
err, _ = run([JKS, "nosuch"], [SP, KP], mine)
check(err and "no key called 'nosuch'" in err and "upload" in err, "an unknown alias: the ones there are listed")
err, _ = run([JKS, ""], [SP, KP, "ghp_classic000000000000000000000000000000"], mine)
check(err and "classic token" in err, "a classic GitHub token is refused")
err, _ = run([JKS, ""], [SP, KP, "ghp_classic000000000000000000000000000000", "", CCI], mine, allow_classic_token=True)
check(err is None and "RELEASES_TOKEN" not in store["envvars"], "a classic token when insisted; no releases token: " + str(err))
err, _ = run([JKS, ""], [SP, KP, "github_pat_" + "Z" * 70], mine)
check(err and "cannot open" in err, "a token without access to the repository")
err, _ = run([JKS, ""], [SP, KP, GOOD_GH, "", "bad-cci-token"], mine)
check(err and "did not accept" in err and not store["envvars"], "a wrong CircleCI token: nothing uploaded")
err, out = run([JKS, ""], [SP, KP, GOOD_GH, "", CCI], mine, dry_run=True)
check(err is None and not store["envvars"] and "nothing was uploaded" in out, "dry run: checks only")
err, _ = run([JKS, ""], [SP + " ", KP], mine)
check(err and "spaces" in err, "a pasted value with a trailing space is refused")

# a new keystore: shown once, confirmed, then uploaded
p12 = f"{tmp}/new.p12"
holder = {}
real_new = sc.new_keystore
def spy(tool_, path):
    holder["r"] = real_new(tool_, path)
    return holder["r"]
sc.new_keystore = spy
err, out = run(["NEW KEY", p12], ["placeholder"], mine, new_keystore=True)
check(err and "does not match" in err and os.path.exists(p12) and not store["envvars"], "a new key not confirmed: nothing uploaded, the file kept")
alias, password = holder["r"]
check(len(password) == 43 and password in out and alias == "pisophone", "the new password is 43 random characters, shown once")
if os.name != "nt":
    check(oct(os.stat(p12).st_mode & 0o777) == "0o600", "the new keystore file is private")
os.remove(p12)
it_pw = {}
def confirm(prompt=""):
    return holder["r"][1] if "Type the password back" in prompt else next(it_pw["s"])
it_pw["s"] = iter([GOOD_GH, "", CCI])
store["envvars"].clear()
sc.published_checksum = lambda: mine
args = types.SimpleNamespace(new_keystore=True, dry_run=False, allow_classic_token=False, replace_key=False)
answers = iter(["NEW KEY", p12])
sys.stdout = io.StringIO()
try:
    sc.run(args, read=lambda p="": next(answers), read_secret=confirm)
    err = None
except sc.Stop as e:
    err = str(e)
finally:
    sys.stdout = sys.__stdout__
v = store["envvars"]
check(err is None and v.get("KEY_ALIAS") == "pisophone" and v.get("STORE_PASSWORD") == v.get("KEY_PASSWORD") == holder["r"][1],
      f"a confirmed new key is uploaded: {err}")
r = real_run([tool, "-list", "-v", "-keystore", p12, "-storepass", holder["r"][1]], capture_output=True, text=True)
check("RSA" in r.stdout and ("4096" in r.stdout) and "PKCS12" in r.stdout, "the new key: RSA 4096 in a PKCS12 keystore")
try:
    sc.new_keystore(tool, p12)
    check(False, "an existing keystore file is never overwritten")
except sc.Stop:
    check(True, "")

server.shutdown()
shutil.rmtree(tmp, ignore_errors=True)
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
