#!/usr/bin/env python3
"""Soak test (spec 02, AO-10, feature 014): the real portal through hundreds of coin windows, kills and power cuts, with a report.

What it does: runs `pisoportal` (the release build for this machine) against the fake coin box and the fake ndsctl, one coin window
after another, among a few dozen devices; kills the portal with kill -9 (some in the middle of a window) and simulates power cuts
(kill -9, then a half-written last row on the files that are appended to); starts it again each time. Every `--period` windows it
samples the portal's memory (RSS), threads and open files and the size of every file under DATA_DIR.

What it does NOT do: the program has no fake clock, so time is real: a window takes about 2 s with the shortened COIN_* waits, and
"one simulated day" is only a way to scale the numbers (300 windows). Things that need wall-clock time (hourly timers, day
rollovers) are not exercised. Change nothing in the program for this; run `--windows 9000` for the 30-day equivalent (about 5 h).

Pass: every coin put in is credited exactly once (ledger total, no gaps, `verify` is clean, paid time per device), and in the last
half of the run (no kills there, one process) memory, threads and open files stay within 10% of the middle of that stretch. File
growth is reported, never judged (the expected result for AO-02 to AO-04).

  python3 test_soak.py [--windows N] [--kills N] [--cuts N] [--quick] [--period N] [--seed N] [--devices N] [--strict] [--json PATH]"""
import argparse
import json
import os
import random
import statistics
import time

from harness import *

ap = argparse.ArgumentParser()
ap.add_argument("--windows", type=int, help="coin windows to run (default 240, --quick 36)")
ap.add_argument("--kills", type=int, help="kill -9 of the portal (default 50, --quick 5)")
ap.add_argument("--cuts", type=int, help="simulated power cuts (default 20, --quick 3)")
ap.add_argument("--quick", action="store_true", help="a short run for CI (under 2 minutes)")
ap.add_argument("--period", type=int, help="windows between samples (default 25, --quick 10)")
ap.add_argument("--per-day", type=int, default=300, help="windows that make one simulated day in the report")
ap.add_argument("--devices", type=int, default=40)
ap.add_argument("--seed", type=int, default=1)
ap.add_argument("--strict", action="store_true", help="also fail on coins stranded by a kill before the router noted the window (finding AO-11)")
ap.add_argument("--json", help="write the report here as JSON")
A = ap.parse_args()
WINDOWS = A.windows or (36 if A.quick else 240)
KILLS = A.kills if A.kills is not None else (5 if A.quick else 50)
CUTS = A.cuts if A.cuts is not None else (3 if A.quick else 20)
PERIOD = A.period or (10 if A.quick else 25)
MIN_PER_PESO = 6   # HyperSpeed: 1 to 4 pesos buy 6 minutes each (tiers start at 5 pesos)
rng = random.Random(A.seed)

# real windows, as short as the program's own settings allow
env = Env(offset=160, COIN_FIRST_WAIT_SECONDS=1, COIN_IDLE_WAIT_SECONDS=1, COIN_MAX_SECONDS=4, COIN_DRAIN_SECONDS=2, COIN_POLL_MS=100,
          EMPTY_COOLDOWN=1, EMPTY_LIMIT=1000, FAIR_INTERVAL_SECONDS=1)
devices = [(f"127.0.{1 + i // 250}.{1 + i % 250}", f"aa:bb:cc:01:{i // 250:02x}:{1 + i % 250:02x}") for i in range(A.devices)]
env.macs = dict(devices)
env.write_arp()
STOP_AT = WINDOWS // 2   # kills and power cuts happen before this window; after it the portal runs undisturbed

# ---- what happens when: a sorted list of (window, kind, when) -------------------------------------------------------------------
kinds = ["cut"] * CUTS + ["kill"] * KILLS
rng.shuffle(kinds)
slots = sorted(rng.sample(range(2, STOP_AT), len(kinds)))   # (needs windows >= 2 * (kills + cuts + 2))
plan = {w: (k, rng.choice(["idle", "mid", "mid"])) for w, k in zip(slots, kinds)}


# ---- measuring ----------------------------------------------------------------------------------------------------------------
def proc_numbers(pid):
    out = {"rss_kb": 0, "threads": 0, "fds": 0}
    try:
        with open(f"/proc/{pid}/status") as f:
            for l in f:
                if l.startswith("VmRSS:"):
                    out["rss_kb"] = int(l.split()[1])
                elif l.startswith("Threads:"):
                    out["threads"] = int(l.split()[1])
        out["fds"] = len(os.listdir(f"/proc/{pid}/fd"))
    except OSError:
        pass
    return out


