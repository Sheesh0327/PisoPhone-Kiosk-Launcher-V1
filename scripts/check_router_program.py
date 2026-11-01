#!/usr/bin/env python3
"""Refuses a router release whose program was not built from the source being released. Read-only.

    python3 scripts/check_router_program.py [--ref HEAD] [--fetch] [--root DIR]

Why: the router programs (tools/pisoportal/bin/*) are built by CI only on pushes to `beta`, and publish-router.yml signs whatever is
committed. Releases 1.5.25, 1.5.26 and 1.5.28 were signed with the 2026-10-24 program and not the new code. This check makes that
impossible to do by accident. It reads committed files only (the tree of --ref, default HEAD), for every CPU family that has a program
or a setup file (mipsel, mips, aarch64, armv7, x86_64):

  1. EMBEDDED: the program inside setup/piso-setup[-<family>].sh (the base64 after the `#@@B64 /usr/bin/pisoportal 755 <sha256>` line)
     has the sha256 on that line, is byte for byte tools/pisoportal/bin/pisoportal-<family>, and matches that file's .sha256.
  2. SOURCE: what the program was built from is what is being released. tools/pisoportal/bin/BUILD-INFO-<family> names the commit it was
     built from on its first line and, since CI began writing it, a `source:` line with the ids of the source trees (src, Cargo.toml,
     Cargo.lock, owner_key.b64: the four things the program is made of). The source line needs no history; a commit does. In a shallow
     clone, --fetch asks `origin` for that one commit. A commit that cannot be found is a refusal, never a pass.

This is a check, not a rebuild: it does not prove the program is the bit-for-bit result of the source (HARDENING item 11, not built). It
proves the program was built from this source, and that the signed setup files carry the committed program. Exit 1 on any FAIL.
Standard library and git only."""
import argparse
import base64
import binascii
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FAMILY_ORDER = ["mipsel", "mips", "aarch64", "armv7", "x86_64"]
# the four things the program is made of (src holds the console page and scripts it embeds; owner_key.b64 is include_str!'d)
PARTS = [("src", "tools/pisoportal/src"), ("Cargo.toml", "tools/pisoportal/Cargo.toml"),
         ("Cargo.lock", "tools/pisoportal/Cargo.lock"), ("owner_key.b64", "tools/pisoportal/owner_key.b64")]
BIN = "tools/pisoportal/bin"
STALE_HINT = ("the router program was built before the last change to the router source; push to beta, wait for CI's two commits "
              "(\"ci: built the router program\" and \"ci: rebuilt the setup files around the router programs\"), then release")
FILES_HINT = ("the setup files do not carry the committed router programs; let CI rebuild them on beta "
              "(or run python3 tools/build_piso_setup.py after the programs are committed), then release")


def setup_path(family):
    return "setup/piso-setup.sh" if family == "mipsel" else "setup/piso-setup-%s.sh" % family


class Repo:
    def __init__(self, root, ref):
        self.root, self.ref = root, ref

    def run(self, *args):
        return subprocess.run(["git", "-C", self.root, *args], capture_output=True)

    def show(self, path, ref=None):
        p = self.run("show", "%s:%s" % (ref or self.ref, path))
        return p.stdout if p.returncode == 0 else None

    def tree_id(self, path, ref=None):
        p = self.run("rev-parse", "-q", "--verify", "%s:%s" % (ref or self.ref, path))
        return p.stdout.decode().strip() if p.returncode == 0 else None

    def names(self, directory):
        p = self.run("ls-tree", "--name-only", self.ref, directory.rstrip("/") + "/")
        return p.stdout.decode().split() if p.returncode == 0 else []

    def has_commit(self, commit):
        return self.run("cat-file", "-e", commit + "^{commit}").returncode == 0

    def fetch_commit(self, commit):
        return self.run("fetch", "-q", "--depth=1", "origin", commit).returncode == 0


def families(repo):
    found = set()
    for name in repo.names("setup"):
        m = re.fullmatch(r"setup/piso-setup(?:-([a-z0-9_]+))?\.sh", name)
        if m:
            found.add(m.group(1) or "mipsel")
    for name in repo.names(BIN):
        m = re.fullmatch(BIN + r"/pisoportal-([a-z0-9_]+)", name)
        if m:
            found.add(m.group(1))
    return sorted(found, key=lambda f: (FAMILY_ORDER.index(f) if f in FAMILY_ORDER else len(FAMILY_ORDER), f))


def embedded_program(setup_bytes):
    """(header sha256, decoded program, mode) from the #@@B64 /usr/bin/pisoportal block, or (None, reason, None)."""
    lines = setup_bytes.split(b"\n")
    for i, line in enumerate(lines):
        m = re.fullmatch(rb"#@@B64 /usr/bin/pisoportal (\d+) ([0-9a-f]{64})", line.rstrip(b"\r"))
        if m:
            body = []
            for nxt in lines[i + 1:]:
                if nxt.startswith(b"#@@"):
                    break
                body.append(nxt.strip())
            try:
                return m.group(2).decode(), base64.b64decode(b"".join(body), validate=True), m.group(1).decode()
            except (binascii.Error, ValueError):
                return None, "the base64 after the #@@B64 line cannot be decoded", None
    return None, "it has no `#@@B64 /usr/bin/pisoportal` line", None


