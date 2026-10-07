#!/usr/bin/env python3
"""Router updates from the website, end to end without a router: a web server that plays the Cloudflare site, a release signed
by a throwaway owner key (scripts/sign_router.py), the real portal program built for this computer with that key as the owner
key (the cargo feature test-owner-key; the router program never has it), and the real setup file doing the installing.
Run with:  python3 router/tests/test_selfupdate.py   (needs: cargo, curl, pip install cryptography)"""
import base64, hashlib, http.server, json, os, shutil, subprocess, sys, tempfile, threading

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, f"{ROOT}/scripts")
import sign_router  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

SCRIPT = f"{ROOT}/setup/piso-setup.sh"
tmp = tempfile.mkdtemp()
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


# ---- the owner's key (throwaway) and the program that trusts it -----------------------------------------------------------------
owner = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
pub = owner.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
keyfile = f"{tmp}/owner_key.b64"
open(keyfile, "w").write(base64.b64encode(pub).decode() + "\n")
# (CI keeps this directory between runs, so the program is not compiled from nothing every time)
TARGET = os.environ.get("PISO_TEST_TARGET_DIR") or f"{tmp}/target"
b = subprocess.run(["cargo", "build", "--release", "--locked", "--features", "test-owner-key"], cwd=f"{ROOT}/tools/pisoportal",
                   env=dict(os.environ, CARGO_TARGET_DIR=TARGET), capture_output=True, text=True)
portal = f"{TARGET}/release/pisoportal"
if not os.path.exists(portal):
    sys.exit("could not build the portal program:\n" + b.stderr[-1500:])

# ---- the releases ----------------------------------------------------------------------------------------------------------------
base = open(SCRIPT, "rb").read()
assert b"PISO_RELEASE='1.0.0'" in base, "this test expects setup/RELEASE to be 1.0.0 (the installed release)"


def release(version):
    return base.replace(b"PISO_RELEASE='1.0.0'", f"PISO_RELEASE='{version}'".encode(), 1)


www = f"{tmp}/www"
os.makedirs(f"{www}/update")


def publish(data, key=owner, rollout=100, version=None, changelog="Fixes the coin page"):
    """What scripts/sign_router.py writes to website/update/ (or, with `version`, a manifest that claims another version)."""
    for f in ("router.json", "router-setup.sh"):
        if os.path.exists(f"{www}/update/{f}"):
            os.remove(f"{www}/update/{f}")
    if data is None:
        return
    m = sign_router.make_manifest(key, data, rollout, changelog)
    if version:
        m["version"] = version
        m["sig"] = base64.b64encode(key.sign(sign_router.canonical(version, m["sha256"], m["size"], rollout).encode(), ec.ECDSA(hashes.SHA256()))).decode()
    open(f"{www}/update/router-setup.sh", "wb").write(data)
    open(f"{www}/update/router.json", "w").write(json.dumps(m))


class Site(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=www, **k)

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Site)
threading.Thread(target=srv.serve_forever, daemon=True).start()
url = f"http://127.0.0.1:{srv.server_address[1]}/update"

# ---- a router: its files, a fake Telegram monitor, a fake openNDS, a fake uci ---------------------------------------------------------
bindir = f"{tmp}/bin"
os.makedirs(bindir)
open(f"{bindir}/uci", "w").write("#!/bin/sh\n[ \"$1\" = -q ] && shift\ncase \"$1\" in get) exit 1 ;; esac\nexit 0\n")
msgs = f"{tmp}/telegram.txt"
open(f"{bindir}/monitor", "w").write(f"#!/bin/sh\n[ \"$1\" = send ] && {{ shift; echo \"$*\" >> {msgs}; }}\nexit 0\n")
open(f"{bindir}/ndsctl", "w").write(f"#!/bin/sh\n[ -e {tmp}/guests ] && printf '\"state\":\"Authenticated\",\\n\"state\":\"Authenticated\",\\n'\nexit 0\n")
for f in ("uci", "monitor", "ndsctl"):
    os.chmod(f"{bindir}/{f}", 0o755)
root, inst = f"{tmp}/root", f"{tmp}/inst/piso-setup"
conf = f"{tmp}/conf"
OLD = b"the old portal program"
INSTALLED_FILES = {"usr/bin/pisoportal": OLD, "usr/bin/piso-monitor.sh": b"#!/bin/sh\n# old monitor\n", "etc/init.d/pisoportal": b"#!/bin/sh\n# old init\n",
                   "etc/init.d/piso_monitor": b"#!/bin/sh\n# old init 2\n"}
