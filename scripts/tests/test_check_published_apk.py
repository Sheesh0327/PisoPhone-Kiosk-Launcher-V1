"""scripts/check_published_apk.py catches a published APK that does not match app.json. Run: python3 scripts/tests/test_check_published_apk.py"""
import json
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import check_published_apk as c  # noqa: E402

checks = failures = 0


def check(ok, msg):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", msg)


src = os.path.join(ROOT, "website", "update")
check(c.check(src) == [], "the repository's published APK is consistent: " + str(c.check(src)))

tmp = tempfile.mkdtemp()
check(c.check(tmp) == [], "nothing published yet is fine")
shutil.copy(os.path.join(src, "app-release.apk"), tmp)
check(len(c.check(tmp)) == 1 and "together" in c.check(tmp)[0], "an APK without app.json is refused")
meta = json.load(open(os.path.join(src, "app.json")))
for field, bad in (("signatureChecksum", "A" * 43), ("sha256", "0" * 64), ("size", 1)):
    broken = dict(meta, **{field: bad})
    json.dump(broken, open(os.path.join(tmp, "app.json"), "w"))
    problems = c.check(tmp)
    key = {"signatureChecksum": "signing certificate", "sha256": "sha256", "size": "bytes"}[field]
    check(any(key in p for p in problems), f"a wrong {field} is caught: {problems}")
json.dump({k: v for k, v in meta.items() if k != "signatureChecksum"}, open(os.path.join(tmp, "app.json"), "w"))
check(any("no signatureChecksum" in p for p in c.check(tmp)), "no signatureChecksum is reported (QR setup would be unavailable)")
open(os.path.join(tmp, "app-release.apk"), "wb").write(b"not an apk")
json.dump(meta, open(os.path.join(tmp, "app.json"), "w"))
check(len(c.check(tmp)) >= 1, "garbage instead of an APK is reported, not a crash")
print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
