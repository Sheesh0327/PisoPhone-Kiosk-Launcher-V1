#!/usr/bin/env python3
"""Tests of the setup page (setup/pisophone_setup.py, the web page it serves) without a router, a box or a phone: a fake "core"
stands in for the network, ssh and the USB board. Three parts:
  1. what this computer is and what the program installs (Armbian, Parrot, Windows...), the PIN and the service;
  2. the page's server over HTTP: login, the checks against strangers, and every step of a unit with the "next unit" at the end;
  3. the page itself in a real browser (Playwright, when it is installed): a phone-sized screen, clicking through the steps.
Run with:  python3 router/tests/test_setup_web.py [--shots FOLDER]"""
import http.client, importlib.util, json, os, sys, tempfile, threading, time, types

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)
SHOTS = sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv else None
checks = failures = 0
tmp = tempfile.mkdtemp()
os.environ["PISOPHONE_HOME"] = os.path.join(tmp, "home")


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


SUMMARY = """PisoPhone setup summary (2026-10-07 10:00:00, setup file version abc)
Router (SSH / LuCI):   root@10.0.0.1        password: Rpass2345678
Kiosk Wi-Fi:           PisoKiosk (HIDDEN)   password: Kpass234567    (rental phones only; ...)
PisoWiFi (customers):  Tindahan WiFi   (open; rename with: piso-setup wifi-name "New Name")
Coin box:              http://10.0.0.10      admin password: Bpass23456789    hidden Wi-Fi: PisoCoinBox (only MAC x)
"""

# ---- 1. this computer ------------------------------------------------------------------------------------------------------
ARMBIAN = {"ID": "armbian", "ID_LIKE": "debian", "PRETTY_NAME": "Armbian 24.5 bookworm"}
PARROT = {"ID": "parrot", "ID_LIKE": "debian", "PRETTY_NAME": "Parrot Security 6.0"}
UBUNTU = {"ID": "ubuntu", "ID_LIKE": "debian", "PRETTY_NAME": "Ubuntu 24.04"}
for release, family in ((ARMBIAN, "debian"), (PARROT, "debian"), (UBUNTU, "debian"), ({"ID": "fedora"}, "redhat"),
                        ({"ID": "manjaro", "ID_LIKE": "arch"}, "arch"), ({"ID": "alpine"}, "alpine"), ({"ID": "plan9"}, "")):
    check(ps.package_family(release) == family, f"{release['ID']} is the {family or 'unknown'} family")
info = ps.detect_system(ARMBIAN, "Linux", "aarch64", True)
check(info["kind"] == "linux" and info["family"] == "debian" and info["name"] == "Armbian 24.5 bookworm" and info["arch"] == "aarch64"
      and info["root"], f"Armbian on a small ARM computer: {info}")
check(ps.detect_system(PARROT, "Linux", "x86_64", False, which=lambda n: None)["sudo"] is False, "no sudo: said so")
check(ps.detect_system(None, "Windows", "AMD64")["kind"] == "windows" and ps.detect_system(None, "Darwin", "arm64")["kind"] == "macos",
      "Windows and macOS are told apart")
check(ps.read_os_release(os.path.join(tmp, "missing")) == {}, "no /etc/os-release: no information, no crash")
with open(os.path.join(tmp, "os-release"), "w") as f:
    f.write('NAME="Debian GNU/Linux"\nID=debian\nPRETTY_NAME="Debian GNU/Linux 12 (bookworm)"\n')
check(ps.read_os_release(os.path.join(tmp, "os-release"))["PRETTY_NAME"] == "Debian GNU/Linux 12 (bookworm)", "os-release is read")


class Runner:
    """Stands in for run_command: records the commands, answers from a script."""

    def __init__(self, answers=None):
        self.calls, self.answers = [], answers or {}

    def __call__(self, argv, emit, timeout=900, env=None):
        self.calls.append(list(argv))
        for key, answer in self.answers.items():
            if not isinstance(answer, tuple):
                continue   # (a flag for a subclass)
            code, lines = answer
            if key in " ".join(argv):
                for line in lines:
                    emit(line)
                return code
        return 0


root_debian = {"kind": "linux", "family": "debian", "name": "Armbian", "arch": "aarch64", "root": True, "sudo": False}
sudo_debian = dict(root_debian, root=False, sudo=True)
plain_debian = dict(root_debian, root=False, sudo=False)
lines = []
r = Runner()
check(ps.install_system_package("ssh", root_debian, lines.append, r) and r.calls[-1] == ["apt-get", "install", "-y", "-q", "openssh-client"]
      and r.calls[0][:2] == ["apt-get", "update"], f"as root: apt-get install openssh-client: {r.calls}")
r = Runner()
check(ps.install_system_package("pip", sudo_debian, lines.append, r) and r.calls[-1][:3] == ["sudo", "-n", "apt-get"] and "python3-pip" in r.calls[-1],
      f"with sudo: sudo -n apt-get: {r.calls[-1]}")
r, lines = Runner(), []
check(not ps.install_system_package("ssh", plain_debian, lines.append, r) and not r.calls and "sudo apt-get install -y -q openssh-client" in lines[0],
      "no rights: nothing is run and the exact command is shown: " + str(lines))
check(not ps.install_system_package("ssh", dict(root_debian, family=""), lines.append, Runner()), "an unknown system: said, not guessed")
check("dnf" in ps.INSTALLERS["redhat"][0] and ps.SYSTEM_PACKAGES["ssh"]["redhat"] == "openssh-clients", "Fedora: dnf, openssh-clients")

# esptool: already there; installed with pip into the private folder; pip missing; the "externally managed" Python of Debian 12
real_module = ps.esptool_module
ps.esptool_module = lambda: object()
r = Runner()
check(ps.ensure_esptool(lines.append, root_debian, r) and not r.calls, "esptool already there: nothing is installed")
state = {"have": False}
ps.esptool_module = lambda: object() if state["have"] else None


class PipRunner(Runner):
    def __call__(self, argv, emit, timeout=900, env=None):
        code = super().__call__(argv, emit, timeout, env)
        if code:
            return code
        joined = " ".join(argv)
        if "pip install" in joined:
            if self.answers.get("managed") and "--break-system-packages" not in argv:
                emit("error: externally-managed-environment")
                return 1
            state["have"] = True
        return 0 if not (self.answers.get("nopip") and "pip --version" in joined) else 1


r, lines = PipRunner(), []
check(ps.ensure_esptool(lines.append, root_debian, r), "esptool is installed with pip")
install = [c for c in r.calls if "install" in c and "pip" in c][0]
check("--target" in install and ps.private_lib_dir() in install and install[-1] == "esptool", f"into the program's own folder: {install}")
state["have"] = False
r, lines = PipRunner({"managed": True}), []
check(ps.ensure_esptool(lines.append, root_debian, r) and r.calls[-1][-1] == "--break-system-packages",
      "a Debian 12 / Armbian Python that is 'externally managed': it retries, still only into its own folder")
