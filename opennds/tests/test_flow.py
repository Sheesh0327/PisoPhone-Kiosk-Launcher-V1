"""End-to-end test of the OpenNDS coin-slot integration without a router or an ESP32.

Real pieces under test: theme_coinslot.sh (run the way libopennds.sh runs it, with the libopennds helpers
stubbed) and coinslot-listener.sh (handler + worker). Stand-ins: fakebox.py (the ESP32 gateway API, with the
same HMAC/nonce rules) and fake_socat.py (socat). Run with:  python3 opennds/tests/test_flow.py"""
import hashlib, json, os, re, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
THEME = os.path.join(ROOT, "theme_coinslot.sh")
LISTENER = os.path.join(ROOT, "coinslot-listener.sh")
KEY = "test-gateway-key-123456"
BOX_PORT, LISTEN_PORT = 18090, 18099
tmp = tempfile.mkdtemp()
CTL, LOG, STATE = f"{tmp}/ctl.json", f"{tmp}/box.log", f"{tmp}/state"
open(LOG, "w").close()
failures = checks = 0


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def set_box(**kw):
    with open(CTL, "w") as f:
        json.dump(kw, f)


conf = f"{tmp}/coinslot.conf"
open(conf, "w").write(f"GW_BOX=127.0.0.1:{BOX_PORT}\nGW_KEY={KEY}\nWIFI_MINUTES_PER_COIN=10\nCOIN_WINDOW_SECONDS=7\nSTATE_DIR={STATE}\n")
env = dict(os.environ, FAKEBOX_CTL=CTL, FAKEBOX_LOG=LOG, FAKEBOX_KEY=KEY, COINSLOT_CONF=conf)
set_box(busy=False, coins_at=[1, 2])
procs = [
    subprocess.Popen([sys.executable, f"{HERE}/fakebox.py", str(BOX_PORT)], env=env),
    subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(LISTEN_PORT), LISTENER], env=env),
]
time.sleep(1.0)


def page(hid, coinact="", landing="", port=LISTEN_PORT, auth_ok="1"):
    e = dict(env, HID=hid, COINACT=coinact, LANDING=landing, PORT=str(port), AUTH_OK=auth_ok)
    return subprocess.run(["bash", f"{HERE}/theme_harness.sh", THEME], env=e, capture_output=True, text=True, timeout=60).stdout


def sid_of(hid):
    return hashlib.sha256(hid.encode()).hexdigest()[:32]


def box_actions():
    return open(LOG).read().split("\n")


try:
    # 1. Welcome page shows the rate and offers to start.
    p = page("hidA")
    check("1 coin = 10 minutes" in p and 'value="Insert coin"' in p, "welcome page shows rate and button")
    check("refresh" not in p, "welcome page does not auto-refresh")

    # 2. Start: coin slot armed, waiting page refreshes itself and shows the live count.
    p = page("hidA", "start")
    check("Insert coin(s) now" in p, "start leads to the waiting page")
    check('http-equiv="refresh" content="2; url=/opennds_preauth/?fas=ABC%2B%2F%3D&coinact=wait"' in p, "wait page refresh link is URL-safe")
    check(any(l.startswith("arm ") and sid_of("hidA") in l for l in box_actions()), "box was armed for the hashed session id")
    time.sleep(2.8)  # two coins arrive (at 1 s and 2 s)
    p = page("hidA", "wait")
    check("2 coin(s) = 20 minutes" in p, "wait page shows running total")
    check('value="Connect now"' in p, "connect button appears once coins arrived")

    # 3. Finish: counted, result page, no access granted yet.
    p = page("hidA", "finish")
    for _ in range(10):
        if "Thank you!" in p:
            break
        time.sleep(1)
        p = page("hidA", "finish")
    check("Thank you!" in p and "2 coin(s) = 20 minutes of Wi-Fi" in p, "result page after finish")
    check("AUTHCALL" not in p, "no access before Connect")

    # 4. A different customer cannot claim these coins.
    p = page("hidB", "", "yes")
    check("AUTHCALL" not in p and "We could not start your session" in p, "other client cannot use someone else's coins")

    # 5. Connect (landing): session length comes from the listener, not the browser.
    p = page("hidA", "connect", "yes")
    check("AUTHCALL sessiontimeout=20 quotas=20 0 0 0 0" in p, "auth_log called with 20 minutes: " + p[-300:])
    check("You are connected for 20 minutes" in p, "success page")
    check(any(l.startswith("ack ") and sid_of("hidA") in l for l in box_actions()), "coins acknowledged on the box after access was granted")

    # 6. Replay: connecting again grants nothing.
    p = page("hidA", "connect", "yes")
    check("AUTHCALL" not in p, "second Connect does not grant more time")

    # 7. Failed authentication keeps the coins claimable.
    set_box(busy=False, coins_at=[1])
    page("hidC", "start"); time.sleep(2.2)
    for _ in range(10):
        p = page("hidC", "finish")
        if "Thank you!" in p:
            break
        time.sleep(1)
    p = page("hidC", "connect", "yes", auth_ok="0")
    check("We could not start your session" in p, "auth failure shown")
    p = page("hidC", "connect", "yes", auth_ok="1")
    check("AUTHCALL sessiontimeout=10" in p, "coins still claimable after an auth failure")

    # 8. Zero coins: nothing granted.
    set_box(busy=False, coins_at=[])
    page("hidD", "start"); time.sleep(1.5)
    for _ in range(10):
        p = page("hidD", "finish")
        if "No coins were detected" in p:
            break
        time.sleep(1)
    check("No coins were detected" in p, "no coins, no access")

    # 9. Busy slot: friendly page that retries.
    set_box(busy=True, coins_at=[])
    p = page("hidE", "start")
    for _ in range(5):
        if "coin slot is busy" in p:
            break
        time.sleep(0.5)
        p = page("hidE", "start")
    check("The coin slot is busy" in p and 'content="5; url=' in p and "coinact=start" in p, "busy page retries start")

    # 10. Listener down: portal degrades gracefully.
    p = page("hidF", port=9)
    check("not available right now" in p, "listener down shows unavailable page")

    # 11. Listener input hardening.
    import urllib.request, urllib.error
    def get(path):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{LISTEN_PORT}{path}", timeout=10) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()
    check(get("/status?sid=abc;rm")[0] == 400, "bad sid rejected")
    check(get("/status?sid=" + "g" * 32)[0] == 400, "non-hex sid rejected")
    check(get("/nothing?sid=" + "a" * 32)[0] == 404, "unknown path 404")
finally:
    for pr in procs:
        pr.kill()
    import glob, signal
    for pidfile in glob.glob(f"{STATE}/*/pid"):  # stop any worker still running
        try:
            os.kill(int(open(pidfile).read()), signal.SIGTERM)
        except (OSError, ValueError):
            pass

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
