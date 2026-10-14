#!/usr/bin/env python3
"""The owner's console: a web page for every owner script in scripts/, in place of its command line.

    python3 scripts/owner_console.py             opens http://127.0.0.1:<port>/ in your browser
    python3 scripts/owner_console.py --no-open   only prints the address

Each script has its own page (/p/<name>): a form for its options, a Run button, the script's output, and the files it made
(QR pictures shown, PDFs and tables to download). The scripts themselves are not changed and still work from a terminal;
this program only fills in their command line and runs them in this repository, on YOUR computer.

Your keys never go through the browser: a page asks for the PATH of a key file (the script reads it from disk, as before). The
console listens on this computer only (127.0.0.1), needs the secret in the printed address, checks the Host name, and refuses a
request that did not come from its own pages. Stop it with Ctrl+C. Needs only Python 3.8+ (the scripts need what they needed).
"""
import argparse
import base64
import html
import json
import os
import secrets
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(os.path.expanduser("~"), "pisophone_console")   # pictures the pages make (private: mode 0700)
RUN_SECONDS = 900
KEY_NOTE = "Your key file stays on this computer: the page asks only for its path."

# What each page asks for. kind: text | number | check | select. flag: the option the value goes to (None: a positional argument).
# "auto_out": the console picks the output file (inside OUT_DIR) so that the picture can be shown.
SCRIPTS = [
    {
        "name": "make_admin_qr", "file": "make_admin_qr.py", "group": "Keys and codes", "title": "Owner admin QR code",
        "blurb": "Draws the QR code that logs you in as super admin on every coin box (box login page, Scan admin QR). "
                 "It uses your key file, or makes one the first time.",
        "warn": "The picture is a key: anyone who has it is super admin on your boxes. Keep it private. 'Replace the key' makes a "
                "new one and the old QR stops working once every box is updated.",
        "fields": [
            {"id": "private", "label": "Key file (leave empty for ~/pisophone-superadmin.key)", "kind": "text", "flag": "--private"},
            {"id": "new", "label": "Replace the key with a new one", "kind": "check", "flag": "--new"},
        ],
        "auto_out": "--out",
    },
    {
        "name": "make_recovery_code", "file": "make_recovery_code.py", "group": "Keys and codes", "title": "Phone recovery code",
        "blurb": "Draws a QR code that, scanned on a phone's lock screen, removes PisoPhone from that phone. "
                 "It stops working when its time is up.",
        "warn": "While it is valid, anyone holding the picture can remove the app from a phone. Delete it afterwards.",
        "fields": [
            {"id": "private", "label": "Owner key file (leave empty for ~/pisophone_license_key.pem)", "kind": "text", "flag": "--private"},
            {"id": "minutes", "label": "Works for (minutes, 1 to 1440)", "kind": "number", "flag": "--minutes", "default": "30"},
        ],
        "auto_out": "--out",
    },
    {
        "name": "make_card_key", "file": "make_card_key.py", "group": "Keys and codes", "title": "Card signing key",
        "blurb": "Makes the key that signs the QR time cards, and writes its public half into the firmware source "
                 "(commit it, then update every box).",
        "warn": "It refuses to overwrite a key that exists. Keep the key file private and backed up.",
        "fields": [{"id": "path", "label": "Where to save the key (leave empty for ~/pisophone_card_key.pem)", "kind": "text", "flag": None}],
    },
    {
        "name": "make_owner_keys", "file": "make_owner_keys.py", "group": "Keys and codes", "title": "Owner signing key",
        "blurb": "Makes the one key that signs firmware and router updates, and writes its public half into the firmware source.",
        "warn": "Run this only on a computer you control. 'Show the key once' prints it here instead of saving it; copy it into the "
                "repository secret OWNER_SIGNING_KEY_B64 and keep an offline copy. It cannot be shown again.",
        "fields": [
            {"id": "path", "label": "Where to save the key (leave empty for ~/pisophone_license_key.pem)", "kind": "text", "flag": None},
            {"id": "github_secret", "label": "Show the key once instead of saving it", "kind": "check", "flag": "--github-secret"},
        ],
    },
    {
        "name": "make_cards", "file": "make_cards.py", "group": "Time cards", "title": "Print QR time cards",
        "blurb": "Makes a batch of QR time cards for one coin box: front and back sheets (A4, 12 cards each) and a ledger of every "
                 "serial ever issued.",
        "warn": "The QR code IS the account key: keep unsold cards and the ledger private.",
        "fields": [
            {"id": "box", "label": "Coin box MAC address (as on its dashboard, AA:BB:CC:DD:EE:FF)", "kind": "text", "flag": "--box", "required": True},
            {"id": "count", "label": "How many cards", "kind": "number", "flag": "--count", "required": True, "default": "24"},
            {"id": "hours", "label": "Starter time on the first scan (hours)", "kind": "number", "flag": "--hours", "default": "3"},
            {"id": "key", "label": "Card key file (leave empty for ~/pisophone_card_key.pem)", "kind": "text", "flag": "--key"},
            {"id": "out", "label": "Save the batch in (leave empty for ~/pisophone_cards)", "kind": "text", "flag": "--out"},
            {"id": "flip", "label": "How you turn the paper over", "kind": "select", "flag": "--flip",
             "choices": [["long", "like a page (long edge)"], ["short", "short edge"]], "default": "long"},
            {"id": "shift_x", "label": "Nudge the backs right (mm, minus for left)", "kind": "number", "flag": "--back-shift-x-mm"},
            {"id": "shift_y", "label": "Nudge the backs down (mm, minus for up)", "kind": "number", "flag": "--back-shift-y-mm"},
        ],
        "collect": "out", "collect_default": os.path.join(os.path.expanduser("~"), "pisophone_cards"),
    },
    {
        "name": "sign_firmware", "file": "sign_firmware.py", "group": "Releasing", "title": "Sign a firmware image",
        "blurb": "Signs a firmware.bin built with PlatformIO so boxes accept it, and writes the manifest next to the image.",
        "warn": KEY_NOTE,
        "fields": [
            {"id": "private", "label": "Owner key file", "kind": "text", "flag": "--private", "required": True},
            {"id": "chip", "label": "Chip", "kind": "select", "flag": "--chip", "choices": [["esp32c3", "ESP32-C3"], ["esp32", "ESP32"]]},
            {"id": "image", "label": "firmware.bin (path)", "kind": "text", "flag": "--image", "required": True},
            {"id": "version", "label": "Version (leave empty for the one in FirmwareVersion.h)", "kind": "text", "flag": "--version"},
            {"id": "out", "label": "Manifest path (leave empty for <image>.manifest.json)", "kind": "text", "flag": "--out"},
        ],
    },
    {
        "name": "sign_router", "file": "sign_router.py", "group": "Releasing", "title": "Sign a router update",
        "blurb": "Signs setup/piso-setup.sh so every router can install it, and writes website/update/router-setup.sh and router.json.",
        "warn": KEY_NOTE + " Commit the two files to main afterwards.",
        "fields": [
            {"id": "private", "label": "Owner key file", "kind": "text", "flag": "--private", "required": True},
            {"id": "rollout", "label": "Percent of routers that take it now", "kind": "number", "flag": "--rollout", "default": "100"},
            {"id": "changelog", "label": "One line for the routers' Telegram message", "kind": "text", "flag": "--changelog"},
            {"id": "setup", "label": "Setup file (leave empty for setup/piso-setup.sh)", "kind": "text", "flag": "--setup"},
            {"id": "out_dir", "label": "Output folder (leave empty for website/update)", "kind": "text", "flag": "--out-dir"},
        ],
    },
    {
        "name": "sign_console_ui", "file": "sign_console_ui.py", "group": "Releasing", "title": "Sign the router console page",
        "blurb": "Signs the router console's page script so routers can take a newer one from the website without a router update. "
                 "Writes website/console/ui.js and ui.json.",
        "warn": KEY_NOTE + " Commit the two files to main afterwards (CI does it on main).",
        "fields": [
            {"id": "private", "label": "Owner key file", "kind": "text", "flag": "--private", "required": True},
            {"id": "expires_days", "label": "Good for (days)", "kind": "number", "flag": "--expires-days", "default": "120"},
        ],
    },
    {
        "name": "publish_firmware_ota", "file": "publish_firmware_ota.py", "group": "Releasing", "title": "Publish box firmware",
        "blurb": "Copies freshly built images into website/update/, signs them and rewrites firmware.json (the dashboard's Install update).",
        "warn": "CI does this on main by itself. Run it by hand only when you must. " + KEY_NOTE,
        "fields": [
            {"id": "images", "label": "Folder with firmware-esp32c3.bin and firmware-esp32.bin", "kind": "text", "flag": "--images"},
            {"id": "json_only", "label": "Only rewrite firmware.json from the files already there", "kind": "check", "flag": "--json-only"},
            {"id": "key", "label": "Owner key file (signs the images)", "kind": "text", "flag": "--key"},
        ],
    },
    {
        "name": "verify_release", "file": "verify_release.py", "group": "Checks", "title": "Check a release",
        "blurb": "Checks that what is about to be published can really be installed, before beta is merged into main. Read-only.",
        "fields": [{"id": "against", "label": "Compare with (leave empty for origin/main)", "kind": "text", "flag": "--against"}],
    },
    {
        "name": "check_published_apk", "file": "check_published_apk.py", "group": "Checks", "title": "Check the published APK",
        "blurb": "Checks that the published APK matches app.json (size, sha256 and signing certificate). Read-only.",
        "fields": [],
    },
    {
        "name": "check_vendored", "file": "check_vendored.py", "group": "Checks", "title": "Check the bundled libraries",
        "blurb": "Checks that the third-party files kept in this repository are the ones they should be. Read-only.",
        "fields": [],
    },
    {
        "name": "check_security_rules", "file": "check_security_rules.sh", "group": "Checks", "title": "Check the security rules",
        "blurb": "Looks through the repository for keys, passwords and other things that must never be committed. Read-only.",
        "fields": [],
    },
    {
        "name": "sold_unit_fuses", "file": "sold_unit_check.py", "prefix": ["fuses"], "group": "Checks", "title": "Sold unit: security fuses",
        "blurb": "Shows the security eFuses of a hardened box (needs espefuse.py). Burns nothing.",
        "fields": [
            {"id": "port", "label": "Serial port (COM3, /dev/ttyUSB0)", "kind": "text", "flag": "--port", "required": True},
            {"id": "expect_locked", "label": "Expect the box to be locked", "kind": "check", "flag": "--expect-locked"},
        ],
    },
    {
        "name": "sold_unit_flash", "file": "sold_unit_check.py", "prefix": ["flash"], "group": "Checks", "title": "Sold unit: flash dump",
        "blurb": "Tells whether a flash dump is ciphertext (a hardened box) or readable. Read-only.",
        "fields": [
            {"id": "dump", "label": "Flash dump file (path)", "kind": "text", "flag": None, "required": True},
            {"id": "find", "label": "Look for this text in it", "kind": "text", "flag": "--find"},
        ],
    },
]
BY_NAME = {s["name"]: s for s in SCRIPTS}


