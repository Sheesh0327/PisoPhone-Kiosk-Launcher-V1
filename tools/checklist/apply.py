#!/usr/bin/env python3
"""Writes the results of the tickable test page into docs/REAL_WORLD_TESTING.md, so the document stays the one record.

    python3 tools/checklist/apply.py RESULTS.json [--dry-run] [--date YYYY-MM-DD] [--doc PATH]

RESULTS.json is the page's shared record (the `run/main` document of the artifact's database): {"items": {ID: {s, sev, note}},
"setup": {...}} where s is "p" (pass), "f" (fail) or "k" (skipped). "-" reads it from standard input. What it writes:
  - a pass ticks the test `[x]`, a fail marks it `[!]`; a skipped or untouched test is left as it is;
  - every failed test gets a row in the Issue log (Status `open`, severity and note from the page), unless it already has an open
    one; pushing the file then makes the "Real-world test issues" workflow open a GitHub issue for it;
  - a test that passes now but has an open row in the Issue log has that row set to `fixed`, which closes its GitHub issue;
  - the Test setup table is filled in, a skipped test with a reason goes under "Notes and ideas", and a row is added to the
    Baseline summary (a second run on the same day replaces that day's row).
It is safe to run again with the same results: nothing changes the second time. A result for a test ID the document does not have is
reported and ignored. Only the standard library is needed."""
import argparse, datetime, difflib, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOC = os.path.join(ROOT, "docs", "REAL_WORLD_TESTING.md")
ITEM = re.compile(r"^- \[([ x!])\] \*\*([A-Z]+\d+[a-z]?)\*\* (.*)$")
SETUP_LABELS = {"date": "Date / tester", "fw": "Firmware version and board", "apk": "APK build number",
                "phone": "Phone model", "router": "Router model", "coin": "Coin acceptor model"}
SEVERITIES = ("blocker", "bug", "polish")


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def row(values):
    return "| " + " | ".join(values) + " |"


def cell_text(text):
    """Text that is safe inside a table cell: no pipes, no line breaks."""
    return re.sub(r"\s+", " ", str(text or "").replace("|", "/")).strip()


def plain(md_text):
    t = re.sub(r"`([^`]+)`", r"\1", md_text)
    return re.sub(r"\*\*([^*]+)\*\*", r"\1", t)


def table_at(lines, heading):
    """(index of the header row, index one past the last row) of the first table after the line starting with `heading`."""
    start = next((i for i, l in enumerate(lines) if l.startswith(heading)), None)
    if start is None:
        return None
    hdr = next((i for i in range(start, len(lines)) if lines[i].startswith("|")), None)
    if hdr is None:
        return None
    end = hdr + 2
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    return hdr, end


