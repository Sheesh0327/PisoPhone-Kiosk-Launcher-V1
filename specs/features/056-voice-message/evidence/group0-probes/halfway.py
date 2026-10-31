"""Feature 056, group 0 (d): a sender stops after half a clip. Run against NanoProbe.java in `server` mode.

A: the sender closes the connection. B: the sender goes silent and leaves the socket open (a Wi-Fi drop).
The server's own log (`SERVER SAW ...`) shows what the phone's side would see.
"""

import os
import socket
import time

HEAD = (
    b"POST /raw?payload=a&hmac=b HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\n"
    b"Content-Length: 60000\r\nConnection: close\r\n\r\n"
)


def send(label: str, stall: float) -> None:
    started = time.time()
    sock = socket.create_connection(("127.0.0.1", 18099))
    sock.sendall(HEAD)
    body = os.urandom(60000)
    for off in range(0, 30000, 1436):
        sock.sendall(body[off : min(off + 1436, 30000)])
        time.sleep(0.002)
    if stall:
        time.sleep(stall)  # no more bytes, and the connection is not closed
    sock.close()
    print(f"{label}: client done after {time.time() - started:.1f}s", flush=True)


send("A sender closes after 30000 of 60000", 0)
time.sleep(1)
send("B sender goes silent after 30000 (socket left open for 12 s)", 12)
