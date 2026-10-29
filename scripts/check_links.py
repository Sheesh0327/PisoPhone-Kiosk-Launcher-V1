#!/usr/bin/env python3
"""Lists the links in the repository's documents that point to a file that is not there: markdown links `[text](path)` and paths in
backticks that start with one of the repository's own folders (`docs/ARCHITECTURE.md`, `scripts/x.py:12`).

History is not scanned by default (the changelog, ledger, notes, replans, feature folders and templates name files that were removed
on purpose); `--all` scans it too. Exit status 1 when a dead link is found.

    python3 scripts/check_links.py [--root DIR] [--all]
"""
import argparse
import os
import re
import subprocess
import sys

HISTORY = ("CHANGELOG.md", "ledger/", "specs/notes/", "specs/replans/", "specs/features/", "specs/templates/")
MD_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
TICK_PATH = re.compile(r"`([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-<>*{}$]*)+/?)(?::\d+(?:-\d+)?)?`")
PLACEHOLDER = re.compile(r"NNN|NN_|YYYY|<|>|\*|\{|\$|\.\.\.")
# paths that are not files of this repository: ESP-IDF's own documents, build output, and the bootloader folder PlatformIO makes
OUTSIDE = ("docs/en/", "app/build/", "esp32_firmware/bootloader/")


def tracked(root):
    out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout
    return out.splitlines()


def find_dead(root, everything=False):
    """[(file, line number, target)] for every link whose target does not exist."""
    files = tracked(root)
    folders = {f.split("/")[0] for f in files if "/" in f}
    dead = []
    for rel in files:
        if not rel.endswith(".md") or (not everything and rel.startswith(HISTORY)):
            continue
        here = os.path.dirname(os.path.join(root, rel))
        with open(os.path.join(root, rel), encoding="utf-8") as f:
            for number, line in enumerate(f, 1):
                found = [(m.group(1).split("#")[0], True) for m in MD_LINK.finditer(line)]
                found += [(m.group(1), False) for m in TICK_PATH.finditer(line) if m.group(1).split("/")[0] in folders]
                for target, is_link in found:
                    if not target or target.startswith(("http://", "https://", "mailto:", "/")) or target.startswith(OUTSIDE) or PLACEHOLDER.search(target):
                        continue
                    # a link is relative to its file; a path in backticks is usually written from the repository's root
                    places = [here] if is_link else [root, here]
                    if not any(os.path.exists(os.path.join(place, target)) for place in places):
                        dead.append((rel, number, target))
    return dead


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    p.add_argument("--all", action="store_true", help="scan the history too")
    args = p.parse_args()
    dead = find_dead(os.path.abspath(args.root), args.all)
    for rel, number, target in dead:
        print(f"{rel}:{number}: {target}")
    print(f"{len(dead)} dead link(s)")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main())
