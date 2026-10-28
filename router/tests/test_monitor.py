#!/usr/bin/env python3
"""Tests for piso_monitor.sh against a fake Telegram server, a fake portal program and a fake openNDS."""
import json, os, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
MON = os.path.join(os.path.dirname(HERE), "piso_monitor.sh")
tmp = tempfile.mkdtemp()
checks = failures = 0
sent, queue, hits = [], [], []


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def reply(self, obj):
        b = json.dumps(obj, separators=(",", ":")).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path.endswith("/getUpdates"):
            off = int(q.get("offset", ["0"])[0])
            res = [m for m in queue if m["update_id"] >= off]
            return self.reply({"ok": True, "result": res})
        if u.path == "/ping":   # the portal program: answers "ok", or hangs while the test says so
            if os.path.exists(f"{tmp}/pinghang"):
                time.sleep(3)
            b = b"ok"
            try:
                self.send_response(200); self.send_header("Content-Length", "2"); self.end_headers(); self.wfile.write(b)
            except OSError:
                pass   # the monitor gave up waiting (that is the point of the hang)
            return
        if u.path == "/hc":
            hits.append("hc"); return self.reply({})
        self.reply({"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = parse_qs(self.rfile.read(n).decode())
        if self.path.endswith("/sendMessage"):
            sent.append((body["chat_id"][0], body["text"][0]))
        self.reply({"ok": True})


srv = ThreadingHTTPServer(("127.0.0.1", 0), H); port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()

fake = os.path.join(tmp, "portal.sh")
open(fake, "w").write(f"""#!/bin/sh
case "$1" in
  box) [ -e {tmp}/boxdown ] && {{ echo no; exit 1; }}; echo "box answers" ;;
  reconcile) if [ -e {tmp}/mismatch ]; then echo "RECONCILE MISMATCH ledger=9 box=3"; exit 1; fi; echo "RECONCILE OK ledger=5 box=9"; exit 0 ;;
  report) echo "REPORT days=$2 total 120" ;;
esac
""")
os.chmod(fake, 0o755)
logf = os.path.join(tmp, "log.txt"); open(logf, "w").close()
fakelog = os.path.join(tmp, "logread.sh")
open(fakelog, "w").write(f"#!/bin/sh\ncat {logf}\n"); os.chmod(fakelog, 0o755)
fakesetup = os.path.join(tmp, "setup.sh"); open(fakesetup, "w").write(f"#!/bin/sh\n[ \"$1\" = self-update ] && {{ echo \"$*\" >> {tmp}/setup.calls; exit 0; }}\n[ \"$1\" = wan-clash ] && {{ [ -e {tmp}/wanclash ] && {{ cat {tmp}/wanclash; exit 1; }}; exit 0; }}\necho DIAGTEXT\n"); os.chmod(fakesetup, 0o755)
fakesvc = os.path.join(tmp, "pisoportal.init"); open(fakesvc, "w").write(f"#!/bin/sh\necho \"$1\" >> {tmp}/service.log\n"); os.chmod(fakesvc, 0o755)
fakends = os.path.join(tmp, "ndsctl"); open(fakends, "w").write("""#!/bin/sh
[ "$1" = json ] && printf '{"client_list_length":"3","clients":{\n"aa":{\n  "state":"Authenticated",\n},\n"bb":{\n  "state":"Preauthenticated",\n},\n"cc":{\n  "state":"Authenticated",\n}}}\n'
""")
os.chmod(fakends, 0o755)
conf = os.path.join(tmp, "mon.conf")
open(conf, "w").write(f"TG_TOKEN=T0K\nTG_CHAT=111\nSITE_NAME=Shop1\nREPORT_HOUR=0\nHEALTHCHECK_URL=http://127.0.0.1:{port}/hc\n")
env = dict(os.environ, PISO_MONITOR_CONF=conf, TG_API=f"http://127.0.0.1:{port}", MON_STATE=f"{tmp}/state", PORTAL=fake,
           PISO_SETUP=fakesetup, LOGREAD=fakelog, BOX_DOWN_AFTER="2", PORTAL_SERVICE=fakesvc, NDSCTL=fakends,
           PING_URL=f"http://127.0.0.1:{port}/ping", PING_TIMEOUT="1", ALERTS_FILE=f"{tmp}/alerts.txt", DF=f"{tmp}/df.sh")
open(f"{tmp}/df.sh", "w").write("#!/bin/sh\necho 'Filesystem 1K-blocks Used Available Use% Mounted on'\necho \"overlayfs:/overlay 14000 1000 $(cat " + tmp + "/free 2>/dev/null || echo 9000) 7% /overlay\"\n")
os.chmod(f"{tmp}/df.sh", 0o755)


def mon(*a):
    return subprocess.run(["sh", MON, *a], env=env, capture_output=True, text=True, timeout=60)


def texts():
    return [t for _, t in sent]


mon("tick")
check(any("router started" in t for t in texts()) and all(c == "111" and t.startswith("Shop1: ") for c, t in sent), f"boot message: {sent}")
check(any("daily report" in t and "total 120" in t for t in texts()), "daily report sent once the hour is reached")
check(hits == ["hc"], "dead-man ping sent")
n = len(sent); mon("tick")
check(not any("daily report" in t for t in texts()[n:]) and not any("router started" in t for t in texts()[n:]), "boot message and daily report are sent once")
# box offline
open(f"{tmp}/boxdown", "w").close()
n = len(sent); mon("tick")
check(len(sent) == n, "one missed check is not an alert")
mon("tick")
check(any("has not answered" in t for t in texts()[n:]), "box offline alert after the limit")
n = len(sent); mon("tick"); mon("tick")
check(len(sent) == n, "box offline is not repeated")
os.remove(f"{tmp}/boxdown"); mon("tick")
check(any("box is back" in t for t in texts()[n:]), "box back message")
# feature 033 (SU-05): a modem plugged in after the setup whose network overlaps the router's own: one alert (checked hourly: clear the stamp)
n = len(sent); mon("tick")
check(not any("modem" in t for t in texts()[n:]), "no clash: no modem alert")
open(f"{tmp}/wanclash", "w").write("the modem's network (10.1.2.3/8) overlaps the router's 10.0.0.1 network. Change the modem's own LAN address.\n")
for stamp in ("lastwan",):
    if os.path.exists(f"{tmp}/state/{stamp}"): os.remove(f"{tmp}/state/{stamp}")
n = len(sent); mon("tick")
check(any("modem's network (10.1.2.3/8)" in t for t in texts()[n:]), "a modem whose network overlaps the router's: an alert with the advice")
os.remove(f"{tmp}/state/lastwan"); n = len(sent); mon("tick"); mon("tick")
check(not any("modem's network" in t for t in texts()[n:]), "and it is not repeated within a day")
os.remove(f"{tmp}/wanclash")
# ledger mismatch (checked hourly: clear the stamp)
open(f"{tmp}/mismatch", "w").close(); os.remove(f"{tmp}/state/lastreconcile"); n = len(sent); mon("tick")
check(any("REVENUE MISMATCH" in t for t in texts()[n:]), "ledger mismatch alert")
os.remove(f"{tmp}/state/lastreconcile"); n = len(sent); mon("tick")
check(len(sent) == n, "mismatch alert is rate limited")
# griefing lines
open(logf, "w").write("Mon coinslot: alert grief: aa:bb opened 2 empty coin windows in 300s\n")
n = len(sent); mon("tick"); mon("tick")
check(sum("opened 2 empty" in t for t in texts()[n:]) == 1, "grief alert once")
# commands
queue.append({"update_id": 5, "message": {"chat": {"id": 111, "type": "private"}, "text": "/report 7"}})
queue.append({"update_id": 6, "message": {"chat": {"id": 999, "type": "private"}, "text": "/reboot confirm"}})
n = len(sent); mon("poll", "1")
check(any("REPORT days=7" in t for t in texts()[n:]), "/report 7 from the owner")
check(len(sent) - n == 1, "a message from another chat gets no answer")
mon("poll", "1")
check(len(sent) - n == 1, "an update is handled only once (offset)")
queue.append({"update_id": 7, "message": {"chat": {"id": 111}, "text": "/diag"}})
mon("poll", "1")
check(any("DIAGTEXT" in t for t in texts()), "/diag")
queue.append({"update_id": 8, "message": {"chat": {"id": 111}, "text": "/reboot"}})
n = len(sent); mon("poll", "1")
check(any("/reboot confirm" in t for t in texts()[n:]), "/reboot asks for confirmation first")
queue.append({"update_id": 9, "message": {"chat": {"id": 111}, "text": "/restart"}})
n = len(sent); mon("poll", "1")
check(os.path.exists(f"{tmp}/service.log") and open(f"{tmp}/service.log").read() == "restart\n" and any("portal restarted" in t for t in texts()[n:]),
      "/restart restarts the portal service (pisoportal)")
queue.append({"update_id": 10, "message": {"chat": {"id": 111}, "text": "/status"}})
n = len(sent); mon("poll", "1")
st = "\n".join(texts()[n:])
check("guests online: 2" in st and "box: answers" in st and "RECONCILE" in st, "/status counts the guests openNDS has let online: " + st)
open(os.path.join(tmp, "x-should-not-appear"), "w").close()
queue.append({"update_id": 11, "message": {"chat": {"id": 111}, "text": "/report *"}})
n = len(sent); r = subprocess.run(["sh", MON, "poll", "1"], env=env, capture_output=True, text=True, timeout=60, cwd=tmp)
check(any("REPORT days=1" in t for t in texts()[n:]), "a '*' in a command is a word, not a list of files")
queue.append({"update_id": 12, "message": {"chat": {"id": 111}, "text": "/update"}})
n = len(sent); mon("poll", "1"); time.sleep(0.5)
check(any("looking for an update" in t for t in texts()[n:]) and os.path.exists(f"{tmp}/setup.calls") and open(f"{tmp}/setup.calls").read() == "self-update\n",
      "/update runs piso-setup self-update (in the background) and answers at once")
queue.append({"update_id": 13, "message": {"chat": {"id": 999}, "text": "/update"}})
n = len(sent); mon("poll", "1"); time.sleep(0.3)
check(len(sent) == n and open(f"{tmp}/setup.calls").read() == "self-update\n", "/update from another chat does nothing")

# ---- AO-01 / AO-05 / AO-06: the portal program, free flash, alerts without Telegram ---------------------------------------------
def alerts():
    return open(f"{tmp}/alerts.txt").read().splitlines() if os.path.exists(f"{tmp}/alerts.txt") else []


def restarts():
    return open(f"{tmp}/service.log").read().count("restart") if os.path.exists(f"{tmp}/service.log") else 0


check(any("the router started" in l for l in alerts()) and any("has not answered" in l for l in alerts()), "AO-06: alerts are also written to alerts.txt: " + str(alerts()[:3]))
mon("tick")   # settle: the portal answers, nothing pending
base_restarts, n, na = restarts(), len(sent), len(alerts())
open(f"{tmp}/pinghang", "w").close()
mon("tick"); mon("tick")
check(restarts() == base_restarts and not any("did not answer" in t for t in texts()[n:]), "AO-01: two failed pings are not enough to restart the portal")
mon("tick")
check(restarts() == base_restarts + 1 and sum("was restarted" in t for t in texts()[n:]) == 1, "AO-01: the third failed ping restarts the portal once and sends one alert")
check(any("did not answer for 3 minutes" in l for l in alerts()[na:]), "and writes it to alerts.txt")
n, na = len(sent), len(alerts())
mon("tick"); mon("tick")
check(restarts() == base_restarts + 1 and len(sent) == n and len(alerts()) == na, "AO-01: the next ticks restart nothing and alert nothing new (once in 10 minutes)")
open(f"{tmp}/state/portal.restarted", "w").write(str(int(time.time()) - 700))   # ten minutes have passed and it still hangs
mon("tick")
check(restarts() == base_restarts + 2, "AO-01: after ten minutes a portal that still hangs is restarted again")
check(sum("did not answer" in t for t in texts()[n:]) == 0, "but the alert is not repeated within the hour")
os.remove(f"{tmp}/pinghang"); n, na = len(sent), len(alerts())
mon("tick")
check(any("answers again" in t for t in texts()[n:]) and any("answers again" in l for l in alerts()[na:]), "AO-01: an alert when the portal answers again")
n = len(sent); mon("tick")
check(len(sent) == n, "and only once")

# free flash (AO-05): hourly, under 1 MB and under 256 KB, each once a day
n, na = len(sent), len(alerts())
open(f"{tmp}/free", "w").write("900"); os.remove(f"{tmp}/state/lastspace") if os.path.exists(f"{tmp}/state/lastspace") else None
mon("tick")
check(sum("less than 1 MB" in t for t in texts()[n:]) == 1 and not any("less than 256 KB" in t for t in texts()[n:]), "AO-05: under 1 MB of free flash is one alert")
os.remove(f"{tmp}/state/lastspace"); n = len(sent); mon("tick")
check(len(sent) == n, "AO-05: and not again the same day")
open(f"{tmp}/free", "w").write("200"); os.remove(f"{tmp}/state/lastspace"); n = len(sent); mon("tick")
check(sum("less than 256 KB" in t for t in texts()[n:]) == 1, "AO-05: under 256 KB is another alert")
mon("tick"); n = len(sent); mon("tick")
check(len(sent) == n, "AO-05: the check runs once an hour")
open(f"{tmp}/free", "w").write("9000")

# the last 20 lines only (AO-06)
for i in range(25):
    mon("note", f"line {i}")
check(len(alerts()) == 20 and alerts()[-1].endswith("line 24") and alerts()[0].endswith("line 5"), "AO-06: alerts.txt keeps the last 20 lines: " + str(len(alerts())))

# a shop without Telegram: no settings at all (AO-06, Q1)
noconf = dict(env, PISO_MONITOR_CONF=f"{tmp}/does-not-exist.conf", MON_STATE=f"{tmp}/state2", ALERTS_FILE=f"{tmp}/alerts2.txt")
n, base = len(sent), restarts()
open(f"{tmp}/pinghang", "w").close()
for _ in range(3):
    subprocess.run(["sh", MON, "tick"], env=noconf, capture_output=True, text=True, timeout=60)
a2 = open(f"{tmp}/alerts2.txt").read() if os.path.exists(f"{tmp}/alerts2.txt") else ""
check(len(sent) == n, "no Telegram settings: nothing is sent")
check(restarts() == base + 1 and "did not answer for 3 minutes" in a2 and "router started" in a2, "no Telegram settings: the portal is still restarted and the alerts are written: " + a2)
os.remove(f"{tmp}/pinghang")
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
