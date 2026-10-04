"""Tests of setup/piso-setup.sh (the one-file router setup) without a router: a fake uci with canned radios, the generated
settings, the payload, the helpers, and the coin box provisioning against a fake box.
Run with:  python3 opennds/tests/test_setup.py"""
import http.server, json, os, re, subprocess, sys, tempfile, threading, base64

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = f"{ROOT}/setup/piso-setup.sh"
failures = checks = 0


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


tmp = tempfile.mkdtemp()
bindir = f"{tmp}/bin"
os.makedirs(bindir)


def fake_uci(radios, ifaces=3, lan=None):
    """radios: {name: band}. The fake answers 'show wireless' and 'get'; every other call is recorded."""
    show = "".join(f"wireless.{r}=wifi-device\nwireless.{r}.band='{b}'\n" for r, b in radios.items())
    show += "".join(f"wireless.default_radio{i}=wifi-iface\n" for i in range(ifaces))
    open(f"{bindir}/uci", "w").write(f"""#!/bin/sh
echo "$*" >> {tmp}/uci.log
[ "$1" = -q ] && shift
case "$1" in
  show) cat << 'EOT'
{show}EOT
  ;;
  get) case "$2" in
{"".join(f"    wireless.{r}.band) echo {b} ;;{chr(10)}" for r, b in radios.items())}{f"    network.lan.ipaddr) echo {lan} ;;{chr(10)}" if lan else ""}    *) exit 1 ;;
  esac ;;
esac
exit 0
""")
    os.chmod(f"{bindir}/uci", 0o755)


def run(*args, env=None, inp=None):
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_CONF=f"{tmp}/conf", PISO_LOG=f"{tmp}/log",
             PISO_STATE=f"{tmp}/state", PISO_SUMMARY=f"{tmp}/summary")
    e.update(env or {})
    return subprocess.run(["sh", SCRIPT, *args], env=e, capture_output=True, text=True, timeout=60, input=inp)


def lib(code, env=None):
    """Load the script's functions (no main) and run shell code."""
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_CONF=f"{tmp}/conf2", PISO_LOG=f"{tmp}/log2",
             PISO_STATE=f"{tmp}/state2", PISO_SUMMARY=f"{tmp}/summary2", PISO_SETUP_SOURCE_ONLY="1")
    e.update(env or {})
    return subprocess.run(["sh", "-c", f". {SCRIPT}; {code}"], env=e, capture_output=True, text=True, timeout=60)


# ---- the built file is current and sound ---------------------------------------------------------------------------
r = subprocess.run([sys.executable, f"{ROOT}/tools/build_piso_setup.py", "--check"], capture_output=True, text=True)
check(r.returncode == 0, "setup/piso-setup.sh is up to date with its sources: " + r.stdout + r.stderr)
check(subprocess.run(["sh", "-n", SCRIPT]).returncode == 0, "syntax")
check(os.access(SCRIPT, os.X_OK), "executable")
text = open(SCRIPT).read()
check("\r" not in text and text.startswith("#!/bin/sh"), "LF endings, shell shebang")

# ---- dual-band router: the settings --------------------------------------------------------------------------------
fake_uci({"radio0": "2g", "radio1": "5g"})
r = run("--dry-run")
out = r.stdout
check(r.returncode == 0, "dry run succeeds: " + r.stderr + out[-300:])
check("network.lan.ipaddr" not in out and "network.lan.netmask" not in out and "network restart" not in text.split("stage1()")[1], "the LAN address is never changed by the script (so SSH stays open)")
inst = text.split("install_packages() {")[1].split("\n}\n")[0]
check("/etc/init.d/opennds stop" in inst, "openNDS is stopped right after it is installed (its default settings would gate the kiosk LAN)")
check(not re.search(r"\bod -|hexdump|xxd", text.split("# ---- payload")[0]), "no tools a stock BusyBox lacks (od, hexdump, xxd)")
check("--stage2" not in text.split("# ---- payload")[0] and "nohup" not in text.split("# ---- payload")[0], "no background stage that outlives the SSH session")
check("set network.guest.ipaddr='192.168.30.1'" in out and "set network.guest.device='br-guest'" in out, "guest network on its own bridge")
for radio in ("radio0", "radio1"):
    check(f"set wireless.kiosk_{radio}.ssid='PisoKiosk'" in out and f"set wireless.kiosk_{radio}.network='lan'" in out, f"PisoKiosk on {radio}")
    check(f"set wireless.guest_{radio}.ssid='PisoWiFi'" in out and f"set wireless.guest_{radio}.encryption='none'" in out and f"set wireless.guest_{radio}.network='guest'" in out,
          f"open PisoWiFi on {radio}, in the guest network")
    check(f"set wireless.{radio}.country='PH'" in out and f"set wireless.{radio}.disabled='0'" in out, f"{radio} on with a country")
check("set wireless.box_ap.device='radio0'" in out and "set wireless.box_ap.hidden='1'" in out and "set wireless.box_ap.ssid='PisoCoinBox'" in out
      and "set wireless.box_ap.key='PisoCoinBox@Setup'" in out, "hidden coin box network on the 2.4 GHz radio only")
