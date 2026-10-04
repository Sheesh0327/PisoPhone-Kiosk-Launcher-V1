"""Fake ESP32 gateway API for tests: verifies the same HMAC and nonce rules as the firmware.

Behaviour is driven by a JSON control file (path in FAKEBOX_CTL) that tests rewrite:
  {"busy": false, "coins_at": [2, 4]}   # seconds after arm at which each coin (1 peso) arrives
The action log is appended to FAKEBOX_LOG (one action per line)."""
import hashlib, hmac, json, os, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

KEY = os.environ.get("FAKEBOX_KEY", "test-gateway-key-123456")
CTL = os.environ["FAKEBOX_CTL"]
LOG = os.environ["FAKEBOX_LOG"]
LOCK = threading.Lock()  # the server is threaded: nonce use and session changes must not interleave
nonces, sessions = set(), {}  # sid -> {"armed_at", "released_at", "pulses_acked": 0, "coins_seen": 0}


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
        if action == "release":
            s["released"], s["rel_at"] = True, time.time()
        pulses = 0
        if s["armed_at"]:
            end = s["rel_at"] if s["released"] else time.time()
            # pulses that arrive while the acceptor settles are ignored by the real box, not delayed
            pulses = sum(1 for t in c.get("coins_at", []) if s["armed_at"] + t <= end and s["armed_at"] + t >= s.get("settle_until", 0)) - s["acked"]
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
                                   x["armed_at"] and not x["released"] for x in sessions.values())})


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
