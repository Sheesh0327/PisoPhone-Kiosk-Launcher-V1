"""Tests of `piso-setup sqm test` of setup/piso-setup.sh (feature 058, group 5b): the router measures the line itself, only when nobody is online,
pauses SQM for the test and always puts it back, and says honestly when it could not measure or the router itself was the limit.
Fake curl (no network), fake sqm init script, fake /proc/stat. Run with:  python3 router/tests/test_sqm_test.py"""
import json
import os
import pathlib
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
# The fake curl: four streams; a stream of B bytes at T_DOWN_MBPS / T_UP_MBPS in total takes 4*B*8/rate seconds. It prints what -w asks for:
# "size_download size_upload time_total". T_FAIL_HOSTS: hosts that do not answer. T_CPU=busy: /proc/stat shows a router that is flat out.
pathlib.Path(f"{bindir}/curl").write_text(r'''#!/bin/sh
echo "curl $*" >> "$T_LOG"
url=""; for a in "$@"; do url="$a"; done
host=${url#https://}; host=${host%%/*}
case " $T_FAIL_HOSTS " in *" $host "*) exit 6 ;; esac
[ -n "$T_SLEEP" ] && sleep "$T_SLEEP"
[ -n "$T_GUESTS_FILE" ] && echo 1 > "$T_GUESTS_FILE"
if [ "$T_CPU" = busy ]; then echo "cpu 1000 0 1000 810 0 0 0 0" > "$T_STAT"; else echo "cpu 200 0 200 1600 0 0 0 0" > "$T_STAT"; fi
case "$url" in
	*__down*) b=${url##*bytes=}; awk -v b="$b" -v r="${T_DOWN_MBPS:-100}" 'BEGIN { printf "%d 0 %.3f\n", b, 4 * b * 8 / (r * 1000000) }' ;;
	*) b=$(wc -c); awk -v b="$b" -v r="${T_UP_MBPS:-20}" 'BEGIN { printf "0 %d %.3f\n", b, 4 * b * 8 / (r * 1000000) }' ;;
esac
exit 0
''')
os.chmod(f"{bindir}/curl", 0o755)

STUBS = r'''
sqm_wan_dev() { echo eth1; }
kernel_has() { return 1; }
pkg_installed() { return 1; }
sqm_active() { [ "${T_ACTIVE:-0}" = 1 ]; }
guests_online() { if [ -n "$T_GUESTS_FILE" ] && [ -s "$T_GUESTS_FILE" ]; then echo 1; else echo "${T_GUESTS:-0}"; fi; }
'''


def run(code="cmd_sqm test; echo rc=$?", settings=None, sqm_installed=True, **env):
    root = tempfile.mkdtemp(dir=tmp)
    for d in ("etc/coinslot.d", "etc/init.d", "tmp", "proc"):
        os.makedirs(f"{root}/{d}")
    pathlib.Path(f"{root}/proc/stat").write_text("cpu 100 0 100 800 0 0 0 0\n")
    if settings is not None:
        pathlib.Path(f"{root}/etc/coinslot.d/settings.txt").write_text("".join(f"{k}={v}\n" for k, v in settings.items()))
    log = f"{root}/calls.log"
    pathlib.Path(log).write_text("")
    if sqm_installed:
        pathlib.Path(f"{root}/etc/init.d/sqm").write_text(f'#!/bin/sh\necho "sqm-init $*" >> "{log}"\n')
        os.chmod(f"{root}/etc/init.d/sqm", 0o755)
    e = dict(os.environ, PATH=f"{bindir}:" + os.environ["PATH"], PISO_SETUP_SOURCE_ONLY="1", PISO_CONF=f"{tmp}/conf", PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
             PISO_ROUTER_ID="a1b2", PISO_ROOT=root, T_LOG=log, T_STAT=f"{root}/proc/stat", T_GUESTS_FILE="")
    e.update(env)
    r = subprocess.run(["sh", "-c", f". {SCRIPT}; {STUBS}\n{code}"], env=e, capture_output=True, text=True, timeout=120, check=False)
    out = None
    if os.path.exists(f"{root}/tmp/piso-speedtest.json"):
        out = json.loads(pathlib.Path(f"{root}/tmp/piso-speedtest.json").read_text())
    return r, pathlib.Path(log).read_text(), out, root


ON = {"SQM_ON": "1", "SQM_DOWN_MBPS": "50", "SQM_UP_MBPS": "20", "SQM_PERCENT": "95"}

# ---- a good test ----------------------------------------------------------------------------------------------------
r, calls, js, root = run(T_DOWN_MBPS="87.4", T_UP_MBPS="18.2")
check("rc=0" in r.stdout and js is not None, "a test on a quiet router works and writes its result: " + r.stdout + r.stderr)
if js:
    check(js["state"] == "done", "state done: " + str(js))
    check(abs(js["down"] - 87.4) < 0.2 and abs(js["up"] - 18.2) < 0.2, "both directions are measured from the bytes and the time: " + str(js))
    check(js["host"] == "speed.cloudflare.com", "the host that answered is named: " + str(js))
    check(js["cpu_limited"] is False and 0 <= js["cpu"] < 90, "an idle router is not the limit: " + str(js))
    check(isinstance(js["time"], int) and js["time"] > 1000000000, "the result carries the time it was made: " + str(js))
