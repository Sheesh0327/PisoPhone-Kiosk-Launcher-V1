#!/usr/bin/env python3
"""Tests for piso_monitor.sh against a fake Telegram server, a fake portal program and a fake openNDS."""
import json, os, signal, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
MON = os.path.join(os.path.dirname(HERE), "piso_monitor.sh")
tmp = tempfile.mkdtemp()
checks = failures = 0
sent, queue, hits, cmds_reg = [], [], [], []


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
        if u.path == "/api/status":
            if os.path.exists(f"{tmp}/boxstatus.json"):
                b = open(f"{tmp}/boxstatus.json", "rb").read()
                self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
            else:
                self.send_response(503); self.end_headers()
            return
        if u.path == "/hc":
            hits.append("hc"); return self.reply({})
        self.reply({"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); raw = self.rfile.read(n).decode()
        if self.path.endswith("/sendMessage"):
            if os.path.exists(f"{tmp}/tgdown"):
                self.send_response(500); self.end_headers(); return
            body = parse_qs(raw)
            if os.path.exists(f"{tmp}/failhtml") and body.get("parse_mode", [""])[0] == "HTML":
                self.send_response(400); self.end_headers(); return
            sent.append((body["chat_id"][0], body["text"][0]))
        elif self.path.endswith("/setMyCommands"):
            cmds_reg.append(json.loads(raw))
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
fakesetup = os.path.join(tmp, "setup.sh"); open(fakesetup, "w").write(f"#!/bin/sh\n[ \"$1\" = self-update ] && {{ echo \"$*\" >> {tmp}/setup.calls; exit 0; }}\n[ \"$1\" = auto-update ] && {{ echo \"$*\" >> {tmp}/setup.calls; echo \"auto-update $2\"; exit 0; }}\n[ \"$1\" = wan-clash ] && {{ [ -e {tmp}/wanclash ] && {{ cat {tmp}/wanclash; exit 1; }}; exit 0; }}\necho DIAGTEXT\n"); os.chmod(fakesetup, 0o755)
fakesvc = os.path.join(tmp, "pisoportal.init"); open(fakesvc, "w").write(f"#!/bin/sh\necho \"$1\" >> {tmp}/service.log\n"); os.chmod(fakesvc, 0o755)
fakends = os.path.join(tmp, "ndsctl"); open(fakends, "w").write(f"""#!/bin/sh
[ "$1" = deauth ] && {{ echo "$2" >> {tmp}/deauth.log; exit 0; }}
[ "$1" = json ] && printf '{{"client_list_length":"3","clients":{{\n"aa":{{\n  "state":"Authenticated",\n}},\n"bb":{{\n  "state":"Preauthenticated",\n}},\n"cc":{{\n  "state":"Authenticated",\n}}}}}}\n'
""")
os.chmod(fakends, 0o755)
open(f"{tmp}/leases", "w").write("1791858999 aa 10.0.30.1 Rowels-Phone 01:aa\n")
conf = os.path.join(tmp, "mon.conf")
open(conf, "w").write(f"TG_TOKEN=T0K\nTG_CHAT=111\nSITE_NAME=Shop1\nREPORT_HOUR=0\nHEALTHCHECK_URL=http://127.0.0.1:{port}/hc\n")
np = ",".join(filter(None, (os.environ.get("no_proxy", ""), "127.0.0.2")))
NP = ",".join(filter(None, (os.environ.get("NO_PROXY", ""), "127.0.0.2")))
env = dict(os.environ, no_proxy=np, NO_PROXY=NP, PISO_SETUP_CONF=f"{tmp}/setup.conf", PISO_MONITOR_CONF=conf, TG_API=f"http://127.0.0.1:{port}", MON_STATE=f"{tmp}/state", PORTAL=fake,
           PISO_SETUP=fakesetup, LOGREAD=fakelog, BOX_DOWN_AFTER="2", PORTAL_SERVICE=fakesvc, NDSCTL=fakends,
           PING_URL=f"http://127.0.0.1:{port}/ping", PING_TIMEOUT="1", ALERTS_FILE=f"{tmp}/alerts.txt", DF=f"{tmp}/df.sh",
           BOX_STATUS_URL=f"http://127.0.0.1:{port}/api/status", SETTINGS_FILE=f"{tmp}/settings.txt", DATA_DIR=tmp,
           DHCP_LEASES=f"{tmp}/leases", PHONE_CHECK_SECONDS="0", PHONE_DOWN_AFTER="2")
open(f"{tmp}/df.sh", "w").write("#!/bin/sh\necho 'Filesystem 1K-blocks Used Available Use% Mounted on'\necho \"overlayfs:/overlay 14000 1000 $(cat " + tmp + "/free 2>/dev/null || echo 9000) 7% /overlay\"\n")
os.chmod(f"{tmp}/df.sh", 0o755)


def mon(*a):
    return subprocess.run(["sh", MON, *a], env=env, capture_output=True, text=True, timeout=60)


def texts():
    return [t for _, t in sent]


mon("tick")
check(any("router started" in t.lower() for t in texts()) and all(c == "111" and t.startswith("<b>Shop1") for c, t in sent), f"boot message: {sent}")
check(any("daily report" in t.lower() and "total 120" in t for t in texts()), "daily report sent once the hour is reached")
check(hits == ["hc"], "dead-man ping sent")
n = len(sent); mon("tick")
check(not any("daily report" in t.lower() for t in texts()[n:]) and not any("router started" in t.lower() for t in texts()[n:]), "boot message and daily report are sent once")
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
# feature 046 (SU-05): a modem plugged in after the setup whose network overlaps the router's own: one alert (checked hourly: clear the stamp)
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
check(any("/reboot_confirm" in t for t in texts()[n:]), "/reboot asks for confirmation first")
queue.append({"update_id": 9, "message": {"chat": {"id": 111}, "text": "/restart"}})
n = len(sent); mon("poll", "1")
check(os.path.exists(f"{tmp}/service.log") and open(f"{tmp}/service.log").read() == "restart\n" and any("portal restarted" in t.lower() for t in texts()[n:]),
      "/restart restarts the portal service (pisoportal)")
queue.append({"update_id": 10, "message": {"chat": {"id": 111}, "text": "/status"}})
n = len(sent); mon("poll", "1")
st = "\n".join(texts()[n:])
check("2 online" in st and "Coin Box:</b> Online" in st and "Mismatch (₱9 vs box ₱3)" in st, "/status counts the guests openNDS has let online: " + st)
open(os.path.join(tmp, "x-should-not-appear"), "w").close()
queue.append({"update_id": 11, "message": {"chat": {"id": 111}, "text": "/report *"}})
n = len(sent); r = subprocess.run(["sh", MON, "poll", "1"], env=env, capture_output=True, text=True, timeout=60, cwd=tmp)
check(any("REPORT days=1" in t for t in texts()[n:]), "a '*' in a command is a word, not a list of files")
queue.append({"update_id": 12, "message": {"chat": {"id": 111}, "text": "/update"}})
n = len(sent); mon("poll", "1"); time.sleep(0.5)
check(any("signed updates" in t for t in texts()[n:]) and os.path.exists(f"{tmp}/setup.calls") and open(f"{tmp}/setup.calls").read() == "self-update\n",
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
# ---- feature 044: update states the update scripts recorded are shown as alerts, not as Telegram noise ----------------------------------
def set_updates(router="", box=""):
    """The setup conf as the update scripts leave it (state|latest|since|running|detail), plus secrets that must never be repeated."""
    open(f"{tmp}/setup.conf", "w").write("GW_KEY='ab" + "cd" * 31 + "'\nBOX_ADMIN_PASS='boxpw-secret'\n"
                                         + (f"UPDATE_STATE_ROUTER='{router}'\n" if router else "") + (f"UPDATE_STATE_BOX='{box}'\n" if box else ""))


def upd_tick():
    if os.path.exists(f"{tmp}/state/lastupdates"):
        os.remove(f"{tmp}/state/lastupdates")
    n = len(sent)
    mon("tick")
    return texts()[n:], open(f"{tmp}/alerts.txt").read() if os.path.exists(f"{tmp}/alerts.txt") else ""


NOW = int(time.time())
OLD, FRESH = NOW - 90000, NOW - 3600
set_updates("current|1.5.27|%d|1.5.27|" % FRESH, "current|3.12.4|%d|3.12.4|" % FRESH)
t_, a_ = upd_tick()
check(not any("update" in x.lower() for x in t_), f"044: up to date: no update alert: {t_}")
set_updates("waiting|1.5.28|%d|1.5.27|customers are online, so it installs at the next quiet moment" % FRESH)
t_, a_ = upd_tick()
check(not any("quiet moment" in x for x in t_) and "quiet moment" not in a_, "044: waiting for less than a day: no alert")
set_updates("", "waiting|3.12.5|%d|3.12.4|a phone has time running, so it installs at the next quiet moment" % OLD)
t_, a_ = upd_tick()
check(any("coin box" in x and "3.12.5" in x and "waited" in x and "piso-setup box-update" in x for x in t_) and "waited" in a_, f"044: the coin box has waited over a day: one alert with the command: {t_}")
t2, a2 = upd_tick()
check(not t2 and a2.count("waited") == 1, "044: and it is not repeated every hour")
set_updates("off|1.5.28|%d|1.5.27|automatic updates are off: send /update in Telegram" % OLD)
t_, a_ = upd_tick()
check(any("router" in x and "1.5.28" in x and "Automatic updates are off" in x for x in t_), f"044: automatic updates off for over a day: alert for the router: {t_}")
set_updates("failed|1.5.28|%d|1.5.27|the update to 1.5.28 failed and was undone (the health check command failed)" % FRESH)
t_, a_ = upd_tick()
check(not t_ and "1.5.28" in a_ and "did not finish" in a_, f"044: a failure goes to alerts.txt at once and not to Telegram again: {t_} {a_[-200:]}")
check("boxpw-secret" not in a_ and "abcdcd" not in a_ and not any("boxpw-secret" in x for x in texts()), "044: no secret reaches alerts.txt or Telegram")
before = open(f"{tmp}/alerts.txt").read()
set_updates("rollout|1.5.28|%d|1.5.27|" % OLD, "newer|3.12.4|%d|3.13.0|" % OLD)
t_, a_ = upd_tick()
check(not t_ and a_ == before, "044: a staged rollout and a box newer than the website never alert")
set_updates("unknown||%d||the website cannot be reached" % OLD, "available|3.12.5|%d|3.12.4|" % OLD)
t_, a_ = upd_tick()
check(not t_ and a_ == before, f"044: not checked and merely available never alert: {t_}")
set_updates("current|1.5.28|%d|1.5.28|" % FRESH)
upd_tick()
set_updates("failed|1.5.29|%d|1.5.28|the download failed" % FRESH)
t_, a_ = upd_tick()
check("1.5.29" in a_ and "the download failed" in a_, "044: after it recovered, a new failure is shown at once again")
os.remove(f"{tmp}/setup.conf")
t_, a_ = upd_tick()
check(not t_, "044: no setup conf at all: nothing happens")

# ---- the monitor asks the portal where the portal really listens (feature 052) ------------------------------------------------------------
# The installer binds the portal to the guest network's address only (PORTAL_BIND), so asking 127.0.0.1 never gets an answer: the monitor used
# to restart a healthy portal every ten minutes and its alert counted failed minutes without end. These runs leave PING_URL unset, as a router does.
def listening(host):
    sv = ThreadingHTTPServer((host, 0), H)
    threading.Thread(target=sv.serve_forever, daemon=True).start()
    return sv, sv.server_address[1]


def own_run(name, conf_lines):
    d = os.path.join(tmp, name); os.makedirs(d, exist_ok=True)
    cc = os.path.join(d, "coinslot.conf"); open(cc, "w").write("".join(l + "\n" for l in conf_lines))
    svc = os.path.join(d, "svc.sh"); open(svc, "w").write(f"#!/bin/sh\necho \"$1\" >> {d}/service.log\n"); os.chmod(svc, 0o755)
    e2 = {k: v for k, v in env.items() if k not in ("PING_URL", "PORTAL_PORT")}
    e2.update(COINSLOT_CONF=cc, MON_STATE=f"{d}/state", ALERTS_FILE=f"{d}/alerts.txt", PORTAL_SERVICE=svc, PISO_MONITOR_CONF=os.path.join(d, "none.conf"))

    def run(n=1):
        for _ in range(n):
            subprocess.run(["sh", MON, "tick"], env=e2, capture_output=True, text=True, timeout=60)

    def count(): return open(f"{d}/service.log").read().count("restart") if os.path.exists(f"{d}/service.log") else 0
    def al(): return open(f"{d}/alerts.txt").read() if os.path.exists(f"{d}/alerts.txt") else ""
    def fails(): return open(f"{d}/state/pingfails").read().strip() if os.path.exists(f"{d}/state/pingfails") else "?"
    return run, count, al, fails


sv2, port2 = listening("127.0.0.2")
run, count, al, fails = own_run("guestbind", [f"PORTAL_BIND='127.0.0.2'", f"PORTAL_PORT='{port2}'"])
run(5)
check(count() == 0 and "did not answer" not in al() and fails() == "0", f"052: a portal bound to the guest address only is pinged there, and is healthy: restarts={count()} fails={fails()} {al()!r}")
sv2.shutdown()

sv3, port3 = listening("127.0.0.1")
for label, lines in (("bound to every address", [f"PORTAL_BIND='0.0.0.0'", f"PORTAL_PORT='{port3}'"]), ("no bind set (an older setup)", [f"PORTAL_PORT='{port3}'"]),
                     ("bind set to the loopback address", [f"PORTAL_BIND='127.0.0.1'", f"PORTAL_PORT='{port3}'"])):
    run, count, al, fails = own_run("loop" + str(abs(hash(label)) % 1000), lines)
    run(5)
    check(count() == 0 and fails() == "0", f"052: {label}: pinged on 127.0.0.1 and healthy: restarts={count()} fails={fails()}")

# a portal that really hangs is still restarted, and the count of failed minutes starts again after a restart
sv4, port4 = listening("127.0.0.2")
run, count, al, fails = own_run("hang", [f"PORTAL_BIND='127.0.0.2'", f"PORTAL_PORT='{port4}'"])
run(2)
open(f"{tmp}/pinghang", "w").close()
run(2)
check(count() == 0, "052: two failed pings are still not enough")
run(1)
check(count() == 1 and "did not answer for 3 minutes" in al(), f"052: the third failed ping restarts it once, as before: {count()} {al()!r}")
check(fails() == "0", f"052: and the count of failed minutes starts again after the restart: {fails()}")
run(3)
check(count() == 1 and "did not answer" in al() and al().count("did not answer") == 1, "052: the next ticks restart nothing and say nothing new")
os.remove(f"{tmp}/pinghang")
run(1)
check("answers again" in al() and fails() == "0", f"052: and it is noted when the portal answers again: {al()!r}")
sv4.shutdown(); sv3.shutdown()

# ---- primary Telegram path: phone checks, outbox, and remote management commands -------------------------------------------
def write_box(devices, unhandled=0, pulses=0):
    open(f"{tmp}/boxstatus.json", "w").write(json.dumps({
        "orphans": {"unhandled": unhandled, "pulses": pulses},
        "wifi": {"rssi": -55, "status": "Excellent"},
        "devices": devices,
    }))


write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": True, "time": 600, "battery": 80, "charging": False},
    {"slotNum": 2, "isBound": True, "active": True, "name": "PisoPhone 2", "online": True, "time": 0, "battery": 90, "charging": True},
    {"slotNum": 3, "isBound": False, "active": True, "name": "PisoPhone 3", "online": False, "time": 0, "battery": -1, "charging": False},
])
n = len(sent); mon("tick")
check(len(sent) == n, f"healthy bound phones trigger no alert: {texts()[n:]}")

# idle phone (time=0) going offline must NOT alert
write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": True, "time": 600, "battery": 80, "charging": False},
    {"slotNum": 2, "isBound": True, "active": True, "name": "PisoPhone 2", "online": False, "time": 0, "battery": 90, "charging": False},
])
n = len(sent); mon("tick"); mon("tick"); mon("tick")
check(len(sent) == n, f"idle phone offline triggers no alert: {texts()[n:]}")

