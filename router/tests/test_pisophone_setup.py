#!/usr/bin/env python3
"""Tests of setup/pisophone_setup.py, the router setup run from a computer, without a router: a fake ssh runs the remote
commands here with sh, against a fake router (fake wget serving the repository's files from a folder, fake uci, ubus and
jsonfilter), with the real one-line installer (setup/install.sh) and a stand-in setup file.
Run with:  python3 router/tests/test_pisophone_setup.py"""
import hashlib, importlib.util, io, os, shutil, subprocess, sys, tempfile, time, types

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)

tmp = tempfile.mkdtemp()
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


# ---- what is typed -------------------------------------------------------------------------------------------------------
check(ps.password_problem("abcdefgh", 8, 63) is None, "8 characters are enough")
check(ps.password_problem("abcdefg", 8, 63), "7 are not")
check(ps.password_problem("a" * 33, 8, 32), "the box password is at most 32")
for bad in ("abc defgh", "abc'defgh", 'abc"defgh', "abc$defgh", "abc`defgh", "abc\\defgh", "abcdéfgh"):
    check(ps.password_problem(bad, 8, 63), f"refused: {bad!r}")
check(ps.name_problem("PisoWiFi", "wifi") is None and ps.name_problem("Aling Nena's", "site"), "names: no quotes")
check(ps.name_problem("PisoKiosk", "wifi") and ps.name_problem("MyPisoCoinBox", "wifi"), "the fixed network names are refused")
check(ps.name_problem("x" * 33, "site") and ps.name_problem("", "wifi"), "names: 1 to 32 characters")
gen = [ps.generate(14) for _ in range(50)]
check(all(len(g) == 14 and ps.password_problem(g, 8, 63) is None and not set(g) & set("0O1lI") for g in gen) and len(set(gen)) == 50,
      "generated passwords are valid, unambiguous and different")


def reader(*answers):
    it = iter(answers)
    return lambda prompt="": next(it)


args = types.SimpleNamespace(yes=False, guest_ssid=None, site_name=None)
out = io.StringIO()
sys.stdout = out
try:
    a = ps.collect_answers(args, reader("", "Aling Nena Store"), reader("short", "routerpass1", "routerpass2", "routerpass1", "routerpass1", "", "boxadmin99", "boxadmin99"))
finally:
    sys.stdout = sys.__stdout__
check(a["GUEST_SSID"] == "PisoWiFi" and a["SITE_NAME"] == "Aling Nena Store", "Enter keeps the default Wi-Fi name: " + str(a))
check(a["ROOT_PASSWORD"] == "routerpass1" and "Not usable" in out.getvalue() and "differ" in out.getvalue(), "a short or mistyped password is asked again")
check(len(a["KIOSK_PASSWORD"]) == 12 and a["KIOSK_PASSWORD"] in out.getvalue(), "Enter generates the kiosk password and shows it")
check(a["BOX_NEW_ADMIN_PASSWORD"] == "boxadmin99", "the box admin password as typed")
sys.stdout = io.StringIO()
try:
    a = ps.collect_answers(types.SimpleNamespace(yes=False, guest_ssid=None, site_name=None), reader("PisoKiosk", "Shop WiFi", ""),
                           reader("", "", ""))
finally:
    sys.stdout = sys.__stdout__
check(a["GUEST_SSID"] == "Shop WiFi" and a["SITE_NAME"] == "Shop WiFi", "a refused Wi-Fi name is asked again; the site name defaults to it")
a = ps.collect_answers(types.SimpleNamespace(yes=True, guest_ssid="Cafe", site_name=None))
check(a["GUEST_SSID"] == "Cafe" and a["SITE_NAME"] == "Cafe" and len(a["BOX_NEW_ADMIN_PASSWORD"]) == 16, "--yes: options, defaults and generated passwords")
try:
    ps.collect_answers(types.SimpleNamespace(yes=True, guest_ssid="Bad'Name", site_name=None))
    check(False, "--yes with a bad name is refused")
except ps.SetupError:
    check(True, "")

