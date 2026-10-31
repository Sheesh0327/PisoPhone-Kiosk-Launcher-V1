#!/usr/bin/env python3
"""Tests of setup/install.sh, the one-line router installer (wget -qO- .../install.sh | sh), without a router: a fake wget
serving files from a folder, a fake uci, ubus and jsonfilter, and a file standing in for the terminal.
Run with:  python3 router/tests/test_install.py"""
import hashlib, os, shutil, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INSTALL = f"{ROOT}/setup/install.sh"
REAL_SETUP = f"{ROOT}/setup/piso-setup.sh"
tmp = tempfile.mkdtemp()
checks = failures = 0
SH = os.environ.get("TEST_SH", "sh")   # the second pass uses BusyBox ash, the router's shell


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


bindir, site, home = f"{tmp}/bin", f"{tmp}/site", f"{tmp}/root"
fakeroot = f"{tmp}/fakeroot"
for d in (bindir, site, home, f"{fakeroot}/tmp", f"{fakeroot}/root"):
    os.makedirs(d)


def script(name, body):
    open(f"{bindir}/{name}", "w").write("#!/bin/sh\n" + body)
    os.chmod(f"{bindir}/{name}", 0o755)


# wget -q -T 60 -O <file> <url>: the website of a branch (https://fake.test/... main, https://<branch>.fake.test/... others)
# served from <site>/<branch>/, logging every request
script("wget", f"""out=""; while [ $# -gt 1 ]; do [ "$1" = -O ] && out="$2"; shift; done; url="$1"
echo "$url" >> {tmp}/wget.log
u="${{url#*://}}"; host="${{u%%/*}}"; p="${{u#*/}}"
case "$host" in fake.test) b=main ;; *.fake.test) b="${{host%.fake.test}}" ;; *) exit 8 ;; esac
[ -f "{site}/$b/$p" ] || exit 8
cat "{site}/$b/$p" > "$out"
""")
script("uci", f"""echo "$*" >> {tmp}/uci.log
[ "$1" = -q ] && shift
case "$1" in get) [ "$2" = network.lan.ipaddr ] && echo "${{FAKE_LAN}}/24" ;; esac
exit 0
""")
script("opkg", "exit 0\n")   # (a router with opkg; run(opkg=False) hides it)
script("ubus", "echo '{}'\n")
script("jsonfilter", 'case "$*" in *mask*) echo "${FAKE_MASK:-24}" ;; *) echo "$FAKE_WAN" ;; esac\n')
script("netinit", f'echo "$1" >> {tmp}/network.log\n')
script("ip", f'echo "$*" >> {tmp}/ip.log\n')   # (the LAN ports going down and up again after the move)
netdir = f"{tmp}/sysnet"   # a fake /sys/class/net: a DSA router (lan1..lan4, the WAN port, the bridge and the CPU interface)
for n in ("lan1", "lan2", "lan3", "lan4", "wan", "br-lan", "eth0", "lan1.1"):
    os.makedirs(f"{netdir}/{n}")
emptynet = f"{tmp}/sysnet-none"   # a router whose ports are not named lanN (an older, swconfig kind): nothing is switched
for n in ("eth0", "br-lan"):
    os.makedirs(f"{emptynet}/{n}")

FAKE_SETUP = """#!/bin/sh
PISO_RELEASE='1.2.3'
read -r answer
echo "SETUP-RAN args=[$*] answer=[$answer] cwd=$(pwd)"
exit 0
# ---- payload: the portal files (extracted by extract_payload) ----
"""


def publish(branch, data, sha=None):
    d = f"{site}/{branch}/setup"
    os.makedirs(d, exist_ok=True)
    open(f"{d}/piso-setup.sh", "wb").write(data)
    open(f"{d}/piso-setup.sh.sha256", "w").write((sha or hashlib.sha256(data).hexdigest()) + "  piso-setup.sh\n")