def check_embedded(repo, family, out):
    """Group 1: the program in the setup file is the committed one. Returns the committed program (or None)."""
    setup, prog_path = setup_path(family), "%s/pisoportal-%s" % (BIN, family)
    program = repo.show(prog_path)
    setup_bytes = repo.show(setup)
    ok = True
    if setup_bytes is None:
        out.append(("FAIL", "%s: %s is missing: a program is committed (%s) but no setup file carries it" % (family, setup, prog_path)))
        ok = False
    if program is None:
        out.append(("FAIL", "%s: %s is missing: %s has a program but nothing is committed to compare it with" % (family, prog_path, setup)))
        return None
    sha = hashlib.sha256(program).hexdigest()
    sha_file = repo.show(prog_path + ".sha256")
    if sha_file is not None and (sha_file.split() or [b""])[0].decode(errors="replace") != sha:
        out.append(("FAIL", "%s: %s.sha256 does not match the program (%s...)" % (family, prog_path, sha[:12])))
        ok = False
    if setup_bytes is not None:
        header, body, mode = embedded_program(setup_bytes)
        if header is None:
            out.append(("FAIL", "%s: %s: %s" % (family, setup, body)))
            ok = False
        else:
            if mode != "755":  # the installer applies this mode with chmod: anything else leaves /usr/bin/pisoportal unable to run
                out.append(("FAIL", "%s: %s installs the program with mode %s, not 755: the router could not run it"
                            % (family, setup, mode)))
                ok = False
            if hashlib.sha256(body).hexdigest() != header:
                out.append(("FAIL", "%s: the program inside %s does not match the sha256 on its #@@B64 line (%s...)"
                            % (family, setup, header[:12])))
                ok = False
            if body != program:
                out.append(("FAIL", "%s: the program inside %s is not the committed %s (embedded %s..., committed %s...)" %
                            (family, setup, prog_path, hashlib.sha256(body).hexdigest()[:12], sha[:12])))
                ok = False
    if ok:
        out.append(("PASS", "%s: the program inside %s is %s (sha256 %s...)" % (family, setup, prog_path, sha[:12])))
    return program


def parse_build_info(text):
    first = text.splitlines()[0] if text.strip() else ""
    m = re.search(r"from commit ([0-9a-f]{40})\b", first)
    source = {}
    for line in text.splitlines():
        if line.startswith("source:"):
            for token in line[len("source:"):].split():
                key, _, value = token.partition("=")
                source[key] = value
    return (m.group(1) if m else None), source


def check_source(repo, family, fetch, out):
    """Group 2: the source the program was built from is the source of this tree. Returns True when it is."""
    info_path = "%s/BUILD-INFO-%s" % (BIN, family)
    raw = repo.show(info_path)
    if raw is None:
        out.append(("FAIL", "%s: %s is missing: nothing says what the program was built from" % (family, info_path)))
        return False
    commit, source = parse_build_info(raw.decode(errors="replace"))
    built = {}
    if source:
        missing = [k for k, _ in PARTS if k not in source]
        if missing:
            out.append(("FAIL", "%s: the source line of %s lacks %s" % (family, info_path, ", ".join(missing))))
            return False
        how = "source line"
        built = {k: source[k] for k, _ in PARTS}
    elif commit:
        how = "commit " + commit[:12]
        if not repo.has_commit(commit) and fetch:
            repo.fetch_commit(commit)
        if not repo.has_commit(commit):
            out.append(("FAIL", "%s: %s says the program was built from commit %s, which is not in this clone (a shallow clone?); run "
                                "git fetch, or use --fetch, or let CI write a source line" % (family, info_path, commit[:12])))
            return False
        built = {k: repo.tree_id(p, commit) for k, p in PARTS}
    else:
        out.append(("FAIL", "%s: %s names no commit and has no source line: nothing says what the program was built from"
                    % (family, info_path)))
        return False
    stale = [p for k, p in PARTS if (repo.tree_id(p) or "") != (built[k] or "")]
    if stale:
        out.append(("FAIL", "%s: the program was built from different source than this tree (by %s): %s changed since"
                    % (family, how, ", ".join(stale))))
        return False
    out.append(("PASS", "%s: built from the source of this tree (by %s)" % (family, how)))
    return True


def check(root, ref="HEAD", fetch=False):
    """The list of (level, text) lines. A level is PASS or FAIL; the last FAIL line says what to do."""
    repo = Repo(root, ref)
    out = []
    if repo.run("rev-parse", "--verify", "-q", ref + "^{commit}").returncode != 0:
        return [("FAIL", "no such commit or ref here: %s" % ref)]
    fams = families(repo)
    if not fams:
        return [("FAIL", "no router program and no router setup file found in %s" % ref)]
    source_bad = False
    for family in fams:
        check_embedded(repo, family, out)
        source_bad = not check_source(repo, family, fetch, out) or source_bad
    if source_bad:
        out.append(("FAIL", STALE_HINT))
    elif any(level == "FAIL" for level, _ in out):
        out.append(("FAIL", FILES_HINT))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=ROOT, help="the repository (default: this one)")
    ap.add_argument("--ref", default="HEAD", help="the commit whose committed files are checked (default HEAD)")
    ap.add_argument("--fetch", action="store_true", help="in a shallow clone, fetch the one commit BUILD-INFO names from origin")
    a = ap.parse_args()
    lines = check(a.root, a.ref, a.fetch)
    for level, text in lines:
        print("%-5s %s" % (level, text))
    bad = sum(1 for level, _ in lines if level == "FAIL")
    print("router program check: %s" % ("%d problem line(s)" % bad if bad else "ok"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
