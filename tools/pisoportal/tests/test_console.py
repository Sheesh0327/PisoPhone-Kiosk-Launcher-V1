#!/usr/bin/env python3
"""The owner's console: sign-in with the router password, lock-out, the overview, the customer list with speeds, the rental phones, kicking a device."""
import http.server, json, os, sys, threading, time, urllib.error, urllib.request
from harness import *

HERE = os.path.dirname(os.path.abspath(__file__))
CP = 18290
env = Env(offset=40)
tmp = env.tmp
# a fake `ubus`: the password "router-pass" is the router's
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/leases", "w").write("1791858999 aa:bb:cc:00:00:01 10.0.30.1 Rowels-iPhone 01:aa\n")
open(f"{tmp}/route", "w").write("Iface\tDestination\tGateway\tFlags\neth9\t00000000\t0101A8C0\t0003\n")
open(f"{tmp}/dev", "w").write(" face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n  eth9: 1000 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0\n")
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")


class Stub(http.server.BaseHTTPRequestHandler):   # the coin box: a nonce for the gateway, and the dashboard's status with a password
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/api/gateway/challenge"):
            body = b'{"nonce":"00112233445566778899aabbccddeeff"}'
        elif self.path == "/api/status":
            import base64
            if self.headers.get("Authorization") != "Basic " + base64.b64encode(b"admin:box-pass-123").decode():
                self.send_response(401); self.end_headers(); return
            body = json.dumps({"devices": [{"slotNum": 1, "isBound": True, "online": True, "name": "Terminal 1", "ip": "10.0.0.51", "time": 600, "battery": 80, "charging": False},
                                           {"slotNum": 2, "isBound": False}]}).encode()
        else:
            body = b"{}"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)


stub = http.server.ThreadingHTTPServer(("127.0.0.1", CP + 1), Stub)
threading.Thread(target=stub.serve_forever, daemon=True).start()
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", DHCP_LEASES=f"{tmp}/leases", ROUTE_FILE=f"{tmp}/route", NETDEV_FILE=f"{tmp}/dev",
                 PISO_SETUP_CONF=f"{tmp}/setup.conf", GW_BOX=f"127.0.0.1:{CP + 1}")


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


try:
    check(env.start(), "the portal starts")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    code, body, h = call("/")
    check(b"TechNet PisoHub" in body and "default-src 'none'" in h.get("Content-Security-Policy", ""), "the page is the TechNet console, with a strict content security policy")
    check(len(body) < 20000, f"the page is small ({len(body)} bytes)")
    check(call("/api/overview")[0] == 401 and call("/api/clients")[0] == 401 and call("/api/phones")[0] == 401, "no session, no data")
    check(call("/brand.png")[0] == 200 and call("/mascot.png")[0] == 200, "the logo and the mascot are served")
    check(call("/api/login", {"password": "router-pass"}, headers={"X-Piso": "0"})[0] == 403, "a login without the page's header is refused")
    check(call("/api/login", {"password": "router-pass"}, headers={"Origin": "http://evil.example"})[0] == 403, "a login from another site is refused")
    code, body, h = call("/api/login", {"password": "wrong"})
    check(code == 401 and "Set-Cookie" not in h, "a wrong password is refused")
    code, body, h = call("/api/login", {"password": "router-pass"})
    cookie = (h.get("Set-Cookie") or "").split(";")[0]
    check(code == 200 and cookie.startswith("piso_console=") and "HttpOnly" in h.get("Set-Cookie", "") and "SameSite=Strict" in h.get("Set-Cookie", ""), "the router's password signs in (HttpOnly, SameSite=Strict cookie)")
    # lock-out after five wrong passwords
    for _ in range(5):
        call("/api/login", {"password": "nope"})
    code, body, _h = call("/api/login", {"password": "router-pass"})
    check(code == 429 and b"LOCKED" in body, "five wrong passwords lock the sign-in for a minute (even the right one)")
    # data
    env.nds_client("aa:bb:cc:00:00:01", STATE="Authenticated", SESSION_END=int(time.time()) + 900, DL=2048, UL=512)
    env.nds_client("aa:bb:cc:00:00:02", STATE="Preauthenticated")
    open(f"{env.data}/revenue.csv", "w").write(f"{int(time.time())},hyper,5,30,new,x\n{int(time.time()) - 3 * 86400},endurance,17,690,new,x\n")
    time.sleep(6)   # two internet-line samples
    open(f"{tmp}/dev", "w").write(" face |bytes\n  eth9: 626000 1 0 0 0 0 0 0 125500 1 0 0 0 0 0 0\n")
    time.sleep(6)
    code, body, _h = call("/api/overview", cookie=cookie)
    j = json.loads(body)
    check(code == 200 and j["online"] == 1 and j["waiting"] == 1 and j["wan_if"] == "eth9", f"overview: customers and the internet line: {j}")
    check(j["today"] == 5 and j["total"] == 22 and sum(j["week"]) == 22 and j["box_ok"] is True and j["ledger_lines"] >= 0, f"overview: money and the coin box: {j}")
    check(j["rx_kbps"] > 0 and j["tx_total"] == 125500, f"overview: the internet speed is worked out from the counters: rx {j['rx_kbps']} kbps")
    code, body, _h = call("/api/clients", cookie=cookie)
    first = json.loads(body)["clients"]
    check(code == 200 and len(first) == 2 and any(c["name"] == "Rowels-iPhone" and c["state"] == "Authenticated" for c in first), f"clients: name from the lease file, state: {first}")
    env.nds_client("aa:bb:cc:00:00:01", STATE="Authenticated", SESSION_END=int(time.time()) + 900, DL=2048 + 1250, UL=512 + 250)
    time.sleep(2)
    second = json.loads(call("/api/clients", cookie=cookie)[1])["clients"]
    me = [c for c in second if c["mac"] == "aa:bb:cc:00:00:01"][0]
    check(me["dl_kbps"] > 0 and me["ul_kbps"] > 0 and me["dl_kb"] == 3298 and second[0]["mac"] == "aa:bb:cc:00:00:01", f"clients: a speed from the counters, the busiest first: {me}")
    code, body, _h = call("/api/phones", cookie=cookie)
    check(json.loads(body)["devices"][0]["time"] == 600, "phones: the coin box's slot list, fetched with its stored password: " + body.decode()[:100])
    open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='changed'\n")
    check(json.loads(call("/api/phones", cookie=cookie)[1]).get("error") == "PASSWORD", "phones: a password the box no longer accepts is named")
    check(call("/api/kick", {"mac": "not-a-mac"}, cookie=cookie)[0] == 400, "kick: a bad address is refused")
    check(call("/api/kick", {"mac": "aa:bb:cc:00:00:01"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "kick: needs the page's header")
    code, _b, _h = call("/api/kick", {"mac": "aa:bb:cc:00:00:01"}, cookie=cookie)
    check(code == 200 and env.nds_get("aa:bb:cc:00:00:01")["STATE"] == "Preauthenticated", "kick: the device is disconnected")
    code, _b, _h = call("/api/logout", {}, cookie=cookie)
    check(code == 200 and call("/api/overview", cookie=cookie)[0] == 401, "sign out ends the session")
finally:
    env.close()
    stub.shutdown()
finish()
