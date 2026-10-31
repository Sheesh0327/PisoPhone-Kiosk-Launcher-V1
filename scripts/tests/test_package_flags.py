"""Tests that scripts/check_security_rules.sh refuses a package-manager flag that skips a signature, checksum, certificate or
dependency check (feature 057), and passes the real tree. Run with:  python3 scripts/tests/test_package_flags.py"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RULE = "package manager flag"
failures = checks = 0


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def run_rules(files):
    """Run a copy of the rule script in a tree that holds only `files` ({path: text})."""
    tree = tempfile.mkdtemp()
    os.makedirs(f"{tree}/scripts")
    shutil.copy(f"{ROOT}/scripts/check_security_rules.sh", f"{tree}/scripts/check_security_rules.sh")
    for path, text in files.items():
        os.makedirs(os.path.dirname(f"{tree}/{path}"), exist_ok=True)
        open(f"{tree}/{path}", "w").write(text)
    r = subprocess.run(["sh", f"{tree}/scripts/check_security_rules.sh"], capture_output=True, text=True, cwd=tree)
    shutil.rmtree(tree, ignore_errors=True)
    return r.stdout + r.stderr


for flag in ("--force-signature", "--force-checksum", "--allow-untrusted", "--no-check-certificate", "--force-depends"):
    out = run_rules({"setup/src/bad.sh": f"opkg install {flag} sqm-scripts\n"})
    check(RULE in out, f"{flag} in the installer is a violation: " + out[-300:])
out = run_rules({"setup/pisophone_setup.py": 'cmd = "apk add --allow-untrusted x"\n'})
check(RULE in out, "the setup program too: " + out[-300:])
out = run_rules({"setup/src/ok.sh": "opkg install --noaction sqm-scripts\napk add --simulate sqm-scripts\n"})
check(RULE not in out, "a dry run is not a violation: " + out[-300:])
out = run_rules({"router/tests/test_x.py": 'x = "--allow-untrusted"\n'})
check(RULE not in out, "a test that names a forbidden flag is not a violation: " + out[-300:])
r = subprocess.run(["sh", f"{ROOT}/scripts/check_security_rules.sh"], capture_output=True, text=True, cwd=ROOT)
check(RULE not in r.stdout + r.stderr, "the real tree has none: " + r.stdout[-300:])

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