# the installed command: the setup file without its payload, as piso-setup installs it
installed_cmd = subprocess.run(["sed", "/^# ---- payload: the portal files/,$d", SCRIPT], capture_output=True).stdout


def reset(extra_conf=""):
    shutil.rmtree(root, ignore_errors=True)
    shutil.rmtree(f"{tmp}/backup", ignore_errors=True)
    shutil.rmtree(f"{tmp}/t", ignore_errors=True)
    os.makedirs(f"{tmp}/t")
    for rel, data in INSTALLED_FILES.items():
        os.makedirs(os.path.dirname(f"{root}/{rel}"), exist_ok=True)
        open(f"{root}/{rel}", "wb").write(data)
    os.makedirs(os.path.dirname(inst), exist_ok=True)
    open(inst, "wb").write(installed_cmd)
    os.chmod(inst, 0o755)
    os.makedirs(f"{root}/etc/crontabs", exist_ok=True)
    open(f"{root}/etc/crontabs/root", "w").write("0 3 * * * /usr/bin/other-job\n")
    open(conf, "w").write("GW_KEY='" + "ab" * 32 + f"'\nUPDATE_URL='{url}'\nGUEST_NAME='Shop WiFi'\nBOX_MAC='AA:BB:CC:DD:EE:09'\n" + extra_conf)
    open(msgs, "w").close()
    if os.path.exists(f"{tmp}/guests"):
        os.remove(f"{tmp}/guests")


def env(**extra):
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_ROOT=root, PISO_CONF=conf, PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
             PISO_SUMMARY=f"{tmp}/summary", PISO_SELF_PATH=inst, PISO_PORTAL_BIN=portal, PISO_TEST_NONROOT="1", PISO_UPDATE_BACKUP=f"{tmp}/backup",
             PISO_HEALTH_WAIT="0", PISO_MONITOR_BIN=f"{bindir}/monitor", PISO_MONITOR_CONF=conf, PISO_NDSCTL=f"{bindir}/ndsctl",
             PISO_CRON_FILE=f"{root}/etc/crontabs/root", PISO_UPDATE_ALLOW_HTTP="1", PISOPORTAL_TEST_OWNER_KEY=keyfile, TMPDIR=f"{tmp}/t")
    e.update(extra)
    return e


def self_update(*args, **extra):
    return subprocess.run(["sh", inst, "self-update", *args], env=env(**extra), capture_output=True, text=True, timeout=120)


def state():
    return {rel: open(f"{root}/{rel}", "rb").read() for rel in INSTALLED_FILES}


def release_of_installed():
    return sign_router.release_of(open(inst, "rb").read())


def telegram():
    return open(msgs).read()


def unchanged(why):
    check(state() == INSTALLED_FILES and release_of_installed() == "1.0.0", "nothing on the router changed: " + why)


reset()
check(release_of_installed() == "1.0.0", "the installed command knows its release")

# ---- nothing published, offline, https only ---------------------------------------------------------------------------------------
publish(None)
r = self_update()
check(r.returncode == 0 and "No update information" in r.stdout, "no feed published yet: said so, no error: " + r.stdout + r.stderr)
unchanged("no feed")
publish(release("1.1.0"))
r = self_update(PISO_UPDATE_ALLOW_HTTP="")
check("No update information" in r.stdout, "a feed over plain http is not used (the router asks https only)")
unchanged("plain http")
reset("UPDATE_URL='http://127.0.0.1:9/update'\n")
r = self_update()
check(r.returncode == 0 and "No update information" in r.stdout, "an unreachable site is not an error and changes nothing")
unchanged("offline")

# ---- check, hold while customers are online, hold when auto-update is off ---------------------------------------------------------
reset()
publish(release("1.1.0"), changelog="Fixes the coin page")
r = self_update("check")
check(r.returncode == 0 and "Release 1.1.0 is available" in r.stdout and "Fixes the coin page" in r.stdout, "check says what is available: " + r.stdout)
unchanged("check only looks")
open(f"{tmp}/guests", "w").close()
r = self_update("auto")
check(r.returncode == 0 and "quiet moment" in r.stdout, "with customers online the nightly update waits: " + r.stdout)
unchanged("customers online")
check(telegram().count("release 1.1.0 is available") == 1, "and tells the owner once: " + telegram())
self_update("auto")
check(telegram().count("release 1.1.0 is available") == 1, "not again every hour")
reset("AUTO_UPDATE='0'\n")
r = self_update("auto")
check("automatic updates are off" in r.stdout and "/update" in telegram(), "with auto-update off it only tells the owner how to install it: " + telegram())
unchanged("auto-update off")
r = subprocess.run(["sh", inst, "auto-update", "on"], env=env(), capture_output=True, text=True)
check("AUTO_UPDATE='1'" in open(conf).read() and "by themselves" in r.stdout, "piso-setup auto-update on")
r = subprocess.run(["sh", inst, "auto-update", "off"], env=env(), capture_output=True, text=True)
check("AUTO_UPDATE='0'" in open(conf).read(), "piso-setup auto-update off")

