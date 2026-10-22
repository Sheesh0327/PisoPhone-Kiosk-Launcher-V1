#!/usr/bin/env python3
"""The wheel's layout and landing rules (feature 007), run under node on the pure functions in src/wheel.html (the block between the LAYOUT markers):
one slice per slot, every row appears as often as its weight, equal slices, the wins spread between the losses and the bigger prizes spread among the wins,
the same wheel for the same table every time, a table above the slice cap drawn as proportional slices, and the stop position always inside the middle 80%
of a slice of the result's row (never near an edge, never next to a bigger prize on purpose)."""
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
html = open(os.path.join(HERE, "..", "src", "wheel.html")).read()
m = re.search(r"/\*LAYOUT-START\*/(.*?)/\*LAYOUT-END\*/", html, re.S)
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name, flush=True)


node = shutil.which("node")
if not node:
    print("skipped: node is not installed")
    sys.exit(0)
check(m is not None, "wheel.html has a LAYOUT block with the pure functions")
if m:
    harness = m.group(1) + r"""
var out = {};
function T(spec) { return spec.split(" ").map(function (p) { var a = p.split("x"); return { mult: +a[0], weight: +a[1] }; }); }
function counts(slots, n) { var c = []; for (var i = 0; i < n; i++) c[i] = 0; slots.forEach(function (s) { c[s]++; }); return c; }
var tables = { def: "0x13 2x5 3x1 5x1", two: "2x1 0x1", one: "0x1", half: "0x1 2x1", many: "0x10 2x4 3x3 5x2 10x1", wins: "2x9 0x3", odd: "0x7 2x2 4x1" };
Object.keys(tables).forEach(function (k) {
  var segs = T(tables[k]), slots = layout(segs, 24), n = slots.length, c = counts(slots, segs.length);
  out[k] = { n: n, counts: c, again: JSON.stringify(layout(segs, 24)) === JSON.stringify(slots),
    mults: slots.map(function (s) { return segs[s].mult; }) };
});
// a table above the cap: 100 slots drawn as 24 proportional slices; every row that has slots keeps at least one
var big = T("0x65 2x25 3x6 5x4"), bs = layout(big, 24);
out.big = { n: bs.length, counts: counts(bs, big.length) };
// landing: for every slice and many random numbers the stop is inside the middle 80% of that slice, and always ends clockwise of where it was
var bad = 0, back = 0, edge = 0;
[1, 2, 5, 20, 24].forEach(function (n) {
  var step = 360 / n;
  for (var i = 0; i < n; i++) for (var t = 0; t < 40; t++) {
    var r = (t + 0.5) / 40, turn = t * 37.3, nt = landTurn(turn, i, step, r);
    var under = (((-nt) % 360) + 360) % 360, idx = Math.floor(under / step), within = (under - idx * step) / step;
    if (idx !== i) bad++;
    if (nt < turn + 360 * 4) back++;
    if (within < 0.0999 || within > 0.9001) edge++;
  }
});
out.land = { bad: bad, back: back, edge: edge };
console.log(JSON.stringify(out));
"""
    r = subprocess.run([node, "-e", harness], capture_output=True, text=True, timeout=30)
    check(r.returncode == 0, "the layout block runs: " + r.stderr[:300])
    if r.returncode == 0:
        o = json.loads(r.stdout)
        d = o["def"]
        check(d["n"] == 20 and d["counts"] == [13, 5, 1, 1], f"the default table gives 20 slices, 13 + 5 + 1 + 1: {d['n']} {d['counts']}")
        mu = d["mults"]
        wins = [i for i, x in enumerate(mu) if x > 0]
        gaps = [(wins[(j + 1) % len(wins)] - wins[j]) % 20 for j in range(len(wins))]
        check(len(wins) == 7 and min(gaps) >= 2 and max(gaps) - min(gaps) <= 1, f"the 7 wins are spread between the losses (no two together, gaps differ by at most 1): {gaps}")
        pr = [i for i, x in enumerate(mu) if x >= 3]
        check(len(pr) == 2 and (pr[1] - pr[0]) % 20 >= 3 and (pr[0] - pr[1]) % 20 >= 3, f"the two big prizes are not side by side: {pr}")
        for k, e in o.items():
            if k in ("big", "land"):
                continue
            check(e["again"], f"{k}: the same table always gives the same wheel")
        check(o["two"]["counts"] == [1, 1] and o["one"]["counts"] == [1] and o["half"]["n"] == 2, "tiny tables: 2 slices, 1 slice")
        check(o["many"]["n"] == 20 and o["many"]["counts"] == [10, 4, 3, 2, 1], f"a table with many prizes keeps every weight: {o['many']['counts']}")
        w = [i for i, x in enumerate(o["wins"]["mults"]) if x > 0]
        check(o["wins"]["n"] == 12 and len(w) == 9, "a wheel with more wins than losses still has the right counts (wins cannot all be apart)")
        b = o["big"]
        check(b["n"] == 24 and all(c >= 1 for c in b["counts"]) and b["counts"][0] > b["counts"][1] > b["counts"][2] >= b["counts"][3], f"100 slots are drawn as 24 proportional slices, every row kept: {b['counts']}")
        check(o["land"] == {"bad": 0, "back": 0, "edge": 0}, f"the stop is always inside the middle 80% of a slice of the result's row: {o['land']}")
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
