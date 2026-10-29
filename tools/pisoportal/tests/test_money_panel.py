#!/usr/bin/env python3
"""Feature 036: the console front page says whether the coin box and the sales record agree. Each state of the panel against the fake coin
box; the panel never writes the saved base (reconcile.state) and asks the box at most once a minute."""
import glob, hashlib, json, os, shutil, time, urllib.error, urllib.request
from harness import *

CP = 18600
env = Env(offset=120)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/leases", "w").write("")
open(f"{tmp}/route", "w").write("Iface\tDestination\tGateway\tFlags\neth9\t00000000\t0101A8C0\t0003\n")
open(f"{tmp}/dev", "w").write(" face |bytes\n  eth9: 1000 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0\n")
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", DHCP_LEASES=f"{tmp}/leases", ROUTE_FILE=f"{tmp}/route",
                 NETDEV_FILE=f"{tmp}/dev", PISO_SETUP_CONF=f"{tmp}/setup.conf")
STATE = f"{env.data}/reconcile.state"
LEDGER = f"{env.data}/revenue.csv"


def call(path, body=None, cookie=None):
    req = urllib.request.Request(f"http://127.0.0.1:{CP}{path}", data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    if body is not None:
        req.add_header("X-Piso", "1")
        req.add_header("Content-Type", "application/json")
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


def panel():
    """Start the portal fresh (the panel keeps its answer for a minute, a new process starts empty), read the money line, stop it."""
    env.stop()
    check(env.start(), "the portal starts")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    cookie = None
    for _ in range(5):
        _c, _b, h = call("/api/login", {"password": "router-pass"})
        if h.get("Set-Cookie"):
            cookie = h["Set-Cookie"].split(";")[0]
            break
        time.sleep(1)
    check(cookie is not None, "the owner can sign in")
    code, body, _h = call("/api/overview", cookie=cookie)
    check(code == 200, "the overview is served")
    return json.loads(body).get("money"), cookie


def ledger(*pesos):
    """A real sales record: every row carries the hash that chains it to the row before (rows without a hash are not counted)."""
    now, prev, out = int(time.time()), "0", ""
    for p in pesos:
        row = f"{now},hyper,{p},30,coin"
        prev = hashlib.sha256(f"{prev}|{row}".encode()).hexdigest()[:12]
        out += f"{row},{prev}\n"
    open(LEDGER, "w").write(out)


def status_calls():
    return sum(1 for l in env.boxlines() if l.startswith("status "))


try:
    # row 8: nothing sold, nothing counted
    env.set_box(wifi_counter=True, lifetime_base=0)
    m, cookie = panel()
    check(m is not None and m["state"] == "no_sales" and m["ledger"] == 0 and m["box"] == 0, f"036 row 8: no sales yet: {m}")

    # row 1: they agree
    ledger(1000, 247)
    env.set_box(wifi_counter=True, lifetime_base=1247)
    m, cookie = panel()
    check(m and m["state"] == "agrees" and (m["ledger"], m["box"], m["diff"]) == (1247, 1247, 0) and m["restarted"] is False, f"036 row 1: money agrees: {m}")

    # row 2: current firmware, the box counted more Wi-Fi coins than were recorded: not the rental phones
    env.set_box(wifi_counter=True, lifetime_base=1310, phone_coins=63)
    m, cookie = panel()
    check(m and m["state"] == "box_ahead" and (m["ledger"], m["box"], m["diff"]) == (1247, 1310, 63), f"036 row 2: the box is ahead and says so: {m}")

    # row 3: older firmware counts every coin, so the rental phones explain the gap
    env.set_box(lifetime_base=1310)
    m, cookie = panel()
    check(m and m["state"] == "box_ahead_phones" and m["diff"] == 63, f"036 row 3: older box, the gap is the rental phones: {m}")

    # row 4: the one real mismatch (the hourly check has run once, so a base is saved; a first look with no base never cries wolf)
    ledger(1300)
    open(STATE, "w").write("0 0 1247\n")
    env.set_box(wifi_counter=True, lifetime_base=1247)
    m, cookie = panel()
    os.remove(STATE)
    check(m and m["state"] == "mismatch" and (m["ledger"], m["box"], m["diff"]) == (1300, 1247, 53), f"036 row 4: the record holds more than the box counted: {m}")

    # row 5: the box's count restarted (revenue collected); the saved base is the hourly check's to write, never the panel's
    ledger(650)
    open(STATE, "w").write("0 0 900\n")
    env.set_box(wifi_counter=True, lifetime_base=3)
    m, cookie = panel()
    check(m and m["state"] == "agrees" and m["restarted"] is True and (m["ledger"], m["box"]) == (0, 0), f"036 row 5: a restart is a note, not a mismatch: {m}")
    check(open(STATE).read() == "0 0 900\n", "036: the panel left reconcile.state exactly as it was")
    os.remove(STATE)

    # row 6: the box does not answer; the record stays in view
    ledger(1000, 247)
    env.set_box(wifi_counter=True, lifetime_base=1247, status_fail=True)
    m, cookie = panel()
    check(m and m["state"] == "box_silent" and m["ledger"] == 1247 and m["box"] is None, f"036 row 6: the box is silent: {m}")

    # row 7: a changed sales record is named before any comparison
    now = int(time.time())
    open(LEDGER, "w").write(f"{now},hyper,5,30,coin,0123456789ab\n")
    env.set_box(wifi_counter=True, lifetime_base=5)
    m, cookie = panel()
    check(m and m["state"] == "bad_ledger" and m["bad_line"] == 1, f"036 row 7: the changed record is named: {m}")

    # criterion 4: fifty polls, one question to the box; the saved base untouched; a console that is open all day costs the box little
    ledger(1000, 247)
    env.set_box(wifi_counter=True, lifetime_base=1247)
    calls0 = status_calls()
    m, cookie = panel()
    before = status_calls()
    check(before - calls0 == 1, f"036: the first look asks the box once ({before - calls0} status calls)")
    for _ in range(50):
        call("/api/overview", cookie=cookie)
    check(status_calls() == before, f"036: fifty more polls inside the minute ask the box nothing more ({status_calls() - before} extra calls)")
    check(not os.path.exists(STATE), "036: fifty polls never created reconcile.state")
    rc, out = env.cli("reconcile")
    check(rc == 0 and out.startswith("RECONCILE OK") and "ledger=1247" in out, f"036: pisoportal reconcile still says the same thing: {rc} {out}")

    # ---- the page itself, in a real browser: the line sits under the earnings header, above the tiles, with both numbers ------------------
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sync_playwright = None
        print("skipped: playwright is not installed (browser checks)")
    if sync_playwright:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome")) if c), None)
        with sync_playwright() as pw:
            br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])

            def see(scheme="light", width=1100):
                m, cookie = panel()
                ctx = br.new_context(viewport={"width": width, "height": 900}, color_scheme=scheme)
                ctx.add_cookies([{"name": "piso_console", "value": cookie.split("=", 1)[1], "url": f"http://127.0.0.1:{CP}"}])
                pg = ctx.new_page()
                errs = []
                pg.on("pageerror", lambda e: errs.append(str(e)))
                pg.goto(f"http://127.0.0.1:{CP}/")
                try:
                    pg.wait_for_function("(document.getElementById('mny')||{}).textContent", timeout=10000)
                except Exception:
                    pass
                info = pg.evaluate("""() => { const e = document.getElementById('mny'), t = document.getElementById('tiles');
                  if (!e) return {text: '', cls: '', above: false, fits: false};
                  const r = e.getBoundingClientRect();
                  return {text: e.textContent, cls: e.className, above: r.bottom <= t.getBoundingClientRect().top + 1,
                          fits: r.left >= 0 && r.right <= innerWidth, wide: r.width >= innerWidth * 0.8 - 40, shown: !!(e.offsetWidth && e.offsetHeight)}; }""")
                ctx.close()
                return info, errs

            ledger(1000, 247)
            env.set_box(wifi_counter=True, lifetime_base=1247)
            i, errs = see("light")
            check("Money agrees" in i["text"] and i["text"].count("1,247") == 2 and "ok" in i["cls"] and i["above"] and i["shown"] and not errs, f"036 page, row 1 (light): {i} {errs}")
            env.set_box(wifi_counter=True, lifetime_base=1310, phone_coins=63)
            i, errs = see("dark")
            check("1,310" in i["text"] and "1,247" in i["text"] and "63" in i["text"] and "if it stays" in i["text"] and "bad" not in i["cls"] and i["above"] and not errs,
                  f"036 page, row 2 (dark): the box ahead is gold and never red: {i} {errs}")
            ledger(1300)
            open(STATE, "w").write("0 0 1247\n")
            env.set_box(wifi_counter=True, lifetime_base=1247)
            i, errs = see("light", width=360)
            os.remove(STATE)
            check("MISMATCH" in i["text"] and "1,300" in i["text"] and "1,247" in i["text"] and "53" in i["text"] and "bad" in i["cls"] and i["fits"] and not errs,
                  f"036 page, row 4 (phone width): the mismatch is red, both numbers, the line fits the screen: {i} {errs}")
            ledger(1000, 247)
            env.set_box(wifi_counter=True, lifetime_base=1247, status_fail=True)
            i, errs = see("dark", width=360)
            check("not answering" in i["text"] and "1,247" in i["text"] and i["fits"] and not errs, f"036 page, row 6 (dark, phone width): {i} {errs}")
            br.close()
finally:
    env.close()
finish()