# phone with paid time left going offline alerts after PHONE_DOWN_AFTER checks
write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": False, "time": 600, "battery": 80, "charging": False},
    {"slotNum": 2, "isBound": True, "active": True, "name": "PisoPhone 2", "online": False, "time": 0, "battery": 90, "charging": False},
])
n = len(sent); mon("tick")
check(len(sent) == n, "one missed phone check does not alert")
mon("tick")
check(any("PisoPhone 1</b> (10m left)" in t or "PisoPhone 1 (10m left)" in t for t in texts()[n:]), f"paid-time phone offline alert: {texts()[n:]}")
n = len(sent); mon("tick")
check(len(sent) == n, "phone offline alert is not repeated")

# recovery + low battery (unplugged <= 15%) + orphan coins
write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": True, "time": 540, "battery": 12, "charging": False},
    {"slotNum": 2, "isBound": True, "active": True, "name": "PisoPhone 2", "online": False, "time": 0, "battery": 90, "charging": False},
], unhandled=2, pulses=10)
n = len(sent); mon("tick")
batch = texts()[n:]
check(any("back online" in t and "PisoPhone 1" in t for t in batch), f"phone back online alert: {batch}")
check(any("battery is low (12%" in t for t in batch), f"low battery alert: {batch}")
check(any("2 unhandled coin payment" in t for t in batch), f"orphan coins alert: {batch}")
n = len(sent); mon("tick")
check(len(sent) == n, f"battery and orphan alerts are rate-limited: {texts()[n:]}")

