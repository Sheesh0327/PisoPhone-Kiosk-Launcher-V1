"""End-to-end test of the OpenNDS coin-slot integration without a router or an ESP32.

Real pieces under test: theme_coinslot.sh (run the way libopennds.sh runs it, helpers stubbed) and
coinslot-listener.sh (handler, worker, fair use, vouchers, revenue). Stand-ins: fakebox.py (the ESP32 gateway API
with the same HMAC/nonce rules), fake_ndsctl.sh (openNDS' ndsctl) and fake_socat.py (socat).
Run with:  python3 opennds/tests/test_flow.py"""
import glob, hashlib, socket, json, os, re, shutil, signal, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
THEME, LISTENER = f"{ROOT}/theme_coinslot.sh", f"{ROOT}/coinslot-listener.sh"
KEY = "test-gateway-key-123456"
QUEUE_CLAIM = 5
BOX_PORT, LISTEN_PORT, STREAM_PORT = 18090, 18099, 18110
tmp = tempfile.mkdtemp()
CTL, LOG, STATE, DATA, NDS = f"{tmp}/ctl.json", f"{tmp}/box.log", f"{tmp}/state", f"{tmp}/data", f"{tmp}/nds"
os.makedirs(NDS)
open(LOG, "w").close()
failures = checks = 0
MAC_A, MAC_B, MAC_C = "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:03"


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def set_box(**kw):
    json.dump(kw, open(CTL, "w"))


def nds_client(mac, **kw):
    """Create/overwrite a fake openNDS client."""
    d = dict(STATE="Preauthenticated", SESSION_END=0, DL=0, UL=0, UPRATE=0, DOWNRATE=0)
    d.update(kw)
    open(f"{NDS}/{mac.replace(':', '')}", "w").write("".join(f"{k}={v}\n" for k, v in d.items()))


def nds_get(mac):
    out = {}
    for line in open(f"{NDS}/{mac.replace(':', '')}"):
        k, v = line.strip().split("=", 1)
        out[k] = v
    return out


def calls():
    return open(f"{NDS}/calls.log").read().split("\n") if os.path.exists(f"{NDS}/calls.log") else []


conf = f"{tmp}/coinslot.conf"
open(conf, "w").write(
    f"GW_BOX=127.0.0.1:{BOX_PORT}\nGW_KEY={KEY}\nSTATE_DIR={STATE}\nDATA_DIR={DATA}\nNDSCTL={HERE}/fake_ndsctl.sh\n"
    f"LISTEN_PORT={LISTEN_PORT}\nCOIN_FIRST_WAIT_SECONDS=4\nCOIN_IDLE_WAIT_SECONDS=3\nCOIN_MAX_SECONDS=12\nQUEUE_CLAIM_SECONDS=5\nSTREAM_PORT={STREAM_PORT}\n"
    "FAIR_USE_KB=1000\nFAIR_THROTTLE_DOWN_KBPS=2000\nFAIR_THROTTLE_UP_KBPS=1000\nFAIR_THROTTLE_MINUTES=5\nFAIR_FULL_MINUTES=2\n")
env = dict(os.environ, FAKEBOX_CTL=CTL, FAKEBOX_LOG=LOG, FAKEBOX_KEY=KEY, COINSLOT_CONF=conf, FAKE_NDS_DIR=NDS,
           NDSCTL=f"{HERE}/fake_ndsctl.sh")
ARP = f"{tmp}/arp"
open(ARP, "w").write(f"IP address       HW type     Flags       HW address            Mask     Device\n127.0.0.1  0x1  0x2  {MAC_A}  *  lo\n")
env["ARP_FILE"] = ARP
set_box(busy=False, coins_at=[])
procs = [subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(STREAM_PORT), LISTENER, "stream-handle"], env=env),
         subprocess.Popen([sys.executable, f"{HERE}/fakebox.py", str(BOX_PORT)], env=env),
         subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(LISTEN_PORT), LISTENER], env=env)]
time.sleep(1.0)


def page(hid, mac, coinact="", plan="", landing="", port=LISTEN_PORT, auth_ok="1", vcode="", statusvar="", terms="", forfeit=""):
    e = dict(env, HID=hid, MAC=mac, COINACT=coinact, COINPLAN=plan, LANDING=landing, PORT=str(port), AUTH_OK=auth_ok,
             VCODE=vcode, STATUSVAR=statusvar, TERMS=terms, FORFEIT=forfeit)
    return subprocess.run(["bash", f"{HERE}/theme_harness.sh", THEME], env=e, capture_output=True, text=True, timeout=90).stdout


