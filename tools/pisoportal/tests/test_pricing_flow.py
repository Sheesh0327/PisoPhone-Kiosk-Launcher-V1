#!/usr/bin/env python3
"""A price change made in the console while a customer pays: that payment keeps the prices it started with, the next one gets the new ones."""
import json, os, urllib.request
from harness import *

CP = 18310
env = Env(offset=60)
tmp = env.tmp
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf")


def call(path, body=None, cookie=None):
    req = urllib.request.Request(f"http://127.0.0.1:{CP}{path}", data=None if body is None else json.dumps(body).encode(), method="GET" if body is None else "POST")
    if body is not None:
        req.add_header("X-Piso", "1")
        req.add_header("Content-Type", "application/json")
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, r.read(), r.headers


try:
    check(env.start(), "the portal starts")
    check(wait_until(lambda: env.get("/ping")[1] == "ok", 5), "the coin page answers")
    env.nds_client("aa:bb:cc:00:00:01")
    env.nds_client("aa:bb:cc:00:00:02")
    cookie = call("/api/login", {"password": "router-pass"})[2].get("Set-Cookie", "").split(";")[0]
    check(cookie.startswith("piso_console="), "the owner signs in to the console")

    # a customer pays 3 pesos on HyperSpeed (6 minutes a peso = 18); the owner doubles the minutes per peso while the coins go in
    env.set_box(busy=False, coins_at=[0.3, 0.6, 3.2])
    a = Cust("127.0.0.1", port=env.PP, reset=True)
    a.wait_state("idle", 5)
    a.send({"t": "start", "plan": "hyper"})
    check(a.wait_state("armed", 10) is not None, "the window is armed")
    code, body, _h = call("/api/pricing", {"prorata": "20"}, cookie)
    check(code == 200 and json.loads(body)["prorata"] == 20, "the owner changes the minutes per peso to 20 while the customer is paying")
    fin = a.wait_state("final", 30)
    check(fin is not None and fin["pulses"] == 3 and fin["minutes"] == 18, f"that payment keeps the prices it started with (3 pesos = 18 min): {fin}")
    a.close()
    # (the page hears the result a moment before the box has been acknowledged; the next customer can start after that)
    check(wait_until(lambda: not os.path.isdir(f"{env.data}/open") or os.listdir(f"{env.data}/open") == [], 10), "the box is acknowledged")

    # the next customer pays the same 3 pesos at the new prices
    env.set_box(busy=False, coins_at=[0.3, 0.6, 0.9])
    b = Cust("127.0.0.2", port=env.PP, reset=True)
    b.wait_state("idle", 5)
    b.send({"t": "start", "plan": "hyper"})
    fin = b.wait_state("final", 30)
    check(fin is not None and fin["pulses"] == 3 and fin["minutes"] == 60, f"the next payment uses the new prices (3 pesos = 60 min): {fin}")
    b.close()
    check(",hyper,3,18," in env.revenue() and ",hyper,3,60," in env.revenue(), "each payment was booked at its own prices")
finally:
    env.close()
finish()
