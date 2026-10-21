#!/usr/bin/env python3
"""The spinning wheel, end to end: it is off until the owner confirms the responsibility warning in the console (a typed phrase and the warning's
version; a setting typed into a file is not enough), the free mini-game stays while it is off, a spin changes a paying customer's time once (a repeat of
the same spin id changes nothing, even after a restart), another site cannot spin for a customer, the revenue ledger is never touched, and turning
the wheel off is immediate."""
import json, os, urllib.error, urllib.request
from harness import *

CP = 18400
env = Env(offset=100)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf")
MAC = "aa:bb:cc:00:00:01"
ID1, ID2 = "0123456789abcdef", "fedcba9876543210"
PHRASE = "I TAKE RESPONSIBILITY"


def console(path, body=None, cookie=None):
    req = urllib.request.Request(f"http://127.0.0.1:{CP}{path}", data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    if body is not None:
        req.add_header("X-Piso", "1")
        req.add_header("Content-Type", "application/json")
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read() or b"{}"), r.headers
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), e.headers


def spin(sid, bet, headers=None):
    h = {"X-Piso": "1"} if headers is None else headers
    req = urllib.request.Request(f"http://127.0.0.1:{env.PP}/wheel/spin?id={sid}&bet={bet}", data=b"", method="POST", headers=h)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def minutes_bought():
    return int(env.roll()[MAC][5])


