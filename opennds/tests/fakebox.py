"""Fake ESP32 gateway API for tests: verifies the same HMAC and nonce rules as the firmware.

Behaviour is driven by a JSON control file (path in FAKEBOX_CTL) that tests rewrite:
  {"busy": false, "coins_at": [2, 4]}   # seconds after arm at which each coin (1 peso) arrives
  "events": false                         # do not push coin events (an older box); "drop_events": true loses them all
Coin events (GatewayEvent.h): an arm with wid & evport makes it push signed UDP lines to the caller, like the box.
The action log is appended to FAKEBOX_LOG (one action per line)."""
import hashlib, hmac, json, os, socket, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

KEY = os.environ.get("FAKEBOX_KEY", "test-gateway-key-123456")
CTL = os.environ["FAKEBOX_CTL"]
LOG = os.environ["FAKEBOX_LOG"]
LOCK = threading.Lock()  # the server is threaded: nonce use and session changes must not interleave
nonces, sessions = set(), {}  # sid -> {"armed_at", "released_at", "pulses_acked": 0, "coins_seen": 0}


def pulses_now(s, c, at=None):
    if not s["armed_at"]:
        return 0
    end = s["rel_at"] if s["released"] else (at if at is not None else time.time())
    # pulses that arrive while the acceptor settles are ignored by the real box, not delayed
    return max(sum(1 for t in c.get("coins_at", []) if s["armed_at"] + t <= end and s["armed_at"] + t >= s.get("settle_until", 0)) - s["acked"], 0)


def push(sid, s, typ):
    """Send one signed event line (twice, like the box) for the window in s, if the router asked for events."""
    ev = s.get("ev")
    c = ctl()
    if not ev or c.get("drop_events"):
        return
    with LOCK:
        ev["seq"] += 1
        p = pulses_now(s, c)
        body = f"gw1ev:{sid}:{ev['wid']}:{ev['seq']}:{typ}:{p}"
    line = (body + ":" + hmac.new(KEY.encode(), body.encode(), hashlib.sha256).hexdigest() + "\n").encode()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as u:
        for _ in range(2):
            u.sendto(line, (ev["host"], ev["port"]))
    with open(LOG, "a") as f:
        f.write(f"event {typ} {sid} {p}\n")


def ctl():
    with open(CTL) as f:
        return json.load(f)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, obj):
        body = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        with LOCK:
            self.handle_get()

    def handle_get(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        action = u.path.rsplit("/", 1)[1]
        if action == "challenge":
            n = os.urandom(16).hex()
            nonces.add(n)
            return self.send(200, {"nonce": n})
        n, sid = q.get("nonce", ""), q.get("session", "")
        if n not in nonces:
            return self.send(403, {"success": False, "error": "AUTH_FAILED"})
        nonces.discard(n)
        want = hmac.new(KEY.encode(), f"gw1:{action}:{sid}:{n}".encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(want, q.get("sig", "")):
            return self.send(403, {"success": False, "error": "AUTH_FAILED"})
        with open(LOG, "a") as f:
            f.write(f"{action} {sid}\n")
        s = sessions.setdefault(sid, {"armed_at": None, "released": False, "acked": 0, "rel_at": 0})
        c = ctl()
        if action == "arm":
            if c.get("busy"):
                return self.send(409, {"success": False, "error": "SLOT_BUSY"})
            if s["armed_at"] is None or s["released"]:  # a new window; re-arming a live one keeps its coins
                s.update(armed_at=time.time(), released=False, acked=0, settle_until=time.time() + c.get("settle_ms", 0) / 1000.0)
                s["ev"] = None
                if c.get("events", True) and q.get("wid") and q.get("evport"):
                    s["ev"] = {"wid": q["wid"], "port": int(q["evport"]), "host": self.client_address[0], "seq": 0}
                    gen = s["armed_at"]
                    def later(delay, typ, gen=gen):
                        def fire():
                            if s["armed_at"] == gen and not (typ == "coin" and s["released"] and time.time() - s["rel_at"] > 0.8):
                                push(sid, s, typ)
                        threading.Timer(max(delay, 0), fire).start()
                    later(s["settle_until"] - time.time() + 0.01, "ready")
                    for t in c.get("coins_at", []):
                        if s["armed_at"] + t >= s["settle_until"]:
                            later(t + 0.3, "coin")   # the box knows the coin's value 280 ms after its last pulse
        if action == "release" and not s["released"]:
            s["released"], s["rel_at"] = True, time.time()
            if s.get("ev"):
                gen = s["armed_at"]
                threading.Timer(0.85, lambda: s["armed_at"] == gen and push(sid, s, "end")).start()
        pulses = pulses_now(s, c)
        if action == "ack":
            s["acked"] += pulses
            return self.send(200, {"success": True, "acknowledged_pulses": pulses})
        state = "idle" if (s["released"] or not s["armed_at"]) else "armed"
        if s["released"] and time.time() - s["rel_at"] < 0.8:
            state = "draining"
        return self.send(200, {"success": True, "session": sid, "state": state, "armed_remaining": 0,
                               "pulses": max(pulses, 0), "minutes_per_coin": 6,
                               "ready_in_ms": max(0, int((s.get("settle_until", 0) - time.time()) * 1000)),
                               "slot_free": not c.get("busy") and not any(
                                   x["armed_at"] and not x["released"] for x in sessions.values()),
                               **({"events": True} if c.get("events", True) else {})})


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