# ---- finding the router, waiting for the move -----------------------------------------------------------------------------
check(ps.find_router(lambda h: h == "192.168.1.1") == "192.168.1.1", "a factory router is found")
check(ps.find_router(lambda h: True) == "10.0.0.1", "10.0.0.1 is preferred")
check(ps.find_router(lambda h: False) is None, "no router: None")
clock = [0.0]
seen = []
sys.stdout = out = io.StringIO()
try:
    up = ps.wait_for_move(probe=lambda h: clock[0] > 40, sleep=lambda s: clock.__setitem__(0, clock[0] + s), now=lambda: clock[0],
                          renew=lambda: seen.append(clock[0]))
    down = ps.wait_for_move(probe=lambda h: False, wait=60, sleep=lambda s: clock.__setitem__(0, clock[0] + s), now=lambda: clock[0])
finally:
    sys.stdout = sys.__stdout__
check(up and len(seen) == 1 and "Unplug" in out.getvalue(), "waits for 10.0.0.1, renews the address once, says to replug the cable")
check(down is False, "gives up after the wait")

# ---- the remote commands --------------------------------------------------------------------------------------------------
cmd = ps.setup_command("beta")
check("https://beta.pisophone.pages.dev/install.sh" in cmd and "--yes" in cmd, "the branch's installer, from its website, --yes")
check(ps.site_url("main") == "https://pisophone.pages.dev" and ps.site_url("Feature/New_UI") == "https://feature-new-ui.pisophone.pages.dev",
      "the website of a branch (Cloudflare's branch alias)")
check("IFS= read -r ROOT_PASSWORD" in cmd and "export ROOT_PASSWORD" in cmd, "the answers are read from the input")
check("'x'\"'\"';reboot;'\"'\"''" in ps.installer_command("x';reboot;'"), "installer arguments are quoted")
bb = shutil.which("busybox")
for shell in ["sh"] + ([f"{bb} sh"] if bb else []):
    r = subprocess.run(shell.split() + ["-n", "-c", cmd], capture_output=True, text=True)
    check(r.returncode == 0, f"the remote command reads as shell ({shell}): {r.stderr}")

# ---- end to end, with a fake ssh and a fake router -------------------------------------------------------------------------
bindir, site, fake_root = f"{tmp}/bin", f"{tmp}/site", f"{tmp}/router"
for d in (bindir, site, f"{fake_root}/root", f"{fake_root}/tmp", f"{fake_root}/etc"):
    os.makedirs(d)


def script(name, body):
    open(f"{bindir}/{name}", "w").write("#!/bin/sh\n" + body)
    os.chmod(f"{bindir}/{name}", 0o755)


# wget -q -T 60 -O <file> <url>: the repository's raw files, from <site>/<branch>/...
script("wget", f"""out=""; while [ $# -gt 1 ]; do [ "$1" = -O ] && out="$2"; shift; done; url="$1"
echo "$url" >> {tmp}/wget.log
u="${{url#*://}}"; host="${{u%%/*}}"; p="${{u#*/}}"
case "$host" in pisophone.pages.dev) b=main ;; *.pisophone.pages.dev) b="${{host%.pisophone.pages.dev}}" ;; *) exit 8 ;; esac
[ -f "{site}/$b/$p" ] || exit 8
cat "{site}/$b/$p" > "$out"
""")
script("uci", f"""echo "$*" >> {tmp}/uci.log
[ "$1" = -q ] && shift
case "$1" in get) [ "$2" = network.lan.ipaddr ] && echo "$(cat {tmp}/lan)/24" ;; set) case "$2" in network.lan.ipaddr=*) echo "${{2#*=}}" > {tmp}/lan ;; esac ;; esac
exit 0
""")
script("ubus", "echo '{}'\n")
script("jsonfilter", 'echo "192.168.100.20"\n')
script("netinit", f'echo "$1" >> {tmp}/network.log\n')

