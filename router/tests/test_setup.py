"""Tests of setup/piso-setup.sh (the one-file router setup) without a router: a fake uci with canned radios, the generated
settings, the payload, the helpers, and the coin box provisioning against a fake box.
Run with:  python3 router/tests/test_setup.py"""
import http.server, json, os, re, shutil, subprocess, sys, tempfile, threading, base64

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = f"{ROOT}/setup/piso-setup.sh"
PAYLOAD_MARK = "\n# ---- payload: the portal files"   # the line where the embedded files start
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
check("flow_offloading='0'" in out and "flow_offloading_hw='0'" in out, "flow offloading is switched off (it can bypass the speed caps)")
check("set dhcp.guest.ra='disabled'" in out and "set dhcp.guest.dhcpv6='disabled'" in out, "guests get no IPv6 (openNDS gates IPv4 only)")
check("set dropbear.@dropbear[0].Interface='lan'" in out, "SSH answers on the kiosk LAN only (openNDS always lets guests reach port 22)")
os.makedirs(f"{tmp}/fakeroot/etc/config", exist_ok=True)
open(f"{tmp}/fakeroot/etc/config/uhttpd", "w").close()
r2 = run("--dry-run", env={"PISO_ROOT": f"{tmp}/fakeroot"})
check("add_list uhttpd.main.listen_https='10.0.0.1:443'" in r2.stdout and "add_list uhttpd.main.listen_http='10.0.0.1:80'" in r2.stdout,
      "and so does LuCI, when it is installed")
check("uhttpd" not in out, "no LuCI settings on a router without LuCI")
r2 = run("--dry-run", env={"GUEST_SSID": "Maria Free WiFi"})
check("set wireless.guest_radio0.ssid='Maria Free WiFi'" in r2.stdout, "the public Wi-Fi name can be chosen at setup: " + r2.stderr[-200:])
check("set wireless.kiosk_radio0.ssid='PisoKiosk'" in r2.stdout and "set wireless.kiosk_radio0.hidden='1'" in r2.stdout and "set wireless.kiosk_radio1.hidden='1'" in r2.stdout,
      "the kiosk network is hidden on both bands and keeps its fixed name")
r2 = run("--dry-run", env={"GUEST_SSID": "PisoKiosk"})
check(r2.returncode != 0 and "cannot be PisoKiosk" in (r2.stdout + r2.stderr), "the public name cannot be the kiosk name")
r2 = run("--dry-run", env={"GUEST_SSID": "x" * 33})
check(r2.returncode != 0 and "at most 32" in (r2.stdout + r2.stderr), "a name over 32 characters is refused")
r2 = run("--dry-run", env={"GUEST_SSID": "it's"})
check(r2.returncode != 0 and "may not contain" in (r2.stdout + r2.stderr), "a name with a quote is refused")
check("ca-bundle" in text and "cmd_telegram" in text and "#@@FILE /usr/bin/piso-monitor.sh 755" in text, "the monitor and its https certificates are part of the setup")
check("network.lan.ipaddr" not in out and "network.lan.netmask" not in out and "network restart" not in text.split("stage1()")[1], "the LAN address is never changed by the script (so SSH stays open)")
inst = text.split("install_packages() {")[1].split("\n}\n")[0]
check('[ "$p" = opennds ] && stop_opennds' in inst and inst.index("stop_opennds") < inst.index("done"), "openNDS is stopped right after it is installed, inside the install loop (its default settings would gate the kiosk LAN)")
stopper = text.split("stop_opennds() {")[1].split("\n}\n")[0]
check("/etc/init.d/opennds stop" in stopper and "NDS_RESTORE=1" in stopper and "br-guest" in stopper, "a re-run remembers a working guest portal so a failed run can start it again")
check("NDS_RESTORE" in text.split("die() {")[1].split("\n}\n")[0], "a failed run restores the guest portal it stopped")
pre = text.split(PAYLOAD_MARK)[0]
r = lib('conf_set ROOT_PASS "abcdefgh123"; conf_set ROOT_PASS_SET 0; write_summary; cat "$SUMMARY"; conf_set ROOT_PASS_SET 1; write_summary; cat "$SUMMARY"')
first, second = r.stdout.split("PisoPhone setup summary")[1:3]
check("abcdefgh123" not in first and "NOT SET" in first and "set-password" in first, "a password that was not applied is never shown as the router password")
check("abcdefgh123" in second, "an applied password is shown")
check("ROUTER (SSH / LuCI) PASSWORD" in pre and 'cat "$SUMMARY"' in pre.split("stage2() {")[1].split("\n}\n")[0], "the router password and the whole summary are shown on screen")
r = lib('conf_set ROOT_PASS ""; ROOT_PASSWORD=short; ASSUME_YES=0; choose_passwords; echo rc=$?', env={"ROOT_PASSWORD": "short"})
check("at least 8" in r.stdout, "a too-short router password is refused: " + r.stdout)
r = lib('rm -f "$CONF"; ROOT_PASSWORD="my-own-pass"; choose_passwords; conf_get ROOT_PASS', env={"ROOT_PASSWORD": "my-own-pass"})
check(r.stdout.strip().endswith("my-own-pass"), "ROOT_PASSWORD is used as the router password: " + r.stdout)
r = lib('rm -f "$CONF"; KIOSK_PASSWORD="kiosk-pass-1"; BOX_NEW_ADMIN_PASSWORD="boxadmin-77"; ROOT_PASSWORD="root-pass-9"; choose_passwords; echo "$(conf_get KIOSK_PASS)|$(conf_get BOX_ADMIN_PASS_NEW)|$(conf_get ROOT_PASS)"',
        env={"KIOSK_PASSWORD": "kiosk-pass-1", "BOX_NEW_ADMIN_PASSWORD": "boxadmin-77", "ROOT_PASSWORD": "root-pass-9"})
