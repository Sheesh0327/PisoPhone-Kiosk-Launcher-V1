"""scripts/check_router_program.py refuses a router release whose program is not the one built from the source being released.

Every case builds a small throwaway git repository (the same layout as the real one: tools/pisoportal/src, Cargo.toml, Cargo.lock,
owner_key.b64, tools/pisoportal/bin/pisoportal-<family> with its .sha256 and BUILD-INFO-<family>, and setup/piso-setup[-<family>].sh
with the program after a `#@@B64 /usr/bin/pisoportal 755 <sha256>` line) and runs the checker on it. Nothing in the real tree is read.
Run it with: python3 scripts/tests/test_check_router_program.py"""
import base64
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "check_router_program.py")
sys.path.insert(0, os.path.join(HERE, ".."))
fails = n = 0


def check(ok, what):
    global fails, n
    n += 1
    if not ok:
        fails += 1
        print("FAIL:", what)


FAMILIES = ["mipsel", "mips"]
SOURCE_FILES = ["tools/pisoportal/src/main.rs", "tools/pisoportal/src/core.rs", "tools/pisoportal/Cargo.toml",
                "tools/pisoportal/Cargo.lock", "tools/pisoportal/owner_key.b64"]


def git(root, *args):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.com", GIT_COMMITTER_NAME="t",
               GIT_COMMITTER_EMAIL="t@example.com", GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")
    p = subprocess.run(["git", "-C", root, *args], capture_output=True, text=True, env=env)
    assert p.returncode == 0, (args, p.stderr)
    return p.stdout.strip()


def write(root, rel, data):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb" if isinstance(data, bytes) else "w") as f:
        f.write(data)


def setup_file(program, header_sha=None, mode="755"):
    """A setup file the way tools/build_piso_setup.py writes one: the script, then the program as 76-column base64."""
    b64 = base64.b64encode(program).decode()
    sha = header_sha or hashlib.sha256(program).hexdigest()
    lines = "".join(b64[i:i + 76] + "\n" for i in range(0, len(b64), 76))
    return "#!/bin/sh\necho installer\n#@@FILE /etc/x 644\nhello\n#@@B64 /usr/bin/pisoportal %s %s\n%s" % (mode, sha, lines)


def program(family, tag=""):
    return (("program for %s %s\n" % (family, tag)) * 40).encode() + bytes(range(256))


def tree_ids(root, ref="HEAD"):
    out = []
    for name, path in (("src", "tools/pisoportal/src"), ("Cargo.toml", "tools/pisoportal/Cargo.toml"),
                       ("Cargo.lock", "tools/pisoportal/Cargo.lock"), ("owner_key.b64", "tools/pisoportal/owner_key.b64")):
        out.append("%s=%s" % (name, git(root, "rev-parse", "%s:%s" % (ref, path))))
    return " ".join(out)


def make_repo(source_line=False):
    """A repository whose programs were built from its source: commit 1 is the source, commit 2 adds the built files."""
    root = tempfile.mkdtemp(prefix="routerprog-")
    git(root, "init", "-q", "-b", "main")
    for rel in SOURCE_FILES:
        write(root, rel, "content of %s\n" % rel)
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "source")
    built_from = git(root, "rev-parse", "HEAD")
    ids = tree_ids(root)
    for fam in FAMILIES:
        prog = program(fam)
        write(root, "tools/pisoportal/bin/pisoportal-%s" % fam, prog)
        write(root, "tools/pisoportal/bin/pisoportal-%s.sha256" % fam, "%s  pisoportal-%s\n" % (hashlib.sha256(prog).hexdigest(), fam))
        info = "pisoportal 1.0.0 for %s-unknown-linux-musl (%s), from commit %s\nrustc 1.99.1\nC toolchain: x\n" % (fam, fam, built_from)
        if source_line:
            info += "source: %s\n" % ids
        write(root, "tools/pisoportal/bin/BUILD-INFO-%s" % fam, info)
        write(root, "setup/piso-setup.sh" if fam == "mipsel" else "setup/piso-setup-%s.sh" % fam, setup_file(prog))
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "ci: built the router programs")
    return root, built_from


