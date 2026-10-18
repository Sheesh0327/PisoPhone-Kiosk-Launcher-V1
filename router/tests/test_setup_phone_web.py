#!/usr/bin/env python3
"""The PisoPhone-only mode of the setup page (setup/pisophone_setup.py: the coin box on the shop's normal router, set up over USB, no
router software, no SSH) end to end with a pretend coin box (no board, no network): the choice at the start, the checks, the Wi-Fi settings, the installation through its stages, a board that already runs a set-up coin box (it is erased only on the owner's
yes), a failed installation that can be tried again, the phones and finish steps, the next coin box, and the whole page walked in a
real browser at phone width. Run:  python3 router/tests/test_setup_phone_web.py   (the browser part needs Playwright)"""
import http.client, importlib.util, json, os, stat, sys, tempfile, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)

tmp = tempfile.mkdtemp()
os.environ["PISOPHONE_HOME"] = os.path.join(tmp, "home")
os.environ["PISOPHONE_KEYS"] = os.path.join(tmp, "keys")
os.makedirs(os.environ["PISOPHONE_KEYS"])
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


STATUS = {"fw": "1.0.0", "mac": "aa:bb:cc:00:11:22", "setup": "done", "wifi": "up", "ip": "192.168.1.77"}


class FakeCore:
    """The USB board and the network, as the page uses them."""

    def __init__(self):
        self.calls, self.fail_flash, self.existing, self.boards = [], False, None, [("COM5", "USB Serial Device")]
        self.checks = [("internet and the coin box software on GitHub", True, ""), ("USB flashing tool (esptool)", True, "")]
        self.up = True

    def preflight(self): raise AssertionError('the full system checks must not run in the PisoPhone-only mode')
    def preflight_phone(self): return self.checks
    def usb_boards(self): return list(self.boards)
    def probe_box(self, port): return self.existing
    def box_reachable(self, ip): return self.up

    def box_checkup(self):
        return [{"name": "Board on USB", "state": "ok", "text": "USB Serial Device on COM5.", "fix": ""},
                {"name": "Admin page", "state": "warn", "text": "does not answer.", "fix": "Is this computer on the same Wi-Fi?"}]

    def flash_box(self, port, emit):
        self.calls.append(("flash", port))
        emit("== Looking at the board on " + port)
        emit("ESP32-C3 found. Software version 1.0.0 (1.33 MB).")
        if self.fail_flash:
            emit("A fatal error occurred: Failed to connect to ESP32-C3: No serial data received.")
            raise ps.SetupError("the flashing tool stopped: Failed to connect")
        emit("Writing at 0x00010000... (50 %)")
        return "ESP32-C3", "1.0.0"

    def provision(self, port, wifi, pw, admin, emit, stage):
        self.calls.append(("provision", port, wifi, pw, admin))
        stage(1)
        emit("== Giving the board your Wi-Fi and a new admin password")
        stage(2)
        emit("== Waiting for the board to join your Wi-Fi (up to two minutes)")
        return dict(STATUS)


system = {"kind": "linux", "family": "debian", "name": "Test", "arch": "x86_64", "root": False, "sudo": False}
core = FakeCore()
out = os.path.join(tmp, "out")
session = ps.Session(core, "main", out, system, install_deps=lambda emit: emit("x"))
server = ps.make_server(session, "123456", "127.0.0.1", 0)
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_address[1]
cookie = ""


def call(method, path, body=None, host=None):
    global cookie
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"X-Piso": "1", "Content-Type": "application/json"}
    if cookie:
        h["Cookie"] = cookie
    if host:
        h["Host"] = host
    conn.request(method, path, json.dumps(body) if body is not None else None, h)
    resp = conn.getresponse()
    data = resp.read()
    if resp.getheader("Set-Cookie"):
        cookie = resp.getheader("Set-Cookie").split(";")[0]
    try:
        return resp.status, json.loads(data)
    except ValueError:
        return resp.status, data


def act(action, **kw):
    return call("POST", "/api/do", dict(action=action, **kw))[1]


def wait(pred, what, seconds=10):
    end = time.time() + seconds
    while time.time() < end:
        state = call("GET", "/api/state")[1]
        if pred(state):
            return state
        time.sleep(0.05)
    check(False, "timed out waiting for " + what)
    return call("GET", "/api/state")[1]