def file_sizes():
    """Bytes of every file under DATA_DIR (a folder counts as the sum of its files and shows its file count)."""
    out = {}
    for d, _dirs, files in os.walk(env.data):
        for f in files:
            p = os.path.join(d, f)
            top = f if d == env.data else os.path.relpath(p, env.data).split("/")[0] + "/"
            out[top] = out.get(top, 0) + os.path.getsize(p)
    return out


def ledger_total():
    """(pesos, whole rows) of revenue.csv; a torn or foreign line is not a row."""
    pesos = rows = 0
    for l in env.revenue().split("\n"):
        f = l.split(",")
        if len(f) == 6 and f[2].isdigit() and f[4] in ("new", "topup", "voucher", "reprint", "carry"):
            pesos += int(f[2])
            rows += 1
    return pesos, rows


def paid_minutes(mac):
    e = env.roll().get(mac)
    return int(e[5]) if e else 0


# ---- the run ------------------------------------------------------------------------------------------------------------------
samples: list = []
incidents: list = []
life = {"n": 0, "windows": 0}
coins_in, kills_done, cuts_done = 0, 0, 0
coins_by_mac: dict = {}
credits: dict = {}   # mac -> [(time, pesos)] of windows credited, for the paid-time check
window_log: list = []
stranded_log: list = []
stranded, cur_mac, busy_total = 0, "", [0]


def start_portal():
    ok = env.start()
    life["n"] += 1
    life["windows"] = 0
    return ok


def sample(tag):
    s = {"tag": tag, "window": len(window_log), "life": life["n"], "life_windows": life["windows"], "t": round(time.time() - t0, 1),
         **proc_numbers(env.portal.pid), "files": file_sizes(), "coins_in": coins_in, "credited": ledger_total()[0]}
    samples.append(s)
    return s


def restart_after_kill(cut):
    """Start the portal again (after a power cut with a half-written last row on the appended files)."""
    global cuts_done
    if cut:
        cuts_done += 1
        for name in ("revenue.csv", "wheel.log"):
            p = f"{env.data}/{name}"
            if os.path.exists(p):
                with open(p, "ab") as f:
                    f.write(b"1792797243,hyper,1,6,top" if name == "revenue.csv" else b"1792797243,sp")   # a write cut half-way
    if not start_portal():
        incidents.append("the portal did not answer after a restart")


def kill_portal(cut, n, before_pesos, where):
    """kill -9 now, start again, and settle the books for the window of `n` coins that was running (or about to).
    The portal writes a window's record to flash when it first SEES a coin; a kill before that leaves the box holding coins nobody
    can match to a window. Those are counted as stranded (finding AO-11), never as credited. Anything else must be credited whole."""
    global kills_done, stranded
    noted = os.listdir(f"{env.data}/open") != []
    env.stop()
    kills_done += 1
    restart_after_kill(cut)
    noted = noted or os.listdir(f"{env.data}/open") != []   # (a coin may have been noted between the look and the kill)
    got = lambda: ledger_total()[0] - before_pesos
    if noted:
        ok = wait_until(lambda: got() == n and os.listdir(f"{env.data}/open") == [], 20)
        if not ok:
            incidents.append(f"kill {kills_done} ({where}, window {len(window_log)}): the window was on flash, {n} pesos put in, {got()} credited, open: {os.listdir(f'{env.data}/open')}")
    elif not wait_until(lambda: got() == n, 4):
        if got() == 0:
            stranded += n
            stranded_log.append({"window": len(window_log), "kill": kills_done, "where": where, "pesos": n, "mac": cur_mac})
        else:
            incidents.append(f"kill {kills_done} ({where}, window {len(window_log)}): {n} pesos put in, {got()} credited (neither all nor none)")
    return noted