state["have"] = False
r, lines = PipRunner({"nopip": True}), []
ok = ps.ensure_esptool(lines.append, plain_debian, r)
check(not ok and any("python3-pip" in l for l in lines) and not any("pip" in c and "install" in c and "esptool" in c for c in r.calls),
      "no pip and no rights: it says what to run (sudo apt install python3-pip) and does not pretend: " + str(lines))
state["have"] = False
r = PipRunner({"pip install": (1, ["ERROR: no internet"])})
check(not ps.ensure_esptool(lines.append, root_debian, r) and "could not be installed" in lines[-1], "pip fails: said, False")
ps.esptool_module = real_module

check(ps.ensure_ssh(lines.append, root_debian, Runner(), which=lambda n: "/usr/bin/ssh"), "ssh present: nothing to do")
calls = Runner()
got = {"n": 0}


def which_after(name):
    got["n"] += 1
    return "/usr/bin/ssh" if got["n"] > 1 else None


os.environ.pop("PISO_SSH", None)
check(ps.ensure_ssh(lines.append, root_debian, calls, which=which_after) and calls.calls[-1][-1] == "openssh-client", "ssh missing on Armbian: installed")
lines = []
check(not ps.ensure_ssh(lines.append, {"kind": "windows", "name": "Windows"}, Runner(), which=lambda n: None) and "OpenSSH Client" in lines[0],
      "ssh missing on Windows: says where to turn it on")
res = ps.ensure_dependencies(lambda l: None, root_debian, Runner(), which=lambda n: "/usr/bin/ssh")
check(set(res) == {"ssh", "esptool"}, "ensure_dependencies reports both")

# the USB port on Linux
port = os.path.join(tmp, "ttyUSB0")
open(port, "w").close()
os.chmod(port, 0o000)
check(os.geteuid() == 0 or not os.access(port, os.R_OK), "(test setup: a port nobody can open)")
lines = []
if os.geteuid() != 0:
    r = Runner()
    check(not ps.serial_access_fix(port, plain_debian, lines.append, r) and "usermod -aG dialout" in lines[0], "no access, no sudo: says what to run")
    check(ps.serial_access_fix(port, sudo_debian, lines.append, r) and r.calls[-1][:3] == ["sudo", "-n", "chmod"], "sudo: the port is opened for this run")
check(ps.serial_access_fix("COM5", {"kind": "windows"}, lines.append, Runner()), "Windows: nothing to fix")
os.chmod(port, 0o600)

# the PIN
pin_file = os.path.join(tmp, "pinfile")
p1 = ps.load_pin(pin_file)
check(p1.isdigit() and len(p1) == 6 and ps.load_pin(pin_file) == p1, "a random 6-digit PIN, kept for the next run")
check(oct(os.stat(pin_file).st_mode & 0o777) == "0o600", "the PIN file is private")
check(ps.load_pin(pin_file, "abc12345") == "abc12345" and ps.load_pin(pin_file) == "abc12345", "--pin sets it for good")
try:
    ps.load_pin(pin_file, "12")
    check(False, "a short PIN is refused")
except ps.SetupError:
    check(True, "a short PIN is refused")

# the service
unit = ps.service_unit("/opt/pisophone_setup.py", 8765)
check("ExecStart=" in unit and "/opt/pisophone_setup.py --port 8765 --no-open" in unit and "WantedBy=multi-user.target" in unit
      and "PISOPHONE_HOME=/var/lib/pisophone-setup" in unit and "After=network-online.target" in unit, "the systemd unit starts the page at boot")
if hasattr(os, "geteuid") and os.geteuid() == 0:
    unit_path = os.path.join(tmp, "x.service")
    r = Runner()
    old_home = os.environ["PISOPHONE_HOME"]
    out = []
    ps.say, real_say = out.append, ps.say
    try:
        rc = ps.install_service(types.SimpleNamespace(pin="654321", port=8800), unit_path, os.path.join(tmp, "svc"), r, supported=True)
    finally:
        ps.say = real_say
        os.environ["PISOPHONE_HOME"] = old_home
    check(rc == 0 and open(unit_path).read().count("--port 8800") == 1 and ["systemctl", "enable", "--now", "pisophone-setup"] in r.calls
          and open(os.path.join(tmp, "svc", "pin")).read().strip() == "654321" and any("PIN 654321" in l for l in out),
          "--install-service writes the unit, enables it, keeps the PIN and prints the address: " + str(out))
# feature 045 (SU-21): the service starts its own copy of the program, not the file in Downloads
if hasattr(os, "geteuid") and os.geteuid() == 0:
    downloaded = os.path.join(tmp, "Downloads", "pisophone_setup.py")
    os.makedirs(os.path.dirname(downloaded))
    open(downloaded, "w").write("# the program as downloaded\n")
    unit_path, state = os.path.join(tmp, "y.service"), os.path.join(tmp, "svc2")
    out = []
    ps.say, real_say = out.append, ps.say
    try:
        ps.install_service(types.SimpleNamespace(pin="654321", port=8801), unit_path, state, Runner(), supported=True, script=downloaded)
    finally:
        ps.say = real_say
        os.environ["PISOPHONE_HOME"] = old_home
    copy = os.path.join(state, "pisophone_setup.py")
    check(os.path.exists(copy) and open(copy).read() == "# the program as downloaded\n", "the program is copied into the service's own folder")
    check(f"{copy} --port 8801" in open(unit_path).read() and "Downloads" not in open(unit_path).read(), "and the unit starts that copy, not the downloaded file")
try:
    ps.install_service(types.SimpleNamespace(pin=None, port=1), supported=False)
    check(False, "no systemd: refused")
except ps.SetupError:
    check(True, "no systemd: refused")