try:
    check(env.start(), "the portal starts")
    now = int(time.time())
    with open(f"{env.data}/vouchers.txt", "w") as f:
        f.write(f"c1,0,0,0,0,60,{now},{MAC},0,0,0,hyper,w1,10,60,10,1\n")
    revenue_before = env.revenue()
    code, body, h = console("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]
    check(code == 200 and cookie.startswith("piso_console="), "the owner signs in to the console")

    # ---- off: nothing of the wheel shows, the free mini-game is still there --------------------------------------------------------
    check(env.get("/wheel")[0] == 404 and env.get("/wheel/state")[0] == 404, "the wheel pages answer 'not found' while it is off")
    check(spin(ID1, 5)[0] == 404 and minutes_bought() == 60, "a spin is refused while it is off and the time is untouched")
    check(env.get("/game")[0] == 200, "the free mini-game is still served while the wheel is off")
    c = Cust("127.0.0.1", port=env.PP)
    st = c.wait_state("idle")
    check(st is not None and st.get("wh") is False and st.get("gm", -1) > 0, f"the coin page offers the mini-game and no wheel: {st and (st.get('wh'), st.get('gm'))}")

    # ---- the responsibility gate ---------------------------------------------------------------------------------------------------
    code, j, _ = console("/api/wheel", cookie=cookie)
    check(code == 200 and j["on"] is False and j["phrase"] == PHRASE and j["warning_version"] == 1, f"the console says the wheel is off and which phrase is wanted: {j}")
    for body, name in [({"on": "1"}, "no phrase"), ({"on": "1", "phrase": "i take responsibility", "version": "1"}, "lower case"),
                       ({"on": "1", "phrase": PHRASE + " ", "version": "1"}, "an extra space"), ({"on": "1", "phrase": PHRASE, "version": "0"}, "an old warning version"),
                       ({"on": "1", "phrase": PHRASE}, "no version")]:
        code, j, _ = console("/api/wheel", body, cookie)
        check(code == 403 and j.get("error") == "CONFIRM", f"turning on with {name} is refused: {code} {j}")
    check(env.get("/wheel/state")[0] == 404 and not os.path.exists(f"{env.data}/wheel-confirm.txt"), "after the refusals the wheel is still off and nothing is on record")
    check(console("/api/wheel", {"on": "1", "phrase": PHRASE, "version": "1"})[0] == 401, "turning on without signing in is refused")

    # a setting typed into a file does not turn it on, not even after a restart
    open(f"{env.data}/settings.txt", "a").write("WHEEL=on\n")
    env.stop()
    check(env.start(), "the portal restarts with WHEEL=on typed into the settings")
    check(env.get("/wheel/state")[0] == 404 and spin(ID1, 5)[0] == 404, "WHEEL=on alone leaves the wheel off")
    check("stays off" in open(f"{env.data}/wheel.log").read(), "and the wheel log says why")
    code, body, h = console("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]

    c = Cust("127.0.0.1", port=env.PP)   # (the first page was closed by the restart)
    check(c.wait_state("idle") is not None, "a coin page is open again")

    # ---- the owner turns it on ----------------------------------------------------------------------------------------------------
    code, j, _ = console("/api/wheel", {"table": "2x3 0x1"}, cookie)
    check(code == 400 and j.get("error") == "BAD_TABLE", f"a wheel that pays back more than 100% is refused: {j}")
    code, j, _ = console("/api/wheel", {"table": "2x1 0x1", "wait": "0", "max_bet": "10"}, cookie)
    check(code == 200 and j["table"] == "2x1 0x1" and j["wait"] == 0 and j["on"] is False, f"a fair table and the limits are saved while it is still off: {j}")
    code, j, _ = console("/api/wheel", {"on": "1", "phrase": PHRASE, "version": "1"}, cookie)
    check(code == 200 and j["on"] is True and isinstance(j["since"], int), f"the exact phrase with the current warning turns it on: {j}")
    check(os.path.exists(f"{env.data}/wheel-confirm.txt"), "the router keeps a record of the confirmation")
    code, page, _h = env.get("/wheel")
    check(code == 200 and "Spin the wheel" in page and "same for everyone" in page, "the wheel page is served, with the odds panel")
    check(env.get("/game")[0] == 404, "the free mini-game is replaced while the wheel is on")
    st = c.wait(lambda m: m.get("wh") is True, 8)
    check(st is not None and st.get("gm") == -1, "an open coin page is told at once: wheel button, no mini-game")

    # ---- a customer spins -----------------------------------------------------------------------------------------------------------
    code, s = env.get("/wheel/state")[0], json.loads(env.get("/wheel/state")[1])
    check(code == 200 and s["rtp"] == 1000 and s["spins_left"] == 10 and s["max_bet"] == 10, f"the state shows the odds, the spins left and the most that may be bet: {s}")
    check(spin(ID1, 5, headers={})[0] == 403, "a spin without the page's own header is refused")
    check(spin(ID1, 5, headers={"X-Piso": "1", "Origin": "http://evil.example"})[0] == 403, "a spin asked for by another site is refused")
    check(spin("zz", 5)[0] == 400 and spin(ID1, 0)[0] == 400 and spin(ID1, 11)[0] == 400 and minutes_bought() == 60, "a bad id and bad bets are refused and charge nothing")
    code, r = spin(ID1, 5)
    check(code == 200 and r["ok"] and r["delta"] in (5, -5), f"a spin answers with the router's result: {r}")
    after = minutes_bought()
    check(after == 60 + r["delta"] and r["left_min"] in (after, after - 1), f"the customer's time changed by exactly the stated amount: {after}")
    code, again = spin(ID1, 9)
    check(code == 200 and again["repeated"] is True and again["delta"] == r["delta"] and minutes_bought() == after, "the same spin id again: the same answer, no second charge")
    check(env.revenue() == revenue_before, "the revenue ledger is untouched")
    env.stop()
    check(env.start(), "the portal restarts")
    code, again = spin(ID1, 9)
    check(code == 200 and again["repeated"] is True and minutes_bought() == after, "after a restart the same spin id still gives the first answer and charges nothing")
    code, r2 = spin(ID2, 3)
    check(code == 200 and r2["spins_left"] == 8, f"the spins left today count down by one per spin: {r2}")
    log = open(f"{env.data}/wheel.log").read()
    check(log.count(",spin,") == 2 and ",on," in log, "each spin and the turn on are in the wheel log")

    # ---- off again --------------------------------------------------------------------------------------------------------------------
    code, body, h = console("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]
    code, j, _ = console("/api/wheel", {"off": "1"}, cookie)
    check(code == 200 and j["on"] is False, "the owner turns it off")
    check(env.get("/wheel/state")[0] == 404 and spin("00112233445566ff", 5)[0] == 404, "off takes effect at once")
    check(not os.path.exists(f"{env.data}/wheel-confirm.txt") and env.get("/game")[0] == 200, "the record is cleared and the mini-game is back")
    code, j, _ = console("/api/wheel", {"on": "1"}, cookie)
    check(code == 403, "the next turn on asks for the confirmation again")
    check(minutes_bought() == after + r2["delta"], "minutes already won or lost stay as they are")
finally:
    env.close()
finish()