class Stream:
    """A Server-Sent Events client: collects (seconds since open, event, data) in the background."""
    def __init__(self, sid, mode):
        import socket, threading
        self.t0, self.events, self.head = time.time(), [], b""
        self.sock = socket.create_connection(("127.0.0.1", STREAM_PORT), timeout=30)
        self.sock.sendall(f"GET /stream?sid={sid}&mode={mode} HTTP/1.1\r\nHost: x\r\n\r\n".encode())
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        buf = b""
        try:
            while True:
                d = self.sock.recv(4096)
                if not d:
                    break
                buf += d
                if not self.head and b"\r\n\r\n" in buf:
                    self.head, buf = buf.split(b"\r\n\r\n", 1)
                while b"\n\n" in buf and self.head:
                    block, buf = buf.split(b"\n\n", 1)
                    ev = dict(l.split(": ", 1) for l in block.decode().split("\n") if ": " in l and not l.startswith(":"))
                    if "event" in ev:
                        self.events.append((time.time() - self.t0, ev["event"], ev.get("data", "")))
        except OSError:
            pass

    def wait(self, event, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.events:
                if e[1] == event:
                    return e
            time.sleep(0.05)
        return None

    def close(self):
        self.sock.close()


class WS:
    """A minimal WebSocket client (masked text frames) for the MicroPython edition's /ws endpoint."""
    GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    def __init__(self, sid, mac, origin=None, key="dGhlIHNhbXBsZSBub25jZQ==", host=None):
        import base64
        self.sock = socket.create_connection(("127.0.0.1", STREAM_PORT), timeout=10)
        req = f"GET /ws?sid={sid}&mac={mac} HTTP/1.1\r\nHost: {host or '127.0.0.1:%d' % STREAM_PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        if key:
            req += f"Sec-WebSocket-Key: {key}\r\n"
        req += "Sec-WebSocket-Version: 13\r\n" + (f"Origin: {origin}\r\n" if origin else "") + "\r\n"
        self.sock.sendall(req.encode())
        self.head = b""
        while b"\r\n\r\n" not in self.head:
            d = self.sock.recv(4096)
            if not d:
                break
            self.head += d
        self.head, _, self.buf = self.head.partition(b"\r\n\r\n")
        self.accept_ok = key and base64.b64encode(hashlib.sha1((key + self.GUID).encode()).digest()) in self.head

    def send(self, obj_or_bytes, masked=True, op=1):
        b = obj_or_bytes if isinstance(obj_or_bytes, bytes) else json.dumps(obj_or_bytes).encode()
        m = os.urandom(4)
        n = len(b)
        hdr = bytes([0x80 | op, (0x80 if masked else 0) | (n if n < 126 else 126)]) + (n.to_bytes(2, "big") if n >= 126 else b"")
        self.sock.sendall(hdr + (m + bytes(c ^ m[i % 4] for i, c in enumerate(b)) if masked else b))

    def _need(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(4096)
            if not d:
                raise EOFError
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    TIMEOUT = {"e": "__timeout__"}  # recv() result when nothing arrived in time (the connection is still open)

    def recv(self, timeout=5):
        """Next text message as a dict (pings skipped); None only when the connection closed (EOF or a close frame);
        WS.TIMEOUT when nothing arrived in time."""
        self.sock.settimeout(timeout)
        try:
            while True:
                h = self._need(2)
                op, n = h[0] & 15, h[1] & 127
                if n == 126:
                    n = int.from_bytes(self._need(2), "big")
                data = self._need(n) if n else b""
                if op == 8:
                    return None
                if op == 1:
                    return json.loads(data)
        except socket.timeout:
            return self.TIMEOUT
        except (EOFError, OSError):
            return None

    def wait(self, event, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            m = self.recv(max(0.2, end - time.time()))
            if m is None:
                return None
            if m.get("e") == event:
                return m
        return None

    def closed(self, timeout=3):
        """True once the server has closed the connection (pushed messages that arrive first are skipped)."""
        end = time.time() + timeout
        while time.time() < end:
            if self.recv(max(0.2, end - time.time())) is None:
                return True
        return False

    def close(self):
        self.sock.close()


def listener(*args):
    return subprocess.run(["sh", LISTENER, *args], env=env, capture_output=True, text=True, timeout=60).stdout.strip()


def sid_of(hid):
    return hashlib.sha256(hid.encode()).hexdigest()[:32]


def get(path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{LISTEN_PORT}{path}", timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def pay(hid, mac, plan, n_coins, coin_times=None):
    """Drive the customer pages: start, wait, finish. Returns the result page."""
    set_box(busy=False, coins_at=coin_times if coin_times is not None else [0.5] * n_coins)
    p = page(hid, mac, "start", plan)
    time.sleep(1.6)
    page(hid, mac, "wait", plan)
    p = page(hid, mac, "finish", plan)
    for _ in range(25):
        if "counting" not in p.lower():
            break
        time.sleep(1)
        p = page(hid, mac, "finish", plan)
    return p


def code_from(p):
    m = re.search(r'class="code">([A-Z2-9]{8})<', p)
    return m.group(1) if m else None


try:
    # ---- rates engine -------------------------------------------------------------------------------------------
    rates = {("hyper", 5): 30, ("hyper", 10): 60, ("hyper", 20): 120, ("hyper", 3): 18, ("hyper", 17): 102,
             ("endurance", 1): 15, ("endurance", 2): 30, ("endurance", 5): 180, ("endurance", 10): 480,
             ("endurance", 20): 1440, ("endurance", 17): 690, ("endurance", 25): 1620, ("endurance", 30): 1920}
    for (plan, pesos), minutes in rates.items():
        check(listener("minutes", plan, str(pesos)) == str(minutes), f"{plan} {pesos} pesos = {minutes} min")

    # ---- welcome page ---------------------------------------------------------------------------------------------
    nds_client(MAC_A)
    p = page("hidA", MAC_A)
    check("HyperSpeed" in p and "Endurance" in p and "Insert Coin" in p, "welcome shows both plans and Insert Coin")
    for needle in ["&#8369;5</span><span>30 min", "&#8369;10</span><span>1 hr", "&#8369;20</span><span>2 hrs",
                   "&#8369;1</span><span>15 min", "&#8369;5</span><span>3 hrs", "&#8369;10</span><span>8 hrs", "&#8369;20</span><span>24 hrs"]:
        check(needle in p, f"rate row present: {needle}")
    check("up to 5 Mbps down / 2 Mbps up" in p.lower() or "Up to 5 Mbps down / 2 Mbps up" in p, "Endurance speed caps shown")
    check('value="hyper" checked' in p, "HyperSpeed preselected")
    check("refresh" not in p.lower(), "welcome does not auto-refresh")
    check(len(p) < 8300, f"welcome page is small ({len(p)} bytes)")
    check("Please wait" in p and "__t" in p, "every form shows it was tapped and ignores a second tap while loading")
    check("function instant()" in p and "Do not insert coins yet" in p and 'id="lleft"' not in p,
          "Insert Coin shows a waiting screen at once that does not invite coins before the slot is armed")
    # the real waiting page: no countdown and no "insert now" until the box has armed the slot
    sw = sid_of("hidW")
    os.makedirs(f"{STATE}/{sw}", exist_ok=True)
    open(f"{STATE}/{sw}/plan", "w").write("hyper")
    open(f"{STATE}/{sw}/state", "w").write("STATE=starting\nPULSES=0\nREMAINING=30\nERROR=\n")
    p = page("hidW", MAC_A, "wait", "hyper")
    check("Getting the coin slot ready" in p and 'id="cd" style="display:none"' in p and "Insert coin(s) now" not in p.split('id="wait"')[1].split("<script>")[0],
          "waiting page says 'getting ready' and hides the countdown while the slot is still arming")
    sw2 = sid_of("hidW2")
    os.makedirs(f"{STATE}/{sw2}", exist_ok=True)
    open(f"{STATE}/{sw2}/plan", "w").write("hyper")
    open(f"{STATE}/{sw2}/state", "w").write("STATE=armed\nPULSES=0\nREMAINING=30\nERROR=\n")
    p = page("hidW2", MAC_A, "wait", "hyper")
    check("Insert coin(s) now" in p.split('id="wait"')[1].split("<script>")[0] and 'id="cd" style="display:none"' not in p,
          "waiting page invites coins and shows the countdown once the slot is armed")
    shutil.rmtree(f"{STATE}/{sw}")
    shutil.rmtree(f"{STATE}/{sw2}")
    # ---- Endurance: 17 pesos accumulate to 11 hrs 30 min -----------------------------------------------------------
    p = pay("hidA", MAC_A, "endurance", 17)
    check("11 hrs 30 min" in p and "&#8369;17" in p, "result: P17 Endurance = 11 hrs 30 min")
    check("AUTHCALL" not in p, "no access before Connect")
    p = page("hidA", MAC_A, "connect", "endurance", landing="yes")
    check("AUTHCALL sessiontimeout=690 quotas=690 2000 5000 0 0" in p, "Endurance grant carries 2 Mbit/s up / 5 Mbit/s down (bursting is openNDS' own): " + p[-400:])
    code = code_from(p)
    check(code is not None, "voucher code shown after paying")
    check(any(l.startswith("ack ") and sid_of("hidA") in l for l in open(LOG).read().split("\n")), "coins acknowledged on the box after access")
    check("endurance,17,690,new" in open(f"{DATA}/revenue.csv").read(), "revenue logged")
    check(nds_get(MAC_A)["STATE"] == "Authenticated", "client is authenticated")
    check(nds_get(MAC_A)["UPRATE"] == "2000" and nds_get(MAC_A)["DOWNRATE"] == "5000", "client holds the Endurance caps")

    # ---- other customers cannot take these coins ------------------------------------------------------------------
    set_box(busy=False, coins_at=[0.5] * 2)
    page("hidX", MAC_B, "start", "hyper"); time.sleep(1.5)
    page("hidX", MAC_B, "finish", "hyper"); time.sleep(2)
    p = page("hidY", MAC_B, "connect", "hyper", landing="yes")
    check("AUTHCALL" not in p and "could not start your session" in p, "a different client id cannot claim someone else's coins")
    nds_client(MAC_B)

    # ---- status page and top-up ------------------------------------------------------------------------------------
    p = page("hidA", MAC_A, statusvar="authenticated")
    check("You are connected" in p and "11 hr" in p and code in p and "Add time" in p, "status page: time left, voucher, top-up button")
    p = page("hidA2", MAC_A, "start", "hyper", statusvar="authenticated")
    check("still have Endurance time" in p and "Switch to HyperSpeed" in p and "forfeited" in p, "other plan: warned, with add-time and switch options")
    p = pay("hidA3", MAC_A, "endurance", 2)
    check("&#8369;2 = 30 min + 11 hrs" in p and "Add time" in p, "top-up result keeps the remaining time: " + re.sub(r"\s+", " ", p)[-500:])
    p = page("hidA3", MAC_A, "connect", "endurance", landing="yes", statusvar="authenticated")
    check("Connected" in p and code_from(p) == code, "top-up keeps the same voucher code")
    check(any(re.match(r"auth aa:bb:cc:00:00:01 72[01] 2000 5000 0 0", c) for c in calls()), "top-up re-granted ~12 hr with Endurance caps: " + str([c for c in calls() if c.startswith("auth")]))
    check("endurance,2,30,topup" in open(f"{DATA}/revenue.csv").read(), "top-up logged as revenue")

    # ---- voucher on another device, time moves ---------------------------------------------------------------------
    nds_client(MAC_B)
    p = page("hidB", MAC_B, "vform"); check("voucher code" in p.lower(), "voucher form")
    p = page("hidB", MAC_B, "voucher", vcode="ZZZZZZZZ")
    check("not accepted" in p, "wrong code rejected")
    p = page("hidB", MAC_B, "voucher", vcode=code)
    check("Time left on your voucher" in p, "valid code accepted: " + re.sub(r"\s+", " ", p)[-300:])
    p = page("hidB", MAC_B, "connect", "endurance", landing="yes")
    check("AUTHCALL sessiontimeout=7" in p, "voucher grants the remaining ~12 hr (" + (re.search(r"sessiontimeout=\d+", p) or [""])[0] + ")")
    check(any(c == "deauth aa:bb:cc:00:00:01" for c in calls()), "the old device is disconnected (time moved)")

    # ---- a disconnected device with paid time is welcomed back ------------------------------------------------------
    nds_client(MAC_B)  # B drops off
    p = page("hidB", MAC_B)
    check("Time left on your voucher" in p, "returning device is offered its remaining time")
    p = page("hidB", MAC_B, "connect", "endurance", landing="yes")
    check("quotas=" in p and re.search(r"quotas=\d+ 2000 5000 0 0", p), "reconnecting keeps the Endurance caps: " + (re.search(r"quotas=[^-]*", p) or [""])[0])

    # ---- pause once (Endurance, 10+ pesos paid) ------------------------------------------------------------------------
    p = page("hidB", MAC_B, statusvar="authenticated")
    check("Pause my time (once)" in p, "pause offered for a 10+ peso Endurance session")
    p = page("hidB", MAC_B, "pausecheck", statusvar="authenticated")
    check("only <b>once</b>" in p and "Yes, pause my time" in p, "pause asks for confirmation")
    p = page("hidB", MAC_B, "pause", statusvar="authenticated")
    check("Time paused" in p, "pause done: " + re.sub(r"\s+", " ", p)[-300:])
    check(any(c == "deauth aa:bb:cc:00:00:02" for c in calls()), "the device is disconnected while paused")
    v = open(f"{DATA}/vouchers/{code}").read()
    check("PAUSED=1" in v and "PAUSE_USED=1" in v, "voucher records the pause")
    nds_client(MAC_B)
    p = page("hidB", MAC_B)
    check("Your paused time is ready" in p and "Resume" in p, "paused time offered for resume")
    p = page("hidB2", MAC_B, "start", "hyper")
    check("still have Endurance time" in p and "Switch to HyperSpeed" in p, "paused time also warns before switching")
    p = page("hidB", MAC_B, "connect", "endurance", landing="yes")
    check(re.search(r"quotas=7[12]\d 2000 5000 0 0", p) is not None, "resume restores the frozen time with Endurance caps: " + (re.search(r"quotas=[^-]*", p) or [""])[0])
    p = page("hidB", MAC_B, statusvar="authenticated")
    check("Pause my time" not in p, "the pause is offered only once")
    p = page("hidB", MAC_B, "pause", statusvar="authenticated")
    check("already used your one pause" in p, "a second pause is refused")

    # ---- HyperSpeed: no caps, then fair use -------------------------------------------------------------------------
    nds_client(MAC_C)
    p = pay("hidC", MAC_C, "hyper", 10)
    check("1 hr" in p, "Hyper 10 pesos = 1 hr")
    p = page("hidC", MAC_C, "connect", "hyper", landing="yes")
    check("AUTHCALL sessiontimeout=60 quotas=60 0 0 0 0" in p, "HyperSpeed has no speed caps")
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check(any(re.match(r"auth aa:bb:cc:00:00:03 5\d 1000 2000 0 0", c) for c in calls()), "over the limit: throttled (1 Mbps up / 2 Mbps down): " + str([c for c in calls() if "00:03" in c]))
    fair = open(f"{STATE}/fair/aabbcc000003").read()
    check("PHASE=throttled" in fair, "fair-use phase recorded")
    f = open(f"{STATE}/fair/aabbcc000003").read().replace("PHASE_SINCE=" + re.search(r"PHASE_SINCE=(\d+)", fair).group(1), f"PHASE_SINCE={int(time.time()) - 400}")
    open(f"{STATE}/fair/aabbcc000003", "w").write(f)
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check("PHASE=normal" in open(f"{STATE}/fair/aabbcc000003").read(), "after 5 minutes the throttle lifts (intermittent)")
    f = open(f"{STATE}/fair/aabbcc000003").read()
    open(f"{STATE}/fair/aabbcc000003", "w").write(re.sub(r"PHASE_SINCE=\d+", f"PHASE_SINCE={int(time.time()) - 200}", f))
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check("PHASE=throttled" in open(f"{STATE}/fair/aabbcc000003").read(), "and it throttles again after the 2 minute break")
    me = json.loads(get(f"/me?mac={MAC_C}")[1])
    check(me["plan"] == "hyper" and me["throttled"] is True, "status reports the fair-use slowdown")

    # ---- Endurance is never fair-use throttled -------------------------------------------------------------------------
    before = len(calls())
    nds_client(MAC_B, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=9999999, UL=9999999, UPRATE=2000, DOWNRATE=5000)
    listener("fairuse-once")
    check(not any("00:02" in c and c.startswith("auth") for c in calls()[before:]), "Endurance clients are not fair-use throttled")

    # ---- switching plan by forfeiting the remaining time ---------------------------------------------------------------
    nds_client(MAC_B, STATE="Authenticated", SESSION_END=int(time.time()) + 40000, UPRATE=2000, DOWNRATE=5000)
    old_code = re.search(r"PLAN=endurance", open(f"{DATA}/vouchers/{code}").read()) and code
    # cancelling costs nothing: no coins -> the Endurance voucher stays
    set_box(busy=False, coins_at=[])
    page("hidS0", MAC_B, "start", "hyper", forfeit="yes"); time.sleep(1.5)
    for _ in range(25):
        p = page("hidS0", MAC_B, "finish", "hyper", forfeit="yes")
        if "No coins detected" in p: break
        time.sleep(1)
    check("No coins detected" in p and os.path.exists(f"{DATA}/vouchers/{code}"), "tapping switch and paying nothing forfeits nothing")
    set_box(busy=False, coins_at=[0.5] * 10)
    page("hidS", MAC_B, "start", "hyper", forfeit="yes"); time.sleep(1.6)
    p = page("hidS", MAC_B, "finish", "hyper", forfeit="yes")
    for _ in range(25):
        if "Thank you" in p: break
        time.sleep(1); p = page("hidS", MAC_B, "finish", "hyper", forfeit="yes")
    check("1 hr" in p and "replaced by this one" in p and "1 hr 12" not in p, "switch result: only the new plan's time (the old time is forfeited): " + re.sub(r"\s+", " ", p)[-400:])
    check(os.path.exists(f"{DATA}/vouchers/{code}"), "old voucher still exists until the switch is paid and applied")
    p = page("hidS", MAC_B, "connect", "hyper", landing="yes", statusvar="authenticated")
    new_code = code_from(p)
    check(new_code and new_code != code, "switching issues a new voucher code")
    check(not os.path.exists(f"{DATA}/vouchers/{code}"), "the old plan's voucher is deleted")
    check(any(re.match(r"auth aa:bb:cc:00:00:02 60 0 0 0 0", c) for c in calls()), "re-granted with exactly the new plan's time and no caps: " + str([c for c in calls() if c.startswith("auth") and "00:02" in c][-2:]))
    check("hyper,10,60,switch" in open(f"{DATA}/revenue.csv").read(), "switch logged")

    # ---- small Endurance purchases cannot pause ------------------------------------------------------------------------
    MAC_D = "aa:bb:cc:00:00:04"
    nds_client(MAC_D)
    pay("hidD1", MAC_D, "endurance", 5)
    page("hidD1", MAC_D, "connect", "endurance", landing="yes")
    p = page("hidD1", MAC_D, statusvar="authenticated")
    check("Pause my time" not in p, "no pause for Endurance purchases under 10 pesos")
    p = page("hidD1", MAC_D, "pause", statusvar="authenticated")
    check("only available" in p, "pause refused for a 5 peso Endurance session")

    # ---- zero coins, busy slot, offline -----------------------------------------------------------------------------
    nds_client(MAC_C)
    set_box(busy=False, coins_at=[])
    page("hidD", MAC_C, "start", "hyper"); time.sleep(1.5)
    for _ in range(25):
        p = page("hidD", MAC_C, "finish", "hyper")
        if "No coins detected" in p: break
        time.sleep(1)
    check("No coins detected" in p, "no coins, no access")
    set_box(busy=True, coins_at=[])
    p = page("hidE", MAC_C, "start", "hyper")
    for _ in range(40):  # the waiting page polls; once the window failed to arm it turns into the busy page
        if "Coin slot is busy" in p: break
        time.sleep(0.5); p = page("hidE", MAC_C, "wait", "hyper")
    check("Coin slot is busy" in p and 'content="5; url=' in p and "coinact=start" in p, "busy page retries start")
    p = page("hidF", MAC_C, port=9)
    check("offline" in p.lower(), "listener down: friendly offline page")

    # ---- window extension: a coin restarts the short wait ---------------------------------------------------------
    set_box(busy=False, coins_at=[2.5])  # first wait is 4 s: this coin arrives late, the 3 s idle wait then applies
    t0 = time.time()
    page("hidG", MAC_C, "start", "hyper")
    for _ in range(30):
        st = json.loads(get(f"/status?sid={sid_of('hidG')}")[1])
        if st["state"] == "done": break
        time.sleep(0.5)
    took = time.time() - t0
    check(st["pulses"] == 1 and 5.0 <= took <= 12.0, f"window extended after a coin (took {took:.1f}s, status {st})")

    # ---- acceptor settling: the customer is not invited to pay (state stays "starting") until the box says it is ready ----
    set_box(busy=False, coins_at=[0.4, 1.5], settle_ms=900)   # the first coin falls inside the settling window and is ignored
    sT = sid_of("hidSettle")
    t0 = time.time()
    get(f"/start?sid={sT}&plan=hyper&mac={MAC_C}")
    time.sleep(0.5)
    early = json.loads(get(f"/status?sid={sT}")[1])
    check(early["state"] == "starting", f"still 'getting ready' while the acceptor settles ({early['state']} at {time.time() - t0:.1f}s)")
    for _ in range(30):
        st = json.loads(get(f"/status?sid={sT}")[1])
        if st["state"] == "armed":
            break
        time.sleep(0.2)
    check(st["state"] == "armed" and time.time() - t0 >= 0.85, f"armed only after the settling time ({time.time() - t0:.1f}s)")
    check(st["remaining"] >= 3, f"the countdown starts when the slot is ready ({st['remaining']}s of 4)")
    for _ in range(40):
        st = json.loads(get(f"/status?sid={sT}")[1])
        if st["state"] == "done":
            break
        time.sleep(0.5)
    check(st["pulses"] == 1, f"a coin inside the settling window is ignored and one after it is counted ({st['pulses']})")

    # ---- live updates (Server-Sent Events) ----------------------------------------------------------------------------
    # coins reach the page as they arrive, not on a 2 s refresh
    nds_client(MAC_A)
    set_box(busy=False, coins_at=[1.0, 2.0])
    sS = sid_of("hidS")
    get(f"/start?sid={sS}&plan=hyper&mac={MAC_A}")
    t_start = time.time()
    ss = Stream(sS, "wait")
    check(ss.wait("status", 3) is not None, "stream opens for the device that started the session")
    check(b"text/event-stream" in ss.head, "stream is text/event-stream")
    first = [e for e in ss.events if e[1] == "status" and json.loads(e[2])["pulses"] >= 1]
    for _ in range(60):
        first = [e for e in ss.events if e[1] == "status" and json.loads(e[2])["pulses"] >= 1]
        if first: break
        time.sleep(0.05)
    check(bool(first), "stream reports the first coin")
    if first:
        lag = (ss.t0 + first[0][0]) - (t_start + 1.0)
        check(lag < 1.6, f"first coin pushed within 1.6 s of arriving (lag {lag:.2f}s)")
    for _ in range(200):
        if any(json.loads(e[2])["state"] == "done" for e in ss.events if e[1] == "status"): break
        time.sleep(0.1)
    check(any(json.loads(e[2])["state"] == "done" for e in ss.events if e[1] == "status"), "stream ends with the finished window")
    ss.close()

    # only the owner's device, only a known session, only the stream path
    sS2 = sid_of("hidS2")
    get(f"/start?sid={sS2}&plan=hyper&mac={MAC_B}")                     # started by another device than 127.0.0.1's MAC
    bad = Stream(sS2, "wait"); time.sleep(0.6)
    check(b"403" in bad.head, "stream refused for a session that belongs to another device")
    bad = Stream("e" * 32, "wait"); time.sleep(0.6)
    check(b"403" in bad.head, "stream refused for an unknown session")
    bad = Stream("zz", "wait"); time.sleep(0.6)
    check(b"400" in bad.head, "stream refuses a malformed session id")
    import socket as _s
    c = _s.create_connection(("127.0.0.1", STREAM_PORT)); c.sendall(b"GET /status?sid=" + sS.encode() + b" HTTP/1.1\r\n\r\n"); time.sleep(0.5)
    check(b"404" in c.recv(4096), "stream port serves nothing but /stream"); c.close()

    # a busy slot is pushed, never polled: waiting line in order, ready for the first only, nobody jumps the line
    set_box(busy=True, coins_at=[])
    q1, q2, q3 = sid_of("hidQ1"), sid_of("hidQ2"), sid_of("hidQ3")
    for q in (q1, q2):
        get(f"/start?sid={q}&plan=hyper&mac={MAC_A}")
    time.sleep(1.5)
    s1 = Stream(q1, "queue"); time.sleep(0.3)
    s2 = Stream(q2, "queue")
    ev = s1.wait("queue", 3); check(ev is not None and json.loads(ev[2])["pos"] == 1, "first in line is told position 1")
    ev = s2.wait("queue", 3); check(ev is not None and json.loads(ev[2])["pos"] == 2, "second in line is told position 2")
    time.sleep(1.0)
    check(s1.wait("ready", 0.1) is None, "nobody is told ready while the slot is busy")
    get(f"/start?sid={q3}&plan=hyper&mac={MAC_A}")
    check(json.loads(get(f"/start?sid={q3}&plan=hyper&mac={MAC_A}")[1]).get("error") == "SLOT_BUSY", "a newcomer cannot jump the waiting line")
    t_free = time.time()
    set_box(busy=False, coins_at=[])
    ev = s1.wait("ready", 5)
    check(ev is not None and time.time() - t_free < 2.0, "first in line is told the moment the slot frees")
    check(s2.wait("ready", 0.8) is None, "second in line is not told while the first holds the claim")
    check(json.loads(get(f"/start?sid={q2}&plan=hyper&mac={MAC_A}")[1]).get("error") == "SLOT_BUSY", "second in line cannot start ahead of the first")
    set_box(busy=False, coins_at=[0.5])
    r = json.loads(get(f"/start?sid={q1}&plan=hyper&mac={MAC_A}")[1])
    check(r.get("state") in ("starting", "armed"), "the customer who was told ready can start")
    check(s1.wait("started", 3) is not None, "their stream ends with started")
    # q2 is now first, the slot is held by q1: no ready until q1 finishes
    check(s2.wait("ready", 1.5) is None, "next in line waits while the slot is in use")
    get(f"/finish?sid={q1}")
    ev = s2.wait("ready", 8); check(ev is not None, "next in line is told when the previous customer is done")
    s1.close()
    # a ready customer who does not tap loses the claim
    for _ in range(40):
        st = json.loads(get(f"/status?sid={q1}")[1])["state"]
        if st == "done": break
        time.sleep(0.25)
    check(s2.wait("expired", QUEUE_CLAIM + 3) is not None, "an unclaimed ready slot is released after the claim window")
    s2.close()
    time.sleep(0.5)
    check(not glob.glob(f"{STATE}/queue/*"), "the waiting line is empty afterwards")

    # ---- browser: the waiting page updates itself and plays a coin sound (headless Chrome; skipped if none) ---------------
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
    def browser_run(stream_ok, coins, driver=None):
        """Tap Insert Coin in headless Chrome against the real theme; returns (dumped DOM, request counts)."""
        nds_client("aa:bb:cc:00:00:09")
        set_box(busy=False, coins_at=coins)
        open(ARP, "w").write(f"IP address HW type Flags HW address Mask Device\n127.0.0.1 0x1 0x2 {'aa:bb:cc:00:00:09' if stream_ok else 'aa:bb:cc:99:99:99'} * lo\n")   # another MAC: the stream refuses, the page falls back to polling
        hits = {"wait": 0, "start_mode": None, "finish": 0}

        class Portal(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
                act = q.get("coinact", "")
                html = page(f"hidJS{int(stream_ok)}{len(coins)}{id(driver)}", "aa:bb:cc:00:00:09", act, q.get("coinplan", ""))
                if act == "":  # the welcome page: tap Insert Coin a moment after loading, like a customer would
                    html = html.replace("</body>", '<script>setTimeout(function(){document.querySelector(".coin").click()},300)</script></body>')
                if act == "start":
                    hits["start_mode"] = self.headers.get("Sec-Fetch-Mode")
                if act == "finish":
                    hits["finish"] += 1
                if act == "wait":  # the first reload still shows 0 pesos; later polls show that 2 coins arrived
                    hits["wait"] += 1
                    if hits["wait"] > 1:
                        html = html.replace('id="pes">&#8369;0<', 'id="pes">&#8369;2<')
                body = html.encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                try:
                    self.wfile.write(body)
                except BrokenPipeError:  # Chrome abandons requests when it exits
                    pass

        srv = ThreadingHTTPServer(("127.0.0.1", 18120), Portal)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        if driver is not None:   # real-time browser session (Playwright) instead of Chrome's virtual-time dump
            try:
                dom = driver("http://127.0.0.1:18120/opennds_preauth/?fas=ABC")
            finally:
                srv.shutdown()
                srv.server_close()
            return dom, hits
        r = subprocess.run([chrome, "--headless=new", "--no-sandbox", "--disable-gpu", "--autoplay-policy=no-user-gesture-required",
                            "--virtual-time-budget=5000", "--dump-dom",
                            "http://127.0.0.1:18120/opennds_preauth/?fas=ABC"],
                           capture_output=True, text=True, timeout=90)
        srv.shutdown()
        srv.server_close()
        return r.stdout, hits

    if chrome:
        # Without a reachable stream port the page falls back to polling (and the coin sound still plays).
        dom, hits = browser_run(False, [0.8, 1.2])
        check('id="pes">\u20b12<' in dom, "waiting page updated its coin count by itself (fallback polling)")
        check('data-dings="1"' in dom, "a new coin played the coin sound once")
        check(hits["start_mode"] not in (None, "navigate"), f"Insert Coin started the window without leaving the page (request mode {hits['start_mode']})")
        try:
            from playwright.sync_api import sync_playwright
        except ImportError:
            sync_playwright = None
            print("skipped: playwright not installed (real-time tap tests)")

        def tap_connect(url):
            """A customer taps Insert Coin, waits until coins show, then taps Connect now (real time, so the live stream runs)."""
            with sync_playwright() as pw:
                br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
                pg = br.new_page()
                pg.goto(url)
                try:
                    pg.wait_for_function("(document.getElementById('pes')||{}).textContent && /[1-9]/.test(document.getElementById('pes').textContent)", timeout=15000)
                    pg.wait_for_timeout(500)
                    pg.click("#wait button.btn", timeout=5000)
                    pg.wait_for_timeout(2500)
                except Exception as e:
                    print("tap_connect:", str(e)[:200])
                dom = pg.content()
                br.close()
                return dom

        def keeps_button(url):
            """The same Connect button element must stay in the page while coins arrive and the page refreshes."""
            with sync_playwright() as pw:
                br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
                pg = br.new_page()
                pg.goto(url)
                pg.wait_for_selector("#wait button.btn", timeout=15000)
                pg.evaluate("window.__b = document.querySelector('#wait button.btn')")
                pg.wait_for_timeout(5500)   # several refreshes and coins
                same = pg.evaluate("window.__b === document.querySelector('#wait button.btn') && document.body.contains(window.__b)")
                br.close()
                return str(same)
        if sync_playwright:
            for label, ok in (("live stream", True), ("polling fallback", False)):
                dom, hits = browser_run(ok, [0.8, 1.2, 1.6], driver=tap_connect)
                check(hits["finish"] >= 1, f"tapping Connect now sends the finish request, {label} (seen: {hits['finish']})")
                dom, hits = browser_run(ok, [0.8, 1.2, 1.6, 3.0, 4.5, 6.0, 7.5, 9.0, 10.5], driver=keeps_button)
                check(dom == "True", f"the Connect button is never replaced while the page updates, {label}")
        # With the stream: the page follows the window to its end through the pushed events, with no polling of the portal.
        dom, hits = browser_run(True, [0.8, 1.2])
        check("Connect" in dom and "&#8369;2" not in dom and hits["wait"] == 1, f"live stream carried the page to the result without polling (portal wait requests: {hits['wait']})")
    else:
        print("skipped: no Chrome/Chromium found for the browser test")

    # ---- status page for connected customers (openNDS "statuspath") -------------------------------------------------------
    STATUS = f"{ROOT}/coinslot_status.sh"
    MAC_S = "aa:bb:cc:00:00:0a"
    nds_client(MAC_S)
    p = pay("hidST", MAC_S, "hyper", 2)
    page("hidST", MAC_S, "connect", "hyper", landing="yes")
    open(f"{NDS}/ip_10.9.9.9", "w").write(MAC_S)
    nds_client("aa:bb:cc:00:00:0b")                   # a client that is not connected
    open(f"{NDS}/ip_10.9.9.8", "w").write("aa:bb:cc:00:00:0b")

    def status_page(*args, **kw):
        return subprocess.run(["sh", STATUS, *args], env={**env, "COINSLOT_URL": f"http://127.0.0.1:{LISTEN_PORT}", **kw}, capture_output=True, text=True, timeout=30)
    r = status_page("status", "10.9.9.9")
    check(r.returncode == 0 and "You are connected" in r.stdout and "time left" in r.stdout, "status page shows a connected customer")
    check(code_from(r.stdout) is not None, "status page shows the voucher code")
    check("HyperSpeed" in r.stdout and "Data used" in r.stdout, "status page shows plan and data used")
    check('href="http://192.168.1.1:2050/opennds_auth/"' in r.stdout, "status page links to the portal for more time")
    check("Logout" not in r.stdout and "opennds_deny" not in r.stdout, "no logout button (paid time keeps running)")
    check("<b>" not in r.stdout.split("<h1>")[1].split("</h1>")[0] and "Test&lt;Spot" in r.stdout, "gateway name is escaped")
    r = status_page("status", "10.9.9.9", COINSLOT_URL="http://127.0.0.1:9")
    check(r.returncode == 0 and "time left" in r.stdout, "status page still works when the coin-slot manager is down")
    r = status_page("status", "10.9.9.8")
    check("not connected" in r.stdout and "/login" in r.stdout, "status page for a device that is not connected")
    r = status_page("err511", "10.9.9.8")
    check("Continue" in r.stdout and 'href="http://192.168.1.1:2050/login"' in r.stdout, "login-needed page offers Continue")
    check(status_page("status", "10.0;rm").returncode != 0 and status_page("nonsense", "10.9.9.9").returncode != 0, "bad input is refused")
    theme_src = open(THEME).read()
    css = [l for l in open(STATUS).read().split("<style>\n")[1].split("</style>")[0].split("\n") if l]
    check(all(l in theme_src for l in css), "status page styles are copies of the portal's (no drift)")

    # ---- finding the box when it moved (layout A) --------------------------------------------------------------------
    def box_cmd(extra, state):
        c = f"{tmp}/moved.conf"
        open(c, "w").write(open(conf).read().replace(f"GW_BOX=127.0.0.1:{BOX_PORT}", "GW_BOX=127.0.0.1:1") + extra + f"STATE_DIR={state}\n")
        e = dict(env, COINSLOT_CONF=c)
        r = subprocess.run(["sh", LISTENER, "box"], env=e, capture_output=True, text=True)
        return r.returncode, r.stdout.strip()
    good = '{"type":"PISOPHONE_ESP32_RESPONSE","mac":"AA:BB:CC:DD:EE:01","ip":"%s","port":%d}'
    rc, out = box_cmd("DISCOVER_CMD='echo nothing'\n", f"{tmp}/s1")
    check(rc != 0 and "does not answer" in out, "unreachable box with no discovery answer is reported: " + out)
    open(f"{tmp}/fakereply", "w").write(good % ("127.0.0.1;rm", BOX_PORT))
    rc, out = box_cmd(f"DISCOVER_CMD='cat {tmp}/fakereply'\n", f"{tmp}/s2")
    check(rc != 0, "a discovery answer that is not a plain IPv4 address is refused")
    open(f"{tmp}/fakereply", "w").write(good % ("127.0.0.1", BOX_PORT))
    rc, out = box_cmd(f"DISCOVER_CMD='cat {tmp}/fakereply'\nGW_BOX_MAC=11:22:33:44:55:66\n", f"{tmp}/s3")
    check(rc != 0 and not os.path.exists(f"{tmp}/s3/box_addr"), "a box with the wrong MAC is not adopted")
    rc, out = box_cmd(f"DISCOVER_CMD='cat {tmp}/fakereply'\nGW_BOX_MAC=aa:bb:cc:dd:ee:01\n", f"{tmp}/s4")
    check(rc == 0 and f"127.0.0.1:{BOX_PORT}" in out, "a moved box with the right MAC is found and used: " + out)

    # ---- router flash wear: DATA_DIR (flash on a router) is written only when money or a voucher changes -------------
    def snapshot():
        return {os.path.join(r, f): os.stat(os.path.join(r, f)).st_mtime_ns for r, _, fs in os.walk(DATA) for f in fs}
    before = snapshot()
    for _ in range(5):
        get(f"/status?sid={'c' * 32}")
        get("/info")
        get("/tiers?plan=hyper")
        get(f"/me?mac={MAC_A}")
        get(f"/claim?sid={'c' * 32}&mac={MAC_A}")
    check(snapshot() == before, "polling and status pages never write to the flash-backed data directory")

    # ---- reports and hardening ---------------------------------------------------------------------------------------
    rep = listener("report", "7")
    check("endurance" in rep and "hyper" in rep and "Total last 7 day(s): PHP" in rep, "revenue report: " + rep.replace("\n", " | "))
    check(get("/status?sid=abc;rm")[0] == 400, "bad sid rejected")
    check(get("/status?sid=" + "g" * 32)[0] == 400, "non-hex sid rejected")
    check(get(f"/start?sid={'a' * 32}&plan=evil&mac={MAC_A}")[0] == 400, "bad plan rejected")
    check(get(f"/claim?sid={'a' * 32}&mac=zz")[0] == 400, "bad mac rejected")
    check(get("/nothing?sid=" + "a" * 32)[0] == 404, "unknown path 404")
    for _ in range(11):
        get(f"/voucher?sid={'b' * 32}&mac={MAC_B}&code=ZZZZZZZZ")
    check("TOO_MANY_TRIES" in get(f"/voucher?sid={'b' * 32}&mac={MAC_B}&code=ZZZZZZZZ")[1], "voucher guessing is rate limited")
    p = page("hidA", MAC_A, terms="yes")
    check("Terms of Service" in p, "terms page")
finally:
    for pr in procs:
        pr.kill()
    for pidfile in glob.glob(f"{STATE}/*/pid"):
        try:
            os.kill(int(open(pidfile).read()), signal.SIGTERM)
        except (OSError, ValueError):
            pass

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
