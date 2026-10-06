#!/usr/bin/env python3
"""Builds setup/piso-setup.sh: the single file that is copied to the router. It is setup/piso-setup.sh.in plus the files the
router needs as a payload: text files after a '#@@FILE <destination> <mode>' line, and the portal program (a binary, so as
base64) after a '#@@B64 <destination> <mode> <sha256>' line. Run it after changing any embedded file:
    python3 tools/build_piso_setup.py            writes setup/piso-setup.sh
    python3 tools/build_piso_setup.py --check    fails if the committed file is out of date (CI)
The portal program is built by CI (.github/workflows/rust-router-probe.yml) and committed to tools/pisoportal/bin; CI
rebuilds this file right after, so the two always match."""
import base64, hashlib, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [  # (source in the repository, destination on the router, mode)
    ("router/piso_monitor.sh", "/usr/bin/piso-monitor.sh", "755"),
    ("router/piso_monitor.init", "/etc/init.d/piso_monitor", "755"),
    ("router/pisoportal.init", "/etc/init.d/pisoportal", "755"),
]
BINARIES = [
    ("tools/pisoportal/bin/pisoportal-mipsel", "/usr/bin/pisoportal", "755"),
]


def build():
    version = os.environ.get("SETUP_VERSION") or "dev"
    out = open(os.path.join(ROOT, "setup/piso-setup.sh.in")).read().replace("@VERSION@", version)
    for src, dest, mode in FILES:
        body = open(os.path.join(ROOT, src)).read()
        assert "\n#@@" not in body and "\r" not in body, src
        if not body.endswith("\n"):
            body += "\n"
        out += f"#@@FILE {dest} {mode}\n{body}"
    for src, dest, mode in BINARIES:
        raw = open(os.path.join(ROOT, src), "rb").read()
        b64 = base64.b64encode(raw).decode()
        # the checksum lets the router verify whichever decoder it used
        out += f"#@@B64 {dest} {mode} {hashlib.sha256(raw).hexdigest()}\n" + "".join(b64[i:i + 76] + "\n" for i in range(0, len(b64), 76))
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