check(r.stdout.strip().endswith("kiosk-pass-1|boxadmin-77|root-pass-9"), "every password can be chosen: " + r.stdout)
r = lib('rm -f "$CONF"; choose_passwords; echo "[$(conf_get KIOSK_PASS)][$(conf_get BOX_ADMIN_PASS_NEW)]"')
check(r.stdout.strip().endswith("[][]"), "without a choice nothing is stored here (they are generated later): " + r.stdout)
for bad, why in [("has space", "spaces"), ("it'sbad12", "a quote"), ("short", "length")]:
    r = lib('rm -f "$CONF"; choose_passwords; echo rc=$?', env={"KIOSK_PASSWORD": bad})
    check("rc=0" not in r.stdout and ("may not contain" in r.stdout or "at least" in r.stdout), f"a Wi-Fi password with {why} is refused: " + r.stdout)
check("ask_password BOX_ADMIN_PASS_NEW" in text and "ask_password KIOSK_PASS" in text and "super-admin" in text, "all the operating passwords are chosen in the setup")
check(not re.search(r"\bod -|hexdump|xxd", text.split(PAYLOAD_MARK)[0]), "no tools a stock BusyBox lacks (od, hexdump, xxd)")
check("--stage2" not in text.split(PAYLOAD_MARK)[0] and "nohup" not in text.split(PAYLOAD_MARK)[0], "no background stage that outlives the SSH session")
check("set network.guest.ipaddr='192.168.30.1'" in out and "set network.guest.device='br-guest'" in out, "guest network on its own bridge")
for radio in ("radio0", "radio1"):
    check(f"set wireless.kiosk_{radio}.ssid='PisoKiosk'" in out and f"set wireless.kiosk_{radio}.network='lan'" in out, f"PisoKiosk on {radio}")
    check(f"set wireless.guest_{radio}.ssid='PisoWiFi'" in out and f"set wireless.guest_{radio}.encryption='none'" in out and f"set wireless.guest_{radio}.network='guest'" in out,
          f"open PisoWiFi on {radio}, in the guest network")
    check(f"set wireless.{radio}.country='PH'" in out and f"set wireless.{radio}.disabled='0'" in out, f"{radio} on with a country")
check("set wireless.box_ap.device='radio0'" in out and "set wireless.box_ap.hidden='1'" in out and "set wireless.box_ap.ssid='PisoCoinBox'" in out
      and "set wireless.box_ap.key='PisoCoinBox@Setup'" in out, "hidden coin box network on the 2.4 GHz radio only")