# ---- the PIN and the server ---------------------------------------------------------------------------------------------------
st, page = call("GET", "/")
check(st == 200 and b"What are you setting up?" in page and b"PisoPhone only" in page and b"The full TechNet PisoHub" in page, "the page opens with the choice: the full system or PisoPhone only")
phone_html = page.split(b'data-key="p_settings"', 1)[1].split(b"</main>", 1)[0]
check(b"openwrt" not in phone_html.lower().replace(b"nothing is installed on", b"") and b"ssh" not in phone_html.lower() and b"telegram" not in phone_html.lower(), "the PisoPhone-only sections have nothing about OpenWrt, SSH or Telegram")
check(call("GET", "/api/state")[0] == 401, "the state needs the PIN")
check(call("POST", "/api/login", {"pin": "nope"})[0] == 403, "a wrong PIN is refused")
check(call("POST", "/api/login", {"pin": "123456"})[0] == 200, "the right PIN opens it")
check(call("GET", "/api/state", host="evil.example")[0] == 403, "another site's host name is refused (DNS rebinding)")
check(call("POST", "/api/do", {"action": "../x"})[0] == 400 and call("POST", "/api/do", {"action": "nothing_here"})[0] == 400, "an unknown action is refused")

# ---- start: the choice, then the settings --------------------------------------------------------------------------------------
s = wait(lambda s: s["checks"] is not None, "the checks")
check(s["mode"] is None and not s["can_next"], "the start waits for the choice of system")
s = act("set_mode", mode="phone")
s = wait(lambda s: s["checks"] is not None, "the checks of the PisoPhone-only mode")
check(s["mode"] == "phone" and s["step"] == 0 and s["keys"] == ["welcome", "p_settings", "p_install", "p_phones", "p_finish"] and s["steps"][1] == "Settings", "five steps, no router step")
check([c[1] for c in s["checks"]] == [True, True] and s["can_next"], "the checks of this computer are shown")
s = act("next")
check(s["step"] == 1 and not s["can_next"], "the settings step cannot go on while the Wi-Fi is empty")
admin = s["p"]["settings"]["values"]["ADMIN_PASSWORD"]
check(len(admin) == 12 and "WIFI_NAME" in s["p"]["settings"]["problems"] and "WIFI_PASSWORD" in s["p"]["settings"]["problems"], "an admin password is made for you; the Wi-Fi is asked")
s = act("pset", values={"WIFI_NAME": "Dela Cruz 5G", "WIFI_PASSWORD": "short"})
check(not s["can_next"] and "WIFI_PASSWORD" in s["p"]["settings"]["problems"], "a Wi-Fi password under 8 characters is refused")
s = act("pset", values={"WIFI_PASSWORD": "p@ss w0rd!", "ADMIN_PASSWORD": "Coinslot@Setup"})
check(not s["can_next"] and "ADMIN_PASSWORD" in s["p"]["settings"]["problems"], "the factory admin password is refused")
s = act("pset", values={"ADMIN_PASSWORD": "Shop-Admin-2026"})
check(s["can_next"] and not s["p"]["settings"]["problems"], "good settings can go on")
check(act("pset", values={"NOT_A_KEY": "x", "WIFI_NAME": "x" * 500})["p"]["settings"]["values"]["WIFI_NAME"] == "Dela Cruz 5G", "unknown or oversized values are ignored")
s = act("next")
check(s["step"] == 2, "on to the install step")
s = wait(lambda s: s["usb"]["boards"] is not None, "the USB boards")
check(s["usb"]["boards"] == [["COM5", "USB Serial Device"]], "the board is found")

# ---- a board that already runs a set-up coin box is not erased by accident -------------------------------------------------------
core.existing = dict(STATUS)
act("pstart_install")
s = wait(lambda s: not s["p"]["install"]["running"] and s["p"]["install"]["error"], "the refusal")
check("already runs a set-up coin box" in s["p"]["install"]["error"] and s["p"]["erase_needed"] and not core.calls, "a working box: nothing is written until the owner says so")
act("perase_ok", value=True)
core.existing = None

