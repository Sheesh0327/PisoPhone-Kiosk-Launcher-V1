"""How much room PisoPhone takes on a router: the budget a small (16 MB flash) router needs. Fails when a file grows past it."""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
BUDGET_KB = {
    "tools/pisoportal/bin/pisoportal-mipsel": 1000,   # the portal program (what runs)
    "tools/pisoportal/bin/pisoportal-mips": 1000,
    "tools/pisoportal/bin/pisoportal-aarch64": 1000,
    "tools/pisoportal/bin/pisoportal-armv7": 1000,
    "tools/pisoportal/bin/pisoportal-x86_64": 1000,
    "setup/piso-setup.sh": 1500,                      # the one-file installer / update (it is downloaded, not kept)
    "setup/piso-setup.sh.in": 150,                    # the installed command (the file without its payload)
    "tools/pisoportal/src/game.html": 10,             # the mini-game page (1.3.6: Coin Catch, one file, no libraries)
    "tools/pisoportal/src/page.html": 28,             # the customer page (1.3.4: voucher choice and code view, speech with a fallback, heartbeat)
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