check(out.count("set wireless.box_ap=wifi-iface") == 1, "only one box network")
check("set wireless.radio0.htmode='HT20'" in out and "set wireless.radio1.htmode" not in out, "2.4 GHz uses HT20")
check(out.count("delete wireless.@wifi-iface[") == 3 and "delete wireless.@wifi-iface[2]" in out and out.index("[2]") < out.index("[0]"), "router's own default networks removed (highest first)")
check("macfilter" not in out and "dhcp.pisocoinbox" not in out, "box network is open for pairing until the box's MAC is known")
check("set firewall.guest.forward='REJECT'" in out and "set firewall.guest_wan.dest='wan'" in out and "set firewall.guest_stream.dest_port='2080'" in out, "guests reach the internet only (and the portal port)")
check("set opennds.@opennds[0].gatewayinterface='br-guest'" in out and "set opennds.@opennds[0].gatewayname='PisoWiFi'" in out, "openNDS gates the guest network")
check("set opennds.@opennds[0].fasport='2080'" in out and "set opennds.@opennds[0].faspath='/'" in out and "set opennds.@opennds[0].fas_secure_enabled='1'" in out
      and "set opennds.@opennds[0].login_option_enabled='0'" in out and "delete opennds.@opennds[0].themespec_path" in out and "allow tcp port 2080" in out,
      "openNDS forwards new guests to the portal (FAS level 1) and no theme script is used")
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
for src, dest in [("router/piso_monitor.sh", "/usr/bin/piso-monitor.sh"), ("router/piso_monitor.init", "/etc/init.d/piso_monitor"), ("router/pisoportal.init", "/etc/init.d/pisoportal")]:
    p = f"{root}{dest}"
    check(os.path.exists(p) and open(p).read() == open(f"{ROOT}/{src}").read(), f"payload {dest} is identical to {src}")
    check(os.path.exists(p) and os.access(p, os.X_OK), f"{dest} is executable")
pp = f"{root}/usr/bin/pisoportal"
check(os.path.exists(pp) and os.access(pp, os.X_OK) and open(pp, "rb").read() == open(f"{ROOT}/tools/pisoportal/bin/pisoportal-mipsel", "rb").read(), "the portal program is decoded from the payload byte for byte")
check(not [f for f in os.listdir(f"{root}/usr/bin") if f.endswith((".b64", ".new", ".clean"))], "no temporary files are left behind: " + str(os.listdir(f"{root}/usr/bin")))
installed = f"{tmp}/installed-copy"
r = lib(f'PISO_ROOT={root}2; SELF_PATH={installed}; DRY=1; extract_payload {SCRIPT}')
check(os.path.exists(installed) and not any(l.startswith("#@@") for l in open(installed)) and open(installed).read().startswith("#!/bin/sh")
      and os.path.getsize(installed) < 200_000, "the installed command is the script without the payload (no 1 MB of flash)")
r = lib(f'PISO_ROOT={root}3; DRY=1; extract_payload {installed}; echo rc=$?')
check("rc=0" in r.stdout and "carries no portal files" in r.stdout and not os.path.exists(f"{root}3/usr/bin/pisoportal"), "re-running the installed copy keeps the files on the router")
# the full file copied to the command's own path and run from there: it must not be emptied while it is being read
selfrun = f"{tmp}/selfrun/piso-setup"
os.makedirs(os.path.dirname(selfrun))
shutil.copy(SCRIPT, selfrun)
r = subprocess.run(["sh", "-c", f". {selfrun}; PISO_ROOT={root}4; SELF_PATH={selfrun}; DRY=1; extract_payload {selfrun}; echo rc=$?"],
                   env=dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_SETUP_SOURCE_ONLY="1", PISO_LOG=f"{tmp}/log4"),
                   capture_output=True, text=True, timeout=120)
check("rc=0" in r.stdout and os.path.exists(f"{root}4/usr/bin/pisoportal") and open(selfrun).read().startswith("#!/bin/sh")
      and 1000 < os.path.getsize(selfrun) < 200_000 and not os.path.exists(selfrun + ".new"),
      "run from the command's own path, the setup installs and leaves the command whole: " + r.stdout[-300:])
