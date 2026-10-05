"""Test harness for pisoportal: a fake coin box (fakebox.py), a fake ndsctl, a fake ARP table, and customers that talk to the
portal over WebSocket like the page does."""
import base64, hashlib, hmac, json, os, shutil, signal, socket, subprocess, sys, tempfile, threading, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.environ.get("PISOPORTAL") or os.path.join(HERE, "..", "target", "release", "pisoportal")
KEY = "test-gateway-key-123456"
PORTAL_PORT, ADMIN_PORT, EVENT_PORT, BOX_PORT = 18280, 18281, 18282, 18283

checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name, flush=True)


def finish():
    print(f"{checks} checks, {failures} failures")
    sys.exit(1 if failures else 0)


def wait_until(cond, timeout, step=0.05):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if cond():
                return True
        except Exception:
            pass
        time.sleep(step)
    return False


class Env:
    """One test environment: temp dirs, fake box, fake ndsctl, ARP table. start()/stop() run the portal."""

    def __init__(self, offset=0, **extra):
        self.PP, self.AP, self.EP, self.BP = PORTAL_PORT + offset, ADMIN_PORT + offset, EVENT_PORT + offset, BOX_PORT + offset
        self.tmp = tempfile.mkdtemp(prefix="pisoportal-test-")
        self.data = f"{self.tmp}/data"
        self.nds = f"{self.tmp}/nds"
        os.makedirs(self.data)
        os.makedirs(self.nds)
        self.ctl, self.boxlog = f"{self.tmp}/ctl.json", f"{self.tmp}/box.log"
        self.arp = f"{self.tmp}/arp"
        self.macs = {"127.0.0.1": "aa:bb:cc:00:00:01", "127.0.0.2": "aa:bb:cc:00:00:02", "127.0.0.3": "aa:bb:cc:00:00:03", "127.0.0.4": "aa:bb:cc:00:00:04"}
        self.write_arp()
        self.set_box()
        self.extra = extra
        self.procs = []
        self.portal = None
        self.start_box()

    def write_arp(self):
        open(self.arp, "w").write("IP address HW type Flags HW address Mask Device\n" + "".join(f"{ip} 0x1 0x2 {m} * lo\n" for ip, m in self.macs.items()))

    def set_box(self, **kw):
        json.dump(kw, open(self.ctl, "w"))

    def box_env(self):
        return dict(os.environ, FAKEBOX_CTL=self.ctl, FAKEBOX_LOG=self.boxlog, FAKEBOX_KEY=KEY)

    def start_box(self):
        open(self.boxlog, "w").close()
        self.box = subprocess.Popen([sys.executable, f"{HERE}/fakebox.py", str(self.BP)], env=self.box_env(), stderr=subprocess.DEVNULL)
        wait_until(lambda: socket.create_connection(("127.0.0.1", self.BP), timeout=0.2).close() or True, 5)

    def portal_env(self):
        e = dict(os.environ, PISOPORTAL_CONF=f"{self.tmp}/none.conf", PORTAL_BIND="0.0.0.0", PORTAL_PORT=str(self.PP), ADMIN_PORT=str(self.AP),
                 EVENT_PORT=str(self.EP), EVENT_BIND="127.0.0.1", GW_BOX=f"127.0.0.1:{self.BP}", GW_KEY=KEY, DATA_DIR=self.data,
                 NDSCTL=f"{HERE}/fake_ndsctl.sh", FAKE_NDS_DIR=self.nds, ARP_FILE=self.arp, GATEWAY_NAME="Test Spot",
                 COIN_FIRST_WAIT_SECONDS="4", COIN_IDLE_WAIT_SECONDS="3", COIN_MAX_SECONDS="12", COIN_DRAIN_SECONDS="5", EMPTY_COOLDOWN="20",
                 BUSY_RETRY="7", FAIR_USE_KB="1000", FAIR_INTERVAL_SECONDS="1", FAIR_FULL_MINUTES="0", FAIR_THROTTLE_MINUTES="0")
        e.update({k: str(v) for k, v in self.extra.items()})
        return e

    def start(self):
        self.log = open(f"{self.tmp}/portal.log", "ab")
        self.portal = subprocess.Popen([BIN], env=self.portal_env(), stderr=self.log, stdout=subprocess.DEVNULL)
        ok = wait_until(lambda: socket.create_connection(("127.0.0.1", self.PP), timeout=0.2).close() or True, 5)
        wait_until(lambda: socket.create_connection(("127.0.0.1", self.AP), timeout=0.2).close() or True, 5)
        return ok

    def stop(self):
        if self.portal:
            self.portal.kill()
            self.portal.wait()
            self.portal = None

    def close(self):
        self.stop()
        self.box.kill()
        shutil.rmtree(self.tmp, ignore_errors=True)

    # ---- fake openNDS --------------------------------------------------------------------------------------------------------
    def nds_client(self, mac, **kw):
        d = dict(STATE="Preauthenticated", SESSION_END=0, DL=0, UL=0, UPRATE=0, DOWNRATE=0)
        d.update(kw)
        open(f"{self.nds}/{mac.replace(':', '')}", "w").write("".join(f"{k}={v}\n" for k, v in d.items()))

    def nds_get(self, mac):
        out = {}
        for l in open(f"{self.nds}/{mac.replace(':', '')}").read().split("\n"):
            if "=" in l:
                k, v = l.split("=", 1)
                out[k] = v
        return out

    def nds_calls(self):
        p = f"{self.nds}/calls.log"
        return open(p).read().split("\n") if os.path.exists(p) else []

    def clear_nds_log(self):
        open(f"{self.nds}/calls.log", "w").close()

    # ---- files ---------------------------------------------------------------------------------------------------------------------
    def roll(self):
        p = f"{self.data}/vouchers.txt"
        out = {}
        if os.path.exists(p):
            for l in open(p).read().split("\n"):
                f = l.split(",")
                if len(f) >= 17:
                    out[f[7]] = f
        return out

    def revenue(self):
        p = f"{self.data}/revenue.csv"
        return open(p).read() if os.path.exists(p) else ""

    def boxlines(self):
        return open(self.boxlog).read().split("\n")

    def log_text(self):
        return open(f"{self.tmp}/portal.log").read() if os.path.exists(f"{self.tmp}/portal.log") else ""

    def box_call(self, action, sid):
        n = json.load(urllib.request.urlopen(f"http://127.0.0.1:{self.BP}/api/gateway/challenge"))["nonce"]
        sig = hmac.new(KEY.encode(), f"gw1:{action}:{sid}:{n}".encode(), hashlib.sha256).hexdigest()
        return json.load(urllib.request.urlopen(f"http://127.0.0.1:{self.BP}/api/gateway/{action}?session={sid}&nonce={n}&sig={sig}"))

    def reset_device(self, mac):
        """Forget a device's paid time and put it back in the not-yet-online state."""
        p = f"{self.data}/vouchers.txt"
        if os.path.exists(p):
            lines = [l for l in open(p).read().split("\n") if l and l.split(",")[7] != mac]
            open(p, "w").write("".join(l + "\n" for l in lines))
        self.nds_client(mac)

    def cli(self, *args):
        r = subprocess.run([BIN, *args], env=self.portal_env(), capture_output=True, text=True, timeout=30)
        return r.returncode, r.stdout.strip()

    def admin(self, path):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.AP}{path}", timeout=10) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return json.loads(e.read().decode())

    def get(self, path, headers=None, ip=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.PP}{path}", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(), dict(e.headers)


