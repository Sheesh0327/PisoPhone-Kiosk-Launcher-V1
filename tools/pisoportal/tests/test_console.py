#!/usr/bin/env python3
"""The owner's console: sign-in with the router password, lock-out, the overview, the customer list with speeds, the rental phones (and the coin box's own page passed through one port up), kicking a device."""
import http.server, json, os, threading, time, urllib.error, urllib.request
from harness import *

HERE = os.path.dirname(os.path.abspath(__file__))
CP = 18290
env = Env(offset=40)
open(f"{env.data}/send.key", "w").write("k" * 40 + "\n")   # the print server's key is installed on this router
# feature 034: a router that had the mini-game setting keeps the owner's line; an unknown key is harmless and not ours to delete
OLD_SETTINGS = "GAME_MINUTES=10\nGAME_LIMIT_SECONDS=6\n"
open(f"{env.data}/settings.txt", "w").write(OLD_SETTINGS)
tmp = env.tmp
# a fake `ubus`: the password "router-pass" is the router's
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/leases", "w").write("1791858999 aa:bb:cc:00:00:01 10.0.30.1 Rowels-iPhone 01:aa\n")
open(f"{tmp}/route", "w").write("Iface\tDestination\tGateway\tFlags\neth9\t00000000\t0101A8C0\t0003\n")
open(f"{tmp}/dev", "w").write(" face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n  eth9: 1000 1 0 0 0 0 0 0 500 1 0 0 0 0 0 0\n")
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")


seen = {}   # what the coin box last received from the router


class Stub(http.server.BaseHTTPRequestHandler):   # the coin box: a nonce for the gateway, the dashboard's status with a password, a page
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        seen.update(path=self.path, auth=self.headers.get("Authorization"), origin=self.headers.get("Origin"), cookie=self.headers.get("Cookie"), body=self.rfile.read(n), host=self.headers.get("Host"))
        body = b"saved"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/api/gateway/challenge"):
            body = b'{"nonce":"00112233445566778899aabbccddeeff"}'
        elif self.path == "/api/status":
            import base64
            if self.headers.get("Authorization") != "Basic " + base64.b64encode(b"admin:box-pass-123").decode():
                self.send_response(401); self.end_headers(); return
            body = json.dumps({"devices": [{"slotNum": 1, "isBound": True, "online": True, "name": "Terminal 1", "ip": "10.0.0.51", "time": 600, "battery": 80, "charging": False},
                                           {"slotNum": 2, "isBound": False}]}).encode()
        elif self.path.startswith("/?") or self.path == "/":
            seen.update(path=self.path, auth=self.headers.get("Authorization"), origin=self.headers.get("Origin"), cookie=self.headers.get("Cookie"), host=self.headers.get("Host"))
            body = b"<html><title>Coin box</title></html>"
            self.send_response(200); self.send_header("Content-Length", str(len(body))); self.send_header("X-Frame-Options", "DENY"); self.send_header("Content-Security-Policy", "default-src 'none'"); self.send_header("Set-Cookie", "box_session=1; Path=/"); self.end_headers(); self.wfile.write(body); return
        else:
            body = b"{}"
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)


