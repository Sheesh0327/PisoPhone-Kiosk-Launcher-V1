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
import base64, gzip, hashlib, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILES = [  # (source in the repository, destination on the router, mode)
    ("router/piso_monitor.sh", "/usr/bin/piso-monitor.sh", "755"),
    ("router/piso_monitor.init", "/etc/init.d/piso_monitor", "755"),
    ("router/pisoportal.init", "/etc/init.d/pisoportal", "755"),
    ("router/pisoportal_status.sh", "/usr/lib/opennds/pisoportal_status.sh", "755"),
    ("router/piso_isolation.sh", "/etc/hotplug.d/firewall/50-piso-isolation", "755"),
]
DEFAULT_ARCH = "mipsel"   # its setup file is setup/piso-setup.sh (as it always was); the other families have setup/piso-setup-<family>.sh
ARCHES = ["mipsel", "mips", "aarch64", "armv7", "x86_64"]


def binary_for(arch):
    return [(f"tools/pisoportal/bin/pisoportal-{arch}", "/usr/bin/pisoportal", "755")]


def built_arches():
    """The families that have a portal program in tools/pisoportal/bin (CI builds them)."""
    return [a for a in ARCHES if os.path.exists(os.path.join(ROOT, f"tools/pisoportal/bin/pisoportal-{a}"))]


def setup_name(arch):
    return "piso-setup.sh" if arch == DEFAULT_ARCH else f"piso-setup-{arch}.sh"


def template():
    """setup/piso-setup.sh.in: the parts in setup/src/ (NN-name.sh, in name order) joined as they are. The parts are what you
    edit; the .in file is generated (and committed, so tools and CI that read it keep working)."""
    src = os.path.join(ROOT, "setup/src")
    names = sorted(n for n in os.listdir(src) if re.fullmatch(r"\d\d-[a-z0-9-]+\.sh", n))
    assert names, "setup/src has no parts"
    return "".join(open(os.path.join(src, n)).read() for n in names)


def build(arch=DEFAULT_ARCH):
    version = os.environ.get("SETUP_VERSION") or "dev"
    release = open(os.path.join(ROOT, "setup/RELEASE")).read().strip()
    assert re.fullmatch(r"\d{1,5}\.\d{1,5}\.\d{1,5}", release), "setup/RELEASE must look like 1.2.3"
    out = template().replace("@VERSION@", version).replace("@RELEASE@", release).replace("@ARCH@", arch)
    for src, dest, mode in FILES:
        body = open(os.path.join(ROOT, src)).read()
        assert "\n#@@" not in body and "\r" not in body, src
        if not body.endswith("\n"):
            body += "\n"
        out += f"#@@FILE {dest} {mode}\n{body}"
    for src, dest, mode in binary_for(arch):
        raw = open(os.path.join(ROOT, src), "rb").read()
        gz = gzip.compress(raw, compresslevel=9, mtime=0)
        b64 = base64.b64encode(gz).decode()
        # the checksum verifies the uncompressed executable after decoding and gunzip
        out += f"#@@B64 {dest} {mode} {hashlib.sha256(raw).hexdigest()} gz\n" + "".join(b64[i:i + 76] + "\n" for i in range(0, len(b64), 76))
    return out


def outputs(text):
    """Every generated file and its content: the setup file, its sha256 (the one-line installer checks the download against
    it) and the website's copies of the installer (https://pisophone.pages.dev/install.sh) and of the computer-side setup
    script (https://pisophone.pages.dev/pisophone_setup.py). The other CPU families get setup/piso-setup-<family>.sh (and the same
    copies on the website) for every family whose portal program CI has built."""
    out = {
        "setup/piso-setup.sh.in": template(),
        "setup/piso-setup.sh": text,
        "setup/piso-setup.sh.sha256": hashlib.sha256(text.encode()).hexdigest() + "  piso-setup.sh\n",
        # the router downloads the setup file from the website (the repository is private): the installer's site_for()
        "website/setup/piso-setup.sh": text,
        "website/setup/piso-setup.sh.sha256": hashlib.sha256(text.encode()).hexdigest() + "  piso-setup.sh\n",
        "website/install.sh": open(os.path.join(ROOT, "setup/install.sh")).read(),
        "website/pisophone_setup.py": open(os.path.join(ROOT, "setup/pisophone_setup.py")).read(),
    }
    for arch in built_arches():
        if arch == DEFAULT_ARCH:
            continue
        body, name = build(arch), setup_name(arch)
        digest = hashlib.sha256(body.encode()).hexdigest() + f"  {name}\n"
        out.update({f"setup/{name}": body, f"setup/{name}.sha256": digest, f"website/setup/{name}": body, f"website/setup/{name}.sha256": digest})
    return out


def check_match(path, expected):
    if not os.path.exists(path):
        return False
    actual = open(path).read()
    if actual == expected:
        return True
    if not path.endswith(".sh"):
        return False
    marker = "\n#@@B64 "
    if marker not in actual or marker not in expected:
        return False
    a_head, a_tail = actual.split(marker, 1)
    e_head, e_tail = expected.split(marker, 1)
    if a_head != e_head:
        return False
    a_hdr, a_b64 = a_tail.split("\n", 1)
    e_hdr, e_b64 = e_tail.split("\n", 1)
    if a_hdr != e_hdr or not a_hdr.endswith(" gz"):
        return False
    try:
        return gzip.decompress(base64.b64decode(a_b64)) == gzip.decompress(base64.b64decode(e_b64))
    except Exception:
        return False


if __name__ == "__main__":
    files = outputs(build())
    if "--check" in sys.argv:
        stale = [p for p, t in files.items() if not p.endswith(".sha256") and not check_match(os.path.join(ROOT, p), t)]
        # keep .sha256 and website copies strictly matching the committed .sh bytes
        for p, t in files.items():
            if p + ".sha256" in files and os.path.exists(os.path.join(ROOT, p)):
                body = open(os.path.join(ROOT, p)).read()
                exp_sha = hashlib.sha256(body.encode()).hexdigest() + f"  {os.path.basename(p)}\n"
                sha_p = os.path.join(ROOT, p + ".sha256")
                if not os.path.exists(sha_p) or open(sha_p).read() != exp_sha:
                    stale.append(p + ".sha256")
                if p.startswith("setup/"):
                    web_p = os.path.join(ROOT, "website", p)
                    if not os.path.exists(web_p) or open(web_p).read() != body:
                        stale.append("website/" + p)
        if stale:
            sys.exit(", ".join(sorted(set(stale))) + " out of date: edit the parts in setup/src/ (not the generated files), then run python3 tools/build_piso_setup.py")
        print("setup/piso-setup.sh (with its sha256) and the website's copies (website/setup/, install.sh, pisophone_setup.py) are up to date")
    else:
        for p, t in files.items():
            target = os.path.join(ROOT, p)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            open(target, "w", newline="\n").write(t)
            if p.endswith((".sh", ".py")) and not p.endswith(".sha256"):
                os.chmod(target, 0o755)
            print(f"wrote {target} ({len(t)} bytes)")
