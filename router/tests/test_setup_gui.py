#!/usr/bin/env python3
"""Tests of the setup assistant's window (setup/pisophone_setup.py) without a router, a box or a phone: a fake "core" stands
in for the network, ssh and the browser, and the test clicks through every page the way a person does.
Needs Tk and a display (CI: xvfb-run). Without them it says so and succeeds (the checks need a window).
Run with:  xvfb-run -a python3 router/tests/test_setup_gui.py [--shots FOLDER]   (--shots saves a screenshot of every page)"""
import importlib.util, os, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
try:
    import tkinter
    root = tkinter.Tk()
    root.destroy()
except Exception as e:
    print(f"no window available here ({e}): the window tests are skipped")
    sys.exit(0)

spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)
ps._load_tk()
tk = ps.tk
asked = []   # the dialogs the window would show (a test has nobody to click them)
ps.messagebox.askyesno = lambda title, message, **kw: asked.append(message) or True
SHOTS = sys.argv[sys.argv.index("--shots") + 1] if "--shots" in sys.argv else None
checks = failures = 0


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


class FakeCore:
    """The network, ssh and the browser, as the window uses them."""

    def __init__(self, tmp, state="factory"):
        self.tmp, self.state, self.calls, self.fail_install = tmp, state, [], False
        self.boards, self.fail_flash = [("COM5", "USB-SERIAL CH340")], False
        self.preflight_result = [("ssh program", True, ""), ("internet and the pisophone.pages.dev website", True, ""),
                                 ("USB flashing tool (esptool)", True, ""), ("Chrome or Edge", True, "")]
        self.branch = "main"

    def ssh_missing(self): return False
    def preflight(self): return self.preflight_result
    def find_router(self): return "192.168.1.1" if self.state == "factory" else "10.0.0.1"
    def probe(self, host): return self.state

    def saved_login(self, host, out_dir): return None

    def login(self, host, password):
        self.calls.append(("login", password))
        return password == "Rpass2345678"

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
        open(sheet, "w").write("<html></html>")
        return {"ok": True, "host": "10.0.0.1", "state": "DONE all checks passed", "summary_text": SUMMARY, "rc": 0,
                "saved": [("Summary (every password)", summary), ("Printable setup sheet", sheet)]}

    def run(self, host, password, command, emit):
        self.calls.append(("run", command, password))
        emit("The coin slot is armed (the box should beep). Insert a coin now. Waiting up to 30 seconds...")
        emit("  coin detected: 1 peso(s) so far")
        emit("RESULT: the box counted 1 peso(s). The box and the portal work.")
        return 0

    def telegram(self, host, password, token, site, emit, confirm):
        self.calls.append(("telegram", token, site, confirm("Got a message from chat 4242 (Evan).")))
        return True

    def usb_boards(self): return list(self.boards)

    def flash_box(self, port, emit):
        self.calls.append(("flash", port))
        emit("== Looking at the board on " + port)
        emit("ESP32-C3 found. Software version 3.3.3 (1.36 MB).")
        if self.fail_flash:
            raise ps.SetupError("the flashing tool stopped: Failed to connect")
        emit("Writing at 0x00010000... (50 %)")
        return "ESP32-C3", "3.3.3"

    def open_provisioning(self, passwords, slot): self.calls.append(("provisioning", dict(passwords), slot)); return "setup", ""
    def box_online(self): return True
    def browse(self, url): self.calls.append(("browse", url))
    def open_path(self, path): self.calls.append(("open", path))


def pump(seconds=0.2):
    end = time.time() + seconds
    while time.time() < end:
        root.update()
        time.sleep(0.01)


def wait_for(cond, what, timeout=8.0):
    end = time.time() + timeout
    while time.time() < end:
        root.update()
        if cond():
            return True
        time.sleep(0.02)
    check(False, "timed out waiting for: " + what)
    return False


def shot(name):
    if not SHOTS:
        return
    os.makedirs(SHOTS, exist_ok=True)
    pump(0.4)
    subprocess.run(["import", "-window", "root", os.path.join(SHOTS, f"{name}.png")], check=False)


def page():
    return ps.STEPS[w.step][0]


def enabled(btn):
    return str(btn.cget("state")) != "disabled"


def texts(widget):
    """Every text shown in a widget tree."""
    out = []
    for child in widget.winfo_children():
        try:
            t = child.cget("text")
            if t:
                out.append(str(t))
        except tk.TclError:
            pass
        out += texts(child)
    return out


