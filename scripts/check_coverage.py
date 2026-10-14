#!/usr/bin/env python3
"""Line coverage of the phone app's unit tests, per package, against the minimums in app/coverage-minimums.txt.

Reads the JaCoCo XML report that `./gradlew :app:testDebugUnitTest :app:createDebugUnitTestCoverageReport` writes (under
app/build/reports/coverage/ or app/build/outputs/), prints a table, and exits 1 when a package listed in the minimums file
is below its minimum. The minimums only go up: a package that gets better is raised by hand (a note says so), never lowered
without a reason in the commit.

    python3 scripts/check_coverage.py [report.xml]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MINIMUMS = os.path.join(ROOT, "app", "coverage-minimums.txt")


def find_report():
    for pattern in ("app/build/reports/coverage/**/*.xml", "app/build/outputs/**/*.xml", "app/build/reports/jacoco/**/*.xml"):
        for path in glob.glob(os.path.join(ROOT, pattern), recursive=True):
            try:
                if ET.parse(path).getroot().tag == "report":
                    return path
            except ET.ParseError:
                continue
    return None


def line_counts(report_path):
    """{package with dots: (covered lines, missed lines)}"""
    out = {}
    for pkg in ET.parse(report_path).getroot().iter("package"):
        name = pkg.get("name", "").replace("/", ".")
        covered = missed = 0
        for counter in pkg.findall("counter"):
            if counter.get("type") == "LINE":
                covered += int(counter.get("covered"))
                missed += int(counter.get("missed"))
        out[name] = (covered, missed)
    return out


def load_minimums():
    mins = {}
    if os.path.exists(MINIMUMS):
        for line in open(MINIMUMS):
            line = line.split("#")[0].strip()
            if line:
                pkg, pct = line.split()
                mins[pkg] = float(pct)
    return mins


def pct(covered, missed):
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_report()
    if not path or not os.path.exists(path):
        print("no JaCoCo XML report found (run :app:createDebugUnitTestCoverageReport first)")
        return 1
    counts = line_counts(path)
    mins = load_minimums()
    total_c = sum(c for c, _ in counts.values())
    total_m = sum(m for _, m in counts.values())
    print(f"{'package':60} {'lines':>7} {'covered':>8} {'min':>5}")
    failed = 0
    for name in sorted(counts):
        c, m = counts[name]
        minimum = mins.get(name)
        flag = ""
        if minimum is not None and pct(c, m) + 1e-9 < minimum:
            flag = "  <-- BELOW MINIMUM"
            failed += 1
        print(f"{name:60} {c + m:7d} {pct(c, m):7.1f}% {'' if minimum is None else f'{minimum:4.0f}%'}{flag}")
    print(f"{'TOTAL':60} {total_c + total_m:7d} {pct(total_c, total_m):7.1f}%")
    for name in mins:
        if name not in counts:
            print(f"minimum given for {name}, but the report has no such package")
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
