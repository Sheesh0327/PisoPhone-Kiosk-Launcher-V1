#!/usr/bin/env python3
"""Feature 044: the console front page says whether the router and the coin box are up to date or waiting for an update. The update scripts
write their state into the setup conf; the overview carries it (no secret) and the page draws one quiet line under the money line."""
import glob, json, os, shutil, time, urllib.error, urllib.request
from harness import *

CP = 18610
env = Env(offset=130)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/leases", "w").write("")
open(f"{tmp}/route", "w").write("Iface\tDestination\tGateway\tFlags\neth9\t00000000\t0101A8C0\t0003\n")
open(f"{tmp}/dev", "w").write(" face |bytes\n  eth9: 1000 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0\n")
SETUP = f"{tmp}/setup.conf"
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", DHCP_LEASES=f"{tmp}/leases", ROUTE_FILE=f"{tmp}/route",
                 NETDEV_FILE=f"{tmp}/dev", PISO_SETUP_CONF=SETUP)
SECRETS = ("boxpw-secret", "abcdcdcd")
NOW = int(time.time())


def conf(router="", box="", checked=None):
    """The setup conf as the update scripts leave it, with secrets the page must never show."""
    open(SETUP, "w").write("GW_KEY='abcdcdcd" + "cd" * 28 + "'\nBOX_ADMIN_PASS='boxpw-secret'\n" + (f"UPDATE_STATE_ROUTER='{router}'\n" if router else "")
                           + (f"UPDATE_STATE_BOX='{box}'\n" if box else "") + (f"UPDATE_LAST_CHECK='{checked}'\n" if checked else ""))


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


def login():
    for _ in range(5):
        _c, _b, h = call("/api/login", {"password": "router-pass"})
        if h.get("Set-Cookie"):
            return h["Set-Cookie"].split(";")[0]
        time.sleep(1)
    return None


def versions(cookie):
    code, body, _h = call("/api/overview", cookie=cookie)
    return json.loads(body)["versions"], body.decode()


try:
    env.set_box(wifi_counter=True, lifetime_base=0)
    conf()
    check(env.start(), "the portal starts")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    cookie = login()
    check(cookie is not None, "the owner can sign in")

    v, raw = versions(cookie)
    check(v == {"router": None, "box": None, "checked": 0}, f"044: nothing recorded yet: {v}")
    conf("waiting|1.5.28|%d|1.5.27|customers are online, so it installs at the next quiet moment" % (NOW - 5000), "current|3.12.4|%d|3.12.4|" % (NOW - 5000), NOW - 600)
    v, raw = versions(cookie)
    check(v["router"]["state"] == "waiting" and v["router"]["latest"] == "1.5.28" and v["router"]["running"] == "1.5.27" and v["router"]["since"] == NOW - 5000
          and v["box"]["state"] == "current" and v["checked"] == NOW - 600, f"044: both records and the last check are in the overview: {v}")
    check(not any(s in raw for s in SECRETS), "044: no secret from the setup conf is in the overview")
    conf("bogus|1|2|3|4")
    v, raw = versions(cookie)
    check(v["router"] is None, "044: a record in an unknown state is not drawn")

    # ---- the page, in a real browser ------------------------------------------------------------------------------------------------
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
                ctx = br.new_context(viewport={"width": width, "height": 900}, color_scheme=scheme)
                ctx.add_cookies([{"name": "piso_console", "value": cookie.split("=", 1)[1], "url": f"http://127.0.0.1:{CP}"}])
                pg = ctx.new_page()
                errs = []
                pg.on("pageerror", lambda e: errs.append(str(e)))
                pg.goto(f"http://127.0.0.1:{CP}/")
                try:
                    pg.wait_for_function("(document.getElementById('upd')||{}).textContent", timeout=10000)
                except Exception:
                    pass
                info = pg.evaluate("""() => { const e = document.getElementById('upd'), m = document.getElementById('mny'), t = document.getElementById('tiles');
                  if (!e) return {text: '', cls: '', order: false, fits: false, shown: false};
                  const r = e.getBoundingClientRect(), g = getComputedStyle(e).borderLeftColor;
                  return {text: e.textContent, cls: e.className, gold: g, order: m.getBoundingClientRect().top <= r.top && r.bottom <= t.getBoundingClientRect().top + 1,
                          fits: r.left >= 0 && r.right <= innerWidth, shown: !!(e.offsetWidth && e.offsetHeight)}; }""")
                ctx.close()
                return info, errs

            conf("current|1.5.27|%d|1.5.27|" % (NOW - 5000), "current|3.12.4|%d|3.12.4|" % (NOW - 5000), NOW - 600)
            i, errs = see("light")
            check("Router 1.5.27 is up to date" in i["text"] and "Coin box 3.12.4 is up to date" in i["text"] and i["shown"] and i["order"] and "gold" not in i["cls"] and not errs,
                  f"044 page, up to date (light): {i} {errs}")
            conf("current|1.5.27|%d|1.5.27|" % (NOW - 5000), "waiting|3.12.5|%d|3.12.4|a phone has time running, so it installs at the next quiet moment" % (NOW - 3600), NOW - 600)
            i, errs = see("light")
            check("Coin box 3.12.5 is waiting for a quiet moment" in i["text"] and "gold" not in i["cls"] and not errs, f"044 page, waiting less than a day is not a warning: {i} {errs}")
            conf("current|1.5.27|%d|1.5.27|" % (NOW - 5000), "waiting|3.12.5|%d|3.12.4|a phone has time running, so it installs at the next quiet moment" % (NOW - 2 * 86400 - 100), NOW - 600)
            i, errs = see("dark")
            check("has waited 2 days" in i["text"] and "piso-setup box-update" in i["text"] and "gold" in i["cls"] and "bad" not in i["cls"] and i["order"] and not errs,
                  f"044 page, waited more than a day (dark): gold, with the command: {i} {errs}")
            conf("failed|1.5.28|%d|1.5.27|the update to 1.5.28 failed and was undone (the health check command failed)" % (NOW - 600), "current|3.12.4|%d|3.12.4|" % (NOW - 5000), NOW - 600)
            i, errs = see("light", width=360)
            check("could not be installed" in i["text"] and "health check" in i["text"] and "piso-setup self-update" in i["text"] and "gold" in i["cls"] and i["fits"] and not errs,
                  f"044 page, a failure (phone width): gold at once, the line fits the screen: {i} {errs}")
            conf("unknown||%d||the coin box did not answer" % (NOW - 600), "current|3.12.4|%d|3.12.4|" % (NOW - 5000), NOW - 5 * 3600)
            i, errs = see("dark", width=360)
            check("not checked" in i["text"] and "did not answer" in i["text"] and "checked 5 hours ago" in i["text"] and i["fits"] and not errs,
                  f"044 page, not checked, and the last check was hours ago (dark, phone width): {i} {errs}")
            conf()
            i, errs = see("light")
            check("not checked yet" in i["text"] and "bad" not in i["cls"] and not errs, f"044 page, nothing recorded yet: {i} {errs}")
            br.close()
finally:
    env.close()
finish()
