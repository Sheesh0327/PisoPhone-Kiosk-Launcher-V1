"""How much room PisoPhone takes on a router: the budget a small (16 MB flash) router needs. Fails when a file grows past it."""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
# 2026-10-22: raised to 1200/1700 KB by the owner for a feature that feature 033 removed again (the programs had reached about 1090 KB, mipsel,
# and the installer 1617 KB). The programs are smaller now, but the budgets stay until CI has rebuilt the committed programs; lower them then.
BUDGET_KB = {
    "tools/pisoportal/bin/pisoportal-mipsel": 1200,   # the portal program (what runs)
    "tools/pisoportal/bin/pisoportal-mips": 1200,
    "tools/pisoportal/bin/pisoportal-aarch64": 1200,
    "tools/pisoportal/bin/pisoportal-armv7": 1200,
    "tools/pisoportal/bin/pisoportal-x86_64": 1200,
    "setup/piso-setup.sh": 1800,                      # the one-file installer / update (it is downloaded, not kept). 1700 until features 057 and 058: the portal program grew about 65 KB (the smooth-line settings, speed test and card) and the installed command about 25 KB, which made it 1759 KB; raised by the owner 2026-10-31 (routers have about 100 MB of free flash, and the installer is not kept on flash)
    "setup/piso-setup.sh.in": 175,                    # the installed command (the file without its payload; 148 KB before features 057 and 058: 155 KB with the package-manager layer, 162 KB with the optional-package step; the rest of 056 is expected to need up to 175)
    "tools/pisoportal/src/page.html": 28,             # the customer page (1.3.4: voucher choice and code view, speech with a fallback, heartbeat)
    "tools/pisoportal/src/brand_fil.js": 14,          # a branded page: the Filipino wording and the language button (feature 049; page.html itself did not grow)
    "tools/pisoportal/src/brand_page.css": 6,         # a branded page: the four layouts
    "tools/pisoportal/src/console.html": 30,          # the owner's console page: sidebar, icons, phone rows (feature 053; 21 KB before it)
    "tools/pisoportal/src/console-ui.js": 33,         # the console's script, version 13 (27 KB before feature 053; the Smooth line card of feature 058 added 3.6 KB)
}
bad = 0
for path, limit in BUDGET_KB.items():
    if not os.path.exists(os.path.join(ROOT, path)):
        continue   # (a family CI has not built yet)
    kb = (os.path.getsize(os.path.join(ROOT, path)) + 1023) // 1024
    ok = kb <= limit
    bad += not ok
    print(("ok   " if ok else "FAIL ") + f"{path}: {kb} KB (budget {limit} KB)")
print(f"{len(BUDGET_KB)} budgets, {bad} failures")
sys.exit(1 if bad else 0)
