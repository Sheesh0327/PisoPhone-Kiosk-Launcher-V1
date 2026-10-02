"""Builds the .ipk and checks its structure: files, modes, LF line endings, control fields, reproducible."""
import hashlib, io, os, subprocess, sys, tarfile, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(os.path.dirname(HERE), "package", "build_ipk.py")
failures = 0


def check(cond, msg):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", msg)


def members(blob):
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as t:
        return {m.name: (m, t.extractfile(m).read()) for m in t.getmembers() if m.isfile()}


out = tempfile.mkdtemp()
p = subprocess.run([sys.executable, BUILD, "--version", "1.2.3", "--out", out], capture_output=True, text=True)
check(p.returncode == 0, "builds: " + p.stderr)
ipk = os.path.join(out, "opennds-coinslot_1.2.3_all.ipk")
check(os.path.exists(ipk), "file name follows opkg convention")
outer = members(open(ipk, "rb").read())
check(set(outer) == {"./debian-binary", "./control.tar.gz", "./data.tar.gz"}, "outer layout: " + str(sorted(outer)))
check(outer["./debian-binary"][1] == b"2.0\n", "debian-binary")
data = members(outer["./data.tar.gz"][1])
ctl = members(outer["./control.tar.gz"][1])
want = {"./usr/bin/coinslot-listener.sh": 0o755, "./usr/lib/opennds/theme_coinslot.sh": 0o755,
        "./etc/init.d/coinslot": 0o755, "./etc/config/coinslot": 0o600}
for name, mode in want.items():
    check(name in data and data[name][0].mode == mode, f"{name} present with mode {oct(mode)}")
check(all(b"\r" not in blob for _, blob in data.values()), "no CR characters in installed files")
check(all(blob.startswith(b"#!/bin/sh") for n, (_, blob) in data.items() if "config" not in n), "scripts start with a shell shebang")
control = ctl["./control"][1].decode()
for line in ("Package: opennds-coinslot", "Version: 1.2.3", "Architecture: all", "Depends: opennds, socat, openssl-util, curl"):
    check(line in control, "control has " + line)
check(ctl["./conffiles"][1] == b"/etc/config/coinslot\n", "the settings file is a conffile (kept on upgrade)")
check(ctl["./postinst"][0].mode == 0o755 and b"coinslot enable" in ctl["./postinst"][1], "postinst enables the service")
check(b"coinslot-listener.sh migrate" in ctl["./postinst"][1], "postinst migrates old settings")
check(b"gw_key ''" in data["./etc/config/coinslot"][1] or b"gw_key ''" in data["./etc/config/coinslot"][1], "no key ships in the package")
p2 = subprocess.run([sys.executable, BUILD, "--version", "1.2.3", "--out", out + "2"], capture_output=True, text=True)
h = lambda path: hashlib.sha256(open(path, "rb").read()).hexdigest()
check(h(ipk) == h(os.path.join(out + "2", "opennds-coinslot_1.2.3_all.ipk")), "build is reproducible")
check(subprocess.run([sys.executable, BUILD, "--version", "x"], capture_output=True).returncode != 0, "bad version refused")
print("ipk:", "OK" if not failures else f"{failures} failure(s)")
sys.exit(1 if failures else 0)
