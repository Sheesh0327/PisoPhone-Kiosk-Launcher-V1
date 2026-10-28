#!/usr/bin/env python3
"""Tests of setup/pisophone_setup.py, the router setup run from a computer, without a router: a fake ssh runs the remote
commands here with sh, against a fake router (fake wget serving the repository's files from a folder, fake uci, ubus and
jsonfilter), with the real one-line installer (setup/install.sh) and a stand-in setup file.
Run with:  python3 router/tests/test_pisophone_setup.py"""
import base64, hashlib, importlib.util, io, json, os, re, shutil, subprocess, sys, tempfile, time, types, urllib.error, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)

if os.name == "nt" and not shutil.which("sh"):
    for git_dir in [r"C:\Program Files\Git\bin", r"E:\Git\bin", r"C:\Program Files\Git\usr\bin", r"E:\Git\usr\bin"]:
        if os.path.isdir(git_dir):
            os.environ["PATH"] = git_dir + os.pathsep + os.environ["PATH"]
            break

tmp = tempfile.mkdtemp()
os.environ["PISOPHONE_HOME"] = os.path.join(tmp, "home")
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


# ---- what is typed -------------------------------------------------------------------------------------------------------
check(open(f"{ROOT}/setup/pisophone_setup.py", "rb").read() == open(f"{ROOT}/website/pisophone_setup.py", "rb").read(),
      "the website serves the same setup program as setup/pisophone_setup.py (copy one over the other after editing)")
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
check(ps.find_router(lambda h: False, gateways=lambda: []) is None, "no router: None")
# feature 032 (SU-04): a router with another factory address is found at the gateway the cable's lease names
WINDOWS_ROUTES = """IPv4 Route Table
Active Routes:
Network Destination        Netmask          Gateway       Interface  Metric
          0.0.0.0          0.0.0.0      192.168.8.1    192.168.8.50     25
          0.0.0.0          0.0.0.0         On-link     192.168.1.20    300
        127.0.0.0        255.0.0.0         On-link         127.0.0.1    331
"""
LINUX_ROUTES = "default via 192.168.8.1 dev eth0 proto dhcp src 192.168.8.50 metric 100\n192.168.8.0/24 dev eth0 proto kernel scope link src 192.168.8.50\ndefault via 192.168.1.1 dev wlan0 metric 600\n"
MAC_ROUTES = "Routing tables\n\nInternet:\nDestination        Gateway            Flags           Netif Expire\ndefault            192.168.8.1        UGScg             en0\n127                127.0.0.1          UCS               lo0\n"
for label, text in (("Windows", WINDOWS_ROUTES), ("Linux", LINUX_ROUTES), ("macOS", MAC_ROUTES)):
    got = ps.parse_gateways(text)
    check(got[0] == "192.168.8.1" and all(re.fullmatch(r"\d+\.\d+\.\d+\.\d+", g) for g in got), f"{label}: the default gateway is read from the routes: {got}")
check(ps.parse_gateways("") == [] and ps.parse_gateways("default via fe80::1 dev eth0") == [], "no routes, or only an IPv6 one: nothing")
seen_probe = []
found = ps.find_router(lambda h: seen_probe.append(h) or h == "192.168.8.1", gateways=lambda: ["192.168.8.1", "192.168.1.1"])
check(found == "192.168.8.1" and seen_probe == ["10.0.0.1", "192.168.1.1", "192.168.8.1"], f"a router at the lease's gateway is found; the usual two addresses are tried first once each: {seen_probe}")
check(ps.find_router(lambda h: h == "10.0.0.1", gateways=lambda: ["192.168.8.1"]) == "10.0.0.1", "10.0.0.1 is still preferred")
def broken_run(command, **kw): raise FileNotFoundError(command[0])
check(ps.default_gateways({"kind": "linux"}, run=broken_run) == [], "a missing route command: no gateways, no error")
ran_cmd = []
check(ps.default_gateways({"kind": "windows"}, run=lambda c, **kw: ran_cmd.append(c) or types.SimpleNamespace(stdout=WINDOWS_ROUTES.encode())) == ["192.168.8.1"] and ran_cmd[0][0] == "route",
      "Windows: asks route print")
# feature 032 (SU-01): two networks in the same range (the shop modem's Wi-Fi and the factory router's cable are both 192.168.1.x)
LINUX_ADDR = "1: lo    inet 127.0.0.1/8 scope host lo\n2: eth0    inet 192.168.1.50/24 brd 192.168.1.255 scope global dynamic eth0\n3: wlan0    inet 192.168.1.23/24 brd 192.168.1.255 scope global wlan0\n"
MAC_ADDR = "lo0: flags=8049<UP,LOOPBACK> mtu 16384\n\tinet 127.0.0.1 netmask 0xff000000\nen0: flags=8863<UP> mtu 1500\n\tinet 192.168.1.50 netmask 0xffffff00 broadcast 192.168.1.255\nen1: flags=8863<UP> mtu 1500\n\tinet 192.168.1.23 netmask 0xffffff00 broadcast 192.168.1.255\n"
WIN_ADDR = """Windows IP Configuration

Ethernet adapter Ethernet:

   Connection-specific DNS Suffix  . :
   IPv4 Address. . . . . . . . . . . : 192.168.1.50(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.0

Wireless LAN adapter Wi-Fi:

   IPv4 Address. . . . . . . . . . . : 192.168.1.23(Preferred)
   Subnet Mask . . . . . . . . . . . : 255.255.255.0
"""
for label, text in (("Linux", LINUX_ADDR), ("macOS", MAC_ADDR), ("Windows", WIN_ADDR)):
    got = ps.parse_interface_addresses(text)
    check(sorted(ip for _n, ip in got) == ["127.0.0.1", "192.168.1.23", "192.168.1.50"] or sorted(ip for _n, ip in got) == ["192.168.1.23", "192.168.1.50"],
          f"{label}: the address of every interface is read: {got}")
    advice = ps.network_clash_advice(got)
    check(advice and "192.168.1" in advice and "hotspot" in advice and "Wi-Fi" in advice, f"{label}: two interfaces in 192.168.1.x: stop with the hotspot advice: {advice}")
one_each = [("eth0", "192.168.1.50"), ("wlan0", "192.168.0.23"), ("lo", "127.0.0.1")]
check(ps.network_clash_advice(one_each) is None and ps.network_clash_advice([("eth0", "192.168.1.50")]) is None and ps.network_clash_advice([]) is None,
      "different ranges, one network, or nothing: no clash")
check(ps.network_clash_advice([("eth0", "192.168.1.50"), ("eth0", "192.168.1.51")]) is None, "one interface with two addresses is not two networks")
check(ps.local_interface_addresses({"kind": "linux"}, run=lambda c, **kw: types.SimpleNamespace(stdout=LINUX_ADDR.encode())) != [] and
      ps.local_interface_addresses({"kind": "linux"}, run=broken_run) == [], "the table is read with the system's command; a missing command gives an empty table")

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
tmp_p = tmp.replace("\\", "/")
fake_root_p = fake_root.replace("\\", "/")
bindir_p = bindir.replace("\\", "/")
site_p = site.replace("\\", "/")
for d in (bindir, site, f"{fake_root}/root", f"{fake_root}/tmp", f"{fake_root}/etc"):
    os.makedirs(d)


def script(name, body):
    open(f"{bindir}/{name}", "w", newline="\n").write("#!/bin/sh\n" + body)
    os.chmod(f"{bindir}/{name}", 0o755)


# wget -q -T 60 -O <file> <url>: the repository's raw files, from <site>/<branch>/...
script("wget", f"""out=""; while [ $# -gt 1 ]; do [ "$1" = -O ] && out="$2"; shift; done; url="$1"
echo "$url" >> {tmp_p}/wget.log
u="${{url#*://}}"; host="${{u%%/*}}"; p="${{u#*/}}"
case "$host" in pisophone.pages.dev) b=main ;; *.pisophone.pages.dev) b="${{host%.pisophone.pages.dev}}" ;; *) exit 8 ;; esac
[ -f "{site_p}/$b/$p" ] || exit 8
cat "{site_p}/$b/$p" > "$out"
""")
script("uci", f"""echo "$*" >> {tmp_p}/uci.log
[ "$1" = -q ] && shift
case "$1" in get) [ "$2" = network.lan.ipaddr ] && echo "$(cat {tmp_p}/lan)/24" ;; set) case "$2" in network.lan.ipaddr=*) echo "${{2#*=}}" > {tmp_p}/lan ;; esac ;; esac
exit 0
""")
script("ubus", "echo '{}'\n")
script("jsonfilter", 'echo "192.168.100.20"\n')
script("netinit", f'echo "$1" >> {tmp_p}/network.log\n')

