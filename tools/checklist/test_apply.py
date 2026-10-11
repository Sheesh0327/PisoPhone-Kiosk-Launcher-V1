#!/usr/bin/env python3
"""Tests tools/checklist/apply.py against the real docs/REAL_WORLD_TESTING.md: ticks, failures that become Issue log rows (and
then really would become GitHub issues, checked with the repo's own sync script), retests that close them, setup, skip notes,
the baseline row, and that running it twice changes nothing.   Run: python3 tools/checklist/test_apply.py"""
import contextlib, importlib.util, io, json, os, re, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
spec = importlib.util.spec_from_file_location("apply_results", os.path.join(HERE, "apply.py"))
ap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ap)
SRC = open(os.path.join(ROOT, "docs", "REAL_WORLD_TESTING.md"), encoding="utf-8").read()
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


def mark_of(md, tid):
    m = re.search(r"^- \[([ x!])\] \*\*%s\*\* " % re.escape(tid), md, flags=re.M)
    return m.group(1) if m else None


def issue_rows(md):
    lines = md.split("\n")
    start = next(i for i, l in enumerate(lines) if l.startswith("## Issue log"))
    hdr = next(i for i in range(start, len(lines)) if lines[i].startswith("|"))
    out, i = [], hdr + 2
    while i < len(lines) and lines[i].startswith("|"):
        out.append(ap.cells(lines[i]))
        i += 1
    return out


def sync_would_create(md):
    """What the repo's issue-sync workflow would open for this document (its own --dry-run)."""
    spec2 = importlib.util.spec_from_file_location("sync_test_issues", os.path.join(ROOT, ".github", "scripts", "sync_test_issues.py"))
    sync = importlib.util.module_from_spec(spec2)
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
        f.write(md)
    old_argv = sys.argv
    sys.argv = ["sync", "--dry-run"]
    try:
        spec2.loader.exec_module(sync)
        sync.FILE = f.name
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            sync.main()
    finally:
        sys.argv = old_argv
        os.unlink(f.name)
    return [l for l in buf.getvalue().splitlines() if l.startswith("would create")]


TODAY = "2026-10-12"
base_marks = [m for m in re.findall(r"^- \[([ x!])\] \*\*", SRC, flags=re.M)]
check(all(m == " " for m in base_marks), "the document starts with nothing ticked (a fresh baseline), or this test needs updating")

# --- a first session: passes, a failure with a note, a skip with a reason, and the setup
R1 = {
    "setup": {"date": "2026-10-12 / Sam", "fw": "3.3.4 esp32-c3-dev", "apk": "413", "phone": "Pixel 6 / Android 14"},
    "items": {
        "B1": {"s": "p"}, "C1": {"s": "p"}, "C3b": {"s": "p"}, "H4a": {"s": "p"},
        "M3a": {"s": "f", "sev": "blocker", "note": "banner never appeared | after 25 h\nqueue was empty"},
        "H2": {"s": "f", "note": ""},
        "H4c": {"s": "k", "note": "no way to cut power today"},
        "B2": {"s": None},
        "ZZ99": {"s": "p"},
    },
}
md1, s1 = ap.apply(SRC, R1, TODAY)
check([mark_of(md1, t) for t in ("B1", "C1", "C3b", "H4a")] == ["x"] * 4, "passes are ticked, including IDs with a letter suffix")
check(mark_of(md1, "M3a") == "!" and mark_of(md1, "H2") == "!", "failures are marked [!]")
check(mark_of(md1, "H4c") == " " and mark_of(md1, "B2") == " " and mark_of(md1, "B3") == " ", "skipped and untouched tests stay [ ]")
check(s1["unknown"] == ["ZZ99"], "a result for a test the document does not have is reported, not written")
check("ZZ99" not in md1, "...and nothing about it is written")

rows = issue_rows(md1)
real = [r for r in rows if r[1]]
check(len(real) == 2 and all(r[8] == "open" for r in real), "each failure got an open Issue log row")
check(not any(r[0] == "1" and not r[1] for r in rows), "the empty placeholder row is gone once real rows exist")
m3 = next(r for r in real if r[1].startswith("M3a "))
check(m3[2] == "blocker" and "banner never appeared / after 25 h queue was empty" in m3[5], "severity and the note land in the row, pipes and line breaks made safe")
h2 = next(r for r in real if r[1].startswith("H2 "))
check(h2[2] == "bug" and h2[5] == "(no note)", "a failure with no severity or note gets bug / '(no note)'")
check([r[0] for r in real] == ["1", "2"], "rows are numbered")
for r in real:
    check(len(r) == 10, "a row has all 10 columns: %s" % r[1][:12])

