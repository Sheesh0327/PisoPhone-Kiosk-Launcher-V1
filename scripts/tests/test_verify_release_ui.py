#!/usr/bin/env python3
"""scripts/verify_release.py checks the owner console's signed script (website/console/ui.json): it must have more than 30 days
left, match ui.js and carry a signature that verifies. Throwaway key, temporary files. Needs `pip install cryptography`.
Run: python3 scripts/tests/test_verify_release_ui.py"""
import json, os, shutil, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import verify_release as v  # noqa: E402
import sign_console_ui as S  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


key = ec.generate_private_key(ec.SECP256R1())
other = ec.generate_private_key(ec.SECP256R1())
data = open(os.path.join(ROOT, "tools", "pisoportal", "src", "console-ui.js"), "rb").read()
now = 1_800_000_000
tmp = tempfile.mkdtemp()


def publish(days, signer=key, payload=data):
    with open(os.path.join(tmp, "ui.js"), "wb") as f:
        f.write(payload)
    with open(os.path.join(tmp, "ui.json"), "w") as f:
        json.dump(S.make_manifest(signer, data, now=now, expires_days=days), f)
    return v.check_console_ui(os.path.join(tmp, "ui.json"), key.public_key(), now=now)


level, text = publish(120)
check(level == "PASS", f"120 days left passes: {level} {text}")
level, text = publish(31)
check(level == "PASS", f"31 days left passes: {level} {text}")
level, text = publish(29)
check(level == "FAIL" and "30" in text, f"29 days left fails: {level} {text}")
level, text = publish(-2)
check(level == "FAIL" and "expired" in text, f"an expired signature fails: {level} {text}")
level, text = publish(120, signer=other)
check(level == "FAIL" and "signature" in text, f"a signature by another key fails: {level} {text}")
level, text = publish(120, payload=data + b" ")
check(level == "FAIL" and "ui.js" in text, f"a script that is not the signed one fails: {level} {text}")
os.remove(os.path.join(tmp, "ui.json"))
level, text = v.check_console_ui(os.path.join(tmp, "ui.json"), key.public_key(), now=now)
check(level == "WARN", f"nothing published is a warning: {level}")

# the repository's own file is good today
level, text = v.check_console_ui(os.path.join(ROOT, "website", "console", "ui.json"), None)
check(level != "FAIL", f"the repository's ui.json passes: {text}")

shutil.rmtree(tmp, ignore_errors=True)
print("test_verify_release_ui: %d checks, %d failures" % (checks, failures))
sys.exit(1 if failures else 0)