# ---- a signed release installs ---------------------------------------------------------------------------------------------------
reset()
publish(release("1.1.0"))
r = self_update("auto")
new_prog = open(f"{root}/usr/bin/pisoportal", "rb").read()
real_prog = open(f"{ROOT}/tools/pisoportal/bin/pisoportal-mipsel", "rb").read()
check(r.returncode == 0 and new_prog == real_prog, "the signed release installs the portal program from the payload: " + r.stdout[-300:] + r.stderr[-300:])
check(open(f"{root}/usr/bin/piso-monitor.sh").read() == open(f"{ROOT}/router/piso_monitor.sh").read(), "and the monitor")
check(release_of_installed() == "1.1.0" and not any(ln.startswith(b"#@@") for ln in open(inst, "rb")) and os.path.getsize(inst) < 200_000,
      "the installed command is now release 1.1.0, without the payload")
check(open(f"{tmp}/backup/" + f"{root}/usr/bin/pisoportal".replace("/", "_"), "rb").read() == OLD, "the earlier program was kept aside")
check("release 1.1.0 installed and running" in telegram(), "the owner is told: " + telegram())
cron = open(f"{root}/etc/crontabs/root").read()
check("/usr/bin/other-job" in cron and len([ln for ln in cron.splitlines() if "self-update auto" in ln]) == 1 and f"{inst} self-update auto" in cron, "the hourly timer is in cron, the other jobs kept: " + cron)
r = self_update()
check(r.returncode == 0 and "Up to date (release 1.1.0)" in r.stdout, "afterwards it is up to date: " + r.stdout)
r = self_update("auto")
check(r.returncode == 0 and "Up to date" in r.stdout and telegram().count("installed and running") == 1, "and the nightly check says nothing")
check(not os.path.exists(f"{tmp}/t/piso-update.lock") and not [f for f in os.listdir(f"{tmp}/t") if f.startswith("piso-update")], "no lock or temporary files are left: " + str(os.listdir(f"{tmp}/t")))
# the timer's line is not duplicated by another update of the same router
reset()
publish(release("1.1.0"))
self_update("auto")
publish(release("1.2.0"))
self_update("auto")
cron = open(f"{root}/etc/crontabs/root").read()
check(release_of_installed() == "1.2.0" and len([ln for ln in cron.splitlines() if "self-update" in ln]) == 1, "a second update leaves one timer line: " + cron)

# ---- what is refused ---------------------------------------------------------------------------------------------------------------
reset()
bad = bytearray(release("1.1.0"))
bad[5000] ^= 1
good = release("1.1.0")
publish(good)
open(f"{www}/update/router-setup.sh", "wb").write(bytes(bad))
r = self_update()
check(r.returncode != 0 and "Update refused" in r.stdout and "checksum" in r.stdout, "a file that is not the signed one is refused (checksum differs): " + r.stdout)
unchanged("tampered file")
check("REFUSED" in telegram(), "and the owner is told: " + telegram())
reset()
publish(release("1.1.0"), key=other)
r = self_update()
check(r.returncode != 0 and "not the owner's" in r.stdout, "a release signed by another key: " + r.stdout)
unchanged("another key")
reset()
publish(release("1.1.0"), version="1.9.9")
r = self_update()
check(r.returncode != 0 and "signed file is not release 1.9.9" in r.stdout, "a signed manifest whose file is another release: " + r.stdout)
unchanged("manifest and file disagree")
reset()
publish(release("1.1.0"))
j = json.load(open(f"{www}/update/router.json"))
j["size"] = j["size"] + 1
open(f"{www}/update/router.json", "w").write(json.dumps(j))
r = self_update()
check(r.returncode != 0 and "Update refused" in r.stdout, "a manifest changed after signing: " + r.stdout)
unchanged("manifest edited")
reset()
publish(release("1.0.0"))
r = self_update()
check(r.returncode == 0 and "Up to date" in r.stdout, "the same release is not installed again")
unchanged("same release")
reset()
publish(base.replace(b"PISO_RELEASE='1.0.0'", b"PISO_RELEASE='0.9.0'", 1))
r = self_update()
check(r.returncode == 0 and "Up to date" in r.stdout, "an older release is never installed (no downgrade): " + r.stdout)
unchanged("downgrade")
reset()
publish(release("1.1.0"))
r = self_update(PISOPORTAL_TEST_OWNER_KEY=f"{tmp}/no-key")
check(r.returncode != 0 and "no owner key" in r.stdout and telegram() == "", "without an owner key built in every update is refused (and it does not nag): " + r.stdout)
unchanged("no owner key")
reset()
os.makedirs(f"{tmp}/t/piso-update.lock")
open(f"{tmp}/t/piso-update.lock/pid", "w").write(str(os.getpid()))
publish(release("1.1.0"))
r = self_update()
check("Another update is running" in r.stdout, "two updates never run at once")
unchanged("locked")