# 5-minute polling interval throttling
env_300 = dict(env, PHONE_CHECK_SECONDS="300")
open(f"{tmp}/state/lastphones", "w").write(str(int(time.time())))
write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": True, "time": 540, "battery": 5, "charging": False},
])
if os.path.exists(f"{tmp}/state/alert.bat1"): os.remove(f"{tmp}/state/alert.bat1")
n = len(sent)
subprocess.run(["sh", MON, "tick"], env=env_300, capture_output=True, text=True, timeout=60)
check(len(sent) == n, "PHONE_CHECK_SECONDS=300 skips phone polling inside 5-min window")

# restore clean state for commands
write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": True, "time": 540, "battery": 80, "charging": False},
    {"slotNum": 2, "isBound": True, "active": True, "name": "PisoPhone 2", "online": False, "time": 0, "battery": 90, "charging": False},
])
mon("tick")

# outbox queuing & bounded 3-message flush
open(f"{tmp}/tgdown", "w").close()
if os.path.exists(f"{tmp}/state/lastreport"): os.remove(f"{tmp}/state/lastreport")
n = len(sent); mon("tick")
check(len(sent) == n and os.path.exists(f"{tmp}/state/outbox") and "Daily Report" in open(f"{tmp}/state/outbox").read(),
      "failed one-shot notification is queued in outbox")