# a stock OpenWrt BusyBox has neither base64 nor openssl: the awk decoder must give the same program, checked by its sha256
bb = subprocess.run(["sh", "-c", "command -v busybox"], capture_output=True, text=True).stdout.strip()
if bb:
    bbdir = f"{tmp}/bb"
    os.makedirs(bbdir)
    for a in "sh awk sed grep tr rm mv chmod mkdir dirname cut sha256sum printf cat date head tail ln".split():
        os.symlink(bb, f"{bbdir}/{a}")
    # (functions stand in for the missing tools: a BusyBox built with "standalone shell" would find its own applets)
    r = subprocess.run([f"{bbdir}/sh", "-c", f". {SCRIPT}; base64() {{ return 127; }}; openssl() {{ return 127; }}; DRY=1; extract_payload {SCRIPT}"], env={"PATH": bbdir, "PISO_ROOT": f"{tmp}/bbroot", "PISO_SETUP_SOURCE_ONLY": "1",
                       "PISO_SELF_PATH": f"{tmp}/bbroot/piso-setup", "PISO_LOG": f"{tmp}/bblog"}, capture_output=True, text=True, timeout=300)
    got = f"{tmp}/bbroot/usr/bin/pisoportal"
    check(os.path.exists(got) and open(got, "rb").read() == open(f"{ROOT}/tools/pisoportal/bin/pisoportal-mipsel", "rb").read() and "decoding with awk" in r.stdout,
          "BusyBox only (no base64, no openssl): the portal program is decoded by awk, byte for byte: " + r.stdout[-300:] + r.stderr[-300:])
    check(os.access(f"{tmp}/bbroot/etc/init.d/pisoportal", os.X_OK), "and the init scripts are executable (marker parsing without GNU sed extensions)")
else:
    print("note: busybox not installed; the BusyBox-only decoding test was skipped")
# a damaged setup file is refused instead of installing a broken program
bad = f"{tmp}/damaged.sh"
t = open(SCRIPT).read()
i = t.index("\n#@@B64 ") + 1
j = t.index("\n", i) + 200
open(bad, "w").write(t[:j] + ("B" if t[j] != "B" else "C") + t[j + 1:])
os.makedirs(f"{tmp}/badroot/usr/bin")
open(f"{tmp}/badroot/usr/bin/pisoportal", "w").write("the working program")
r = lib(f'PISO_ROOT={tmp}/badroot; DRY=1; extract_payload {bad}; echo rc=$?')
check("rc=" not in r.stdout and "could not decode" in (r.stdout + r.stderr), "a damaged payload (checksum mismatch) stops the setup: " + r.stdout[-200:])
check(open(f"{tmp}/badroot/usr/bin/pisoportal").read() == "the working program", "and the program already on the router is left as it was")

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
        if self.path == "/save" and "wifi_pass" in body:
            Box.wifi = body["wifi_pass"]
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

# ---- the box's own Wi-Fi password -----------------------------------------------------------------------------------------------
Box.wifi = None
open(f"{tmp}/uci.log", "w").close()
r = lib('conf_set BOX_ADMIN_PASS NewPassw0rdXYZ; rotate_box_wifi; echo rc=$?; echo "ROT=$(conf_get BOX_WIFI_ROTATED) KEY=$(conf_get BOX_WIFI_PASS_NEW)"; box_wifi_key',
        env={"BOX_IP": addr, "ROTATE_SETTLE": "0"})
m = re.search(r"KEY=(\S+)", r.stdout)
check("rc=0" in r.stdout and "ROT=1" in r.stdout and m and Box.wifi == m.group(1) and len(m.group(1)) == 20 and m.group(1) != "PisoCoinBox@Setup",
      "the box gets its own random 20-character Wi-Fi password through its API: " + r.stdout + r.stderr)
check(f"set wireless.box_ap.key={Box.wifi}" in open(f"{tmp}/uci.log").read(), "and the router's box network takes the same one")
check(r.stdout.strip().endswith(Box.wifi), "box_wifi_key returns it from then on (a re-run of the setup keeps it)")
Box.wifi = None
r = lib('rotate_box_wifi; echo rc=$?', env={"BOX_IP": addr, "ROTATE_SETTLE": "0"})
check("rc=0" in r.stdout and Box.wifi is None, "a second run changes nothing")
r = lib('conf_set BOX_WIFI_ROTATED 0; box_wifi_key')
check(r.stdout.strip().endswith("PisoCoinBox@Setup"), "before rotation (or after pairing a new box) the built-in password is used")
check("conf_set BOX_WIFI_ROTATED 0" in text.split("pair_box() {")[1].split("\n}\n")[0], "pairing a new box starts from the built-in password")
# a box paired before keeps its own Wi-Fi password: re-pairing offers the built-in one first, then that one, in turn
open(f"{tmp}/uci.log", "w").close()
r = lib('conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; sleep() { :; }; box_up() { return 0; }; '
        'box_station() { [ "$(grep "set wireless.box_ap.key=" ' + tmp + '/uci.log | tail -n 1)" = "set wireless.box_ap.key=OwnPassw0rd1234567" ] && echo AA:BB:CC:DD:EE:01; }; '
        'pair_box; echo "rc=$? ROT=$(conf_get BOX_WIFI_ROTATED) MAC=$(conf_get BOX_MAC)"', env={"PAIR_WAIT": "600"})