# ---- staged rollout ---------------------------------------------------------------------------------------------------------------------
reset()
publish(release("1.1.0"), rollout=0)
r = self_update()
check(r.returncode == 0 and "staged rollout" in r.stdout, "at 0% no router takes it: " + r.stdout)
unchanged("rollout 0")
check("rolling out in stages" in telegram(), "a manual /update says why: " + telegram())
publish(release("1.1.0"), rollout=100)
r = self_update()
check(release_of_installed() == "1.1.0", "at 100% the same release installs: " + r.stdout)

# ---- a release that does not run is undone ----------------------------------------------------------------------------------------------
reset()
publish(release("1.1.0"))
r = self_update(PISO_HEALTH_CMD="false")
check(r.returncode != 0 and "Going back to release 1.0.0" in r.stdout, "a release that fails its check is undone: " + r.stdout)
unchanged("rolled back")
check("FAILED and was undone" in telegram(), "the owner is told: " + telegram())
r = self_update()
check(release_of_installed() == "1.1.0", "and a healthy one installs afterwards")

# ---- the owner's tool ---------------------------------------------------------------------------------------------------------------------
keyfile_pem = f"{tmp}/owner.pem"
open(keyfile_pem, "wb").write(owner.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
out = f"{tmp}/out"
s = f"{tmp}/setup-1.4.0.sh"
open(s, "wb").write(release("1.4.0"))
r = subprocess.run([sys.executable, f"{ROOT}/scripts/sign_router.py", "--private", keyfile_pem, "--setup", s, "--rollout", "10", "--changelog", 'a "quoted"\nline', "--out-dir", out],
                   capture_output=True, text=True)
m = json.load(open(f"{out}/router.json"))
check(r.returncode == 0 and m["version"] == "1.4.0" and m["rollout"] == 10 and m["sha256"] == hashlib.sha256(release("1.4.0")).hexdigest() and "\n" not in m["changelog"] and '"' not in m["changelog"]
      and open(f"{out}/router-setup.sh", "rb").read() == release("1.4.0"), "sign_router.py writes the manifest and the file: " + r.stdout + r.stderr)
v = subprocess.run([portal, "update-verify", f"{out}/router.json", f"{out}/router-setup.sh"], env=env(), capture_output=True, text=True)
check(v.returncode == 0 and v.stdout.strip() == "OK 1.4.0", "and the router program verifies what it wrote: " + v.stdout)
open(f"{tmp}/not-setup", "wb").write(b"#!/bin/sh\necho hi\n" * 100)
r = subprocess.run([sys.executable, f"{ROOT}/scripts/sign_router.py", "--private", keyfile_pem, "--setup", f"{tmp}/not-setup", "--out-dir", f"{tmp}/out2"], capture_output=True, text=True)
check(r.returncode != 0 and "PISO_RELEASE" in r.stderr and not os.path.exists(f"{tmp}/out2/router.json"), "it refuses a file that is not a router setup: " + r.stderr)
r = subprocess.run([sys.executable, f"{ROOT}/scripts/sign_router.py", "--private", keyfile_pem, "--setup", s, "--rollout", "101", "--out-dir", f"{tmp}/out3"], capture_output=True, text=True)
check(r.returncode != 0 and "percentage" in r.stderr, "and a rollout above 100%")

print(f"{checks} checks, {failures} failures")
shutil.rmtree(tmp, ignore_errors=True)
sys.exit(1 if failures else 0)