open(f"{tmp}/state/outbox", "w").write("queued 1\nqueued 2\nqueued 3\nqueued 4\n")
os.remove(f"{tmp}/tgdown")
n = len(sent); mon("tick")
flushed = texts()[n:]
check(len(flushed) == 3 and "queued 1" in flushed[0] and "queued 3" in flushed[2], f"outbox flushes max 3 messages per tick: {flushed}")
check(open(f"{tmp}/state/outbox").read().strip() == "queued 4", "4th message remains for next tick")
n = len(sent); mon("tick")
check(any("queued 4" in t for t in texts()[n:]) and not os.path.exists(f"{tmp}/state/outbox"), "remaining outbox message flushed on next tick")

# new commands: /phones, /status, /clients, /kick, /settings, /autoupdate, /pricing
queue.append({"update_id": 20, "message": {"chat": {"id": 111}, "text": "/phones"}})
queue.append({"update_id": 21, "message": {"chat": {"id": 111}, "text": "/status"}})
queue.append({"update_id": 22, "message": {"chat": {"id": 111}, "text": "/clients"}})
n = len(sent); mon("poll", "1")
r20 = texts()[n:]
check(any("Box Wi-Fi: Excellent (-55 dBm)" in t and "<b>PisoPhone 1</b>" in t and "Idle" in t for t in r20), f"/phones command: {r20}")
check(any("1/2 online" in t and "REPORT days=1 total 120" in t for t in r20), f"/status includes phones and sales: {r20}")
check(any("1. <b>Rowels-Phone</b>  /kick_1" in t and "2. <b>cc</b>  /kick_2" in t for t in r20), f"/clients lists guests concisely with 1-tap /kick links: {r20}")

