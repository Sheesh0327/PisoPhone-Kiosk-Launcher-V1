"""Tests of the package-manager layer in setup/piso-setup.sh (feature 057): opkg, apk or none, and the image type, with fake package managers.
Run with:  python3 router/tests/test_pkgmgr.py"""
import os, subprocess, sys, tempfile

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


def bindir(name, **tools):
    """A directory of fake commands: name=script body. Returns its path."""
    d = f"{tmp}/{name}"
    os.makedirs(d, exist_ok=True)
    for cmd, body in tools.items():
        open(f"{d}/{cmd}", "w").write(f'#!/bin/sh\necho "{cmd} $*" >> {tmp}/calls.log\n{body}\n')
        os.chmod(f"{d}/{cmd}", 0o755)
    return d


def sh(code, path_dir=None, root=None):
    """Run shell code with the script's functions loaded. PATH holds only the fake commands and the system's basics."""
    e = dict(os.environ, PISO_SETUP_SOURCE_ONLY="1", PISO_CONF=f"{tmp}/conf", PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
             PISO_SUMMARY=f"{tmp}/summary", PISO_ROUTER_ID="a1b2", PISO_ROOT=root or "")
    base = f"{tmp}/basic"
    if not os.path.isdir(base):
        os.makedirs(base)
        for c in ("sed", "grep", "sort", "head", "cat", "awk", "df", "tr", "cut", "date", "id", "uname", "sh", "dirname", "basename", "tail", "chmod", "touch", "rm", "mv", "mkdir", "wc", "tee", "cp"):
            p = subprocess.run(["sh", "-c", f"command -v {c}"], capture_output=True, text=True).stdout.strip()
            if p:
                os.symlink(p, f"{base}/{c}")
    e["PATH"] = (path_dir + ":" if path_dir else "") + base
    return subprocess.run(["sh", "-c", f". {SCRIPT}; {code}"], env=e, capture_output=True, text=True, timeout=60)


def calls():
    try:
        return open(f"{tmp}/calls.log").read()
    except OSError:
        return ""


def reset():
    open(f"{tmp}/calls.log", "w").close()


r = subprocess.run([sys.executable, f"{ROOT}/tools/build_piso_setup.py", "--check"], capture_output=True, text=True)
check(r.returncode == 0, "setup/piso-setup.sh is up to date with its sources: " + r.stdout + r.stderr)

OPKG = bindir("opkg_only", opkg='case "$1" in list-installed) echo "busybox - 1.36.1-1" ;; info) [ "$2" = sqm-scripts ] && echo "Package: sqm-scripts" ;; install) exit 0 ;; esac')
APK = bindir("apk_only", apk='case "$1" in info) [ "$3" = busybox ] && exit 0 || exit 1 ;; search) [ "$3" = sqm-scripts ] && echo "sqm-scripts-1.5" || exit 1 ;; esac')
NONE = bindir("none")
BOTH_OPKG = bindir("both_opkg", opkg='case "$1" in list-installed) echo "busybox - 1.36.1-1" ;; esac', apk="exit 1")
BOTH_APK = bindir("both_apk", opkg="exit 0", apk='case "$1" in info) exit 0 ;; esac')

# ---- which package manager -------------------------------------------------------------------------------------------------
check(sh("pkg_manager", OPKG).stdout.strip() == "opkg", "a router with opkg only uses opkg")
check(sh("pkg_manager", APK).stdout.strip() == "apk", "a router with apk only uses apk")
check(sh("pkg_manager", NONE).stdout.strip() == "none", "a router with neither says none")
check(sh("pkg_manager", BOTH_OPKG).stdout.strip() == "opkg", "with both, the one that lists the base system (opkg) is used")
check(sh("pkg_manager", BOTH_APK).stdout.strip() == "apk", "with both, apk is used when it owns the base system and opkg lists nothing")

