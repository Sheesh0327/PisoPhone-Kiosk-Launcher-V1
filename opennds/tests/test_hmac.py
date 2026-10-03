"""The listener signs box requests with a shell-only HMAC-SHA256 (openssl takes ~0.6 s to start on the router).
It must agree with a reference implementation for every kind of key and message, or the box refuses the request."""
import hashlib, hmac, os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LISTENER = os.path.join(os.path.dirname(HERE), "coinslot-listener.sh")
failures = 0
tmp = tempfile.mkdtemp()
keys = ["k", "test-gateway-key-123456", "6666666666", "has space and 'quote' and %s %d", "a" * 63, "a" * 64, "a" * 65, "b" * 200,
        "0123456789abcdef" * 4, "\\\\n"]
msgs = ["hello", "gw1:arm:" + "a" * 32 + ":" + "b" * 32, "100% 'sure' \"x\" $HOME `id`", "x" * 300]
for key in keys:
    conf = os.path.join(tmp, "c")
    open(conf, "w").write("GW_KEY='" + key.replace("'", "'\\''") + "'\n")
    for m in msgs:
        r = subprocess.run(["sh", LISTENER, "hmac", m], env=dict(os.environ, COINSLOT_CONF=conf, UCI="none"), capture_output=True, text=True)
        lines = r.stdout.split("\n")
        want = hmac.new(key.encode(), m.encode(), hashlib.sha256).hexdigest()
        if lines[0] != "shell" or lines[1] != want:
            failures += 1
            print(f"FAIL: key {key[:20]!r} message {m[:20]!r}: mode {lines[0]!r} got {lines[1:2]} want {want}")
print("hmac:", "OK" if not failures else f"{failures} failure(s)")
sys.exit(1 if failures else 0)
