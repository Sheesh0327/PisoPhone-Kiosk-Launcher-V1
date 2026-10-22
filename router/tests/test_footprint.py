"""How much room PisoPhone takes on a router: the budget a small (16 MB flash) router needs. Fails when a file grows past it."""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
# 2026-10-22 (later): raised again to 1200/1700 KB by the owner. With the wheel's page and graphics (router 1.5.15) the programs reached about 1090 KB
# (mipsel) and the installer 1617 KB; the extra room is for the next wheel changes (the thin jackpot, feature 009).
# 2026-10-22: raised from 1000/1500 KB to 1100/1600 KB by the owner. The spinning wheel (router 1.5.14, live) made the programs 962 -> 1026 KB and the
# installer 1443 -> 1529 KB; the program is already built with opt-level z, LTO and strip. The room is for the wheel's graphics (feature 007).
BUDGET_KB = {
    "tools/pisoportal/bin/pisoportal-mipsel": 1200,   # the portal program (what runs)
    "tools/pisoportal/bin/pisoportal-mips": 1200,
    "tools/pisoportal/bin/pisoportal-aarch64": 1200,
    "tools/pisoportal/bin/pisoportal-armv7": 1200,
    "tools/pisoportal/bin/pisoportal-x86_64": 1200,
    "setup/piso-setup.sh": 1700,                      # the one-file installer / update (it is downloaded, not kept)
    "setup/piso-setup.sh.in": 150,                    # the installed command (the file without its payload)
    "tools/pisoportal/src/game.html": 10,             # the mini-game page (1.3.6: Coin Catch, one file, no libraries)
    "tools/pisoportal/src/page.html": 28,
    "tools/pisoportal/src/wheel.html": 30,            # the wheel page (1.5.15: one labelled slice per slot, the odds explained, no library)             # the customer page (1.3.4: voucher choice and code view, speech with a fallback, heartbeat)
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