# ---- the functions, with each manager --------------------------------------------------------------------------------------
reset()
sh("pkg_update; pkg_install a b; pkg_remove a", OPKG)
c = calls()
check("opkg update" in c and "opkg install a b" in c and "opkg remove a" in c, "opkg: update, install and remove use opkg's own commands: " + c)
reset()
sh("pkg_update; pkg_install a b; pkg_remove a", APK)
c = calls()
check("apk update" in c and "apk add a b" in c and "apk del a" in c, "apk: update, install and remove use apk's own commands: " + c)
check(sh("pkg_installed busybox", OPKG).returncode == 0 and sh("pkg_installed nosuch", OPKG).returncode != 0, "opkg: is-installed reads the installed list")
check(sh("pkg_installed busybox", APK).returncode == 0 and sh("pkg_installed nosuch", APK).returncode != 0, "apk: is-installed asks apk info -e")
check(sh("pkg_available sqm-scripts", OPKG).returncode == 0 and sh("pkg_available nosuch", OPKG).returncode != 0, "opkg: available reads the output of opkg info (it exits 0 even for an unknown package)")
check(sh("pkg_available sqm-scripts", APK).returncode == 0 and sh("pkg_available nosuch", APK).returncode != 0, "apk: available reads apk search -e")
reset()
sh("pkg_dry_run a b", OPKG)
check("opkg install --noaction a b" in calls(), "opkg: a dry run is install --noaction: " + calls())
reset()
sh("pkg_dry_run a b", APK)
check("apk add --simulate a b" in calls(), "apk: a dry run is add --simulate: " + calls())
fail_opkg = bindir("opkg_fail", opkg="exit 255")
check(sh("pkg_dry_run a", fail_opkg).returncode != 0, "a failing dry run is a failing answer")
check(sh("pkg_name sqm-scripts", OPKG).stdout.strip() == "sqm-scripts", "package names go through one name map (unchanged unless a manager calls one differently)")

# ---- no package manager ----------------------------------------------------------------------------------------------------
for fn in ("pkg_update", "pkg_installed busybox", "pkg_available a", "pkg_dry_run a", "pkg_install a", "pkg_remove a"):
    check(sh(fn, NONE).returncode != 0, f"no package manager: {fn} fails instead of pretending")

# ---- nothing forces an unsigned or unchecked install -----------------------------------------------------------------------
src = open(f"{ROOT}/setup/src/12-pkgmgr.sh").read() if os.path.exists(f"{ROOT}/setup/src/12-pkgmgr.sh") else "(missing)"
for bad in ("--force-signature", "--force-checksum", "--allow-untrusted", "--no-check-certificate", "--force-depends", "--no-cache"):
    check(bad not in src.replace("never passes", ""), f"the layer never passes {bad}")

# ---- the image type --------------------------------------------------------------------------------------------------------
def image(release, feeds, probe_ok=True, mgr="opkg"):
    """A fake router root with a release file and feed files; returns (type, description, reason)."""
    root = tempfile.mkdtemp(dir=tmp)
    os.makedirs(f"{root}/etc/opkg")
    os.makedirs(f"{root}/etc/apk/repositories.d")
    if release is not None:
        open(f"{root}/etc/openwrt_release", "w").write(release)
    for path, body in feeds.items():
        open(f"{root}/{path}", "w").write(body)
    d = bindir(f"img{len(os.listdir(tmp))}", **{mgr: "exit 0" if probe_ok else "exit 255"})
    out = sh("pkg_image_type", d, root).stdout.strip().split("|")
    return out + [""] * (3 - len(out))


def rel(i, d, v="23.05.5"):
    return f"DISTRIB_ID='{i}'\nDISTRIB_RELEASE='{v}'\nDISTRIB_REVISION='r1-abc'\nDISTRIB_TARGET='x86/64'\nDISTRIB_DESCRIPTION='{d}'\n"


OFFICIAL_FEEDS = {"etc/opkg/distfeeds.conf": "src/gz openwrt_core https://downloads.openwrt.org/releases/23.05.5/targets/x86/64/packages\nsrc/gz openwrt_base https://downloads.openwrt.org/releases/23.05.5/packages/x86_64/base\n",
                  "etc/opkg/customfeeds.conf": "# add your custom package feeds here\n#\n# src/gz example_feed_name http://www.example.com/path/to/files\n"}
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r24106"), OFFICIAL_FEEDS)
check(t[0] == "official" and "OpenWrt 23.05.5" in t[1], f"official OpenWrt with its own feed and a passing probe is official: {t}")
t = image(rel("OpenWrt", "OpenWrt 25.12.1 r32768", "25.12.1"), {"etc/apk/repositories.d/distfeeds.list": "https://downloads.openwrt.org/releases/25.12.1/targets/x86/64/packages/packages.adb\n",
                                                               "etc/apk/repositories.d/customfeeds.list": "# add your custom package feeds here\n#\n# http://www.example.com/path/to/files/packages.adb\n"}, mgr="apk")