check("__down?bytes=" in calls and "__up" in calls, "downloads from __down and uploads to __up: " + calls)
check(calls.count("curl ") == 8, "four parallel streams each way: " + str(calls.count("curl ")))
check("-m " in calls, "every transfer has its own time limit: " + calls)
check("sqm-init" not in calls, "SQM was not running, so the service is not touched: " + calls)
check(not os.path.exists(f"{root}/etc/coinslot.d/settings.txt"), "a test saves no settings")

# ---- the data is capped -----------------------------------------------------------------------------------------------
import re

sizes = [int(x) for x in re.findall(r"bytes=(\d+)", calls)]
check(sizes and sum(sizes) <= 260 * 1000 * 1000, "no more than about 250 MB is asked for downloading: " + str(sizes))

# ---- SQM is paused and always put back --------------------------------------------------------------------------------
r, calls, js, root = run(settings=ON, T_ACTIVE="1")
check("rc=0" in r.stdout, "test with SQM on works: " + r.stdout + r.stderr)
check("sqm-init stop" in calls and "sqm-init start" in calls and calls.index("sqm-init stop") < calls.index("curl ") < calls.index("sqm-init start"),
      "SQM is stopped before the first transfer and started after the last: " + calls)
check("restart" not in calls and "uci" not in calls, "the saved SQM settings are never edited: " + calls)
r, calls, js, root = run(settings=ON, T_ACTIVE="0")
check("sqm-init stop" in calls and "sqm-init start" in calls, "saved as on, queue not seen: it is still stopped and started: " + calls)

r, calls, js, root = run(settings=ON, T_ACTIVE="1", T_FAIL_HOSTS="speed.cloudflare.com")
check("rc=0" not in r.stdout and js and js["state"] == "failed", "no host answers: failed: " + r.stdout + str(js))
check("sqm-init start" in calls, "and SQM is started again after the failure: " + calls)
if js:
    check(js["down"] is None and js["up"] is None and js["reason"], "a failed test prefills nothing and says why: " + str(js))

r, calls, js, root = run("cmd_sqm test & p=$!; sleep 1; kill -TERM $p; wait $p; echo done", settings=ON, T_ACTIVE="1", T_SLEEP="5")
check("sqm-init stop" in calls and "sqm-init start" in calls, "killed in the middle: SQM is still started again: " + calls + r.stderr)

# ---- refused while a customer is online, or one connects ------------------------------------------------------------
r, calls, js, root = run(settings=ON, T_ACTIVE="1", T_GUESTS="2")
check("rc=0" not in r.stdout and "BUSY" in r.stdout + r.stderr, "customers online: BUSY: " + r.stdout + r.stderr)
check("curl " not in calls and "sqm-init" not in calls and js is None, "and nothing started, SQM untouched, no result written: " + calls + str(js))

r, calls, js, root = run(settings=ON, T_ACTIVE="1", T_SLEEP="2", T_GUESTS_FILE=f"{tmp}/guest-arrived")
check("rc=0" not in r.stdout and js and js["state"] == "failed" and "customer" in js["reason"], "a customer connects mid-test: it stops and says so: " + r.stdout + str(js))
check("sqm-init start" in calls, "and SQM is back: " + calls)
if os.path.exists(f"{tmp}/guest-arrived"):
    os.remove(f"{tmp}/guest-arrived")

# ---- the router itself was the limit ----------------------------------------------------------------------------------
r, calls, js, root = run(T_CPU="busy", T_DOWN_MBPS="300")
check("rc=0" in r.stdout and js and js["state"] == "done", "a busy router still reports: " + r.stdout + str(js))
if js:
    check(js["cpu_limited"] is True and js["cpu"] >= 90, "and says the router may be the limit: " + str(js))

# ---- one test at a time -----------------------------------------------------------------------------------------------
r, calls, js, root = run("mkdir -p \"$PISO_ROOT/tmp/piso-speedtest.lock\"; echo $$ > \"$PISO_ROOT/tmp/piso-speedtest.lock/pid\"; cmd_sqm test; echo rc=$?")
check("rc=0" not in r.stdout and "BUSY" in r.stdout + r.stderr and "curl " not in calls, "a test already running: BUSY and nothing started: " + r.stdout + r.stderr)
r, calls, js, root = run("mkdir -p \"$PISO_ROOT/tmp/piso-speedtest.lock\"; echo 999999 > \"$PISO_ROOT/tmp/piso-speedtest.lock/pid\"; cmd_sqm test; echo rc=$?")
check("rc=0" in r.stdout and js and js["state"] == "done", "a lock left by a dead test does not block for ever: " + r.stdout + r.stderr)
check(not os.path.exists(f"{root}/tmp/piso-speedtest.lock"), "the lock is removed when the test ends")

# ---- the host list is in one place, a fallback is tried -----------------------------------------------------------------
r, calls, js, root = run(T_FAIL_HOSTS="a.example", PISO_SPEEDTEST_HOSTS="a.example b.example")
check(js and js["state"] == "done" and js["host"] == "b.example", "the first host fails, the next answers and is named: " + r.stdout + str(js))

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