queue.append({"update_id": 23, "message": {"chat": {"id": 111}, "text": "/kick_1"}})
n = len(sent); mon("poll", "1")
check(any("/kick_confirm" in t and "Rowels-Phone" in t and "(<code>aa</code>)" in t for t in texts()[n:]), f"/kick_1 prompts for confirmation: {texts()[n:]}")
queue.append({"update_id": 24, "message": {"chat": {"id": 111}, "text": "/kick_confirm"}})
n = len(sent); mon("poll", "1")
check(any("Disconnected <b>Rowels-Phone</b>" in t for t in texts()[n:]) and open(f"{tmp}/deauth.log").read().strip() == "aa",
      "/kick_confirm disconnects the guest via ndsctl deauth")

queue.append({"update_id": 25, "message": {"chat": {"id": 111}, "text": "/settings"}})
queue.append({"update_id": 26, "message": {"chat": {"id": 111}, "text": "/set_voucher_off"}})
queue.append({"update_id": 27, "message": {"chat": {"id": 111}, "text": "/set_send_off"}})
queue.append({"update_id": 28, "message": {"chat": {"id": 111}, "text": "/settings report_hour 19"}})
queue.append({"update_id": 29, "message": {"chat": {"id": 111}, "text": "/autoupdate off"}})
n = len(sent); mon("poll", "1")
r25 = texts()[n:]
check(any("Vouchers: <b>on</b>  (/set_voucher_off)" in t and "File Send: <b>on</b>  (/set_send_off)" in t for t in r25), f"/settings shows concise single toggle per row: {r25}")
stxt = open(f"{tmp}/settings.txt").read()
check("VOUCHER_CHOICE=off" in stxt and "SEND_ENABLED=off" in stxt, f"settings.txt updated: {stxt}")
check("REPORT_HOUR='19'" in open(conf).read(), "report_hour updated in monitor conf")
check("auto-update off" in open(f"{tmp}/setup.calls").read(), "/autoupdate off delegates to piso-setup")