def run(*args, lan="10.0.0.1", wan="192.168.1.50", mask=24, answer="y", stdin_script=None, self_path=None, mem=None, install_dir=True, opkg=True, apk=False, nds=False, net_dir=None):
    for f in ("wget.log", "uci.log", "network.log", "ip.log"):
        if os.path.exists(f"{tmp}/{f}"):
            os.remove(f"{tmp}/{f}")
    open(f"{tmp}/tty", "w").write(answer + "\n")
    net_dir = net_dir or netdir
    path_dir = bindir
    if not opkg:   # the same fake commands without opkg (OpenWrt 25.x uses apk, or the image has no package manager)
        path_dir = f"{tmp}/bin-no-opkg"
        shutil.rmtree(path_dir, ignore_errors=True)
        os.makedirs(path_dir)
        for name in os.listdir(bindir):
            if name != "opkg":
                os.symlink(f"{bindir}/{name}", f"{path_dir}/{name}")
        if apk:
            open(f"{path_dir}/apk", "w").write("#!/bin/sh\nexit 0\n")
            os.chmod(f"{path_dir}/apk", 0o755)
        if nds:   # openNDS built into the image: its init script (under PISO_ROOT) and ndsctl
            open(f"{path_dir}/ndsctl", "w").write("#!/bin/sh\nexit 0\n")
            os.chmod(f"{path_dir}/ndsctl", 0o755)
    env = dict(os.environ, PATH=f"{path_dir}:" + os.environ["PATH"], PISO_SITE_HOST="fake.test", PISO_INSTALL_DIR=home,
               PISO_TTY=f"{tmp}/tty", PISO_TEST_NONROOT="1", PISO_NETWORK_INIT=f"{bindir}/netinit", PISO_NET_DIR=net_dir, FAKE_LAN=lan, FAKE_WAN=wan, FAKE_MASK=str(mask),
               PISO_SELF_PATH=self_path or f"{tmp}/no-such-piso-setup")
    if mem is not None:
        env["PISO_MEM_KB"] = str(mem)
    if not install_dir:   # the default places /tmp and /root, under a fake root
        del env["PISO_INSTALL_DIR"]
        env["PISO_ROOT"] = fakeroot
    if nds:
        env["PISO_ROOT"] = fakeroot
    # as on the router: the installer arrives on sh's standard input
    body = stdin_script if stdin_script is not None else open(INSTALL).read()
    if SH != "sh":
        # (BusyBox runs its own wget and ip applets before PATH: the test's stubs are called by name instead)
        body = f'wget() {{ {bindir}/wget "$@"; }}\nip() {{ {bindir}/ip "$@"; }}\n' + body
    r = subprocess.run([SH, "-s", *args], input=body, env=env, capture_output=True, text=True, timeout=120)
    time.sleep(0.2)
    return r


def log(name):
    p = f"{tmp}/{name}"
    return open(p).read() if os.path.exists(p) else ""


# ---- a router already at 10.0.0.1: download, check, run the setup with the terminal's answers ---------------------------------
publish("main", FAKE_SETUP.encode())
r = run()
check(r.returncode == 0 and "SETUP-RAN args=[] answer=[y]" in r.stdout, "it downloads the setup and runs it, answers from the terminal: " + r.stdout + r.stderr)
check(f"cwd={home}" in r.stdout and os.access(f"{home}/piso-setup.sh", os.X_OK) and open(f"{home}/piso-setup.sh").read() == FAKE_SETUP,
      "the setup file is saved in /root, executable, byte for byte")
check("https://fake.test/setup/piso-setup.sh" in log("wget.log") and "piso-setup.sh.sha256" in log("wget.log"), "from the main branch, with its checksum")
check("release 1.2.3" in r.stdout, "it says which release it saved")
check(not [f for f in os.listdir(home) if f.startswith(".piso-setup.download")], "no partial download is left behind")
r = run("update")
check("SETUP-RAN args=[update] " in r.stdout, "'sh -s update' runs the setup's update (no setup installed yet)")
# an installed router: 'update' hands over to the signed self-update, and downloads nothing
installed = f"{tmp}/installed-piso-setup"
open(installed, "w").write('#!/bin/sh\necho "SELF-RAN args=[$*]"\n# self-update\n')
os.chmod(installed, 0o755)
r = run("update", self_path=installed)
check("SELF-RAN args=[self-update]" in r.stdout and log("wget.log") == "", "installed router: 'update' runs the signed self-update and downloads nothing: " + r.stdout + r.stderr)
publish("beta", FAKE_SETUP.replace("1.2.3", "1.3.0").encode())
r = run("--", "--branch", "beta", "update", self_path=installed)
check("SETUP-RAN" in r.stdout and "SELF-RAN" not in r.stdout and "not signed by the owner" in r.stdout, "--branch beta update: the branch's file, with a warning that it is unsigned: " + r.stdout)
r = run("--", "--branch", "beta")
check("https://beta.fake.test/setup/piso-setup.sh" in log("wget.log") and "release 1.3.0" in r.stdout and "SETUP-RAN args=[]" in r.stdout, "--branch beta fetches beta's setup")
r = run("--", "--branch", "beta;reboot")
check(r.returncode != 0 and "not a branch name" in r.stdout and log("wget.log") == "", "a branch name with odd characters is refused")