ul = open(f"{tmp}/uci.log").read()
check("rc=0 ROT=1 MAC=AA:BB:CC:DD:EE:01" in r.stdout, "a re-paired box that holds its own password joins with it: " + r.stdout + r.stderr)
check(ul.index("set wireless.box_ap.key=PisoCoinBox@Setup") < ul.index("set wireless.box_ap.key=OwnPassw0rd1234567"), "the built-in password is offered first")
open(f"{tmp}/uci.log", "w").close()
r = lib('conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; sleep() { :; }; box_up() { return 0; }; '
        'box_station() { [ "$(grep "set wireless.box_ap.key=" ' + tmp + '/uci.log | tail -n 1)" = "set wireless.box_ap.key=PisoCoinBox@Setup" ] && [ "$(grep -c "set wireless.box_ap.key=" ' + tmp + '/uci.log)" -gt 2 ] && echo AA:BB:CC:DD:EE:02; }; '
        'pair_box; echo "rc=$? ROT=$(conf_get BOX_WIFI_ROTATED) MAC=$(conf_get BOX_MAC)"', env={"PAIR_WAIT": "600"})
check("rc=0 ROT=0 MAC=AA:BB:CC:DD:EE:02" in r.stdout, "a factory-reset box still joins with the built-in password on a later turn: " + r.stdout + r.stderr)
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "10"})
check("rc=0" not in r.stdout and "not on the air" in r.stderr + r.stdout, "a missing box network is reported as such: " + r.stdout + r.stderr)
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "10"})
check("rc=0" not in r.stdout and "did not join" in r.stderr + r.stdout, "a network that is up but never joined keeps the original message: " + r.stdout + r.stderr)
check("rotate_box_wifi" in text.split("stage2() {")[1].split("\n}\n")[0] and "rotate_box_wifi" in text.split("cmd_pair() {")[1].split("\n}\n")[0], "stage 2 and pair both rotate it")
# ---- the printed setup sheet, the review screen and the Telegram prompt ----------------------------------------------------------
r = lib('rm -f "$CONF"; conf_set SITE_NAME "Maria <Shop> & Sons"; conf_set GUEST_NAME "Maria Free WiFi"; conf_set KIOSK_PASS kioskpw12345; conf_set BOX_ADMIN_PASS boxpw123456; '
        'conf_set ROOT_PASS rootpw12345; conf_set ROOT_PASS_SET 1; HANDOUT=' + tmp + '/handout.html write_handout; cat ' + tmp + '/handout.html')
h = r.stdout
check("Maria &lt;Shop&gt; &amp; Sons" in h and "Maria Free WiFi" in h and "kioskpw12345" in h and "boxpw123456" in h and "rootpw12345" in h and "PisoKiosk" in h and "http://" in h,
      "the setup sheet carries every name and password (escaped): " + h[:300])
check(oct(os.stat(f"{tmp}/handout.html").st_mode)[-3:] == "600", "and is readable by root only")
r = lib('ASSUME_YES=0; ask_telegram; echo rc=$?; echo "[$(conf_get TG_TOKEN)]"')
check("[]" in r.stdout, "without a terminal the Telegram prompt is skipped")
check("review_choices" in text.split("stage1() {")[1] and "Apply these settings?" in text and text.index("review_choices\n\task_telegram") > 0, "answers are reviewed before anything is changed")
check("finish_telegram" in text.split("stage2() {")[1].split("\n}\n")[0] and "write_handout" in text.split("stage2() {")[1].split("\n}\n")[0], "stage 2 writes the sheet and offers Telegram at the end")
# ---- opt-in admin lock -------------------------------------------------------------------------------------------------------------
open(f"{tmp}/uci.log", "w").close()
r = lib('LOCK_ADMIN_SECONDS=0; id() { echo 0; }; cmd_lock_admin aa:bb:cc:dd:ee:01 AA:BB:CC:DD:EE:02 < /dev/null; echo rc=$?; echo "ADMIN=$(conf_get ADMIN_MACS)"',
        env={"LOCK_ADMIN_SECONDS": "300"})