def one_window(idx, event):
    """One customer inserts 1 to 3 coins. `event` = (kind, when) to kill the portal during or before this window."""
    global coins_in
    global cur_mac
    ip, mac = devices[rng.randrange(len(devices))]
    cur_mac = mac
    n = rng.choice([1, 1, 2, 3])
    env.set_box(busy=False, coins_at=[0.2, 0.4, 0.6][:n], expire=True)
    killed = False
    if event and event[1] == "idle":
        wait_until(lambda: os.listdir(f"{env.data}/open") == [], 10)   # between customers: the last window is closed
        kill_portal(event[0] == "cut", 0, ledger_total()[0], "before the window")
    c = Cust(ip, port=env.PP, reset=True)
    if not c.ok or c.wait(lambda m: True, 8) is None:   # (after a restart the first screen may be busy: recovery runs first)
        incidents.append(f"window {idx}: the coin page did not open")
        c.close()
        return
    before_pesos = ledger_total()[0]
    log_from = len(env.log_text())
    busy = 0
    for _ in range(40):   # a customer who is told "busy" waits a moment and taps again; no coin goes in until the window is armed
        c.mark()
        c.send({"t": "start", "plan": "hyper"})
        m = c.wait(lambda m: m.get("s") in ("armed", "busy", "error", "final", "empty"), 10)
        if m is not None and m.get("s") in ("busy", "error", "final"):   # (final: the page of a window a restart settled)
            busy += 1
            time.sleep(0.4)
            continue
        break
    if m is None or m.get("s") != "armed":
        incidents.append(f"window {idx}: the window did not arm after {busy} busy answers (answer: {m and m.get('s')})")
        c.close()
        return
    busy_total[0] += busy
    coins_in += n
    coins_by_mac[mac] = coins_by_mac.get(mac, 0) + n
    life["windows"] += 1
    final = None
    if event and event[1] == "mid":
        time.sleep(rng.uniform(0.0, 1.9))   # (armed already)
        was_stranded = stranded
        kill_portal(event[0] == "cut", n, before_pesos, "in the window")
        c.close()
        killed = True
        if stranded == was_stranded:
            credits.setdefault(mac, []).append((time.time(), n))
    else:
        final = c.wait_state("final", 25)
    got = ledger_total()[0] - before_pesos
    if not killed:
        wait_until(lambda: ledger_total()[0] - before_pesos >= n, 3)
        got = ledger_total()[0] - before_pesos
        if got != n or not final or final.get("pulses") != n:
            seen = [m.get("s") for m in c.states()[c.since:]]
            tail = " | ".join(l[10:] for l in env.log_text()[log_from:].split("\n") if l)[-700:]
            incidents.append(f"window {idx} for {mac}: {n} coins in, {got} pesos booked, customer was told {final and final.get('pulses')}; screens {seen[-6:]}; portal log: {tail}")
        else:
            credits.setdefault(mac, []).append((time.time(), n))
    c.close()
    window_log.append({"i": idx, "mac": mac, "coins": n, "killed": killed, "event": event})


