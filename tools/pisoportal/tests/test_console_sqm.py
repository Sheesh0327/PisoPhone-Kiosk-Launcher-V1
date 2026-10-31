#!/usr/bin/env python3
"""The console's smooth-line (SQM) settings (feature 058, group 4): what GET /api/settings reports about SQM, which values POST /api/settings accepts, that a bad
value saves nothing and starts nothing, and that a good one is saved first and then handed to `piso-setup sqm apply` outside the request."""
import json
import os
import time
import urllib.error
import urllib.request

from harness import *

CP = 18800
env = Env(offset=230)
tmp = env.tmp
OLD_SETTINGS = "GAME_MINUTES=10\nVOUCHER_CHOICE=on\n"
open(f"{env.data}/settings.txt", "w").write(OLD_SETTINGS)
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
# a fake piso-setup: it writes down its arguments and what settings.txt said at that moment, then takes its time (the request must not wait for it)
open(f"{tmp}/piso-setup", "w").write(f'#!/bin/sh\necho "$*" >> "{tmp}/setup-calls"\ncp "{env.data}/settings.txt" "{tmp}/settings-at-call" 2>/dev/null\nsleep "${{FAKE_SETUP_SLEEP:-2}}"\nexit 0\n')
os.chmod(f"{tmp}/piso-setup", 0o755)
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf", PISO_SETUP_BIN=f"{tmp}/piso-setup")


