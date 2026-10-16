#!/usr/bin/env python3
"""The owner's console: sign-in with the router password, lock-out, the overview, the customer list with speeds, the rental phones, kicking a device."""
import http.server, json, os, threading, time, urllib.error, urllib.request
from harness import *

HERE = os.path.dirname(os.path.abspath(__file__))
CP = 18290
env = Env(offset=40)
open(f"{env.data}/send.key", "w").write("k" * 40 + "\n")   # the print server's key is installed on this router
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
    # a paused device stays online (slowed to a crawl): the list says so, and its time is the time on hold
    now = int(time.time())
    env.nds_client("aa:bb:cc:00:00:09", STATE="Authenticated", SESSION_END=now + 86000, UPRATE=1, DOWNRATE=1)
    with open(f"{env.data}/vouchers.txt", "a") as f:
        f.write(f"pz,0,0,0,0,60,{now - 600},aa:bb:cc:00:00:09,1,{now - 60},1500,hyper,w9,5,60,5,1\n")
    rows = json.loads(call("/api/clients", cookie=cookie)[1])["clients"]
    pz = [c for c in rows if c["mac"] == "aa:bb:cc:00:00:09"][0]
    check(pz["paused"] is True and pz["state"] == "Authenticated" and pz["left"] == 1500 and all(c["paused"] is False for c in rows if c["mac"] != "aa:bb:cc:00:00:09"),
          f"clients: a paused device is marked paused, with its time on hold: {pz}")
    code, body, _h = call("/api/phones", cookie=cookie)
    check(json.loads(body)["devices"][0]["time"] == 600, "phones: the coin box's slot list, fetched with its stored password: " + body.decode()[:100])
    open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='changed'\n")
    check(json.loads(call("/api/phones", cookie=cookie)[1]).get("error") == "PASSWORD", "phones: a password the box no longer accepts is named")
    check(call("/api/kick", {"mac": "not-a-mac"}, cookie=cookie)[0] == 400, "kick: a bad address is refused")
    check(call("/api/kick", {"mac": "aa:bb:cc:00:00:01"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "kick: needs the page's header")
    code, _b, _h = call("/api/kick", {"mac": "aa:bb:cc:00:00:01"}, cookie=cookie)
    check(code == 200 and env.nds_get("aa:bb:cc:00:00:01")["STATE"] == "Preauthenticated", "kick: the device is disconnected")
    # ---- vouchers: the list of what was issued, cancelling an unused one; no more batches or printing ----------------------------------------------
    legacy = legacy_batch(env.data, minutes=60, pesos=10, plan="hyper", count=3, days=30)
    now = int(time.time())
    # a voucher a customer bought with coins: a batch of one that remembers the device and the coin window
    open(f"{env.data}/voucher_batches.txt", "a").write(f"c0ffee,{'ee' * 16},1,18,3,hyper,{now + 30 * 86400},1,{now},0,,aa:bb:cc:00:00:02,{'ab' * 16}\n")
    open(f"{env.data}/voucher_codes.txt", "a").write("0123456789abcdef,c0ffee,0,\n")
    code, body, _h = call("/api/vouchers", cookie=cookie)
    v = json.loads(body)["batches"]
    old = [b for b in v if not b["bought"]]
    mine = [b for b in v if b["bought"]]
    check(code == 200 and len(old) == 1 and old[0]["count"] == 3 and old[0]["used"] == 0 and old[0]["minutes"] == 60 and old[0]["plan"] == "hyper" and old[0]["expires"] > now,
          f"the list shows an old printed batch (what, how many used, plan, expiry): {v}")
    check(len(mine) == 1 and mine[0]["id"] == "c0ffee" and mine[0]["minutes"] == 18 and mine[0]["count"] == 1 and mine[0]["created"] == now and mine[0]["void"] is False,
          f"and a voucher a customer bought (issued time, minutes, plan, not used, expiry): {mine}")
    check("codes" not in body.decode() and legacy[0].replace("-", "") not in body.decode(), "the list carries no codes")
    check(call("/api/vouchers/new", {"minutes": 60, "count": 5}, cookie=cookie)[0] == 404 and call("/api/vouchers/codes?batch=c0ffee", cookie=cookie)[0] == 404,
          "making batches and reading codes are gone")
    check(call("/api/vouchers/void", {"batch": "c0ffee"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "cancelling needs the page's header")
    check(call("/api/vouchers/void", {"batch": "nope"}, cookie=cookie)[0] == 404, "cancelling an unknown voucher is refused")
    check(call("/api/vouchers/void", {"batch": "c0ffee"}, cookie=cookie)[0] == 200, "the owner can cancel an unused voucher")
    check([b["void"] for b in json.loads(call("/api/vouchers", cookie=cookie)[1])["batches"] if b["id"] == "c0ffee"] == [True], "and it shows as cancelled")
    # ---- the sales report: coins, vouchers issued and redeemed, old slips ------------------------------------------------------------------------
    t0 = int(time.time())
    open(f"{env.data}/revenue.csv", "w").write(f"{t0},hyper,5,30,new,x\n{t0},hyper,10,60,vbuy,x\n{t0},endurance,2,30,vbuy,x\n{t0 - 86400},endurance,20,1440,voucher,x\n")
    code, body, _h = call("/api/sales?days=7", cookie=cookie)
    s = json.loads(body)
    check(code == 200 and s["kinds"] == {"coin": 5, "vcoin": 12, "voucher": 20, "vi": 2} and s["plans"] == {"hyper": 15, "endurance": 22}, f"sales: coins, coins that bought vouchers, old slips: {s['kinds']} {s['plans']}")
    today = s["days"][-1]
    check(today["coin"] == 5 and today["vcoin"] == 12 and today["vi"] == 2 and today["n"] == 3 and today["voucher"] == 0 and len(s["days"]) == 7, f"sales: the day shows both: {today}")
    check(s["vouchers"] == {"redeemed": 0, "waiting": 0}, f"sales: vouchers redeemed and waiting come from the voucher records (the one voucher is cancelled): {s['vouchers']}")
    # ---- settings: the voucher choice ----------------------------------------------------------------------------------------------------------
    code, body, _h = call("/api/settings", cookie=cookie)
    check(code == 200 and json.loads(body) == {"voucher_choice": True, "game_minutes": 10, "file_send": True, "file_send_key": True}, f"settings: the voucher choice is on, the mini-game has 10 minutes and file sending is on by default: {body}")
    check(call("/api/settings", cookie="piso_console=x")[0] == 401, "settings need a session")
    check(call("/api/settings", {"voucher_choice": "off"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "changing a setting needs the page's header")
    check(call("/api/settings", {}, cookie=cookie)[0] == 400, "a change that names nothing is refused")
    code, body, _h = call("/api/settings", {"voucher_choice": "off"}, cookie=cookie)
    check(code == 200 and json.loads(body)["voucher_choice"] is False and json.loads(call("/api/settings", cookie=cookie)[1])["voucher_choice"] is False, "the owner switches the choice off")
    check("VOUCHER_CHOICE=off" in open(f"{env.data}/settings.txt").read(), "and it is kept in the data folder")
    c9 = Cust("127.0.0.1", port=env.PP, reset=True)
    st = c9.wait_state("idle", 5)
    check(st is not None and st["vc"] is False, f"an open customer page is told at once: {st}")
    c9.close()
    call("/api/settings", {"voucher_choice": True}, cookie=cookie)
    check(json.loads(call("/api/settings", cookie=cookie)[1])["voucher_choice"] is True, "and on again")
    # the mini-game minutes a device gets per day (0 = no game), live
    check(env.get("/game")[0] == 200, "the mini-game page is served by default")
    for bad in ("abc", "-1", "121", "1.5"):
        code, body, _h = call("/api/settings", {"game_minutes": bad}, cookie=cookie)
        check(code == 400 and b"BAD_MINUTES" in body, f"refused: game_minutes {bad!r}")
    code, body, _h = call("/api/settings", {"game_minutes": "0"}, cookie=cookie)
    check(code == 200 and json.loads(body)["game_minutes"] == 0 and json.loads(body)["voucher_choice"] is True, f"the owner turns the game off: {body}")
    check("GAME_MINUTES=0" in open(f"{env.data}/settings.txt").read() and "VOUCHER_CHOICE" in open(f"{env.data}/settings.txt").read(), "it is kept next to the other console settings")
    check(env.get("/game")[0] == 404 and env.get("/game/tick")[0] == 404, "no game page and no ticks while it is off")
    c8 = Cust("127.0.0.1", port=env.PP, reset=True)
    st = c8.wait_state("idle", 5)
    check(st is not None and st["gm"] == -1, f"the coin page is told there is no game: {st and st.get('gm')}")
    c8.close()
    code, body, _h = call("/api/settings", {"game_minutes": 15}, cookie=cookie)
    check(code == 200 and json.loads(body)["game_minutes"] == 15 and env.get("/game")[0] == 200, "and 15 minutes turns it back on at once")
    c8 = Cust("127.0.0.1", port=env.PP, reset=True)
    st = c8.wait_state("idle", 5)
    check(st is not None and st["gm"] == 900, f"with all 15 minutes ahead: {st and st.get('gm')}")
    c8.close()
    call("/api/settings", {"game_minutes": 10}, cookie=cookie)
    # sending files to the owner (the print inbox): on by default when the key is installed; the owner can switch it off for sites without printing
    c7 = Cust("127.0.0.1", port=env.PP, reset=True)
    st = c7.wait_state("idle", 5)
    check(st is not None and st["sd"] is True and env.get("/send")[0] == 200, f"file sending is offered by default: {st and st.get('sd')}")
    c7.mark()
    code, body, _h = call("/api/settings", {"file_send": "off"}, cookie=cookie)
    check(code == 200 and json.loads(body)["file_send"] is False and json.loads(body)["voucher_choice"] is True and json.loads(body)["game_minutes"] == 10, f"the owner switches it off: {body}")
    check("SEND_ENABLED=off" in open(f"{env.data}/settings.txt").read(), "and it is kept next to the other console settings")
    check(c7.wait(lambda m: m.get("s") == "idle" and m.get("sd") is False, 5) is not None, "an open customer page is told at once and hides the button")
    check(env.get("/send")[0] == 404, "the upload page is gone while it is off")
    try:
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{env.PP}/send/upload", data=b"%PDF-1.4", headers={"X-Filename": "a.pdf"}, method="POST"))
        check(False, "an upload must be refused while it is off")
    except urllib.error.HTTPError as e:
        check(e.code == 404, f"and an upload is refused while it is off: {e.code}")
    code, body, _h = call("/api/settings", {"file_send": "on"}, cookie=cookie)
    check(code == 200 and json.loads(body)["file_send"] is True and env.get("/send")[0] == 200, "and on again, at once")
    c7.close()
    # prices: the owner edits the price list live (kept in the data folder, followed by the customer page at once)
    check(call("/api/pricing")[0] == 401, "prices need a session")
    code, body, _h = call("/api/pricing", cookie=cookie)
    pr = json.loads(body)
    check(code == 200 and pr["endurance"][0] == [1, 15] and pr["hyper"][0] == [5, 30] and pr["prorata"] == 6 and pr["down"] == 5000 and pr["changed"] is False,
          f"the prices start as the settings file's: {body}")
    check(call("/api/pricing", {"prorata": "9"}, cookie=cookie, headers={"X-Piso": "0"})[0] == 403, "changing prices needs the page's header")
    page0 = env.get("/")[1]
    check("<span>&#8369;5</span><span>3 hrs</span>" in page0, "the customer page shows the starting price list")
    code, body, _h = call("/api/pricing", {"hyper": "10:100 5:45", "endurance": "5:300 1:20", "prorata": "7", "down": "8000", "up": "0"}, cookie=cookie)
    pr = json.loads(body)
    check(code == 200 and pr["endurance"] == [[1, 20], [5, 300]] and pr["hyper"] == [[5, 45], [10, 100]] and pr["prorata"] == 7 and pr["down"] == 8000 and pr["up"] == 0 and pr["changed"] is True,
          f"new prices are saved and sorted: {body}")
    saved = open(f"{env.data}/settings.txt").read()
    check("ENDURANCE_TIERS=1:20 5:300" in saved and "HYPER_TIERS=5:45 10:100" in saved and "ENDURANCE_DOWN_KBPS=8000" in saved and "VOUCHER_CHOICE" in saved, "they are kept in the data folder next to the other console settings")
    page1 = env.get("/")[1]
    check("<span>&#8369;5</span><span>5 hrs</span>" in page1 and "<span>&#8369;10</span><span>1 hr 40 min</span>" in page1 and "<span>&#8369;5</span><span>3 hrs</span>" not in page1,
          "the customer page shows the new list without a restart")
    for bad, why in [({"endurance": "5:300"}, "1-peso"), ({"hyper": "5:30 5:40"}, "twice"), ({"endurance": "x"}, "pesos:minutes"), ({"endurance": "1:0"}, "minutes must be"),
                     ({"prorata": "abc"}, "not a number"), ({"down": "99999999"}, "too large"), ({"hyper": ""}, "at least one")]:
        code, body, _h = call("/api/pricing", bad, cookie=cookie)
        check(code == 400 and b"BAD_PRICES" in body and why in body.decode(), f"refused: {bad} ({body.decode()[:80]})")
    check(json.loads(call("/api/pricing", cookie=cookie)[1])["endurance"] == [[1, 20], [5, 300]], "a refused change leaves the prices as they were")
    code, body, _h = call("/api/pricing", {"reset": "1"}, cookie=cookie)
    pr = json.loads(body)
    check(code == 200 and pr["changed"] is False and pr["endurance"][0] == [1, 15] and "ENDURANCE_TIERS" not in open(f"{env.data}/settings.txt").read(), "going back forgets the console prices")
    check("<span>&#8369;5</span><span>3 hrs</span>" in env.get("/")[1], "and the customer page follows")
    ui = call("/ui.js")[1].decode()
    page = call("/")[1].decode()
    check("printBatch" not in ui and "vouchers/new" not in ui and "@media print" not in ui and "Print" not in page and "Make codes" not in page, "the console has no batch forms and no slip printing")
    check('data-t="set"' in page and "Voucher choice" in page and "Get a voucher code" in page, "the console has a Settings page with the voucher choice")
    code, _b, _h = call("/api/logout", {}, cookie=cookie)
    check(code == 200 and call("/api/overview", cookie=cookie)[0] == 401, "sign out ends the session")
finally:
    env.close()
    stub.shutdown()
finish()