class Cust:
    """A customer's page: one WebSocket to the portal, from its own source address (the portal maps it to a MAC via ARP)."""

    def __init__(self, ip="127.0.0.1", port=PORTAL_PORT, fas="", reset=False):
        self.ip, self.msgs, self.closed, self.lock, self.since = ip, [], False, threading.Lock(), 0
        self.s = socket.create_connection(("127.0.0.1", port), source_address=(ip, 0), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.send(f"GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
        head = b""
        while b"\r\n\r\n" not in head:
            c = self.s.recv(1)
            if not c:
                break
            head += c
        self.head = head.decode(errors="replace")
        self.ok = "101" in self.head.split("\r\n")[0]
        self.s.settimeout(None)
        if self.ok:
            threading.Thread(target=self._read, daemon=True).start()
            self.send({"t": "hello", "fas": fas, "reset": reset})

    def _rd(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise EOFError
            b += c
        return b

    def _read(self):
        try:
            while True:
                h = self._rd(2)
                op, ln = h[0] & 15, h[1] & 127
                if ln == 126:
                    ln = int.from_bytes(self._rd(2), "big")
                data = self._rd(ln)
                if op == 1:
                    with self.lock:
                        self.msgs.append((time.time(), json.loads(data.decode())))
                elif op == 9:
                    self.send_raw(0x8A, data)
                elif op == 8:
                    break
        except Exception:
            pass
        self.closed = True

    def send_raw(self, op, payload):
        m = os.urandom(4)
        n = len(payload)
        lenb = bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big")
        try:
            self.s.send(bytes([op]) + lenb + m + bytes(c ^ m[i % 4] for i, c in enumerate(payload)))
        except OSError:
            pass

    def send(self, obj):
        self.send_raw(0x81, json.dumps(obj).encode())

    def states(self):
        with self.lock:
            return [m for _, m in self.msgs if m.get("t") == "state"]

    def last(self):
        s = self.states()
        return s[-1] if s else {}

    def mark(self):
        """Only look at what arrives from now on."""
        self.since = len(self.states())

    def wait(self, pred, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            for m in self.states()[self.since:]:
                if pred(m):
                    return m
            time.sleep(0.03)
        return None

    def wait_state(self, name, timeout=15):
        return self.wait(lambda m: m.get("s") == name, timeout)

    def close(self):
        try:
            self.send_raw(0x88, b"")
            self.s.close()
        except OSError:
            pass