ul = open(f"{tmp}/uci.log").read()
check("rc=0" in r.stdout and "firewall.piso_admin_allow_1.src_mac=aa:bb:cc:dd:ee:01" in ul and "firewall.piso_admin_allow_2.src_mac=AA:BB:CC:DD:EE:02" in ul
      and "firewall.piso_admin_block.target=REJECT" in ul and "dest_port=22 80 443" in ul and "commit firewall" in ul, "lock-admin allows the named computers and blocks the rest: " + r.stdout + r.stderr[-300:])
check("undo" in r.stdout.lower() and "ADMIN=aa:bb:cc:dd:ee:01 AA:BB:CC:DD:EE:02" in r.stdout, "it says it undoes itself unless confirmed, and remembers the addresses")
r = lib('id() { echo 0; }; cmd_lock_admin not-a-mac < /dev/null; echo rc=$?')
check("not a MAC" in (r.stdout + r.stderr), "a bad address is refused")
r = lib('id() { echo 0; }; SSH_CONNECTION=""; cmd_lock_admin < /dev/null; echo rc=$?')
check("name the computer" in (r.stdout + r.stderr), "without any address (and none detectable) it refuses instead of locking everyone out")
open(f"{tmp}/uci.log", "w").close()
r = lib('id() { echo 0; }; cmd_unlock_admin; echo rc=$?')
check("rc=0" in r.stdout and "commit firewall" in open(f"{tmp}/uci.log").read(), "unlock-admin removes the rules")
check("setsid sh -c 'sleep" in text and "piso-admin-confirm" in text, "the lock has a self-undo timer")
# ---- piso-setup update --------------------------------------------------------------------------------------------------------------
root = f"{tmp}/uproot"; os.makedirs(root)
conf_up = f"{tmp}/upconf"; open(conf_up, "w").write("GW_KEY='" + "ab" * 32 + "'\n")
r = run("update", env={"PISO_TEST_NONROOT": "1", "PISO_ROOT": root, "PISO_CONF": conf_up, "PISO_SELF_PATH": f"{tmp}/installed/piso-setup"})
check(os.path.exists(f"{root}/usr/bin/pisoportal") and os.path.exists(f"{root}/etc/init.d/pisoportal") and os.path.exists(f"{root}/usr/bin/piso-monitor.sh"),
      "update installs the portal program and the monitor: " + r.stdout[-300:] + r.stderr[-300:])
check(open(f"{root}/usr/bin/piso-monitor.sh").read() == open(f"{ROOT}/router/piso_monitor.sh").read(), "they are the current ones from this file")
# a router set up with the earlier ThemeSpec portal: update moves openNDS and the firewall to the FAS portal, nothing else
open(f"{bindir}/uci", "w").write(f"""#!/bin/sh
echo "$*" >> {tmp}/uci.log
[ "$1" = -q ] && shift
case "$1" in
  batch) cat >> {tmp}/uci.batch; touch {tmp}/uci.pending ;;
  changes) [ -e {tmp}/uci.pending ] && echo "opennds.@opennds[0].fasport='2080'" ;;
  commit) rm -f {tmp}/uci.pending ;;
  get) exit 1 ;;
esac
exit 0
""")
for f in ("uci.log", "uci.batch"):
    open(f"{tmp}/{f}", "w").close()
open(conf_up, "a").write("GUEST_NAME='Shop WiFi'\nBOX_MAC='AA:BB:CC:DD:EE:09'\n")
r = run("update", env={"PISO_TEST_NONROOT": "1", "PISO_ROOT": root, "PISO_CONF": conf_up, "PISO_SELF_PATH": f"{tmp}/installed/piso-setup"})
ub, ul = open(f"{tmp}/uci.batch").read(), open(f"{tmp}/uci.log").read()
check("set opennds.@opennds[0].fasport='2080'" in ub and "set opennds.@opennds[0].login_option_enabled='0'" in ub and "delete opennds.@opennds[0].themespec_path" in ub
      and "set firewall.guest_stream.dest_port='2080'" in ub and "set opennds.@opennds[0].gatewayname='Shop WiFi'" in ub, "update moves openNDS to the FAS portal: " + ub[:300])
