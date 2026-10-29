"""Tests scripts/check_links.py: a markdown link or a `path` in backticks that names a file the repository does not have is reported."""
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
fails = 0


def check(ok, what):
    global fails
    if not ok:
        fails += 1
        print("FAIL:", what)


import check_links  # noqa: E402

root = tempfile.mkdtemp()
for rel, text in {
    "README.md": "See [the guide](docs/GUIDE.md), [gone](docs/GONE.md), [site](https://example.com/x.md) and [top](#top).\n"
                 "Code in `scripts/real.py:12` and `scripts/missing.py` and `specs/features/NNN-name/` and `docs/*.md`.\n",
    "docs/GUIDE.md": "Back to [readme](../README.md) and [bad](../nothing.md#part).\n",
    "scripts/real.py": "",
    "CHANGELOG.md": "Old `scripts/removed.py` is allowed here.\n",
    "specs/notes/old.md": "[old](../nowhere.md)\n",
}.items():
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)
subprocess.run(["git", "init", "-q"], cwd=root, check=True)
subprocess.run(["git", "add", "-A"], cwd=root, check=True)

dead = {(f, t) for f, _n, t in check_links.find_dead(root)}
check(("README.md", "docs/GONE.md") in dead, "a dead markdown link is reported")
check(("README.md", "scripts/missing.py") in dead, "a dead backtick path is reported")
check(("docs/GUIDE.md", "../nothing.md") in dead, "a dead link is resolved against the file's folder, its #part dropped")
check(not any(t in ("docs/GUIDE.md", "../README.md", "scripts/real.py", "https://example.com/x.md", "#top") for _f, t in dead),
      "links that work (also with :12, an https link, an anchor) are not reported: " + str(dead))
check(not any("NNN" in t or "*" in t for _f, t in dead), "placeholders and globs are not reported")
check(not any(f in ("CHANGELOG.md", "specs/notes/old.md") for f, _t in dead), "history (CHANGELOG, notes) is not scanned by default")
check(("specs/notes/old.md", "../nowhere.md") in {(f, t) for f, _n, t in check_links.find_dead(root, everything=True)}, "--all scans it")
r = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "check_links.py"), "--root", root], capture_output=True, text=True)
check(r.returncode == 1 and "docs/GONE.md" in r.stdout, "the command exits 1 and names the dead link: " + r.stdout + r.stderr)
print(f"check_links: {fails} failures" if fails else "check_links: OK")
sys.exit(1 if fails else 0)