def build_command(spec, values):
    """The command line for a page's form values, or a ValueError naming the first thing wrong. Only the page's own fields count;
    a value that begins with '-' (other than a number) is refused so that a field can never pose as an option."""
    script = os.path.join(HERE, spec["file"])
    argv = (["sh"] if spec["file"].endswith(".sh") else [sys.executable]) + [script] + list(spec.get("prefix", []))
    positional = []
    for f in spec["fields"]:
        raw = values.get(f["id"])
        value = "" if raw is None else str(raw).strip()
        if f["kind"] == "check":
            if raw in (True, "true", "on", "1"):
                argv.append(f["flag"])
            continue
        if not value:
            if f.get("required"):
                raise ValueError(f"{f['label']}: please fill this in")
            continue
        if "\x00" in value or "\n" in value or "\r" in value:
            raise ValueError(f"{f['label']}: not a valid value")
        if value.startswith("-") and not (f["kind"] == "number" and _is_number(value)):
            raise ValueError(f"{f['label']}: must not start with '-'")
        if f["kind"] == "number" and not _is_number(value):
            raise ValueError(f"{f['label']}: must be a number")
        if f["kind"] == "select" and value not in [c[0] for c in f["choices"]]:
            raise ValueError(f"{f['label']}: pick one of the choices")
        if f["kind"] == "text" and f["flag"] != "--box" and ("path" in f["label"].lower() or "file" in f["label"].lower()
                                                            or "folder" in f["label"].lower() or "save" in f["label"].lower()):
            value = os.path.expanduser(value)
        if f["flag"]:
            argv += [f["flag"], value]
        else:
            positional.append(value)
    return argv + positional