def apply(md, results, today):
    """Returns (new document, summary dict). Pure: no file access."""
    lines = md.split("\n")
    items = (results or {}).get("items") or {}
    setup = (results or {}).get("setup") or {}
    summary = {"passed": [], "failed": [], "fixed_rows": [], "new_rows": [], "unknown": [], "skipped_noted": []}

    # 1. the ticks
    known = {}
    for i, l in enumerate(lines):
        m = ITEM.match(l)
        if m:
            known[m.group(2)] = (i, m.group(1), m.group(3))
    for tid in sorted(items):
        if tid not in known:
            summary["unknown"].append(tid)
    for tid, (i, mark, text) in known.items():
        s = (items.get(tid) or {}).get("s")
        want = {"p": "x", "f": "!"}.get(s)
        if want and want != mark:
            lines[i] = "- [%s] **%s** %s" % (want, tid, text)
            (summary["passed"] if want == "x" else summary["failed"]).append(tid)

    # 2. the Issue log
    t = table_at(lines, "## Issue log")
    if t:
        hdr, end = t
        head = cells(lines[hdr])
        col = {name: k for k, name in enumerate(head)}
        need = ("Checklist item", "Severity (blocker / bug / polish)", "Actual", "Status")
        if all(n in col for n in need):
            # each row keeps its original text unless this run changes it, so a run with nothing to record leaves the file as it was
            rows = [{"c": cells(l) + [""] * (len(head) - len(cells(l))), "line": l, "dirty": False} for l in lines[hdr + 2:end]]

            def is_blank(r):
                return not any(r["c"][k] for n, k in col.items() if n not in ("#", "Status", "GitHub #") and k != 0)

            def open_row_of(tid):
                for r in rows:
                    if r["c"][col["Checklist item"]].startswith(tid + " ") and r["c"][col["Status"]].lower() == "open":
                        return r
                return None

            for tid, (i, mark, text) in known.items():
                st = items.get(tid) or {}
                if st.get("s") == "f" and open_row_of(tid) is None:
                    sev = st.get("sev") if st.get("sev") in SEVERITIES else "bug"
                    r = [""] * len(head)
                    r[col["Checklist item"]] = cell_text(tid + " " + plain(text))[:100]
                    r[col["Severity (blocker / bug / polish)"]] = sev
                    r[col["Actual"]] = cell_text(st.get("note")) or "(no note)"
                    r[col["Status"]] = "open"
                    rows.append({"c": r, "line": None, "dirty": True})
                    summary["new_rows"].append(tid)
                elif st.get("s") == "p":
                    for r in rows:
                        if r["c"][col["Checklist item"]].startswith(tid + " ") and r["c"][col["Status"]].lower() == "open":
                            r["c"][col["Status"]] = "fixed"
                            r["dirty"] = True
                            summary["fixed_rows"].append(tid)
            if summary["new_rows"]:
                rows = [r for r in rows if not is_blank(r)] or rows   # the empty example row goes once real rows exist
            # number the rows that have no number, after the highest one in use
            top = max([int(r["c"][0]) for r in rows if r["c"][0].isdigit()] or [0])
            for r in rows:
                if not r["c"][0]:
                    top += 1
                    r["c"][0] = str(top)
            lines[hdr + 2:end] = [row(r["c"]) if r["dirty"] or r["line"] is None else r["line"] for r in rows]

    # 3. the Test setup table
    t = table_at(lines, "## 0. Test setup")
    if t:
        hdr, end = t
        for k in range(hdr + 2, end):
            c = cells(lines[k])
            c += [""] * (2 - len(c))
            for key, label in SETUP_LABELS.items():
                if c[0].startswith(label) and cell_text(setup.get(key)):
                    lines[k] = row([c[0], cell_text(setup.get(key))])

    # 4. skipped tests that have a reason
    notes_at = next((i for i, l in enumerate(lines) if l.startswith("## Notes and ideas")), None)
    if notes_at is not None:
        stop = next((i for i in range(notes_at + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
        for tid in sorted(items):
            st = items[tid]
            if tid in known and st.get("s") == "k" and cell_text(st.get("note")):
                text = "- %s skipped (%s): %s" % (tid, today, cell_text(st.get("note")))
                if text not in lines[notes_at + 1:stop]:
                    blank = [i for i in range(notes_at + 1, stop) if lines[i].strip() == "-"]
                    if blank:
                        lines[blank[0]] = text
                    else:
                        last = max([i for i in range(notes_at + 1, stop) if lines[i].startswith("- ")] or [notes_at])
                        lines.insert(last + 1, text)
                        stop += 1
                    summary["skipped_noted"].append(tid)

    # 5. the Baseline summary: counts of the whole document as it is now
    marks = [m.group(1) for m in map(ITEM.match, lines) if m]
    passed, failed, todo = marks.count("x"), marks.count("!"), marks.count(" ")
    blockers = 0
    t = table_at(lines, "## Issue log")
    if t:
        hdr, end = t
        head = cells(lines[hdr])
        if "Status" in head and "Severity (blocker / bug / polish)" in head:
            sc, vc = head.index("Status"), head.index("Severity (blocker / bug / polish)")
            for l in lines[hdr + 2:end]:
                c = cells(l) + [""] * len(head)
                blockers += 1 if c[sc].lower() == "open" and c[vc].lower() == "blocker" else 0
    if blockers:
        verdict = "not ready: %d blocker%s open" % (blockers, "" if blockers == 1 else "s")
    elif failed:
        verdict = "not ready: %d failing test%s" % (failed, "" if failed == 1 else "s")
    elif todo:
        verdict = "no failures so far; %d not tested" % todo
    else:
        verdict = "all tests passed"
    t = table_at(lines, "## Baseline summary")
    if t and any((v or {}).get("s") for v in items.values()):   # only when this run recorded a result
        hdr, end = t
        new = row([today, str(passed), str(failed), str(todo), str(blockers), verdict])
        body = [l for l in lines[hdr + 2:end] if any(cells(l))]            # drop the empty placeholder row
        body = [l for l in body if not l.startswith("| " + today + " |")]   # a second run on the same day replaces that day's row
        lines[hdr + 2:end] = body + [new]
    summary["counts"] = {"passed": passed, "failed": failed, "not_tested": todo, "blockers": blockers, "verdict": verdict}
    return "\n".join(lines), summary


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("results", help="the page's shared record as JSON ('-' for standard input)")
    ap.add_argument("--doc", default=DOC)
    ap.add_argument("--date", default=datetime.date.today().isoformat())
    ap.add_argument("--dry-run", action="store_true", help="show what would change and write nothing")
    a = ap.parse_args(argv)
    raw = sys.stdin.read() if a.results == "-" else open(a.results, encoding="utf-8").read()
    data = json.loads(raw)
    if isinstance(data, dict) and "items" not in data and isinstance(data.get("data"), dict):
        data = data["data"]                                  # the database tool wraps a document in "data"
    old = open(a.doc, encoding="utf-8").read()
    new, s = apply(old, data, a.date)
    for label, key in (("ticked", "passed"), ("marked failed", "failed"), ("issue rows added", "new_rows"),
                       ("issue rows set to fixed", "fixed_rows"), ("skip notes added", "skipped_noted")):
        if s[key]:
            print("%s: %s" % (label, ", ".join(s[key])))
    if s["unknown"]:
        print("WARNING: results for tests the document does not have (ignored): %s" % ", ".join(s["unknown"]))
    c = s["counts"]
    print("document now: %d passed, %d failed, %d not tested, %d open blockers (%s)" % (c["passed"], c["failed"], c["not_tested"], c["blockers"], c["verdict"]))
    if new == old:
        print("nothing to change")
        return 0
    if a.dry_run:
        sys.stdout.writelines(difflib.unified_diff(old.splitlines(True), new.splitlines(True), "before", "after", n=0))
        return 0
    open(a.doc, "w", encoding="utf-8").write(new)
    print("wrote %s" % a.doc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