t0 = time.time()
try:
    check(start_portal(), "the portal starts")
    for _, mac in devices:
        env.nds_client(mac)
    sample("start")
    sizes_start = dict(samples[0]["files"])
    for idx in range(WINDOWS):
        one_window(idx, plan.get(idx))
        if (idx + 1) % PERIOD == 0:
            s = sample("period")
            print(f"  window {idx + 1}/{WINDOWS}  {s['t']:>6} s  life {s['life']}  rss {s['rss_kb']} kB  threads {s['threads']}  fds {s['fds']}"
                  f"  coins in {s['coins_in']}  credited {s['credited']}  revenue.csv {s['files'].get('revenue.csv', 0)} B", flush=True)
    # let the last window finish its book-keeping, then the final numbers
    wait_until(lambda: ledger_total()[0] + stranded == coins_in and os.listdir(f"{env.data}/open") == [], 15)
    final_sample = sample("end")
    elapsed = time.time() - t0
    rc, verify_out = env.cli("verify")
    credited, rows = ledger_total()

    # ---- the money checks --------------------------------------------------------------------------------------------------
    check(credited + stranded == coins_in, f"every coin put in is credited exactly once or stranded by a kill (reported below): {coins_in} put in, {credited} credited, {stranded} stranded, {rows} ledger rows")
    if A.strict:
        check(stranded == 0, f"no coin stranded on the box by a kill ({stranded} pesos in {len(stranded_log)} kills)")
    check(rc == 0, f"the ledger verifies ({verify_out})")
    check(not incidents, "no window or restart left a coin unsettled: " + "; ".join(incidents[:5]))
    # paid time: each purchase adds 6 minutes a peso to a running session; a purchase after the session ran out starts a new one.
    # (A purchase within 20 s of the end is too close to call from outside, so that device is not compared.)
    wrong, skipped = {}, 0
    for m, seq in credits.items():
        mins = end = 0.0
        unsure = False
        for t, p in seq:
            if t > end + 20:
                mins = 0.0
            elif t > end - 20:
                unsure = True
            mins += p * MIN_PER_PESO
            end = max(end, t) + p * MIN_PER_PESO * 60
        if unsure:
            skipped += 1
        elif paid_minutes(m) != mins:
            wrong[m] = (paid_minutes(m), mins)
    check(not wrong, f"each device holds exactly the time its coins bought ({len(credits) - skipped} devices compared, {skipped} too close to the end of their time to call; wrong: {dict(list(wrong.items())[:3])})")
    check(kills_done == KILLS + CUTS and cuts_done == CUTS, f"the planned kills and power cuts were done ({kills_done} kill -9 of which {cuts_done} power cuts)")

    # ---- resources: the last stretch is one process, no kills ---------------------------------------------------------------
    last = [s for s in samples if s["life"] == samples[-1]["life"] and s["tag"] == "period"]
    verdicts = {}
    if len(last) >= 4:
        mid = last[1:len(last) // 2 + 1]
        for key, slack in (("rss_kb", 0), ("threads", 2), ("fds", 2)):
            base = statistics.median(s[key] for s in mid)
            end = max(s[key] for s in last[-2:])
            limit = max(base * 1.10, base + slack)
            verdicts[key] = {"middle": base, "end": end, "limit": limit, "ok": end <= limit}
            check(end <= limit, f"{key} in the undisturbed stretch: middle {base}, end {end}, limit {limit:.0f}")
    else:
        print("note: the undisturbed stretch has fewer than 4 samples; resource growth is reported, not judged")

    # ---- the report ---------------------------------------------------------------------------------------------------------
    sizes_end = final_sample["files"]
    per_day = A.per_day / WINDOWS
    files = {}
    for name in sorted(set(sizes_start) | set(sizes_end)):
        a, b = sizes_start.get(name, 0), sizes_end.get(name, 0)
        files[name] = {"start": a, "end": b, "per_window": round((b - a) / WINDOWS, 2), "per_sim_day": round((b - a) * per_day)}
    print("\n==== soak report ====")
    print(f"windows {WINDOWS} ({WINDOWS / A.per_day:.2f} simulated days at {A.per_day} a day), {elapsed / 60:.1f} min, seed {A.seed}, devices {A.devices}, "
          f"process starts {life['n']}, kill -9 {kills_done} (power cuts {cuts_done})")
    print(f"coins put in {coins_in} pesos, credited {credited} pesos in {rows} ledger rows; verify: {verify_out}")
    print(f"customers told 'busy' (they tapped again): {busy_total[0]}")
    print(f"stranded by a kill before the router noted the window (finding AO-11): {stranded} pesos in {len(stranded_log)} of {kills_done} kills")
    print(f"{'file':<28}{'start B':>10}{'end B':>10}{'B/window':>10}{'B/sim day':>12}")
    for name, f in files.items():
        print(f"{name:<28}{f['start']:>10}{f['end']:>10}{f['per_window']:>10}{f['per_sim_day']:>12}")
    print(f"{'(period)':<8}{'window':>8}{'life':>6}{'rss kB':>9}{'threads':>9}{'fds':>6}")
    for s in samples:
        print(f"{s['tag']:<8}{s['window']:>8}{s['life']:>6}{s['rss_kb']:>9}{s['threads']:>9}{s['fds']:>6}")
    if A.json:
        with open(A.json, "w") as jf:
            json.dump({"windows": WINDOWS, "per_day": A.per_day, "minutes": round(elapsed / 60, 2), "seed": A.seed, "devices": A.devices,
                       "process_starts": life["n"], "kills": kills_done, "power_cuts": cuts_done, "coins_in": coins_in, "credited": credited,
                       "ledger_rows": rows, "busy_answers": busy_total[0], "stranded_pesos": stranded, "stranded": stranded_log,
                       "verify": verify_out, "files": files, "resource_verdicts": verdicts, "incidents": incidents, "samples": samples},
                      jf, indent=1)
finally:
    env.close()
finish()