check("set dropbear.@dropbear[0].Interface='lan'" in ub, "and keeps SSH off the guest network")
check(all(l.startswith(("set firewall.guest_stream", "set opennds.", "delete opennds.", "add_list opennds.", "set dropbear.", "delete uhttpd.", "add_list uhttpd.", "#"))
          for l in ub.splitlines() if l.strip()), "and touches nothing else (no Wi-Fi, network or passwords): " + ub)
check("commit opennds" in ul and "commit firewall" in ul, "the change is committed")
cc = open(f"{root}/etc/coinslot.conf").read()
check("GW_KEY='" + "ab" * 32 + "'" in cc and "PORTAL_PORT='2080'" in cc and "GATEWAY_NAME='Shop WiFi'" in cc and "GW_BOX_MAC='AA:BB:CC:DD:EE:09'" in cc,
      "and the portal's settings are written for this version: " + cc)
fake_uci({"radio0": "2g", "radio1": "5g"})
r = run("update", env={"PISO_TEST_NONROOT": "1", "PISO_ROOT": root, "PISO_CONF": f"{tmp}/nosetup"})
check(r.returncode != 0 and "run ./piso-setup.sh without arguments first" in (r.stdout + r.stderr), "without an existing setup it refuses")
r = subprocess.run(["sh", SCRIPT, "update"], env=dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_CONF=conf_up, PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
                   PISO_SUMMARY=f"{tmp}/summary", PISO_ROOT=root, PISO_SELF_PATH=SCRIPT, PISO_TEST_NONROOT="1"), capture_output=True, text=True, timeout=60)
check(r.returncode != 0 and "installed (old) copy" in (r.stdout + r.stderr), "run from the installed copy it says to use the new file")
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

# ---- test-coin against a stub portal ------------------------------------------------------------------------------------
class Mgr(http.server.BaseHTTPRequestHandler):
    pulses = 0
    calls = 0
    finished = False
    coin = True

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/admin/status"):
            Mgr.calls += 1
            if Mgr.coin and Mgr.calls >= 2:
                Mgr.pulses = 2
            if Mgr.finished:
                body = '{"t":"state","s":"final","pulses":%d}' % Mgr.pulses if Mgr.pulses else '{"t":"state","s":"empty","pulses":0}'
            else:
                body = '{"t":"state","s":"armed","pulses":%d}' % Mgr.pulses
        elif self.path.startswith("/admin/start"):
            body = '{"t":"state","s":"starting","pulses":0}'
        elif self.path.startswith("/admin/finish"):
            Mgr.finished = True
            body = '{"t":"state","s":"closing","pulses":%d}' % Mgr.pulses
        else:
            body = '{"success":true}'
        self.send_response(200); self.end_headers(); self.wfile.write(body.encode())


msrv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Mgr)
threading.Thread(target=msrv.serve_forever, daemon=True).start()
PA = f"http://127.0.0.1:{msrv.server_address[1]}"
r = run("test-coin", env={"PORTAL_ADMIN_URL": PA, "TEST_SECONDS": "3"})
check(r.returncode == 0 and "coin detected: 2" in r.stdout and "the box counted 2" in r.stdout, "test-coin reports a counted coin: " + r.stdout + r.stderr)
Mgr.pulses = 0; Mgr.calls = 0; Mgr.finished = False; Mgr.coin = False
r = run("test-coin", env={"PORTAL_ADMIN_URL": PA, "TEST_SECONDS": "2"})
check(r.returncode != 0 and "no coin was counted" in r.stdout, "test-coin says so when no coin is counted: " + r.stdout)
class Html(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"<html>router login</html>")


hsrv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Html)
threading.Thread(target=hsrv.serve_forever, daemon=True).start()
r = run("test-coin", env={"PORTAL_ADMIN_URL": f"http://127.0.0.1:{hsrv.server_address[1]}"})
check(r.returncode != 0 and "Unexpected answer" in r.stdout and "Insert a coin" not in r.stdout, "test-coin does not ask for a coin when something else answers: " + r.stdout)
r = run("test-coin", env={"PORTAL_ADMIN_URL": "http://127.0.0.1:9"})
check(r.returncode != 0 and "does not answer" in r.stdout, "test-coin explains a manager that is down")
r = run("diag")
check("=== piso-setup diag" in r.stdout and "--- last coin-slot log lines" in r.stdout and "ROOT_PASS" not in r.stdout and "password:" not in r.stdout.lower(), "diag runs and prints no passwords")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
