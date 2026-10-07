#!/usr/bin/env python3
"""Decides which CircleCI workflows a push needs, from the files it changed, and hands them on (.circleci/config.yml).

    python3 .circleci/what_changed.py <base revision>        writes /tmp/continue.json for the continuation API
    python3 .circleci/what_changed.py <base revision> --print   prints the flags only

Each flag is a pipeline parameter of .circleci/main.yml, whose workflows run only when theirs is true. A firmware build is
skipped by a change to the portal, and so on, so no credit is spent on what a change cannot affect. A push made only by
CI itself (the APK, the router program, the flasher images...) runs nothing: those commits change generated files only.
Without a list of changed files (a new branch, or history that is not there) everything runs."""
import json
import os
import re
import subprocess
import sys

BOT_EMAIL = "ci@pisophone.invalid"     # the author of CI's own commits (.circleci/main.yml, the bot-git command)
C = r"\.circleci/"                     # a change to the CI itself runs everything it describes

# flag -> (regex of the paths that matter, branches it runs on: None for every branch)
FLAGS = {
    "security": (r".", None),
    "kotlin": (rf"^(app/|[^/]*\.kts$|\.editorconfig|{C})", None),
    "firmware_build": (rf"^(esp32_firmware/|protocol/|{C})", None),
    "firmware_tests": (rf"^(esp32_firmware/|protocol/|scripts/|{C})", None),
    "portal": (rf"^(tools/pisoportal/|router/|setup/|website/install\.sh|website/pisophone_setup\.py|website/setup/|"
               rf"tools/build_piso_setup\.py|scripts/(sign_router|generate_license|make_owner_keys)\.py|{C})", None),
    "website": (rf"^(website/|tools/site-css/|scripts/test_provisioning\.js|scripts/test_flasher\.js|"
                rf"scripts/tests/test_site_page\.py|scripts/check_vendored\.py|{C})", None),
    "apk": (rf"^(app/|protocol/|gradle/|[^/]*\.gradle\.kts$|gradle\.properties$|{C})", None),
    "router_program": (rf"^(tools/pisoportal/src/|tools/pisoportal/Cargo\.|tools/pisoportal/owner_key\.b64|setup/RELEASE|"
                       rf"router/[^/]*\.init$|router/piso_monitor\.sh|setup/piso-setup\.sh\.in|tools/build_piso_setup\.py|{C})", None),
    "firmware_images": (rf"^(esp32_firmware/|{C})", ("main", "beta")),
    "test_issues": (r"^docs/REAL_WORLD_TESTING\.md$", None),
    "firmware_json": (r"^website/update/firmware-esp32(c3)?\.bin$", ("main",)),
}


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout


def changed(base):
    """The files changed since base, and the authors of the commits in between (None when not known)."""
    if not base or not re.fullmatch(r"[0-9a-f]{7,40}", base) or git("cat-file", "-t", base).strip() != "commit":
        return None, [git("log", "-1", "--format=%ae").strip()]
    files = [f for f in git("diff", "--name-only", f"{base}...HEAD").splitlines() if f]
    authors = [a for a in git("log", "--format=%ae", f"{base}..HEAD").splitlines() if a]
    return files, authors


def flags(files, authors, branch):
    if authors and all(a == BOT_EMAIL for a in authors):
        print("only CI's own commits: nothing to run")
        return {name: False for name in FLAGS}
    if files is None:
        print("no list of changed files (a new branch, or its base is not in the history): running everything")
    out = {}
    for name, (pattern, branches) in FLAGS.items():
        on_branch = branches is None or branch in branches
        out[name] = on_branch and (files is None or any(re.search(pattern, f) for f in files))
    return out


def main(argv):
    base = argv[1] if len(argv) > 1 else ""
    files, authors = changed(base)
    result = flags(files, authors, os.environ.get("CIRCLE_BRANCH", ""))
    for name, value in result.items():
        print(f"{name}={str(value).lower()}")
    if "--print" in argv:
        return 0
    here = os.path.dirname(os.path.abspath(__file__))
    body = {"continuation-key": os.environ.get("CIRCLE_CONTINUATION_KEY", ""),
            "configuration": open(os.path.join(here, "main.yml")).read(), "parameters": result}
    with open("/tmp/continue.json", "w") as f:
        json.dump(body, f)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