# the stand-in setup file: shows what it was given, writes the summary, the sheet and the state file like the real one
FAKE_SETUP = f"""#!/bin/sh
PISO_RELEASE='9.9.9'
echo "  TIP: fake advice"
echo "LOCAL-PKGS=[$PISO_LOCAL_PKGS]"
echo "SETUP-RAN args=[$*] root=[$ROOT_PASSWORD] kiosk=[$KIOSK_PASSWORD] box=[$BOX_NEW_ADMIN_PASSWORD] guest=[$GUEST_SSID] site=[$SITE_NAME] product=[$SHOP_PRODUCT]"
R={fake_root_p}
case "$(cat {tmp_p}/result 2>/dev/null)" in
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
    open(f"{d}/piso-setup.sh", "w", newline="\n").write(FAKE_SETUP)
    open(f"{d}/piso-setup.sh.sha256", "w", newline="\n").write(hashlib.sha256(FAKE_SETUP.encode()).hexdigest() + "  piso-setup.sh\n")

# The fake ssh: logs its arguments and input, answers the login probe as told (FAKE_PROBE), runs everything else with sh as
# the router would, with the router's fixed paths moved into the fake router's folder.
open(f"{tmp}/fake_ssh.py", "w").write(f"""import os, subprocess, sys
argv = sys.argv[1:]
host = next(a for a in argv if a.startswith("root@"))[5:]
remote = argv[-1]
with open("{tmp_p}/ssh.log", "a") as f:
    f.write(repr(argv) + "\\n")
if "BatchMode=yes" in argv:
    probe = open("{tmp_p}/probe").read().strip()
    if probe == "denied":
        sys.stderr.write("root@" + host + ": Permission denied (publickey,password).\\n"); sys.exit(255)
    print("@@OPENWRT" if probe != "notopenwrt" else "hello")
    if probe == "configured": print("@@CONFIGURED")
    sys.exit(0)
lan = open("{tmp_p}/lan").read().strip()
if host != lan:
    sys.stderr.write("ssh: connect to host " + host + " port 22: Connection timed out\\n"); sys.exit(255)
need = "{tmp_p}/needpw"   # a router with a password: a login without it is refused
if os.path.exists(need) and os.environ.get("PISO_ASKPASS_PW") != open(need).read().strip():
    sys.stderr.write("root@" + host + ": Permission denied (publickey,password).\\n"); sys.exit(255)
if remote.startswith("echo @@RELEASE") and os.path.exists("{tmp_p}/feeds_output"):   # the offline read: what this router knows
    sys.stdout.write(open("{tmp_p}/feeds_output").read()); sys.exit(0)
if "ping -c 1 -W 3 1.1.1.1" in remote:   # does the router have internet
    sys.exit(0 if open("{tmp_p}/inet").read().strip() == "1" else 1)
remote = remote.replace("/tmp/piso", "{fake_root_p}/tmp/piso").replace("/root/piso", "{fake_root_p}/root/piso")
if remote == "piso-setup telegram":   # a conversation: the input is passed through as it comes, like real ssh
    env = dict(os.environ, PATH="{bindir_p}" + os.pathsep + os.environ["PATH"])
    sys.exit(subprocess.run(["sh", "-c", remote], env=env).returncode)
data = sys.stdin.buffer.read() if not sys.stdin.isatty() else b""
open("{tmp_p}/ssh-input.log", "ab").write(data)
env = dict(os.environ, PATH="{bindir_p}" + os.pathsep + os.environ["PATH"], PISO_INSTALL_DIR="{fake_root_p}/root", PISO_TEST_NONROOT="1",
           PISO_NETWORK_INIT="{bindir_p}/netinit", PISO_TTY="/dev/null", PISO_SELF_PATH="{fake_root_p}/no-such-piso-setup")
