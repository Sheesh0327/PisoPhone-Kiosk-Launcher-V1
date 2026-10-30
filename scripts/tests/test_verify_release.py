"""Tests the two rules of scripts/verify_release.py that decide whether a change reaches boxes or routers: a document in a watched folder
(a README) is not a change to what a box or a router installs, and the files that are still count."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
fails = 0
total = 0


def check(ok, what):
    global fails, total
    total += 1
    if not ok:
        fails += 1
        print("FAIL:", what)


import verify_release

fw = verify_release.firmware_changed
rt = verify_release.router_changed

check(fw(["esp32_firmware/web/README.md"]) == [], "a README in the dashboard files is not a firmware change")
check(fw(["esp32_firmware/README.md", "esp32_firmware/envs/NOTES.md"]) == [], "no Markdown file under the firmware folders counts")
check(fw(["esp32_firmware/src/main.cpp"]) == ["esp32_firmware/src/main.cpp"], "a source file is a firmware change")
check(fw(["esp32_firmware/web/portal.css", "esp32_firmware/web/README.md"]) == ["esp32_firmware/web/portal.css"],
      "next to a real change, only the real file is counted")
check(fw(["esp32_firmware/include/WebAssets.h"]) == ["esp32_firmware/include/WebAssets.h"], "the embedded dashboard is a firmware change")
check(fw(["esp32_firmware/include/FirmwareVersion.h"]) == [], "raising the version is not itself a firmware change")
check(fw(["esp32_firmware/platformio.ini"]) == ["esp32_firmware/platformio.ini"], "the build setup is a firmware change")

check(rt(["router/README.md"]) is False, "a README in router/ is not a router change")
check(rt(["tools/pisoportal/src/NOTES.md", "setup/src/README.md"]) is False, "no Markdown file under the router folders counts")
check(rt(["router/piso_monitor.sh"]) is True, "a router script is a router change")
check(rt(["tools/pisoportal/src/core.rs"]) is True, "the portal program is a router change")
check(rt(["setup/src/00-header.sh"]) is True, "a part of the setup file is a router change")
check(rt(["setup/piso-setup.sh"]) is True, "the setup file is a router change")
check(rt(["router/README.md", "router/pisoportal.init"]) is True, "next to a real change, the router change still counts")
check(rt([]) is False, "no changed file is no router change")

print(f"{total} checks, {fails} failures")
sys.exit(1 if fails else 0)
