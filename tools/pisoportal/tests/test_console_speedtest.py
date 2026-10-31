#!/usr/bin/env python3
"""The console's "Test my line" (feature 058, group 5c): POST /api/speedtest starts `piso-setup sqm test` outside the request, only when it may (SQM installed, nobody
online, one at a time, one a minute), and GET /api/settings carries the state, the result and the reason a start is not possible."""
import json
import os
import time
import urllib.error
import urllib.request

from harness import *

CP = 18810
env = Env(offset=235)
tmp = env.tmp
RESULT = f"{tmp}/piso-speedtest.json"
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
# a fake piso-setup: `sqm test` behaves as the file "mode" says, after a second; anything else is only written down
open(f"{tmp}/piso-setup", "w").write(f'''#!/bin/sh
echo "$*" >> "{tmp}/setup-calls"
[ "$1 $2" = "sqm test" ] || exit 0
sleep 1
now=$(date +%s)
case "$(cat "{tmp}/mode" 2>/dev/null)" in
  fail) echo '{{"state":"failed","down":null,"up":null,"host":"","cpu":3,"cpu_limited":false,"time":'$now',"reason":"no speed test server answered (is the router online?)"}}' > "{RESULT}"; exit 1 ;;
  busy) exit 3 ;;
  cpu) echo '{{"state":"done","down":312.5,"up":88.0,"host":"speed.cloudflare.com","cpu":97,"cpu_limited":true,"time":'$now',"reason":""}}' > "{RESULT}" ;;
  *) echo '{{"state":"done","down":87.4,"up":18.2,"host":"speed.cloudflare.com","cpu":31,"cpu_limited":false,"time":'$now',"reason":""}}' > "{RESULT}" ;;
esac
exit 0
''')
os.chmod(f"{tmp}/piso-setup", 0o755)
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf", PISO_SETUP_BIN=f"{tmp}/piso-setup",
                 SPEEDTEST_FILE=RESULT, SPEEDTEST_GAP_SECONDS="4")


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


def calls():
    return open(f"{tmp}/setup-calls").read() if os.path.exists(f"{tmp}/setup-calls") else ""


def test_block(cookie):
    return json.loads(call("/api/settings", cookie=cookie)[1])["sqm"]["speedtest"]


def finished(cookie, state=None):
    return wait_until(lambda: test_block(cookie)["state"] not in ("running", "idle") and (state is None or test_block(cookie)["state"] == state), 10)


def mode(m):
    open(f"{tmp}/mode", "w").write(m)


