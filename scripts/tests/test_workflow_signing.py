"""AC2.4: a signing step in a workflow must not run a script from the tree it has just moved with `git reset --hard`.

After a `reset --hard origin/<branch>` the working tree is the NEWEST branch tip, not the commit the owner approved
($GITHUB_SHA). A signing script called as a bare `scripts/<name>.py` after that would be whatever was pushed after the approval.
Such a script must be run from a copy of $GITHUB_SHA (for example `python3 "$TRUSTED/scripts/sign_router.py"`), which this check
sees as a path that does not start at `scripts/`. Plain text check; run it with: python3 scripts/tests/test_workflow_signing.py

Feature 060: publish-router.yml must run scripts/check_router_program.py (the router program was built from the source being
released) BEFORE any step that signs, and the step must not be allowed to fail quietly."""
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


GUARD = "check_router_program.py"
SIGN_CALL = re.compile(r"scripts/(sign_router|sign_console_ui)\.py")
STEP = re.compile(r"^\s*- (name|uses):")


def guard_problems(path):
    """The router guard runs before the first signing call of the workflow, in a step that stops the run when it fails."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    name = os.path.basename(path)
    code = [(i, ln) for i, ln in enumerate(lines) if not ln.lstrip().startswith("#")]
    guard = [i for i, ln in code if GUARD in ln]
    sign = [i for i, ln in code if SIGN_CALL.search(ln)]
    if not guard:
        return [f"{name}: no step runs scripts/{GUARD}: a router release could be signed around an old router program"]
    if sign and min(sign) < min(guard):
        return [f"{name}:{min(guard) + 1}: the router program check runs after the first signing call (line {min(sign) + 1})"]
    start = max(i for i in range(min(guard) + 1) if STEP.match(lines[i]))
    end = next((i for i in range(min(guard) + 1, len(lines)) if STEP.match(lines[i])), len(lines))
    step = "\n".join(lines[start:end])
    if re.search(r"continue-on-error|\|\|\s*(true|:)\b|\bset \+e", step):
        return [f"{name}:{min(guard) + 1}: the router program check is allowed to fail without stopping the run"]
    return []


def main():
    files = sorted(glob.glob(os.path.join(WORKFLOWS, "*.yml")))
    if not files:
        print("FAIL: no workflow files found")
        return 1
    bad = [p for f in files for p in problems(f)]
    bad += guard_problems(os.path.join(WORKFLOWS, "publish-router.yml"))
    for p in bad:
        print("FAIL:", p)
    if not bad:
        print(f"ok: {len(files)} workflows, no signing step follows a git reset --hard; publish-router.yml checks the router program first")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
