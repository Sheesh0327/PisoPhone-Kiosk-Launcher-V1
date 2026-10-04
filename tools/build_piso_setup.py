#!/usr/bin/env python3
"""Builds setup/piso-setup.sh: the single file that is copied to the router. It is setup/piso-setup.sh.in plus the portal
files appended as a payload (each after a '#@@FILE <destination> <mode>' line). Run it after changing any embedded file:
    python3 tools/build_piso_setup.py            writes setup/piso-setup.sh
    python3 tools/build_piso_setup.py --check    fails if the committed file is out of date (CI)"""
import os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [  # (source in the repository, destination on the router, mode)
    ("opennds/coinslot-listener.sh", "/usr/bin/coinslot-listener.sh", "755"),
    ("opennds/flash_coin.sh", "/usr/lib/opennds/flash_coin.sh", "755"),
    ("opennds/flash_coin_lib.sh", "/usr/lib/opennds/flash_coin_lib.sh", "755"),
    ("opennds/flash_coin_status.sh", "/usr/lib/opennds/flash_coin_status.sh", "755"),
    ("opennds/flash_fairuse.sh", "/usr/lib/opennds/flash_fairuse.sh", "755"),
    ("opennds/flash_coin.init", "/etc/init.d/flash_coin", "755"),
]


def build():
    version = os.environ.get("SETUP_VERSION") or "dev"
    out = open(os.path.join(ROOT, "setup/piso-setup.sh.in")).read().replace("@VERSION@", version)
    for src, dest, mode in FILES:
        body = open(os.path.join(ROOT, src)).read()
        assert "\n#@@FILE " not in body and "\r" not in body, src
        if not body.endswith("\n"):
            body += "\n"
        out += f"#@@FILE {dest} {mode}\n{body}"
    return out


if __name__ == "__main__":
    target = os.path.join(ROOT, "setup/piso-setup.sh")
    text = build()
    if "--check" in sys.argv:
        if not os.path.exists(target) or open(target).read() != text:
            sys.exit("setup/piso-setup.sh is out of date: run python3 tools/build_piso_setup.py")
        print("setup/piso-setup.sh is up to date")
    else:
        open(target, "w").write(text)
        os.chmod(target, 0o755)
        print(f"wrote {target} ({len(text)} bytes)")