# ---- a failed installation, then a good one -------------------------------------------------------------------------------------
core.fail_flash = True
act("pstart_install")
s = wait(lambda s: not s["p"]["install"]["running"] and s["p"]["install"]["error"] and "Failed to connect" in s["p"]["install"]["error"], "the failure")
check(not s["p"]["install"]["ok"] and not s["can_next"] and "USB DATA cable" in (s["p"]["install"]["banner"] or ["", ""])[1] or "data cable" in (s["p"]["install"]["banner"] or ["", ""])[1].lower(), "a failed write is explained, with what to try")
core.fail_flash = False
core.calls.clear()
act("pstart_install")
s = wait(lambda s: s["p"]["install"]["ok"], "the installation")
check([c[0] for c in core.calls] == ["flash", "provision"] and core.calls[1][2:] == ("Dela Cruz 5G", "p@ss w0rd!", "Shop-Admin-2026"), "the software is written, then the Wi-Fi and the admin password are given")
check(s["p"]["install"]["stage"] == len(s["p"]["stages"]) and s["can_next"] and s["p"]["final"]["ip"] == "192.168.1.77" and s["p"]["final"]["admin"] == "Shop-Admin-2026", "done: the address, the box code and the password are known")
summary = os.path.join(out, "pisophone-box-aabbcc001122.txt")
text = open(summary).read()
check("http://192.168.1.77/" in text and "Shop-Admin-2026" in text and "aa:bb:cc:00:11:22" in text and (os.name == "nt" or stat.S_IMODE(os.stat(summary).st_mode) == 0o600),
      "the summary is saved, readable only by the owner")
st, body = call("GET", "/api/saved?i=0")
check(st == 200 and b"Shop-Admin-2026" in body and call("GET", "/api/saved?i=5")[0] == 404 and call("GET", "/api/saved?i=../x")[0] == 404, "it can be opened from the page, and nothing else can")
check(act("skip_flash", value=True)["usb"]["skip"] and not act("skip_flash", value=False)["usb"]["skip"], "'do not write it' can be ticked")

# ---- phones and finish ------------------------------------------------------------------------------------------------------------
s = act("next")
s = wait(lambda s: s["p"]["phones"]["box_up"] is not None, "the box check")
check(s["step"] == 3 and s["p"]["phones"]["url"] == "http://192.168.1.77/" and s["p"]["phones"]["qr"].startswith("<svg") and s["p"]["phones"]["box_up"], "the phones step has the admin page address, its QR code and the box check")
core.up = False
s = act("precheck_box")
s = wait(lambda s: s["p"]["phones"]["box_up"] is False, "the box check again")
check(s["p"]["phones"]["box_up"] is False, "a box this computer cannot reach is reported (not as a failure)")
s = act("next")
check(s["step"] == 4 and not s["can_next"], "the finish step is the last")
s = act("checkup")
s = wait(lambda s: s["checkup"]["done"], "the check-up")
check([i["state"] for i in s["checkup"]["items"]] == ["ok", "warn"], "the check-up lists what it found")
s = act("next_unit")
check(s["unit"] == 2 and s["step"] == 1 and s["mode"] == "phone" and not s["p"]["install"]["ok"] and s["p"]["settings"]["values"]["WIFI_NAME"] == ""
      and s["p"]["settings"]["values"]["ADMIN_PASSWORD"] != "Shop-Admin-2026" and not s["p"]["erase_ok"] and s["saved"] == [], "the next coin box starts at the settings with a new admin password")

# ---- the page in a real browser, at phone width ---------------------------------------------------------------------------------
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sync_playwright = None
if sync_playwright is None:
    print("note: playwright is not installed, the browser part is skipped")