# ---- feature 041: the installer takes no setup file from a folder: it always downloads it and checks its sha256 ---------------------
check("--local" not in open(f"{ROOT}/setup/install.sh").read() and "LOCAL" not in open(f"{ROOT}/setup/install.sh").read(), "install.sh has no --local option any more")
for f in os.listdir(home):
    os.remove(f"{home}/{f}")
folder = f"{tmp}/local_copy"
os.makedirs(folder, exist_ok=True)
other = FAKE_SETUP.replace("1.2.3", "2.0.0").encode()
open(f"{folder}/piso-setup.sh", "wb").write(other)
open(f"{folder}/piso-setup.sh.sha256", "w").write(hashlib.sha256(other).hexdigest() + "  piso-setup.sh\n")
publish("main", FAKE_SETUP.encode())
r = run("--", "--yes", "--local", folder)
check("release 2.0.0" not in r.stdout and "piso-setup.sh" in log("wget.log"), "a setup file in a folder is never used: the installer downloads the published one: " + r.stdout + r.stderr + log("wget.log"))

# ---- what is refused ------------------------------------------------------------------------------------------------------------
shutil.rmtree(home)
os.makedirs(home)
publish("main", FAKE_SETUP.encode(), sha="0" * 64)
r = run()
check(r.returncode != 0 and "checksum differs" in r.stdout and "SETUP-RAN" not in r.stdout and not os.path.exists(f"{home}/piso-setup.sh"),
      "a download that does not match its checksum is not saved or run: " + r.stdout)
html = b"<html>captive portal login</html>\n"
publish("main", html)
r = run()
check(r.returncode != 0 and "not the setup file" in r.stdout and not os.path.exists(f"{home}/piso-setup.sh"), "a web page instead of the file is refused")
nopay = FAKE_SETUP.split("# ---- payload")[0].encode()
publish("main", nopay)
r = run()
check(r.returncode != 0 and "no portal program" in r.stdout, "a setup file without its payload is refused")
publish("main", b"#!/bin/sh\nif then fi (\n# ---- payload: the portal files\n")
r = run()
check(r.returncode != 0 and "does not read as a shell script" in r.stdout, "a file that is not valid shell is refused")
shutil.rmtree(f"{site}/main")
r = run()
check(r.returncode != 0 and "could not download" in r.stdout, "no internet: it says so")
publish("main", FAKE_SETUP.encode())

# ---- a factory router (192.168.1.1): moved to 10.0.0.1 after asking -------------------------------------------------------------
r = run(lan="192.168.1.1", answer="n")
check(r.returncode != 0 and "nothing was changed" in r.stdout and "network.lan.ipaddr=10.0.0.1" not in log("uci.log") and "SETUP-RAN" not in r.stdout,
      "answered no: the address is not changed: " + r.stdout)
check(os.path.exists(f"{home}/piso-setup.sh"), "the setup file is kept for the next try")
r = run(lan="192.168.1.1", answer="y")
time.sleep(3.6)
check(r.returncode == 0 and "set network.lan.ipaddr=10.0.0.1" in log("uci.log") and "commit network" in log("uci.log"), "answered yes: the router is moved to 10.0.0.1: " + log("uci.log"))
check(log("network.log") == "restart\n", "the network is restarted (after the message is shown)")
# feature 046 (SU-03): then the LAN ports go down and up again, so a Linux or macOS computer (and the Armbian box) asks for a new address by itself
time.sleep(9)
ips = [ln.split() for ln in log("ip.log").splitlines()]
downs, ups = [i for i, a in enumerate(ips) if a[-1] == "down"], [i for i, a in enumerate(ips) if a[-1] == "up"]
ported = sorted(a[2] for a in ips if a[:2] == ["link", "set"])
check(ported == ["lan1", "lan1", "lan2", "lan2", "lan3", "lan3", "lan4", "lan4"] and len(downs) == 4 and len(ups) == 4 and max(downs) < min(ups),
      "after the move every LAN port is set down, then up (and no WAN port, bridge, CPU interface or VLAN): " + log("ip.log"))
