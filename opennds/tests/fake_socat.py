"""Stands in for socat in tests: accepts TCP connections and runs `coinslot-listener.sh <mode>` for each.
Usage: fake_socat.py <port> <listener> [handle|stream-handle]. Like socat's EXEC, the child gets SOCAT_PEERADDR; a
stream-handle child is relayed live and killed as soon as the client goes away."""
import os, socketserver, subprocess, sys

LISTENER = sys.argv[2]
MODE = sys.argv[3] if len(sys.argv) > 3 else "handle"


class H(socketserver.StreamRequestHandler):
    def handle(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            data += chunk
        env = dict(os.environ, SOCAT_PEERADDR=self.client_address[0])
        if MODE == "handle":
            out = subprocess.run(["sh", LISTENER, "handle"], input=data, capture_output=True, timeout=60, env=env).stdout
            self.request.sendall(out)
            return
        p = subprocess.Popen(["sh", LISTENER, MODE], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        p.stdin.write(data)
        p.stdin.flush()
        try:
            while True:
                out = os.read(p.stdout.fileno(), 4096)
                if not out:
                    break
                self.request.sendall(out)
        except OSError:
            pass
        finally:
            p.kill()


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


S(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
