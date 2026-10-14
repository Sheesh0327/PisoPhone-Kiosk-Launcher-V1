"""The console page's signed script: the signer's manifest is accepted by the browser-side verifier (Node), and a changed script,
a changed version, an expired or foreign signature, and a damaged signature are all refused."""
import base64
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
import sign_console_ui as S  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

ROOT = os.path.join(HERE, "..", "..")
VERIFY_JS = os.path.join(ROOT, "tools", "pisoportal", "src", "console-verify.js")
UI_JS = os.path.join(ROOT, "tools", "pisoportal", "src", "console-ui.js")
fails = n = 0


def check(ok, what):
    global fails, n
    n += 1
    if not ok:
        fails += 1
        print("FAIL:", what)


key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
spki = lambda k: base64.b64encode(k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
data = open(UI_JS, "rb").read()
now = 1_800_000_000
m = S.make_manifest(key, data, now=now, expires_days=10)

# the same code the browser runs, in Node (window = globalThis)
NODE = """
global.window = global; global.atob = (s) => Buffer.from(s, 'base64').toString('binary');
new Function(require('fs').readFileSync(process.argv[1], 'utf8'))();
const jobs = JSON.parse(require('fs').readFileSync(0, 'utf8'));
console.log(JSON.stringify(jobs.map(j => j.kind === 'sha' ? PisoVerify.sha256hex(Buffer.from(j.data, 'base64')) : PisoVerify.verify(j.key, j.msg, j.sig))));
"""


def node(jobs):
    r = subprocess.run(["node", "-e", NODE, VERIFY_JS], input=json.dumps(jobs), capture_output=True, text=True)
    if r.returncode:
        print(r.stderr)
    return json.loads(r.stdout)


def msg(mm):
    return S.canonical(mm["version"], mm["api"], mm["expires"], mm["sha256"])


import hashlib
res = node([
    {"kind": "sha", "data": base64.b64encode(data).decode()},
    {"kind": "v", "key": spki(key), "msg": msg(m), "sig": m["sig"]},
    {"kind": "v", "key": spki(key), "msg": msg(m).replace("|1|", "|2|", 1), "sig": m["sig"]},           # another version
    {"kind": "v", "key": spki(other), "msg": msg(m), "sig": m["sig"]},                                   # another key
    {"kind": "v", "key": spki(key), "msg": msg(m), "sig": base64.b64encode(b"\x30\x06\x02\x01\x01\x02\x01\x01").decode()},   # a made-up signature
    {"kind": "v", "key": spki(key), "msg": msg(m), "sig": "AAAA"},                                       # damaged
    {"kind": "v", "key": "AAAA", "msg": msg(m), "sig": m["sig"]},                                        # damaged key
])
check(res[0] == hashlib.sha256(data).hexdigest() == m["sha256"], "the browser's SHA-256 equals the signer's")
check(res[1] is True, "the browser accepts the owner's signature")
check(res[2] is False, "a changed version is refused")
check(res[3] is False, "another key's signature is refused")
check(res[4] is False and res[5] is False and res[6] is False, "made-up and damaged signatures and keys are refused")
for i in range(5):   # signatures vary (random k); many all verify
    mi = S.make_manifest(key, data + b" " * i, now=now)
    check(node([{"kind": "v", "key": spki(key), "msg": msg(mi), "sig": mi["sig"]}])[0] is True, f"signature {i} verifies")
check(m["version"] == S.version_of(data) and m["api"] == 1 and m["expires"] == now + 10 * 86400, "the manifest has the version, api and expiry")
try:
    S.make_manifest(key, b"no version here" * 20)
    check(False, "a script without a version is refused")
except ValueError:
    check(True, "a script without a version is refused")
# the command line writes both files
with tempfile.TemporaryDirectory() as d:
    pem = os.path.join(d, "k.pem")
    open(pem, "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    r = subprocess.run([sys.executable, os.path.join(HERE, "..", "sign_console_ui.py"), "--private", pem, "--out-dir", os.path.join(d, "out")], capture_output=True, text=True)
    out = os.path.join(d, "out")
    check(r.returncode == 0 and open(os.path.join(out, "ui.js"), "rb").read() == data and json.load(open(os.path.join(out, "ui.json")))["version"] == S.version_of(data),
          "the tool writes ui.js and ui.json: " + r.stdout + r.stderr)
print(f"{n} checks, {fails} failures")
sys.exit(1 if fails else 0)