else:
    import glob, shutil
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
    core2 = FakeCore()
    session2 = ps.Session(core2, "main", os.path.join(tmp, "out2"), system, install_deps=lambda emit: emit("x"))
    server2 = ps.make_server(session2, "654321", "127.0.0.1", 0)
    threading.Thread(target=server2.serve_forever, daemon=True).start()
    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        page = browser.new_page(viewport={"width": 390, "height": 800})
        problems = []
        page.on("pageerror", lambda e: problems.append(str(e)))
        page.goto(f"http://127.0.0.1:{server2.server_address[1]}/")
        page.fill("#pin", "654321")
        page.click("#pinBtn")
        page.wait_for_selector("#nav", state="visible")
        for _ in range(50):   # (the bar is drawn by the page's first poll of the state)
            if page.inner_text("#nav").split("\n") == ["Start", "Settings", "Install", "Phones", "Finish"]:
                break
            time.sleep(0.1)
        page.check("input[name=mode][value=phone]")
        for _ in range(50):   # (the bar and the checks are drawn by the page's next polls of the state)
            if page.inner_text("#nav").split("\n") == ["Start", "Settings", "Install", "Phones", "Finish"] and "internet" in page.inner_text("#checks"):
                break
            time.sleep(0.1)
        check(page.inner_text("#nav").split("\n") == ["Start", "Settings", "Install", "Phones", "Finish"], "the progress bar has five steps")
        check(page.is_visible("#needPhone") and not page.is_visible("#needFull"), "the start step says what the PisoPhone-only mode needs")
        for _ in range(50):
            if "OK" in page.inner_text("#checks"):
                break
            time.sleep(0.1)
        check("OK" in page.inner_text("#checks") and "GitHub" in page.inner_text("#checks"), "the checks of this computer show")
        page.click("#btnNextStep")
        page.fill("#pm_WIFI_NAME", "Shop Wi-Fi")
        page.fill("#pm_WIFI_PASSWORD", "wifi-pass-123")
        time.sleep(1.5)   # (the page sends what was typed after a short pause)
        check(page.get_attribute("#pm_WIFI_PASSWORD", "type") == "password", "the Wi-Fi password is hidden")
        page.check("#pm_showPw")
        check(page.get_attribute("#pm_WIFI_PASSWORD", "type") == "text", "and can be shown")
        page.click("#btnNextStep")
        page.wait_for_selector("#pm_btnStart", state="visible")
        for _ in range(50):   # (the page learns of the board with its next poll)
            if "Found:" in page.inner_text("#pm_usbText") and "Shop Wi-Fi" in page.inner_text("#pm_review"):
                break
            time.sleep(0.1)
        check("Found: USB Serial Device on COM5" in page.inner_text("#pm_usbText") and "Shop Wi-Fi" in page.inner_text("#pm_review"), "the install step shows the board and the settings")
        page.click("#pm_btnStart")
        for _ in range(100):
            if "The coin box is online" in page.inner_text("#pm_instBanner"):
                break
            time.sleep(0.1)
        check("The coin box is online" in page.inner_text("#pm_instBanner") and core2.calls[-1][2:4] == ("Shop Wi-Fi", "wifi-pass-123"), "the installation runs from the page")
        page.click("#btnNextStep")
        page.wait_for_selector("#pm_boxUrl a", state="visible")
        for _ in range(50):
            if page.locator("#pm_boxQr svg").count() == 1 and "aa:bb:cc:00:11:22" in page.inner_text("#pm_pwList"):
                break
            time.sleep(0.1)
        check(page.inner_text("#pm_boxUrl") == "http://192.168.1.77/" and page.locator("#pm_boxQr svg").count() == 1 and "aa:bb:cc:00:11:22" in page.inner_text("#pm_pwList"), "the phones step shows the address, the QR code and the box code")
        check("P500" in page.inner_text("#sp3"), "and the slot license price")
        page.click("#btnNextStep")
        page.wait_for_selector("#pm_btnNext", state="visible")
        check("Saved on this computer" in page.inner_text("#sp4") and "pisophone-box-aabbcc001122.txt" in page.inner_text("#pm_savedList"), "the finish step lists the saved summary")
        page.click("#pm_btnToCards")
        page.wait_for_selector("#toolsMain", state="visible")
        check("Slot licenses" in page.inner_text("#toolsMain"), "the owner tools open (slot licenses first)")
        page.click("#btnTools")
        check(not page.evaluate("document.documentElement.scrollWidth > window.innerWidth"), "no sideways scrolling on a phone screen")
        check(not problems, f"no script error: {problems}")
        browser.close()
    server2.shutdown()

server.shutdown()
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