check(t[0] == "official", f"official OpenWrt with an apk feed file is official: {t}")
t = image(rel("ImmortalWrt", "ImmortalWrt 23.05.0 r1"), {"etc/opkg/distfeeds.conf": "src/gz immortalwrt_core https://downloads.immortalwrt.org/releases/23.05.0/targets/x86/64/packages\n"})
check(t[0] == "fork" and "ImmortalWrt" in t[1], f"ImmortalWrt with its own feed and a passing probe is a fork: {t}")
t = image(rel("ImmortalWrt", "ImmortalWrt 23.05.0 r1"), OFFICIAL_FEEDS)
check(t[0] == "custom", f"ImmortalWrt pointing at OpenWrt's feed is not a fork: {t}")
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r1"), {"etc/opkg/distfeeds.conf": "src/gz x http://feeds.example.net/openwrt\n"})
check(t[0] == "custom" and "feeds.example.net" in t[2], f"an unknown feed host is custom and the reason names it: {t}")
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r1"), {"etc/opkg/distfeeds.conf": "src/gz x https://evilopenwrt.org/feed\n"})
check(t[0] == "custom", f"a host that only ends in the letters of openwrt.org is not OpenWrt's: {t}")
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r1"), dict(OFFICIAL_FEEDS, **{"etc/opkg/customfeeds.conf": "src/gz mine https://feeds.example.net/x\n"}))
check(t[0] == "custom", f"one foreign feed among the official ones makes the image custom: {t}")
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r1"), OFFICIAL_FEEDS, probe_ok=False)
check(t[0] == "custom" and "kernel" in t[2], f"a failing kernel-fit probe lowers an official image to custom and says why: {t}")
t = image(None, OFFICIAL_FEEDS)
check(t[0] == "custom", f"no release file is custom: {t}")
t = image(rel("OpenWrt", "OpenWrt 23.05.5 r1"), {})
check(t[0] == "custom" and "feed" in t[2], f"no feed files at all is custom, without a crash: {t}")
t = image(rel("SomeVendorOS", "Vendor 1.0"), OFFICIAL_FEEDS)
check(t[0] == "custom", f"an unknown distribution id is custom: {t}")
r = sh("pkg_image_type", NONE, tempfile.mkdtemp(dir=tmp))
check(r.stdout.startswith("custom|") and r.returncode == 0, f"no package manager gives custom: {r.stdout} {r.stderr}")

# ---- the router installer uses the layer (feature 057, group 2) ------------------------------------------------------------
STUBS = 'stop_opennds() { echo STOPPED >> ' + tmp + '/calls.log; }; '
APK_FRESH = bindir("apk_fresh", apk='case "$1" in info) exit 1 ;; esac')   # nothing installed yet
reset()
r = sh(STUBS + "(install_packages); echo rc=$?", APK_FRESH)
c = calls()
check("rc=0" in r.stdout and "apk update" in c and "apk add opennds" in c and "apk add curl" in c and "apk add ca-bundle" in c and "apk add jsonfilter" in c and "STOPPED" in c,
      "apk router: install_packages updates, installs openNDS and the others with apk and stops openNDS: " + c + r.stdout + r.stderr)
check("opkg" not in c, "apk router: opkg is never called: " + c)
OPKG_FRESH = bindir("opkg_fresh", opkg='case "$1" in list-installed) ;; esac')
reset()
r = sh(STUBS + "(install_packages); echo rc=$?", OPKG_FRESH)
c = calls()
check("rc=0" in r.stdout and "opkg update" in c and "opkg install opennds" in c and "STOPPED" in c, "opkg router: the same installs as before: " + c + r.stdout)
reset()
r = sh(STUBS + "(install_packages); echo rc=$?", NONE)
check("rc=0" in r.stdout and "no package manager" in r.stdout and "apk" not in calls() and "opkg" not in calls(),
      "no package manager: install_packages says so, installs nothing and does not fail: " + r.stdout + r.stderr)