# ---- 2. the page's server over HTTP -----------------------------------------------------------------------------------------
class FakeCore:
    """The network, ssh and the USB board, as the page uses them."""

    def __init__(self, state="factory"):
        self.state, self.calls, self.fail_install, self.fail_flash = state, [], False, False
        self.boards = [("COM5", "USB-SERIAL CH340")]
        self.preflight_result = [("ssh program", True, ""), ("internet and the pisophone.pages.dev website", True, ""),
                                 ("USB flashing tool (esptool)", True, "")]

    def preflight(self): return self.preflight_result
    def find_router(self): return "192.168.1.1" if self.state == "factory" else "10.0.0.1"
    def network_clash(self): return None
    def router_reaches_site(self, host, password):
        self.calls.append(("reach", host, password))
        return getattr(self, "has_internet", True)
    def board_mac(self, port):
        self.calls.append(("board_mac", port))
        return "AA:BB:CC:DD:EE:01"
    def probe(self, host): return self.state
    def saved_login(self, host, out_dir): return None
    def usb_boards(self): return list(self.boards)
    def box_online(self): return True
    def checkup(self): return [{"name": "Router", "state": "ok", "text": "Answers at 10.0.0.1.", "fix": ""},
                               {"name": "Print server", "state": "off", "text": "Nothing at 10.0.0.95.", "fix": "Optional."}]

    def login(self, host, password):
        self.calls.append(("login", password))
        return password == "Rpass2345678"

    def flash_box(self, port, emit, product="full"):
        self.calls.append(("flash", port))
        self.products = getattr(self, "products", []) + [product]
        emit("== Looking at the board on " + port)
        emit("ESP32-C3 found. Software version 3.3.3 (1.36 MB).")
        if self.fail_flash:
            raise ps.SetupError("the flashing tool stopped: Failed to connect")
        emit("Writing at 0x00010000... (50 %)")
        return "ESP32-C3", "3.3.3"

    def install(self, host, answers, out_dir, emit, stage, notify):
        self.calls.append(("install", dict(answers)))
        stage(0)
        emit("Downloading the PisoPhone setup (main)...")
        stage(1)
        notify("Waiting for the router")
        stage(2)
        emit("== Installing packages")
        emit("== Pairing the coin box")
        if self.fail_install:
            raise ps.SetupError("the router could not be prepared (see the messages above)")
        stage(3)
        emit("PASS  the coin box answers")
        os.makedirs(out_dir, exist_ok=True)
        summary, sheet = os.path.join(out_dir, "summary.txt"), os.path.join(out_dir, "sheet.html")
        open(summary, "w").write(SUMMARY)
        open(sheet, "w").write("<html><body>setup sheet</body></html>")
        return {"ok": True, "host": "10.0.0.1", "state": "DONE all checks passed", "summary_text": SUMMARY, "rc": 0,
                "saved": [("Summary (every password)", summary), ("Printable setup sheet", sheet)]}

    def run(self, host, password, command, emit):
        self.calls.append(("run", command, password))
        emit("The coin slot is armed (the box should beep). Insert a coin now. Waiting up to 30 seconds...")
        emit("RESULT: the box counted 1 peso(s). The box and the portal work.")
        return 0

    def telegram(self, host, password, token, site, emit, confirm):
        emit("Got a message from chat 4242 (Evan).")
        ok = confirm("Got a message from chat 4242 (Evan).")
        self.calls.append(("telegram", token, site, ok))
        return ok

    def provisioning_target(self, passwords, slot):
        self.calls.append(("provisioning", dict(passwords), slot))
        return "setup", f"https://pisophone.pages.dev/#mac=AA&secret=S&slot={slot}", ""


