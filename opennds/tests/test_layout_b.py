"""Checks what layout_b.sh would configure: the two networks must not reach each other, only guest is gated."""
import os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "layout_b.sh")
failures = 0


def check(cond, msg):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", msg)


def run(**env):
    e = dict(os.environ, KIOSK_KEY="supersecret1", BOX_MAC="AA:BB:CC:DD:EE:FF")
    e.update(env)
    return subprocess.run(["sh", SCRIPT], env=e, capture_output=True, text=True)


r = run()
check(r.returncode == 0, "script runs: " + r.stderr)
out = r.stdout
sets = {}
for line in out.splitlines():
    m = re.match(r"set (\S+?)='?([^']*)'?$", line)
    if m:
        sets[m.group(1)] = m.group(2)
check(sets.get("opennds.@opennds[0].gatewayinterface") == "br-guest", "openNDS gates only the guest bridge")
check("br-kiosk" not in sets.get("opennds.@opennds[0].gatewayinterface", ""), "kiosk bridge is not gated")
check(sets.get("wireless.kiosk_ap.isolate") == "0", "kiosk Wi-Fi has no client isolation (phones must reach the box)")
check(sets.get("wireless.guest_ap.isolate") == "1", "guest Wi-Fi isolates customers from each other")
check(sets.get("wireless.kiosk_ap.encryption") == "psk2", "kiosk Wi-Fi is password protected")
check(sets.get("dhcp.@host[-1].ip") == "192.168.20.10" and sets.get("dhcp.@host[-1].mac") == "AA:BB:CC:DD:EE:FF", "box has a fixed address")
forwards = {(sets[k], sets[k.replace(".src", ".dest")]) for k in sets if k.startswith("firewall.") and k.endswith(".src") and k.replace(".src", ".dest") in sets}
check(("kiosk", "wan") in forwards and ("guest", "wan") in forwards, "both sides reach the internet")
check(not any(s in ("kiosk", "guest") and d in ("kiosk", "guest") for s, d in forwards), "no forwarding between guest and kiosk")
for z in ("kiosk", "guest"):
    check(sets.get(f"firewall.{z}.forward") == "REJECT" and sets.get(f"firewall.{z}.input") == "REJECT", f"{z} zone rejects input and forward by default")
rule_ports = [sets[k] for k in sets if k.startswith("firewall.") and k.endswith(".dest_port")]
check(all(set(p.split()) <= {"53", "67"} for p in rule_ports), "only DNS and DHCP are opened to the router: " + str(rule_ports))
check("wan" not in sets.get("firewall.kiosk.network", ""), "kiosk zone is not the wan")
check("network.lan" not in out, "the existing lan is not touched")
check(run(KIOSK_KEY="short").returncode != 0, "a short Wi-Fi password is refused")
check(run(KIOSK_KEY="").returncode != 0, "a missing Wi-Fi password is refused")
check("add_list network.kiosk_dev.ports='lan3'" in run(KIOSK_PORTS="lan3 lan4").stdout, "wired ports can join the kiosk network")
check(subprocess.run(["sh", "-c", f"KIOSK_KEY=x1234567 sh {SCRIPT} >/dev/null 2>&1"], env=dict(os.environ)).returncode != 0, "BOX_MAC is required")
print("layout_b:", "OK" if not failures else f"{failures} failure(s)")
sys.exit(1 if failures else 0)
