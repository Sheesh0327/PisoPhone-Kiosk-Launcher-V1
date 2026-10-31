"""Tests of the optional smooth-line packages step of setup/piso-setup.sh (feature 058): which packages are tried, in what order, and what is reported,
with stand-ins for the package layer and the kernel. Run with:  python3 router/tests/test_optional.py"""
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

STUBS = r'''
pkg_manager() { echo "${T_MGR:-opkg}"; }
pkg_installed() { case " $T_INSTALLED " in *" $1 "*) return 0 ;; esac; return 1; }
pkg_available() { case " $T_FEED " in *" $1 "*) return 0 ;; esac; return 1; }
pkg_dry_run() { for p in "$@"; do case " $T_FEED " in *" $p "*) ;; *) echo "DRYFAIL $*" >> $T_LOG; return 1 ;; esac; done; echo "DRY $*" >> $T_LOG; return 0; }
pkg_install() { echo "INSTALL $*" >> $T_LOG; for p in "$@"; do case " $T_FAILINSTALL " in *" $p "*) return 1 ;; esac; done; return 0; }
pkg_remove() { echo "REMOVE $*" >> $T_LOG; }
pkg_free_kb() { echo "${T_FREE:-9000}"; }
kernel_has() { case " $T_KERNEL " in *" $1 "*) return 0 ;; esac; return 1; }
opt_have_tc() { [ "${T_TC:-0}" = 1 ]; }
conf_get() { [ "$1" = IMAGE_TYPE ] && echo "${T_IMAGE:-official|OpenWrt 23.05.5|ok}"; }
step() { :; }
'''
FULL = "sqm-scripts kmod-ifb tc-full kmod-sched-cake irqbalance"


def run(code, **env):
    root = tempfile.mkdtemp(dir=tmp)
    log = f"{root}/calls.log"
    open(log, "w").close()
    e = dict(os.environ, PISO_SETUP_SOURCE_ONLY="1", PISO_CONF=f"{tmp}/conf", PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state", PISO_ROUTER_ID="a1b2",
             PISO_ROOT=root, T_LOG=log, T_FEED=FULL, T_INSTALLED="", T_KERNEL="", T_FAILINSTALL="")
    e.update(env)
    r = subprocess.run(["sh", "-c", f". {SCRIPT}; {STUBS}\n{code}"], env=e, capture_output=True, text=True, timeout=60)
    calls = open(log).read()
    status = ""
    if os.path.exists(f"{root}/etc/coinslot.d/packages.txt"):
        status = open(f"{root}/etc/coinslot.d/packages.txt").read()
    return r, calls, status


def installs(calls):
    return [line[len("INSTALL "):] for line in calls.splitlines() if line.startswith("INSTALL ")]


r, calls, st = run("install_optional_packages; echo rc=$?")
check("rc=0" in r.stdout, "it always finishes without an error: " + r.stdout + r.stderr)
check(installs(calls) == ["sqm-scripts kmod-ifb tc-full", "kmod-sched-cake", "irqbalance"], "official, full feed: the core group first (in one install), then cake, then irqbalance: " + calls)
check("pkg|sqm-scripts|installed|" in st and "pkg|kmod-sched-cake|installed|" in st and "pkg|irqbalance|installed|" in st, "each installed package is in the status file: " + st)
r, calls, st = run("install_optional_packages", T_KERNEL="ifb sch_cake")
check(installs(calls) == ["sqm-scripts tc-full", "irqbalance"], "modules the kernel already has are not installed again: " + calls)
check("pkg|kmod-ifb|builtin|" in st and "pkg|kmod-sched-cake|builtin|" in st, "and are reported as already in the kernel: " + st)
r, calls, st = run("install_optional_packages", T_FEED="sqm-scripts kmod-ifb tc-full irqbalance")
check("pkg|kmod-sched-cake|skipped|" in st and "package feed" in st.split("kmod-sched-cake|skipped|")[1].split("\n")[0], "cake missing from the feed: skipped and the reason says the feed: " + st)
check(installs(calls) == ["sqm-scripts kmod-ifb tc-full", "irqbalance"], "and the rest still goes in: " + calls)
r, calls, st = run("install_optional_packages", T_FEED="sqm-scripts tc-full kmod-sched-cake irqbalance")
check(installs(calls) == ["irqbalance"] and "pkg|sqm-scripts|skipped|" in st and "pkg|kmod-ifb|skipped|" in st and "pkg|kmod-sched-cake|skipped|" in st,
      "the core group is all or nothing: ifb missing means no sqm-scripts, and cake (useless alone) is skipped: " + calls + st)
