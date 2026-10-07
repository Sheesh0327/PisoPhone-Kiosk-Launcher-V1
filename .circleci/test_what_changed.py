#!/usr/bin/env python3
"""Tests of .circleci/what_changed.py (which CircleCI workflows a push runs).   python3 .circleci/test_what_changed.py"""
import importlib.util, os, sys

spec = importlib.util.spec_from_file_location("wc", os.path.join(os.path.dirname(os.path.abspath(__file__)), "what_changed.py"))
wc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wc)
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def on(files, branch="beta", authors=("dev@example.com",)):
    f = wc.flags(files, list(authors), branch)
    return {k for k, v in f.items() if v}


check(on(["tools/pisoportal/src/main.rs"]) == {"security", "portal", "router_program"}, "a portal change: " + str(on(["tools/pisoportal/src/main.rs"])))
check(on(["app/src/main/java/x/A.kt"]) == {"security", "kotlin", "apk"}, "an app change")
check(on(["esp32_firmware/src/Config.cpp"]) == {"security", "firmware_build", "firmware_tests", "firmware_images"}, "a firmware change on beta")
check("firmware_images" not in on(["esp32_firmware/src/Config.cpp"], branch="feature-x"), "flasher images only on main and beta")
check(on(["website/js/flasher.js"]) == {"security", "website"}, "a website change")
check(on(["website/setup/piso-setup.sh"]) == {"security", "portal", "website"}, "the website's setup file copy")
check("apk" in on(["scripts/setup_ci_secrets.py"]), "the CI-secrets tool is tested in the APK workflow")
check(on(["docs/REAL_WORLD_TESTING.md"]) == {"security", "test_issues"}, "the test log")
check(on(["website/update/firmware-esp32c3.bin"], branch="main") >= {"firmware_json"}
      and "firmware_json" not in on(["website/update/firmware-esp32c3.bin"], branch="beta"), "firmware.json only on main")
check(on(["setup/piso-setup.sh.in"]) >= {"portal", "router_program"}, "the setup file's source")
check(on(["setup/piso-setup.sh", "tools/pisoportal/bin/pisoportal-mipsel"]) == {"security", "portal"}, "a generated file alone does not rebuild the router program")
everything = on([".circleci/main.yml"], branch="main")
check(everything == set(wc.FLAGS) - {"test_issues", "firmware_json"}, "a CI change runs every build and check: " + str(everything))
check(on(None, branch="main") == set(wc.FLAGS), "no list of files: everything")
check(on(["app/x.kt"], authors=[wc.BOT_EMAIL, wc.BOT_EMAIL]) == set(), "CI's own commits run nothing")
check(on(["app/x.kt"], authors=[wc.BOT_EMAIL, "dev@example.com"]) == {"security", "kotlin", "apk"}, "a push with a person's commit runs")
check(wc.changed("not-a-sha")[0] is None and wc.changed("")[0] is None, "a missing or odd base revision: no list (everything runs)")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
