"""Checks the owner's manual page: every link inside it works, it loads nothing from outside, and it has print rules."""
import os
import re
import sys
from html.parser import HTMLParser

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
text = open(os.path.join(ROOT, "website", "manual.html"), encoding="utf-8").read()


class P(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids, self.hrefs, self.ext = [], [], []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            self.ids.append(a["id"])
        if tag == "a" and a.get("href", "").startswith("#"):
            self.hrefs.append(a["href"][1:])
        if tag in ("script", "link", "img", "iframe") and (a.get("src") or a.get("href") or "").startswith(("http", "//")):
            self.ext.append(tag)


p = P()
p.feed(text)
fails = []
for h in p.hrefs:
    if h not in p.ids:
        fails.append(f"link #{h} has no target")
if len(p.ids) != len(set(p.ids)):
    fails.append("duplicate ids")
if p.ext:
    fails.append(f"loads external resources: {p.ext}")
if "@media print" not in text or "@page" not in text:
    fails.append("no print rules")
if "<title>" not in text:
    fails.append("no title")
for must in ("10.0.0.10", "Scan admin QR", "piso-setup status", "make_admin_qr.py"):
    if must not in text:
        fails.append(f"missing {must}")
if len(re.findall(r"<h2 id=", text)) < 15:
    fails.append("too few chapters")
for f in fails:
    print("FAIL", f)
print("manual:", "OK" if not fails else f"{len(fails)} problem(s)")
sys.exit(1 if fails else 0)