def new_wizard(state="factory", branch="main"):
    global root, w, core, tmp
    tmp = tempfile.mkdtemp()
    root = tk.Tk()
    root.geometry("1000x700+0+0")
    core = FakeCore(tmp, state)
    w = ps.Wizard(root, core, branch, os.path.join(tmp, "out"))
    root.update()
    return w


def stop():
    """Closes the window the way the end of a test should: no timer is left to fire into a window that is gone."""

    try:
        for after_id in root.tk.call("after", "info"):
            root.after_cancel(after_id)
        root.destroy()
    except (tk.TclError, TypeError):   # (a timer that is gone already)
        pass


def advance_to(key):
    """Clicks Next until the page is `key` (the box ticked as flashed, the router waited for)."""
    for _ in range(10):
        if page() == key:
            return
        if page() == "settings" and not w.vars["GUEST_SSID"].get():
            w.vars["GUEST_SSID"].set("Tindahan")
            w.vars["SITE_NAME"].set("Aling Nena")
            root.update()
        if page() == "router":
            wait_for(lambda: w.router["host"] is not None and enabled(w.next_btn), "the router is found")
        click_next()


def click_next():
    check(enabled(w.next_btn), f"Next is available on the {page()} page")
    w.next_btn.invoke()
    root.update()


# ---- the whole way, on a factory router ---------------------------------------------------------------------------------
new_wizard()
check(root.title() == "PisoPhone Setup" and page() == "welcome" and w.title.cget("text") == "Set up your PisoPhone system", "the first page")
check(not enabled(w.back_btn) and enabled(w.next_btn), "no Back on the first page, Next is there")
wait_for(lambda: w.pre is not None, "the checks of this computer")
pump(0.2)
check(any("Chrome or Edge" in t for t in texts(w.checks_box)) and any("esptool" in t for t in texts(w.checks_box)),
      "the first page shows what it checked, the USB flashing tool too")
check([n for _k, n in ps.STEPS] == ["Start", "Router", "Settings", "Install", "Phones", "Finish"], "no coin box page: the flashing is part of the install")
shot("1-start")
click_next()

check(page() == "router", "the router page")
wait_for(lambda: w.router["host"] == "192.168.1.1" and w.router["state"] == "factory", "the router is found by itself")
pump(0.3)
check(any("Router found at 192.168.1.1" in t for t in texts(w.router_status)), "it says the router was found")
shot("3-router")
click_next()

check(page() == "settings", "the settings page")
check(w.title.cget("text") == "Your network and passwords", "factory router: all the settings")
for env, _label, lo, hi, _n in ps.PASSWORDS:
    check(ps.password_problem(w.vars[env].get(), lo, hi) is None, f"{env}: a valid password is already there")
check(not enabled(w.next_btn), "the names are asked every time: Next waits for them")
check(w.vars["GUEST_SSID"].get() == "" and w.vars["SITE_NAME"].get() == "", "no name is filled in beforehand")
w.vars["GUEST_SSID"].set("Tindahan WiFi")
root.update()
check(not enabled(w.next_btn), "the shop name is needed too")
shot("4-settings")
w.vars["GUEST_SSID"].set("Bad'Name")
root.update()
check(not enabled(w.next_btn) and any("quotes" in t for t in texts(w.body)), "a bad Wi-Fi name: Next is off and the reason is shown")
w.vars["GUEST_SSID"].set("Tindahan WiFi")
w.vars["SITE_NAME"].set("Aling Nena")
w.vars["ROOT_PASSWORD"].set("short")
root.update()
check(not enabled(w.next_btn), "a short password: Next is off")
w.vars["ROOT_PASSWORD"].set("Rpass2345678")
w.vars["KIOSK_PASSWORD"].set("Kpass234567")
w.vars["BOX_NEW_ADMIN_PASSWORD"].set("Bpass23456789")
w.vars["COUNTRY"].set("Singapore (SG)")
root.update()
click_next()

check(page() == "install" and not enabled(w.next_btn), "the install page: Next waits for the installation")
wait_for(lambda: w.usb is not None, "the USB boards are looked for")
pump(0.2)
check(any("Found: USB-SERIAL CH340 on COM5" in t for t in texts(w.body)), "the board on USB is shown")
shot("5-install-ready")
w.start_btn.invoke()
check(w.installing, "the installation runs")
wait_for(lambda: w.result is not None or w.install_error, "the installation finishes")
order = [c[0] for c in core.calls if c[0] in ("flash", "install")]
check(order == ["flash", "install"] and ("flash", "COM5") in core.calls, "the coin box is written first, over USB, then the router: " + str(order))
check("ESP32-C3 found" in w.log.get("1.0", "end") and w.flashed, "the log shows the flashing and it is remembered")
check(core.calls[-1][0] == "install" or any(c[0] == "install" for c in core.calls), "the core installed")
sent = [c for c in core.calls if c[0] == "install"][0][1]
check(sent["GUEST_SSID"] == "Tindahan WiFi" and sent["ROOT_PASSWORD"] == "Rpass2345678" and sent["COUNTRY"] == "SG" and sent["SITE_NAME"] == "Aling Nena",
      "the answers were sent as typed: " + str(sent))