r = run(lan="192.168.1.1", answer="y", net_dir=emptynet)
time.sleep(9)
check(r.returncode == 0 and log("network.log") == "restart\n" and log("ip.log") == "", "a router whose ports are not named lanN: the move works as before and no port is touched: " + log("ip.log"))
check("ssh root@10.0.0.1" in r.stdout and f"sh {home}/piso-setup.sh" in r.stdout and "SETUP-RAN" not in r.stdout, "it says how to log in again and continue with sh <the saved path>, and does not run the setup yet")
r = run(lan="192.168.1.1", wan="10.0.0.23", answer="y")
check(r.returncode != 0 and "192.168.100.1" in r.stdout and "network.lan.ipaddr=10.0.0.1" not in log("uci.log"), "a modem on 10.0.0.x: it stops and says what to change")
r = run(lan="192.168.1.1", wan="10.1.2.3", mask=8, answer="y")
check(r.returncode != 0 and "192.168.100.1" in r.stdout and "network.lan.ipaddr=10.0.0.1" not in log("uci.log"), "feature 046 (SU-05): a modem lease of 10.1.2.3/8 overlaps the kiosk network too: refused by the netmask")
r = run(lan="192.168.1.1", wan="10.9.9.9", mask=24, answer="y")
time.sleep(3.6)
check(r.returncode == 0 and "set network.lan.ipaddr=10.0.0.1" in log("uci.log"), "and 10.9.9.9/24 is another network: the router is moved: " + r.stdout)
r = run("update", lan="192.168.1.1")
check("SETUP-RAN args=[update]" in r.stdout and "set network.lan.ipaddr" not in log("uci.log"), "update never moves the router")
publish("main", FAKE_SETUP.encode())
# feature 057: a router with apk and no opkg (OpenWrt 25.x) is no longer refused: the setup goes on
r = run(lan="192.168.1.1", answer="y", opkg=False, apk=True)
check(r.returncode == 0 and "Saved " in r.stdout and "set network.lan.ipaddr=10.0.0.1" in log("uci.log"), "apk and no opkg: the installer goes on and moves the router: " + r.stdout + r.stderr)
# feature 057 (was 046 SU-06b): no package manager at all and no openNDS is refused before anything is downloaded, moved or run
r = run(lan="192.168.1.1", answer="y", opkg=False)
check(r.returncode != 0 and "package manager" in r.stdout + r.stderr and "openNDS" in r.stdout + r.stderr and "ROUTERS.md" in r.stdout + r.stderr and "Nothing was changed" in r.stdout + r.stderr,
      "no package manager and no openNDS: it stops and says what to do: " + r.stdout + r.stderr)
check(log("wget.log") == "" and "network.lan.ipaddr" not in log("uci.log") and "SETUP-RAN" not in r.stdout and log("network.log") == "",
      "and nothing was downloaded, moved, restarted or run")
# ... but a custom image with openNDS built in and no package manager goes on
os.makedirs(f"{fakeroot}/etc/init.d", exist_ok=True)
open(f"{fakeroot}/etc/init.d/opennds", "w").write("#!/bin/sh\n")
os.chmod(f"{fakeroot}/etc/init.d/opennds", 0o755)
r = run(opkg=False, nds=True)
check(r.returncode == 0 and "SETUP-RAN" in r.stdout, "no package manager but openNDS built in: the installer goes on: " + r.stdout + r.stderr)
os.remove(f"{fakeroot}/etc/init.d/opennds")

# ---- feature 010: the installer copy lives in RAM (/tmp) unless memory is short (then /root); PISO_INSTALL_DIR overrides both ---------
publish("main", FAKE_SETUP.encode())
for mem, where, other in ((24000, "tmp", "root"), (500000, "tmp", "root"), (23999, "root", "tmp")):
    r = run(mem=mem, install_dir=False)
    saved = f"{fakeroot}/{where}/piso-setup.sh"
    check(r.returncode == 0 and os.path.exists(saved) and not os.path.exists(f"{fakeroot}/{other}/piso-setup.sh") and f"cwd={fakeroot}/{where}" in r.stdout,
          f"{mem} KB free: the installer copy is saved in /{where}, not in /{other}: " + r.stdout + r.stderr)
    for f in ("tmp", "root"):
        for g in os.listdir(f"{fakeroot}/{f}"):
            os.remove(f"{fakeroot}/{f}/{g}")
r = run(mem=100, install_dir=True)
check(os.path.exists(f"{home}/piso-setup.sh") and not os.path.exists(f"{fakeroot}/root/piso-setup.sh"), "PISO_INSTALL_DIR overrides the memory rule")
r = run(mem=None, install_dir=False)
check(os.path.exists(f"{fakeroot}/tmp/piso-setup.sh") or os.path.exists(f"{fakeroot}/root/piso-setup.sh"), "memory unknown: the copy is saved somewhere")
for f in ("tmp", "root"):
    for g in os.listdir(f"{fakeroot}/{f}"):
        os.remove(f"{fakeroot}/{f}/{g}")
