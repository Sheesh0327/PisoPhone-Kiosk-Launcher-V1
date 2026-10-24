"""AC2.4: a signing step in a workflow must not run a script from the tree it has just moved with `git reset --hard`.

After a `reset --hard origin/<branch>` the working tree is the NEWEST branch tip, not the commit the owner approved
($GITHUB_SHA). A signing script called as a bare `scripts/<name>.py` after that would be whatever was pushed after the approval.
Such a script must be run from a copy of $GITHUB_SHA (for example `python3 "$TRUSTED/scripts/sign_router.py"`), which this check
sees as a path that does not start at `scripts/`. Plain text check; run it with: python3 scripts/tests/test_workflow_signing.py"""
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")

RESET = re.compile(r"git\s+reset\b[^\n]*--hard")
# a bare relative call of a script that signs or publishes signed files (not one that is preceded by a path such as "$TRUSTED/")
SIGNING = re.compile(r"(?<![\w/}\"'.-])scripts/(sign_\w+|publish_firmware_ota|publish_\w+|make_owner_keys)\.py")
JOB = re.compile(r"^  ([\w-]+):\s*$")


def problems(path):
    found = []
    job, reset_line = None, None
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            m = JOB.match(line)
            if m:
                job, reset_line = m.group(1), None
                continue
            if line.lstrip().startswith("#") or "sha256sum" in line:  # (a fingerprint reads the file, it does not run it)
                continue
            if RESET.search(line):
                reset_line = number
            elif reset_line and SIGNING.search(line):
                found.append(f"{os.path.basename(path)}:{number} (job {job}): signing script run from the tree after "
                             f"`git reset --hard` on line {reset_line}; run it from a copy of $GITHUB_SHA")
    return found


def main():
    files = sorted(glob.glob(os.path.join(WORKFLOWS, "*.yml")))
    if not files:
        print("FAIL: no workflow files found")
        return 1
    bad = [p for f in files for p in problems(f)]
    for p in bad:
        print("FAIL:", p)
    if not bad:
        print(f"ok: {len(files)} workflows, no signing step follows a git reset --hard")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