pump(0.3)
check(any("Installed and checked" in t or "Installation complete" in t for t in texts(w.banner_box)), "the done banner is shown")
check("== Pairing the coin box" in w.log.get("1.0", "end"), "the log shows the progress")
shot("5-install-done")

# on its own, on to the phones, which opens the phone setup page
wait_for(lambda: page() == "phones", "it goes on to the phones by itself", timeout=6)
wait_for(lambda: any(c[0] == "provisioning" for c in core.calls), "the phone setup page opens by itself")
prov = [c for c in core.calls if c[0] == "provisioning"][0]
check(prov[1]["BOX_NEW_ADMIN_PASSWORD"] == "Bpass23456789" and prov[1]["KIOSK_PASSWORD"] == "Kpass234567" and prov[2] == 1,
      "it opened slot 1 with the box admin and kiosk Wi-Fi passwords from the summary: " + str(prov))
wait_for(lambda: w.prov["state"] == "setup", "the page is reported as open")
pump(0.3)
check(any("slot 1 is open" in t for t in texts(w.prov_box)), "the page says the setup page is open")
check(any("Bpass23456789" in t for t in texts(w.body)), "the passwords are shown for copying")
shot("6-phones")
w.slot.set(2)
w.open_btn.invoke()
wait_for(lambda: len([c for c in core.calls if c[0] == "provisioning"]) == 2, "the next slot's page opens")
check([c for c in core.calls if c[0] == "provisioning"][1][2] == 2, "slot 2")
click_next()

check(page() == "finish", "the last page")
check(w.next_btn.cget("text") == "Finish", "the last button says Finish")
w.coin_btn.invoke()
wait_for(lambda: w.coin["ok"] is not None and not w.coin["running"], "the coin test")
run = [c for c in core.calls if c[0] == "run"][0]
check(run[1] == "piso-setup test-coin" and run[2] == "Rpass2345678", "the coin test runs on the router with the router password")
pump(0.2)
check(w.coin["ok"] is True and any("counted 1 peso" in t for t in texts(w.coin_box)), "the coin test passed and says so")
shot("7-finish")
w.open_telegram_dialog()
root.update()
shot("7-telegram")
w.tg_token.set("123456789:" + "A" * 35)
w.start_telegram()
wait_for(lambda: w.telegram["ok"], "Telegram connects")
tg = [c for c in core.calls if c[0] == "telegram"][0]
check(tg[1].startswith("123456789:") and tg[2] == "Aling Nena", "the token and the site name were used: " + str(tg))
check(tg[3] is True and any("Evan" in m and "Is that you" in m for m in asked), "the chat was shown and confirmed")
w.tg_win.destroy()
pump(0.1)
check(any("Connected" in t for t in texts(w.body)), "the last page says Telegram is connected")
w.next_btn.invoke()
try:
    root.update()
    still_open = bool(root.winfo_exists())
except tk.TclError:
    still_open = False
check(not still_open, "Finish closes the window")

# ---- a router that was set up before: it keeps its settings, only the country is asked ---------------------------------------
new_wizard(state="configured")
advance_to("settings")
check(page() == "settings" and w.title.cget("text") == "This router keeps its settings", "a set-up router: no questions about names and passwords")
shot("4b-kept")
check(enabled(w.next_btn), "only the country is asked, and it is filled in")
click_next()
w.start_btn.invoke()
wait_for(lambda: w.result is not None, "the installation finishes")
sent = [c for c in core.calls if c[0] == "install"][0][1]
check(sent["ROOT_PASSWORD"] == "" and sent["GUEST_SSID"] == "" and sent["COUNTRY"] == "PH", "nothing but the country is sent: " + str(sent))
wait_for(lambda: page() == "phones", "on to the phones")
wait_for(lambda: any(c[0] == "provisioning" for c in core.calls), "the phone setup page opens")
check(any(c[0] == "provisioning" and c[1]["BOX_NEW_ADMIN_PASSWORD"] == "Bpass23456789" for c in core.calls),
      "its passwords come from the summary the router sent back")

