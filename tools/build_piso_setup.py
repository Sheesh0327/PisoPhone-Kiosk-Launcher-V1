#!/usr/bin/env python3
"""Builds setup/piso-setup.sh: the single file that is copied to the router. It is setup/piso-setup.sh.in (itself generated from
the parts in setup/src/: edit those) plus the files the
router needs as a payload: text files after a '#@@FILE <destination> <mode>' line, and the portal program (a binary, so as
base64) after a '#@@B64 <destination> <mode> <sha256>' line. Run it after changing any embedded file:
    python3 tools/build_piso_setup.py            writes setup/piso-setup.sh
    python3 tools/build_piso_setup.py --check    fails if the committed file is out of date (CI)
The portal program is built by CI (.github/workflows/router-program.yml) and committed to tools/pisoportal/bin; CI
rebuilds this file right after, so the two always match. It also writes the file's sha256 and the website's copy of the
one-line installer (setup/install.sh) and of setup/pisophone_setup.py."""
import base64, hashlib, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [  # (source in the repository, destination on the router, mode)
    ("router/piso_monitor.sh", "/usr/bin/piso-monitor.sh", "755"),
    ("router/piso_monitor.init", "/etc/init.d/piso_monitor", "755"),
    ("router/pisoportal.init", "/etc/init.d/pisoportal", "755"),
    ("router/pisoportal_status.sh", "/usr/lib/opennds/pisoportal_status.sh", "755"),
]
BINARIES = [
    ("tools/pisoportal/bin/pisoportal-mipsel", "/usr/bin/pisoportal", "755"),
]


def template():
    """setup/piso-setup.sh.in: the parts in setup/src/ (NN-name.sh, in name order) joined as they are. The parts are what you
    edit; the .in file is generated (and committed, so tools and CI that read it keep working)."""
    src = os.path.join(ROOT, "setup/src")
    names = sorted(n for n in os.listdir(src) if re.fullmatch(r"\d\d-[a-z0-9-]+\.sh", n))
    assert names, "setup/src has no parts"
    return "".join(open(os.path.join(src, n)).read() for n in names)


def build():
    version = os.environ.get("SETUP_VERSION") or "dev"
    release = open(os.path.join(ROOT, "setup/RELEASE")).read().strip()
    assert re.fullmatch(r"\d{1,5}\.\d{1,5}\.\d{1,5}", release), "setup/RELEASE must look like 1.2.3"
    out = template().replace("@VERSION@", version).replace("@RELEASE@", release)
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


def outputs(text):
    """Every generated file and its content: the setup file, its sha256 (the one-line installer checks the download against
    it) and the website's copies of the installer (https://pisophone.pages.dev/install.sh) and of the computer-side setup
    script (https://pisophone.pages.dev/pisophone_setup.py)."""
    return {
        "setup/piso-setup.sh.in": template(),
        "setup/piso-setup.sh": text,
        "setup/piso-setup.sh.sha256": hashlib.sha256(text.encode()).hexdigest() + "  piso-setup.sh\n",
        # the router downloads the setup file from the website (the repository is private): the installer's site_for()
        "website/setup/piso-setup.sh": text,
        "website/setup/piso-setup.sh.sha256": hashlib.sha256(text.encode()).hexdigest() + "  piso-setup.sh\n",
        "website/install.sh": open(os.path.join(ROOT, "setup/install.sh")).read(),
        "website/pisophone_setup.py": open(os.path.join(ROOT, "setup/pisophone_setup.py")).read(),
    }


if __name__ == "__main__":
    files = outputs(build())
    if "--check" in sys.argv:
        stale = [p for p, t in files.items() if not os.path.exists(os.path.join(ROOT, p)) or open(os.path.join(ROOT, p)).read() != t]
        if stale:
            sys.exit(", ".join(stale) + " out of date: edit the parts in setup/src/ (not the generated files), then run python3 tools/build_piso_setup.py")
        print("setup/piso-setup.sh (with its sha256) and the website's copies (website/setup/, install.sh, pisophone_setup.py) are up to date")
    else:
        for p, t in files.items():
            target = os.path.join(ROOT, p)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            open(target, "w").write(t)
            if p.endswith((".sh", ".py")):
                os.chmod(target, 0o755)
            print(f"wrote {target} ({len(t)} bytes)")