def _is_number(text):
    try:
        float(text)
        return True
    except ValueError:
        return False


class Console:
    """The server's state: the secret, and the files a run offered (by id: only those can be downloaded)."""

    def __init__(self):
        self.token = secrets.token_urlsafe(24)
        self.files = {}
        self.lock = threading.Lock()
        self.logo = ""
        try:
            with open(os.path.join(ROOT, "website", "img", "technet-logo-64.png"), "rb") as f:
                self.logo = "data:image/png;base64," + base64.b64encode(f.read()).decode()
        except OSError:
            pass

    def offer(self, path):
        fid = secrets.token_urlsafe(10)
        with self.lock:
            self.files[fid] = os.path.realpath(path)
        return fid

    def run(self, spec, values):
        """Runs a script for a page. Returns {"rc", "output", "files": [{"name", "id", "image"}], "command"}."""
        out_path = ""
        argv_values = dict(values)
        argv = build_command(spec, argv_values)
        if spec.get("auto_out"):
            os.makedirs(OUT_DIR, mode=0o700, exist_ok=True)
            out_path = os.path.join(OUT_DIR, f"{spec['name']}-{time.strftime('%Y%m%d-%H%M%S')}.png")
            argv += [spec["auto_out"], out_path]
        started = time.time()
        try:
            done = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=RUN_SECONDS, stdin=subprocess.DEVNULL)
            output, rc = (done.stdout or "") + (done.stderr or ""), done.returncode
        except subprocess.TimeoutExpired:
            output, rc = f"The script took longer than {RUN_SECONDS // 60} minutes and was stopped.", 124
        except OSError as e:
            output, rc = f"Could not start the script: {e}", 127
        files = []
        if out_path and os.path.isfile(out_path):
            files.append(out_path)
        if spec.get("collect"):
            root = os.path.expanduser(str(values.get(spec["collect"]) or "").strip() or spec["collect_default"])
            files += self._made_since(root, started)
        offered = [{"name": os.path.basename(p), "id": self.offer(p), "image": p.lower().endswith(".png") and "front" not in p.lower()
                    and os.path.getsize(p) < 3_000_000} for p in files]
        return {"rc": rc, "output": output, "files": offered, "command": " ".join(os.path.basename(a) if i < 2 else a for i, a in enumerate(argv))}

    @staticmethod
    def _made_since(root, started, limit=40):
        found = []
        for base, _dirs, names in os.walk(root):
            for n in names:
                p = os.path.join(base, n)
                try:
                    if os.path.getmtime(p) >= started - 1 and n.lower().endswith((".pdf", ".csv")):
                        found.append(p)
                except OSError:
                    pass
        return sorted(found)[:limit]


