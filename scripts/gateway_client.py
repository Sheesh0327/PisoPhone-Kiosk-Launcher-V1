#!/usr/bin/env python3
"""Reference client for the PisoPhone coin-slot gateway API (standard library only).

Use it to test a box from a PC, or as the model for the router side (OpenNDS binauth script).

  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> arm   --session aa-bb-cc --duration 60
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> status --session aa-bb-cc
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> release --session aa-bb-cc
  python3 scripts/gateway_client.py --box 192.168.1.50 --key <gateway key> ack   --session aa-bb-cc

Every call first fetches a one-time nonce, then signs: HMAC-SHA256(key, "gw1:<action>:<session>:<nonce>").
"""
import argparse
import hashlib
import hmac
import json
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


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--box", required=True, help="IP of the ESP32 box")
    p.add_argument("--key", required=True, help="gateway key configured on the box")
    p.add_argument("action", choices=["arm", "status", "release", "ack"])
    p.add_argument("--session", required=True)
    p.add_argument("--duration", type=int, help="arm only: seconds to accept coins (5-120)")
    a = p.parse_args()
    extra = {"duration": a.duration} if a.action == "arm" and a.duration else None
    print(json.dumps(call(a.box, a.key, a.action, a.session, extra), indent=2))


if __name__ == "__main__":
    main()