check("set wireless.kiosk_radio1.hidden" not in out and out.count("set wireless.box_ap=wifi-iface") == 1, "only one box network")
check("set wireless.radio0.htmode='HT20'" in out and "set wireless.radio1.htmode" not in out, "2.4 GHz uses HT20")
check(out.count("delete wireless.@wifi-iface[") == 3 and "delete wireless.@wifi-iface[2]" in out and out.index("[2]") < out.index("[0]"), "router's own default networks removed (highest first)")
check("macfilter" not in out and "dhcp.pisocoinbox" not in out, "box network is open for pairing until the box's MAC is known")
check("set firewall.guest.forward='REJECT'" in out and "set firewall.guest_wan.dest='wan'" in out and "set firewall.guest_stream.dest_port='8100'" in out, "guests reach the internet only (and the stream port)")
check("set opennds.@opennds[0].gatewayinterface='br-guest'" in out and "flash_coin.sh" in out and "flash_coin_status.sh" in out and "set opennds.@opennds[0].gatewayname='PisoWiFi'" in out,
      "openNDS gates the guest network with the flash coin theme")
check("set firewall.kiosk" not in out, "no separate kiosk firewall zone (the kiosk network is the LAN)")
check("<kiosk password>" in out, "dry run never prints a real password")
# once paired, the box is locked and gets its address
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/paired"})
open(f"{tmp}/paired", "w").write("BOX_MAC='AA:BB:CC:00:11:22'\nGUEST_NAME='Juan Net'\n")
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/paired"})
check("set wireless.box_ap.macfilter='allow'" in r.stdout and "add_list wireless.box_ap.maclist='AA:BB:CC:00:11:22'" in r.stdout
      and "set dhcp.pisocoinbox.ip='10.0.0.10'" in r.stdout, "paired box: MAC allow-list and fixed address")
check("set wireless.guest_radio0.ssid='Juan Net'" in r.stdout, "a renamed customer Wi-Fi keeps its name on re-run")
r = run("--dry-run", env={"GUEST_IP": "192.168.31.1", "COUNTRY": "US"})
check("country='US'" in r.stdout and "ipaddr='192.168.31.1'" in r.stdout, "guest address and country can be overridden")
r = lib('echo "$LAN_IP $BOX_IP"')
check(r.stdout.strip() == "10.0.0.1 10.0.0.10", "default LAN 10.0.0.1, box 10.0.0.10: " + r.stdout)
fake_uci({"radio0": "2g"}, lan="10.0.0.1/24")
r = lib('echo "$LAN_IP $BOX_IP"')
check(r.stdout.strip() == "10.0.0.1 10.0.0.10", "a CIDR LAN address is read correctly: " + r.stdout)
fake_uci({"radio0": "2g"}, lan="192.168.1.1")
r = lib('echo "$LAN_IP $BOX_IP"')
check(r.stdout.strip() == "192.168.1.1 192.168.1.10", "the box address follows the router's own LAN: " + r.stdout)
r = run("--yes", env={"PISO_ROOT": f"{tmp}/nowhere"})
check(r.returncode != 0, "a real run needs root on OpenWrt (not run in the test)")
fake_uci({"radio0": "2g", "radio1": "5g"})

# ---- 2.4 GHz only router, and a router without 2.4 GHz -------------------------------------------------------------------
fake_uci({"radio0": "2g"}, ifaces=1)
r = run("--dry-run")
check("kiosk_radio0" in r.stdout and "radio1" not in r.stdout and "delete wireless.@wifi-iface[0]" in r.stdout, "single-band router works")
fake_uci({"radio0": "5g"})
r = run("--dry-run")
check(r.returncode != 0 and "no 2.4 GHz" in r.stdout, "refuses a router without 2.4 GHz (the box needs it)")
fake_uci({"radio0": "2g", "radio1": "5g"})

# ---- payload ---------------------------------------------------------------------------------------------------------------
root = f"{tmp}/root"
r = lib(f'PISO_ROOT={root}; DRY=1; extract_payload {SCRIPT}', env={"PISO_ROOT": root})
for src, dest in [("opennds/coinslot-listener.sh", "/usr/bin/coinslot-listener.sh"), ("opennds/flash_coin.sh", "/usr/lib/opennds/flash_coin.sh"),
                  ("opennds/flash_coin_lib.sh", "/usr/lib/opennds/flash_coin_lib.sh"), ("opennds/flash_coin_status.sh", "/usr/lib/opennds/flash_coin_status.sh"),
                  ("opennds/flash_fairuse.sh", "/usr/lib/opennds/flash_fairuse.sh"), ("opennds/flash_coin.init", "/etc/init.d/flash_coin")]:
    p = root + dest
    check(os.path.exists(p) and open(p).read() == open(f"{ROOT}/{src}").read(), f"payload {dest} is identical to {src}")
    check(os.path.exists(p) and os.access(p, os.X_OK), f"{dest} is executable")

