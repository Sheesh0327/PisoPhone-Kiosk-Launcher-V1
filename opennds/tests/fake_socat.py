"""Stands in for socat in tests: accepts TCP connections and runs `coinslot-listener.sh handle` for each."""
import socketserver, subprocess, sys

LISTENER = sys.argv[2]


class H(socketserver.StreamRequestHandler):
    def handle(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            data += chunk
        out = subprocess.run(["sh", LISTENER, "handle"], input=data, capture_output=True, timeout=60).stdout
        self.request.sendall(out)


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


S(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