created = sync_would_create(md1)
check(len(created) == 2 and any("M3a" in c and "blocker" in c for c in created), "the repo's own sync script would open a GitHub issue for each failure: %s" % created)

check("H4c skipped (2026-10-12): no way to cut power today" in md1, "a skip with a reason is noted")
check("| 2026-10-12 / Sam |" in md1 and "| 3.3.4 esp32-c3-dev |" in md1 and "| 413 |" in md1, "the setup table is filled in")
check("Coin acceptor model" in md1 and re.search(r"\| Coin acceptor model[^|]*\| *\|", md1), "setup fields not given stay empty")
base = [l for l in md1.split("\n") if l.startswith("| 2026-10-12 |")]
check(len(base) == 1 and "not ready: 1 blocker open" in base[0], "the baseline row says not ready with the blocker: %s" % base)
check(s1["counts"]["blockers"] == 1 and s1["counts"]["failed"] == 2 and s1["counts"]["passed"] == 4, "counts are of the whole document")

# --- nothing changes the second time
md1b, _ = ap.apply(md1, R1, TODAY)
check(md1b == md1, "running it again with the same results changes nothing")

# --- the retest: the failures now pass, the issue rows are closed, and the baseline row is replaced, not repeated
R2 = {"items": {"M3a": {"s": "p"}, "H2": {"s": "p"}, "H4c": {"s": "p"}}}
md2, s2 = ap.apply(md1, R2, TODAY)
check(mark_of(md2, "M3a") == "x" and mark_of(md2, "H2") == "x" and mark_of(md2, "H4c") == "x", "a retest that passes flips [!] and [ ] to [x]")
rows2 = [r for r in issue_rows(md2) if r[1]]
check(len(rows2) == 2 and all(r[8] == "fixed" for r in rows2), "the issue rows are set to fixed (that closes the GitHub issues)")
check(sync_would_create(md2) == [], "...and nothing new would be opened")
base2 = [l for l in md2.split("\n") if l.startswith("| 2026-10-12 |")]
check(len(base2) == 1 and "no failures so far" in base2[0], "the same day's baseline row is replaced")
md2b, _ = ap.apply(md2, R2, TODAY)
check(md2b == md2, "idempotent after a retest too")

# --- a failure that is already open is not logged twice; a new day adds a baseline row
R3 = {"items": {"M3a": {"s": "f", "note": "again"}}}
md3, s3 = ap.apply(md1, R3, TODAY)
check(len([r for r in issue_rows(md3) if r[1].startswith("M3a ")]) == 1, "a test that already has an open row does not get a second one")
md4, _ = ap.apply(md1, R2, "2026-10-13")
check(len([l for l in md4.split("\n") if re.match(r"\| 2026-10-1[23] \|", l)]) == 2, "a new day adds a new baseline row")

# --- nothing to do
mdn, sn = ap.apply(SRC, {"items": {}, "setup": {}}, TODAY)
check(mdn == SRC, "empty results change nothing")
mdn2, _ = ap.apply(SRC, {"items": {"B1": {"s": None}}}, TODAY)
check(mdn2 == SRC, "a test that was un-ticked on the page leaves no trace")

# --- the command line, including the wrapped form the database tool returns, and --dry-run
with tempfile.TemporaryDirectory() as d:
    doc = os.path.join(d, "doc.md")
    res = os.path.join(d, "res.json")
    open(doc, "w", encoding="utf-8").write(SRC)
    json.dump({"data": R1}, open(res, "w"))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        ap.main([res, "--doc", doc, "--date", TODAY, "--dry-run"])
    check(open(doc, encoding="utf-8").read() == SRC and "+| " in out.getvalue(), "--dry-run shows a diff and writes nothing")
    with contextlib.redirect_stdout(io.StringIO()):
        ap.main([res, "--doc", doc, "--date", TODAY])
    check(open(doc, encoding="utf-8").read() == md1, "the command line writes the same document (also from the wrapped form)")

print("test_apply: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