STYLE = """
:root{--bg:#eef3fb;--card:#fff;--line:#d9e3f5;--ink:#0b1630;--mut:#5b6b8c;--acc:#1f6bff;--acc2:#12b894;--bad:#b3392b;--warn:#fdf0d3;--log:#0b1220}
@media(prefers-color-scheme:dark){:root{--bg:#050d22;--card:#0c1a3a;--line:#1f3361;--ink:#eef3ff;--mut:#97a6c7;--acc:#4f93ff;--acc2:#16c9a4;--bad:#ff8a8a;--warn:#33280c;--log:#030816}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;min-height:100vh}
nav{width:260px;flex:none;border-right:1px solid var(--line);padding:14px;overflow-y:auto}
nav .brand{display:flex;align-items:center;gap:8px;font-weight:800;font-style:italic;margin-bottom:12px}nav .brand img{width:34px;height:34px}
nav h3{font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:var(--mut);margin:16px 0 4px}
nav a{display:block;padding:6px 10px;border-radius:8px;color:var(--ink);text-decoration:none}nav a:hover{background:var(--card)}nav a.on{background:linear-gradient(90deg,var(--acc),var(--acc2));color:#fff;font-weight:600}
main{flex:1;max-width:820px;padding:20px 24px 60px}h1{font-size:22px;margin:0 0 6px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:14px 0}
.warn{background:var(--warn);border-left:4px solid #e69500;border-radius:6px;padding:8px 12px;margin:12px 0}
label{display:block;font-weight:600;margin-top:12px}label.check{display:flex;gap:8px;align-items:center;font-weight:500}
input[type=text],input[type=number],select{width:100%;font:inherit;padding:9px 11px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
button{font:inherit;margin-top:16px;padding:10px 18px;border-radius:10px;border:0;color:#fff;font-weight:700;cursor:pointer;background:linear-gradient(90deg,var(--acc),var(--acc2))}button:disabled{opacity:.5}
pre{background:var(--log);color:#cbd5e1;border-radius:8px;padding:10px 12px;overflow:auto;max-height:420px;white-space:pre-wrap;word-break:break-word;font:12px/1.4 ui-monospace,Menlo,Consolas,monospace}
.ok{color:#0f8f6b;font-weight:700}.bad{color:var(--bad);font-weight:700}.mut{color:var(--mut)}img.qr{max-width:100%;border-radius:8px;background:#fff;margin-top:8px}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:12px}.cards a.tile{display:block;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px;text-decoration:none;color:var(--ink)}
.cards a.tile b{display:block}@media(max-width:760px){body{display:block}nav{width:auto;border:0}}
"""


