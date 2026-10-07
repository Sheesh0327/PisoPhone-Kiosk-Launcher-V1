#!/usr/bin/env python3
"""Fails if a vendored file changed without its record in website/js/VENDORED.md being updated."""
import hashlib, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RECORD = os.path.join(ROOT, "website", "js", "VENDORED.md")
FILES = ["website/js/yume-chan-bundle.js", "website/js/qrcode.js"]

text = open(RECORD, encoding="utf-8").read()
bad = 0
for rel in FILES:
    actual = hashlib.sha256(open(os.path.join(ROOT, rel), "rb").read()).hexdigest()
    if actual not in text:
        print(f"{rel}: hash {actual} is not recorded in website/js/VENDORED.md")
        bad += 1
print("vendored files match their record" if not bad else "update website/js/VENDORED.md")
sys.exit(1 if bad else 0)
