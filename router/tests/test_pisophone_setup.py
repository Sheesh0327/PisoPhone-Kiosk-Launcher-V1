#!/usr/bin/env python3
"""Tests of setup/pisophone_setup.py, the router setup run from a computer, without a router: a fake ssh runs the remote
commands here with sh, against a fake router (fake wget serving the repository's files from a folder, fake uci, ubus and
jsonfilter), with the real one-line installer (setup/install.sh) and a stand-in setup file.
Run with:  python3 router/tests/test_pisophone_setup.py"""
import base64, hashlib, importlib.util, io, os, shutil, subprocess, sys, tempfile, time, types, urllib.error, urllib.parse

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
if remote == "piso-setup telegram":   # a conversation: the input is passed through as it comes, like real ssh
    env = dict(os.environ, PATH="{bindir}:" + os.environ["PATH"])
    sys.exit(subprocess.run(["sh", "-c", remote], env=env).returncode)
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
    a = dict(branch="main", update=False, router=None, guest_ssid=None, site_name=None, yes=False, out=outdir, no_browser=False,
             country="PH")
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
                          wait=lambda probe, renew: moved and probe("10.0.0.1"), open_page=lambda url, chromium=True: opened.append(url))
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
check(r.input == "routerpass1\nkioskpass1\nboxadmin99\nTindahan WiFi\nAling Nena\nPH\n", "the answers went over the connection's input: " + repr(r.input))
check(r.ssh.count("root@192.168.1.1") == 2 and r.ssh.count("root@10.0.0.1") == 1, "probe and installer at 192.168.1.1, setup at 10.0.0.1: " + r.ssh)
check("HostKeyAlias=pisophone-router" in r.ssh and "StrictHostKeyChecking=accept-new" in r.ssh, "one pinned host key for both addresses")
check("https://pisophone.pages.dev/install.sh" in r.wget and "https://pisophone.pages.dev/setup/piso-setup.sh" in r.wget, "main's installer and setup file")
summary = [f for f in r.saved if f.startswith("pisophone-summary-")]
sheet = [f for f in r.saved if f.startswith("pisophone-setup-sheet-")]
check(len(summary) == 1 and "Router password: routerpass1" in open(f"{r.outdir}/{summary[0]}").read(), "the summary is saved: " + str(r.saved))
check(len(sheet) == 1 and "sheet for Aling Nena" in open(f"{r.outdir}/{sheet[0]}").read(), "the setup sheet is saved")
check(r.opened == ["http://10.0.0.10/"], "the phone setup page opens at the end (here the box does not answer: its own page): " + str(r.opened))
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
check(r.rc is None and "the coin box did not join the hidden PisoCoinBox network" in (r.err or "") and "run it again" in r.err,
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

# --country, and a configured router still gets the country
r = e2e(lan="10.0.0.1", yes=True, country="SG", no_browser=True)
check(r.rc == 0 and r.input.endswith("\nSG\n"), "--country SG is sent: " + repr(r.input))
r = e2e(answers=("y", "n"), lan="10.0.0.1", probe="configured", country="JP")
check(r.input == "\n\n\n\n\nJP\n", "a configured router: only the country is sent: " + repr(r.input))

# Telegram: the router waits for a message to the bot, the owner confirms the chat
script("piso-setup", f"""[ "$1" = telegram ] || exit 9
read -r tok; read -r site
echo "token=[$tok] site=[$site]" >> {tmp}/tg.log
echo "Open your bot in Telegram and send it any message (for example /start). Waiting up to 2 minutes..."
echo "Got a message from chat 4242 (Evan)."
printf 'Is that you (alerts and commands will be accepted only from this chat)? [y/N] '
read -r a
case "$a" in y) echo "Done. A message was sent to your Telegram."; exit 0 ;; *) echo "Cancelled."; exit 1 ;; esac
""")
open(f"{tmp}/lan", "w").write("10.0.0.1")
for answer in (True, False):
    seen, lines = [], []
    ok = ps.connect_telegram(ps.Ssh(tmp), "10.0.0.1", "123456789:" + "A" * 35, "Aling Nena", lines.append,
                             lambda chat: seen.append(chat) or answer)
    check(ok is answer and seen == ["Got a message from chat 4242 (Evan)."], f"telegram, answered {answer}: {ok} {seen} {lines}")
check("token=[123456789:" in open(f"{tmp}/tg.log").read() and "site=[Aling Nena]" in open(f"{tmp}/tg.log").read(),
      "the token and the site went over the input")
check(ps.TELEGRAM_TOKEN.fullmatch("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw") and not ps.TELEGRAM_TOKEN.fullmatch("hello"),
      "bot token format")

# the summary the router sends back
SUMMARY = """PisoPhone setup summary (2026-10-07 10:00:00, setup file version abc)
Router (SSH / LuCI):   root@10.0.0.1        password: Rpass2345678
Kiosk Wi-Fi:           PisoKiosk (HIDDEN)   password: Kpass234567    (rental phones only; ...)
PisoWiFi (customers):  Tindahan WiFi   (open; rename with: piso-setup wifi-name "New Name")
Coin box:              http://10.0.0.10      admin password: Bpass23456789    hidden Wi-Fi: PisoCoinBox (only MAC x)
"""
found = ps.parse_summary(SUMMARY)
check(found == {"ROOT_PASSWORD": "Rpass2345678", "KIOSK_PASSWORD": "Kpass234567", "GUEST_SSID": "Tindahan WiFi",
                "BOX_NEW_ADMIN_PASSWORD": "Bpass23456789"}, "the summary is read: " + str(found))
check("ROOT_PASSWORD" not in ps.parse_summary(SUMMARY.replace("password: Rpass2345678", "password: NOT SET by this setup")),
      "a router password the setup did not set is not shown as one")

# the password helper of the window mode: ssh runs it and reads the password from it
s2 = ps.Ssh(tmp)
s2.set_password("pa&ss|w<rd%^")
helper = s2.env["SSH_ASKPASS"]
out = subprocess.run([helper, "root@10.0.0.1's password:"], env=s2.env, capture_output=True, text=True).stdout
check(out == "pa&ss|w<rd%^\n" and s2.env["SSH_ASKPASS_REQUIRE"] == "force", "the helper answers with the password: " + repr(out))
check("NumberOfPasswordPrompts=1" in s2.argv("10.0.0.1", "true"), "one try only: a wrong password fails at once")
out = subprocess.run([sys.executable, f"{ROOT}/setup/pisophone_setup.py"], env=dict(os.environ, PISO_ASKPASS_MODE="1", PISO_ASKPASS_PW="x&y"),
                     capture_output=True, text=True).stdout
check(out == "x&y\n", "started as the helper (Windows), the program prints only the password")

# the coin box's phone setup page: read from the box's admin page, filled in, opened
class FakeResponse:
    status = 200

    def __init__(self, body): self.body = body.encode()
    def read(self): return self.body
    def __enter__(self): return self
    def __exit__(self, *a): return False


PAGE = '<script>window.PISO_CFG = { mac: "AA:BB:CC:DD:EE:0F", secret: "box_secret-12345678" };</script>'
seen_req = []


def fake_open(req, timeout=0):
    seen_req.append(req)
    return FakeResponse(PAGE)


link = ps.fetch_box_link("10.0.0.10", "boxadmin99", opener=fake_open)
check(link == {"mac": "AA:BB:CC:DD:EE:0F", "secret": "box_secret-12345678"}, "the box's MAC and secret are read from its admin page")
check(seen_req[0].full_url == "http://10.0.0.10/" and base64.b64decode(seen_req[0].get_header("Authorization").split()[1]) == b"admin:boxadmin99",
      "logged in as admin with the box password")
for bad in (urllib.error.HTTPError("u", 401, "no", {}, None), urllib.error.URLError("timed out")):
    def failing(req, timeout=0, bad=bad): raise bad
    try:
        ps.fetch_box_link("10.0.0.10", "x", opener=failing)
        check(False, "a box that refuses or does not answer")
    except ps.SetupError as e:
        check("admin password" in str(e) or "does not answer" in str(e), "a box that refuses or does not answer: " + str(e))
try:
    ps.fetch_box_link("10.0.0.10", "x", opener=lambda req, timeout=0: FakeResponse("<html>router</html>"))
    check(False, "another page than the box's")
except ps.SetupError as e:
    check("did not show the coin box" in str(e), "another page than the box's admin page")
url = ps.provisioning_url("main", link, 2, "Kpass 123&x")
frag = urllib.parse.parse_qs(urllib.parse.urlsplit(url).fragment)
check(url.startswith("https://pisophone.pages.dev/#") and "?" not in url and frag["mac"] == ["AA:BB:CC:DD:EE:0F"] and frag["slot"] == ["2"]
      and frag["secret"] == ["box_secret-12345678"] and frag["ip"] == ["10.0.0.10"] and frag["wifi_pass"] == ["Kpass 123&x"] and frag["name"] == ["PisoPhone 2"],
      "the setup page's address: everything after the #, nothing in the query: " + url)
check("beta.pisophone.pages.dev" in ps.provisioning_url("beta", link, 1, ""), "a branch's own website")
opened_urls = []
kind, note = ps.open_provisioning("main", {"BOX_NEW_ADMIN_PASSWORD": "boxadmin99", "KIOSK_PASSWORD": "Kpass123456"}, 3,
                                  fetch=lambda host, pw: link, opener=lambda u, chromium=True: opened_urls.append((u, chromium)))
check(kind == "setup" and note == "" and opened_urls[0][0].startswith("https://pisophone.pages.dev/#") and "slot=3" in opened_urls[0][0]
      and "wifi_pass=Kpass123456" in opened_urls[0][0], "the filled-in setup page is opened")
opened_urls.clear()


def refusing(host, pw): raise ps.SetupError("the coin box did not accept the admin password")


kind, note = ps.open_provisioning("main", {"BOX_NEW_ADMIN_PASSWORD": "wrong"}, 1, fetch=refusing, opener=lambda u, chromium=True: opened_urls.append((u, chromium)))
check(kind == "box" and "did not accept" in note and opened_urls == [("http://10.0.0.10/", False)], "no luck reading the box: its own page opens, with the reason")
opened_urls.clear()
kind, note = ps.open_provisioning("main", {}, 1, fetch=refusing, opener=lambda u, chromium=True: opened_urls.append((u, chromium)))
check(kind == "box" and "not known" in note, "no admin password known: the box's own page")
kind, note = ps.open_provisioning("main", {"BOX_NEW_ADMIN_PASSWORD": "x"}, 1, fetch=lambda h, p: {"mac": "00:00:00:00:00:00", "secret": ""},
                                  opener=lambda u, chromium=True: opened_urls.append((u, chromium)))
check(kind == "box", "a box without an address yet: its own page")

# the browser: Chrome or Edge first (Web Serial and WebUSB), else the default one
launched, fell = [], []
check(ps.open_url("https://x/", find=lambda: "/usr/bin/chrome", launch=lambda cmd, **kw: launched.append(cmd), fallback=fell.append) == "chromium"
      and launched == [["/usr/bin/chrome", "https://x/"]] and not fell, "Chrome or Edge opens the page when there is one")
check(ps.open_url("https://x/", find=lambda: None, launch=lambda cmd, **kw: launched.append(cmd), fallback=fell.append) == "default" and fell == ["https://x/"],
      "else the default browser")


def no_launch(cmd, **kw): raise OSError("cannot start")


check(ps.open_url("https://y/", find=lambda: "/x/chrome", launch=no_launch, fallback=fell.append) == "default" and fell[-1] == "https://y/",
      "a browser that cannot start: the default one")
check(ps.open_url("http://10.0.0.10/", chromium=False, find=lambda: "/x/chrome", launch=no_launch, fallback=fell.append) == "default", "any browser when Chromium is not needed")
fakebin = tempfile.mkdtemp(dir=tmp)
open(f"{fakebin}/microsoft-edge", "w").write("#!/bin/sh\n")
os.chmod(f"{fakebin}/microsoft-edge", 0o755)
old_path = os.environ["PATH"]
os.environ["PATH"] = fakebin
if os.name != "nt" and sys.platform != "darwin":
    check(ps.chromium_path() == f"{fakebin}/microsoft-edge", "Edge is found on the path")
os.environ["PATH"] = old_path

# the checks of the first page
pf = ps.preflight("main", opener=lambda req, timeout=0: FakeResponse("{}"), find=lambda: "/x/chrome")
check([c[1] for c in pf][1:] == [True, True], "preflight: the website and the browser are found: " + str(pf))
pf2 = ps.preflight("main", opener=lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.URLError("offline")), find=lambda: None)
check(pf2[1][1] is False and pf2[2][1] is False and "Chrome" in pf2[2][2], "preflight: no internet, no Chrome: said")
check(ps.box_online("10.0.0.10", probe=lambda h, p: (h, p) == ("10.0.0.10", 80)) is True, "the box is online when its page port answers")

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
