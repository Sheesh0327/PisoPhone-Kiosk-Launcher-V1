"""Settings from UCI win over the old /etc/coinslot.conf; the old file still works; `migrate` copies it into UCI."""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
LISTENER = os.path.join(os.path.dirname(HERE), "coinslot-listener.sh")
tmp = tempfile.mkdtemp()
failures = 0


def check(cond, msg):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", msg)


def run(args, conf_text, uci_opts=None, with_uci=True):
    d = tempfile.mkdtemp(dir=tmp)
    conf = os.path.join(d, "coinslot.conf")
    open(conf, "w").write(conf_text)
    for k, v in (uci_opts or {}).items():
        open(os.path.join(d, k), "w").write(v)
    env = dict(os.environ, COINSLOT_CONF=conf, FAKE_UCI_DIR=d, COINSLOT_UCI_TEST="1",
               UCI=os.path.join(HERE, "fake_uci.sh") if with_uci else "no-such-uci-binary")
    r = subprocess.run(["sh", LISTENER] + args, env=env, capture_output=True, text=True)
    return r, d, conf


r, *_ = run(["minutes", "endurance", "3"], "GW_KEY=k\nENDURANCE_TIERS='1:15'\n", with_uci=False)
check(r.stdout.strip() == "45", "old settings file alone still works: " + r.stdout)
r, *_ = run(["minutes", "endurance", "3"], "GW_KEY=k\nENDURANCE_TIERS='1:15'\n", {"coinslot.main.endurance_tiers": "1:20"})
check(r.stdout.strip() == "60", "a UCI option overrides the old file: " + r.stdout)
r, *_ = run(["minutes", "endurance", "3"], "GW_KEY=k\nENDURANCE_TIERS='1:15'\n", {"coinslot.main.path": "/nowhere", "coinslot.main.endurance_tiers": "1:20"})
check(r.stdout.strip() == "60", "unknown option names are ignored (PATH is not overridable): " + r.stdout + r.stderr)
r, d, conf = run(["migrate"], "GW_BOX=10.0.0.5\nGW_KEY=abc123\nHYPER_TIERS='5:30 10:60'\nSOMETHING_ELSE=1\n")
log = open(os.path.join(d, "calls.log")).read()
check(r.returncode == 0, "migrate succeeds: " + r.stderr)
check("set coinslot.main.gw_box=10.0.0.5" in log and "set coinslot.main.gw_key=abc123" in log, "migrate copies the box and key: " + log)
check("set coinslot.main.hyper_tiers=5:30 10:60" in log, "migrate keeps values with spaces")
check("SOMETHING_ELSE" not in log.upper().replace("SOMETHING_ELSE=1", "") and "something_else" not in log, "migrate copies only known settings")
check("commit coinslot" in log and os.path.exists(conf + ".migrated") and not os.path.exists(conf), "migrate commits and keeps the old file as .migrated")
print("uci_config:", "OK" if not failures else f"{failures} failure(s)")
sys.exit(1 if failures else 0)