try:
    check(env.start(), "the portal starts")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    code, _b, h = call("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]
    check(code == 200 and cookie.startswith("piso_console="), "signed in")

    # ---- SQM not installed: the button is off, with the reason; a direct call starts nothing -------------------------------------------------------------
    t = test_block(cookie)
    check(t["state"] == "idle" and t["down"] is None and t["up"] is None and t["can_start"] is False and t["blocked"] != "", f"nothing installed: idle, cannot start, and says why: {t}")
    code, body, _h = call("/api/speedtest", {}, cookie=cookie)
    check(code == 409 and b"NO_SQM" in body and calls() == "", f"a direct call is refused too: {code} {body}")

    open(f"{env.data}/sqm.txt", "w").write("installed|1\nqdisc|cake\nactive|0\nwan|eth1\nlimit_down_kbps|\nlimit_up_kbps|\nreason|switched off\n")
    t = test_block(cookie)
    check(t["can_start"] is True and t["blocked"] == "" and t["state"] == "idle", f"SQM installed and a quiet router: the button is on: {t}")

    # ---- a customer is online: refused with BUSY, nothing starts ---------------------------------------------------------------------------------------------
    env.nds_client("aa:bb:cc:00:00:01", STATE="Authenticated", SESSION_END=int(time.time()) + 900, DL=100, UL=10)
    t = test_block(cookie)
    check(t["can_start"] is False and "online" in t["blocked"].lower(), f"a customer online: the button is off with the reason: {t}")
    code, body, _h = call("/api/speedtest", {}, cookie=cookie)
    check(code == 409 and b"BUSY" in body and calls() == "", f"and a direct call is refused with BUSY: {code} {body}")
    env.nds_client("aa:bb:cc:00:00:01", STATE="Preauthenticated")
    check(test_block(cookie)["can_start"] is True, "a customer who is only waiting on the page does not block the test")

    # ---- the session and the page's header are needed -----------------------------------------------------------------------------------------------------
    check(call("/api/speedtest", {}, cookie="piso_console=x")[0] == 401, "a test needs a session")
    check(call("/api/speedtest", {}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "and the page's header")
    check(call("/api/speedtest", {}, cookie=cookie, headers={"Origin": "http://evil.example"})[0] == 403, "and not from another site")
    check(calls() == "", "and those started nothing")

    # ---- a good test ---------------------------------------------------------------------------------------------------------------------------------------
    mode("ok")
    t0 = time.time()
    code, body, _h = call("/api/speedtest", {}, cookie=cookie)
    took = time.time() - t0
    j = json.loads(body)
    check(code == 200 and j["ok"] is True and j["sqm"]["speedtest"]["state"] == "running", f"started: {body}")
    check(took < 0.8, f"the answer does not wait for the test ({took:.1f} s)")
    check(wait_until(lambda: calls() == "sqm test\n", 3), f"piso-setup was called with fixed arguments only: {calls()!r}")
    t = test_block(cookie)
    check(t["state"] == "running" and t["can_start"] is False and "running" in t["blocked"].lower(), f"while it runs the button is off: {t}")
    code, body, _h = call("/api/speedtest", {}, cookie=cookie)
    check(code == 409 and b"RUNNING" in body and calls() == "sqm test\n", f"a second start while one runs is refused: {code} {body}")
    check(finished(cookie, "done"), "the test ends")
    t = test_block(cookie)
    check(abs(t["down"] - 87.4) < 0.01 and abs(t["up"] - 18.2) < 0.01 and t["host"] == "speed.cloudflare.com" and t["cpu_limited"] is False and t["reason"] == "", f"the result: {t}")
    check(t["age"] is not None and 0 <= t["age"] < 5, f"with its age: {t}")
    check("SQM_DOWN_MBPS" not in open(f"{env.data}/settings.txt").read() if os.path.exists(f"{env.data}/settings.txt") else True, "a test saves no settings (the owner presses Save)")
    code, body, _h = call("/api/speedtest", {}, cookie=cookie)
    check(code == 429 and b"TOO_SOON" in body and calls() == "sqm test\n", f"another test within the gap is refused: {code} {body}")
    t = test_block(cookie)
    check(t["can_start"] is False and "wait" in t["blocked"].lower(), f"and the button says to wait: {t}")
    check(wait_until(lambda: test_block(cookie)["can_start"] is True, 6), "after the gap the button is on again")

    # ---- the router itself was the limit ------------------------------------------------------------------------------------------------------------------
    mode("cpu")
    check(call("/api/speedtest", {}, cookie=cookie)[0] == 200 and finished(cookie, "done"), "a second test runs")
    t = test_block(cookie)
    check(t["cpu_limited"] is True and t["cpu"] == 97 and abs(t["down"] - 312.5) < 0.01, f"the busy router is reported: {t}")
    time.sleep(4.2)

    # ---- the test cannot reach any host --------------------------------------------------------------------------------------------------------------------
    mode("fail")
    check(call("/api/speedtest", {}, cookie=cookie)[0] == 200 and finished(cookie, "failed"), "a failing test ends")
    t = test_block(cookie)
    check(t["down"] is None and t["up"] is None and "answered" in t["reason"], f"nothing to prefill, and it says why: {t}")
    time.sleep(4.2)

    # ---- the router says BUSY (a customer came between the check and the start) -------------------------------------------------------------------------------------
    mode("busy")
    check(call("/api/speedtest", {}, cookie=cookie)[0] == 200 and finished(cookie, "failed"), "a refused start ends")
    t = test_block(cookie)
    check(t["down"] is None and "online" in t["reason"].lower(), f"and the reason is the customers: {t}")
    time.sleep(4.2)

    # ---- a result left by a test that was killed ------------------------------------------------------------------------------------------------------------------
    open(RESULT, "w").write('{"state":"running","down":null,"up":null,"host":"","cpu":0,"cpu_limited":false,"time":%d,"reason":""}\n' % int(time.time()))
    t = test_block(cookie)
    check(t["state"] == "failed" and "interrupted" in t["reason"], f"a 'running' file with no test running is shown as interrupted, not as for ever running: {t}")
    check(t["can_start"] is True, "and the button is on")
    os.remove(RESULT)
    open(f"{tmp}/setup-calls", "w").close()
    check(call("/api/settings", {"voucher_choice": "on"}, cookie=cookie)[0] == 200 and calls() == "", "other settings do not start a test")
finally:
    env.close()
finish()