def esc(text):
    return html.escape(str(text), quote=True)


def nav_html(c, current):
    out = [f'<nav><div class="brand">{f"<img src={chr(34)}{c.logo}{chr(34)} alt={chr(34)}{chr(34)}>" if c.logo else ""}<span>Owner console</span></div>'
           f'<a href="/" class="{"on" if current == "" else ""}">All tools</a>']
    group = None
    for s in SCRIPTS:
        if s["group"] != group:
            group = s["group"]
            out.append(f"<h3>{esc(group)}</h3>")
        out.append(f'<a href="/p/{s["name"]}" class="{"on" if current == s["name"] else ""}">{esc(s["title"])}</a>')
    return "".join(out) + "</nav>"


def page(c, current, body, title="Owner console"):
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            f'<title>{esc(title)} - TechNet PisoHub</title><style>{STYLE}</style></head><body>{nav_html(c, current)}<main>{body}</main></body></html>')


def index_body():
    out = ['<h1>TechNet PisoHub owner console</h1><p class="mut">One page for every owner script. Pick a tool. Everything runs on this '
           'computer; your keys are read from disk by the scripts and never go through the browser.</p>']
    group = None
    for s in SCRIPTS:
        if s["group"] != group:
            if group:
                out.append("</div>")
            group = s["group"]
            out.append(f'<h2>{esc(group)}</h2><div class="cards">')
        out.append(f'<a class="tile" href="/p/{s["name"]}"><b>{esc(s["title"])}</b><span class="mut">{esc(s["blurb"][:110])}…</span></a>')
    return "".join(out) + "</div>"


def form_body(spec):
    parts = [f"<h1>{esc(spec['title'])}</h1><p>{esc(spec['blurb'])}</p>"]
    if spec.get("warn"):
        parts.append(f'<div class="warn">{esc(spec["warn"])}</div>')
    parts.append('<form id="f" class="card" autocomplete="off">')
    for f in spec["fields"]:
        fid, label, default = f["id"], esc(f["label"]), esc(f.get("default", ""))
        req = " required" if f.get("required") else ""
        if f["kind"] == "check":
            parts.append(f'<label class="check"><input type="checkbox" name="{fid}"> {label}</label>')
        elif f["kind"] == "select":
            opts = "".join(f'<option value="{esc(v)}"{" selected" if v == f.get("default") else ""}>{esc(t)}</option>' for v, t in f["choices"])
            parts.append(f'<label>{label}<select name="{fid}">{opts}</select></label>')
        else:
            kind = "number" if f["kind"] == "number" else "text"
            parts.append(f'<label>{label}<input type="{kind}" name="{fid}" value="{default}"{req}{" step=any" if kind == "number" else ""}></label>')
    parts.append('<button type="submit" id="go">Run</button></form><div id="result"></div>')
    parts.append("""<script>
var f=document.getElementById('f'),res=document.getElementById('result'),go=document.getElementById('go');
function esc(t){return String(t).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
f.onsubmit=function(e){e.preventDefault();var v={};Array.prototype.forEach.call(f.elements,function(x){if(!x.name)return;v[x.name]=x.type==='checkbox'?x.checked:x.value});
go.disabled=true;go.textContent='Running...';res.innerHTML='<p class="mut">Running. Some tools take a minute.</p>';
fetch('/run/'+location.pathname.split('/').pop(),{method:'POST',headers:{'Content-Type':'application/json','X-Piso':'1'},body:JSON.stringify(v)})
.then(function(r){return r.json()}).then(function(j){var h='';
 if(j.error){h='<p class="bad">'+esc(j.error)+'</p>'}else{
  h='<p class="'+(j.rc===0?'ok':'bad')+'">'+(j.rc===0?'Done.':'It stopped with code '+j.rc+'.')+'</p><pre>'+esc(j.output||'(no output)')+'</pre>';
  (j.files||[]).forEach(function(x){h+='<div class="card"><b>'+esc(x.name)+'</b> <a href="/file/'+x.id+'" download="'+esc(x.name)+'">Download</a>'+(x.image?'<br><img class="qr" src="/file/'+x.id+'" alt="">':'')+'</div>'})}
 res.innerHTML=h}).catch(function(){res.innerHTML='<p class="bad">The console did not answer. Is it still running?</p>'}).finally(function(){go.disabled=false;go.textContent='Run'})};
</script>""")
    return "".join(parts)