def run(root, *extra):
    # the checker reads committed files only: whatever a case changed is committed first
    git(root, "add", "-A")
    if git(root, "status", "--porcelain"):
        git(root, "commit", "-q", "-m", "the case's change")
    p = subprocess.run([sys.executable, SCRIPT, "--root", root, *extra], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def fresh(source_line=False):
    root, built_from = make_repo(source_line)
    return root, built_from


cleanup = []


def case(source_line=False):
    root, built_from = fresh(source_line)
    cleanup.append(root)
    return root, built_from


# 1. a clean tree passes (the commit named in BUILD-INFO holds the same source)
root, built_from = case()
code, out = run(root)
check(code == 0, "clean tree (commit line only) passes; got %d: %s" % (code, out))
check("FAIL" not in out, "clean tree prints no FAIL: " + out)
check("mipsel" in out and "mips" in out, "clean tree reports each family: " + out)

# 2. a clean tree whose BUILD-INFO has the source line passes with no history at all (the named commit does not exist here)
root, built_from = case(source_line=True)
info_path = os.path.join(root, "tools/pisoportal/bin/BUILD-INFO-mips")
text = open(info_path).read().replace(built_from, "f" * 40)
write(root, "tools/pisoportal/bin/BUILD-INFO-mips", text)
info_path = os.path.join(root, "tools/pisoportal/bin/BUILD-INFO-mipsel")
write(root, "tools/pisoportal/bin/BUILD-INFO-mipsel", open(info_path).read().replace(built_from, "f" * 40))
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "unreachable commit")
code, out = run(root)
check(code == 0, "a source line needs no history; got %d: %s" % (code, out))

# 3. the source changed after the programs were built: refused, names the changed part and tells the owner what to do
root, built_from = case()
write(root, "tools/pisoportal/src/core.rs", "money code changed after the build\n")
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "change the router source on main, no rebuild")
code, out = run(root)
check(code == 1, "source changed after build is refused; got %d" % code)
check("FAIL" in out and "tools/pisoportal/src" in out, "the refusal names the source: " + out)
check("beta" in out and "built before the last change" in out, "the refusal says what to do: " + out)

# 4. the same with a source line (the tree ids differ)
root, built_from = case(source_line=True)
write(root, "tools/pisoportal/src/core.rs", "changed\n")
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "change the router source")
code, out = run(root)
check(code == 1 and "tools/pisoportal/src" in out, "source line: stale source is refused: %d %s" % (code, out))

# 5. Cargo.lock or the owner key changed after the build (they are inside the program too)
for rel in ("tools/pisoportal/Cargo.lock", "tools/pisoportal/owner_key.b64"):
    root, built_from = case()
    write(root, rel, "changed\n")
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "change " + rel)
    code, out = run(root)
    check(code == 1 and os.path.basename(rel) in out, "%s changed after the build is refused: %d %s" % (rel, code, out))

# 6. a change that is not in the program (a test, a doc) does not matter
root, built_from = case()
write(root, "tools/pisoportal/tests/test_x.py", "a test\n")
write(root, "docs/x.md", "words\n")
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "tests and docs only")
code, out = run(root)
check(code == 0, "a test or a document changed is not a reason to refuse: %d %s" % (code, out))

# 7. one family's embedded program is not the committed one (body and header agree with each other, but not with bin/)
root, built_from = case()
other = program("mips", "swapped")
write(root, "setup/piso-setup-mips.sh", setup_file(other))
code, out = run(root)
check(code == 1 and "mips" in out and "FAIL" in out, "a swapped program in one family is refused: %d %s" % (code, out))
check("mipsel" not in "".join(ln for ln in out.splitlines() if ln.startswith("FAIL")), "the other family is not blamed: " + out)

# 8. the sha256 on the #@@B64 line is wrong (the body is the committed program)
root, built_from = case()
write(root, "setup/piso-setup.sh", setup_file(program("mipsel"), header_sha="0" * 64))
code, out = run(root)
check(code == 1 and "sha256" in out and "setup/piso-setup.sh" in out, "a wrong header sha256 is refused: %d %s" % (code, out))

# 9. the body was edited but the header still has the old sha256
root, built_from = case()
good = program("mipsel")
write(root, "setup/piso-setup.sh", setup_file(program("mipsel", "edited"), header_sha=hashlib.sha256(good).hexdigest()))
code, out = run(root)
check(code == 1 and "FAIL" in out, "a body that does not match its header is refused: %d %s" % (code, out))

# 10. BUILD-INFO is missing for a family
root, built_from = case()
os.remove(os.path.join(root, "tools/pisoportal/bin/BUILD-INFO-mips"))
code, out = run(root)
check(code == 1 and "BUILD-INFO-mips" in out, "a missing BUILD-INFO is refused: %d %s" % (code, out))

