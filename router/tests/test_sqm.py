"""Tests of the `piso-setup sqm` commands of setup/piso-setup.sh (feature 056): the saved line speed becomes the router's SQM settings, switching off removes
them, and installing the packages waits for a quiet moment. Fake uci, fake sqm init script, stand-ins for the package layer.
Run with:  python3 router/tests/test_sqm.py"""
import os
import subprocess
import sys
import tempfile

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
open(f"{bindir}/uci", "w").write(f'''#!/bin/sh
echo "uci $*" >> "$T_LOG"
case "$1" in batch) cat >> "$T_LOG" ;; esac
exit 0
''')
os.chmod(f"{bindir}/uci", 0o755)

STUBS = r'''
sqm_wan_dev() { echo "${T_WAN:-eth1}"; }
kernel_has() { case " $T_KERNEL " in *" $1 "*) return 0 ;; esac; return 1; }
pkg_installed() { case " $T_INSTALLED " in *" $1 "*) return 0 ;; esac; return 1; }
sqm_active() { [ "${T_ACTIVE:-1}" = 1 ]; }
guests_online() { echo "${T_GUESTS:-0}"; }
pkg_update() { echo "pkg_update" >> "$T_LOG"; return "${T_UPDATE_RC:-0}"; }
pkg_image_record() { echo "pkg_image_record" >> "$T_LOG"; }
install_optional_packages() { echo "install_optional_packages" >> "$T_LOG"; }
optional_report() { :; }
step() { :; }
'''


def run(code, settings=None, sqm_installed=True, **env):
    root = tempfile.mkdtemp(dir=tmp)
    os.makedirs(f"{root}/etc/coinslot.d")
    os.makedirs(f"{root}/etc/init.d")
    if settings is not None:
        open(f"{root}/etc/coinslot.d/settings.txt", "w").write("".join(f"{k}={v}\n" for k, v in settings.items()))
    log = f"{root}/calls.log"
    open(log, "w").close()
    if sqm_installed:
        open(f"{root}/etc/init.d/sqm", "w").write(f'#!/bin/sh\necho "sqm-init $*" >> "{log}"\n')
        os.chmod(f"{root}/etc/init.d/sqm", 0o755)
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_SETUP_SOURCE_ONLY="1", PISO_CONF=f"{tmp}/conf", PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
             PISO_ROUTER_ID="a1b2", PISO_ROOT=root, T_LOG=log, T_KERNEL="", T_INSTALLED="")
    e.update(env)
    r = subprocess.run(["sh", "-c", f". {SCRIPT}; {STUBS}\n{code}"], env=e, capture_output=True, text=True, timeout=60)
    status = ""
    if os.path.exists(f"{root}/etc/coinslot.d/sqm.txt"):
        status = open(f"{root}/etc/coinslot.d/sqm.txt").read()
    return r, open(log).read(), status


ON = {"SQM_ON": "1", "SQM_DOWN_MBPS": "50", "SQM_UP_MBPS": "20", "SQM_PERCENT": "95"}

r, calls, st = run("sqm_kbps 50 95; echo; sqm_kbps 87.3 95; echo; sqm_kbps 9.7 95; echo; sqm_kbps 100 100")
check(r.stdout.split() == ["47500", "82935", "9215", "100000"], "the limit is the typed speed at the margin, in kbit/s: " + r.stdout)

r, calls, st = run("cmd_sqm apply; echo rc=$?", ON, T_KERNEL="sch_cake")
check("rc=0" in r.stdout, "apply works: " + r.stdout + r.stderr)
for want in ("set sqm.piso_wan=queue", "set sqm.piso_wan.interface='eth1'", "set sqm.piso_wan.download='47500'", "set sqm.piso_wan.upload='19000'", "set sqm.piso_wan.qdisc='cake'",
             "set sqm.piso_wan.enabled='1'", "dual-dsthost", "dual-srchost", "uci commit sqm", "sqm-init enable", "sqm-init restart"):
    check(want in calls, f"apply with cake: {want}: " + calls)
check("installed|1" in st and "qdisc|cake" in st and "active|1" in st and "wan|eth1" in st and "limit_down_kbps|47500" in st and "limit_up_kbps|19000" in st, "and the status says so: " + st)