stub = http.server.ThreadingHTTPServer(("127.0.0.1", CP + 10), Stub)
threading.Thread(target=stub.serve_forever, daemon=True).start()
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", DHCP_LEASES=f"{tmp}/leases", ROUTE_FILE=f"{tmp}/route", NETDEV_FILE=f"{tmp}/dev",
                 PISO_SETUP_CONF=f"{tmp}/setup.conf", GW_BOX=f"127.0.0.1:{CP + 10}")


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
    check(open(f"{env.data}/settings.txt", "rb").read() == OLD_SETTINGS.encode(), "034: starting the new program leaves the owner's settings.txt byte-identical (criterion 11)")
    check(wait_until(lambda: call("/")[0] == 200, 5), "the console page is served")
    code, body, h = call("/")
    check(b"TechNet PisoHub" in body and "default-src 'none'" in h.get("Content-Security-Policy", ""), "the page is the TechNet console, with a strict content security policy")
    check(len(body) < 31000, f"the page is small ({len(body)} bytes)")  # (20000 until the Branding screen, feature 049; 24000 until the sidebar and the icons, feature 053; 30000 until the Smooth line card, feature 056)
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
    check(j["ledger_kept"] == 1 and j["ledger_pruned"] == 0 and j["ledger_wanted"] == 3, f"overview: one month of sales detail kept, none deleted, 3 wanted: {j}")
    check(j.get("alerts") == [], f"overview: no alerts file, no alerts: {j.get('alerts')}")
    open(f"{env.data}/alerts.txt", "w").write("2026-10-24 09:00 the coin box has not answered for 5 minutes\n2026-10-24 10:00 the portal program did not answer \"3\" minutes\n")
    j = json.loads(call("/api/overview", cookie=cookie)[1])
    check(j["alerts"] == ['2026-10-24 10:00 the portal program did not answer "3" minutes', "2026-10-24 09:00 the coin box has not answered for 5 minutes"], f"AO-06: the console front page carries alerts.txt, newest first: {j['alerts']}")
    check("j.alerts" in call("/ui.js")[1].decode(), "and the page script draws them")
    os.remove(f"{env.data}/alerts.txt")
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
    # ---- the coin box's own page, passed through one port above the console (the Rental phones tab shows it) ----------------------------------------
    PP = CP + 1
    def via(path, method="GET", body=None, cookie=None, headers=None):
        req = urllib.request.Request(f"http://127.0.0.1:{PP}{path}", data=body, method=method)
        if cookie:
            req.add_header("Cookie", cookie)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers
    check(via("/")[0] == 401 and via("/save", "POST", b"a=1")[0] == 401 and via("/", cookie="piso_console=nope")[0] == 401, "box page: nobody without a console session gets it")
    code, page, h = call("/")
    check(f"frame-src http://127.0.0.1:{PP}" in h.get("Content-Security-Policy", "") and "frame-ancestors 'none'" in h.get("Content-Security-Policy", ""), "the console page may show the box page's address in a frame, nothing else: " + h.get("Content-Security-Policy", ""))
    check(b'id="boxf"' in page and b"/api/phones" not in page, "the Rental phones tab is the box's page in a frame")
    seen.clear()
    code, page, h = via("/?embed=1&theme=dark", cookie=cookie + "; box_other=7")
    check(code == 200 and b"Coin box" in page and seen.get("path") == "/?embed=1&theme=dark", f"box page: the page and its query come through: {code} {seen}")
    import base64
    check(seen.get("auth") == "Basic " + base64.b64encode(b"admin:box-pass-123").decode(), "box page: the router adds the box's admin login from its stored password")
    check(seen.get("cookie") == "box_other=7" and "piso_console" not in str(seen), "box page: the console's session cookie is not given to the box: " + str(seen.get("cookie")))
    check(h.get("X-Frame-Options") is None and f"frame-ancestors http://127.0.0.1:{CP}" in h.get("Content-Security-Policy", "") and "default-src" not in h.get("Content-Security-Policy", ""),
          "box page: the box's framing refusal is replaced by 'only the console may frame it': " + str(dict(h)))
    check("box_session=1" in (h.get("Set-Cookie") or ""), "box page: the box's own cookie goes on to the browser")
    seen.clear()
    code, out, _h = via("/save", "POST", b"a=1&b=2", cookie=cookie, headers={"Content-Type": "application/x-www-form-urlencoded", "Origin": f"http://127.0.0.1:{PP}"})
    check(code == 200 and out == b"saved" and seen.get("body") == b"a=1&b=2" and seen.get("origin") == f"http://127.0.0.1:{CP + 10}" and seen.get("host") == f"127.0.0.1:{CP + 10}",
          f"box page: a form post goes through with the box's own address as origin: {code} {seen}")
    seen.clear()
    code, out, _h = via("/save", "POST", b"a=1", cookie=cookie, headers={"Origin": "http://evil.test"})
    check(code == 403 and not seen, "box page: a post from another page is refused and never reaches the box")
    open(f"{tmp}/setup.conf", "w").write("")
    check(via("/", cookie=cookie)[0] == 503, "box page: without the stored box password it says so (503)")
    open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
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
    # ---- feature 033: the spinning wheel is gone from the console ---------------------------------------------------------------------------
    check(call("/api/wheel", cookie=cookie)[0] == 404 and call("/api/wheel", {"off": "1"}, cookie=cookie)[0] == 404, "033: /api/wheel is gone (404, signed in)")
    check(b"wheel" not in call("/ui.js")[1].lower() and b"wheel" not in call("/")[1].lower(), "033: the console page and its script have no wheel panel")
    # ---- settings: the voucher choice ----------------------------------------------------------------------------------------------------------
    code, body, _h = call("/api/settings", cookie=cookie)
    settings_now = json.loads(body)
    sqm_block = settings_now.pop("sqm", None)   # (feature 056: the smooth line block has its own test, test_console_sqm.py)
    check(code == 200 and settings_now == {"voucher_choice": True, "file_send": True, "file_send_key": True, "ledger_max_kb": 1024, "ledger_keep_months": 3} and isinstance(sqm_block, dict), f"settings: the voucher choice is on, the mini-game has 10 minutes, file sending is on and the revenue ledger has 1024 KB for 3 wanted months by default: {body}")
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
    # feature 032: the revenue ledger's size budget (0 = never delete old months) and the months the owner would like to keep
    for bad in ("abc", "-1", "100", "255", "16385", "1.5"):
        code, body, _h = call("/api/settings", {"ledger_max_kb": bad}, cookie=cookie)
        check(code == 400 and b"BAD_LEDGER_KB" in body, f"refused: ledger_max_kb {bad!r}")
    for bad in ("abc", "0", "-1", "61", "2.5"):
        code, body, _h = call("/api/settings", {"ledger_keep_months": bad}, cookie=cookie)
        check(code == 400 and b"BAD_MONTHS" in body, f"refused: ledger_keep_months {bad!r}")
    code, body, _h = call("/api/settings", {"ledger_max_kb": "2048", "ledger_keep_months": "6"}, cookie=cookie)
    check(code == 200 and json.loads(body)["ledger_max_kb"] == 2048 and json.loads(body)["ledger_keep_months"] == 6, f"the owner sets 2048 KB and 6 months: {body}")
    check("LEDGER_MAX_KB=2048" in open(f"{env.data}/settings.txt").read() and "LEDGER_KEEP_MONTHS=6" in open(f"{env.data}/settings.txt").read(), "and both are kept in the data folder")
    code, body, _h = call("/api/settings", {"ledger_max_kb": "0"}, cookie=cookie)
    check(code == 200 and json.loads(body)["ledger_max_kb"] == 0, "0 turns the pruning off and is accepted")
    call("/api/settings", {"ledger_max_kb": "1024", "ledger_keep_months": "3"}, cookie=cookie)
    check(json.loads(call("/api/settings", cookie=cookie)[1])["ledger_max_kb"] == 1024, "and back to the default")
    # feature 034: the mini-game is gone. Its setting is no setting any more; the owner's old line stays where it is, unread
    check("game_minutes" not in json.loads(call("/api/settings", cookie=cookie)[1]), "034: the console reports no mini-game setting")
    code, body, _h = call("/api/settings", {"game_minutes": "15"}, cookie=cookie)
    check(code == 400 and b"NOTHING" in body, f"034: a request for the old setting changes nothing and is told so: {code} {body}")
    check(env.get("/game")[1] == env.get("/")[1], "034: /game is the ordinary customer page (the portal answers every unknown address that way)")
    st = open(f"{env.data}/settings.txt").read()
    check("GAME_MINUTES=10\n" in st and "GAME_MINUTES=15" not in st and "GAME_LIMIT_SECONDS=6\n" in st, f"034: the owner's old lines are still there after other settings were saved (criterion 11): {st!r}")
    # sending files to the owner (the print inbox): on by default when the key is installed; the owner can switch it off for sites without printing
    c7 = Cust("127.0.0.1", port=env.PP, reset=True)
    st = c7.wait_state("idle", 5)
    check(st is not None and st["sd"] is True and env.get("/send")[0] == 200, f"file sending is offered by default: {st and st.get('sd')}")
    c7.mark()
    code, body, _h = call("/api/settings", {"file_send": "off"}, cookie=cookie)
    check(code == 200 and json.loads(body)["file_send"] is False and json.loads(body)["voucher_choice"] is True, f"the owner switches it off: {body}")
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
    # ---- AO-09: 200 idle sockets from one guest do not starve anyone else, and do not pile up threads ---------------------------------
    import http.client, socket

    def threads():
        return len(os.listdir(f"/proc/{env.portal.pid}/task"))

    def console_from(ip, port=CP):
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=5, source_address=(ip, 0))
        c.request("GET", "/")
        return c.getresponse().status

    before = threads()
    idle = []
    for port in (CP, CP + 1):
        for _ in range(100):
            try:
                idle.append(socket.create_connection(("127.0.0.1", port), timeout=2))
            except OSError:
                break
    time.sleep(0.5)
    check(threads() - before <= 40, f"200 idle sockets on the console and the coin box page start few threads ({threads() - before} more)")
    check(env.get("/ping")[1] == "ok" and env.get("/")[0] == 200, "the customers' coin page still answers")
    check(console_from("127.0.0.2") == 200, "another address still gets the console")
    check(100 <= console_from("127.0.0.2", CP + 1) < 600, "and the coin box page answers (with whatever the stub box says)")
    for x in idle:
        x.close()
    check(wait_until(lambda: console_from("127.0.0.1") == 200, 15), "when the idle sockets go the first address is served again")

    code, _b, _h = call("/api/logout", {}, cookie=cookie)
    check(code == 200 and call("/api/overview", cookie=cookie)[0] == 401, "sign out ends the session")
finally:
    env.close()
    stub.shutdown()
finish()
