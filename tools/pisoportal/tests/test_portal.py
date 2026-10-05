#!/usr/bin/env python3
"""Runs the built pisoportal (step 1) against a fake ndsctl and a fake ARP table: the page, the grant, the WebSocket."""
import base64, hashlib, os, socket, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.environ.get("PISOPORTAL") or os.path.join(HERE, "..", "target", "release", "pisoportal")
PORT = 18200
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


tmp = tempfile.mkdtemp()
calls = os.path.join(tmp, "calls.log")
open(os.path.join(tmp, "ndsctl"), "w").write(f'#!/bin/sh\necho "ndsctl $*" >> {calls}\necho "Client $2 authenticated."\n')
os.chmod(os.path.join(tmp, "ndsctl"), 0o755)
open(os.path.join(tmp, "arp"), "w").write("IP address HW type Flags HW address Mask Device\n127.0.0.1 0x1 0x2 AA:BB:CC:DD:EE:FF * lo\n")

r = subprocess.run([BIN, "--selftest"], capture_output=True, text=True)
check(r.returncode == 0 and "websocket accept key: true" in r.stdout, "selftest (sha1, websocket key, base64, fas query): " + r.stdout)

srv = subprocess.Popen([BIN, "--port", str(PORT), "--bind", "127.0.0.1"], env=dict(os.environ, NDSCTL=os.path.join(tmp, "ndsctl"), ARP_FILE=os.path.join(tmp, "arp")),
                       stderr=subprocess.DEVNULL)
try:
    for _ in range(50):
        try:
            socket.create_connection(("127.0.0.1", PORT), timeout=0.2).close()
            break
        except OSError:
            time.sleep(0.1)

    def get(path):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=5) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    check(get("/ping") == (200, "ok"), "ping")
    fas = base64.b64encode(b"clientip=127.0.0.1, clientmac=aa:bb:cc:dd:ee:ff, gatewayname=PisoWiFi, hid=SECRETHID, originurl=http://x/").decode()
    fas = fas.replace("+", "%2B").replace("=", "%3D").replace("/", "%2F")
    code, body = get("/?fas=" + fas)
    check(code == 200 and "gatewayname" in body and "PisoWiFi" in body and "aa:bb:cc:dd:ee:ff" in body.lower(), "the login page shows what openNDS sent")
    check("SECRETHID" not in body and "http://x/" not in body, "the hashed id and the origin url are never echoed")
    check(get("/anything/else?x=1")[0] == 200, "any other address is the login page")
    code, body = get("/grant")
    check(code == 200 and body.startswith("ok") and "ndsctl auth aa:bb:cc:dd:ee:ff 5 0 0 0 0" in open(calls).read(),
          "grant runs ndsctl auth for the MAC the router sees: " + body)

    s = socket.create_connection(("127.0.0.1", PORT))
    key = base64.b64encode(os.urandom(16)).decode()
    s.send(f"GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
    head = s.recv(1024).decode()
    acc = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
    check("101" in head and acc in head, "websocket handshake")

    def send(p, op=0x81):
        m = os.urandom(4)
        n = len(p)
        lenb = bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big")
        s.send(bytes([op]) + lenb + m + bytes(c ^ m[i % 4] for i, c in enumerate(p)))

    send(b"ping")
    reply = s.recv(100)
    check(reply[0] == 0x81 and reply[2:].decode().startswith("pong 4 bytes"), "websocket text reply: " + repr(reply))
    send(b"x" * 300)   # a longer frame (16-bit length)
    check(s.recv(100)[2:].decode().startswith("pong 300 bytes"), "a longer frame")
    send(b"", 0x88)
    check(s.recv(10)[0] == 0x88, "close is answered")
    # a connection that sends garbage does not take the server down
    g = socket.create_connection(("127.0.0.1", PORT)); g.send(b"\x00\x01garbage\r\n\r\n"); g.close()
    check(get("/ping") == (200, "ok"), "still serving after garbage")
    t = time.time()
    for _ in range(50):
        get("/")
    check((time.time() - t) / 50 < 0.05, "50 page loads average under 50 ms (%.1f ms)" % ((time.time() - t) / 50 * 1000))
finally:
    srv.kill()

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