r, calls, st = run("cmd_sqm apply; echo rc=$?", ON)
check("set sqm.piso_wan.qdisc='fq_codel'" in calls and "simple.qos" in calls and "dual-dsthost" not in calls and "qdisc|fq_codel" in st, "without cake the shaper is fq_codel and says so: " + calls + st)
r, calls, st = run("cmd_sqm apply", ON, T_INSTALLED="kmod-sched-cake")
check("set sqm.piso_wan.qdisc='cake'" in calls, "an installed cake package counts as well as one in the kernel: " + calls)
r, calls, st = run("cmd_sqm apply", ON, T_WAN="pppoe-wan", T_KERNEL="sch_cake")
check("interface='pppoe-wan'" in calls and "wan|pppoe-wan" in st, "the WAN device is asked for at apply time (PPPoE): " + calls)
r, calls, st = run("cmd_sqm apply", dict(ON, SQM_PERCENT="90", SQM_DOWN_MBPS="87.3"), T_KERNEL="sch_cake")
check("download='78570'" in calls, "a changed margin and a decimal speed are used: " + calls)

for bad in ({"SQM_DOWN_MBPS": "0"}, {"SQM_DOWN_MBPS": "abc"}, {"SQM_UP_MBPS": "20000"}, {"SQM_PERCENT": "40"}, {"SQM_PERCENT": "101"}, {"SQM_UP_MBPS": ""}):
    r, calls, st = run("cmd_sqm apply; echo rc=$?", dict(ON, **bad), T_KERNEL="sch_cake")
    check("rc=0" not in r.stdout and "set sqm." not in calls and "restart" not in calls, f"{bad}: refused and nothing is changed: " + r.stdout + calls)

r, calls, st = run("cmd_sqm apply; echo rc=$?", dict(ON, SQM_ON="0"), T_KERNEL="sch_cake")
check("rc=0" in r.stdout and "delete sqm.piso_wan" in calls and "sqm-init stop" in calls and "set sqm.piso_wan" not in calls and "active|0" in st, "switched off: our queue is removed, SQM stops: " + r.stdout + calls + st)
r, calls, st = run("cmd_sqm apply; echo rc=$?", None)
check("rc=0" in r.stdout and "set sqm.piso_wan" not in calls, "no saved speeds at all: nothing is set (SQM stays off): " + r.stdout + calls)
r, calls, st = run("cmd_sqm apply; echo rc=$?", ON, sqm_installed=False)
check("rc=0" not in r.stdout and "set sqm." not in calls and "installed|0" in st and "not installed" in r.stdout + r.stderr, "SQM not installed: refused with the reason, and the status says installed|0: " + r.stdout + calls + st)
r, calls, st = run("cmd_sqm apply; echo rc=$?", ON, T_KERNEL="sch_cake", T_ACTIVE="0")
check("active|0" in st and "reason|" in st, "if the queue is not seen on the WAN after the restart, the status says not active and why: " + st)

r, calls, st = run("cmd_sqm status", ON, T_KERNEL="sch_cake")
check("installed|1" in st, "status writes the status file: " + st)

# ---- install: the packages wait for a quiet moment ----------------------------------------------------------------------
r, calls, st = run("cmd_sqm install; echo rc=$?", T_GUESTS="2")
check("rc=0" not in r.stdout and "BUSY" in r.stdout + r.stderr and "install_optional_packages" not in calls and "pkg_update" not in calls, "customers online: BUSY and nothing is installed: " + r.stdout + calls)
r, calls, st = run("cmd_sqm install; echo rc=$?", T_GUESTS="0")
check("rc=0" in r.stdout and calls.index("pkg_update") < calls.index("pkg_image_record") < calls.index("install_optional_packages"), "quiet: update, image type, then the optional packages: " + r.stdout + calls)
r, calls, st = run("cmd_sqm install; echo rc=$?", T_UPDATE_RC="1")
check("rc=0" not in r.stdout and "install_optional_packages" not in calls, "the package lists cannot be updated: it stops and says so: " + r.stdout + calls)
r, calls, st = run("cmd_sqm nonsense; echo rc=$?")
check("rc=0" not in r.stdout and "usage" in r.stdout + r.stderr, "an unknown sqm command prints the usage: " + r.stdout + r.stderr)
flow = open(f"{ROOT}/setup/src/98-setup-flow.sh").read()
check("cmd_sqm \"$ARG\"" in flow and "brand-pack | sqm)" in flow, "piso-setup knows the sqm command")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
