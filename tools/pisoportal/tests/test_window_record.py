#!/usr/bin/env python3
"""AO-11 (feature 021): a coin the box counted is credited exactly once even if the router is killed or loses power before it has
noticed the coin. The router writes the window's record to flash when it arms the box (not when it first sees a coin), so a restart
can ask the box what it holds for that window.
The coin is hidden from the router on purpose (events dropped, a 60 s poll) so that the kill lands exactly in the moment between the
box counting the coin and the router seeing it, which is what was random in the soak."""
import os, time
from harness import *

env = Env(offset=90, COIN_POLL_MS="60000", COIN_FIRST_WAIT_SECONDS="3", COIN_IDLE_WAIT_SECONDS="2", COIN_MAX_SECONDS="8", COIN_DRAIN_SECONDS="3")
MAC = "aa:bb:cc:00:00:01"


def open_files():
    return os.listdir(f"{env.data}/open") if os.path.isdir(f"{env.data}/open") else []


def pesos():
    total = 0
    for line in env.revenue().split("\n"):
        f = line.split(",")
        if len(f) == 6 and f[2].isdigit():
            total += int(f[2])
    return total


try:
    check(env.start(), "the portal starts")
    env.nds_client(MAC)

    # ---- a kill after the box counted a coin and before the router saw it ----------------------------------------------------
    env.set_box(busy=False, coins_at=[0.2], drop_events=True, expire=True)
    a = Cust("127.0.0.1", port=env.PP, reset=True)
    a.wait_state("idle", 5)
    a.send({"t": "start", "plan": "hyper"})
    check(a.wait_state("armed", 10) is not None, "the window opens")
    time.sleep(1.0)   # the box has counted the coin (0.2 s); the router has not (no events, a 60 s poll)
    check(open_files() != [], "the window is on flash while the box is armed, before any coin was seen")
    env.stop()   # kill -9, or a power cut
    a.close()
    check(pesos() == 0, "nothing was credited yet")
    check(env.start(), "the portal starts again")
    check(wait_until(lambda: pesos() == 1, 20), f"the coin the router never saw is credited after the restart: {env.revenue()!r}")
    check(wait_until(lambda: open_files() == [], 10), "and the window record is gone once the box is acknowledged")
    time.sleep(3)
    check(pesos() == 1, "and credited once")
    check(any(l.startswith("ack ") for l in env.boxlines()), "and the box was acknowledged")

    # ---- a window nobody put a coin into leaves nothing behind ----------------------------------------------------------------
    env.set_box(busy=False, coins_at=[], expire=True)
    b = Cust("127.0.0.1", port=env.PP, reset=True)
    b.wait_state("idle", 5)
    b.send({"t": "start", "plan": "hyper"})
    check(b.wait_state("armed", 10) is not None, "an empty window opens")
    check(open_files() != [], "and has its record while it is open")
    check(b.wait_state("empty", 25) is not None, "and ends as empty")
    time.sleep(1.0)
    check(open_files() == [], f"an empty window leaves no record on flash: {open_files()}")
    check(pesos() == 1, "and books nothing")
    b.close()
finally:
    env.close()
finish()