# the stand-in setup file: shows what it was given, writes the summary, the sheet and the state file like the real one
FAKE_SETUP = f"""#!/bin/sh
PISO_RELEASE='9.9.9'
echo "SETUP-RAN args=[$*] root=[$ROOT_PASSWORD] kiosk=[$KIOSK_PASSWORD] box=[$BOX_NEW_ADMIN_PASSWORD] guest=[$GUEST_SSID] site=[$SITE_NAME]"
R={fake_root}
case "$(cat {tmp}/result 2>/dev/null)" in
  fail) echo "ERROR: the coin box did not join"; echo "FAILED the coin box did not join the hidden PisoCoinBox network" > $R/tmp/piso-setup.state; exit 1 ;;
  checks) echo "DONE with 1 failed checks (see log)" > $R/tmp/piso-setup.state ;;
  drop) exit 0 ;;
  *) echo "DONE all checks passed" > $R/tmp/piso-setup.state ;;
esac
printf 'Router password: %s\\nPisoKiosk Wi-Fi password: %s\\n' "$ROOT_PASSWORD" "$KIOSK_PASSWORD" > $R/root/piso-setup-summary.txt
echo "<html><body>sheet for $SITE_NAME</body></html>" > $R/root/piso-handout.html
echo "SETUP COMPLETE."
exit 0
# ---- payload: the portal files (extracted by extract_payload) ----
"""
for branch in ("main", "beta"):
    d = f"{site}/{branch}/setup"
    os.makedirs(d)
    shutil.copy(f"{ROOT}/setup/install.sh", f"{site}/{branch}/install.sh")
    open(f"{d}/piso-setup.sh", "w").write(FAKE_SETUP)
    open(f"{d}/piso-setup.sh.sha256", "w").write(hashlib.sha256(FAKE_SETUP.encode()).hexdigest() + "  piso-setup.sh\n")

# The fake ssh: logs its arguments and input, answers the login probe as told (FAKE_PROBE), runs everything else with sh as
# the router would, with the router's fixed paths moved into the fake router's folder.
open(f"{tmp}/fake_ssh.py", "w").write(f"""import os, subprocess, sys
argv = sys.argv[1:]
host = next(a for a in argv if a.startswith("root@"))[5:]
remote = argv[-1]
with open("{tmp}/ssh.log", "a") as f:
    f.write(repr(argv) + "\\n")
if "BatchMode=yes" in argv:
    probe = open("{tmp}/probe").read().strip()
    if probe == "denied":
        sys.stderr.write("root@" + host + ": Permission denied (publickey,password).\\n"); sys.exit(255)
    print("@@OPENWRT" if probe != "notopenwrt" else "hello")
    if probe == "configured": print("@@CONFIGURED")
    sys.exit(0)
lan = open("{tmp}/lan").read().strip()
if host != lan:
    sys.stderr.write("ssh: connect to host " + host + " port 22: Connection timed out\\n"); sys.exit(255)
remote = remote.replace("/tmp/piso", "{fake_root}/tmp/piso").replace("/root/piso", "{fake_root}/root/piso")
data = sys.stdin.buffer.read() if not sys.stdin.isatty() else b""
open("{tmp}/ssh-input.log", "ab").write(data)
env = dict(os.environ, PATH="{bindir}:" + os.environ["PATH"], PISO_INSTALL_DIR="{fake_root}/root", PISO_TEST_NONROOT="1",
           PISO_NETWORK_INIT="{bindir}/netinit", PISO_TTY="/dev/null")
sys.exit(subprocess.run(["sh", "-c", remote], input=data, env=env).returncode)
""")
os.environ["PISO_SSH"] = f"{sys.executable} {tmp}/fake_ssh.py"


