#!/usr/bin/env python3
"""Reference client for the PisoPhone coin-slot gateway API (standard library only).

Use it to test a box from a PC, or as the model for the router side (OpenNDS binauth script).

  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> arm   --session aa-bb-cc --duration 60
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> status --session aa-bb-cc
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> release --session aa-bb-cc
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> ack   --session aa-bb-cc

  # Whole payment in one command: arm, stay open and count coins until the timeout (or Ctrl+C),
  # then release safely, wait for in-flight coins and print the total. Add --ack to clear the coins.
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> run --session aa-bb-cc --duration 60

Every call first fetches a one-time nonce, then signs: HMAC-SHA256(key, "gw1:<action>:<session>:<nonce>").
"""
import argparse
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def call(box, key, action, session, extra=None, timeout=5):
    base = f"http://{box}/api/gateway"
    with urllib.request.urlopen(f"{base}/challenge", timeout=timeout) as r:
        nonce = json.load(r)["nonce"]
    sig = hmac.new(key.encode(), f"gw1:{action}:{session}:{nonce}".encode(), hashlib.sha256).hexdigest()
    params = {"session": session, "nonce": nonce, "sig": sig, **(extra or {})}
    url = f"{base}/{action}?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, method="POST" if action != "status" else "GET"), timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:  # the box answers errors with a JSON body
        return json.load(e)


def run(box, key, session, duration, ack=False, poll=1.0):
    """Arm, count coins until `duration` seconds pass (or Ctrl+C), then always disarm safely.

    Returns the total pulses received for the session.
    """
    started = call(box, key, "arm", session, {"duration": duration})
    if not started.get("success"):
        print(f"Could not arm: {started.get('error', started)}")
        return 0
    rate = started.get("minutes_per_coin", 0)
    print(f"Armed for {duration}s (session {session}). Insert coins; Ctrl+C stops early.")
    deadline = time.monotonic() + duration
    last = -1
    try:
        while time.monotonic() < deadline:
            time.sleep(poll)
            try:
                st = call(box, key, "status", session)
            except (urllib.error.URLError, OSError) as e:  # a missed poll must not end the session early
                print(f"  (poll failed: {e}; retrying)")
                continue
            if not st.get("success"):
                print(f"  status error: {st.get('error', st)}")
                continue
            if st["pulses"] != last:
                last = st["pulses"]
                print(f"  {last} coin(s) so far ({last * st['minutes_per_coin']} min)")
            if st["state"] != "armed":  # the box ended the session itself (its own 120 s cap)
                print(f"  box reports state '{st['state']}', stopping")
                break
    except KeyboardInterrupt:
        print("\nStopped by user.")
    finally:
        total = release_and_count(box, key, session)
    print(f"Total: {total} coin(s)" + (f" = {total * rate} minute(s)" if rate else ""))
    if ack and total > 0:
        done = call(box, key, "ack", session)
        print(f"Acknowledged {done.get('acknowledged_pulses', '?')} coin(s); they are cleared from the box.")
    elif total > 0:
        print("Coins stay on the box until you run the 'ack' action (or use --ack).")
    return total


def release_and_count(box, key, session, drain_wait=15.0):
    """Disarm, wait until in-flight coins are counted (state idle), return the final pulse count."""
    for _ in range(3):  # a single failed request must not leave the acceptor powered
        try:
            call(box, key, "release", session)
            break
        except (urllib.error.URLError, OSError) as e:
            print(f"  release failed ({e}); retrying")
            time.sleep(1)
    end = time.monotonic() + drain_wait
    pulses = 0
    while time.monotonic() < end:
        try:
            st = call(box, key, "status", session)
        except (urllib.error.URLError, OSError):
            time.sleep(1)
            continue
        pulses = st.get("pulses", pulses)
        if st.get("state") == "idle":
            break
        time.sleep(0.5)
    return pulses


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--box", required=True, help="IP of the ESP32 box")
    p.add_argument("--key", required=True, help="gateway key configured on the box")
    p.add_argument("action", choices=["arm", "status", "release", "ack", "run"])
    p.add_argument("--session", required=True)
    p.add_argument("--duration", type=int, default=60, help="arm/run: seconds to accept coins (5-120, default 60)")
    p.add_argument("--ack", action="store_true", help="run only: acknowledge (clear) the coins after counting")
    a = p.parse_args()
    if a.action == "run":
        run(a.box, a.key, a.session, a.duration, ack=a.ack)
        return
    extra = {"duration": a.duration} if a.action == "arm" else None
    print(json.dumps(call(a.box, a.key, a.action, a.session, extra), indent=2))


if __name__ == "__main__":
    main()