# 11. BUILD-INFO that does not name a commit and has no source line
root, built_from = case()
write(root, "tools/pisoportal/bin/BUILD-INFO-mips", "pisoportal 1.0.0 for mips\nrustc\n")
code, out = run(root)
check(code == 1 and "BUILD-INFO-mips" in out, "a BUILD-INFO that names no commit is refused: %d %s" % (code, out))

# 12. the named commit is not in this clone and there is no source line: refuse and say why (never pass)
root, built_from = case()
for fam in FAMILIES:
    p = os.path.join(root, "tools/pisoportal/bin/BUILD-INFO-%s" % fam)
    write(root, "tools/pisoportal/bin/BUILD-INFO-%s" % fam, open(p).read().replace(built_from, "e" * 40))
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "commit that is not here")
code, out = run(root)
check(code == 1 and "not in this" in out and "e" * 12 in out, "an unknown commit is refused with a plain reason: %d %s" % (code, out))

# 13. the program file is missing from bin/ while its setup file has one
root, built_from = case()
os.remove(os.path.join(root, "tools/pisoportal/bin/pisoportal-mips"))
code, out = run(root)
check(code == 1 and "pisoportal-mips" in out, "a setup file with no committed program is refused: %d %s" % (code, out))

# 14. a program in bin/ without a setup file around it
root, built_from = case()
os.remove(os.path.join(root, "setup/piso-setup-mips.sh"))
code, out = run(root)
check(code == 1 and "piso-setup-mips.sh" in out, "a program with no setup file is refused: %d %s" % (code, out))

# 15. the setup file has no program at all
root, built_from = case()
write(root, "setup/piso-setup-mips.sh", "#!/bin/sh\necho installer\n")
code, out = run(root)
check(code == 1 and "#@@B64" in out, "a setup file without the #@@B64 line is refused: %d %s" % (code, out))

# 16. bin/<family>.sha256 disagrees with the program
root, built_from = case()
write(root, "tools/pisoportal/bin/pisoportal-mips.sha256", "%s  pisoportal-mips\n" % ("1" * 64))
code, out = run(root)
check(code == 1 and "pisoportal-mips.sha256" in out, "a wrong bin/*.sha256 is refused: %d %s" % (code, out))

# 17. --ref: check another commit than the one checked out (the older commit has the built files at its head)
root, built_from = case()
good_head = git(root, "rev-parse", "HEAD")
write(root, "tools/pisoportal/src/core.rs", "changed\n")
git(root, "add", "-A")
git(root, "commit", "-q", "-m", "later change")
code, out = run(root, "--ref", good_head)
check(code == 0, "--ref checks that commit's tree, not the working tree's: %d %s" % (code, out))

# 18. a shallow clone that lacks the named commit passes after --fetch (the signing job checks out one commit)
origin, built_from = fresh()
cleanup.append(origin)
write(origin, "docs/later.md", "a later commit that is not in the build\n")
git(origin, "add", "-A")
git(origin, "commit", "-q", "-m", "docs only, later")
clone = tempfile.mkdtemp(prefix="routerprog-clone-")
cleanup.append(clone)
shutil.rmtree(clone)
env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_SYSTEM="/dev/null")
subprocess.run(["git", "clone", "-q", "--depth=1", "file://" + origin, clone], check=True, capture_output=True, env=env)
code, out = run(clone)
check(code == 1 and "not in this" in out, "a shallow clone without the commit is refused: %d %s" % (code, out))
code, out = run(clone, "--fetch")
check(code == 0, "--fetch gets the named commit and then passes: %d %s" % (code, out))

# 19. the program would be installed without the executable bit (mode on the #@@B64 line is applied with chmod by the installer)
root, built_from = case()
write(root, "setup/piso-setup-mips.sh", setup_file(program("mips"), mode="644"))
code, out = run(root)
check(code == 1 and "644" in out and "piso-setup-mips.sh" in out, "a program with mode 644 is refused: %d %s" % (code, out))
check("mipsel" not in "".join(ln for ln in out.splitlines() if ln.startswith("FAIL")),
      "only the family with the bad mode is blamed: " + out)

for d in cleanup:
    shutil.rmtree(d, ignore_errors=True)
print("%d checks, %d failures" % (n, fails))
sys.exit(1 if fails else 0)