# /pricing validation, staging, confirmation, speed, and reset
queue.append({"update_id": 30, "message": {"chat": {"id": 111}, "text": "/pricing endurance 5:100"}})
n = len(sent); mon("poll", "1")
check(any("Invalid Endurance tiers" in t for t in texts()[n:]) and not os.path.exists(f"{tmp}/state/pricing.ask"),
      "Endurance pricing without 1-peso tier is rejected")

queue.append({"update_id": 31, "message": {"chat": {"id": 111}, "text": "/pricing hyper 1:10 5:60"}})
n = len(sent); mon("poll", "1")
check(any("/pricing_confirm" in t for t in texts()[n:]) and os.path.exists(f"{tmp}/state/pricing.ask"),
      "/pricing stages change for confirmation")
queue.append({"update_id": 32, "message": {"chat": {"id": 111}, "text": "/pricing_confirm"}})
n = len(sent); mon("poll", "1")
check(any("Updated <b>hyper</b>" in t for t in texts()[n:]) and "HYPER_TIERS=1:10 5:60" in open(f"{tmp}/settings.txt").read(),
      "/pricing_confirm applies HYPER_TIERS")

queue.append({"update_id": 33, "message": {"chat": {"id": 111}, "text": "/pricing speed 4000 1500"}})
queue.append({"update_id": 34, "message": {"chat": {"id": 111}, "text": "/pricing_confirm"}})
mon("poll", "1")
stxt2 = open(f"{tmp}/settings.txt").read()
check("ENDURANCE_DOWN_KBPS=4000" in stxt2 and "ENDURANCE_UP_KBPS=1500" in stxt2, f"/pricing speed applied: {stxt2}")