# ---- stored settings -------------------------------------------------------------------------------------------------------
r = lib('conf_set A "x y"; conf_set A "second"; conf_set B 2; echo "$(conf_get A)|$(conf_get B)"; K=$(secret K 12); echo "$K|$(secret K 12)"; secret G 64 hex')
a, b, c = r.stdout.strip().split("\n")
check(a == "second|2", "conf_set replaces, conf_get reads: " + a)
k1, k2 = b.split("|")
check(len(k1) == 12 and k1 == k2 and re.fullmatch(r"[a-hjkmnp-zA-HJ-NP-Z2-9]+", k1), "secrets are random, look-alike free and remembered: " + b)
check(re.fullmatch(r"[0-9a-f]{64}", c), "gateway key is 64 hex characters")
check(oct(os.stat(f"{tmp}/conf2").st_mode)[-3:] == "600", "settings file is private")

# ---- wifi-name -----------------------------------------------------------------------------------------------------------
open(f"{tmp}/uci.log", "w").close()
fake_uci({"radio0": "2g", "radio1": "5g"})
for bad in ["", "PisoKiosk", "PisoCoinBox", 'He said "hi"', "a$b", "x" * 33]:
    r = run("wifi-name", bad)
    check(r.returncode != 0, f"wifi-name refuses {bad!r}")

# ---- coin box provisioning against a fake box ---------------------------------------------------------------------------
class Box(http.server.BaseHTTPRequestHandler):
    admin = "Coinslot@Setup"
    key = None
    log = []

    def log_message(self, *a):
        pass

    def _auth(self):
        want = "Basic " + base64.b64encode(f"admin:{Box.admin}".encode()).decode()
        return self.headers.get("Authorization") == want

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = dict(x.split("=", 1) for x in self.rfile.read(n).decode().split("&") if "=" in x)
        Box.log.append((self.path, body))
        if not self._auth():
            self.send_response(401); self.end_headers(); return
        if self.path == "/save" and "admin_pw" in body:
            Box.admin = body["admin_pw"]
            self.send_response(200); self.end_headers(); self.wfile.write(b"{}"); return
        if self.path == "/api/gateway/config":
            Box.key = body.get("key")
            self.send_response(200); self.end_headers(); self.wfile.write(b'{"success":true,"configured":true}'); return
        self.send_response(404); self.end_headers()

    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'{"error":"GATEWAY_DISABLED"}')


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Box)
threading.Thread(target=srv.serve_forever, daemon=True).start()
addr = f"127.0.0.1:{srv.server_address[1]}"
prov = 'conf_set BOX_ADMIN_PASS_NEW "NewPassw0rdXYZ"; provision_box NewPassw0rdXYZ ' + "ab" * 32 + "; echo rc=$?"
r = lib(prov, env={"BOX_IP": addr})
check("rc=0" in r.stdout and Box.admin == "NewPassw0rdXYZ" and Box.key == "ab" * 32, "default password replaced and key set: " + r.stdout + r.stderr)
check("gateway key is set" in r.stdout, "reports the key")
r = lib('provision_box NewPassw0rdXYZ ' + "cd" * 32 + "; echo rc=$?", env={"BOX_IP": addr})   # re-run with the stored password
check("rc=0" in r.stdout and Box.key == "cd" * 32, "a re-run uses the stored password: " + r.stdout)
Box.admin = "SomethingElse1"
r = lib('provision_box NewPassw0rdXYZ ' + "ef" * 32 + "; echo rc=$?", env={"BOX_IP": addr, "PISO_CONF": f"{tmp}/fresh"})
check("rc=0" not in r.stdout and "factory" in r.stdout.lower(), "a box with an unknown password gives a clear message: " + r.stdout)
r = lib('provision_box NewPassw0rdXYZ ' + "ef" * 32 + "; echo rc=$?", env={"BOX_IP": addr, "PISO_CONF": f"{tmp}/fresh", "BOX_ADMIN_PASSWORD": "SomethingElse1"})
check("rc=0" in r.stdout and Box.key == "ef" * 32, "BOX_ADMIN_PASSWORD lets setup adopt an already set-up box: " + r.stdout)

# ---- finding the box on its own network --------------------------------------------------------------------------------------
open(f"{bindir}/iwinfo", "w").write("""#!/bin/sh
if [ -z "$1" ]; then
cat << 'EOT'
phy0-ap0  ESSID: "PisoKiosk"
          Access Point: 00:11:22:33:44:55
phy0-ap2  ESSID: "PisoCoinBox"
          Access Point: 00:11:22:33:44:57
EOT
elif [ "$1" = phy0-ap2 ] && [ "$2" = assoclist ]; then
cat << 'EOT'
aa:bb:cc:dd:ee:09  -57 dBm / unknown (SNR 36)  1000 ms ago
	RX: 6.0 MBit/s                                  12 Pkts.
EOT
fi
""")
os.chmod(f"{bindir}/iwinfo", 0o755)
r = lib("echo \"$(box_ifname) $(box_station)\"")
check(r.stdout.strip() == "phy0-ap2 AA:BB:CC:DD:EE:09", "finds the box's interface and the joined station: " + r.stdout + r.stderr)

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