root = tempfile.mkdtemp(dir=tmp)
r = sh("(preflight_packages); echo rc=$?", NONE, root)
check("rc=0" not in r.stdout and "no package manager" in r.stdout + r.stderr and "openNDS is not installed" in r.stdout + r.stderr and "Nothing was changed" in r.stdout + r.stderr,
      "no package manager and no openNDS: the setup stops before changing anything and says why: " + r.stdout + r.stderr)
os.makedirs(f"{root}/etc/init.d")
open(f"{root}/etc/init.d/opennds", "w").write("#!/bin/sh\n")
os.chmod(f"{root}/etc/init.d/opennds", 0o755)
withnds = bindir("with_ndsctl", ndsctl="exit 0")
r = sh("(preflight_packages); echo rc=$?", withnds, root)
check("rc=0" in r.stdout, "no package manager but openNDS is installed (init script and ndsctl): the setup goes on: " + r.stdout + r.stderr)
r = sh("(preflight_packages); echo rc=$?", APK, root)
check("rc=0" in r.stdout, "apk router: the package preflight passes: " + r.stdout + r.stderr)
r = sh("(preflight_packages); echo rc=$?", OPKG, root)
check("rc=0" in r.stdout, "opkg router: the package preflight passes: " + r.stdout + r.stderr)
APK_BAD = bindir("apk_bad", apk="exit 1")
r = sh("step() { :; }; ping() { return 0; }; (install_packages); echo rc=$?", APK_BAD, root)
check("rc=0" not in r.stdout and "apk update failed" in r.stdout + r.stderr, "an apk update that fails says apk, not opkg: " + r.stdout + r.stderr)
check(sh("pkg_installed_prefix libmicrohttpd", OPKG).returncode != 0 and sh("pkg_installed_prefix busybox", OPKG).returncode == 0, "opkg: a package family is found by the start of its name")

# ---- the image type is recorded at install and shown (feature 057, group 2) ------------------------------------------------
FORK = "pkg_image_type() { echo 'fork|ImmortalWrt 23.05.0 r1|feeds from immortalwrt.org, kernel fit ok'; }; "
cf = f"{tmp}/imgconf"
open(cf, "w").close()
def withconf(code, path_dir=None):
    e_conf = cf
    return subprocess.run(["sh", "-c", f". {SCRIPT}; {code}"], env=dict(os.environ, PISO_SETUP_SOURCE_ONLY="1", PISO_CONF=e_conf, PISO_LOG=f"{tmp}/log", PISO_STATE=f"{tmp}/state",
                          PISO_SUMMARY=f"{tmp}/summary-img", PISO_ROUTER_ID="a1b2", PATH=(path_dir + ":" if path_dir else "") + f"{tmp}/basic"), capture_output=True, text=True, timeout=60)
r = withconf(FORK + "step() { :; }; pkg_image_record; pkg_image_report", OPKG)
check("IMAGE_TYPE='fork|ImmortalWrt 23.05.0 r1|" in open(cf).read(), "the image type is saved in the setup settings: " + open(cf).read() + r.stderr)
check("Package manager: opkg" in r.stdout and "ImmortalWrt 23.05.0 r1" in r.stdout and "known fork" in r.stdout and "immortalwrt.org" in r.stdout, "the report names the manager, the image and the kind in plain words: " + r.stdout)
r = withconf("pkg_image_report", NONE)
check("Package manager: none" in r.stdout and "Image:" in r.stdout, "the report works with no package manager: " + r.stdout)
r = withconf("check_all() { echo CHECKS; return 0; }; cmd_status", OPKG)
check("Image:" in r.stdout and "known fork" in r.stdout, "piso-setup status shows the image kind: " + r.stdout + r.stderr)
r = withconf("write_summary; cat \"$SUMMARY\"", OPKG)
check("Image:" in r.stdout and "known fork" in r.stdout, "the setup summary shows the image kind: " + r.stdout + r.stderr)
check(sh("pkg_image_type", OPKG, root).stdout.count("'") == 0, "the saved image text never holds a single quote (the settings file is quoted with them)")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
