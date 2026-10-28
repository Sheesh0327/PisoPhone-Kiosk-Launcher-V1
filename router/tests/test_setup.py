"""Tests of setup/piso-setup.sh (the one-file router setup) without a router: a fake uci with canned radios, the generated
settings, the payload, the helpers, and the coin box provisioning against a fake box.
Run with:  python3 router/tests/test_setup.py"""
import urllib.parse, http.server, os, re, shutil, subprocess, sys, tempfile, threading, base64

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
             PISO_STATE=f"{tmp}/state", PISO_SUMMARY=f"{tmp}/summary", PISO_ROUTER_ID="a1b2")
    e.update(env or {})
    return subprocess.run(["sh", SCRIPT, *args], env=e, capture_output=True, text=True, timeout=60, input=inp)


def lib(code, env=None):
    """Load the script's functions (no main) and run shell code."""
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_CONF=f"{tmp}/conf2", PISO_LOG=f"{tmp}/log2",
             PISO_STATE=f"{tmp}/state2", PISO_SUMMARY=f"{tmp}/summary2", PISO_SETUP_SOURCE_ONLY="1", PISO_ROUTER_ID="a1b2")
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
check("set wireless.kiosk_radio0.ssid='PisoKiosk-a1b2'" in r2.stdout and "set wireless.kiosk_radio0.hidden='1'" in r2.stdout and "set wireless.kiosk_radio1.hidden='1'" in r2.stdout,
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
# a check right after the install waits for a service that was just started (openNDS needs a few seconds)
r = lib(f'sleep() {{ echo s >> {tmp}/slept; }}; n=0; ndsctl() {{ n=$((n + 1)); [ "$n" -ge 4 ]; }}; wait_until 30 \'ndsctl status\'; echo rc=$?; echo n=$n')
check("rc=0" in r.stdout and "n=4" in r.stdout, "wait_until: a service that starts after a few tries passes: " + r.stdout)
check(open(f"{tmp}/slept").read().count("s") == 3, "and it slept between the tries, not before the first")
os.remove(f"{tmp}/slept")
r = lib(f'sleep() {{ echo s >> {tmp}/slept; }}; ndsctl() {{ return 1; }}; wait_until 30 \'ndsctl status\'; echo rc=$?')
check("rc=1" in r.stdout and open(f"{tmp}/slept").read().count("s") == 15, "wait_until: a service that never starts fails after the time is up (15 tries of 2 s): " + r.stdout)
os.remove(f"{tmp}/slept")
r = lib(f'sleep() {{ echo s >> {tmp}/slept; }}; ndsctl() {{ return 0; }}; wait_until 30 \'ndsctl status\'; echo rc=$?')
check("rc=0" in r.stdout and not os.path.exists(f"{tmp}/slept"), "wait_until: a service that is already up passes at once, without waiting")
check('wait_until 30 \'ndsctl status\'' in text.split("check_all() {")[1].split("\n}\n")[0], "the 'openNDS is running' check waits for openNDS")
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
check("ask_password BOX_ADMIN_PASS_NEW" in text and "ask_password KIOSK_PASS" in text and "admin QR code" in text, "all the operating passwords are chosen in the setup")
check(not re.search(r"\bod -|hexdump|xxd", text.split(PAYLOAD_MARK)[0]), "no tools a stock BusyBox lacks (od, hexdump, xxd)")
check("--stage2" not in text.split(PAYLOAD_MARK)[0] and "nohup" not in text.split(PAYLOAD_MARK)[0], "no background stage that outlives the SSH session")
check("set network.guest.ipaddr='10.0.30.1'" in out and "set network.guest.device='br-guest'" in out, "guest network on its own bridge")
for radio in ("radio0", "radio1"):
    check(f"set wireless.kiosk_{radio}.ssid='PisoKiosk-a1b2'" in out and f"set wireless.kiosk_{radio}.network='lan'" in out, f"PisoKiosk on {radio}")
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
check("set firewall.guest_nomodem.src='guest'" in out and "set firewall.guest_nomodem.dest='wan'" in out and "set firewall.guest_nomodem.target='REJECT'" in out
      and "add_list firewall.guest_nomodem.dest_ip='192.168.1.1'" in out and out.index("set firewall.guest_nomodem=rule") < out.index("set opennds.@opennds[0].enabled"),
      "guests are refused the modem's address (192.168.1.1) in the router's firewall")
check("set firewall.guest_noprivate.src='guest'" in out and "set firewall.guest_noprivate.dest='wan'" in out and "set firewall.guest_noprivate.target='REJECT'" in out
      and all(f"add_list firewall.guest_noprivate.dest_ip='{n}'" in out for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"))
      and out.index("set firewall.guest_noprivate=rule") < out.index("set opennds.@opennds[0].enabled"),
      "guests are refused the private address ranges beyond the modem (the internet stays open)")

check("set firewall.phone_noprivate.src='lan'" in out and "set firewall.phone_noprivate.dest='wan'" in out and "set firewall.phone_noprivate.target='REJECT'" in out
      and "set firewall.phone_noprivate.mark='0x00200000/0x00200000'" in out
      and all(f"add_list firewall.phone_noprivate.dest_ip='{n}'" in out for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10")),
      "phones that come in by Wi-Fi (marked by the bridge rules) are refused the modem's network")
check("0x00200000" in open(f"{ROOT}/router/piso_isolation.sh").read(), "and the bridge rules mark them with the same mark")

# the bridge rules (router/piso_isolation.sh): guests apart, rental phones reach the router and the coin box only. A fake nft keeps what it is given.
iso = f"{tmp}/iso"; shutil.rmtree(iso, ignore_errors=True)
for d in ("sys/br-lan/brif", "sys/br-guest/brif/phy0-ap1", "sys/br-guest/brif/phy1-ap1", "bin"): os.makedirs(f"{iso}/{d}", exist_ok=True)
open(f"{iso}/coin.conf", "w").write("GW_BOX='10.0.0.10'\nGW_BOX_MAC='AA:BB:CC:DD:EE:09'\nCONSOLE_BIND='10.0.0.1'\n"); open(f"{iso}/piso.conf", "w").write("")
open(f"{iso}/nft", "w").write(f'#!/bin/sh\necho "ARGS $*" >> {iso}/nft.log\nif [ "$1" = -f ]; then {{ echo "FILE $2"; cat "$2"; }} >> {iso}/nft.log; [ -e {iso}/nft-fail ] && exit 1; [ -e {iso}/nft-noname ] && grep -q ibrname "$2" && exit 1; fi\nexit 0\n')
open(f"{iso}/bin/logger", "w").write(f'#!/bin/sh\necho "$*" >> {iso}/logger.log\n')
for f in ("nft", "bin/logger"): os.chmod(f"{iso}/{f}", 0o755)
def run_iso(clear=True):
    if clear:
        for f in ("nft.log", "logger.log"):
            if os.path.exists(f"{iso}/{f}"): os.remove(f"{iso}/{f}")
    r = subprocess.run(["sh", f"{ROOT}/router/piso_isolation.sh"], capture_output=True, text=True,
                       env={**os.environ, "PATH": f"{iso}/bin:{os.environ['PATH']}", "PISO_NFT": f"{iso}/nft", "PISO_SYS": f"{iso}/sys", "PISO_CONF": f"{iso}/piso.conf", "PISO_COINSLOT_CONF": f"{iso}/coin.conf"})
    return r, (open(f"{iso}/nft.log").read() if os.path.exists(f"{iso}/nft.log") else ""), (open(f"{iso}/logger.log").read() if os.path.exists(f"{iso}/logger.log") else "")
r, nl, ll = run_iso()
check(r.returncode == 0 and nl.count("delete table bridge piso_guest") == 1 and 'meta ibrname "br-guest" counter drop' in nl and ll == "",
      "the guest bridge rule drops what would pass from one guest port to another: " + nl[:400])
check("delete table bridge piso_kiosk" in nl and "ether saddr aa:bb:cc:dd:ee:09 accept" in nl and "ether daddr aa:bb:cc:dd:ee:09 accept" in nl and "arp daddr ip 10.0.0.10 accept" in nl
      and 'iifname "phy*" counter drop' in nl and 'oifname "phy*" counter drop' in nl and 'iifname "wlan*" counter drop' in nl,
      "rental phones: only the coin box (by its address) is let through, everything else on a Wi-Fi port is dropped")
check('iifname "phy*" ip daddr 10.0.0.1 tcp dport { 22, 80, 443, 8080, 8443, 8081, 8082 } counter drop' in nl and "dport 2050" not in nl,
      "the router's login pages and the console (and the coin box page one port up) are not offered on Wi-Fi, and only on the router's own kiosk address (a guest's web request to any website is not touched): " + nl[-900:])
check('iifname "phy*" ether saddr != aa:bb:cc:dd:ee:09 meta mark set meta mark | 0x00200000' in nl, "what comes in by Wi-Fi (except the coin box) is marked for the firewall rule")
# a router that cannot match the bridge name: the guest ports are named instead; and the rule is the same each time it runs
open(f"{iso}/nft-noname", "w").close()
r, nl, ll = run_iso()
check(r.returncode == 0 and 'iifname { "phy0-ap1","phy1-ap1" } oifname { "phy0-ap1","phy1-ap1" } counter drop' in nl and ll == "", "without bridge-name matching the guest ports are listed instead: " + nl[:300])
os.remove(f"{iso}/nft-noname")
# the admin switch, and a router before the box is paired
open(f"{iso}/piso.conf", "w").write("WIFI_ADMIN='on'\n"); open(f"{iso}/coin.conf", "w").write("GW_BOX='10.0.0.10'\nGW_BOX_MAC=''\nCONSOLE_BIND='10.0.0.1'\n")
r, nl, ll = run_iso()
check("tcp dport" not in nl and "ether saddr aa:bb" not in nl and 'iifname "phy*" counter drop' in nl and 'iifname "phy*" meta mark set meta mark | 0x00200000' in nl,
      "wifi-admin on leaves the login pages open on Wi-Fi; before the box is paired phones are isolated and no address is named: " + nl[-500:])
# the console as the front page (port 80): its ports are listed once each
open(f"{iso}/coin.conf", "w").write("GW_BOX='10.0.0.10'\nGW_BOX_MAC='AA:BB:CC:DD:EE:09'\nCONSOLE_BIND='10.0.0.1'\nCONSOLE_PORT='80'\n"); open(f"{iso}/piso.conf", "w").write("")
r, nl, ll = run_iso()
check('tcp dport { 22, 80, 443, 8080, 8443, 81 } counter drop' in nl, "with the console on port 80 the list has 80 once and 81 for the box page: " + nl[-700:])
open(f"{iso}/piso.conf", "w").write("WIFI_ADMIN='on'\n"); open(f"{iso}/coin.conf", "w").write("GW_BOX='10.0.0.10'\nGW_BOX_MAC=''\nCONSOLE_BIND='10.0.0.1'\n")
r, nl, ll = run_iso()
# a router that cannot load the rules logs it and carries on
open(f"{iso}/nft-fail", "w").close()
r, nl, ll = run_iso()
check(r.returncode == 0 and ll.count("NOT active") == 2 and "guest isolation is NOT active" in ll and "phone isolation is NOT active" in ll, "a router that cannot load the bridge rules logs it and carries on (exit 0): " + ll)
# RT-10: the old table is deleted in the same nft transaction that loads the new one, never on its own (a failed load leaves the old rules in place)
check("ARGS delete table" not in nl, "RT-10: no separate `nft delete table` call: " + nl[:300])
check(all(t in nl for t in ("table bridge piso_guest\ndelete table bridge piso_guest\ntable bridge piso_guest {", "table bridge piso_kiosk\ndelete table bridge piso_kiosk\ntable bridge piso_kiosk {")),
      "RT-10: each rule file declares, deletes and re-creates its table in one file: " + nl[:500])
os.remove(f"{iso}/nft-fail")
# RT-18: a console bound to every address (CONSOLE_BIND 0.0.0.0 or empty) must still be closed on Wi-Fi
open(f"{iso}/piso.conf", "w").write("")
open(f"{iso}/bin/ip", "w").write('#!/bin/sh\n[ -e ' + iso + '/ip-fail ] && exit 1\necho "5: br-lan    inet 10.0.0.1/24 brd 10.0.0.255 scope global br-lan"\n'); os.chmod(f"{iso}/bin/ip", 0o755)
for bind in ("0.0.0.0", ""):
    open(f"{iso}/coin.conf", "w").write(f"GW_BOX='10.0.0.10'\nGW_BOX_MAC='AA:BB:CC:DD:EE:09'\nCONSOLE_BIND='{bind}'\n")
    r, nl, ll = run_iso()
    check('iifname "phy*" ip daddr 10.0.0.1 tcp dport { 22, 80, 443, 8080, 8443, 8081, 8082 } counter drop' in nl,
          f"RT-18: CONSOLE_BIND '{bind}': the router's own address is read from br-lan: " + nl[-600:])
open(f"{iso}/ip-fail", "w").close()
for bind in ("0.0.0.0", ""):
    open(f"{iso}/coin.conf", "w").write(f"GW_BOX='10.0.0.10'\nGW_BOX_MAC='AA:BB:CC:DD:EE:09'\nCONSOLE_BIND='{bind}'\n")
    r, nl, ll = run_iso()
    check('iifname "phy*" tcp dport { 8081, 8082 } counter drop' in nl and "ip daddr 0.0.0.0" not in nl and "dport { 22, 80" not in nl,
          f"RT-18: CONSOLE_BIND '{bind}', router address unknown: the console ports are closed for any address, ports 80 and 443 are left alone: " + nl[-600:])
os.remove(f"{iso}/ip-fail"); os.remove(f"{iso}/bin/ip")

check("authenticated_users='block to" not in out and "authenticated_users='allow all'" not in out, "openNDS's own rules for paying guests are not replaced (1.5.5 did, and was taken back)")
r = lib('ip() { echo "default via 192.168.0.1 dev wan"; }; modem_addresses')
check(r.stdout.split() == ["192.168.1.1", "192.168.0.1"], "the modem's address and the router's own default gateway are both refused: " + r.stdout)
r = lib('ip() { echo "default via 192.168.1.1 dev wan"; }; modem_addresses')
check(r.stdout.split() == ["192.168.1.1"], "the same address is listed once: " + r.stdout)
r = lib('ip() { echo "default via not-an-address dev wan"; }; modem_addresses')
check(r.stdout.split() == ["192.168.1.1"], "a gateway that is not an IPv4 address is left out: " + r.stdout)
r = lib('ip() { return 1; }; modem_addresses')
check(r.stdout.split() == ["192.168.1.1"], "no default route (modem off): the usual modem address is still refused: " + r.stdout)
open(f"{tmp}/conf2", "w").write("MODEM_IP='10.9.9.1'\n")
r = lib('ip() { return 1; }; modem_addresses')
check(r.stdout.split() == ["192.168.1.1", "10.9.9.1"], "MODEM_IP in the settings adds an address: " + r.stdout)
os.remove(f"{tmp}/conf2")
check("set opennds.@opennds[0].gatewayinterface='br-guest'" in out and "set opennds.@opennds[0].gatewayname='PisoWiFi'" in out, "openNDS gates the guest network")
check("set opennds.@opennds[0].fasport='2080'" in out and "set opennds.@opennds[0].faspath='/'" in out and "set opennds.@opennds[0].fas_secure_enabled='1'" in out
      and "set opennds.@opennds[0].login_option_enabled='0'" in out and "delete opennds.@opennds[0].themespec_path" in out and "allow tcp port 2080" in out
      and "set opennds.@opennds[0].statuspath='/usr/lib/opennds/pisoportal_status.sh'" in out,
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
for src, dest in [("router/piso_monitor.sh", "/usr/bin/piso-monitor.sh"), ("router/piso_monitor.init", "/etc/init.d/piso_monitor"), ("router/pisoportal.init", "/etc/init.d/pisoportal"), ("router/pisoportal_status.sh", "/usr/lib/opennds/pisoportal_status.sh"), ("router/piso_isolation.sh", "/etc/hotplug.d/firewall/50-piso-isolation")]:
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

# a settings write that fails (a full flash: no byte can be written) keeps the old settings, which hold every password and the gateway key
r = lib("conf_set KEEP1 one; conf_set KEEP2 two; ( trap '' XFSZ; ulimit -f 0; conf_set NEW three; echo rc=$? ); "
        'echo "$(conf_get KEEP1)|$(conf_get KEEP2)|$(conf_get NEW)"; ls "$(dirname "$CONF")" | grep -c "tmp" || true')
check("rc=1" in r.stdout and "one|two|\n" in r.stdout, "a failed settings write keeps the old settings: " + r.stdout + r.stderr)
check(r.stdout.strip().endswith("0"), "no temporary settings file is left behind: " + r.stdout)

# the owner's own lines in coinslot.conf survive a rewrite (prices, SEND_HOST, ...); the setup's keys are replaced
cs = f"{tmp}/csroot"
os.makedirs(f"{cs}/etc", exist_ok=True)
open(f"{cs}/etc/coinslot.conf", "w").write("GW_KEY='old'\nHYPER_TIERS='5:40 10:90'\nSEND_HOST='10.0.0.96'\n# my note\n")
r = lib(f'PISO_ROOT={cs}; conf_set GUEST_NAME "Shop"; write_coinslot_conf {"ab" * 32} AA:BB:CC:DD:EE:FF; cat {cs}/etc/coinslot.conf')
check("HYPER_TIERS='5:40 10:90'" in r.stdout and "SEND_HOST='10.0.0.96'" in r.stdout and "# my note" in r.stdout,
      "the owner's own coinslot.conf lines are kept: " + r.stdout)
check(r.stdout.count("GW_KEY=") == 1 and f"GW_KEY='{'ab' * 32}'" in r.stdout, "the setup's own keys are replaced, not doubled")
check(oct(os.stat(f"{cs}/etc/coinslot.conf").st_mode & 0o777) == "0o600", "coinslot.conf stays private")

# stopped from outside (Ctrl-C, the SSH session lost): the clean-up runs, as on an error
r = lib('NDS_RESTORE=0; UNDO="echo undone-it"; ( kill -INT $$ ) ; echo not-reached', env={})
check("undone-it" in r.stdout and "not-reached" not in r.stdout and "interrupted" in r.stdout, "an interrupted run undoes its half-done step: " + r.stdout)
check(re.fullmatch(r"[0-9a-f]{64}", c), "gateway key is 64 hex characters")
check(oct(os.stat(f"{tmp}/conf2").st_mode)[-3:] == "600", "settings file is private")

# ---- wifi-name -----------------------------------------------------------------------------------------------------------
open(f"{tmp}/uci.log", "w").close()
fake_uci({"radio0": "2g", "radio1": "5g"})
for bad in ["", "PisoKiosk", "PisoCoinBox", 'He said "hi"', "a$b", "x" * 33]:
    r = run("wifi-name", bad)
    check(r.returncode != 0, f"wifi-name refuses {bad!r}")

# ---- guest-port --------------------------------------------------------------------------------------------------------
def fake_bridges(lan="lan1 lan2 lan3 lan4", guest="", with_guest=True):
    """A uci that keeps the ports of br-lan (network.cfglan) and br-guest (network.cfgguest) in files."""
    open(f"{tmp}/ports_lan", "w").write(lan)
    open(f"{tmp}/ports_guest", "w").write(guest)
    guest_line = "network.cfgguest=device\\nnetwork.cfgguest.name='br-guest'\\n" if with_guest else ""
    open(f"{bindir}/uci", "w").write(f"""#!/bin/sh
echo "$*" >> {tmp}/uci.log
[ "$1" = -q ] && shift
f() {{ case "$1" in network.cfglan.*) echo {tmp}/ports_lan ;; network.cfgguest.*) echo {tmp}/ports_guest ;; esac; }}
case "$1" in
  show) printf "network.cfglan=device\\nnetwork.cfglan.name='br-lan'\\n{guest_line}" ;;
  get) F=$(f "$2"); [ -n "$F" ] && [ -s "$F" ] && cat "$F" || exit 1 ;;
  del_list) F=$(f "$2"); P=${{2#*=}}; tr ' ' '\\n' < "$F" | grep -vx "$P" | tr '\\n' ' ' | sed 's/ $//' > "$F.n"; mv "$F.n" "$F" ;;
  add_list) F=$(f "$2"); P=${{2#*=}}; {{ cat "$F"; echo " $P"; }} | tr '\\n' ' ' | sed 's/^ *//; s/ *$//' > "$F.n"; mv "$F.n" "$F" ;;
esac
exit 0
""")
    os.chmod(f"{bindir}/uci", 0o755)
    open(f"{bindir}/id", "w").write("#!/bin/sh\necho 0\n")   # the command wants root; CI does not run as root
    os.chmod(f"{bindir}/id", 0o755)


def ports(which):
    return open(f"{tmp}/ports_{which}").read().split()


fake_bridges()
r = run("guest-port")
check(r.returncode == 0 and "lan1 lan2 lan3 lan4" in r.stdout, "guest-port lists the ports: " + r.stdout + r.stderr)
for bad in ["wan", "lan", "lan4x", "lan4;reboot", "br-guest", "lan9"]:
    r = run("guest-port", bad)
    check(r.returncode != 0 and ports("lan") == "lan1 lan2 lan3 lan4".split(), f"guest-port refuses {bad!r}")
r = run("--yes", "guest-port", "lan4")
check(r.returncode == 0 and ports("lan") == ["lan1", "lan2", "lan3"] and ports("guest") == ["lan4"],
      f"lan4 moves to the guest bridge: {ports('lan')} {ports('guest')} {r.stdout}{r.stderr}")
check("DHCP off" in r.stdout and "NAT" in r.stdout, "it tells you how to set the AP up")
check("commit network" in open(f"{tmp}/uci.log").read(), "the change is committed")
r = run("--yes", "guest-port", "lan4")
check(r.returncode == 0 and "already" in r.stdout and ports("guest") == ["lan4"], "moving it twice changes nothing")
r = run("--yes", "guest-port", "off")
check(r.returncode == 0 and sorted(ports("lan")) == ["lan1", "lan2", "lan3", "lan4"] and ports("guest") == [], "off puts it back")
fake_bridges(lan="lan1", guest="")
r = run("--yes", "guest-port", "lan1")
check(r.returncode != 0 and ports("lan") == ["lan1"], "the last LAN port is never taken: " + r.stderr)
fake_bridges(with_guest=False)
check(run("guest-port", "lan4").returncode != 0, "refuses before the setup has made the guest network")
fake_bridges()
r = run("--dry-run", "guest-port", "lan4")
check(r.returncode == 0 and ports("lan") == ["lan1", "lan2", "lan3", "lan4"], "dry run changes nothing")
os.remove(f"{bindir}/id")
fake_uci({"radio0": "2g", "radio1": "5g"})

# ---- coin box provisioning against a fake box ---------------------------------------------------------------------------
class Box(http.server.BaseHTTPRequestHandler):
    admin = "Coinslot@Setup"
    key = None
    kiosk = None
    kiosk_ssid = None
    wifi_ssid = None
    refuse_wifi = False   # a box that answers the box-network request with an error
    kiosk_support = True
    ssid_support = True   # firmware 3.6.0 and newer carries the kiosk network's name in the link
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
        if self.path == "/save" and "wifi_pass" in body and Box.refuse_wifi:
            self.send_response(500); self.end_headers(); return
        if self.path == "/save" and "wifi_pass" in body:
            Box.wifi = body["wifi_pass"]
            Box.wifi_ssid = urllib.parse.unquote_plus(body.get("wifi_ssid", "")) or None
            self.send_response(200); self.end_headers(); self.wfile.write(b"{}"); return
        if self.path == "/save" and "kiosk_wifi" in body:
            if not Box.kiosk_support:
                self.send_response(404); self.end_headers(); return   # a box with older firmware
            Box.kiosk = urllib.parse.unquote_plus(body["kiosk_wifi"])
            if Box.ssid_support and "kiosk_ssid" in body:
                Box.kiosk_ssid = urllib.parse.unquote_plus(body["kiosk_ssid"])
            self.send_response(200); self.end_headers(); self.wfile.write(b"{}"); return
        if self.path == "/api/gateway/config":
            Box.key = body.get("key")
            self.send_response(200); self.end_headers(); self.wfile.write(b'{"success":true,"configured":true}'); return
        self.send_response(404); self.end_headers()

    def do_GET(self):
        if self.path == "/" and not self._auth():
            self.send_response(401); self.end_headers(); return
        if self.path == "/" and self._auth():
            link = ("&wifi_ssid=" + urllib.parse.quote_plus(Box.kiosk_ssid) if Box.kiosk_ssid else "") + \
                   ("&wifi_pass=" + urllib.parse.quote_plus(Box.kiosk) if Box.kiosk else "")
            self.send_response(200); self.end_headers()
            self.wfile.write(f'<a href="https://pisophone.pages.dev/?mac=AA&secret=xyz{link}">Set up a phone</a>'.encode()); return
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

# ---- the phones' PisoKiosk password goes to the box (its "Set up a phone" link then carries it) -----------------------------
Box.admin = "NewPassw0rdXYZ"
KP = "Pa&ss#w0rd+1%"
NAMES = "conf_set KIOSK_SSID_NAME PisoKiosk-a1b2; conf_set BOX_SSID_NAME PisoCoinBox-a1b2; resolve_wifi_names; "   # (this router's own names, as a setup stores them)
pre = f"conf_set BOX_ADMIN_PASS NewPassw0rdXYZ; conf_set KIOSK_PASS '{KP}'; " + NAMES
r = lib(pre + "push_kiosk_wifi; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout and Box.kiosk == KP, f"the router gives the box the PisoKiosk password, special characters intact: {Box.kiosk!r} {r.stdout}{r.stderr}")
check(Box.kiosk_ssid == "PisoKiosk-a1b2", f"... and this router's own kiosk network name: {Box.kiosk_ssid!r}")
r = lib(pre + "kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout, "the check sees the password in the box's link: " + r.stdout + r.stderr)
Box.kiosk_ssid = None   # a box that has the password but not the name (firmware before 3.6.0): not good enough for a router with its own name
r = lib(pre + "kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": addr})
check("rc=1" in r.stdout, "a link without the router's own network name is not accepted")
r = lib(pre + "conf_set KIOSK_SSID_NAME PisoKiosk; conf_set BOX_SSID_NAME PisoCoinBox; resolve_wifi_names; kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout, "a router that keeps the old shared name needs no name in the link (older firmware is fine): " + r.stdout)
Box.kiosk_ssid = "PisoKiosk-a1b2"
Box.kiosk = "something-else"
r = lib(pre + "kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": addr})
check("rc=1" in r.stdout, "a different password in the link is not accepted (the router's password changed)")
r = lib(pre + "sync_kiosk_wifi; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout and Box.kiosk == KP, "sync fixes a box that has an old password: " + r.stdout)
Box.kiosk = None
r = lib(pre + "kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": addr})
check("rc=1" in r.stdout, "a box whose link carries nothing fails the check")
r = lib(pre + "sync_kiosk_wifi; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout and Box.kiosk == KP, "sync fills it in when missing")
Box.kiosk = None
r = lib(pre + "provision_box NewPassw0rdXYZ " + "12" * 32 + "; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout and Box.kiosk == KP, "setting up a box also gives it the password: " + r.stdout + r.stderr)
Box.kiosk = None; Box.kiosk_support = False
r = lib(pre + "provision_box NewPassw0rdXYZ " + "12" * 32 + "; echo rc=$?; push_kiosk_wifi; echo rc2=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout and "rc2=0" in r.stdout and "did not take the PisoKiosk password" in r.stdout and Box.kiosk is None,
      "a box with older firmware is not an error: " + r.stdout + r.stderr)
Box.kiosk_support = True
r = lib("conf_set KIOSK_PASS ''; push_kiosk_wifi; echo rc=$?", env={"BOX_IP": addr})
check("rc=0" in r.stdout, "nothing to give when the router has no PisoKiosk password")
auto = text.split("cmd_self_update() {")[1].split("\n}\n")[0]
check("sync_kiosk_wifi" in auto and auto.index("sync_kiosk_wifi") < auto.index("router.json"), "the hourly update also gives the box the password")
check("sync_kiosk_wifi" in text.split("cmd_update() {")[1].split("\n}\n")[0], "an update gives the box the password")
check("kiosk_wifi_on_box" in text.split("agree_all() {")[1].split("\n}\n")[0], "the agreement checks include the link carrying the password")
check("kiosk-wifi" in text.split("main() {")[1], "piso-setup kiosk-wifi exists")

# ---- agreement: the copies of one value are compared with each other ----------------------------------------------------------
AG_ENV = {"T_KIOSK": "kiosk-pass-1", "T_GUEST": "Shop WiFi", "T_MAC": "aa:bb:cc:dd:ee:ff", "T_BOXKEY": "boxwifi-own-key-123",
          "T_KIOSKSSID": "PisoKiosk-a1b2", "T_BOXSSID": "PisoCoinBox-a1b2"}
AG_PRE = r"""
PISO_ROOT=$T_ROOT; mkdir -p "$PISO_ROOT/etc"
conf_set GW_KEY """ + "9f" * 32 + r"""; conf_set BOX_MAC AA:BB:CC:DD:EE:FF; conf_set GUEST_NAME "Shop WiFi"; conf_set KIOSK_PASS kiosk-pass-1
conf_set BOX_ADMIN_PASS NewPassw0rdXYZ; conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW boxwifi-own-key-123
write_coinslot_conf """ + "9f" * 32 + r""" AA:BB:CC:DD:EE:FF
printf 'kiosk-pass-1 NewPassw0rdXYZ\n' > "$SUMMARY"
uci() { [ "$1" = -q ] && shift; case "$1 $2" in
  "show wireless") printf 'wireless.kiosk_radio0=wifi-iface\nwireless.kiosk_radio1=wifi-iface\nwireless.guest_radio0=wifi-iface\nwireless.box_ap=wifi-iface\n' ;;
  "get wireless.kiosk_radio0.key" | "get wireless.kiosk_radio1.key") echo "$T_KIOSK" ;;
  "get wireless.guest_radio0.ssid" | "get opennds.@opennds[0].gatewayname") echo "$T_GUEST" ;;
  "get opennds.@opennds[0].fasport") echo "$PORTAL_PORT" ;;
  "get wireless.box_ap.maclist") echo "$T_MAC" ;;
  "get wireless.box_ap.key") echo "$T_BOXKEY" ;;
  "get wireless.kiosk_radio0.ssid" | "get wireless.kiosk_radio1.ssid") echo "$T_KIOSKSSID" ;;
  "get wireless.box_ap.ssid") echo "$T_BOXSSID" ;;
  *) return 1 ;; esac; }
ip() { echo "    inet 10.0.30.1/24 brd 10.0.30.255 scope global br-guest"; }
"""
fake_portal = f"{bindir}/fakeportal"
open(fake_portal, "w").write("#!/bin/sh\necho 'box 10.0.0.10 answers'\n"); os.chmod(fake_portal, 0o755)


def agree(extra="", env=None, box=False, code="agree_all"):
    shutil.rmtree(f"{tmp}/agroot", ignore_errors=True)
    if os.path.exists(f"{tmp}/agconf"):
        os.remove(f"{tmp}/agconf")
    e = dict(AG_ENV, T_ROOT=f"{tmp}/agroot", GUEST_IP="10.0.30.1", PISO_PORTAL_BIN=fake_portal, BOX_IP=addr if box else "127.0.0.1:9",
             PISO_CONF=f"{tmp}/agconf", PISO_SUMMARY=f"{tmp}/agsummary", PISO_LOG=f"{tmp}/aglog")
    e.update(env or {})
    return lib(AG_PRE + ("" if box else "box_up() { return 1; }\n") + extra + f"\n{code}; echo rc=$?; echo \"FAILS=$AGREE_FAILS\"", env=e)


r = agree()
check("rc=0" in r.stdout and "SKIP" in r.stdout and "FAIL" not in r.stdout.replace("FAILS=", ""), "everything agrees (the box checks are skipped while it is off): " + r.stdout + r.stderr)
for what, extra, env, frag in [
    ("another gateway key in the portal's file", "sed -i \"s/^GW_KEY=.*/GW_KEY='aa'/\" $PISO_ROOT/etc/coinslot.conf", None, "gateway key"),
    ("a MAC the box network does not allow", "", {"T_MAC": "11:22:33:44:55:66"}, "address"),
    ("a PisoKiosk radio with another password", "", {"T_KIOSK": "an-old-password"}, "kiosk network"),
    ("a kiosk radio with another name (the shared one of another router)", "", {"T_KIOSKSSID": "PisoKiosk"}, "kiosk network"),
    ("a box network with another name", "", {"T_BOXSSID": "PisoCoinBox"}, "coin box network"),
    ("a customer Wi-Fi name changed in LuCI", "", {"T_GUEST": "Renamed"}, "customer Wi-Fi name"),
    ("a portal that listens on another address", "sed -i \"s/^PORTAL_BIND=.*/PORTAL_BIND='10.9.9.9'/\" $PISO_ROOT/etc/coinslot.conf", None, "listens"),
    ("a box network whose password was changed by hand", "", {"T_BOXKEY": "changed-by-hand-1"}, "coin box network"),
    ("a stale setup summary", "echo old > \"$SUMMARY\"", None, "summary"),
]:
    r = agree(extra, env)
    check("rc=1" in r.stdout and "FAIL  " in r.stdout and frag in r.stdout.split("FAILS=")[1], f"{what} is named: " + r.stdout[-300:] + r.stderr)

# with the box on: its password, key and link
Box.admin = "NewPassw0rdXYZ"; Box.kiosk = "kiosk-pass-1"
r = agree(box=True)
check("rc=0" in r.stdout and "SKIP" not in r.stdout, "with the box on, all agree: " + r.stdout + r.stderr)
Box.admin = "SomethingElse1"
r = agree(box=True)
check("rc=0" not in r.stdout and "admin password the router stored" in r.stdout.split("FAILS=")[1], "a box with another admin password is named: " + r.stdout[-300:])
Box.admin = "NewPassw0rdXYZ"; Box.kiosk = None
r = agree(box=True)
check("rc=1" in r.stdout and "Set up a phone" in r.stdout.split("FAILS=")[1], "a box whose link has no password is named")
Box.kiosk = "kiosk-pass-1"

# the hourly watcher tells Telegram only when the answer changes
watch = "NOTES=$T_ROOT/notes; : > $NOTES; notify() { echo \"$*\" >> $NOTES; }\n"
r = agree(watch, {"T_KIOSK": "an-old-password"}, code="watch_agreement; watch_agreement; cat $T_ROOT/notes; DRY=0")
notes = r.stdout.split("rc=")[0].strip().splitlines()
check(len(notes) == 1 and "no longer agree" in notes[0] and "kiosk network" in notes[0], "a new disagreement is told once, with its name: " + str(notes) + r.stderr)
r = agree(watch + "conf_set AGREE_LAST 12345\n", {}, code="watch_agreement; watch_agreement; cat $T_ROOT/notes")
notes = r.stdout.split("rc=")[0].strip().splitlines()
check(len(notes) == 1 and "agrees again" in notes[0], "and again when everything agrees: " + str(notes))
r = agree(watch, {}, code="watch_agreement; cat $T_ROOT/notes")
check(r.stdout.split("rc=")[0].strip() == "", "a router that always agreed says nothing")
r = agree(code="cmd_verify")
check("rc=0" in r.stdout and "matches" in r.stdout, "piso-setup verify prints the result: " + r.stdout)
r = agree(env={"T_KIOSK": "x"}, code="cmd_verify")
check("rc=1" in r.stdout and "disagree" in r.stdout, "and fails when a value disagrees")
auto = text.split("cmd_self_update() {")[1].split("\n}\n")[0]
check("watch_agreement" in auto and auto.index("watch_agreement") < auto.index("router.json"), "the hourly update check runs the watcher")
check("agree_all" in text.split("check_all() {")[1].split("\n}\n")[0], "status includes the agreement checks")
check("verify)" in text.split("main() {")[1], "piso-setup verify exists")

# ---- the hourly update check is scheduled, and status says so ------------------------------------------------------------------
cron = f"{tmp}/crontab"
open(cron, "w").write("0 3 * * * something\n")
r = lib("update_timer_ok; echo rc=$?", env={"PISO_CRON_FILE": cron})
check("rc=1" in r.stdout, "no hourly update line: not scheduled")
r = lib("install_update_timer; cat $PISO_CRON_FILE; update_timer_ok; echo rc=$?", env={"PISO_CRON_FILE": cron})
check("self-update auto" in r.stdout and "something" in r.stdout, "installing the timer adds the hourly line and keeps the others: " + r.stdout)
_lines = [ln for ln in r.stdout.splitlines() if "update auto" in ln]
check(len(_lines) == 2 and any("box-update auto" in ln for ln in _lines) and len({ln.split()[0] for ln in _lines}) == 2,
      "and a second line for the coin box's firmware, at a different minute: " + r.stdout)
r = lib("install_update_timer; install_update_timer; grep -c 'update auto' $PISO_CRON_FILE", env={"PISO_CRON_FILE": cron})
check(r.stdout.strip().endswith("2"), "installing the timer twice leaves one line of each: " + r.stdout)
for _tool in ("pgrep", "pidof"):  # (both are asked: the machine running the test may really have a scheduler)
    open(f"{bindir}/{_tool}", "w").write("#!/bin/sh\n[ -z \"$FAKE_CRON\" ] || exit 0\nexit 1\n"); os.chmod(f"{bindir}/{_tool}", 0o755)
check("rc=1" in lib("update_timer_ok; echo rc=$?", env={"PISO_CRON_FILE": cron}).stdout, "a scheduler that is not running is reported")
check("rc=0" in lib("update_timer_ok; echo rc=$?", env={"PISO_CRON_FILE": cron, "FAKE_CRON": "1"}).stdout, "and a running one passes")
check("rc=0" in lib("update_check_recent; echo rc=$?").stdout, "a router that has not run the check yet gets the time")
check("rc=0" in lib("conf_set UPDATE_LAST_CHECK $(date +%s); update_check_recent; echo rc=$?").stdout, "a check that just ran passes")
check("rc=1" in lib("conf_set UPDATE_LAST_CHECK $(($(date +%s) - 20000)); update_check_recent; echo rc=$?").stdout, "one that ran 5 hours ago is reported")
auto = text.split("cmd_self_update() {")[1].split("\n}\n")[0]
check("UPDATE_LAST_CHECK" in auto and auto.index("UPDATE_LAST_CHECK") < auto.index("router.json"), "the hourly run records when it ran")
check("update_timer_ok" in text.split("check_all() {")[1].split("\n}\n")[0] and "update_check_recent" in text.split("check_all() {")[1].split("\n}\n")[0], "status checks the schedule")
os.remove(f"{bindir}/pgrep"); os.remove(f"{bindir}/pidof")

# ---- the box's own Wi-Fi password -----------------------------------------------------------------------------------------------
Box.wifi = None
open(f"{tmp}/uci.log", "w").close()
r = lib(NAMES + 'conf_set BOX_ADMIN_PASS NewPassw0rdXYZ; rotate_box_wifi; echo rc=$?; echo "ROT=$(conf_get BOX_WIFI_ROTATED) KEY=$(conf_get BOX_WIFI_PASS_NEW)"; box_wifi_key',
        env={"BOX_IP": addr, "ROTATE_SETTLE": "0"})
m = re.search(r"KEY=(\S+)", r.stdout)
check("rc=0" in r.stdout and "ROT=1" in r.stdout and m and Box.wifi == m.group(1) and len(m.group(1)) == 20 and m.group(1) != "PisoCoinBox@Setup",
      "the box gets its own random 20-character Wi-Fi password through its API: " + r.stdout + r.stderr)
check(f"set wireless.box_ap.key={Box.wifi}" in open(f"{tmp}/uci.log").read(), "and the router's box network takes the same one")
check(Box.wifi_ssid == "PisoCoinBox-a1b2" and "set wireless.box_ap.ssid=PisoCoinBox-a1b2" in open(f"{tmp}/uci.log").read(),
      f"the box is also told this router's own network name, and the router's network takes it: {Box.wifi_ssid!r}")
rn = lib("box_net_ssid; echo; cp $PISO_CONF $PISO_CONF.keep; conf_set BOX_WIFI_ROTATED 0; box_net_ssid; mv $PISO_CONF.keep $PISO_CONF")
check(rn.stdout.split() == ["PisoCoinBox-a1b2", "PisoCoinBox"], "the box network has the router's name once the box has it, the built-in one before: " + rn.stdout)
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
# a pairing that times out right after a swap to the box's own password: the router goes back to it and keeps it "rotated"
open(f"{tmp}/uci.log", "w").close()
r = lib('conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; '
        'cmd_box_diag() { echo "DIAG key=$(uci -q get wireless.box_ap.key) ROT=$(conf_get BOX_WIFI_ROTATED)"; }; (pair_box); echo "after ROT=$(conf_get BOX_WIFI_ROTATED) KEY=$(box_wifi_key)"',
        env={"PAIR_WAIT": "60"})
ul = open(f"{tmp}/uci.log").read()
set_keys = [l for l in ul.splitlines() if l.startswith("set wireless.box_ap.key=")]
check("after ROT=1 KEY=OwnPassw0rd1234567" in r.stdout, "a pairing that fails keeps the box's own password for the next try: " + r.stdout + r.stderr)
check(set_keys and set_keys[-1] == "set wireless.box_ap.key=OwnPassw0rd1234567" and "set wireless.box_ap.key=PisoCoinBox@Setup" in ul,
      "and leaves the router's network on it, after offering both: " + str(set_keys))
check("DIAG key=" in r.stdout and "ROT=1" in r.stdout.split("after ROT")[0].split("DIAG key=")[1], "the failure report sees the restored state: " + r.stdout[-300:])
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "10"})
check("rc=0" not in r.stdout and "not on the air" in r.stderr + r.stdout, "a missing box network is reported as such: " + r.stdout + r.stderr)
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "10"})
check("rc=0" not in r.stdout and "did not join" in r.stderr + r.stdout, "a network that is up but never joined keeps the original message: " + r.stdout + r.stderr)

# ---- feature 011 part B: a box that refuses its new Wi-Fi changes nothing; one that does not come back is told how to recover ----
def _conf_value(path, name):
    for ln in open(path).read().splitlines():
        if ln.startswith(name + "="):
            return ln.split("=", 1)[1].strip("'")
    return ""


NAMES2 = NAMES + "conf_set BOX_ADMIN_PASS NewPassw0rdXYZ; "
Box.admin = "NewPassw0rdXYZ"; Box.wifi = None; Box.wifi_ssid = None; Box.refuse_wifi = True
# (1) a router whose box still has the built-in password: the box says no
cf = f"{tmp}/conf_refuse1"
open(f"{tmp}/uci.log", "w").close()
r = lib(NAMES2 + 'conf_set BOX_WIFI_ROTATED 0; rotate_box_wifi; echo "rc=$?"; echo "ROT=$(conf_get BOX_WIFI_ROTATED) NEW=$(conf_get BOX_WIFI_PASS_NEW)"',
        env={"BOX_IP": addr, "ROTATE_SETTLE": "0", "PISO_CONF": cf})
ul = open(f"{tmp}/uci.log").read()
check("ROT=0 NEW=" in r.stdout and "ROT=0 NEW=\n" in r.stdout + "\n", "a box that refuses leaves BOX_WIFI_ROTATED 0 and no new password stored: " + r.stdout + r.stderr)
check("set wireless.box_ap" not in ul and "wifi reload" not in ul, "and the router's box network is not touched: " + ul)
check("rc=1" in r.stdout and "did not take" in r.stdout and "HTTP 500" in r.stdout, "and it says so and fails, so a command can tell: " + r.stdout)
check(_conf_value(cf, "BOX_SSID_NAME") == "PisoCoinBox-a1b2", "and the stored network name is as it was")
# (2) a router whose box already has its own password and name: the command to do it again must not lose that
cf = f"{tmp}/conf_refuse2"
open(f"{tmp}/uci.log", "w").close()
r = lib(NAMES2 + 'conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; rotate_box_wifi force; echo "rc=$?"; '
        'echo "ROT=$(conf_get BOX_WIFI_ROTATED) NEW=$(conf_get BOX_WIFI_PASS_NEW) KEY=$(box_wifi_key)"',
        env={"BOX_IP": addr, "ROTATE_SETTLE": "0", "PISO_CONF": cf})
check("rc=1" in r.stdout and "ROT=1 NEW=OwnPassw0rd1234567 KEY=OwnPassw0rd1234567" in r.stdout,
      "a forced run that the box refuses keeps BOX_WIFI_ROTATED 1 and the box's own password (fact B7): " + r.stdout + r.stderr)
check("set wireless.box_ap" not in open(f"{tmp}/uci.log").read(), "and does not touch the router's box network")
# (3) the piso-setup command itself (it used to set BOX_WIFI_ROTATED 0 before asking, and leave it there)
open(f"{bindir}/id", "w").write("#!/bin/sh\necho 0\n"); os.chmod(f"{bindir}/id", 0o755)
open(f"{tmp}/conf", "w").write("")
for _k, _v in (("BOX_ADMIN_PASS", "NewPassw0rdXYZ"), ("BOX_WIFI_ROTATED", "1"), ("BOX_WIFI_PASS_NEW", "OwnPassw0rd1234567"), ("KIOSK_SSID_NAME", "PisoKiosk-a1b2"), ("BOX_SSID_NAME", "PisoCoinBox-a1b2")):
    open(f"{tmp}/conf", "a").write(f"{_k}='{_v}'\n")
r = run("rotate-box-wifi", env={"BOX_IP": addr, "ROTATE_SETTLE": "0"})
check(_conf_value(f"{tmp}/conf", "BOX_WIFI_ROTATED") == "1" and _conf_value(f"{tmp}/conf", "BOX_WIFI_PASS_NEW") == "OwnPassw0rd1234567",
      "piso-setup rotate-box-wifi against a refusing box leaves BOX_WIFI_ROTATED 1 (fact B7): " + r.stdout + r.stderr)
os.remove(f"{bindir}/id")
# (4) the box takes it but never comes back: the router keeps the new name and key, the message says what to do
Box.refuse_wifi = False; Box.wifi = None
cf = f"{tmp}/conf_noreturn"
open(f"{tmp}/uci.log", "w").close()
r = lib(NAMES2 + 'conf_set BOX_WIFI_ROTATED 0; box_up() { return 1; }; cmd_box_diag() { echo DIAG; }; (rotate_box_wifi); echo "rc=$?"; '
        'echo "ROT=$(conf_get BOX_WIFI_ROTATED) NEW=$(conf_get BOX_WIFI_PASS_NEW)"',
        env={"BOX_IP": addr, "ROTATE_SETTLE": "0", "ROTATE_WAIT": "0", "PISO_CONF": cf})
ul = open(f"{tmp}/uci.log").read()
out = r.stdout + r.stderr
check("rc=0" not in r.stdout and "Power-cycle" in out and "piso-setup status" in out, "a box that does not come back: the message says power-cycle it and run piso-setup status: " + out)
check(Box.wifi and "ROT=1 NEW=" + Box.wifi in r.stdout and f"set wireless.box_ap.key={Box.wifi}" in ul and "set wireless.box_ap.ssid=PisoCoinBox-a1b2" in ul,
      "and the router keeps the new name and key (the box has them): " + out)

# ---- feature 011 part B: piso-setup box-wifi-name gives the box network the router's own name ----------------------------------
def _bwn_conf(name, **kv):
    path = f"{tmp}/conf_{name}"
    open(path, "w").write("".join(f"{k}='{v}'\n" for k, v in kv.items()))
    return path


def _bwn(path, code="own_box_wifi_name", env=None, ok=True):
    open(f"{tmp}/uci.log", "w").close()
    n0 = len(Box.log)
    r = lib("ASSUME_YES=1; " * ok + "(" + code + '); echo "rc=$?"; echo "ROT=$(conf_get BOX_WIFI_ROTATED) BN=$(conf_get BOX_SSID_NAME) KN=$(conf_get KIOSK_SSID_NAME) KP=$(conf_get KIOSK_PASS) NEW=$(conf_get BOX_WIFI_PASS_NEW)"',
            env=dict({"BOX_IP": addr, "ROTATE_SETTLE": "0", "PISO_CONF": path}, **(env or {})))
    changes = "".join(ln + "\n" for ln in open(f"{tmp}/uci.log").read().splitlines() if not ln.startswith(("get ", "-q get ", "show ")))   # (reading is not changing)
    return r, changes, len(Box.log) - n0


def _cv(path, name):
    return _conf_value(path, name)


MAC = "AA:BB:CC:DD:EE:01"
Box.admin = "NewPassw0rdXYZ"; Box.refuse_wifi = False; Box.wifi = None; Box.wifi_ssid = None
# an older router: shared names (it has a stored phone password and no stored names)
cf = _bwn_conf("old", KIOSK_PASS="kp-old-1", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ", BOX_WIFI_ROTATED="1", BOX_WIFI_PASS_NEW="OwnPassw0rd1234567")
r, ul, sent = _bwn(cf)
out = r.stdout + r.stderr
check("four minutes" in out and "kept" in out, "it says the box is off its network for up to four minutes and that coins are kept: " + out)
check("rc=0" in r.stdout and Box.wifi_ssid == "PisoCoinBox-a1b2" and Box.wifi and len(Box.wifi) >= 16,
      f"an older router: the box is sent wifi_ssid=PisoCoinBox-<id> and a password in one request: {Box.wifi_ssid!r} {Box.wifi!r} " + out)
check("set wireless.box_ap.ssid=PisoCoinBox-a1b2" in ul and f"set wireless.box_ap.key={Box.wifi}" in ul and "ROT=1 BN=PisoCoinBox-a1b2" in r.stdout,
      "and the router's box network takes both, and stores BOX_SSID_NAME and BOX_WIFI_ROTATED 1: " + ul + r.stdout)
check("maclist" not in ul and "macfilter" not in ul, "and the MAC lock is not touched: " + ul)
check("kiosk" not in ul.lower() and " KN= KP=kp-old-1 " in r.stdout, "the rental phones' network, KIOSK_SSID_NAME and KIOSK_PASS are not touched: " + ul + r.stdout)
check(Box.wifi != "OwnPassw0rd1234567" and _cv(cf, "BOX_WIFI_PASS_NEW") == Box.wifi, "and the password is a new one (the old router had one already): " + Box.wifi)
r2 = lib('echo "$BOX_SSID $KIOSK_SSID"', env={"PISO_CONF": cf})
check(r2.stdout.strip() == "PisoCoinBox-a1b2 PisoKiosk", "and the next time the router loads its names the box network keeps the new one, the phones the shared one: " + r2.stdout)
# a newer router whose password change failed once (names stored, BOX_WIFI_ROTATED 0)
cf = _bwn_conf("new", KIOSK_SSID_NAME="PisoKiosk-a1b2", BOX_SSID_NAME="PisoCoinBox-a1b2", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ", BOX_WIFI_ROTATED="0")
Box.wifi = None; Box.wifi_ssid = None
r, ul, sent = _bwn(cf)
check("rc=0" in r.stdout and "ROT=1" in r.stdout and Box.wifi_ssid == "PisoCoinBox-a1b2" and "set wireless.box_ap.ssid=PisoCoinBox-a1b2" in ul,
      "a newer router with BOX_WIFI_ROTATED 0 works the same: " + r.stdout + r.stderr)
# already has its own name: nothing changes
cf = _bwn_conf("own", KIOSK_SSID_NAME="PisoKiosk-a1b2", BOX_SSID_NAME="PisoCoinBox-a1b2", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ", BOX_WIFI_ROTATED="1", BOX_WIFI_PASS_NEW="OwnPassw0rd1234567")
before = open(cf).read()
r, ul, sent = _bwn(cf)
check("already has its own name" in r.stdout + r.stderr and sent == 0 and ul == "" and open(cf).read() == before,
      "a router whose box network already has its own name: says so and changes nothing: " + r.stdout + r.stderr)
# cannot start: no paired box, box silent, a name the box could not use
cf = _bwn_conf("nomac", KIOSK_PASS="kp-old-1", BOX_ADMIN_PASS="NewPassw0rdXYZ")
before = open(cf).read()
r, ul, sent = _bwn(cf)
check("rc=0" not in r.stdout and "pair" in r.stdout + r.stderr and sent == 0 and ul == "" and open(cf).read() == before,
      "without a paired box it stops with the reason and changes nothing: " + r.stdout + r.stderr)
cf = _bwn_conf("silent", KIOSK_PASS="kp-old-1", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ")
before = open(cf).read()
r, ul, sent = _bwn(cf, env={"BOX_IP": "127.0.0.1:1"})
check("rc=0" not in r.stdout and "answer" in r.stdout + r.stderr and ul == "" and open(cf).read() == before,
      "with the box not answering it stops with the reason and changes nothing: " + r.stdout + r.stderr)
for _bad in ("a b;x", "x" * 40, ""):
    cf = _bwn_conf("badname", KIOSK_PASS="kp-old-1", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ")
    before = open(cf).read()
    r, ul, sent = _bwn(cf, env={"PISO_ROUTER_ID": _bad} if _bad else {"PISO_ROUTER_ID": "x/y"})
    check("rc=0" not in r.stdout and "name" in r.stdout + r.stderr and sent == 0 and ul == "" and open(cf).read() == before,
          f"a name the box could not use ({_bad!r}) is refused before anything is sent: " + r.stdout + r.stderr)
# the question: no answer means no change; yes goes ahead
cf = _bwn_conf("ask", KIOSK_PASS="kp-old-1", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ")
before = open(cf).read()
r, ul, sent = _bwn(cf, code="echo n | own_box_wifi_name", ok=False)
check("rc=0" in r.stdout and sent == 0 and ul == "" and open(cf).read() == before, "answering no changes nothing: " + r.stdout + r.stderr)
r, ul, sent = _bwn(cf, code="echo y | own_box_wifi_name", ok=False)
check("ROT=1 BN=PisoCoinBox-a1b2" in r.stdout and sent >= 1, "answering yes goes ahead: " + r.stdout + r.stderr)
# the box says no: the router is exactly as before (an older router has no stored box name again)
cf = _bwn_conf("refused", KIOSK_PASS="kp-old-1", BOX_MAC=MAC, BOX_ADMIN_PASS="NewPassw0rdXYZ")
before = open(cf).read()
Box.refuse_wifi = True
r, ul, sent = _bwn(cf)
check("rc=0" not in r.stdout and "set wireless.box_ap" not in ul and open(cf).read() == before,
      "a box that refuses: BOX_SSID_NAME, BOX_WIFI_ROTATED, BOX_WIFI_PASS_NEW and the box network are as before: " + r.stdout + r.stderr + open(cf).read())
Box.refuse_wifi = False
# the command exists and runs as root only
open(f"{bindir}/id", "w").write("#!/bin/sh\necho 0\n"); os.chmod(f"{bindir}/id", 0o755)
open(f"{tmp}/conf", "w").write(f"KIOSK_PASS='kp-old-1'\nBOX_MAC='{MAC}'\nBOX_ADMIN_PASS='NewPassw0rdXYZ'\n")
Box.wifi_ssid = None
r = run("--yes", "box-wifi-name", env={"BOX_IP": addr, "ROTATE_SETTLE": "0"})
check(Box.wifi_ssid == "PisoCoinBox-a1b2" and _cv(f"{tmp}/conf", "BOX_SSID_NAME") == "PisoCoinBox-a1b2",
      "piso-setup --yes box-wifi-name runs the command: " + r.stdout + r.stderr)
os.remove(f"{bindir}/id")
check("box-wifi-name" in text.split("main() {")[1].split("case \"$CMD\" in")[0], "and it is in the list of commands")

# ---- feature 011 part B: status and the summary tell the owner when the box network is still called PisoCoinBox ---------------
NOTICE = "Coin box network: PisoCoinBox (the shared setup name: a second router being set up nearby can take this box's place). Fix: piso-setup box-wifi-name"
for _label, _kv, _want in (
    ("an older router (shared names)", dict(KIOSK_PASS="kp-old-1", BOX_MAC=MAC), True),
    ("a newer router whose box password change failed (BOX_WIFI_ROTATED 0)", dict(KIOSK_SSID_NAME="PisoKiosk-a1b2", BOX_SSID_NAME="PisoCoinBox-a1b2", BOX_MAC=MAC, BOX_WIFI_ROTATED="0"), True),
    ("a router whose box network has its own name", dict(KIOSK_SSID_NAME="PisoKiosk-a1b2", BOX_SSID_NAME="PisoCoinBox-a1b2", BOX_MAC=MAC, BOX_WIFI_ROTATED="1", BOX_WIFI_PASS_NEW="OwnPassw0rd1234567"), False),
    ("a router with no paired box yet", dict(KIOSK_PASS="kp-old-1"), False),
):
    cf = _bwn_conf("notice", **_kv)
    sm = f"{tmp}/summary_notice"
    r = lib('check_all() { echo CHECKS; return 0; }; cmd_status; echo "rc=$?"; write_summary; cat "$SUMMARY"', env={"PISO_CONF": cf, "PISO_SUMMARY": sm, "BOX_IP": addr})
    status_part, _, summary_part = r.stdout.partition("rc=0")
    check((NOTICE in status_part) == _want, f"piso-setup status {'shows' if _want else 'does not show'} the box network line for {_label}: " + r.stdout[-400:] + r.stderr[-300:])
    check((NOTICE in summary_part) == _want, f"and the summary {'shows' if _want else 'does not show'} it: " + summary_part[-400:])
    check("rc=0" in r.stdout, "and the line does not make status fail")

# ---- feature 015 (AO-06): status shows what the monitor wrote to alerts.txt, with or without Telegram --------------------------------
aroot = f"{tmp}/alertsroot"
os.makedirs(f"{aroot}/etc/coinslot.d", exist_ok=True)
r = lib('check_all() { echo CHECKS; return 0; }; cmd_status; echo "rc=$?"', env={"PISO_ROOT": aroot})
check("Recent alerts" not in r.stdout and "rc=0" in r.stdout, "status shows no alerts section when there is no alerts.txt: " + r.stdout[-300:])
open(f"{aroot}/etc/coinslot.d/alerts.txt", "w").write("".join(f"2026-10-24 10:0{i} alert number {i}\n" for i in range(7)))
r = lib('check_all() { echo CHECKS; return 0; }; cmd_status; echo "rc=$?"', env={"PISO_ROOT": aroot})
check("Recent alerts" in r.stdout and "alert number 6" in r.stdout and "alert number 2" in r.stdout and "alert number 1" not in r.stdout and "rc=0" in r.stdout,
      "status shows the last 5 alerts and does not fail because of them: " + r.stdout[-400:])

# ---- feature 032: status tells the owner the months of sale detail kept, the months deleted, and the lifetime total ---------------------
fp = f"{tmp}/fake_portal_ledger"
open(fp, "w").write('#!/bin/sh\n[ "$1" = ledger ] && echo "Sales record: 3 months of detail kept, 20 months deleted on purpose (their totals are kept). Lifetime total: 135002 pesos."\n')
os.chmod(fp, 0o755)
r = lib('check_all() { echo CHECKS; return 0; }; cmd_status; echo "rc=$?"', env={"PISO_ROOT": aroot, "PISO_PORTAL_BIN": fp})
check("Sales record: 3 months of detail kept, 20 months deleted on purpose" in r.stdout and "Lifetime total: 135002 pesos." in r.stdout and "rc=0" in r.stdout,
      "status shows the sales record line: " + r.stdout[-400:])
r = lib('check_all() { echo CHECKS; return 0; }; cmd_status; echo "rc=$?"', env={"PISO_ROOT": aroot, "PISO_PORTAL_BIN": f"{tmp}/no_such_portal"})
check("Sales record" not in r.stdout and "rc=0" in r.stdout, "and a router with no program installed shows no such line and does not fail: " + r.stdout[-300:])

# ---- feature 011 part B: the pairing tips say that another router nearby may answer the setup name ----------------------------
PAIR_TIP = "another PisoHub router nearby may answer the setup name PisoCoinBox: switch it off for a few minutes, or run piso-setup box-wifi-name on it"
cf = _bwn_conf("pairtips", KIOSK_PASS="kp-old-1")
open(f"{tmp}/uci.log", "w").close()
r = lib('PAIR_WAIT=160; sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; cmd_box_diag() { :; }; (pair_box); echo "rc=$?"', env={"PISO_CONF": cf})
out = r.stdout + r.stderr
tips = [ln for ln in out.splitlines() if PAIR_TIP in ln]
check(len(tips) >= 3, f"the 60 s tip, the 150 s tip and the failure message each carry the nearby-router tip (found {len(tips)}): " + out[-900:])
check("no box yet after a minute" in out and "still nothing" in out and "last try" not in out, "and the tips that were there are still there")
check(out.rstrip().splitlines()[-2:] and PAIR_TIP in "\n".join(out.rstrip().splitlines()[-3:]) and "did not join" in out, "the 'did not join' message ends with it: " + out[-500:])
ul = open(f"{tmp}/uci.log").read()
check("set wireless.box_ap.ssid=PisoCoinBox\n" in ul and "box_ap.ssid=PisoCoinBox-" not in ul.split("macfilter")[0], "pairing still opens the box network as PisoCoinBox, the name new boxes know: " + ul[:400])
r = lib('PAIR_WAIT=70; sleep() { :; }; box_station() { :; }; box_ifname() { :; }; cmd_box_diag() { :; }; (pair_box); echo "rc=$?"', env={"PISO_CONF": cf})
check("not broadcasting" in r.stdout and PAIR_TIP not in r.stdout, "with no box network on the air the tip stays about the radio (another router is not the cause): " + r.stdout[-400:])

# ---- feature 041: the first setup needs the router's internet (the copied-packages path of feature 011 part A is gone) --------------
NONET = (f'ping() {{ echo PING >> {tmp}/nonet.log; return 1; }}; wget() {{ echo WGET >> {tmp}/nonet.log; return 1; }}; curl() {{ echo CURL >> {tmp}/nonet.log; return 1; }}; '
         f'nslookup() {{ echo DNS >> {tmp}/nonet.log; return 1; }}; ')
open(f"{tmp}/nonet.log", "w").close()
r = lib(NONET + 'wan_ip() { :; }; (preflight_network); echo "rc=$?"')
check("rc=0" not in r.stdout and "no WAN address" in r.stdout + r.stderr and "needs internet for the first setup" in r.stdout + r.stderr,
      "no WAN address stops the setup and the message names the requirement: " + r.stdout + r.stderr)
r = lib(NONET + 'wan_ip() { echo 192.168.1.50; }; (preflight_network); echo "rc=$?"')
check("rc=0" not in r.stdout and "needs internet for the first setup" in r.stdout + r.stderr and "PING" in open(f"{tmp}/nonet.log").read(),
      "a WAN address with no ping answer stops the setup and the message names the requirement: " + r.stdout + r.stderr)
r = lib(NONET + 'wan_ip() { echo 10.0.0.77; }; (preflight_network); echo "rc=$?"')
check("rc=0" not in r.stdout and "same range" in r.stdout + r.stderr, "a WAN address that clashes with the LAN is still refused: " + r.stdout + r.stderr)
r = lib('wan_ip() { echo 192.168.1.50; }; ping() { return 0; }; (preflight_network); echo "rc=$?"')
check("rc=0" in r.stdout and "Internet: ok" in r.stdout, "a router with a WAN address and a ping answer passes the network preflight: " + r.stdout + r.stderr)
# the packages always start with opkg update (the router's own signed feeds)
ostub = f'opkg() {{ echo "opkg $*" >> {tmp}/nonet.log; case "$1" in list-installed) printf "%s\\n" "$HAVE" ;; esac; return 0; }}; stop_opennds() {{ echo STOPPED >> {tmp}/nonet.log; }}; '
open(f"{tmp}/nonet.log", "w").close()
r = lib(ostub + '(install_packages); echo "rc=$?"', env={"HAVE": ""})
nl = open(f"{tmp}/nonet.log").read()
check("opkg update" in nl and "opkg install opennds" in nl and "STOPPED" in nl, "install_packages starts with opkg update and installs what is missing: " + nl + r.stdout + r.stderr)
check("PISO_LOCAL_PKGS" not in text and "install_local_packages" not in text, "the installer carries no copied-packages path any more")
check("unset SHOP_PRODUCT" in text.split("main() {")[1], "a command other than the first setup (an update included) never changes the product")
# ---- box-diag: where the router <-> box link breaks -----------------------------------------------------------------------------
def box_diag(setup_code):
    return lib(setup_code + "; cmd_box_diag", env={"BOX_IP": "127.0.0.1:1"})   # nothing listens there: the box does not answer
r = box_diag('box_ifname() { :; }; conf_set BOX_MAC AA:BB:CC:DD:EE:01')
check("NOT on the air" in r.stdout and "verdict: the router is not broadcasting" in r.stdout, "box-diag: a missing box network is the verdict: " + r.stdout + r.stderr)
r = box_diag('box_ifname() { echo wlan0-3; }; iwinfo() { :; }; conf_set BOX_MAC AA:BB:CC:DD:EE:01; conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; '
             'uci() { case "$*" in *box_ap.key*) echo SomethingElse123;; esac; }')
check("differs from the one stored for the box" in r.stdout, "box-diag: a router password that differs from the box's is the verdict: " + r.stdout + r.stderr)
check("SomethingElse123" not in r.stdout and "OwnPassw0rd1234567" not in r.stdout, "box-diag never prints a password")
r = box_diag('box_ifname() { echo wlan0-3; }; iwinfo() { :; }; conf_set BOX_MAC AA:BB:CC:DD:EE:01; conf_set BOX_WIFI_ROTATED 0; uci() { case "$*" in *box_ap.key*) echo PisoCoinBox@Setup;; esac; }')
check("verdict: the box has not joined" in r.stdout, "box-diag: right password, nobody joined -> read the box's serial console: " + r.stdout + r.stderr)
r = box_diag('box_ifname() { echo wlan0-3; }; iwinfo() { [ "$2" = assoclist ] && echo "AA:BB:CC:DD:EE:01  -50 dBm / -95 dBm"; }; conf_set BOX_MAC AA:BB:CC:DD:EE:01; conf_set BOX_WIFI_ROTATED 0; uci() { case "$*" in *box_ap.key*) echo PisoCoinBox@Setup;; esac; }')
check("does not answer" in r.stdout and "lease" in r.stdout, "box-diag: joined but silent -> address lease: " + r.stdout + r.stderr)
r = box_diag('box_ifname() { echo wlan0-3; }; iwinfo() { [ "$2" = assoclist ] && echo "AA:BB:CC:DD:EE:01  -50 dBm / -95 dBm"; }; box_up() { return 0; }; curl() { :; }; '
             'conf_set BOX_MAC AA:BB:CC:DD:EE:01; conf_set BOX_WIFI_ROTATED 0; uci() { case "$*" in *box_ap.key*) echo PisoCoinBox@Setup;; esac; }')
check("verdict: the link works" in r.stdout, "box-diag: everything fine -> the link works: " + r.stdout + r.stderr)
check("box-diag) cmd_box_diag" in text and "cmd_box_diag 2>&1" in text.split("cmd_diag() {")[1].split("\n}\n")[0], "box-diag is a command and part of piso-setup diag")
# a box that is listed as joined while the router's log says its password is refused is not paired: the setup says so and stops
os.path.exists(f"{tmp}/logn") and os.remove(f"{tmp}/logn")
r = lib(f'sleep() {{ :; }}; logread() {{ c=$(cat {tmp}/logn 2>/dev/null || echo 0); c=$((c + 1)); echo $c > {tmp}/logn; [ "$c" -gt 1 ] && echo "hostapd: phy0-ap2: AP-STA-POSSIBLE-PSK-MISMATCH 84:c7:bb:e1:57:60"; }}; '
        'box_station() { echo 84:C7:BB:E1:57:60; }; box_ifname() { echo wlan0-3; }; cmd_box_diag() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "20"})
out = r.stdout + r.stderr
check("refuses its Wi-Fi password" in out and "Factory reset the box" in out and "the coin box joined" not in out and "rc=0" not in out,
      "a refused box is not taken for a paired one, and the setup says to factory reset it: " + out[-700:])
r = box_diag('box_ifname() { echo wlan0-3; }; iwinfo() { [ "$2" = assoclist ] && echo "AA:BB:CC:DD:EE:01  -50 dBm / -95 dBm"; }; logread() { echo "hostapd: AP-STA-POSSIBLE-PSK-MISMATCH aa:bb:cc:dd:ee:01"; }; '
             'conf_set BOX_MAC AA:BB:CC:DD:EE:01; conf_set BOX_WIFI_ROTATED 0; uci() { case "$*" in *box_ap.key*) echo PisoCoinBox@Setup;; esac; }')
check("verdict: the box is trying to join but the router refuses its Wi-Fi password" in r.stdout, "box-diag: password mismatches in the log -> factory reset the box: " + r.stdout + r.stderr)
# ---- every router has Wi-Fi names of its own (two routers in range never compete for the same phones and coin box) -------------
def names_of(conf_lines="", router_id="a1b2", root=None):
    conf = f"{tmp}/names-{abs(hash((conf_lines, router_id, root)))}"
    open(conf, "w").write(conf_lines)
    env = {"PISO_CONF": conf, "PISO_ROUTER_ID": router_id}
    if root:
        env["PISO_ROOT"] = root
    return lib('echo "$KIOSK_SSID|$BOX_SSID|$(box_net_ssid)"', env=env).stdout.strip(), conf
n1, _ = names_of(router_id="0f3c")
n2, _ = names_of(router_id="9d41")
check(n1 == "PisoKiosk-0f3c|PisoCoinBox-0f3c|PisoCoinBox" and n2 == "PisoKiosk-9d41|PisoCoinBox-9d41|PisoCoinBox",
      f"a new router gets names with its own ending (the box network keeps the built-in name until the box is paired): {n1} {n2}")
check(n1.split("|")[0] != n2.split("|")[0] and n1.split("|")[1] != n2.split("|")[1], "two routers never share a name")
legacy, _ = names_of("KIOSK_PASS='abc12345'\n", router_id="0f3c")
check(legacy == "PisoKiosk|PisoCoinBox|PisoCoinBox", f"a router that was set up before keeps the old names, so its phones and box are untouched: {legacy}")
stored, _ = names_of("KIOSK_SSID_NAME='PisoKiosk-aaaa'\nBOX_SSID_NAME='PisoCoinBox-aaaa'\nBOX_WIFI_ROTATED='1'\n", router_id="0f3c")
check(stored == "PisoKiosk-aaaa|PisoCoinBox-aaaa|PisoCoinBox-aaaa", f"names a setup chose stay, whatever the router's MAC says later: {stored}")
os.makedirs(f"{tmp}/sysroot/sys/class/net/br-lan", exist_ok=True)
open(f"{tmp}/sysroot/sys/class/net/br-lan/address", "w").write("AA:BB:CC:DD:E1:B2\n")
fromsys, conf = names_of(router_id="", root=f"{tmp}/sysroot")
check(fromsys.startswith("PisoKiosk-e1b2|PisoCoinBox-e1b2"), f"the ending comes from the router's own MAC address: {fromsys}")
r = lib('remember_wifi_names; conf_get KIOSK_SSID_NAME; conf_get BOX_SSID_NAME', env={"PISO_CONF": f"{tmp}/names-remember", "PISO_ROUTER_ID": "7c70"})
check(r.stdout.split() == ["PisoKiosk-7c70", "PisoCoinBox-7c70"], "a setup stores them before it writes any Wi-Fi setting: " + r.stdout)
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/names-dry", "PISO_ROUTER_ID": "7c70"})
check("set wireless.kiosk_radio0.ssid='PisoKiosk-7c70'" in r.stdout and "set wireless.box_ap.ssid='PisoCoinBox'" in r.stdout,
      "the settings use the router's kiosk name, and the built-in name for the box while pairing")
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/names-dry2", "PISO_ROUTER_ID": "7c70", "GUEST_SSID": "PisoKiosk-7c70"})
check(r.returncode != 0 and "cannot be PisoKiosk-7c70" in (r.stdout + r.stderr), "the public name cannot be the router's kiosk name")
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/names-dry3", "PISO_ROUTER_ID": "7c70", "GUEST_SSID": "My PisoCoinBox"})
check(r.returncode != 0 and "may not contain PisoCoinBox" in (r.stdout + r.stderr), "the public name may not contain the coin box's name")
# a re-pair of a box that holds its own password offers the router's own network name with it, and the built-in name with the built-in password
open(f"{tmp}/uci.log", "w").close()
r = lib(NAMES + 'conf_set BOX_WIFI_ROTATED 1; conf_set BOX_WIFI_PASS_NEW OwnPassw0rd1234567; sleep() { :; }; box_up() { return 0; }; '
        'box_station() { [ "$(grep "set wireless.box_ap.key=" ' + tmp + '/uci.log | tail -n 1)" = "set wireless.box_ap.key=OwnPassw0rd1234567" ] && echo AA:BB:CC:DD:EE:01; }; '
        'pair_box; echo "rc=$? ROT=$(conf_get BOX_WIFI_ROTATED)"', env={"PAIR_WAIT": "600"})
ul = [l for l in open(f"{tmp}/uci.log").read().splitlines() if l.startswith("set wireless.box_ap.s") or l.startswith("set wireless.box_ap.key")]
check("rc=0 ROT=1" in r.stdout and ul.index("set wireless.box_ap.ssid=PisoCoinBox") < ul.index("set wireless.box_ap.ssid=PisoCoinBox-a1b2")
      and ul.index("set wireless.box_ap.key=PisoCoinBox@Setup") < ul.index("set wireless.box_ap.key=OwnPassw0rd1234567"),
      "a re-pair offers the built-in name with the built-in password, then its own name with its own password: " + str(ul) + r.stdout + r.stderr)

# ---- tips along the way ------------------------------------------------------------------------------------------------------
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; box_diag() { :; }; cmd_box_diag() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "310"})
out = r.stdout + r.stderr
check("TIP: the coin box's light tells you" in out and "TIP: no box yet after a minute" in out and "TIP: still nothing" in out and "TIP: last try" in out,
      "while pairing waits, the tips come in turn (light, firmware, factory reset, supply): " + out[-900:])
check(out.index("no box yet") < out.index("still nothing") < out.index("last try"), "in that order")
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { :; }; cmd_box_diag() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "65"})
check("TIP: the router is not broadcasting the box network" in r.stdout + r.stderr and "no box yet after a minute" not in r.stdout + r.stderr,
      "the tip fits what is wrong (the network is not on the air): " + r.stdout + r.stderr)
r = lib('sleep() { :; }; box_station() { :; }; box_ifname() { echo wlan0-3; }; cmd_box_diag() { :; }; pair_box; echo rc=$?', env={"PAIR_WAIT": "65", "PISO_TIPS": "0"})
check("TIP:" not in r.stdout + r.stderr, "PISO_TIPS=0 turns the tips off")
check("tip \"found it." in text and "tip \"next: open the coin box page" in text and "tip \"running the setup again is safe" in text, "tips at the join, at the end and after a failure")
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
      and "firewall.piso_admin_block.target=REJECT" in ul and "dest_port=22 80 443 8080 8443 8081 8082" in ul and "commit firewall" in ul, "lock-admin allows the named computers and blocks the rest: " + r.stdout + r.stderr[-300:])
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
check(all(l.startswith(("set firewall.guest_stream", "set firewall.guest_nomodem", "delete firewall.guest_nomodem", "add_list firewall.guest_nomodem", "set firewall.guest_noprivate", "delete firewall.guest_noprivate", "add_list firewall.guest_noprivate", "set firewall.phone_noprivate", "delete firewall.phone_noprivate", "add_list firewall.phone_noprivate", "add_list dhcp.@dnsmasq", "del_list dhcp.@dnsmasq", "set opennds.", "delete opennds.", "add_list opennds.", "set dropbear.", "delete uhttpd.", "add_list uhttpd.", "#"))
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
check(r.returncode != 0 and "installed (old) copy" not in (r.stdout + r.stderr) and "portal program is not installed" in (r.stdout + r.stderr),
      "run from the installed copy it updates from the website (self-update) instead of installing its own files")
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

# ---- space, backups and what a firmware upgrade keeps (96-space-backup.sh) ---------------------------------------------------------
sysconf = f"{tmp}/sysupgrade.conf"
open(sysconf, "w").write("/etc/config/network\n")
r = lib("keep_across_upgrades; keep_across_upgrades; cat $PISO_SYSUPGRADE_CONF", env={"PISO_SYSUPGRADE_CONF": sysconf})
lines = r.stdout.split()
check("/etc/piso-setup.conf" in lines and "/etc/coinslot.d" in lines and "/usr/bin/pisoportal" in lines and "/etc/config/network" in lines,
      "a firmware upgrade keeps the settings, the ledger and the programs, and the owner's own lines: " + r.stdout)
check(len(lines) == len(set(lines)), "running it twice adds nothing twice")
r = lib("work_base", env={"PISO_MEM_KB": "90000"})
check(r.stdout.strip() == "/tmp", "plenty of memory: an update works in memory: " + r.stdout)
r = lib("work_base", env={"PISO_MEM_KB": "12000", "PISO_FLASH_WORK": "/root"})
check(r.stdout.strip() == "/root", "little memory: an update works in flash instead: " + r.stdout)
logf = f"{tmp}/trim.log"
open(logf, "w").write("".join(f"line {i}\n" for i in range(1000)))
r = lib("trim_log; wc -l < $LOG; head -n1 $LOG", env={"PISO_LOG": logf, "PISO_LOG_MAX": "900", "PISO_LOG_KEEP": "600"})
check(r.stdout.split()[0] == "600" and "line 400" in r.stdout, "a long log is cut to its last 600 lines: " + r.stdout)
osr = f"{tmp}/openwrt"
os.makedirs(f"{osr}/etc", exist_ok=True)
fam = {"mipsel_24kc": "mipsel", "mipsel_74kc": "mipsel", "mips_24kc": "mips", "aarch64_cortex-a53": "aarch64", "aarch64_generic": "aarch64",
       "arm_cortex-a7_neon-vfpv4": "armv7", "x86_64": "x86_64", "arm_arm1176jzf-s_vfp": "", "riscv64_riscv64": ""}
for pkg, want in fam.items():
    open(f"{osr}/etc/openwrt_release", "w").write(f"DISTRIB_ARCH='{pkg}'\n")
    got = lib("router_family", env={"PISO_ROOT": osr}).stdout.strip()
    check(got == want, f"the CPU family of {pkg} is '{want}': got '{got}'")
open(f"{osr}/etc/openwrt_release", "w").write("DISTRIB_ARCH='aarch64_cortex-a53'\n")
r = lib("check_arch; echo reached", env={"PISO_ROOT": osr})
check("aarch64_cortex-a53" in r.stdout + r.stderr and "piso-setup-aarch64.sh" in r.stdout + r.stderr and "reached" not in r.stdout and "Nothing was changed" in r.stdout + r.stderr,
      "another CPU family is refused before anything changes, and the right file is named: " + r.stdout + r.stderr)
open(f"{osr}/etc/openwrt_release", "w").write("DISTRIB_ARCH='riscv64_riscv64'\n")
r = lib("check_arch; echo reached", env={"PISO_ROOT": osr})
check("no portal program built yet" in r.stdout + r.stderr and "reached" not in r.stdout, "a CPU with no build is refused: " + r.stdout + r.stderr)
open(f"{osr}/etc/openwrt_release", "w").write("DISTRIB_ARCH='mipsel_24kc'\n")
check("reached" in lib("check_arch; echo reached", env={"PISO_ROOT": osr}).stdout, "mipsel_24kc passes (this file is built for mipsel)")
open(f"{osr}/etc/openwrt_release", "w").write("DISTRIB_ARCH='aarch64_cortex-a53'\n")
check("reached" in lib("PISO_ARCH=aarch64; check_arch; echo reached", env={"PISO_ROOT": osr}).stdout, "an aarch64 router passes with the aarch64 file")
# export / import round trip
root = f"{tmp}/exp"
for d in ("etc/coinslot.d",):
    os.makedirs(f"{root}/{d}", exist_ok=True)
open(f"{root}/etc/piso-setup.conf", "w").write("GW_KEY='abc'\n")
open(f"{root}/etc/coinslot.d/revenue.csv", "w").write("1,2,3\n")
bk = f"{tmp}/backup.tar.gz"
r = lib(f"cmd_export {bk}", env={"PISO_ROOT": root, "PISO_TEST_NONROOT": "1"})
check(os.path.isfile(bk) and oct(os.stat(bk).st_mode & 0o777) == "0o600" and "Backup written" in r.stdout, "export writes a private backup: " + r.stdout + r.stderr)
os.remove(f"{root}/etc/piso-setup.conf"); os.remove(f"{root}/etc/coinslot.d/revenue.csv")
r = lib(f"ASSUME_YES=1; cmd_import {bk}", env={"PISO_ROOT": root, "PISO_TEST_NONROOT": "1"})
check(open(f"{root}/etc/piso-setup.conf").read() == "GW_KEY='abc'\n" and os.path.isfile(f"{root}/etc/coinslot.d/revenue.csv") and "Restored" in r.stdout, "import puts the settings and the ledger back: " + r.stdout + r.stderr)
import subprocess as _sp
evil = f"{tmp}/evil.tar.gz"
_sp.run(["tar", "-C", root, "-czf", evil, "etc/piso-setup.conf", "etc/coinslot.d/revenue.csv"])
open(f"{root}/etc/passwd", "w").write("x")
_sp.run(["tar", "-C", root, "-czf", evil, "etc/passwd"])
r = lib(f"ASSUME_YES=1; cmd_import {evil}", env={"PISO_ROOT": root, "PISO_TEST_NONROOT": "1"})
check("refused" in r.stdout + r.stderr, "a backup with other files is refused: " + r.stdout + r.stderr)
# RT-22: a path that climbs out of etc/coinslot.d with .. is refused, and nothing outside the backup's folders is written
import tarfile as _tf
dotdot = f"{tmp}/dotdot.tar.gz"
with _tf.open(dotdot, "w:gz") as _t:
    _info = _tf.TarInfo("etc/coinslot.d/../passwd"); _data = b"owned"; _info.size = len(_data)
    import io as _io; _t.addfile(_info, _io.BytesIO(_data))
open(f"{root}/etc/passwd", "w").write("x")
r = lib(f"ASSUME_YES=1; cmd_import {dotdot}", env={"PISO_ROOT": root, "PISO_TEST_NONROOT": "1"})
check("refused" in r.stdout + r.stderr and open(f"{root}/etc/passwd").read() == "x", "RT-22: a backup entry with .. in its path is refused and writes nothing: " + r.stdout + r.stderr)
summ = f"{tmp}/summary.txt"
open(summ, "w").write("passwords")
r = lib("ASSUME_YES=1; cmd_forget_summary", env={"PISO_SUMMARY": summ, "PISO_TEST_NONROOT": "1"})
check(not os.path.exists(summ), "forget-summary deletes the saved summary: " + r.stdout)


# ---- the console and LuCI ports (97-console.sh) ---------------------------------------------------------------------------------------
r = lib("rm -f $CONF; echo $(console_port) $(luci_port http) $(luci_port https); conf_set CONSOLE_MODE front; echo $(console_port) $(luci_port http) $(luci_port https)")
check(r.stdout.split() == ["8081", "80", "443", "80", "8080", "8443"], "console: behind LuCI on 8081 by default; as the front page it takes 80 and LuCI moves to 8080 / 8443: " + r.stdout + r.stderr)
os.makedirs(f"{root}/etc/config", exist_ok=True); open(f"{root}/etc/config/uhttpd", "w").write("")
r = lib("rm -f $CONF; router_access_uci | grep uhttpd; conf_set CONSOLE_MODE front; router_access_uci | grep uhttpd", env={"PISO_ROOT": root})
check("listen_http='10.0.0.1:80'" in r.stdout and "listen_http='10.0.0.1:8080'" in r.stdout and "listen_https='10.0.0.1:8443'" in r.stdout, "LuCI's listen lines follow the mode: " + r.stdout)
os.makedirs(f"{root}/etc", exist_ok=True)
r = lib("rm -f $CONF; write_coinslot_conf k1 aa; cat $PISO_ROOT/etc/coinslot.conf; conf_set CONSOLE_MODE front; write_coinslot_conf k1 aa; grep CONSOLE_PORT $PISO_ROOT/etc/coinslot.conf", env={"PISO_ROOT": root})
check("CONSOLE_BIND='10.0.0.1'" in r.stdout and "CONSOLE_PORT='8081'" in r.stdout and "CONSOLE_PORT='80'" in r.stdout, "the portal's settings carry the console's address and port: " + r.stdout + r.stderr)
r = lib("rm -f $CONF; console_answers() { return 1; }; cmd_console on; echo rc=$?", env={"PISO_TEST_NONROOT": "1"})
check("does not answer" in r.stdout + r.stderr and "front" not in (lib("conf_get CONSOLE_MODE").stdout), "console on: refused while the console does not answer yet (nothing changed): " + r.stdout + r.stderr)
r = lib("rm -f $CONF; n=0; console_answers() { [ \"$1\" = 8081 ]; }; console_apply() { echo APPLY mode=[$(conf_get CONSOLE_MODE)]; }; sleep() { :; }; (cmd_console on); echo mode=[$(conf_get CONSOLE_MODE)]", env={"PISO_TEST_NONROOT": "1"})
check("APPLY mode=[front]" in r.stdout and "APPLY mode=[]" in r.stdout and "put back" in r.stdout + r.stderr and "mode=[]" in r.stdout.split("APPLY mode=[]")[-1], "console on: when the console does not come up on port 80, everything is put back: " + r.stdout + r.stderr)
# ---- admin.hub: the router's own DNS answers it; the console is the front page unless the owner chose otherwise (1.5.12) --------------------------------------------
fake = f"{tmp}/uci-admin"; os.makedirs(fake, exist_ok=True)
open(f"{fake}/uci", "w").write('#!/bin/sh\n[ "$1" = -q ] && shift\nif [ "$1" = get ] && [ "$2" = "dhcp.@dnsmasq[0].address" ]; then [ -e "$ADDR_FILE" ] && cat "$ADDR_FILE"; exit 0; fi\nexit 0\n'); os.chmod(f"{fake}/uci", 0o755)
def name_lines(have):
    open(f"{tmp}/addr", "w").write(have)
    return lib("admin_name_uci", env={"PATH": f"{fake}:{os.environ['PATH']}", "ADDR_FILE": f"{tmp}/addr"}).stdout.strip().splitlines()
check(name_lines("") == ["add_list dhcp.@dnsmasq[0].address='/admin.hub/10.0.0.1'"] or name_lines("\n") == ["add_list dhcp.@dnsmasq[0].address='/admin.hub/10.0.0.1'"], "admin.hub: the router answers the name with its kiosk address")
check(name_lines("/other.lan/10.0.0.5 /admin.hub/10.0.0.1\n") == [], "admin.hub: nothing is added twice, and the owner's other names are left alone")
check(name_lines("/admin.hub/192.168.9.1 /other.lan/10.0.0.5\n") == ["del_list dhcp.@dnsmasq[0].address='/admin.hub/192.168.9.1'", "add_list dhcp.@dnsmasq[0].address='/admin.hub/10.0.0.1'"],
      "admin.hub: an old address of the name is replaced when the router's address changed")
r = lib("rm -f $CONF; console_front && echo F; cmd_console() { echo ON-CALLED; }; console_default; echo mode=[$(conf_get CONSOLE_MODE)]")
check("ON-CALLED" in r.stdout, "console_default: a router that never chose gets the front page: " + r.stdout + r.stderr)
r = lib("rm -f $CONF; conf_set CONSOLE_MODE back; cmd_console() { echo ON-CALLED; }; console_default; echo mode=[$(conf_get CONSOLE_MODE)]")
check("ON-CALLED" not in r.stdout and "mode=[back]" in r.stdout, "console_default: an owner's own choice (console off) is kept: " + r.stdout + r.stderr)
r = lib("rm -f $CONF; conf_set CONSOLE_MODE front; console_apply() { :; }; cmd_console off >/dev/null; echo mode=[$(conf_get CONSOLE_MODE)]; console_front || echo notfront", env={"PISO_TEST_NONROOT": "1"})
check("mode=[back]" in r.stdout and "notfront" in r.stdout, "console off is remembered as the owner's choice: " + r.stdout + r.stderr)
r = lib("rm -f $CONF; cmd_console() { echo ON-FAILED; return 1; }; console_default; echo rc=$?")
check("ON-FAILED" in r.stdout and "rc=0" in r.stdout and "stays at http://10.0.0.1:8081" in r.stdout + r.stderr, "console_default: a front page that could not be made does not stop the update: " + r.stdout + r.stderr)
# ---- voucher-choice: the coin page's "Use now / Get a voucher code" choice -----------------------------------------------------------------
os.makedirs(f"{root}/etc/coinslot.d", exist_ok=True)
open(f"{root}/etc/coinslot.d/settings.txt", "w").write("VOUCHER_CHOICE=on\nOTHER=1\n")
r = lib("rm -f $CONF; write_coinslot_conf k1 aa; echo A=$(grep -c VOUCHER_CHOICE $PISO_ROOT/etc/coinslot.conf); cmd_voucher_choice off; grep VOUCHER_CHOICE $PISO_ROOT/etc/coinslot.conf; "
        "echo SETTINGS; cat $PISO_ROOT/etc/coinslot.d/settings.txt; echo STATUS; cmd_voucher_choice; cmd_voucher_choice on; grep VOUCHER_CHOICE $PISO_ROOT/etc/coinslot.conf; cmd_voucher_choice maybe; echo rc=$?",
        env={"PISO_ROOT": root, "PISO_TEST_NONROOT": "1"})
check("A=0" in r.stdout, "voucher-choice: nothing is written to the portal's file until it is set (the portal's default is on): " + r.stdout + r.stderr)
check("VOUCHER_CHOICE='off'" in r.stdout and "always buy time at once" in r.stdout, "voucher-choice off: kept in the settings and written to the portal's file: " + r.stdout + r.stderr)
check("OTHER=1" in r.stdout.split("SETTINGS")[1].split("STATUS")[0] and "VOUCHER_CHOICE" not in r.stdout.split("SETTINGS")[1].split("STATUS")[0],
      "a choice the owner made in the console is replaced (other console settings stay): " + r.stdout)
check("voucher choice is off" in r.stdout and "VOUCHER_CHOICE='on'" in r.stdout and "usage: piso-setup voucher-choice" in r.stderr and "rc=1" in r.stdout,
      "voucher-choice shows its state, turns on again, and refuses anything else: " + r.stdout + r.stderr)
# ---- feature 010: no installer copy is left behind after a good install ------------------------------------------------------------
croot = f"{tmp}/copyroot"
for d in ("tmp", "root", "other"):
    os.makedirs(f"{croot}/{d}", exist_ok=True)
for name in ("tmp/piso-setup.sh", "root/piso-setup.sh", "other/piso-setup.sh", "tmp/piso-setup.sh.bak"):
    open(f"{croot}/{name}", "w").write("installer copy\n")
r = lib(f"cd {croot}/tmp && echo REL=$(abs_path ./piso-setup.sh) && echo ABS=$(abs_path {croot}/other/piso-setup.sh)", env={"PISO_ROOT": croot})
check(f"REL={croot}/tmp/piso-setup.sh" in r.stdout and f"ABS={croot}/other/piso-setup.sh" in r.stdout,
      "abs_path turns ./piso-setup.sh (as install.sh starts it from inside its folder) into an absolute path: " + r.stdout + r.stderr)
for name, gone in (("tmp/piso-setup.sh", True), ("root/piso-setup.sh", True), ("other/piso-setup.sh", False)):
    r = lib(f'SELF_COPY={croot}/{name}; drop_installer_copy', env={"PISO_ROOT": croot})
    check(os.path.exists(f"{croot}/{name}") != gone, f"drop_installer_copy: {name} is {'deleted' if gone else 'left alone (a copied file or a branch test)'}: " + r.stderr)
    open(f"{croot}/{name}", "w").write("installer copy\n")
check(os.path.exists(f"{croot}/tmp/piso-setup.sh.bak"), "drop_installer_copy deletes only the file it was started from")
r = lib("SELF_COPY=; drop_installer_copy; echo rc=$?", env={"PISO_ROOT": croot})
check("rc=0" in r.stdout, "drop_installer_copy with no copy known does nothing")
s2 = text.split("stage2() {")[1].split("\n}\n")[0]
ok_branch, bad_branch = s2.split('if [ "$_f" = 0 ]; then')[1].split("\telse\n")
check("drop_installer_copy" in ok_branch and "drop_installer_copy" not in bad_branch, "stage 2 deletes the copy only when every check passed; a failed install keeps it for the next run")
main_body = text.split("\nmain() {")[1].split("\n}\n")[0]
check("SELF_COPY=$(abs_path \"$0\")" in main_body, "main makes $0 absolute before anything changes directory")
check("/usr/sbin/piso-setup" in text and "extract_payload \"$0\"" in text, "the installed /usr/sbin/piso-setup is still written from the running file before the copy goes")
# ---- feature 010: flash_class (the chip that holds the writable data) and the free-space lines ---------------------------------------
MiB = 1024 * 1024


def fake_mtd(name, parts, drop=(), garble=False):
    """parts: [(partition name, offset, size, type)]. Writes a /proc/mtd stand-in and a sysfs tree; returns the env for lib()."""
    d = f"{tmp}/mtd-{name}"
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(f"{d}/sys")
    lines = ["dev:    size   erasesize  name"]
    for i, (pn, off, size, typ) in enumerate(parts):
        lines.append(f'mtd{i}: {size:08x} 00010000 "{pn}"')
        os.makedirs(f"{d}/sys/mtd{i}")
        for fn, val in (("type", typ), ("offset", off), ("size", size)):
            if fn not in drop:
                open(f"{d}/sys/mtd{i}/{fn}", "w").write(f"{val}\n")
    open(f"{d}/proc", "w").write("\n".join(lines) + "\n" + ("this is not a partition table\n" if garble else ""))
    return {"PISO_MTD_FILE": f"{d}/proc", "PISO_MTD_SYSFS": f"{d}/sys"}


def flash_of(env, overlay_kb=None):
    e = dict(env)
    if overlay_kb is not None:
        e["PISO_OVERLAY_KB"] = str(overlay_kb)
    return lib("flash_class", env=e).stdout.strip()


boot = [("u-boot", 0, 0x30000, "nor"), ("config", 0x30000, 0x10000, "nor"), ("factory", 0x40000, 0x10000, "nor")]
nor16 = boot + [("firmware", 0x50000, 16 * MiB - 0x50000, "nor"), ("kernel", 0x50000, 0x200000, "nor"), ("rootfs", 0x250000, 0x600000, "nor"),
                ("rootfs_data", 0x850000, 16 * MiB - 0x850000, "nor")]
cases = [
    ("8 MiB NOR", fake_mtd("nor8", [("u-boot", 0, 0x30000, "nor"), ("firmware", 0x50000, 8 * MiB - 0x50000, "nor"), ("rootfs_data", 0x450000, 8 * MiB - 0x450000, "nor")]), None, f"small {8 * MiB} nor"),
    ("16 MiB NOR", fake_mtd("nor16a", [("u-boot", 0, 0x30000, "nor"), ("firmware", 0x50000, 16 * MiB - 0x50000, "nor"), ("rootfs_data", 0x850000, 16 * MiB - 0x850000, "nor")]), None, f"small {16 * MiB} nor"),
    ("32 MiB NOR", fake_mtd("nor32", [("u-boot", 0, 0x30000, "nor"), ("firmware", 0x50000, 32 * MiB - 0x50000, "nor"), ("rootfs_data", 0x850000, 32 * MiB - 0x850000, "nor")]), None, f"large {32 * MiB} nor"),
    ("16 MiB NOR, overlapping firmware / kernel / rootfs / rootfs_data", fake_mtd("nor16b", nor16), None, f"small {16 * MiB} nor"),
    ("a fullflash of 16 MiB", fake_mtd("nor16full", [("fullflash", 0, 16 * MiB, "nor")] + nor16), None, f"small {16 * MiB} nor"),
    ("360 T6GS from NAND", fake_mtd("t6gs", boot + [("ubi", 0, 128 * MiB, "nand")]), None, f"large {128 * MiB} nand"),
    ("360 T6GS NOR-only build", fake_mtd("t6gsnor", nor16), None, f"small {16 * MiB} nor"),
    ("128 MiB NAND-only board", fake_mtd("nand", [("bootloader", 0, 0x100000, "nand"), ("rootfs_data", 0x100000, 100 * MiB, "nand")]), None, f"large {100 * MiB} nand"),
    ("no MTD and a 4 GB disk", {"PISO_MTD_FILE": f"{tmp}/no-such-mtd"}, 4 * 1024 * 1024, f"large {4 * 1024 * 1024 * 1024} disk"),
    ("eMMC with a bootloader-only MTD and a 4 GB root", fake_mtd("emmc", boot), 4 * 1024 * 1024, f"large {4 * 1024 * 1024 * 1024} disk"),
    ("a NAND board whose UBI partition is named UBI_DEV, 90 MiB overlay", fake_mtd("ubidev", [("UBI_DEV", 0, 128 * MiB, "nand")]), 90 * 1024, f"large {90 * MiB} disk"),
    ("no MTD and a 12 MiB disk", {"PISO_MTD_FILE": f"{tmp}/no-such-mtd"}, 12 * 1024, f"small {12 * MiB} disk"),
    ("a missing offset file", fake_mtd("nooff", nor16, drop=("offset",)), None, "small - odd"),
    ("a garbled /proc/mtd", fake_mtd("garbled", nor16, garble=True), None, "small - odd"),
]
for label, env, okb, want in cases:
    got = flash_of(env, okb)
    check(got == want, f"flash_class: {label}: want '{want}', got '{got}'")

fake_df = lambda free, total=7000: open(f"{bindir}/df", "w").write(f"#!/bin/sh\necho 'Filesystem 1K-blocks Used Available Use% Mounted on'\necho 'overlayfs:/overlay {total} {total - free} {free} 10% /overlay'\n")
for free, mem, want_low, want_upd in ((1500, 64000, True, False), (3000, 64000, False, False), (3000, 20000, False, True), (5000, 20000, False, False), (1500, 20000, True, True)):
    fake_df(free)
    os.chmod(f"{bindir}/df", 0o755)
    env = dict(fake_mtd("space", nor16), PISO_MEM_KB=str(mem))
    for what, code in (("space", "cmd_space"), ("summary", 'write_summary; cat "$SUMMARY"')):
        out = lib(code, env=env).stdout
        check(f"flash chip:   small {16 * MiB} nor" in out and f"{free} KB" in out, f"{what}: the flash_class line and the free KB are printed ({free} KB free): " + out[:300])
        check(("less than 2 MB free: records and updates may not fit" in out) == want_low, f"{what}: low-space warning is {want_low} at {free} KB free")
        check(("too little free flash to download an update" in out) == want_upd, f"{what}: update-room warning is {want_upd} at {free} KB free, {mem} KB memory")
os.remove(f"{bindir}/df")
# ---- feature 010 group 5: a PisoWiFi-only router (SHOP_PRODUCT=wifi) has no rental-phone Wi-Fi --------------------------------------
fake_uci({"radio0": "2g", "radio1": "5g"})
both_batch = run("--dry-run").stdout
wifi_batch = run("--dry-run", env={"SHOP_PRODUCT": "wifi"}).stdout
check("wireless.kiosk_radio0" in both_batch and "wireless.kiosk_radio1" in both_batch, "SHOP_PRODUCT missing: the kiosk Wi-Fi is set up as before")
check(run("--dry-run", env={"SHOP_PRODUCT": "both"}).stdout == both_batch, "SHOP_PRODUCT=both gives exactly the batch of a missing SHOP_PRODUCT")
check("kiosk_" not in wifi_batch and "wireless.guest_radio0" in wifi_batch and "wireless.box_ap" in wifi_batch,
      "SHOP_PRODUCT=wifi: no wireless.kiosk_* section; guest Wi-Fi and the coin box Wi-Fi are still there")
r = run("--dry-run", env={"SHOP_PRODUCT": "phone"})
check(r.returncode != 0 and "SHOP_PRODUCT must be wifi or both" in r.stdout + r.stderr, "any other SHOP_PRODUCT stops the setup: " + r.stdout + r.stderr)
r = lib("shop_product; echo ---; conf_set SHOP_PRODUCT wifi; shop_product; echo ---; SHOP_PRODUCT=both shop_product")
check(r.stdout.split() == ["both", "---", "wifi", "---", "both"], "shop_product: missing is both; the settings file is read; the environment wins: " + r.stdout)
r = lib("DRY=0; SHOP_PRODUCT=wifi store_product; grep SHOP_PRODUCT $CONF")
check("SHOP_PRODUCT='wifi'" in r.stdout, "the product is stored in /etc/piso-setup.conf: " + r.stdout + r.stderr)
# no KIOSK_PASS asked, made or stored
r = lib("rm -f $CONF; SHOP_PRODUCT=wifi; ASSUME_YES=1; KIOSK_PASSWORD=Chosen12345; choose_passwords; echo K=[$(kiosk_pass)]; echo N=$(cat $CONF 2>/dev/null | grep -c KIOSK_PASS)")
check("K=[]" in r.stdout and "N=0" in r.stdout, "wifi: KIOSK_PASS is not taken from the environment, made or stored: " + r.stdout + r.stderr)
r = lib("rm -f $CONF; ASSUME_YES=1; KIOSK_PASSWORD=Chosen12345; choose_passwords; K=$(kiosk_pass); echo ${#K}; grep KIOSK_PASS $CONF")
check("KIOSK_PASS='Chosen12345'" in r.stdout and r.stdout.split()[0] == "11", "both: the password is chosen and kept as before: " + r.stdout + r.stderr)
# the coin box is not given a kiosk name or password
pre_wifi = "conf_set BOX_ADMIN_PASS x; conf_set KIOSK_PASS stale-old; SHOP_PRODUCT=wifi; curl() { echo CURL; }; "
r = lib(pre_wifi + "push_kiosk_wifi; echo rc=$?; sync_kiosk_wifi; echo rc=$?; kiosk_wifi_on_box; echo rc=$?", env={"BOX_IP": "127.0.0.1:9"})
check("CURL" not in r.stdout and r.stdout.count("rc=0") == 3 and "PisoKiosk" not in r.stdout, "wifi: the coin box is never given a kiosk name or password; the box-link check reports no problem: " + r.stdout + r.stderr)
# the agreement checks skip the kiosk Wi-Fi
r = agree(env={"SHOP_PRODUCT": "wifi", "T_KIOSK": "an-old-password"})
check("rc=0" in r.stdout and "kiosk" not in r.stdout.lower().replace("fails=", ""), "wifi: the agreement skips the kiosk Wi-Fi and the PisoKiosk link, and reports no problem: " + r.stdout[-400:] + r.stderr)
r = agree(env={"SHOP_PRODUCT": "wifi"}, extra="conf_set KIOSK_PASS ''; echo old > \"$SUMMARY\"; echo \"$(conf_get BOX_ADMIN_PASS)\" > \"$SUMMARY\"")
check("rc=0" in r.stdout, "wifi: the summary check needs only the coin box password: " + r.stdout[-300:])
r = agree(env={"SHOP_PRODUCT": "both", "T_KIOSK": "an-old-password"})
check("rc=1" in r.stdout and "kiosk network" in r.stdout, "both: the kiosk check is still there")
check("wifi_only ||" in text.split("check_all() {")[1].split("\n}\n")[0] and "hidden Wi-Fi $KIOSK_SSID" in text.split("check_all() {")[1], "the stage-2 checks skip the kiosk Wi-Fi on a wifi router")
# summary, handout, plan
r = lib('rm -f $CONF; SHOP_PRODUCT=wifi; conf_set ROOT_PASS_SET 1; write_summary; cat "$SUMMARY"; HANDOUT=' + tmp + '/h-wifi.html write_handout > /dev/null; cat ' + tmp + '/h-wifi.html')
check("Kiosk Wi-Fi" not in r.stdout and "Rental-phone Wi-Fi" not in r.stdout and "rental phone" not in r.stdout.lower() and "PisoKiosk" not in r.stdout.split("<html")[1] and "Customer Wi-Fi" in r.stdout and "Coin box" in r.stdout,
      "wifi: the summary and the handout have no rental-phone Wi-Fi lines: " + r.stdout[:600])
r = lib('rm -f $CONF; conf_set ROOT_PASS_SET 1; write_summary; cat "$SUMMARY"; HANDOUT=' + tmp + '/h-both.html write_handout > /dev/null; cat ' + tmp + '/h-both.html')
check("Kiosk Wi-Fi:" in r.stdout and "Rental-phone Wi-Fi (hidden)" in r.stdout and "Setting up a new rental phone" in r.stdout, "both: the summary and the handout keep their rental-phone lines")
plan = text.split("review_choices() {")[1].split("\n}\n")[0]
check("wifi_only ||" in plan and "Rental-phone Wi-Fi" in plan and "Kiosk Wi-Fi password" in plan, "the plan screen skips the rental-phone lines on a wifi router")
# piso-setup kiosk-wifi
open(f"{tmp}/conf", "a").write("SHOP_PRODUCT='wifi'\n")
r = run("kiosk-wifi")
os.remove(f"{tmp}/conf")
check(r.returncode == 0 and "this shop has no rental phones (PisoWiFi only)" in r.stdout + r.stderr, "wifi: piso-setup kiosk-wifi says there are no rental phones: " + r.stdout + r.stderr)
# the program's settings
r = lib("SHOP_PRODUCT=wifi; rm -f $PISO_ROOT/etc/coinslot.conf; write_coinslot_conf k1 aa; cat $PISO_ROOT/etc/coinslot.conf; SHOP_PRODUCT=both; write_coinslot_conf k1 aa; grep -c SHOP_PRODUCT $PISO_ROOT/etc/coinslot.conf; grep SHOP_PRODUCT $PISO_ROOT/etc/coinslot.conf",
        env={"PISO_ROOT": f"{tmp}/prodroot"})
check("SHOP_PRODUCT='wifi'" in r.stdout and r.stdout.count("SHOP_PRODUCT='both'") == 1 and "\n1\n" in r.stdout, "SHOP_PRODUCT is written to /etc/coinslot.conf, once: " + r.stdout + r.stderr)
# ---- feature 010 group 6: switching the product keeps everything else ---------------------------------------------------------------
import hashlib, pty, select
sroot = f"{tmp}/switchroot"
shutil.rmtree(sroot, ignore_errors=True)
os.makedirs(f"{sroot}/etc/coinslot.d")
for name, body in (("sessions.csv", "aa:bb,paid,1800\n"), ("revenue.csv", "2026-10-22,5\n"), ("ledger.txt", "coin 1\ncoin 2\n"), ("vouchers.txt", "CODE1 30 unused\n"), ("sales.log", "sale 5\n")):
    open(f"{sroot}/etc/coinslot.d/{name}", "w").write(body)
open(f"{sroot}/etc/coinslot.conf", "w").write("GW_BOX='10.0.0.10'\nPRICE_NOTE='mine'\nSEND_HOST='owner.example'\n")


def data_hash():
    h = hashlib.sha256()
    for n in sorted(os.listdir(f"{sroot}/etc/coinslot.d")):
        h.update(n.encode() + open(f"{sroot}/etc/coinslot.d/{n}", "rb").read())
    return h.hexdigest()


def conf_without(path, *drop):
    return sorted(l for l in open(path).read().splitlines() if not any(l.startswith(d + "=") for d in drop))


BOTHCONF = "KIOSK_PASS='oldKioskPw1'\nKIOSK_SSID_NAME='PisoKiosk-a1b2'\nBOX_SSID_NAME='PisoCoinBox-a1b2'\nBOX_MAC='AA:BB:CC:00:00:01'\nGW_KEY='k1'\nGUEST_NAME='ShopWiFi'\nROOT_PASS='rootpw12345'\n"
senv = {"PISO_ROOT": sroot, "PISO_CONF": f"{tmp}/swconf"}


def sw(code, env=None, conf=BOTHCONF):
    open(f"{tmp}/swconf", "w").write(conf)
    return lib(code, env=dict(senv, **(env or {})))


before = data_hash()
# both -> wifi, accepted with --yes: warns, drops the kiosk password, keeps every other setting and the data
r = sw("ASSUME_YES=1; resolve_wifi_names; confirm_product; apply_product; write_coinslot_conf k1 aa; echo rc=$?", env={"SHOP_PRODUCT": "wifi"})
check("rental phones on PisoKiosk-a1b2 will lose their Wi-Fi" in r.stdout + r.stderr and "rc=0" in r.stdout, "both -> wifi: the warning names the Wi-Fi: " + r.stdout + r.stderr)
after = open(f"{tmp}/swconf").read()
check("KIOSK_PASS" not in after and "SHOP_PRODUCT='wifi'" in after, "both -> wifi: KIOSK_PASS is deleted, SHOP_PRODUCT=wifi is stored: " + after)
check(sorted(after.splitlines()) == sorted([l for l in BOTHCONF.splitlines() if not l.startswith("KIOSK_PASS=")] + ["SHOP_PRODUCT='wifi'"]),
      "every other setting of /etc/piso-setup.conf is kept, nothing else is added: " + after)
cs = open(f"{sroot}/etc/coinslot.conf").read()
check("PRICE_NOTE='mine'" in cs and "SEND_HOST='owner.example'" in cs and "SHOP_PRODUCT='wifi'" in cs, "the portal's own settings are kept and the product is written: " + cs)
check(data_hash() == before, "the data folder is byte for byte the same after the switch to wifi")
# the batch of the switched router has no kiosk Wi-Fi
fake_uci({"radio0": "2g", "radio1": "5g"})
r = run("--dry-run", env={"PISO_CONF": f"{tmp}/swconf"})
check("kiosk_" not in r.stdout and "wireless.guest_radio0" in r.stdout, "the next run of a wifi router writes no kiosk Wi-Fi (the product comes from the settings): " + r.stdout[:200])
# no SHOP_PRODUCT: the product stays
r = sw("ASSUME_YES=1; confirm_product; apply_product; shop_product", conf="SHOP_PRODUCT='wifi'\nBOX_MAC='x'\n")
check(r.stdout.split()[-1] == "wifi" and "lose their Wi-Fi" not in r.stdout + r.stderr, "without SHOP_PRODUCT a wifi router stays wifi, with no warning: " + r.stdout + r.stderr)
r = sw("ASSUME_YES=1; confirm_product; apply_product; shop_product; grep -c KIOSK_PASS $CONF", conf=BOTHCONF)
check(r.stdout.split()[-2] == "both" and r.stdout.split()[-1] == "1" and "lose their Wi-Fi" not in r.stdout + r.stderr, "without SHOP_PRODUCT an installed router (no product stored) stays both and keeps its kiosk password: " + r.stdout + r.stderr)
# wifi -> both: the kiosk Wi-Fi comes back with a new password
r = sw("rm -f /dev/null; ASSUME_YES=1; confirm_product; apply_product; choose_passwords; K=$(kiosk_pass); echo LEN=${#K}; grep SHOP_PRODUCT $CONF", env={"SHOP_PRODUCT": "both"},
       conf="SHOP_PRODUCT='wifi'\nBOX_MAC='x'\nKIOSK_SSID_NAME='PisoKiosk-a1b2'\nBOX_SSID_NAME='PisoCoinBox-a1b2'\n")
check("LEN=12" in r.stdout and "SHOP_PRODUCT='both'" in r.stdout and "KIOSK_PASS='" in open(f"{tmp}/swconf").read(), "wifi -> both: a new kiosk password is made and stored: " + r.stdout + r.stderr)
check(data_hash() == before, "the data folder is byte for byte the same after the switch back")
# a dry run only warns
r = sw("DRY=1; confirm_product; apply_product; echo rc=$?", env={"SHOP_PRODUCT": "wifi"})
check("lose their Wi-Fi" in r.stdout and "KIOSK_PASS='oldKioskPw1'" in open(f"{tmp}/swconf").read() and "SHOP_PRODUCT" not in open(f"{tmp}/swconf").read(), "--dry-run warns and changes nothing")
# no terminal and no --yes: refused, nothing changed
r = sw("confirm_product < /dev/null; echo rc=$?", env={"SHOP_PRODUCT": "wifi"})
check("rc=" not in r.stdout and "KIOSK_PASS='oldKioskPw1'" in open(f"{tmp}/swconf").read() and "--yes" in r.stdout + r.stderr, "no terminal and no --yes: it stops, nothing is changed: " + r.stdout + r.stderr)


def at_terminal(code, answer):
    """Run shell code with a real terminal on stdin (the question is only asked at a terminal) and type an answer."""
    open(f"{tmp}/swconf", "w").write(BOTHCONF)
    env = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_SETUP_SOURCE_ONLY="1", PISO_LOG=f"{tmp}/log2", PISO_STATE=f"{tmp}/state2",
               PISO_ROUTER_ID="a1b2", SHOP_PRODUCT="wifi", **senv)
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe("sh", ["sh", "-c", f". {SCRIPT}; {code}"], env)
    out = b""
    sent = False
    while True:
        ready, _, _ = select.select([fd], [], [], 15)
        if not ready:
            break
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        if not sent and b"[y/N]" in out:
            os.write(fd, (answer + "\n").encode())
            sent = True
    os.waitpid(pid, 0)
    return out.decode(errors="replace")


out = at_terminal("confirm_product; apply_product; echo DONE", "n")
check("lose their Wi-Fi" in out and "DONE" not in out and "KIOSK_PASS='oldKioskPw1'" in open(f"{tmp}/swconf").read(), "asked at a terminal, answered no: cancelled, nothing changed: " + out[-300:])
out = at_terminal("confirm_product; apply_product; echo DONE", "y")
check("DONE" in out and "KIOSK_PASS" not in open(f"{tmp}/swconf").read(), "asked at a terminal, answered yes: switched: " + out[-300:])
# an update never changes the product: the environment applies only to the installer
open(f"{tmp}/swconf", "w").write(BOTHCONF)
r = run("kiosk-wifi", env={"SHOP_PRODUCT": "wifi", "PISO_CONF": f"{tmp}/swconf"})
check("no rental phones" not in r.stdout + r.stderr, "SHOP_PRODUCT in the environment is ignored by every command except the install: " + r.stdout + r.stderr)
main_body = text.split("\nmain() {")[1].split("\n}\n")[0]
check('unset SHOP_PRODUCT' in main_body and main_body.index("unset SHOP_PRODUCT") < main_body.index('case "$CMD" in'), "main drops SHOP_PRODUCT before running any command but the install")
st1 = text.split("\nstage1() {")[1].split("\n}\n")[0]
check(st1.index("confirm_product") < st1.index("choose_passwords") and "store_product" not in st1 and st1.index("apply_batch") < st1.index("apply_product") < st1.index("stage2"),
      "stage 1 asks about the product before any password, and stores it (and drops the kiosk password) only after apply_batch")
# ---- feature 010 review fix: the kiosk password goes only after the new Wi-Fi is written -------------------------------------------
def stage1_at_terminal(answers, fail_install=False):
    """Runs the real stage1 of an installed both router switched to wifi, at a terminal, with the router-changing steps stubbed
    (preflight, packages, files, the uci write, stage 2). `answers` are typed at each [y/N] question in turn."""
    open(f"{tmp}/swconf", "w").write(BOTHCONF + "SITE_NAME='Shop'\nBOX_ADMIN_PASS_NEW='boxadmin12345'\n")   # (everything stage 1 would ask is answered)
    stubs = ("preflight() { R24=radio0; R5=radio1; }; ask_telegram() { :; }; extract_payload() { :; }; stage2() { echo STAGE2-RAN; }; box_up() { return 1; }; "
             + ('install_packages() { die "no internet"; }; ' if fail_install else "install_packages() { echo PACKAGES; }; ")
             + f'apply_batch() {{ echo APPLY-RAN; uci_batch "$@" > {tmp}/applied.uci; }}; ')
    env = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_SETUP_SOURCE_ONLY="1", PISO_LOG=f"{tmp}/log2", PISO_STATE=f"{tmp}/state2",
               PISO_SUMMARY=f"{tmp}/sum2", PISO_ROUTER_ID="a1b2", SHOP_PRODUCT="wifi", **senv)
    if os.path.exists(f"{tmp}/applied.uci"):
        os.remove(f"{tmp}/applied.uci")
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe("sh", ["sh", "-c", f". {SCRIPT}; {stubs} resolve_wifi_names; LOG={tmp}/log2; stage1; echo STAGE1-END"], env)
    out, todo = b"", list(answers)
    seen = 0
    while True:
        ready, _, _ = select.select([fd], [], [], 15)
        if not ready:
            break
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
        while todo and out.count(b"[y/N]") > seen:
            seen += 1
            os.write(fd, (todo.pop(0) + "\n").encode())
    os.waitpid(pid, 0)
    return out.decode(errors="replace")


fake_uci({"radio0": "2g", "radio1": "5g"})
# (a) cancelled at the review screen: nothing is changed
out = stage1_at_terminal(["y", "n"])
conf_now = open(f"{tmp}/swconf").read()
check("KIOSK_PASS='oldKioskPw1'" in conf_now and "SHOP_PRODUCT='wifi'" not in conf_now and "APPLY-RAN" not in out and "STAGE1-END" not in out,
      "wifi switch cancelled at the review: KIOSK_PASS and the product are as they were, nothing was written: " + out[-500:] + conf_now)
# (b) the install fails before the new Wi-Fi is written: the old Wi-Fi is still there, so its password is too
out = stage1_at_terminal(["y", "y"], fail_install=True)
conf_now = open(f"{tmp}/swconf").read()
check("KIOSK_PASS='oldKioskPw1'" in conf_now and "SHOP_PRODUCT='wifi'" not in conf_now and "APPLY-RAN" not in out,
      "wifi switch, install stops before the Wi-Fi is written: KIOSK_PASS and the product are unchanged: " + out[-500:] + conf_now)
# (c) the run completes: the password is gone, the product is wifi, the batch has no kiosk Wi-Fi
out = stage1_at_terminal(["y", "y"])
conf_now = open(f"{tmp}/swconf").read()
applied = open(f"{tmp}/applied.uci").read() if os.path.exists(f"{tmp}/applied.uci") else ""
parts = ("STAGE2-RAN" in out, "KIOSK_PASS" not in conf_now, "SHOP_PRODUCT='wifi'" in conf_now, bool(applied), "wireless.kiosk_" not in applied, "wireless.guest_radio0" in applied)
check(all(parts),
      "wifi switch completed: KIOSK_PASS deleted, product wifi stored, no kiosk Wi-Fi written: " + out[-300:] + conf_now + " parts=" + str(parts))
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