def e2e(answers=(), secrets_=(), lan="192.168.1.1", probe="factory", result="ok", up=lambda h: True, moved=True, **opts):
    for f in ("ssh.log", "ssh-input.log", "wget.log", "uci.log", "network.log"):
        if os.path.exists(f"{tmp}/{f}"):
            os.remove(f"{tmp}/{f}")
    for f in ("tmp/piso-setup.state", "root/piso-setup-summary.txt", "root/piso-handout.html"):
        if os.path.exists(f"{fake_root}/{f}"):
            os.remove(f"{fake_root}/{f}")
    open(f"{tmp}/lan", "w").write(lan)
    open(f"{tmp}/probe", "w").write(probe)
    open(f"{tmp}/result", "w").write(result)
    outdir = tempfile.mkdtemp(dir=tmp)
    a = dict(branch="main", update=False, router=None, guest_ssid=None, site_name=None, yes=False, out=outdir, no_browser=False)
    a.update(opts)
    opened = []
    buf = io.StringIO()
    sys.stdout = buf
    # (what ssh itself prints goes to file descriptor 1, as on a real terminal: captured too)
    sys.__stdout__.flush()
    saved_fd, cap = os.dup(1), open(f"{tmp}/fd1.log", "w+")
    os.dup2(cap.fileno(), 1)
    rc, err = None, None
    try:
        rc = ps.run_setup(types.SimpleNamespace(**a), ps.Ssh(tmp), read=reader(*answers), read_secret=reader(*secrets_),
                          probe=lambda h: h == (open(f"{tmp}/lan").read().strip()) and up(h),
                          wait=lambda probe, renew: moved and probe("10.0.0.1"), open_page=opened.append)
    except ps.SetupError as e:
        err = str(e)
    finally:
        sys.stdout = sys.__stdout__
        os.dup2(saved_fd, 1)
        os.close(saved_fd)
    time.sleep(0.1)
    cap.seek(0)
    buf.write(cap.read())
    cap.close()
    log = lambda f: open(f"{tmp}/{f}").read() if os.path.exists(f"{tmp}/{f}") else ""
    return types.SimpleNamespace(rc=rc, err=err, out=buf.getvalue(), ssh=log("ssh.log"), input=log("ssh-input.log"), wget=log("wget.log"),
                                 uci=log("uci.log"), saved=sorted(os.listdir(outdir)), outdir=outdir, opened=opened)


# a factory router: questions, review, the move to 10.0.0.1, then the setup with the answers
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc == 0 and r.err is None, f"factory router: set up ({r.err}): " + r.out[-800:])
check("set network.lan.ipaddr=10.0.0.1" in r.uci and open(f"{tmp}/lan").read().strip() == "10.0.0.1", "it was moved to 10.0.0.1")
check("SETUP-RAN args=[--yes] root=[routerpass1] kiosk=[kioskpass1] box=[boxadmin99] guest=[Tindahan WiFi] site=[Aling Nena]" in r.out,
      "the setup got every answer, unattended: " + r.out[-600:])
check("routerpass1" not in r.ssh and "kioskpass1" not in r.ssh and "boxadmin99" not in r.ssh, "no password on any command line")
check(r.input == "routerpass1\nkioskpass1\nboxadmin99\nTindahan WiFi\nAling Nena\n", "the answers went over the connection's input")
check(r.ssh.count("root@192.168.1.1") == 2 and r.ssh.count("root@10.0.0.1") == 1, "probe and installer at 192.168.1.1, setup at 10.0.0.1: " + r.ssh)
check("HostKeyAlias=pisophone-router" in r.ssh and "StrictHostKeyChecking=accept-new" in r.ssh, "one pinned host key for both addresses")
check("https://pisophone.pages.dev/install.sh" in r.wget and "https://pisophone.pages.dev/setup/piso-setup.sh" in r.wget, "main's installer and setup file")
summary = [f for f in r.saved if f.startswith("pisophone-summary-")]
sheet = [f for f in r.saved if f.startswith("pisophone-setup-sheet-")]
check(len(summary) == 1 and "Router password: routerpass1" in open(f"{r.outdir}/{summary[0]}").read(), "the summary is saved: " + str(r.saved))
check(len(sheet) == 1 and "sheet for Aling Nena" in open(f"{r.outdir}/{sheet[0]}").read() and r.opened and r.opened[0].endswith(sheet[0]),
      "the setup sheet is saved and opened")