r, calls, st = run("install_optional_packages", T_FAILINSTALL="kmod-ifb")
check("REMOVE" in calls and "sqm-scripts" in calls.split("REMOVE")[1] and "pkg|sqm-scripts|failed|" in st, "a core install that fails is taken out again and reported: " + calls + st)
r, calls, st = run("install_optional_packages", T_FREE="1500")
check(installs(calls) == [] and "DRY" not in calls and st.count("|skipped|") == 5 and "2048" in st, "under 2048 KB free: nothing is tried and the reason is written for each: " + calls + st)
r, calls, st = run("install_optional_packages", T_MGR="none", T_KERNEL="ifb")
check(installs(calls) == [] and "pkg|kmod-ifb|builtin|" in st and "no package manager" in st, "no package manager: nothing is installed, what the kernel has is still reported: " + calls + st)
r, calls, st = run("install_optional_packages", T_IMAGE="custom|Vendor 1|the package feed does not offer kmod-ifb")
check(installs(calls) == ["irqbalance"] and "pkg|kmod-ifb|skipped|" in st and "kernel" in st, "custom image: kernel module packages are not tried, and the reason names the kernel: " + calls + st)
r, calls, st = run("install_optional_packages", T_IMAGE="custom|Vendor 1|x", T_KERNEL="ifb sch_cake")
check(installs(calls) == ["sqm-scripts tc-full", "irqbalance"], "custom image whose kernel already has the modules: user-space parts are still installed after the dry run: " + calls)
r, calls, st = run("install_optional_packages", T_IMAGE="fork|ImmortalWrt 23.05.0|ok")
check(len(installs(calls)) == 3, "a known fork is treated like an official image: " + calls)
r, calls, st = run("install_optional_packages", T_INSTALLED="sqm-scripts irqbalance kmod-ifb kmod-sched-cake tc-full")
check(installs(calls) == [] and st.count("|present|") == 5, "already installed: nothing is installed again: " + calls + st)
r, calls, st = run("install_optional_packages", T_FEED="")
check("rc=" not in r.stderr and st.count("|skipped|") == 5, "nothing in the feed: all skipped, no error: " + st + r.stderr)
r, calls, st = run("install_optional_packages; optional_report")
check("Smooth-line packages" in r.stdout and "irqbalance" in r.stdout and "installed" in r.stdout, "the report names each package in plain words: " + r.stdout)
r, calls, st = run("install_optional_packages", T_OFFICIAL_RETRY="1", T_FAILINSTALL="irqbalance")
check("pkg|irqbalance|failed|" in st, "a failed optional install is reported as failed: " + st)

# ---- shown in the summary and in piso-setup status; run by the first setup ------------------------------------------------------
r, calls, st = run("install_optional_packages; check_all() { echo CHECKS; return 0; }; cmd_status")
check("Smooth-line packages" in r.stdout, "piso-setup status shows the optional packages: " + r.stdout + r.stderr)
r, calls, st = run("install_optional_packages; write_summary; cat \"$SUMMARY\"", PISO_SUMMARY=f"{tmp}/sum")
check("Smooth-line packages" in r.stdout, "the setup summary shows the optional packages: " + r.stdout + r.stderr)
flow = open(f"{ROOT}/setup/src/98-setup-flow.sh").read()
check("install_packages\n\tinstall_optional_packages" in flow, "the first setup runs the optional step right after openNDS and the others")

# ---- criterion 1: openNDS first, the others optional --------------------------------------------------------------------------------
CORE = r'''
pkg_update() { return 0; }
stop_opennds() { :; }
pkg_installed_prefix() { return 0; }
pkg_image_record() { :; }
pkg_image_report() { printf 'x\ny\n'; }
'''
r, calls, st = run(CORE + "install_packages; echo rc=$?", T_FAILINSTALL="opennds", T_INSTALLED="curl ca-bundle jsonfilter")
check(r.returncode != 0 and "rc=0" not in r.stdout and "could not install opennds" in r.stdout + r.stderr, "openNDS cannot be installed: the setup stops with the message it has always had: " + r.stdout + r.stderr)
check("INSTALL opennds" in calls and "sqm" not in calls, "and no optional package was tried: " + calls)
r, calls, st = run(CORE + "install_packages && install_optional_packages; echo rc=$?", T_FAILINSTALL="sqm-scripts kmod-ifb kmod-sched-cake irqbalance", T_INSTALLED="curl ca-bundle jsonfilter")
check("rc=0" in r.stdout and "INSTALL opennds" in calls, "openNDS goes in and every optional package fails: the setup carries on without an error: " + r.stdout + r.stderr)
check(st.count("|failed|") >= 3, "and each failure is written down: " + st)
flow = open(f"{ROOT}/setup/src/98-setup-flow.sh").read()
check(flow.index("\tinstall_packages\n") < flow.index("\tinstall_optional_packages\n"), "the setup flow installs the required packages before it tries the optional ones")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
