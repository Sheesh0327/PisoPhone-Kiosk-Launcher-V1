#!/usr/bin/env python3
"""The coin box's link is lost while a window closes: no coin is lost and none is credited twice.
  1. The router saw no coin because the link was down, but the box counted two: recovery credits them once the box answers.
  2. The router credited one coin, then lost the link before the box said it was idle, and the box counted one more: the
     acknowledgement waits for recovery, which credits the late coin first (an acknowledgement erases what the box holds)."""
import os, time
from harness import *

env = Env(offset=80, COIN_DRAIN_SECONDS="3")
MAC = "aa:bb:cc:00:00:01"


def minutes_of(mac):
    e = env.roll().get(mac)
    return int(e[5]) if e else 0


try:
    check(env.start(), "the portal starts")
    env.nds_client(MAC)

    # ---- 1. nothing seen, two coins on the box -------------------------------------------------------------------------------
    env.set_box(busy=False, coins_at=[0.5, 1.0], events=False, status_fail=True)
    a = Cust("127.0.0.1", port=env.PP, reset=True)
    a.wait_state("idle", 5)
    a.send({"t": "start", "plan": "hyper"})
    check(a.wait_state("armed", 10) is not None, "the window opens although status calls fail")
    a.wait_state("final", 25) or a.wait(lambda m: m.get("s") in ("empty", "idle"), 25)
    time.sleep(1)
    check(MAC not in env.roll(), "nothing is credited while the box cannot be asked")
    check(os.path.isdir(f"{env.data}/open") and os.listdir(f"{env.data}/open") != [], "the window is kept on flash for recovery (not closed as empty)")
    check(not any(l.startswith("ack ") for l in env.boxlines()), "and the box is not acknowledged")
    env.set_box(busy=False, coins_at=[0.5, 1.0], events=False)   # the link is back
    check(wait_until(lambda: minutes_of(MAC) > 0, 15), "recovery credits the two coins once the box answers: " + str(env.roll().get(MAC)))
    first = minutes_of(MAC)
    check(wait_until(lambda: os.listdir(f"{env.data}/open") == [], 10) and any(l.startswith("ack ") for l in env.boxlines()),
          "then acknowledges the box and forgets the window")
    time.sleep(3)
    check(minutes_of(MAC) == first, "and credits it only once")
    a.close()

    # ---- 2. one coin credited, one more counted by the box after the link dropped --------------------------------------------
    open(env.boxlog, "w").close()
    env.set_box(busy=False, coins_at=[0.5], events=True)
    b = Cust("127.0.0.1", port=env.PP, reset=True)
    b.wait_state("idle", 5)
    before = minutes_of(MAC)
    b.send({"t": "start", "plan": "hyper"})
    check(b.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 1, 10) is not None, "the first coin is seen")
    env.set_box(busy=False, coins_at=[0.5], events=True, drop_events=True, status_fail=True, extra=1)   # link lost (events too); the box counts one more
    fin = b.wait_state("final", 30)
    check(fin is not None and fin.get("pulses") == 1, f"the customer is credited the coin the router saw: {fin}")
    paid_one = minutes_of(MAC)
    check(paid_one > before, "and is online with it")
    check(not any(l.startswith("ack ") for l in env.boxlines()), "the box is NOT acknowledged while it never said it is idle")
    env.set_box(busy=False, coins_at=[0.5], events=True, extra=1)   # the link is back
    check(wait_until(lambda: any(l.startswith("ack ") for l in env.boxlines()), 15), "recovery acknowledges the box once it is idle")
    late = minutes_of(MAC)
    check(late > paid_one, f"after crediting the late coin first ({paid_one} -> {late} minutes)")
    time.sleep(3)
    check(minutes_of(MAC) == late, "once")
    check("late coins" in env.log_text(), "and the log says so")
    b.close()
finally:
    env.close()
finish()