queue.append({"update_id": 35, "message": {"chat": {"id": 111}, "text": "/pricing_reset"}})
queue.append({"update_id": 36, "message": {"chat": {"id": 111}, "text": "/pricing_confirm"}})
mon("poll", "1")
stxt3 = open(f"{tmp}/settings.txt").read()
check("HYPER_TIERS=" not in stxt3 and "ENDURANCE_DOWN_KBPS=" not in stxt3 and "VOUCHER_CHOICE=off" in stxt3,
      f"/pricing_reset clears only pricing overrides: {stxt3}")

# ---- HTML 400 fallback & clean plain-text alerts.txt ---------------------------------------------------------------------
open(f"{tmp}/failhtml", "w").close()
queue.append({"update_id": 37, "message": {"chat": {"id": 111}, "text": "/help"}})
n = len(sent)
mon("poll", "1")
os.remove(f"{tmp}/failhtml")
check(len(sent) == n + 1 and "<b>" not in sent[-1][1] and "Commands" in sent[-1][1],
      f"HTML 400 triggers automatic plain-text fallback: {sent[-1:]}")
check(all("<b>" not in l and "</b>" not in l for l in alerts()), "alerts.txt never contains raw HTML tags")

# ---- PisoWiFi-only (SHOP_PRODUCT=wifi): no mention of PisoPhones, rental phones, or /phones --------------------------------
wd = os.path.join(tmp, "wifi_only"); os.makedirs(wd, exist_ok=True)
w_state = os.path.join(wd, "state"); os.makedirs(w_state, exist_ok=True)
w_conf = os.path.join(wd, "mon.conf")
open(w_conf, "w").write("TG_TOKEN=T0K\nTG_CHAT=111\nREPORT_HOUR=0\n")
w_coins = os.path.join(wd, "coinslot.conf")
open(w_coins, "w").write("SHOP_PRODUCT='wifi'\n")
# Realistic pisoportal & diag outputs including the "(the difference of 0 went to rental phones)" string we must hide!
w_portal = os.path.join(wd, "portal.sh")
open(w_portal, "w").write("""#!/bin/sh
case "$1" in
  box) echo "box answers" ;;
  reconcile) echo "RECONCILE OK ledger=8 box=8 (the difference of 0 went to rental phones)"; exit 0 ;;
  report)
    printf '2026-11-18  endurance   PHP 1       1 payment(s)\\n'
    printf '2026-11-18  hyper       PHP 7       5 payment(s)\\n'
    printf 'Total last %s day(s): PHP 8\\n' "$2"
    printf '  of which voucher codes customers bought with coins: 1 (PHP 1); using a code later adds no income\\n'
    ;;
esac
""")
os.chmod(w_portal, 0o755)
w_setup = os.path.join(wd, "setup.sh")
open(w_setup, "w").write("""#!/bin/sh
[ "$1" = diag ] && {
  printf 'setup: DONE all checks passed\\nPASS  LAN address is 10.0.0.1\\nPASS  coin box answers\\n'
  printf 'Sales record: 1 month kept, Lifetime total: 8 pesos.\\nVmRSS:\\t1032 kB\\nbox 10.0.0.10 answers\\n'
  exit 0
}
exit 0
""")
os.chmod(w_setup, 0o755)
w_env = dict(env, PISO_MONITOR_CONF=w_conf, COINSLOT_CONF=w_coins, PORTAL=w_portal, PISO_SETUP=w_setup,
             MON_STATE=w_state, ALERTS_FILE=os.path.join(wd, "alerts.txt"), POLL_SECONDS="0")

write_box([
    {"slotNum": 1, "isBound": True, "active": True, "name": "PisoPhone 1", "online": False, "time": 600, "battery": 5, "charging": False},
], unhandled=3, pulses=15)
n = len(sent)
subprocess.run(["sh", MON, "tick"], env=w_env, capture_output=True, text=True, timeout=60)
subprocess.run(["sh", MON, "tick"], env=w_env, capture_output=True, text=True, timeout=60)
wt = texts()[n:]
check(any(t.startswith("<b>PisoWiFi") for t in wt) and not any("phone" in t.lower() or "orphan" in t.lower() or "battery" in t.lower() for t in wt),
      f"wifi_only ticks default to PisoWiFi and skip all phone/orphan alerts: {wt}")