sys.exit(subprocess.run(["sh", "-c", remote], input=data, env=env).returncode)
""")
os.environ["PISO_SSH"] = f'"{sys.executable}" "{tmp_p}/fake_ssh.py"'


OFFLINE_SITE = None   # the offline-first tests point the computer at a local web server


def e2e(answers=(), secrets_=(), lan="192.168.1.1", probe="factory", result="ok", up=lambda h: True, moved=True, needpw=None,
        saved_pw=None, **opts):
    for f in ("needpw", "ssh.log", "ssh-input.log", "wget.log", "uci.log", "network.log"):
        if os.path.exists(f"{tmp}/{f}"):
            os.remove(f"{tmp}/{f}")
    for f in ("tmp/piso-setup.state", "root/piso-setup-summary.txt", "root/piso-handout.html"):
        if os.path.exists(f"{fake_root}/{f}"):
            os.remove(f"{fake_root}/{f}")
    open(f"{tmp}/lan", "w").write(lan)
    open(f"{tmp}/probe", "w").write(probe)
    open(f"{tmp}/result", "w").write(result)
    outdir = tempfile.mkdtemp(dir=tmp)
    if needpw:
        open(f"{tmp}/needpw", "w").write(needpw)
    if saved_pw:
        open(f"{outdir}/pisophone-router-password.txt", "w").write(f"Router (SSH / LuCI):   root@10.0.0.1        password: {saved_pw}\n")
    a = dict(branch="main", update=False, router=None, guest_ssid=None, site_name=None, yes=False, out=outdir, no_browser=False,
             country="PH", router_online=True)   # (these tests are the router-online path; the offline-first ones set router_online=False)
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
                          board_finder=lambda: list(BOARDS), flasher=lambda port, emit: FLASHED.append(port),
                          probe=lambda h: h == (open(f"{tmp}/lan").read().strip()) and up(h),
                          wait=lambda probe, renew: moved and probe("10.0.0.1"), open_page=lambda url, chromium=True: opened.append(url),
                          offline_site=OFFLINE_SITE)
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


# (the coin box over USB: none here; the flashing has its own tests below)
BOARDS, FLASHED = [], []
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc == 0 and r.err is None, f"factory router: set up ({r.err}): " + r.out[-800:])
check("set network.lan.ipaddr=10.0.0.1" in r.uci and open(f"{tmp}/lan").read().strip() == "10.0.0.1", "it was moved to 10.0.0.1")
check("SETUP-RAN args=[--yes] root=[routerpass1] kiosk=[kioskpass1] box=[boxadmin99] guest=[Tindahan WiFi] site=[Aling Nena]" in r.out,
      "the setup got every answer, unattended: " + r.out[-600:])
check("routerpass1" not in r.ssh and "kioskpass1" not in r.ssh and "boxadmin99" not in r.ssh, "no password on any command line")
check(r.input == "routerpass1\nkioskpass1\nboxadmin99\nTindahan WiFi\nAling Nena\nPH\nboth\n", "the answers went over the connection's input: " + repr(r.input))
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

# a router that has a password (set by an earlier run): the saved one is used by itself
r = e2e(lan="10.0.0.1", probe="denied", yes=True, needpw="Saved12345678", saved_pw="Saved12345678", no_browser=True)
check(r.rc == 0 and "Logged in to the router with the password saved in" in r.out and "press Enter" not in r.out and "SETUP-RAN" in r.out,
      "the saved router password is used by itself: " + str(r.err) + r.out[-500:])
check("Saved12345678" not in r.ssh, "and it is not on any command line")
r = e2e(lan="10.0.0.1", probe="denied", yes=True, needpw="Changed1234567", saved_pw="Saved12345678", no_browser=True)
check("was not accepted" in r.out and "Type the router password" in r.out, "a saved password that no longer works is said so, and ssh asks: " + r.out[-500:])
r = e2e(lan="10.0.0.1", probe="denied", yes=True, needpw="Saved12345678", no_browser=True)
check("Logged in to the router" not in r.out and "Type the router password" in r.out, "nothing saved: ssh asks as before")
# ---- feature 032 (SU-10): a refused ssh login is not "the connection was lost" ---------------------------------------------------------------
for typed, word in (("", "was not given"), ("Wrong12345678", "did not accept")):
    open(f"{tmp}/lan", "w").write("10.0.0.1")
    open(f"{tmp}/needpw", "w").write("Right12345678")
    open(f"{tmp}/result", "w").write("ok")
    ssh_ = ps.Ssh(tmp)
    ssh_.set_password(typed)
    out_ = tempfile.mkdtemp(dir=tmp)
    try:
        ps.install(ssh_, "10.0.0.1", {k: "Aa12345678" for k in ps.ANSWER_ORDER}, "main", out_, lambda line: None, lambda: True, online=True)
        msg = "(no error)"
    except ps.SetupError as e:
        msg = str(e)
    os.remove(f"{tmp}/needpw")
    check("lost" not in msg and "password" in msg and word in msg and "Router step" in msg, f"a refused login ({typed!r}) says which password is needed: {msg}")
# the password is saved before the router changes it, so a run that stops half way can be repeated
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"),
        result="fail")
check("pisophone-router-password.txt" in r.saved and "password: routerpass1" in open(f"{r.outdir}/pisophone-router-password.txt").read(),
      "the router password is saved before the setup runs, even when it fails: " + str(r.saved))
check(ps.saved_router_password(r.outdir)[0] == "routerpass1", "and found again from that folder")
check(ps.failure_message({"state": "FAILED the coin box did not join the hidden PisoCoinBox network"}).count("box-diag") == 1, "a coin box failure points to box-diag")

check(ps.tip_text("  TIP: keep the box powered.") == "keep the box powered." and ps.tip_text("== step") is None and ps.tip_text("TIP:") is None,
      "tip lines are recognised for the window")
r = e2e(lan="10.0.0.1", yes=True, no_browser=True)
check("  TIP: fake advice" in r.out and r.rc == 0, "tips pass through the streamed output: " + r.out[-300:])

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
check(r.rc == 0 and r.input.endswith("\nSG\nboth\n"), "--country SG is sent: " + repr(r.input))
r = e2e(answers=("y", "n"), lan="10.0.0.1", probe="configured", country="JP")
check(r.input == "\n\n\n\n\nJP\n\n", "a configured router: only the country is sent (no product was chosen): " + repr(r.input))

# Telegram: the router waits for a message to the bot, the owner confirms the chat
script("piso-setup", f"""[ "$1" = telegram ] || exit 9
read -r tok; read -r site
echo "token=[$tok] site=[$site]" >> {tmp_p}/tg.log
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
Kiosk Wi-Fi:           PisoKiosk-a1b2 (HIDDEN)   password: Kpass234567    (rental phones only; ...)
PisoWiFi (customers):  Tindahan WiFi   (open; rename with: piso-setup wifi-name "New Name")
Coin box:              http://10.0.0.10      admin password: Bpass23456789    hidden Wi-Fi: PisoCoinBox (only MAC x)
"""
found = ps.parse_summary(SUMMARY)
check(found == {"ROOT_PASSWORD": "Rpass2345678", "KIOSK_PASSWORD": "Kpass234567", "KIOSK_SSID_NAME": "PisoKiosk-a1b2", "GUEST_SSID": "Tindahan WiFi",
                "BOX_NEW_ADMIN_PASSWORD": "Bpass23456789"}, "the summary is read, with this router's own kiosk network name: " + str(found))
check(ps.parse_summary(SUMMARY.replace("PisoKiosk-a1b2", "PisoKiosk"))["KIOSK_SSID_NAME"] == "PisoKiosk", "a router with the old shared name")
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
frag = urllib.parse.parse_qs(urllib.parse.urlsplit(ps.provisioning_url("main", link, 2, "Kpass123456", wifi_ssid="PisoKiosk-a1b2")).fragment)
check(frag["wifi_ssid"] == ["PisoKiosk-a1b2"] and frag["wifi_pass"] == ["Kpass123456"], "the setup page gets this router's own kiosk network name")
check("wifi_ssid" not in urllib.parse.urlsplit(ps.provisioning_url("main", link, 2, "Kpass123456", wifi_ssid="PisoKiosk")).fragment,
      "and nothing for the old shared name (the page assumes it)")
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
pf = ps.preflight("main", opener=lambda req, timeout=0: FakeResponse("{}"), tool=lambda: object(), which=lambda n: "/usr/bin/ssh")
check([c[1] for c in pf] == [True, True, True] and len(pf) == 3, "preflight: ssh, the website and the flashing tool are found: " + str(pf))
saved_ssh = os.environ.pop("PISO_SSH", None)
pf2 = ps.preflight("main", opener=lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.URLError("offline")), tool=lambda: None,
                   which=lambda n: None)
if saved_ssh:
    os.environ["PISO_SSH"] = saved_ssh
check(pf2[0][1] is False and pf2[1][1] is False and pf2[2][1] is False and "esptool" in pf2[2][2], "preflight: nothing found: all said")
seen = []
def second_url_works(req, timeout=0):
    seen.append((req.full_url, req.get_header("User-agent")))
    if req.full_url.endswith("app.json"):
        raise urllib.error.HTTPError(req.full_url, 403, "Forbidden", {}, None)
    return FakeResponse("{}")
pf3 = ps.preflight("main", opener=second_url_works, tool=lambda: object(), which=lambda n: "/usr/bin/ssh")
check(pf3[1][1] is True and len(seen) == 2 and all(ua == ps.USER_AGENT for _u, ua in seen),
      "preflight: the website is found by its second address when the first is refused, and says who is asking: " + str(seen))
pf4 = ps.preflight("main", opener=lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.URLError("[SSL: CERTIFICATE_VERIFY_FAILED] unable to get local issuer certificate")),
                   tool=lambda: object(), which=lambda n: "/usr/bin/ssh")
check(pf4[1][1] is False and "CERTIFICATE_VERIFY_FAILED" in pf4[1][2] and "inspecting HTTPS" in pf4[1][2], "preflight: the real error is shown, with a hint for a certificate problem: " + pf4[1][2])
pf5 = ps.preflight("main", opener=lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.HTTPError(req.full_url, 403, "Forbidden", {}, None)), tool=lambda: object(), which=lambda n: "/usr/bin/ssh")
check("HTTP 403" in pf5[1][2], "preflight: an HTTP error is named: " + pf5[1][2])
check(ps.box_online("10.0.0.10", probe=lambda h, p: (h, p) == ("10.0.0.10", 80)) is True, "the box is online when its page port answers")

# ---- feature 010: the product choice (PisoWiFi, PisoPhone or both), nothing preselected --------------------------------------------
check("SHOP_PRODUCT" in ps.ANSWER_ORDER, "SHOP_PRODUCT travels to the router with the other answers (ANSWER_ORDER)")
check("IFS= read -r SHOP_PRODUCT" in ps.setup_command("main") and "export " in ps.setup_command("main") and "SHOP_PRODUCT" in ps.setup_command("main").split("export ")[1].split(";")[0],
      "the router command reads and exports SHOP_PRODUCT")
ns = lambda **k: types.SimpleNamespace(**dict(dict(product=None, phone_only=False, yes=False), **k))
check(ps.product_choice(ns(product="wifi")) == "wifi" and ps.product_choice(ns(product="both")) == "both" and ps.product_choice(ns(product="phone")) == "phone", "--product wifi, phone, both")
check(ps.product_choice(ns(phone_only=True)) == "phone", "--phone-only stays and means --product phone")
for bad in (ns(product="wifi", phone_only=True), ns(product="both", phone_only=True)):
    try:
        ps.product_choice(bad)
        check(False, "--phone-only with another product is refused")
    except ps.SetupError:
        check(True, "")
try:
    ps.product_choice(ns(yes=True))
    check(False, "--yes without a product is refused")
except ps.SetupError as e:
    check("choose the product: --product wifi, phone or both" in str(e), "--yes without a product stops and says what to choose: " + str(e))
asked = []
sys.stdout = io.StringIO()
try:
    got = ps.product_choice(ns(), lambda prompt="": (asked.append(prompt), ["", "9", "tea", "3"][len(asked) - 1])[1])
finally:
    shown, sys.stdout = sys.stdout.getvalue(), sys.__stdout__
check(got == "both" and len(asked) == 4, "terminal: nothing preselected (Enter and nonsense ask again), 3 is both: " + str(asked))
check("PisoWiFi" in shown and "PisoPhone" in shown and "both" in shown.lower() and "[" not in "".join(asked), "the three products are listed; the prompt shows no default")
for word, want in (("1", "wifi"), ("wifi", "wifi"), ("2", "phone"), ("phone", "phone"), ("both", "both")):
    sys.stdout = io.StringIO()
    try:
        check(ps.product_choice(ns(), reader(word)) == want, f"typing {word!r} chooses {want}")
    finally:
        sys.stdout = sys.__stdout__
# answers: the rental-phone Wi-Fi password only for both
a = ps.collect_answers(types.SimpleNamespace(yes=True, guest_ssid="Cafe", site_name=None, product="wifi"))
check(a["SHOP_PRODUCT"] == "wifi" and a["KIOSK_PASSWORD"] == "" and len(a["ROOT_PASSWORD"]) == 14, "--yes, wifi: no rental-phone password is made; SHOP_PRODUCT=wifi")
ps.check_answers(a)
a = ps.collect_answers(types.SimpleNamespace(yes=True, guest_ssid="Cafe", site_name=None, product="both"))
check(a["SHOP_PRODUCT"] == "both" and len(a["KIOSK_PASSWORD"]) == 12, "--yes, both: the password is made")
sys.stdout = io.StringIO()
try:
    a = ps.collect_answers(types.SimpleNamespace(yes=False, guest_ssid=None, site_name=None, product="wifi"), reader("", ""),
                           reader("routerpass1", "routerpass1", "boxadmin99", "boxadmin99"))
finally:
    sys.stdout = sys.__stdout__
check(a["KIOSK_PASSWORD"] == "" and a["ROOT_PASSWORD"] == "routerpass1" and a["BOX_NEW_ADMIN_PASSWORD"] == "boxadmin99", "terminal, wifi: the rental-phone password is not asked")
sys.stdout = io.StringIO()
try:
    ps.review(a)
finally:
    shown, sys.stdout = sys.stdout.getvalue(), sys.__stdout__
check("Rental-phone" not in shown and "PisoKiosk" not in shown and "Public Wi-Fi" in shown, "the review of a wifi setup has no rental-phone lines: " + shown)
k = ps.kept_answers("PH", "wifi")
check(k["SHOP_PRODUCT"] == "wifi" and ps.kept_answers("PH")["SHOP_PRODUCT"] == "", "a router that is set up already is sent the product only when one was chosen")
# main: --product phone runs the PisoPhone-only path; --yes without a product changes nothing
called = []
saved_run, saved_dep = ps.run_phone_setup, ps.ensure_dependencies
ps.run_phone_setup = lambda args, *x, **y: called.append(args.phone_only)
ps.ensure_dependencies = lambda *x, **y: None
sys.stdout, sys.stderr = io.StringIO(), io.StringIO()
try:
    rc_phone = ps.main(["--product", "phone", "--no-install"])
    rc_yes = ps.main(["--yes", "--no-install"])
    out_yes = sys.stdout.getvalue()
finally:
    sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
    ps.run_phone_setup, ps.ensure_dependencies = saved_run, saved_dep
check(rc_phone == 0 and called == [True], "--product phone runs the --phone-only path: " + str(called))
check(rc_yes == 1 and "choose the product: --product wifi, phone or both" in out_yes and called == [True], "--yes without --product: stops, nothing runs: " + out_yes)
# the router setup carries the product
for prod, kiosk_secrets in (("wifi", ()), ("both", ("kioskpass1", "kioskpass1"))):
    secrets_ = ("routerpass1", "routerpass1") + kiosk_secrets + ("boxadmin99", "boxadmin99")
    r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=secrets_, product=prod)
    check(r.rc == 0 and f"product=[{prod}]" in r.out, f"{prod}: the router setup gets SHOP_PRODUCT={prod}: " + r.out[-400:])
    if prod == "wifi":
        check("kiosk=[]" in r.out and "Rental-phone" not in r.out, "wifi: the router gets no rental-phone password")
    else:
        check("kiosk=[kioskpass1]" in r.out, "both: the router gets the rental-phone password")

# ---- QR codes and the Wi-Fi join card -------------------------------------------------------------------------------------------
check(ps.wifi_qr_text("PisoKiosk", "3hC4RATnpQMJ") == "WIFI:T:WPA;S:PisoKiosk;P:3hC4RATnpQMJ;H:true;;", "the Wi-Fi QR text: " + ps.wifi_qr_text("PisoKiosk", "3hC4RATnpQMJ"))
check(ps.wifi_qr_text("My;Net", "a:b,c\\d") == "WIFI:T:WPA;S:My\\;Net;P:a\\:b\\,c\\\\d;H:true;;", "special characters are escaped: " + ps.wifi_qr_text("My;Net", "a:b,c\\d"))
check(ps.wifi_qr_text("Net", "12345678") == 'WIFI:T:WPA;S:Net;P:"12345678";H:true;;', "a password of only hex digits is quoted")
check(ps.wifi_qr_text("Net", "pw123456", hidden=False) == "WIFI:T:WPA;S:Net;P:pw123456;;", "a visible network has no H:true")
try:
    ps.qr_matrix("x" * 214)
    check(False, "a text that is too long is refused")
except ValueError:
    check(True, "a text that is too long is refused")
m = ps.qr_matrix("hello")
check(len(m) == 21 and all(len(r) == 21 for r in m) and m[0][:7] == [True] * 7 and m[6][:7] == [True] * 7, "version 1 is 21 x 21 with its finder pattern")
check(len(ps.qr_matrix("x" * 14)) == 21 and len(ps.qr_matrix("x" * 15)) == 25 and len(ps.qr_matrix("x" * 213)) == 57, "the version grows with the text")

try:
    import zxingcpp
    from PIL import Image
except ImportError:
    zxingcpp = None
    print("note: zxing-cpp / pillow are not installed: the QR codes were not decoded")


def raster(matrix, scale=6, quiet=4):
    n = len(matrix) + 2 * quiet
    img = Image.new("L", (n * scale, n * scale), 255)
    px = img.load()
    for r, row in enumerate(matrix):
        for c, v in enumerate(row):
            if v:
                for dy in range(scale):
                    for dx in range(scale):
                        px[(c + quiet) * scale + dx, (r + quiet) * scale + dy] = 0
    return img


if zxingcpp:
    import random as _random
    rng = _random.Random(3)
    texts = ["a", ps.wifi_qr_text("PisoKiosk", "3hC4RATnpQMJ"), "http://10.0.0.10/"]
    for n in (13, 14, 15, 25, 26, 27, 41, 42, 43, 61, 62, 63, 83, 84, 85, 105, 106, 107, 121, 122, 123, 151, 152, 153, 179, 180, 181, 210, 213):
        texts.append("".join(rng.choice("abcXYZ019 ;:,\\\"-_/.\u00e9") for _ in range(n)))
    bad = [len(t.encode()) for t in texts if len(t.encode()) <= 213 and (not zxingcpp.read_barcodes(raster(ps.qr_matrix(t))) or zxingcpp.read_barcodes(raster(ps.qr_matrix(t)))[0].text != t)]
    check(not bad, f"an independent QR reader reads our codes back, from version 1 to 10 (failures at lengths {bad})")

card = ps.wifi_card_html("Aling <Nena> & Co", "PisoKiosk", "3hC4RATnpQMJ", stamp="2026-10-12")
check("3hC4RATnpQMJ" in card and "PisoKiosk" in card and "http://10.0.0.10/" in card and "<svg" in card and card.count("<svg") == 2, "the card shows the network, the password, the address and two QR codes")
check("Aling &lt;Nena&gt; &amp; Co" in card and "<Nena>" not in card, "the shop name is escaped")
check(card.count("data:image/png;base64,") == 2 and "http" not in card.replace("http://10.0.0.10/", "").replace("http://www.w3.org/2000/svg", ""),
      "the card carries the TechNet logo and mascot inside the file and loads nothing from outside")
check("@@LOGO@@" in ps.PAGE and "@@MASCOT@@" in ps.PAGE and ps.LOGO_URI.startswith("data:image/png;base64,iVBOR"), "the setup page has the TechNet logo and mascot slots")
check("@page" in card and "Keep this page private" in card and "Hidden network" in card, "it is print-ready, with the hand steps and the privacy note")
check(card.count('<rect x=') > 100 and 'viewBox="0 0 ' in card, "the QR codes are drawn as pictures inside the page (nothing to download)")
try:
    from playwright.sync_api import sync_playwright
    import glob as _glob
    chrome = os.environ.get("CHROME") or next(iter(_glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome")), None)
    if zxingcpp:
        page_file = os.path.join(tmp, "card.html")
        open(page_file, "w").write(card)
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
            pg = browser.new_page(viewport={"width": 900, "height": 1200})
            pg.goto("file://" + page_file)
            shot = os.path.join(tmp, "card.png")
            pg.screenshot(path=shot, full_page=True)
            pdf_ok = True
            try:
                pg.pdf(path=os.path.join(tmp, "card.pdf"), format="A4", print_background=True)
            except Exception:
                pdf_ok = False
            browser.close()
        decoded = sorted(r.text for r in zxingcpp.read_barcodes(Image.open(shot)))
        check(decoded == sorted(["http://10.0.0.10/", ps.wifi_qr_text("PisoKiosk", "3hC4RATnpQMJ")]),
              "a photo-like screenshot of the page: both QR codes read back: " + str(decoded))
        if pdf_ok:
            check(os.path.getsize(os.path.join(tmp, "card.pdf")) > 2000, "the page prints to a PDF")
except ImportError:
    print("note: playwright is not installed: the card was not drawn in a browser")

# ---- the coin box over USB ------------------------------------------------------------------------------------------------
Port = types.SimpleNamespace
check(ps.usb_boards(lambda: [Port(device="COM7", vid=0x1A86, description="USB-SERIAL CH340"), Port(device="COM1", vid=None, description="Communications Port"),
                             Port(device="COM3", vid=0x303A, description="USB JTAG/serial debug unit"), Port(device="COM9", vid=0x046D, description="Mouse")])
      == [("COM3", "USB JTAG/serial debug unit"), ("COM7", "USB-SERIAL CH340")], "only the USB serial chips of ESP32 boards are boards")
check(ps.usb_boards(lambda: []) == [], "no board plugged in")
for text, want in (("Chip is ESP32-C3 (QFN32) (revision v0.4)", "ESP32-C3"), ("Chip type:          ESP32-C3 (revision v0.4)", "ESP32-C3"),
                   ("Chip is ESP32-D0WDQ6 (revision v1.0)", "ESP32"), ("Connected to ESP32 on COM5:", "ESP32"),
                   ("Chip is ESP32-S3 (revision v0.1)", "ESP32-S3")):
    m = ps.CHIP_RE.search(text)
    check(m and m.group(1).upper() == want, f"chip {text!r} is {want}: {m and m.group(1)}")

IMG_C3, IMG_32 = b"\xe9" + b"c" * 2000, b"\xe9" + b"d" * 3000
MANIFEST = {"version": "9.9.9", "builds": [
    {"env": "esp32-c3-dev", "chip": "ESP32-C3", "file": "esp32-c3-dev.bin", "offset": 0, "size": len(IMG_C3), "sha256": hashlib.sha256(IMG_C3).hexdigest()},
    {"env": "esp32dev-dev", "chip": "ESP32", "file": "esp32dev-dev.bin", "offset": 4096, "size": len(IMG_32), "sha256": hashlib.sha256(IMG_32).hexdigest()}]}


class BinResponse(FakeResponse):
    def __init__(self, body): self.body = body


def website(requests, files=None, manifest=None):
    """A fake website: records what was asked and serves the manifest and the images."""
    import json as _json
    served = {"flash/manifest.json": _json.dumps(manifest or MANIFEST).encode(), "flash/esp32-c3-dev.bin": IMG_C3, "flash/esp32dev-dev.bin": IMG_32}
    served.update(files or {})

    def opener(req, timeout=0):
        path = req.full_url.split("pages.dev/", 1)[1]
        requests.append(path)
        if path not in served:
            raise urllib.error.URLError("offline")
        return BinResponse(served[path])
    return opener


class FakeEsptool:
    """Stands in for esptool: records the commands, prints what the real one prints."""

    def __init__(self, version, chip_line="Chip is ESP32-C3 (QFN32) (revision v0.4)", fail=None):
        self.__version__, self.calls, self.chip_line, self.fail = version, [], chip_line, fail

    def main(self, argv):
        self.calls.append(list(argv))
        if self.fail:
            print(self.fail)
            sys.exit(2)
        if "chip_id" in argv or "chip-id" in argv:
            print("esptool.py v4\nSerial port " + argv[1] + "\nConnecting....\n" + self.chip_line)
        else:
            sys.stdout.write("Writing at 0x00010000... (10 %)\rWriting at 0x00020000... (15 %)\rWriting at 0x00030000... (100 %)\n"
                             "Hash of data verified.\n")


cache = tempfile.mkdtemp(dir=tmp)
for ver, sep in (("4.8.1", "_"), ("5.5.0", "-")):
    reqs, lines = [], []
    tool = FakeEsptool(ver)
    chip, version = ps.flash_box("beta", "COM5", lines.append, cache, website(reqs), tool)
    check((chip, version) == ("ESP32-C3", "9.9.9"), f"esptool {ver}: flashed the ESP32-C3 build: {chip} {version}")
    cmds = tool.calls
    check(len(cmds) == 2 and cmds[0][-1] == "chip" + sep + "id", f"esptool {ver}: the chip is asked first: {cmds[0]}")
    w = cmds[1]
    check(w[:4] == ["--port", "COM5", "--baud", "460800"] and "write" + sep + "flash" in w and "-e" in w and "-z" in w, f"esptool {ver}: erase and write: {w}")
    check(w[-2] == "0x0" and w[-1].endswith("esp32-c3-dev.bin"), f"esptool {ver}: the image goes to the right offset: {w[-2:]}")
    check("--flash" + sep + "mode" in w and w[w.index("--flash" + sep + "mode") + 1] == "keep", f"esptool {ver}: the flash settings are kept")
    check(reqs == ["flash/manifest.json", "flash/esp32-c3-dev.bin"] or ver != "4.8.1", f"downloaded the manifest and the image: {reqs}")
    check(len([l for l in lines if "%" in l]) <= 3 and any("(100 %)" in l for l in lines), "progress is reported in steps, not every line: " + str([l for l in lines if "%" in l]))
check(not os.path.exists(os.path.join(cache, "esp32-c3-dev.bin")) and any(f.endswith("esp32-c3-dev.bin") for f in os.listdir(cache)),
      "the image is kept in the cache folder")

# the second time the image comes from the cache; the classic ESP32 gets its own image at 0x1000
reqs, lines = [], []
tool = FakeEsptool("4.8.1", chip_line="Chip is ESP32-D0WDQ6 (revision v1.0)")
ps.flash_box("main", "/dev/ttyUSB0", lines.append, cache, website(reqs), tool)
check(tool.calls[1][-2] == "0x1000" and tool.calls[1][-1].endswith("esp32dev-dev.bin") and reqs == ["flash/manifest.json", "flash/esp32dev-dev.bin"],
      "ESP32: its own image, at 0x1000")
reqs = []
ps.flash_box("main", "/dev/ttyUSB0", lambda l: None, cache, website(reqs), FakeEsptool("4.8.1", chip_line="Chip is ESP32-D0WDQ6 (revision v1.0)"))
check(reqs == ["flash/manifest.json"], "an image already downloaded and checked is not downloaded again: " + str(reqs))
# offline: the manifest kept from the last time is used
lines = []
ps.flash_box("main", "COM5", lines.append, cache, lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.URLError("offline")),
             FakeEsptool("4.8.1"))
check(any("kept from the last time" in l for l in lines), "no internet: the kept software list and image are used")


def flash_error(**kw):
    try:
        ps.flash_box("main", "COM5", lambda l: None, kw.pop("cache", tempfile.mkdtemp(dir=tmp)), kw.pop("opener", website([])), kw.pop("tool", FakeEsptool("4.8.1")))
    except ps.SetupError as e:
        return str(e)
    return None


bad_img = IMG_C3[:-1] + b"X"
err = flash_error(opener=website([], files={"flash/esp32-c3-dev.bin": bad_img}))
check(err and "checksum" in err, "an image that does not match its checksum is never written: " + str(err))
err = flash_error(tool=FakeEsptool("4.8.1", chip_line="Chip is ESP32-S3 (revision v0.1)"))
check(err and "ESP32-S3" in err and "ESP32-C3 and ESP32" in err, "an unsupported chip is named: " + str(err))
err = flash_error(tool=FakeEsptool("4.8.1", fail="A fatal error occurred: Failed to connect to ESP32-C3: No serial data received."))
check(err and "BOOT" in err and "DATA cable" in err, "a board that does not answer: what to try: " + str(err))
err = flash_error(tool=FakeEsptool("4.8.1", fail="Could not open COM5: PermissionError(13, 'Access is denied.')"))
check(err and "port" in err.lower() and "serial monitor" in err, "a port in use: said: " + str(err))
err = flash_error(opener=lambda req, timeout=0: (_ for _ in ()).throw(urllib.error.URLError("offline")))
check(err and "internet" in err, "no internet and nothing kept: said: " + str(err))
err = flash_error(opener=website([], manifest={"version": "1", "builds": [{"chip": "ESP32-C3", "file": "../x.bin", "offset": 0, "size": 1, "sha256": "0" * 64}]}))
check(err and "not valid" in err, "a file name that leaves the folder is refused: " + str(err))
orig = ps.esptool_module
ps.esptool_module = lambda: None
err = flash_error(tool=None)
ps.esptool_module = orig
check(err and "pip install esptool" in err, "esptool missing: how to get it: " + str(err))

# the terminal flow: a board is written before the router; --no-flash, several boards, none
BOARDS[:] = [("COM5", "CH340")]
FLASHED.clear()
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc == 0 and "Coin box board found: CH340 on COM5" in r.out, "a board on USB is found and the setup goes on: " + r.out[:600])
check(FLASHED == ["COM5"], "the board is written once: " + str(FLASHED))
FLASHED.clear()
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"), no_flash=True)
check(r.rc == 0 and FLASHED == [], "--no-flash writes nothing")
BOARDS[:] = [("COM5", "CH340"), ("COM6", "CP210x")]
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc != 0 and r.err and "several USB boards" in r.err and FLASHED == [], "two boards: asks to unplug one, writes nothing: " + str(r.err))
BOARDS[:] = []
r = e2e(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc == 0 and "No coin box board found on USB" in r.out, "no board: it says so and goes on with the router")

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
    nossh = ps.main(["--yes", "--product", "both", "--no-install"])
    os.environ["PATH"] = path
finally:
    sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
check(bad == 2, "a bad branch name is refused")
check(nossh == 2, "no ssh command: says how to get it")

# ---- the Check-up (read-only) and the QR code drawn in the terminal --------------------------------------------------------


class Feed:
    def __init__(self, body): self.body = body
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def read(self, n=-1): return self.body


def checkup(up=(), feed=b'{"version": "1.5.4", "rollout": 100}', box_net=None, alerts=None):
    def opener(request, timeout=0):
        if feed is None:
            raise urllib.error.URLError("offline")
        return Feed(feed)
    return {i["name"]: i for i in ps.run_checkup("main", probe=lambda h, p=22, timeout=2.0: (h, p) in up, opener=opener, box_net=box_net, alerts=alerts)}


everything = checkup(up={("10.0.0.1", 22), ("10.0.0.10", 80), ("10.0.0.95", 80)})
check([i["state"] for i in everything.values()] == ["ok"] * 4 and "1.5.4" in everything["Update feed"]["text"], "check-up: all four ok: " + str(everything))
none = checkup(feed=None)
check([none[n]["state"] for n in ("Router", "Coin box", "Update feed", "Print server")] == ["bad", "bad", "bad", "off"], "check-up: nothing answers: the print server is only 'off'")
check(all(none[n]["fix"] for n in ("Router", "Coin box", "Update feed", "Print server")), "check-up: every problem comes with its fix")
factory = checkup(up={("192.168.1.1", 22)})
check(factory["Router"]["state"] == "bad" and "not been set up" in factory["Router"]["text"], "check-up: a router at the factory address is not set up yet")
check(checkup(feed=b"not json")["Update feed"]["state"] == "bad" and checkup(feed=b"{}")["Update feed"]["state"] == "bad", "check-up: a broken update list is a problem, not a crash")

# feature 011 part B: the Check-up names a coin box network that still has the shared setup name
_router_up = {("10.0.0.1", 22), ("10.0.0.10", 80), ("10.0.0.95", 80)}
shared = checkup(up=_router_up, box_net=lambda: "PisoCoinBox")
net = shared.get("Coin box network")
check(net and net["state"] == "warn" and "shared setup name" in net["text"] and "piso-setup box-wifi-name" in net["fix"],
      "check-up: a box network still called PisoCoinBox is a notice with the command that fixes it: " + str(net))
check("Coin box network" not in checkup(up=_router_up, box_net=lambda: "PisoCoinBox-a1b2"), "check-up: a box network with its own name has no such line")
check("Coin box network" not in checkup(up=_router_up, box_net=lambda: None) and "Coin box network" not in checkup(up=_router_up), "check-up: no login (or no way to ask) means no such line")


# AO-06: the Check-up shows what the router's monitor wrote to alerts.txt (a shop without Telegram)
al = checkup(up=_router_up, alerts=lambda: ["2026-10-24 10:00 the portal program did not answer for 3 minutes and was restarted"])["Router alerts"]
check(al["state"] == "warn" and "was restarted" in al["text"] and al["fix"], "check-up: the router's alerts are a notice with the lines: " + str(al))
check(checkup(up=_router_up, alerts=lambda: [])["Router alerts"]["state"] == "ok", "check-up: no alerts is an ok line")
check("Router alerts" not in checkup(up=_router_up, alerts=lambda: None) and "Router alerts" not in checkup(up=_router_up), "check-up: no login (or not asked) means no such line")
check("Router alerts" not in checkup(up={("192.168.1.1", 22)}, alerts=lambda: ["x"]), "check-up: a router that does not answer at 10.0.0.1 is not asked for alerts")


# D1 (feature 015): the Owner tools move the oldest closed months of sales to the computer, checked, then off the router
class FakeRouterSsh:
    """Runs the commands against a folder that stands in for /etc/coinslot.d."""
    def __init__(self, folder, fail_cat=False):
        self.folder, self.fail_cat, self.commands = folder, fail_cat, []

    def run(self, host, cmd, *flags, **kw):
        self.commands.append(cmd)
        if self.fail_cat and cmd.startswith("cat "):
            return subprocess.CompletedProcess(cmd, 0, b"truncated", b"")   # a copy that does not match the router's file
        return subprocess.run(["sh", "-c", cmd.replace("/etc/coinslot.d", self.folder)], capture_output=kw.get("capture_output", False),
                              text=kw.get("text", False), stdin=subprocess.DEVNULL, check=False)


sales = tempfile.mkdtemp()
for name in ("revenue-2026-06.csv", "revenue-2026-07.csv", "revenue-2026-08.csv", "revenue.csv", "revenue-2026-08.csv.bak", "vouchers.txt"):
    with open(os.path.join(sales, name), "w") as f:
        f.write("rows of " + name + "\n" * 3)
dest = tempfile.mkdtemp()
moved = ps.move_sales_months(FakeRouterSsh(sales), "10.0.0.1", 2, dest)
check([m[0] for m in moved] == ["revenue-2026-06.csv", "revenue-2026-07.csv"], "sales months: the two OLDEST closed months are moved: " + str(moved))
check(sorted(os.listdir(dest)) == ["revenue-2026-06.csv", "revenue-2026-07.csv"] and open(os.path.join(dest, "revenue-2026-06.csv")).read().startswith("rows of revenue-2026-06"),
      "sales months: they are on this computer")
left = sorted(os.listdir(sales))
check(left == ["revenue-2026-08.csv", "revenue-2026-08.csv.bak", "revenue.csv", "vouchers.txt"], "sales months: only those two left the router; the newest closed month, the current file and other files stay: " + str(left))
dest2 = tempfile.mkdtemp()
bad = FakeRouterSsh(sales, fail_cat=True)
try:
    ps.move_sales_months(bad, "10.0.0.1", 1, dest2)
    err = ""
except ps.ToolError as e:
    err = str(e)
check("did not match" in err and os.path.exists(os.path.join(sales, "revenue-2026-08.csv")) and not any(c.startswith("rm ") for c in bad.commands),
      "sales months: a copy that does not match the router's file removes nothing: " + err)
try:
    ps.move_sales_months(FakeRouterSsh(tempfile.mkdtemp()), "10.0.0.1", 1, dest2)
    err = ""
except ps.ToolError as e:
    err = str(e)
check("no closed months" in err, "sales months: a router with no closed month says so: " + err)
try:
    ps.move_sales_months(FakeRouterSsh(sales), "10.0.0.1", 0, dest2)
    err = ""
except ps.ToolError as e:
    err = str(e)
check("at least 1" in err, "sales months: zero months is refused")


def _no_call():
    raise AssertionError("the router was asked although it does not answer")


check("Coin box network" not in checkup(up={("192.168.1.1", 22)}, box_net=_no_call) and "Coin box network" not in checkup(box_net=_no_call, feed=None),
      "check-up: the router is not asked when it does not answer at 10.0.0.1")


def _broken():
    raise OSError("ssh failed")


check("Coin box network" not in checkup(up=_router_up, box_net=_broken), "check-up: a failing question is no line, not a crash")


class _Ssh:
    def __init__(self, code=0, out="PisoCoinBox\n", exc=None):
        self.code, self.out, self.exc, self.calls = code, out, exc, []

    def run(self, host, remote, *extra, **kw):
        self.calls.append((host, remote, extra))
        if self.exc:
            raise self.exc
        return subprocess.CompletedProcess([], self.code, self.out, "")


_s = _Ssh()
check(ps.read_box_network(_s, "10.0.0.1") == "PisoCoinBox" and _s.calls[0][1] == "uci -q get wireless.box_ap.ssid" and "BatchMode=yes" not in _s.calls[0][2],
      "check-up: the box network name is read with one read-only uci command over the session's login: " + str(_s.calls))
check(ps.read_box_network(_Ssh(code=255, out=""), "10.0.0.1") is None and ps.read_box_network(_Ssh(exc=subprocess.TimeoutExpired("ssh", 60)), "10.0.0.1") is None,
      "check-up: a failed login or a timeout gives no name")
check("'Notice'" in open(ps.__file__).read(), "check-up page: a notice has its own label")

url = "http://192.168.1.50:8765"
check(ps.terminal_qr(url, tty=False) == "", "no QR code when the output is not a terminal")
art = ps.terminal_qr(url, tty=True)
rows = []
for line in art.split("\n"):
    cells = re.findall(r"\x1b\[(\d+);(\d+)m\u2580", line)
    rows.append([c[0] == "30" for c in cells])
    rows.append([c[1] == "40" for c in cells])
m = ps.qr_matrix(url)
q = 2
check(len(rows) >= len(m) + 2 * q and all(len(r) == len(m) + 2 * q for r in rows), "terminal QR: the size is the code plus a quiet zone")
check(all(rows[q + y][q + x] == m[y][x] for y in range(len(m)) for x in range(len(m))) and not any(rows[0]) and not any(r[0] for r in rows),
      "terminal QR: the modules are the ones of the QR encoder, with a white border")

# ---- feature 011 part A: the first setup without the router's internet, end to end ---------------------------------------------
import ast, gzip, http.server, threading   # noqa: E401

SERVED = {}


class WebSite(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        data = SERVED.get(self.path)
        self.send_response(404 if data is None else 200)
        self.send_header("Content-Length", str(len(data or b"")))
        self.end_headers()
        self.wfile.write(data or b"")


web = http.server.ThreadingHTTPServer(("127.0.0.1", 0), WebSite)
threading.Thread(target=web.serve_forever, daemon=True).start()
WEB = f"http://127.0.0.1:{web.server_address[1]}"


def pkg(name, depends="libc"):
    body = f"{name} ipk".encode()
    return (f"Package: {name}\nVersion: 1\nDepends: {depends}\nFilename: {name}_1_mipsel_24kc.ipk\nSize: {len(body)}\n"
            f"SHA256sum: {hashlib.sha256(body).hexdigest()}\n\n", f"{name}_1_mipsel_24kc.ipk", body)


def serve_offline_site(sources=None):
    SERVED.clear()
    sources = sources or {"openwrt_base": ["curl", "ca-bundle", "jsonfilter"], "openwrt_packages": ["opennds", "libmicrohttpd-no-ssl"]}
    for src, names in sources.items():
        text = ""
        for n in names:
            stanza_text, fname, body = pkg(n)
            text += stanza_text
            SERVED[f"/feeds/{src}/{fname}"] = body
        SERVED[f"/feeds/{src}/Packages"] = text.encode()
        SERVED[f"/feeds/{src}/Packages.sig"] = b"sig"
    SERVED["/install.sh"] = open(f"{site}/main/install.sh", "rb").read()
    SERVED["/setup/piso-setup.sh"] = open(f"{site}/main/setup/piso-setup.sh", "rb").read()
    SERVED["/setup/piso-setup.sh.sha256"] = open(f"{site}/main/setup/piso-setup.sh.sha256", "rb").read()
    return list(sources)


def router_knows(sources, installed="libc - 1\nuclient-fetch - 1\n", conf="option check_signature\n", mem=90000, apk="no", release="23.05.5"):
    feeds = "".join(f"src/gz {n} {WEB}/feeds/{n}\n" for n in sources)
    open(f"{tmp}/feeds_output", "w").write(
        f"@@RELEASE\nDISTRIB_RELEASE='{release}'\nDISTRIB_ARCH='mipsel_24kc'\nDISTRIB_TARGET='ramips/mt7621'\n@@FEEDS\n{feeds}@@INSTALLED\n{installed}"
        f"@@CONF\n{conf}@@USIGN\nyes\n@@KEYS\n/etc/opkg/keys/abcd\n@@APK\n{apk}\n@@MEM\n{mem}\n")


def commands(r):
    """The remote command of every ssh call, in order."""
    return [ast.literal_eval(line)[-1] for line in r.ssh.splitlines() if line.startswith("[")]


def offline_run(inet, **kw):
    open(f"{tmp}/inet", "w").write("1" if inet else "0")
    OFFLINE = dict(answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"),
                   router_online=False)
    OFFLINE.update(kw)
    return e2e(**OFFLINE)


OFFLINE_SITE = WEB
os.environ["PISO_SSH"] = f'"{sys.executable}" "{tmp_p}/fake_ssh.py"'   # (an earlier section took it away)
os.makedirs(f"{fake_root}/tmp", exist_ok=True)
router_knows(serve_offline_site())
r = offline_run(inet=False)
cmds = commands(r)
check(r.err is None and "SETUP-RAN" in r.out and "SETUP COMPLETE" in r.out, f"a factory router with no internet is set up ({r.err}): " + r.out[-700:])
bad_cmds = [(w, c[:200]) for c in cmds for w in ("wget", "curl", "opkg update", "ping") if re.search(r"(^|[\s;&|(])" + w + r"(\s|$)", c)]
check(not bad_cmds, "and no wget, curl, ping or opkg update was ever sent to the router: " + str(bad_cmds))
check(r.wget == "", "(the router's wget was never started)")
check(cmds[1].startswith("echo @@RELEASE"), "the first thing after the login probe is reading the router: " + cmds[1][:60])
i_inst = next((i for i, c in enumerate(cmds) if "install.sh" in c and "--local" in c), -1)
i_setup = next((i for i, c in enumerate(cmds) if "IFS= read -r ROOT_PASSWORD" in c), -1)
i_copy = [i for i, c in enumerate(cmds) if "cat >" in c]
check(0 < i_inst < i_setup and i_copy and min(i_copy) < i_inst and max(i_copy) > i_inst, "the order: read, copy install.sh and the setup file, move the router, copy the whole bundle, run the setup: "
      + str([(i, c[:50]) for i, c in enumerate(cmds)]))
first_copies = [c for c in cmds[:i_inst] if "cat >" in c]
check(first_copies and all(("install.sh" in c or "piso-setup.sh" in c or "SHA256SUMS" in c) for c in first_copies) and not any(".ipk" in c for c in first_copies),
      "at the factory address only install.sh, the setup file and its checksum are copied: " + str([c[-60:] for c in first_copies]))
check(any(".ipk" in c for c in cmds[i_inst:i_setup]) and any("order.txt" in c for c in cmds[i_inst:i_setup]), "and the packages go over at 10.0.0.1, after the move")
check("--local" in cmds[i_inst] and "--yes" in cmds[i_inst] and "piso-offline" in cmds[i_inst], "install.sh is run with --yes --local on the copied folder: " + cmds[i_inst][:140])
check("PISO_LOCAL_PKGS=" in cmds[i_setup] and "install.sh" in cmds[i_setup] and "--local" in cmds[i_setup] and "IFS= read -r ROOT_PASSWORD" in cmds[i_setup], "the setup command exports PISO_LOCAL_PKGS next to the answers: " + cmds[i_setup][-200:])
check("LOCAL-PKGS=[" + fake_root_p + "/tmp/piso-offline/pkgs]" in r.out, "and the setup got it: " + r.out[-600:])
check(r.ssh.count("root@192.168.1.1") >= 3 and r.ssh.count("root@10.0.0.1") >= 3, "the factory address is used before the move and 10.0.0.1 after it")
check(not os.path.exists(f"{fake_root}/tmp/piso-offline"), "the bundle is gone from the router's memory afterwards")
check(cmds[-1].startswith("rm -rf") and "piso-offline" in cmds[-1], "removed by the last command: " + cmds[-1][:80])

# the router at 10.0.0.1 already: no move, the bundle goes over once
router_knows(serve_offline_site())
r = offline_run(inet=False, lan="10.0.0.1", probe="configured", answers=("y", "n"), secrets_=(), country="JP")
cmds = commands(r)
check(r.err is None and "SETUP COMPLETE" in r.out and not any("--local" in c and "IFS= read" not in c for c in cmds), "a router at 10.0.0.1: set up, with no separate install.sh run: " + str(r.err) + r.out[-300:] + str([c[:90] for c in cmds if "--local" in c and "IFS= read" not in c]))
check(sum(1 for c in cmds if "order.txt" in c and "cat >" in c) == 1 and r.ssh.count("root@192.168.1.1") == 0, "and the bundle is copied once")

# the setup fails: the bundle is removed all the same
router_knows(serve_offline_site())
r = offline_run(inet=False, lan="10.0.0.1", probe="configured", result="fail", answers=("y", "n"), secrets_=(), country="JP")
check(not os.path.exists(f"{fake_root}/tmp/piso-offline") and commands(r)[-1].startswith("rm -rf"), "a failed setup also removes the bundle from the router")

# it cannot be prepared and the router has internet: today's way, with the reason
router_knows(serve_offline_site(), conf="option overlay_root /overlay\n")
r = offline_run(inet=True)
check(r.err is None and "SETUP-RAN" in r.out and "https://pisophone.pages.dev/install.sh" in r.wget, "not possible (no signature checking) but the router has internet: today's path runs: " + str(r.err) + r.out[-400:])
check("without the router's internet is not possible" in r.out and "signature" in r.out, "and the message names the reason: " + r.out[:900])
check(any("ping -c 1" in c for c in commands(r)) and not any("cat >" in c for c in commands(r)), "the router's internet was tested by ping over ssh, and no bundle was copied")
# neither works: stops before anything is changed
router_knows(serve_offline_site(), conf="option overlay_root /overlay\n")
open(f"{tmp}/lan", "w").write("192.168.1.1")
r = offline_run(inet=False)
check(r.err and "signature" in r.err and "internet" in r.err and "Nothing was changed" in r.err, "no way to prepare and no internet: it stops with both reasons: " + str(r.err))
check(open(f"{tmp}/lan").read().strip() == "192.168.1.1" and r.uci == "" and not any("install" in c and "--yes" in c for c in commands(r)) and not os.path.exists(f"{fake_root}/tmp/piso-offline"),
      "and the router was not changed (same address, nothing installed, nothing copied)")
# feature 032 (SU-04): a router found at another address (here 192.168.8.1) is moved to 10.0.0.1 like a factory one
r = e2e(lan="192.168.8.1", router="192.168.8.1", answers=("Tindahan WiFi", "Aling Nena", "y", "n"), secrets_=("routerpass1", "routerpass1", "kioskpass1", "kioskpass1", "boxadmin99", "boxadmin99"))
check(r.rc == 0 and r.err is None and "set network.lan.ipaddr=10.0.0.1" in r.uci and open(f"{tmp}/lan").read().strip() == "10.0.0.1", f"a router at 192.168.8.1 is moved to 10.0.0.1 and set up ({r.err}): " + r.out[-500:])
check("Router found at 192.168.8.1" in r.out and "moving it to 10.0.0.1" in r.out, "and the address it was found at is named: " + r.out[:300])
# feature 032 (SU-06a): an apk router (OpenWrt 25.x) is refused before anything is changed, even when it has internet (the online way fails at opkg)
router_knows([], apk="yes", release="25.12.0")
open(f"{tmp}/lan", "w").write("192.168.1.1")
r = offline_run(inet=True)
check(r.err and "apk" in r.err and "25.12.0" in r.err and "ROUTERS.md" in r.err and "Nothing was changed" in r.err, "an apk router: refused, naming its release and the supported ones: " + str(r.err))
check(r.wget == "" and "SETUP-RAN" not in r.out and r.uci == "" and not any("ping -c 1" in c for c in commands(r)) and not any("cat >" in c for c in commands(r)),
      "and nothing was tried on it (no download, no internet test, no copy, no setup, no change)")
# feature 032 (SU-02): the computer's internet goes through the router's cable and the router has none: ask to unplug it for the download
class Clock:
    def __init__(self): self.t = 0.0
    def now(self): return self.t
    def sleep(self, seconds): self.t += seconds


def site_opener(state):
    def opener(request, timeout=None):
        if state["cable_in"]:
            raise urllib.error.URLError("no route to host")
        return urllib.request.urlopen(request, timeout=timeout)
    return opener


clock = Clock()
st = {"cable_in": False}
notes = []
check(ps.wait_for_site(WEB, site_opener(st), notes.append, lambda t: None, clock.sleep, clock.now) is False and notes == [], "the website answers: no prompt, nothing unplugged")
serve_offline_site()
st["cable_in"] = True
notes = []


def unplug_on_ask(msg):
    notes.append(msg)
    if msg.startswith("Unplug the network cable"):
        st["cable_in"] = False


check(ps.wait_for_site(WEB, site_opener(st), unplug_on_ask, lambda t: None, clock.sleep, clock.now) is True and len(notes) == 1 and "router" in notes[0],
      "the website does not answer: the cable is asked to be unplugged, and it is noted that it was: " + str(notes))
st["cable_in"] = True
notes = []
try:
    ps.wait_for_site(WEB, site_opener(st), notes.append, lambda t: None, clock.sleep, clock.now, wait=30)
    why = "did not raise"
except ps.OfflineNotPossible as e:
    why = e.reason
check("website" in why and "unplug" in why.lower() and clock.now() >= 30, "nobody unplugs it: after the wait it gives up with the reason (the router's internet decides): " + why)
rec = []
probes = iter([False, False, True])
ps.wait_for_router("10.0.0.1", lambda h: next(probes), rec.append, lambda t: None, clock.sleep, clock.now)
check(len(rec) == 1 and rec[0].startswith("Plug the network cable back"), "then it asks for the cable to be plugged back in and waits until the router answers: " + str(rec))
try:
    ps.wait_for_router("10.0.0.1", lambda h: False, lambda t: None, lambda t: None, clock.sleep, clock.now, wait=20)
    why = "did not raise"
except ps.SetupError as e:
    why = str(e)
check("router" in why and "plug" in why.lower(), "a router that never answers again: it stops and says so: " + why)

# end to end: install() with the cable on the router, no internet that way; the owner unplugs, the bundle is downloaded, the cable goes back
router_knows(serve_offline_site())
open(f"{tmp}/lan", "w").write("10.0.0.1")
open(f"{tmp}/probe", "w").write("configured")
open(f"{tmp}/result", "w").write("ok")
open(f"{tmp}/inet", "w").write("0")
if os.path.exists(f"{tmp}/needpw"):
    os.remove(f"{tmp}/needpw")
st = {"cable_in": True}
events, emitted = [], []


def cable_notify(msg):
    events.append(msg)
    if msg.startswith("Unplug the network cable"):
        st["cable_in"] = False
    elif msg.startswith("Plug the network cable back"):
        st["cable_in"] = True


ssh_ = ps.Ssh(tmp)
ssh_.set_password("")
out_ = tempfile.mkdtemp()
result = ps.install(ssh_, "10.0.0.1", {k: "Aa12345678" for k in ps.ANSWER_ORDER}, "main", out_, emitted.append, lambda: True, online=False, offline_site=OFFLINE_SITE,
                    opener=site_opener(st), notify=cable_notify, probe=lambda h: True, sleep=clock.sleep, clock=clock.now)
check(result["ok"] and [e.split(" ")[0] for e in events] == ["Unplug", "Plug"], "install(): asked to unplug, then to plug back in, and finished the setup: " + str(events))
check(st["cable_in"] and not os.path.exists(f"{fake_root}/tmp/piso-offline"), "the cable is back in and the bundle is gone from the router")
# the early download at Start (the coin box software for every chip, the installer files and app.json) and the copy used when the website is out of reach later
fw_bytes = {"a.bin": b"image-a" * 10, "b.bin": b"image-b" * 10}
served_site = {"/flash/manifest.json": json.dumps({"builds": [
    {"chip": "esp32c3", "file": n, "size": len(b), "sha256": hashlib.sha256(b).hexdigest(), "product": "full"} for n, b in fw_bytes.items()]}).encode(),
    "/update/app.json": b'{"version": 1}', "/install.sh": b"#!/bin/sh\necho install\n"}
for n, b in fw_bytes.items():
    served_site["/flash/" + n] = b
for n in ps.ROUTER_INSTALLERS:
    served_site["/setup/" + n] = b"setup " + n.encode()
    served_site["/setup/" + n + ".sha256"] = hashlib.sha256(b"setup " + n.encode()).hexdigest().encode()


class FakeBody:
    def __init__(self, body): self.body = body
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def read(self, n=-1): return self.body


def fake_site(request, timeout=None):
    path = request.full_url.split("pisophone.pages.dev", 1)[1]
    if path not in served_site:
        raise urllib.error.HTTPError(request.full_url, 404, "not found", {}, None)
    return FakeBody(served_site[path])


fw_dir, site_dir = tempfile.mkdtemp(), tempfile.mkdtemp()
problems = ps.prefetch_site("main", fw_dir, site_dir, opener=fake_site)
check(problems == [] and len(os.listdir(fw_dir)) == 3, f"the early download keeps the software list and the image of every chip: {problems} {os.listdir(fw_dir)}")
check(all(os.path.exists(os.path.join(site_dir, *rel.split("/"))) for rel in ("update/app.json", "install.sh", "setup/piso-setup.sh", "setup/piso-setup-x86_64.sh.sha256")),
      "and app.json, install.sh and the setup file of every router family")
fw_dir2, site_dir2 = tempfile.mkdtemp(), tempfile.mkdtemp()
check(ps.prefetch_site("main", fw_dir2, site_dir2, with_router=False, opener=fake_site) == [] and not os.path.exists(os.path.join(site_dir2, "install.sh"))
      and os.path.exists(os.path.join(site_dir2, "update", "app.json")), "PisoPhone only: no router files")


def dead_site(request, timeout=None):
    raise urllib.error.URLError("no route to host")


check(len(ps.prefetch_site("main", tempfile.mkdtemp(), tempfile.mkdtemp(), opener=dead_site)) >= 2, "with no website the early download reports what failed and does not raise")
quiet = []
check(ps.site_file("https://pisophone.pages.dev", "/install.sh", "install.sh", dead_site, site_dir, quiet.append) == served_site["/install.sh"] and quiet and "kept" in quiet[0],
      "later, with the website out of reach, the copy kept from the start is used and said so: " + str(quiet))
try:
    ps.site_file("https://pisophone.pages.dev", "/install.sh", "install.sh", dead_site, tempfile.mkdtemp(), quiet.append)
    why = "did not raise"
except ps.OfflineNotPossible as e:
    why = e.reason
check("install.sh" in why, "and with no copy kept it fails naming the file: " + why)

# too little memory is a reason to fall back, found before the router is touched
router_knows(serve_offline_site(), mem=24000)
r = offline_run(inet=True)
check(r.err is None and "memory" in r.out and "https://pisophone.pages.dev/install.sh" in r.wget, "too little free memory: today's path, naming it: " + str(r.err) + r.out[:500])
# a package that cannot be downloaded
sources = serve_offline_site()
SERVED.pop("/feeds/openwrt_base/curl_1_mipsel_24kc.ipk")
router_knows(sources)
r = offline_run(inet=True)
check(r.err is None and "curl_1_mipsel_24kc.ipk" in r.out and "https://pisophone.pages.dev/install.sh" in r.wget, "a package that cannot be downloaded: today's path, naming the file: " + str(r.err))
# --router-online: the offline way is not even tried
router_knows(serve_offline_site())
open(f"{tmp}/ssh.log", "w").close()
r = offline_run(inet=True, router_online=True)
check(r.err is None and "https://pisophone.pages.dev/install.sh" in r.wget and not any(c.startswith("echo @@RELEASE") for c in commands(r)) and "offline" not in r.out.lower(),
      "--router-online: today's path, the router is not asked about its package sources: " + str(r.err))
check(ps.build_parser().parse_args(["--router-online"]).router_online is True and ps.build_parser().parse_args([]).router_online is False, "the option exists and is off by default")
OFFLINE_SITE = None

shutil.rmtree(tmp, ignore_errors=True)
# ---- feature 032 (SU-18): the computer's addresses at the top of the setup log -------------------------------------------------------------
import types as _types
for kind, first in (("windows", "ipconfig"), ("mac", "ifconfig"), ("linux", "ip")):
    ran = []

    def fake_run(command, **kw):
        ran.append(command[0])
        return _types.SimpleNamespace(stdout=f"ADDR {command[0]}\nline two".encode())
    report = ps.network_report({"kind": kind}, run=fake_run)
    check(ran[0] == first and len(ran) == 2 and f"ADDR {first}" in report and "line two" in report, f"{kind}: the address and route commands are run and kept: {ran}")


def refusing_run(command, **kw):
    raise FileNotFoundError(command[0])
report = ps.network_report({"kind": "linux"}, run=refusing_run)
check(len(report) == 2 and all("could not be run" in x for x in report), "a missing command is noted, not raised: " + str(report))
check(ps.SetupLog.mask("pw is Secret123 and ab", ["Secret123", "ab", ""]) == "pw is *** and ab", "only secrets of 6 characters or more are hidden")

# ---- feature 032 (SU-17): the coin box software list and image are asked for with the program's own name ----------------------------------
import json
seen_agents = []


class RecordingOpener:
    def __init__(self, body): self.body = body
    def __call__(self, request, timeout=None):
        seen_agents.append(request.get_header("User-agent"))
        return self
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def read(self): return self.body


manifest_body = json.dumps({"builds": [{"chip": "esp32c3", "file": "x.bin", "size": 1, "sha256": "0" * 64, "product": "full"}]}).encode()
ps.flash_manifest("main", tempfile.mkdtemp(), opener=RecordingOpener(manifest_body), emit=lambda t: None)
try:
    ps.fetch_image("main", {"file": "x.bin", "size": 1, "sha256": "0" * 64}, tempfile.mkdtemp(), opener=RecordingOpener(b"\0"))
except ps.SetupError:
    pass   # (the checksum is fake: only the request matters here)
check(seen_agents == [ps.USER_AGENT, ps.USER_AGENT], "the software list and the image requests carry USER_AGENT: " + str(seen_agents))

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
