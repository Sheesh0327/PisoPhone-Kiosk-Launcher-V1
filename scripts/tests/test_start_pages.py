"""Checks the 'Start here' pages: every link works, nothing is loaded from outside, each page has at most 6 numbered steps,
and no page asks the owner to read or type a key (the one allowed exception is the print server's key file, until pairing ships)."""
import os
import re
import sys
from html.parser import HTMLParser

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
START = os.path.join(ROOT, "website", "start")
PAGES = ["index.html", "shop.html", "phone.html", "print.html"]
LINKS = {"index.html": {"shop.html", "phone.html", "print.html"}}  # the front page leads to the three jobs


class P(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids, self.hrefs, self.ext, self.steps, self.in_steps, self.depth = [], [], [], 0, False, 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            self.ids.append(a["id"])
        if tag == "a" and a.get("href"):
            self.hrefs.append(a["href"])
        for k in ("src", "href"):
            if tag in ("script", "link", "img", "iframe") and (a.get(k) or "").startswith(("http", "//")):
                self.ext.append(tag)
        if tag == "ol" and "steps" in (a.get("class") or ""):
            self.in_steps, self.depth = True, 0
        elif self.in_steps and tag == "ol":
            self.depth += 1
        elif self.in_steps and tag == "li" and self.depth == 0:
            self.steps += 1

    def handle_endtag(self, tag):
        if self.in_steps and tag == "ol":
            if self.depth == 0:
                self.in_steps = False
            else:
                self.depth -= 1


fails = []
for name in PAGES:
    path = os.path.join(START, name)
    if not os.path.exists(path):
        fails.append(f"{name} is missing")
        continue
    text = open(path, encoding="utf-8").read()
    p = P()
    p.feed(text)
    if "<title>" not in text:
        fails.append(f"{name}: no title")
    if p.ext:
        fails.append(f"{name}: loads external resources {p.ext}")
    if p.steps > 6:
        fails.append(f"{name}: {p.steps} steps (the limit is 6)")
    if name != "index.html" and p.steps == 0:
        fails.append(f"{name}: no numbered steps")
    for h in p.hrefs:
        if h.startswith(("http", "mailto:", "#")):
            continue
        target = os.path.normpath(os.path.join(START, h.split("#")[0]))
        if not os.path.exists(target):
            fails.append(f"{name}: link {h} goes nowhere")
    missing = LINKS.get(name, set()) - set(p.hrefs)
    if missing:
        fails.append(f"{name}: no link to {sorted(missing)}")
    for bad in ("make_owner_keys", "sign_router", "owner key", "private key", "--github-secret"):
        if bad in text:
            fails.append(f"{name}: mentions '{bad}' (maintainer material, not for owners)")
    if name != "print.html" and re.search(r"\.key\b|\bkey file", text):
        fails.append(f"{name}: asks the owner to deal with a key file")

# the README and the phone setup page both lead to the Start page
for rel in ("README.md", os.path.join("website", "index.html")):
    if "start/" not in open(os.path.join(ROOT, rel), encoding="utf-8").read():
        fails.append(f"{rel} does not link to the Start page")

if fails:
    print("\n".join("FAIL  " + f for f in fails))
    sys.exit(1)
print(f"start pages: {len(PAGES)} pages OK")