if os.name != "nt":
    check(oct(os.stat(f"{r.outdir}/{summary[0]}").st_mode & 0o777) == "0o600", "the saved files are private")
check("SETUP COMPLETE" in r.out and "@@PISO" not in r.out and "<html>" not in r.out, "the markers and the sheet are not shown")

# cancelled at the review: nothing is run
r = e2e(answers=("", "", "n"), secrets_=("", "", ""))
check(r.rc == 1 and r.ssh.count("root@") == 1 and r.uci == "", "cancelled at the review: only the probe ran")

# --branch beta, --yes, a router already at 10.0.0.1
r = e2e(lan="10.0.0.1", yes=True, branch="beta", guest_ssid="Cafe", no_browser=True)
check(r.rc == 0 and "https://beta.pisophone.pages.dev/install.sh" in r.wget and "guest=[Cafe]" in r.out and "set network.lan.ipaddr" not in r.uci,
      "--yes --branch beta on a router at 10.0.0.1: " + str(r.err) + r.out[-400:])
check(r.ssh.count("root@10.0.0.1") == 2 and r.opened == [], "probe and setup only; --no-browser")

# set up before: no questions, nothing sent, the router keeps its settings
for state in ("configured", "denied"):
    r = e2e(answers=("y", "n"), lan="10.0.0.1", probe=state)
    check(r.rc == 0 and "root=[] kiosk=[] box=[] guest=[] site=[]" in r.out and "keeps the names and passwords" in r.out,
          f"{state}: no new answers are sent: " + r.out[-400:])
check("press Enter" in r.out, "a router with a password: it says to type it")

# failures are explained
r = e2e(answers=("", "", "y"), secrets_=("", "", ""), lan="10.0.0.1", result="fail")
check(r.rc is None and "the coin box did not join the hidden PisoCoinBox network" in (r.err or "") and "run this script again" in r.err,
      "a failed setup: the reason, and that it can be run again: " + str(r.err))
r = e2e(answers=("", "", "y"), secrets_=("", "", ""), lan="10.0.0.1", result="checks")
check(r.err and "some checks failed" in r.err and r.saved, "failed checks: said, and the summary is still saved")
r = e2e(answers=("", "", "y"), secrets_=("", "", ""), result="ok", moved=False)
check(r.err and "does not answer at 10.0.0.1" in r.err, "the router never answers at 10.0.0.1: what to do")
r = e2e(up=lambda h: False)
check(r.err and "no router found" in r.err and "LAN" in r.err, "no router: where to plug the cable")
r = e2e(probe="notopenwrt")
check(r.err and "not an OpenWrt router" in r.err, "another device at the address is refused")
shutil.rmtree(f"{site}/main")
r = e2e(answers=("", "", "y"), secrets_=("", "", ""))
check(r.err and "could not be prepared" in r.err and "could not download the installer" in r.out.lower(), "no internet on the router: said: " + r.out[-300:])
shutil.copytree(f"{site}/beta", f"{site}/main")

# --update
r = e2e(lan="10.0.0.1", probe="denied", update=True)
check(r.rc == 0 and "SETUP-RAN args=[--yes update]" in r.out and "UPDATE DONE" in r.out, "--update runs the installer's update: " + r.out[-300:])
r = e2e(lan="10.0.0.1", probe="factory", update=True)
check(r.err and "not been set up yet" in r.err, "--update on a router that was never set up: refused")

# main(): options and the missing ssh
sys.stdout, sys.stderr = io.StringIO(), io.StringIO()
try:
    bad = None
    try:
        ps.main(["--branch", "x;reboot"])
    except SystemExit as e:
        bad = e.code
    os.environ.pop("PISO_SSH")
    path = os.environ["PATH"]
    os.environ["PATH"] = tmp
    nossh = ps.main(["--yes"])
    os.environ["PATH"] = path
finally:
    sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
check(bad == 2, "a bad branch name is refused")
check(nossh == 2, "no ssh command: says how to get it")

shutil.rmtree(tmp, ignore_errors=True)
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