def start(core=None, pin="123456", mode="full", **kw):   # (mode: the choice made at the start; None leaves it to the test)
    core = core or FakeCore(**kw)
    out = os.path.join(tempfile.mkdtemp(dir=tmp), "out")
    system = {"kind": "linux", "family": "debian", "name": "Armbian 24.5 bookworm", "arch": "aarch64", "root": True, "sudo": False}
    session = ps.Session(core, "main", out, system, install_deps=lambda emit: emit("installing"))
    session.mode = mode
    server = ps.make_server(session, pin, "127.0.0.1", 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return core, session, server


class Client:
    def __init__(self, server, host=None):
        self.port, self.cookie, self.host = server.server_address[1], "", host

    def call(self, method, path, body=None, headers=None, login=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"X-Piso": "1", "Content-Type": "application/json"}
        if self.host:
            h["Host"] = self.host
        if self.cookie and login:
            h["Cookie"] = self.cookie
        h.update(headers or {})
        conn.request(method, path, json.dumps(body) if body is not None else None, h)
        r = conn.getresponse()
        data = r.read()
        cookie = r.getheader("Set-Cookie")
        if cookie:
            self.cookie = cookie.split(";")[0]
        try:
            return r.status, json.loads(data), r
        except ValueError:
            return r.status, data, r

    def do(self, action, **kw):
        status, state, _r = self.call("POST", "/api/do", dict(kw, action=action))
        assert status == 200, (action, status, state)
        return state

    def state(self):
        return self.call("GET", "/api/state")[1]

    def wait(self, cond, what, timeout=8.0):
        end = time.time() + timeout
        while time.time() < end:
            s = self.state()
            if cond(s):
                return s
            time.sleep(0.05)
        check(False, "timed out waiting for: " + what)
        return self.state()


core, session, server = start(mode=None)
c = Client(server)
status, body, r = c.call("GET", "/", login=False)
check(status == 200 and b"PisoHub Setup" in body and "frame" not in str(r.getheader("Content-Security-Policy")).replace("frame-", ""), "the page is served without a login")
check("default-src 'none'" in r.getheader("Content-Security-Policy") and r.getheader("X-Frame-Options") == "DENY" and r.getheader("Cache-Control") == "no-store",
      "and with strict security headers")
check(c.call("GET", "/api/state", login=False)[0] == 401, "the state needs a login")
check(c.call("POST", "/api/do", {"action": "next"}, login=False)[0] == 401, "an action needs a login")
check(c.call("POST", "/api/login", {"pin": "nope"})[0] == 403, "a wrong PIN is refused")
check(c.call("POST", "/api/login", {"pin": "123456"}, headers={"X-Piso": ""})[0] == 403, "a login without the page's header is refused (another site's form)")
check(c.call("GET", "/api/state", login=False, headers={"Cookie": "piso=forged"})[0] == 401, "a forged cookie does not work")
check(c.call("POST", "/api/login", {"pin": "123456"})[0] == 200 and "piso=" in c.cookie, "the right PIN logs in")
check(Client(server, host="evil.example.com").call("GET", "/", login=False)[0] == 403, "another site's name in Host (DNS rebinding) is refused")
check(Client(server, host="192.168.1.50:8765").call("GET", "/", login=False)[0] == 200 and Client(server, host="piso.local:8765").call("GET", "/", login=False)[0] == 200,
      "this computer's address and a .local name are accepted")
check(c.call("GET", "/api/state", headers={"Host": "evil.example.com"})[0] == 403, "also for the state")
check(c.call("POST", "/api/do", {"action": "__init__"})[0] == 400 and c.call("POST", "/api/do", {"action": "act"})[0] == 400, "an unknown action is refused")
check(c.call("POST", "/api/do", "not a dict")[0] == 400, "a body that is not an object is refused")
# five wrong PINs lock the login for a minute, even for the right one
spam = Client(server)
for _ in range(5):
    spam.call("POST", "/api/login", {"pin": "000000"})
status, body, _ = spam.call("POST", "/api/login", {"pin": "123456"})
check(status == 403 and "wait a minute" in body["error"], "five wrong PINs lock the login: " + str(body))

s = c.wait(lambda s: s["checks"] is not None, "the checks of this computer")
check(s["step"] == 0 and s["keys"][0] == "welcome" and [n for n in s["steps"]] == ["Start", "Router", "Settings", "Install", "Phones", "Finish"],
      "starts at the first step; there is no coin box page")
check(s["mode"] is None and not s["can_next"], "the first step waits for the choice: full system or PisoPhone only")
s = c.do("set_mode", mode="nonsense")
check(s["mode"] is None, "an unknown mode is ignored")
s = c.do("set_mode", mode="wifi")
check(s["mode"] == "wifi" and s["steps"] == ["Start", "Router", "Settings", "Install", "Finish"] and s["keys"][-1] == "finish",
      "PisoWiFi only: the router and coin box steps, without the Phones step")
s = c.do("set_mode", mode="full")
s = c.wait(lambda s: s["checks"] is not None, "the checks of this computer, again")
check(s["mode"] == "full" and s["system"]["name"] == "Armbian 24.5 bookworm" and len(s["checks"]) == 3 and s["can_next"], "it knows the system and has the checks")
s = c.do("install_deps")
s = c.wait(lambda s: not s["deps"]["running"] and s["checks"] is not None and s["deps"]["lines"], "the dependencies are installed")
check(s["deps"]["lines"] == ["installing"], "the installation of what is missing is shown")
c.do("recheck")
s = c.do("next")
check(s["step"] == 1, "on to the router")
s = c.wait(lambda s: s["router"]["host"] == "192.168.1.1" and s["router"]["state"] == "factory", "the router is found by itself")
check(s["can_next"], "a factory router: Next")
s = c.do("next")
check(s["step"] == 2 and not s["can_next"], "the settings: Next waits for the names")
check(all(not s["settings"]["values"][k] for k in ("GUEST_SSID", "SITE_NAME")), "no name is filled in beforehand")
for env, _label, lo, hi, _n in ps.PASSWORDS:
    check(ps.password_problem(s["settings"]["values"][env], lo, hi) is None, f"{env}: a valid password is already there")
first_pw = {k: s["settings"]["values"][k] for k in s["settings"]["values"] if k.endswith("PASSWORD")}
s = c.do("set", values={"GUEST_SSID": "Bad'Name"})
check(not s["can_next"] and "quotes" in s["settings"]["problems"]["GUEST_SSID"], "a bad Wi-Fi name: the reason is given")
s = c.do("set", values={"GUEST_SSID": "Tindahan WiFi", "SITE_NAME": "Aling Nena", "ROOT_PASSWORD": "short"})
check(not s["can_next"] and "ROOT_PASSWORD" in s["settings"]["problems"], "a short password: Next is off")
s = c.do("set", values={"ROOT_PASSWORD": "Rpass2345678", "KIOSK_PASSWORD": "Kpass234567", "BOX_NEW_ADMIN_PASSWORD": "Bpass23456789"}, country="sg")
check(s["can_next"] and s["settings"]["country"] == "SG", "everything valid: Next; the country as typed")
check(c.do("set", values={"__class__": "x", "NOPE": "y"})["settings"]["values"].get("NOPE") is None, "unknown fields are ignored")
s = c.do("next")
check(s["step"] == 3 and not s["can_next"], "the install page: Next waits for the installation")
s = c.wait(lambda s: s["usb"]["boards"] is not None, "the USB boards are looked for")
check(s["usb"]["boards"] == [["COM5", "USB-SERIAL CH340"]], "the board on USB is shown")
s = c.do("start_install")
check(s["install"]["running"], "the installation runs")
s = c.wait(lambda s: s["install"]["ok"], "the installation finishes")
sent = [x for x in core.calls if x[0] == "install"][0][1]
check(sent["GUEST_SSID"] == "Tindahan WiFi" and sent["ROOT_PASSWORD"] == "Rpass2345678" and sent["COUNTRY"] == "SG" and sent["SITE_NAME"] == "Aling Nena",
      "the answers were sent as typed: " + str(sent))
check([x[0] for x in core.calls if x[0] in ("flash", "install")] == ["flash", "install"] and s["usb"]["flashed"],
      "the coin box is written first, over USB, then the router")
check(core.products == ["full"], "the full system writes the full image: " + str(core.products))
log = "\n".join(s["install"]["lines"])
check("ESP32-C3 found" in log and "== Pairing the coin box" in log and s["install"]["stage"] == len(s["stages"]), "the log shows the progress")
check(s["final"]["BOX_NEW_ADMIN_PASSWORD"] == "Bpass23456789" and s["final"]["KIOSK_PASSWORD"] == "Kpass234567", "the passwords come from the router's summary")
check([x[0] for x in s["saved"]][:2] == ["Summary (every password)", "Printable setup sheet"] and [x[1] for x in s["saved"]][:2] == ["summary.txt", "sheet.html"]
      and s["saved"][2][1].startswith("pisophone-setup-log-"), "the saved files (and the setup log, last) are listed by name only (no paths)")
status, body, r = c.call("GET", "/api/saved?i=1")
check(status == 200 and b"setup sheet" in body and r.getheader("Content-Type").startswith("text/html"), "the setup sheet can be opened from the page")
check(c.call("GET", "/api/saved?i=7")[0] == 404 and c.call("GET", "/api/saved?i=-1")[0] == 404 and c.call("GET", "/api/saved?i=x")[0] == 404,
      "only the saved files can be fetched")
s = c.do("next")
check(s["step"] == 4, "on to the phones")
s = c.wait(lambda s: s["phones"]["state"] == "setup" and s["phones"]["box_up"] is True, "the phone setup link is made by itself")
prov = [x for x in core.calls if x[0] == "provisioning"][0]
check(prov[1]["BOX_NEW_ADMIN_PASSWORD"] == "Bpass23456789" and prov[2] == 1 and s["phones"]["url"].startswith("https://pisophone.pages.dev/#"),
      "slot 1 with the box admin and kiosk Wi-Fi passwords from the summary: " + str(prov))
s = c.do("open_phone", slot=3)
s = c.wait(lambda s: s["phones"]["state"] == "setup" and s["phones"]["slot"] == 3, "the next slot's link")
check("slot=3" in s["phones"]["url"], "slot 3")
s = c.do("open_phone", slot="99")
s = c.wait(lambda s: s["phones"]["state"] == "setup", "an absurd slot is clamped")
check(s["phones"]["slot"] == 10, "slot 10 at most")
s = c.do("next")
check(s["step"] == 5 and not s["can_next"], "the last page")
c.do("coin_test")
s = c.wait(lambda s: s["coin"]["ok"] is not None and not s["coin"]["running"], "the coin test")
run = [x for x in core.calls if x[0] == "run"][0]
check(run[1] == "piso-setup test-coin" and run[2] == "Rpass2345678" and s["coin"]["ok"] is True, "the coin test runs on the router with the router password")
s = c.do("telegram", token="nonsense")
check("bot token" in s["telegram"]["error"] and not s["telegram"]["running"], "a bad token is refused at once")
c.do("telegram", token="123456789:" + "A" * 35)
s = c.wait(lambda s: s["telegram"]["confirm"], "Telegram asks whether the chat is the owner's")
check("Evan" in s["telegram"]["confirm"], "the chat is shown")
c.do("telegram_answer", yes=True)
s = c.wait(lambda s: s["telegram"]["ok"], "Telegram connects")
tg = [x for x in core.calls if x[0] == "telegram"][0]
check(tg[1].startswith("123456789:") and tg[2] == "Aling Nena" and tg[3] is True, "the token and the site name were used: " + str(tg))

# the next unit
s = c.do("next_unit")
check(s["unit"] == 2 and s["step"] == 1, "the next unit starts at the router page")
check(s["settings"]["values"]["GUEST_SSID"] == "" and s["settings"]["values"]["SITE_NAME"] == "" and s["settings"]["country"] == "SG" and not s["install"]["ok"]
      and not s["usb"]["flashed"] and s["final"] == {} and s["saved"] == [] and s["coin"]["ok"] is None,
      "the names are empty again, the country is kept and nothing of the last unit is left")
check(all(s["settings"]["values"][k] != first_pw[k] for k in first_pw), "the passwords are new")
s = c.wait(lambda s: s["router"]["host"] is not None, "the next router is found")
server.shutdown()

# a router that was set up before: it keeps its settings, only the country is asked
core, session, server = start(state="configured")
c = Client(server)
c.call("POST", "/api/login", {"pin": "123456"})
c.wait(lambda s: s["checks"] is not None, "checks")
c.do("next")
c.wait(lambda s: s["router"]["host"] == "10.0.0.1", "found")
s = c.do("next")
check(s["step"] == 2 and s["settings"]["kept"] and s["can_next"], "a set-up router: no questions about names and passwords")
c.do("next")
c.wait(lambda s: s["usb"]["boards"] is not None, "boards")
c.do("start_install")
c.wait(lambda s: s["install"]["ok"], "installed")
sent = [x for x in core.calls if x[0] == "install"][0][1]
check(sent["ROOT_PASSWORD"] == "" and sent["GUEST_SSID"] == "" and sent["COUNTRY"] == "PH", "nothing but the country is sent: " + str(sent))
server.shutdown()

# a router with a password: it must be typed, a wrong one is refused
core, session, server = start(state="password")
c = Client(server)
c.call("POST", "/api/login", {"pin": "123456"})
c.do("next")
s = c.wait(lambda s: s["router"]["state"] == "password", "the router is found")
check(not s["can_next"], "a router with a password: Next waits for it")
c.do("router_password", password="wrong")
c.do("next")
s = c.wait(lambda s: s["router"]["login_failed"], "a wrong password is refused")
check(s["step"] == 1, "it says so and stays")
c.do("router_password", password="Rpass2345678")
c.do("next")
s = c.wait(lambda s: s["step"] == 2, "the right one goes on")
server.shutdown()

# no board, two boards, a failing flash, a failing install, a repeat
core, session, server = start()
c = Client(server)
c.call("POST", "/api/login", {"pin": "123456"})
core.boards = []
c.do("next")
c.wait(lambda s: s["router"]["host"], "router")
c.do("next")
c.do("set", values={"GUEST_SSID": "Shop", "SITE_NAME": "Shop"})
c.do("next")
s = c.wait(lambda s: s["usb"]["boards"] == [], "no board is found")
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"], "the missing board is reported")
check("no coin box board found on USB" in s["install"]["error"] and not any(x[0] == "install" for x in core.calls), "nothing was installed on the router: " + s["install"]["error"])
core.boards = [("COM5", "a"), ("COM6", "b")]
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"] and "several" in s["install"]["error"], "two boards")
core.boards = [("COM5", "a")]
core.fail_flash = True
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"] and "Failed to connect" in s["install"]["error"], "the flash failure is reported")
check(not any(x[0] == "install" for x in core.calls) and not s["usb"]["flashed"], "the router is not touched when the box could not be written")
core.fail_flash = False
core.fail_install = True
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"] and "could not be prepared" in s["install"]["error"], "a failing installation")
check(s["usb"]["flashed"] and s["step"] == 3 and not s["can_next"], "the box was written; the page stays and shows why")
core.fail_install = False
core.calls.clear()
c.do("start_install")
s = c.wait(lambda s: s["install"]["ok"], "the second try works")
check(not any(x[0] == "flash" for x in core.calls), "a box that was written is not written again on a retry")
core.boards = []
c.do("next_unit")
c.do("next")  # (the router page of unit 2)
c.wait(lambda s: s["router"]["host"], "router 2")
c.do("next")
c.do("set", values={"GUEST_SSID": "B", "SITE_NAME": "B"})
c.do("next")
c.do("skip_flash", value=True)
c.do("start_install")
s = c.wait(lambda s: s["install"]["ok"], "with the box marked as ready the router installs")
check(not any(x[0] == "flash" for x in core.calls), "and nothing is written to a box")
server.shutdown()

# the real functions behind the page (not the fakes): the target of the phone setup page
t = ps.provisioning_target("beta", {"BOX_NEW_ADMIN_PASSWORD": "pw", "KIOSK_PASSWORD": "kp"}, 2,
                           fetch=lambda host, password: {"mac": "AA:BB", "secret": "SEC"})
check(t[0] == "setup" and t[1].startswith("https://beta.pisophone.pages.dev/#") and "secret=SEC" in t[1] and "slot=2" in t[1] and "wifi_pass=kp" in t[1],
      "the phone setup link is filled in: " + str(t))
t = ps.provisioning_target("main", {}, 1)
check(t[0] == "box" and t[1] == "http://10.0.0.10/" and "not known" in t[2], "no box password: the box's own page")
t = ps.provisioning_target("main", {"BOX_NEW_ADMIN_PASSWORD": "pw"}, 1, fetch=lambda h, p: (_ for _ in ()).throw(ps.SetupError("does not answer")))
check(t[0] == "box" and "does not answer" in t[2], "the box does not answer: said")
check(ps.make_server(session, "123456", "127.0.0.1", 0).server_address[1] > 0, "a free port is found")

# the Check-up page: read-only, runs in the background, shows what the core found
cu_core, cu_session, cu_server = start()
cu = Client(cu_server)
cu.call("POST", "/api/login", {"pin": "123456"})
_st, snap, _r = cu.call("GET", "/api/state")
check(snap["checkup"] == {"running": False, "done": False, "items": []}, "the check-up starts empty: " + str(snap["checkup"]))
cu.do("checkup")
for _ in range(50):
    _st, snap, _r = cu.call("GET", "/api/state")
    if snap["checkup"]["done"]:
        break
    time.sleep(0.1)
check(snap["checkup"]["done"] and [i["name"] for i in snap["checkup"]["items"]] == ["Router", "Print server"], "the check-up result reaches the page: " + str(snap["checkup"]))
_st, page, _r = cu.call("GET", "/")
page = page.decode() if isinstance(page, bytes) else str(page)
check('id="btnCheckup"' in page and 'id="pwOwn"' in page and "My phone can't open this page" in page,
      "the page has the Check-up button, the passwords under Advanced and the can't-open help")
check(page.index('id="pwOwn"') < page.index('id="ROOT_PASSWORD"') < page.index("</details>", page.index('id="pwOwn"')) and "Advanced: type my own passwords" in page,
      "the three password fields are inside the Advanced section")

# ---- 3. the page in a real browser -------------------------------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None
    print("note: playwright is not installed: the page itself was not run in a browser")

if sync_playwright:
    import glob
    chrome = os.environ.get("CHROME") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome")), None)
    core, session, server = start(mode=None)
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def until(cond, what, timeout=15.0):
        """Polls from here: the page's own security policy forbids Playwright's string-evaluating waits (a good sign)."""
        end = time.time() + timeout
        while time.time() < end:
            try:
                if cond():
                    return True
            except Exception:
                pass
            time.sleep(0.05)
        check(False, "timed out waiting for: " + what)
        return False

    def shot(page, name):
        if SHOTS:
            os.makedirs(SHOTS, exist_ok=True)
            page.screenshot(path=os.path.join(SHOTS, name + ".png"), full_page=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
        page = browser.new_page(viewport={"width": 390, "height": 800})   # a phone
        problems = []
        page.on("console", lambda m: m.type == "error" and "401" not in m.text and "403" not in m.text and problems.append(m.text))
        page.on("pageerror", lambda e: problems.append(str(e)))
        page.goto(base)
        page.wait_for_selector("#login", state="visible")
        check(page.is_visible("#pin"), "a phone sees the PIN page first")
        page.fill("#pin", "999999")
        page.click("#pinBtn")
        until(lambda: page.inner_text("#pinErr"), "the wrong PIN is reported")
        check("Wrong PIN" in page.inner_text("#pinErr"), "a wrong PIN says so")
        page.fill("#pin", "123456")
        page.click("#pinBtn")
        page.wait_for_selector("#login", state="hidden")
        page.wait_for_selector("#checks .kv")
        check("Armbian" in page.inner_text("#sysline") and page.is_visible("#s0"), "the first step shows what this computer is")
        check(not page.is_visible("#btnDeps"), "nothing missing: no install button")
        shot(page, "1-start")
        check(page.is_disabled("#btnNextStep"), "Next waits for the choice of system")
        page.check("input[name=mode][value=full]")
        until(lambda: not page.is_disabled("#btnNextStep"), "Next opens once the full system is chosen")
        page.click("#btnNextStep")
        page.wait_for_selector("#s1", state="visible")
        until(lambda: "192.168.1.1" in page.inner_text("#routerBox"), "the router is found")
        shot(page, "2-router")
        page.click("#btnNextStep")
        page.wait_for_selector("#s2", state="visible")
        check(page.input_value("#GUEST_SSID") == "" and page.is_disabled("#btnNextStep"), "the names are asked: empty, Next is off")
        page.fill("#GUEST_SSID", "Bad'Name")
        until(lambda: "uotes" in page.inner_text("[data-e=GUEST_SSID]"), "the reason is shown as it is typed")
        check(True, "a bad name shows its reason as it is typed")
        page.fill("#GUEST_SSID", "Tindahan WiFi")
        page.fill("#SITE_NAME", "Aling Nena")
        page.select_option("#COUNTRY", "SG")
        until(lambda: page.is_enabled("#btnNextStep"), "Next is on")
        check(not page.is_visible("#ROOT_PASSWORD") and not page.is_visible("#KIOSK_PASSWORD"), "the passwords are folded away under Advanced")
        page.click("#pwOwn summary")
        made = page.input_value("#ROOT_PASSWORD")   # the one made for this unit
        check(len(made) >= 8, "a password was made for the unit")
        page.fill("#ROOT_PASSWORD", "short")
        until(lambda: "ssword" in page.inner_text("[data-e=ROOT_PASSWORD]") or page.inner_text("[data-e=ROOT_PASSWORD]"), "a short typed password is explained")
        check(page.is_visible("[data-e=ROOT_PASSWORD]") and page.is_disabled("#btnNextStep"), "a typed password is checked in the Advanced section: Next is off")
        page.fill("#ROOT_PASSWORD", made)
        until(lambda: page.is_enabled("#btnNextStep"), "Next is on again")
        page.click("#btnTools")
        page.wait_for_selector("#toolsMain", state="visible")
        page.click("#btnCheckup")
        page.wait_for_selector("#cuList .kv")
        check("Answers at 10.0.0.1" in page.inner_text("#cuList") and "Not used" in page.inner_text("#cuList"), "the Check-up page lists what the core found: " + page.inner_text("#cuList"))
        shot(page, "owner-tools-checkup")
        page.click("#btnTools")
        page.wait_for_selector("#s2", state="visible")
        shot(page, "3-settings")
        page.click("#btnNextStep")
        page.wait_for_selector("#s3", state="visible")
        until(lambda: "CH340" in page.inner_text("#usbText"), "the board is shown")
        check("Tindahan WiFi" in page.inner_text("#review") and "Aling Nena" in page.inner_text("#review"), "the review shows the names")
        shot(page, "4-install-ready")
        page.click("#btnStart")
        until(lambda: "Installation complete" in page.inner_text("#instBanner"), "the installation finishes")
        check("== Pairing the coin box" in page.inner_text("#instLog"), "the log is shown")
        shot(page, "4-install-done")
        page.click("#btnNextStep")
        page.wait_for_selector("#s4", state="visible")
        page.wait_for_selector("#provBox a")
        check(page.get_attribute("#provBox a", "href").startswith("https://pisophone.pages.dev/#") and "Bpass23456789" in page.inner_text("#pwList"),
              "the phone setup link and the passwords are shown")
        shot(page, "5-phones")
        page.click("#btnNextStep")
        page.wait_for_selector("#s5", state="visible")
        page.click("#btnCoin")
        until(lambda: "counted the coin" in page.inner_text("#coinBox"), "the coin test passes")
        check(page.is_hidden("#btnNextStep"), "the last page has no Next")
        check(page.get_attribute("#savedList a", "href") == "/api/saved?i=0", "the saved files can be opened")
        shot(page, "6-finish")
        page.click("#btnNext")
        page.wait_for_selector("#s1", state="visible")
        check("Unit 2" in page.inner_text("#unit") and "Unit 2" in page.inner_text("#unitNote"), "the next unit starts over at the router")
        page.click("#btnNextStep")
        page.wait_for_selector("#s2", state="visible")
        check(page.input_value("#GUEST_SSID") == "" and page.input_value("#SITE_NAME") == "", "its names are empty again")
        shot(page, "7-next-unit")
        check(not problems, f"no script error on the page: {problems}")
        overflow = page.evaluate("document.documentElement.scrollWidth > window.innerWidth")
        check(not overflow, "no sideways scrolling on a phone screen")
        # feature 010: PisoWiFi only hides the rental-phone password, the full system shows it
        for mode_value, kiosk_visible in (("wifi", False), ("full", True)):
            core2, session2, server2 = start(mode=None)
            page2 = browser.new_page(viewport={"width": 390, "height": 800})
            page2.goto(f"http://127.0.0.1:{server2.server_address[1]}")
            page2.fill("#pin", "123456")
            page2.click("#pinBtn")
            page2.wait_for_selector("#login", state="hidden")
            page2.wait_for_selector("#checks .kv")
            check(page2.is_disabled("#btnNextStep") and not page2.is_checked("input[name=mode][value=wifi]") and not page2.is_checked("input[name=mode][value=full]") and not page2.is_checked("input[name=mode][value=phone]"), "nothing is chosen at the start")
            page2.check(f"input[name=mode][value={mode_value}]")
            until(lambda: not page2.is_disabled("#btnNextStep"), "Next opens")
            page2.click("#btnNextStep")
            page2.wait_for_selector("#s1", state="visible")
            until(lambda: "192.168.1.1" in page2.inner_text("#routerBox"), "the router is found")
            page2.click("#btnNextStep")
            page2.wait_for_selector("#s2", state="visible")
            page2.click("#pwOwn summary")
            check(page2.is_visible("#KIOSK_PASSWORD") == kiosk_visible and page2.is_visible("#ROOT_PASSWORD"),
                  f"{mode_value}: the rental-phone password field is {'shown' if kiosk_visible else 'hidden'} in the page")
            page2.close()
            server2.shutdown()
        browser.close()
    server.shutdown()

# ---- feature 010: the page's three choices send the product to the router ------------------------------------------------------------
for mode, product in (("full", "both"), ("wifi", "wifi")):
    core, session, server = start(mode=mode)
    a = session.install_answers()
    check(a["SHOP_PRODUCT"] == product, f"the {mode!r} choice sends SHOP_PRODUCT={product}: {a}")
    if mode == "wifi":
        session.values["KIOSK_PASSWORD"] = ""
        check(a["KIOSK_PASSWORD"] == "" and not session.settings_problems().get("KIOSK_PASSWORD"), "wifi: no rental-phone password is sent or required")
    else:
        check(len(a["KIOSK_PASSWORD"]) == 12 and a["KIOSK_PASSWORD"] == session.values["KIOSK_PASSWORD"], "both: the rental-phone password is sent")
    session.router["state"] = "configured"
    k = session.install_answers()
    check(k["SHOP_PRODUCT"] == product and k["KIOSK_PASSWORD"] == "" and k["GUEST_SSID"] == "", f"a router set up before is sent the product ({product}) and nothing else")
    server.shutdown()
core, session, server = start(mode=None)
check(session.mode is None and not session.can_next(), "nothing is preselected: the first step waits for the choice")
status, page, _r = Client(server).call("GET", "/", login=False)
page = page.decode() if isinstance(page, bytes) else page
check('id="kioskPw"' in page and "S.mode==='wifi'" in page and page.count('name="mode"') == 3 and "checked" not in page.split('name="mode"')[1].split(">")[0],
      "the page has the three choices, none checked, and hides the rental-phone password for PisoWiFi only")
server.shutdown()
# ---- feature 045 (SU-18): the setup log file, so a failed real run leaves evidence to send ----------------------------------------------
import glob, stat


def log_files(session):
    return sorted(glob.glob(os.path.join(session.out_dir, "pisophone-setup-log-*.txt")))


def walk_to_install(c):
    c.call("POST", "/api/login", {"pin": "123456"})
    c.wait(lambda s: s["checks"] is not None, "checks")
    c.do("next")
    c.wait(lambda s: s["router"]["host"] == "10.0.0.1", "found")
    c.do("next")
    c.do("next")
    c.wait(lambda s: s["usb"]["boards"] is not None, "boards")


core, session, server = start(state="configured")
session.network_report = lambda: ["eth0 192.168.1.50/24 (the cable)", "default via 192.168.1.1 dev eth0"]
c = Client(server)
walk_to_install(c)
c.do("start_install")
s = c.wait(lambda s: s["install"]["ok"], "installed")
session.router_password = "TypedRouterPw99"
session.log("the router password is TypedRouterPw99 and the kiosk one is " + session.values["KIOSK_PASSWORD"])
logs = log_files(session)
check(len(logs) == 1, "a run writes one pisophone-setup-log-*.txt in the saved folder: " + str(logs))
text = open(logs[0], encoding="utf-8").read() if logs else ""
check(stat.S_IMODE(os.stat(logs[0]).st_mode) == 0o600 if logs else False, "the setup log is private (0600)")
check("Downloading the PisoPhone setup" in text and "== Pairing the coin box" in text and "PASS  the coin box answers" in text, "every page line is in the log")
check("192.168.1.50/24" in text and "default via 192.168.1.1" in text, "the log starts with this computer's addresses and routes")
check("TypedRouterPw99" not in text and session.values["KIOSK_PASSWORD"] not in text and "***" in text, "the unit's passwords are written as ***")
check(s["saved"][-1][0].startswith("Setup log") and s["saved"][-1][1] == os.path.basename(logs[0]), "the last page lists the log: " + str(s["saved"]))
server.shutdown()

core, session, server = start(state="configured")
core.fail_install = True
c = Client(server)
walk_to_install(c)
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"], "the install fails")
logs = log_files(session)
check(logs and os.path.basename(logs[0]) in s["install"]["error"] and "could not be prepared" in s["install"]["error"],
      "the error banner names the log file: " + str(s["install"]["error"]))
server.shutdown()

# the PisoPhone-only path writes to the same kind of file, with its own secrets hidden
core, session, server = start(mode="phone")
session.p["values"]["WIFI_PASSWORD"] = "ShopWifiPw123"
session.p["values"]["ADMIN_PASSWORD"] = "BoxAdminPw4567"
session.plog("== Looking at the board; shop Wi-Fi password ShopWifiPw123, admin BoxAdminPw4567")
logs = log_files(session)
text = open(logs[0], encoding="utf-8").read() if logs else ""
check("== Looking at the board" in text and "ShopWifiPw123" not in text and "BoxAdminPw4567" not in text, "PisoPhone only: lines are logged, its passwords hidden")
server.shutdown()

# a log that cannot be written never stops the setup
core, session, server = start()
session.out_dir = os.path.join(tmp, "no", "such", "dir", "\0bad")
session.setup_log.out_dir = session.out_dir
try:
    session.log("still works")
    check("still works" in session.install["lines"], "an unwritable log does not stop the setup")
except Exception as e:
    check(False, "an unwritable log must not raise: " + repr(e))
server.shutdown()

# ---- feature 045 (SU-09): Try again after the router password was set logs in with the saved one -------------------------------------------
class PasswordCore(FakeCore):
    """A router that gets its password in the first run, which then breaks off: the program's ssh login still has no password."""

    def __init__(self, saved=True):
        super().__init__(state="factory")
        self.router_pw, self.ssh_pw, self.keeps_saved = None, "", saved

    def probe(self, host): return "password" if self.router_pw else "factory"

    def saved_login(self, host, out_dir):
        if self.keeps_saved and self.router_pw and not self.ssh_pw:
            self.ssh_pw = self.router_pw
            return self.router_pw
        return None

    def login(self, host, password):
        self.calls.append(("login", password))
        if password == self.router_pw:
            self.ssh_pw = password
        return password == self.router_pw

    def install(self, host, answers, out_dir, emit, stage, notify):
        if self.router_pw is None:
            self.router_pw = answers["ROOT_PASSWORD"]   # the setup changed the password, then the connection broke
            self.calls.append(("install", dict(answers)))
            raise ps.SetupError("the connection to the router was lost before the setup finished. Try again.")
        if self.ssh_pw != self.router_pw:
            raise ps.SetupError("the router refused the login")   # (what a retry did before: ssh had no password)
        return FakeCore.install(self, host, answers, out_dir, emit, stage, notify)


def walk_to_wifi_install(c):
    c.call("POST", "/api/login", {"pin": "123456"})
    c.wait(lambda s: s["checks"] is not None, "checks")
    c.do("next")
    c.wait(lambda s: s["router"]["host"] is not None and s["router"]["state"], "router found")
    c.do("next")
    c.do("set", values={"GUEST_SSID": "Tindahan WiFi", "SITE_NAME": "Aling Nena", "ROOT_PASSWORD": "Rpass2345678", "BOX_NEW_ADMIN_PASSWORD": "Bpass23456789"})
    c.do("next")
    c.wait(lambda s: s["usb"]["boards"] is not None, "boards")


core, session, server = start(core=PasswordCore(), mode="wifi")
c = Client(server)
walk_to_wifi_install(c)
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"], "the first run breaks off")
check("lost" in s["install"]["error"], "the first run broke off after the password was set: " + s["install"]["error"])
c.do("start_install")
s = c.wait(lambda s: s["install"]["ok"] or (s["install"]["error"] and not s["install"]["running"] and "lost" not in s["install"]["error"]), "the retry ends")
check(s["install"]["ok"], "Try again logs in with the saved router password and finishes: " + str(s["install"]["error"]))
server.shutdown()

# no saved password: the retry stops before the install and sends the owner to the Router step to type it
core, session, server = start(core=PasswordCore(saved=False), mode="wifi")
c = Client(server)
walk_to_wifi_install(c)
c.do("start_install")
c.wait(lambda s: s["install"]["error"], "the first run breaks off")
installs = len([x for x in core.calls if x[0] == "install"])
c.do("start_install")
s = c.wait(lambda s: s["install"]["error"] and "Router step" in s["install"]["error"], "the retry names the Router step")
check("password" in s["install"]["error"] and len([x for x in core.calls if x[0] == "install"]) == installs, "nothing was installed; it asks for the password: " + s["install"]["error"])
check(not s["router"]["login_ok"], "the Router step asks for the password again")
server.shutdown()

# ---- feature 045 (SU-01): two networks in 192.168.1.x stop the search before any ssh, with the hotspot advice ---------------------------------
class ClashCore(FakeCore):
    def __init__(self):
        super().__init__(state="factory")
        self.advice, self.probed = "This computer is on two networks that use 192.168.1.x. Put its Wi-Fi on your phone's hotspot.", []

    def network_clash(self): return self.advice
    def probe(self, host):
        self.probed.append(host)
        return "factory"


core, session, server = start(core=ClashCore(), mode="wifi")
c = Client(server)
c.call("POST", "/api/login", {"pin": "123456"})
c.wait(lambda s: s["checks"] is not None, "checks")
c.do("next")
s = c.wait(lambda s: s["router"]["error"], "the clash is reported")
check("hotspot" in s["router"]["error"] and s["router"]["host"] is None and not s["can_next"], "two networks in the same range: advice on the Router step, no router, no Next: " + str(s["router"]["error"]))
check(core.probed == [] and not any(x[0] in ("login", "install", "run") for x in core.calls), "nothing was sent to any router (no probe, no login)")
core.advice = None
c.do("search")
s = c.wait(lambda s: s["router"]["host"] == "192.168.1.1", "Check again finds the router once the clash is gone")
check(s["router"]["error"] is None and s["can_next"], "and the advice is gone")
server.shutdown()

# ---- feature 045 (SU-15): the Phones step does not offer the setup page while the router cannot reach the app's address ----------------------------
core, session, server = start(state="configured")
core.has_internet = False
c = Client(server)
walk_to_install(c)
c.do("start_install")
c.wait(lambda s: s["install"]["ok"], "installed")
s = c.do("next")
check(s["keys"][s["step"]] == "phones", "on the Phones step")
s = c.wait(lambda s: s["phones"]["state"] == "nointernet", "the router has no internet")
check(s["phones"]["url"] == "" and not [x for x in core.calls if x[0] == "provisioning"], "no phone setup page is made while the router cannot reach the website: " + str(s["phones"]))
check([x for x in core.calls if x[0] == "reach"][0][1] == "10.0.0.1", "the router at 10.0.0.1 was asked")
core.has_internet = True
s = c.do("open_phone", slot=1)
s = c.wait(lambda s: s["phones"]["state"] == "setup", "Check again: the router reaches the website now")
check(s["phones"]["url"].startswith("https://pisophone.pages.dev/#"), "and then the setup page is made: " + str(s["phones"]["url"]))
status, page, _r = c.call("GET", "/", login=False)
page = page.decode() if isinstance(page, bytes) else page
check("p.state==='nointernet'" in page and "WAN port" in page, "the page says to plug the modem into the router's WAN port")
server.shutdown()

# ---- feature 046 (SU-11): the MAC of the board the page wrote goes to the router; a board that was not written adds none -------------------------
for skip, want in ((False, "AA:BB:CC:DD:EE:01"), (True, "")):
    core, session, server = start(state="configured")
    c = Client(server)
    walk_to_install(c)
    if skip:
        c.do("skip_flash", value=True)
    c.do("start_install")
    c.wait(lambda s: s["install"]["ok"], "installed")
    sent = [x for x in core.calls if x[0] == "install"][0][1]
    check(sent.get("BOX_EXPECT_MAC") == want, f"{'Do not write it' if skip else 'written by the page'}: BOX_EXPECT_MAC is {want!r}: {sent.get('BOX_EXPECT_MAC')!r}")
    if not skip:
        c.do("next_unit")
        s = c.state()
        check(session.box_mac == "", "the next unit starts with no board MAC")
    server.shutdown()
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