publish("main", FAKE_SETUP.encode(), sha="0" * 64)
r = run(mem=500000, install_dir=False)
check(r.returncode != 0 and "checksum differs" in r.stdout and not os.path.exists(f"{fakeroot}/tmp/piso-setup.sh"), "the sha256 check is unchanged: a bad download is not saved in /tmp")
publish("main", FAKE_SETUP.encode())
r = run(lan="192.168.1.1", answer="y", mem=500000, install_dir=False)
time.sleep(3.6)
check(f"sh {fakeroot}/tmp/piso-setup.sh" in r.stdout and "run the one-line installer again" in r.stdout and "log in again" in r.stdout,
      "factory address, copy in /tmp: it says to run sh <path> and to run the one-line installer again if the router restarts: " + r.stdout)
r = run(lan="192.168.1.1", answer="y", mem=1000, install_dir=False)
time.sleep(3.6)
check(f"sh {fakeroot}/root/piso-setup.sh" in r.stdout and "run the one-line installer again" not in r.stdout,
      "factory address, copy in /root (flash): no 'run the one-line installer again' sentence: " + r.stdout)
r = run("--", "--yes", mem=500000, install_dir=False)
check("SETUP-RAN args=[--yes] answer=[]" in r.stdout and f"cwd={fakeroot}/tmp" in r.stdout, "--yes runs the copy in /tmp at once: " + r.stdout)
for f in ("tmp", "root"):
    for g in os.listdir(f"{fakeroot}/{f}"):
        os.remove(f"{fakeroot}/{f}/{g}")

# ---- --yes (setup/pisophone_setup.py): nothing is asked, the setup runs unattended -------------------------------------------
r = run("--", "--yes", lan="192.168.1.1", answer="n")
time.sleep(3.6)
check(r.returncode == 0 and "set network.lan.ipaddr=10.0.0.1" in log("uci.log") and log("network.log") == "restart\n",
      "--yes: a factory router is moved to 10.0.0.1 without asking: " + r.stdout)
check("SETUP-RAN" not in r.stdout, "--yes: the setup itself waits for the login at 10.0.0.1")
r = run("--", "--yes", answer="this must not be read")
check(r.returncode == 0 and "SETUP-RAN args=[--yes] answer=[]" in r.stdout, "--yes: the setup runs with --yes and no terminal: " + r.stdout)
r = run("--", "--branch", "beta", "--yes", "update", lan="192.168.1.1")
check("SETUP-RAN args=[--yes update]" in r.stdout and "set network.lan.ipaddr" not in log("uci.log") and "https://beta.fake.test/setup/" in log("wget.log"),
      "--branch beta --yes update: beta's file, updated unattended, not moved: " + r.stdout)

# ---- the real setup file passes the installer's checks; a cut-off installer runs nothing -----------------------------------------
publish("main", open(REAL_SETUP, "rb").read())
r = run(lan="192.168.1.1", answer="n")
check("Saved" in r.stdout and "nothing was changed" in r.stdout, "the real setup file passes the checks: " + r.stdout[-300:])
body = open(INSTALL).read()
r = run(stdin_script=body[: len(body) * 2 // 3])
check(log("wget.log") == "" and "SETUP-RAN" not in r.stdout, "an installer cut off half way downloads and runs nothing")
check(open(f"{ROOT}/website/install.sh").read() == body, "the website serves the same installer")
sha = open(f"{ROOT}/setup/piso-setup.sh.sha256").read().split()[0]
check(sha == hashlib.sha256(open(REAL_SETUP, "rb").read()).hexdigest(), "setup/piso-setup.sh.sha256 matches the committed setup file")

shutil.rmtree(tmp, ignore_errors=True)
print(f"{checks} checks, {failures} failures" + (" (BusyBox ash)" if SH != "sh" else ""))
bb = shutil.which("busybox")
if SH == "sh" and bb:
    # again with BusyBox ash as "sh" (and its sha256sum, cut, sed...), as on the router
    bbdir = tempfile.mkdtemp()
    os.symlink(bb, f"{bbdir}/sh")
    again = subprocess.run([sys.executable, __file__], env=dict(os.environ, TEST_SH=f"{bbdir}/sh", PATH=f"{bbdir}:" + os.environ["PATH"]))
    shutil.rmtree(bbdir, ignore_errors=True)
    failures += again.returncode != 0
sys.exit(1 if failures else 0)