def call(path, body=None, cookie=None, headers=None):
    req = urllib.request.Request(f"http://127.0.0.1:{CP}{path}", data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    if body is not None:
        req.add_header("X-Piso", "1")
        req.add_header("Content-Type", "application/json")
    if cookie:
        req.add_header("Cookie", cookie)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


def settings():
    return open(f"{env.data}/settings.txt").read()


def calls():
    return open(f"{tmp}/setup-calls").read() if os.path.exists(f"{tmp}/setup-calls") else ""


def sqm(cookie):
    return json.loads(call("/api/settings", cookie=cookie)[1])["sqm"]


def idle(cookie):
    return wait_until(lambda: sqm(cookie)["applying"] is False, 10)


try:
    check(env.start(), "the portal starts")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    code, _b, h = call("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]
    check(code == 200 and cookie.startswith("piso_console="), "signed in")

    # ---- what the console is told ------------------------------------------------------------------------------------------------------------
    s = sqm(cookie)
    check(s["available"] is False and s["on"] is False and s["down"] == "" and s["up"] == "" and s["percent"] == 95 and s["active"] is False and s["applying"] is False,
          f"a router that never installed SQM: not available, off, no speeds, a 95% margin: {s}")
    check(s["reason"] != "", f"and it says why: {s}")
    open(f"{env.data}/sqm.txt", "w").write("installed|1\nqdisc|cake\nactive|1\nwan|eth1\nlimit_down_kbps|47500\nlimit_up_kbps|19000\nreason|\n")
    s = sqm(cookie)
    check(s["available"] is True and s["qdisc"] == "cake" and s["active"] is True and s["limit_down"] == 47500 and s["limit_up"] == 19000 and s["reason"] == "", f"SQM installed and limiting: {s}")
    open(f"{env.data}/sqm.txt", "w").write("installed|1\nqdisc|fq_codel\nactive|0\nwan|\nlimit_down_kbps|\nlimit_up_kbps|\nreason|switched off\n")
    s = sqm(cookie)
    check(s["available"] is True and s["active"] is False and s["limit_down"] == 0 and s["reason"] == "switched off", f"installed but off: {s}")

    # ---- bad values: refused, nothing saved, nothing started ------------------------------------------------------------------------------------
    for k, bad in (("sqm_down", "0"), ("sqm_down", "abc"), ("sqm_down", "10001"), ("sqm_down", "5.55"), ("sqm_down", "-1"), ("sqm_down", ".5"), ("sqm_down", "50."), ("sqm_up", "0.05"),
                   ("sqm_up", "1e3"), ("sqm_percent", "49"), ("sqm_percent", "101"), ("sqm_percent", "95.5"), ("sqm_percent", "abc"), ("sqm_on", "maybe")):
        code, body, _h = call("/api/settings", {k: bad}, cookie=cookie)
        check(code == 400 and b"BAD_SQM" in body, f"refused: {k} {bad!r}: {code} {body}")
    check(settings() == OLD_SETTINGS and calls() == "", f"and none of it was saved or applied: {settings()!r} {calls()!r}")
    code, body, _h = call("/api/settings", {"sqm_down": "50", "sqm_up": "bad"}, cookie=cookie)
    check(code == 400 and settings() == OLD_SETTINGS and calls() == "", "one bad value among good ones saves none of them")
    code, body, _h = call("/api/settings", {"sqm_on": "on"}, cookie=cookie)
    check(code == 400 and b"SQM_NEEDS_SPEEDS" in body and settings() == OLD_SETTINGS and calls() == "", f"switching on without both speeds is refused: {code} {body}")
    code, body, _h = call("/api/settings", {"sqm_on": "on", "sqm_down": "50"}, cookie=cookie)
    check(code == 400 and b"SQM_NEEDS_SPEEDS" in body and calls() == "", "and with only one speed")

    # ---- a good change: saved first, then handed over outside the request ---------------------------------------------------------------------------
    t0 = time.time()
    code, body, _h = call("/api/settings", {"sqm_down": "50", "sqm_up": "20", "sqm_on": "on"}, cookie=cookie)
    took = time.time() - t0
    j = json.loads(body)
    check(code == 200 and j["ok"] is True and j["sqm"]["on"] is True and j["sqm"]["down"] == "50" and j["sqm"]["up"] == "20" and j["sqm"]["percent"] == 95, f"saved: {body}")
    check(took < 1.5, f"the answer does not wait for piso-setup ({took:.1f} s)")
    check(j["sqm"]["applying"] is True, "and says it is applying")
    st = settings()
    check("SQM_DOWN_MBPS=50\n" in st and "SQM_UP_MBPS=20\n" in st and "SQM_ON=1\n" in st and "GAME_MINUTES=10\n" in st and "VOUCHER_CHOICE=on\n" in st, f"kept in the data folder next to the other lines: {st!r}")
    check(wait_until(lambda: calls() != "", 5) and calls() == "sqm apply\n", f"piso-setup was called with fixed arguments only: {calls()!r}")
    wait_until(lambda: os.path.exists(f"{tmp}/settings-at-call"), 3)   # (the fake writes its call down first, then copies the settings)
    at = open(f"{tmp}/settings-at-call").read() if os.path.exists(f"{tmp}/settings-at-call") else ""
    check("SQM_ON=1\n" in at and "SQM_DOWN_MBPS=50\n" in at, "and it found the new speeds already saved")
    check(idle(cookie), "the apply ends and the console is told")

    code, body, _h = call("/api/settings", {"sqm_down": "87.3", "sqm_percent": "90"}, cookie=cookie)
    st = settings()
    check(code == 200 and "SQM_DOWN_MBPS=87.3\n" in st and "SQM_PERCENT=90\n" in st and "SQM_UP_MBPS=20\n" in st and "SQM_ON=1\n" in st, f"a decimal speed and a margin: {st!r}")
    check(idle(cookie) and calls() == "sqm apply\nsqm apply\n", f"a changed speed applies again: {calls()!r}")
    code, body, _h = call("/api/settings", {"sqm_down": "050.0"}, cookie=cookie)
    check(code == 200 and "SQM_DOWN_MBPS=50\n" in settings(), f"a number is kept in its plain form: {settings()!r}")
    check(idle(cookie), "idle again")

    code, body, _h = call("/api/settings", {"sqm_on": "off"}, cookie=cookie)
    st = settings()
    check(code == 200 and "SQM_ON=0\n" in st and "SQM_DOWN_MBPS=50\n" in st and "SQM_UP_MBPS=20\n" in st, f"off keeps the speeds the owner typed: {st!r}")
    check(idle(cookie) and calls().count("sqm apply") == 4, f"and the router is told to take the shaping off: {calls()!r}")

    # ---- other settings do not start SQM ---------------------------------------------------------------------------------------------------------
    before = calls()
    code, body, _h = call("/api/settings", {"voucher_choice": "off"}, cookie=cookie)
    time.sleep(0.5)
    check(code == 200 and calls() == before, "a change of another setting does not call piso-setup")
    check("sqm" in json.loads(body), "but the answer still carries the SQM block")
    check(call("/api/settings", {"sqm_down": "50"}, cookie="piso_console=x")[0] == 401, "a change needs a session")
    check(call("/api/settings", {"sqm_down": "60"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "and the page's header")
    check("SQM_DOWN_MBPS=50\n" in settings(), "and an unsigned change saved nothing")

    # ---- two quick changes: the last one wins, and apply never runs twice at the same time -------------------------------------------------------------
    open(f"{tmp}/setup-calls", "w").close()
    for v in ("40", "41", "42"):
        call("/api/settings", {"sqm_down": v}, cookie=cookie)
    check(idle(cookie), "all applies end")
    check("SQM_DOWN_MBPS=42\n" in settings() and calls().count("sqm apply") in (2, 3), f"changes made while one apply runs are applied once more, after it: {calls()!r}")
    check(wait_until(lambda: open(f"{tmp}/settings-at-call").read().count("SQM_DOWN_MBPS=42") == 1, 3), "the last call saw the last value")

    # ---- Install now (group 5): only where trying again could work, only on a quiet router, handed over with fixed arguments -------------------------------------------
    os.remove(f"{env.data}/sqm.txt")
    open(f"{tmp}/setup-calls", "w").close()
    i = sqm(cookie)["install"]
    check(i["can"] is True and i["why"] == "", f"never tried: Install now is offered: {i}")
    open(f"{env.data}/packages.txt", "w").write("image|official|OpenWrt 23.05|\npkg|sqm-scripts|skipped|not in the package feed\npkg|kmod-ifb|skipped|not in the package feed\n")
    i = sqm(cookie)["install"]
    check(i["can"] is False and "package feed" in i["why"], f"not in the package feed: trying again cannot help, and it says why: {i}")
    code, body, _h = call("/api/sqm/install", {}, cookie=cookie)
    check(code == 409 and b"NOT_POSSIBLE" in body and calls() == "", f"and a direct call is refused: {code} {body}")
    for why in ("no package manager on this router", "the package manager's trial install (dry run) failed: it does not fit this image"):
        open(f"{env.data}/packages.txt", "w").write(f"pkg|sqm-scripts|skipped|{why}\n")
        check(sqm(cookie)["install"]["can"] is False, f"no retry for: {why}")
    for st, why in (("failed", "the install did not finish, so it was taken out again"), ("skipped", "only 1500 KB of flash is free, and this step needs 2048 KB")):
        open(f"{env.data}/packages.txt", "w").write(f"pkg|sqm-scripts|{st}|{why}\n")
        check(sqm(cookie)["install"]["can"] is True, f"a retry can help: {st}, {why}")
    env.nds_client("aa:bb:cc:00:00:01", STATE="Authenticated", SESSION_END=int(time.time()) + 900, DL=100, UL=10)
    i = sqm(cookie)["install"]
    check(i["can"] is False and "online" in i["why"].lower(), f"customers online: Install now is off: {i}")
    code, body, _h = call("/api/sqm/install", {}, cookie=cookie)
    check(code == 409 and b"BUSY" in body and calls() == "", f"and a direct call is refused with BUSY: {code} {body}")
    env.nds_client("aa:bb:cc:00:00:01", STATE="Preauthenticated")
    check(call("/api/sqm/install", {}, cookie="piso_console=x")[0] == 401 and call("/api/sqm/install", {}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "a session and the page's header are needed")
    t0 = time.time()
    code, body, _h = call("/api/sqm/install", {}, cookie=cookie)
    check(code == 200 and json.loads(body)["sqm"]["installing"] is True and time.time() - t0 < 1.5, f"started, and the answer does not wait: {body}")
    check(wait_until(lambda: calls() == "sqm install\n", 3), f"piso-setup was called with fixed arguments only: {calls()!r}")
    code, body, _h = call("/api/sqm/install", {}, cookie=cookie)
    check(code == 409 and b"RUNNING" in body and calls() == "sqm install\n", "a second start while one runs is refused")
    check(wait_until(lambda: sqm(cookie)["installing"] is False, 10), "and it ends")
    open(f"{env.data}/sqm.txt", "w").write("installed|1\nqdisc|cake\nactive|0\nwan|eth1\nlimit_down_kbps|\nlimit_up_kbps|\nreason|switched off\n")
    code, body, _h = call("/api/sqm/install", {}, cookie=cookie)
    check(code == 409 and b"INSTALLED" in body, f"already installed: nothing to do: {code} {body}")
finally:
    env.close()
finish()
