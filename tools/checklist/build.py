#!/usr/bin/env python3
"""Builds the tickable test-log page from docs/REAL_WORLD_TESTING.md, so there is only one list to maintain.

    python3 tools/checklist/build.py OUT.html

The checklist items (`- [ ] **ID** text`) become rows with Pass / Fail / Skip buttons; the page is published as a private
Claude artifact (it keeps the results in the artifact's own database, so they survive reloads and can be read back later).
Re-run it and republish after the checklist changes; results are kept by test ID."""
import html, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs", "REAL_WORLD_TESTING.md")
TEMPLATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "template.html")

PASS1 = {"B1", "B2", "B3", "P1", "P2", "P3", "P4", "C1", "C2", "C3", "C4", "F1", "F2", "F3",
         *(f"RS{i}" for i in (1, 2, 3, 4, 5, 6, 10, 11, 12, 17))}
PASS2_EXTRA = {"S1", "S3", "RS7", "RS8", "RS9"}


def pass_of(item_id):
    if item_id in PASS1:
        return 1
    if item_id in PASS2_EXTRA or re.fullmatch(r"[BPCF]\d+", item_id):
        return 2
    return 3


def inline(text):
    t = html.escape(text, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    return t


def parse(md):
    sections, cur = [], None
    for line in md.splitlines():
        m = re.match(r"^## (\d+)\. (.+?)(?: \(.*\))?$", line)
        if m and int(m.group(1)) >= 1:
            cur = {"id": m.group(1), "title": m.group(2), "hint": "", "items": []}
            sections.append(cur)
            continue
        if line.startswith("## "):
            cur = None
            continue
        if cur is None:
            continue
        m = re.match(r"^\*([^*].*)\*$", line)
        if m and not cur["items"]:
            cur["hint"] = m.group(1)
            continue
        m = re.match(r"^- \[[ x!]\] \*\*([A-Z]+\d+)\*\* (.*)$", line)
        if m:
            cur["items"].append({"id": m.group(1), "pass": pass_of(m.group(1)), "html": inline(m.group(2))})
    return [s for s in sections if s["items"]]


def build():
    sections = parse(open(SRC, encoding="utf-8").read())
    ids = [i["id"] for s in sections for i in s["items"]]
    assert len(ids) == len(set(ids)), "a test ID is used twice"
    data = json.dumps({"sections": sections}, ensure_ascii=False).replace("</", "<\\/")
    return open(TEMPLATE, encoding="utf-8").read().replace("/*DATA*/", data)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = build()
    open(sys.argv[1], "w", encoding="utf-8").write(out)
    n = out.count('"pass":')
    print(f"wrote {sys.argv[1]}: {n} tests")