check(not os.path.exists(os.path.join(w_state, "phones.cache")), "wifi_only never creates phones.cache")

# setMyCommands on wifi_only: omits /phones and upgrades stale empty commands.set
open(os.path.join(w_state, "offset"), "w").write("40\n")
w_cmdset = os.path.join(w_state, "commands.set")
open(w_cmdset, "w").close()
n_cmds = len(cmds_reg)
p_run = subprocess.Popen(["sh", MON, "run"], env=w_env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
deadline = time.time() + 5
while time.time() < deadline and (len(cmds_reg) == n_cmds or not open(w_cmdset).read().strip()):
    time.sleep(0.05)
os.killpg(p_run.pid, signal.SIGTERM)
try:
    p_run.wait(timeout=3)
except subprocess.TimeoutExpired:
    os.killpg(p_run.pid, signal.SIGKILL)
    p_run.wait()
check(len(cmds_reg) > n_cmds and open(os.path.join(w_state, "commands.set")).read().strip() == "v2-wifi",
      "wifi_only run re-registers commands from empty stamp to v2-wifi")
w_cmd_names = [c["command"] for c in cmds_reg[-1].get("commands", [])] if len(cmds_reg) > n_cmds else []
check("status" in w_cmd_names and "phones" not in w_cmd_names, f"wifi_only setMyCommands omits phones: {w_cmd_names}")

# /status, /help, /phones, /report_7, /reconcile, /diag under wifi_only: crisp cards and zero phone wording
queue.append({"update_id": 40, "message": {"chat": {"id": 111}, "text": "/status"}})
queue.append({"update_id": 41, "message": {"chat": {"id": 111}, "text": "/help"}})
queue.append({"update_id": 42, "message": {"chat": {"id": 111}, "text": "/phones"}})
queue.append({"update_id": 43, "message": {"chat": {"id": 111}, "text": "/report_7"}})
queue.append({"update_id": 44, "message": {"chat": {"id": 111}, "text": "/reconcile"}})
queue.append({"update_id": 45, "message": {"chat": {"id": 111}, "text": "/diag"}})
open(os.path.join(w_state, "offset"), "w").write("40\n")
n = len(sent)
subprocess.run(["sh", MON, "poll", "1"], env=w_env, capture_output=True, text=True, timeout=60)
w_replies = texts()[n:]
check(len(w_replies) == 6, f"wifi_only answered all 6 commands: {len(w_replies)}")
check(all("phone" not in r.lower() and "/phones" not in r.lower() for r in w_replies),
      f"wifi_only replies never mention phones or rental phones: {w_replies}")
check("Coin Box:</b> Online" in w_replies[0] and "<b>₱8</b>" in w_replies[0] and "OK (₱8)" in w_replies[0],
      f"wifi_only /status card formatted cleanly: {w_replies[0]}")
check("Commands" in w_replies[1] and "Commands" in w_replies[2],
      f"wifi_only /help and /phones show Commands menu: {w_replies[1:3]}")
check("• 2026-11-18 · Hyper: <b>₱7</b> (5)" in w_replies[3] and "<b>Total: ₱8</b>" in w_replies[3] and "Vouchers sold: 1 (₱1)" in w_replies[3],
      f"/report_7 formats pisoportal report cleanly: {w_replies[3]}")
check("Balanced" in w_replies[4] and "Ledger: <b>₱8</b> · Coin Box: <b>₱8</b>" in w_replies[4],
      f"/reconcile hides rental-phone noise: {w_replies[4]}")
check("2 checks passed" in w_replies[5] and "1032 kB" in w_replies[5],
      f"/diag summarizes health checks cleanly: {w_replies[5]}")

r_usage = subprocess.run(["sh", MON, "invalidarg"], env=w_env, capture_output=True, text=True, timeout=10)
check("phone" not in r_usage.stdout.lower() and "/phones" not in r_usage.stdout.lower() and "PisoWiFi site" in r_usage.stdout,
      f"wifi_only CLI usage omits phone wording: {r_usage.stdout}")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
