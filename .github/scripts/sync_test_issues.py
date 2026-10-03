#!/usr/bin/env python3
"""Turns rows of the Issue log in docs/REAL_WORLD_TESTING.md into GitHub issues.

A row gets an issue when its Status is `open`, it has a Checklist item or an Actual text, and its "GitHub #" cell is empty; the issue
number is then written into that cell. A row whose Status is `fixed` or `wontfix` closes its issue. Needs GITHUB_TOKEN and
GITHUB_REPOSITORY (set by GitHub Actions). Run with --dry-run to see what it would do without calling GitHub.
"""
import json, os, re, sys, urllib.request

FILE = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "REAL_WORLD_TESTING.md")
API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
REPO = os.environ.get("GITHUB_REPOSITORY", "")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
BRANCH = os.environ.get("GITHUB_REF_NAME", "")
DRY = "--dry-run" in sys.argv


def call(method, path, body=None):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"{}")


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def main():
    lines = open(FILE, encoding="utf-8").read().split("\n")
    start = next((i for i, l in enumerate(lines) if l.startswith("## Issue log")), None)
    if start is None:
        sys.exit("no '## Issue log' section")
    hdr = next((i for i in range(start, len(lines)) if lines[i].startswith("|")), None)
    if hdr is None:
        sys.exit("no table in the Issue log")
    head = cells(lines[hdr])
    if "GitHub #" not in head:
        sys.exit("the Issue log table needs a 'GitHub #' column")
    col = {name: i for i, name in enumerate(head)}
    changed = False
    i = hdr + 2
    while i < len(lines) and lines[i].startswith("|"):
        c = cells(lines[i])
        c += [""] * (len(head) - len(c))
        get = lambda n: c[col[n]] if n in col else ""
        status = get("Status").lower()
        num = re.sub(r"\D", "", get("GitHub #"))
        item, actual = get("Checklist item"), get("Actual")
        if status == "open" and not num and (item or actual):
            title = f"[Real-world test] {item or '?'}: {(actual or get('What I did'))[:70]}".strip()
            body = "\n".join(f"**{k}:** {get(k)}" for k in head if k not in ("GitHub #", "Status") and get(k))
            body += f"\n\nLogged in `docs/REAL_WORLD_TESTING.md` on branch `{BRANCH or '?'}`."
            sev = get("Severity (blocker / bug / polish)").lower()
            labels = ["real-world-test"] + ([sev] if sev in ("blocker", "bug", "polish") else [])
            if DRY:
                print("would create:", title, labels)
            else:
                n = call("POST", f"/repos/{REPO}/issues", {"title": title, "body": body, "labels": labels})["number"]
                c[col["GitHub #"]] = f"#{n}"
                lines[i] = "| " + " | ".join(c) + " |"
                changed = True
                print("created", n, title)
        elif status in ("fixed", "wontfix") and num:
            if DRY:
                print("would close:", num)
            else:
                state = call("GET", f"/repos/{REPO}/issues/{num}").get("state")
                if state == "open":
                    call("PATCH", f"/repos/{REPO}/issues/{num}", {"state": "closed",
                                                                  "state_reason": "completed" if status == "fixed" else "not_planned"})
                    print("closed", num)
        i += 1
    if changed:
        open(FILE, "w", encoding="utf-8").write("\n".join(lines))


main()
