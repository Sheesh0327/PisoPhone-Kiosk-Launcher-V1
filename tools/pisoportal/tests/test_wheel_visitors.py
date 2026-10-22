#!/usr/bin/env python3
"""Who is offered the wheel (feature 006): with the wheel on, every visitor sees the Spin the wheel button, a device that has paid nothing included,
and so does the "You are online" screen right after paying; the odds can be read without paying but a spin needs paid time; with the wheel off
nothing about the wheel is offered and the free mini-game stays."""
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

from harness import *

env = Env(offset=112)
MAC = "aa:bb:cc:00:00:01"


def spin(sid, bet):
    req = urllib.request.Request(f"http://127.0.0.1:{env.PP}/wheel/spin?id={sid}&bet={bet}", data=b"", method="POST", headers={"X-Piso": "1"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


try:
    # the wheel on, the way the console leaves it: the switch and the owner's record
    Path(f"{env.data}/settings.txt").write_text("WHEEL=on\n")
    Path(f"{env.data}/wheel-confirm.txt").write_text(f"v=1\nversion=1\nat={int(time.time())}\nsession=test\n")
    check(env.start(), "the portal starts")
    env.nds_client(MAC)
    code, page, _ = env.get("/")
    check('id="wh"' in page, "the coin page carries the wheel button")

    # ---- nobody has paid ---------------------------------------------------------------------------------------------------------------
    a = Cust("127.0.0.1", port=env.PP)
    idle = a.wait_state("idle", 5)
    check(idle is not None and idle["left"] == 0 and idle["wh"] is True and idle["gm"] == -1, f"an unpaid visitor is offered the wheel and not the mini-game: {idle and (idle.get('wh'), idle.get('gm'))}")
    code, body, _ = env.get("/wheel/state")
    st = json.loads(body)
    check(code == 200 and st["can_spin"] is False and st["reason"] == "NO_SESSION" and st["max_bet"] == 0 and st["rtp"] == 900 and len(st["segments"]) == 4,
          f"the odds can be read without paying, spinning is not possible: {body[:120]}")
    check(env.get("/wheel")[0] == 200, "the wheel page is served to an unpaid visitor")
    code, r = spin("0123456789abcdef", 5)
    check(code == 403 and r.get("error") == "NO_SESSION", f"a spin from a device with no time is refused by the router: {code} {r}")
    check(not Path(f"{env.data}/wheel.log").exists() or ",spin," not in Path(f"{env.data}/wheel.log").read_text(), "and nothing was charged or logged")

    # ---- a real payment, then the 'You are online' screen ------------------------------------------------------------------------------
    env.set_box(busy=False, coins_at=[0.3, 0.6])
    a.send({"t": "start", "plan": "hyper"})
    fin = a.wait_state("final", 25)
    check(fin is not None and fin["online"] is True and fin["wh"] is True, f"the screen after paying offers the wheel too: {fin and fin.get('wh')}")
    code, body, _ = env.get("/wheel/state")
    st = json.loads(body)
    check(st["can_spin"] is True and st["reason"] == "" and st["max_bet"] >= 1 and st["left_min"] > 5, f"after paying the same state says the device can spin: {body[:160]}")
    code, r = spin("fedcba9876543210", 1)
    check(code == 200 and r["ok"], f"and a spin is accepted: {code} {r}")
    env.revenue()

    # ---- the switch off: nothing about the wheel, the mini-game is back --------------------------------------------------------------
    env.stop()
    Path(f"{env.data}/settings.txt").write_text("WHEEL=off\n")
    Path(f"{env.data}/wheel-confirm.txt").unlink()
    check(env.start(), "the portal restarts with the wheel off")
    b = Cust("127.0.0.1", port=env.PP)
    v = b.wait(lambda m: m.get("s") in ("idle", "final"), 8)
    check(v is not None and v.get("wh") is False, f"with the wheel off the view says so: {v and v.get('wh')}")
    check(env.get("/wheel/state")[0] == 404 and env.get("/wheel")[0] == 404 and spin("0011223344556677", 1)[0] == 404, "and the wheel answers 'not found'")
    check(env.get("/game")[0] == 200, "the free mini-game is served again")
finally:
    env.close()
finish()