class Handler(BaseHTTPRequestHandler):
    server_version = "OwnerConsole"
    console = None
    port = 0

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body, ctype="text/html; charset=utf-8", extra=()):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                                                    "img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'")
        for k, v in extra:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _host_ok(self):
        host = (self.headers.get("Host") or "").lower()
        return host in (f"127.0.0.1:{self.port}", f"localhost:{self.port}")

    def _authed(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _s, v = part.strip().partition("=")
            if k == "piso_console" and secrets.compare_digest(v, self.console.token):
                return True
        return False

    def do_GET(self):
        if not self._host_ok():
            return self._send(403, "unknown host name", "text/plain")
        url = urlsplit(self.path)
        if url.path == "/enter":   # the printed address carries the secret once; it becomes a cookie and the address is cleaned
            token = (parse_qs(url.query).get("t") or [""])[0]
            if not secrets.compare_digest(token, self.console.token):
                return self._send(403, "wrong or missing secret: use the address the console printed", "text/plain")
            return self._send(303, "", "text/plain", [("Location", "/"), ("Set-Cookie", f"piso_console={self.console.token}; Path=/; HttpOnly; SameSite=Strict")])
        if not self._authed():
            return self._send(403, "Open the address the console printed (it contains the secret).", "text/plain")
        if url.path == "/":
            return self._send(200, page(self.console, "", index_body()))
        if url.path.startswith("/p/"):
            spec = BY_NAME.get(url.path[3:])
            if not spec:
                return self._send(404, "no such tool", "text/plain")
            return self._send(200, page(self.console, spec["name"], form_body(spec), spec["title"]))
        if url.path.startswith("/file/"):
            with self.console.lock:
                path = self.console.files.get(url.path[6:])
            if not path or not os.path.isfile(path):
                return self._send(404, "no such file", "text/plain")
            ctype = {".png": "image/png", ".pdf": "application/pdf", ".csv": "text/csv"}.get(os.path.splitext(path)[1].lower(), "application/octet-stream")
            with open(path, "rb") as fh:
                return self._send(200, fh.read(), ctype)
        return self._send(404, "not found", "text/plain")

    def do_POST(self):
        if not self._host_ok():
            return self._send(403, json.dumps({"error": "unknown host name"}), "application/json")
        if not self._authed() or self.headers.get("X-Piso") != "1":
            return self._send(403, json.dumps({"error": "forbidden"}), "application/json")
        url = urlsplit(self.path)
        spec = BY_NAME.get(url.path[5:]) if url.path.startswith("/run/") else None
        if not spec:
            return self._send(404, json.dumps({"error": "no such tool"}), "application/json")
        try:
            length = int(self.headers.get("Content-Length") or 0)
            values = json.loads(self.rfile.read(min(length, 65536)) or b"{}")
            if not isinstance(values, dict):
                raise ValueError("bad request")
            result = self.console.run(spec, values)
        except ValueError as e:
            return self._send(200, json.dumps({"error": str(e)}), "application/json")
        self._send(200, json.dumps(result), "application/json")


def make_server(port=0):
    console = Console()
    handler = type("BoundHandler", (Handler,), {"console": console})
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    handler.port = server.server_address[1]
    return server, console


def main(argv=None):
    p = argparse.ArgumentParser(description="A web page for every owner script (runs on this computer only).")
    p.add_argument("--port", type=int, default=0, help="port to listen on (default: any free one)")
    p.add_argument("--no-open", action="store_true", help="do not open the browser")
    args = p.parse_args(argv)
    server, console = make_server(args.port)
    url = f"http://127.0.0.1:{server.server_address[1]}/enter?t={console.token}"
    print("Owner console running on this computer only. Open:\n  " + url + "\nStop it with Ctrl+C.")
    if not args.no_open:
        threading.Thread(target=lambda: webbrowser.open(url), daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