# the next unit: names asked again, new passwords, the flashing again
first_pw = {k: v.get() for k, v in w.vars.items() if k.endswith("PASSWORD")}
click_next()
check(page() == "finish", "the last page")
w.next_unit()
root.update()
check(page() == "router" and w.unit == 2, "the next unit starts at the router page")
check(any("Unit 2" in t for t in texts(w.body)), "it says to connect the next router and box")
check(w.vars["GUEST_SSID"].get() == "" and w.vars["SITE_NAME"].get() == "" and w.result is None and not w.flashed,
      "the names are empty again and nothing of the last unit is left")
check(all(w.vars[k].get() != first_pw[k] for k in first_pw), "the passwords are new")
check(w.vars["COUNTRY"].get() == "Philippines (PH)", "the country is kept")
stop()

# ---- no board on USB: the install says so and changes nothing ----------------------------------------------------------------
new_wizard()
core.boards = []
advance_to("install")
wait_for(lambda: w.usb == [], "no board is found")
pump(0.2)
check(any("No board found" in t for t in texts(w.body)), "the page says no board was found")
w.start_btn.invoke()
wait_for(lambda: w.install_error is not None, "the missing board is reported")
check("no coin box board found on USB" in w.install_error and not any(c[0] == "install" for c in core.calls),
      "nothing was installed on the router: " + str(w.install_error))
w.skip_flash.set(True)
w.start_btn.invoke()
wait_for(lambda: w.result is not None and w.result["ok"], "with the box marked as ready, the router installs")
check(not any(c[0] == "flash" for c in core.calls), "and nothing was written to a box")
stop()

# ---- the flashing fails: the reason is shown, the router is not touched, and a second try writes again -----------------------
new_wizard()
core.fail_flash = True
advance_to("install")
w.start_btn.invoke()
wait_for(lambda: w.install_error is not None, "the flash failure is reported")
check("Failed to connect" in w.install_error and not any(c[0] == "install" for c in core.calls) and not w.flashed,
      "the router is not touched when the box could not be written: " + str(w.install_error))
core.fail_flash = False
w.start_btn.invoke()
wait_for(lambda: w.result is not None and w.result["ok"], "the second try writes the box and installs")
core.calls.clear()
w.install_error = None
w.start_btn.invoke()
wait_for(lambda: not w.installing, "a repeat after a success")
check(not any(c[0] == "flash" for c in core.calls), "a box that was written is not written again on a repeat")
stop()

# ---- a router with a password: it must be typed, a wrong one is refused ----------------------------------------------------
new_wizard(state="password")
click_next()
wait_for(lambda: w.router["state"] == "password", "the router is found")
pump(0.2)
check(not enabled(w.next_btn), "a router with a password: Next waits for it")
w.router_password.set("wrong")
w.update_nav()
w.next_btn.invoke()
wait_for(lambda: w.router.get("login_failed"), "a wrong password is refused")
check(page() == "router" and any("did not work" in t for t in texts(w.router_status)), "it says so and stays")
w.router_password.set("Rpass2345678")
w.next_btn.invoke()
wait_for(lambda: page() == "settings", "the right one goes on")
stop()

# ---- a failing installation: the reason is shown and it can be tried again ----------------------------------------------------
new_wizard()
core.fail_install = True
advance_to("install")
w.start_btn.invoke()
wait_for(lambda: w.install_error is not None, "the failure is reported")
pump(0.2)
check(page() == "install" and not enabled(w.next_btn) and any("could not be prepared" in t for t in texts(w.banner_box)),
      "a failure: stays on the page, shows why")
check(w.start_btn.cget("text") == "Try again" and enabled(w.start_btn), "and offers Try again")
shot("5b-install-failed")
core.fail_install = False
w.start_btn.invoke()
wait_for(lambda: w.result is not None and w.result["ok"], "the second try works")
stop()

# ---- the first page says what is missing ----------------------------------------------------------------------------------
new_wizard()
core.preflight_result = [("ssh program", False, "Windows: add OpenSSH Client."), ("internet and the pisophone.pages.dev website", True, ""),
                         ("Chrome or Edge", False, "The phone setup page needs Chrome or Microsoft Edge.")]
w.pre = None
w.pre_running = False
w.show(0)
wait_for(lambda: w.pre is not None, "the checks")
pump(0.2)
shown = " ".join(texts(w.checks_box))
check("OpenSSH Client" in shown and "Microsoft Edge" in shown, "what is missing is shown with what to do")
shot("1b-start-missing")
stop()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
