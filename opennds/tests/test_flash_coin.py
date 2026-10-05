"""End-to-end test of the "flash coin" portal without a router or an ESP32.

Real pieces under test: flash_coin.sh (the theme, run the way libopennds.sh runs it, helpers stubbed), flash_coin_lib.sh
(the roll), flash_coin_status.sh, flash_fairuse.sh and coinslot-listener.sh (/verify, /ack, the coin window).
Stand-ins: fakebox.py (the ESP32), fake_ndsctl.sh (openNDS' ndsctl), fake_socat.py (socat).
Run with:  python3 opennds/tests/test_flash_coin.py"""
import glob, hashlib, json, os, re, shutil, signal, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
THEME, LISTENER = f"{ROOT}/flash_coin.sh", f"{ROOT}/coinslot-listener.sh"
LIB, STATUS, FAIR = f"{ROOT}/flash_coin_lib.sh", f"{ROOT}/flash_coin_status.sh", f"{ROOT}/flash_fairuse.sh"
KEY = "test-gateway-key-123456"
BOX_PORT, LISTEN_PORT, STREAM_PORT, EVENT_PORT = 18190, 18199, 18210, 18212
tmp = tempfile.mkdtemp()
CTL, LOG, STATE, DATA, NDS = f"{tmp}/ctl.json", f"{tmp}/box.log", f"{tmp}/state", f"{tmp}/data", f"{tmp}/nds"
ROLL, REV = f"{tmp}/roll/vouchers.txt", f"{tmp}/roll/revenue.csv"
os.makedirs(NDS)
open(LOG, "w").close()
failures = checks = 0
MAC_A, MAC_B, MAC_C, MAC_D = "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:03", "aa:bb:cc:00:00:04"


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def set_box(**kw):
    json.dump(kw, open(CTL, "w"))


def nds_client(mac, **kw):
    d = dict(STATE="Preauthenticated", SESSION_END=0, DL=0, UL=0, UPRATE=0, DOWNRATE=0)
    d.update(kw)
    open(f"{NDS}/{mac.replace(':', '')}", "w").write("".join(f"{k}={v}\n" for k, v in d.items()))


def nds_get(mac):
    out = {}
    for line in open(f"{NDS}/{mac.replace(':', '')}"):
        k, v = line.strip().split("=", 1)
        out[k] = v
    return out


def calls():
    return open(f"{NDS}/calls.log").read().split("\n") if os.path.exists(f"{NDS}/calls.log") else []


def roll():
    """The roll as {mac: [fields]}"""
    if not os.path.exists(ROLL):
        return {}
    return {l.split(",")[7]: l.split(",") for l in open(ROLL).read().split("\n") if l}


def roll_write(*lines):
    os.makedirs(os.path.dirname(ROLL), exist_ok=True)
    open(ROLL, "w").write("".join(l + "\n" for l in lines))


def revenue():
    return open(REV).read() if os.path.exists(REV) else ""


conf = f"{tmp}/coinslot.conf"
open(conf, "w").write(
    f"GW_BOX=127.0.0.1:{BOX_PORT}\nGW_KEY={KEY}\nSTATE_DIR={STATE}\nDATA_DIR={DATA}\nNDSCTL={HERE}/fake_ndsctl.sh\n"
    f"LISTEN_PORT={LISTEN_PORT}\nCOIN_FIRST_WAIT_SECONDS=4\nCOIN_IDLE_WAIT_SECONDS=3\nCOIN_MAX_SECONDS=12\nSTREAM_PORT={STREAM_PORT}\nEVENT_PORT={EVENT_PORT}\n"
    "FAIR_USE_KB=1000\nFAIR_THROTTLE_DOWN_KBPS=2000\nFAIR_THROTTLE_UP_KBPS=1000\nFAIR_THROTTLE_MINUTES=5\nFAIR_FULL_MINUTES=2\n")
os.makedirs(f"{tmp}/bin")
os.symlink(f"{HERE}/fake_ndsctl.sh", f"{tmp}/bin/ndsctl")
env = dict(os.environ, FAKEBOX_CTL=CTL, FAKEBOX_LOG=LOG, FAKEBOX_KEY=KEY, COINSLOT_CONF=conf, FAKE_NDS_DIR=NDS,
           NDSCTL=f"{HERE}/fake_ndsctl.sh", FLASH_LIB=LIB, FLASH_CONF=f"{tmp}/none.conf", FLASH_ROLL=ROLL, FLASH_LOCK=f"{tmp}/roll.lock",
           FLASH_TMP=f"{tmp}/flash", COINSLOT_URL=f"http://127.0.0.1:{LISTEN_PORT}", PATH=f"{tmp}/bin:" + os.environ["PATH"])
ARP = f"{tmp}/arp"
open(ARP, "w").write(f"IP address       HW type     Flags       HW address            Mask     Device\n127.0.0.1  0x1  0x2  {MAC_A}  *  lo\n")
env["ARP_FILE"] = ARP
set_box(busy=False, coins_at=[])
procs = [subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(STREAM_PORT), LISTENER, "stream-handle"], env=env),
         subprocess.Popen([sys.executable, f"{HERE}/fakebox.py", str(BOX_PORT)], env=env),
         subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(LISTEN_PORT), LISTENER], env=env),
         subprocess.Popen(["sh", LISTENER, "events"], env=env, stderr=subprocess.DEVNULL)]   # the box's coin events (real socat, UDP)
time.sleep(1.0)


def page(hid, mac, coinact="", plan="", landing="", port=LISTEN_PORT, auth_ok="1", vcode="", statusvar="", terms="", forfeit=""):
    e = dict(env, HID=hid, MAC=mac, COINACT=coinact, COINPLAN=plan, LANDING=landing, PORT=str(port), AUTH_OK=auth_ok,
             VCODE=vcode, STATUSVAR=statusvar, TERMS=terms, FORFEIT=forfeit)
    return subprocess.run(["bash", f"{HERE}/theme_harness.sh", THEME], env=e, capture_output=True, text=True, timeout=90).stdout


def lib(script, **kw):
    """Run shell code with the library loaded; returns (exit code, stdout)."""
    r = subprocess.run(["sh", "-c", f". {LIB}; {script}"], env={**env, **kw}, capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout.strip()


def sid_of(hid):
    return hashlib.sha256(hid.encode()).hexdigest()[:32]


def get(path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{LISTEN_PORT}{path}", timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def pay(hid, mac, plan, n_coins, forfeit=""):
    """Drive the customer pages: start, wait, finish. Returns the result page."""
    set_box(busy=False, coins_at=[0.5] * n_coins)
    page(hid, mac, "start", plan, forfeit=forfeit)
    time.sleep(1.6)
    page(hid, mac, "wait", plan, forfeit=forfeit)
    p = page(hid, mac, "finish", plan, forfeit=forfeit)
    for _ in range(25):
        if "counting" not in p.lower():
            break
        time.sleep(1)
        p = page(hid, mac, "finish", plan, forfeit=forfeit)
    return p


def code_from(p):
    m = re.search(r'class="code">([a-z0-9]{4}-[a-z0-9]{4})<', p)
    return m.group(1) if m else None


try:
    # ---- welcome ----------------------------------------------------------------------------------------------------
    nds_client(MAC_A)
    p = page("hidA", MAC_A)
    check("HyperSpeed" in p and "Endurance" in p and "Insert Coin" in p, "welcome shows both plans and Insert Coin")
    for needle in ["&#8369;5</span><span>30 min", "&#8369;10</span><span>1 hr", "&#8369;1</span><span>15 min", "&#8369;20</span><span>24 hrs"]:
        check(needle in p, f"rate row present: {needle}")
    check("#0f1715" in p and "linear-gradient(135deg,#11998e,#38ef7d)" in p, "green flash theme")
    check("refresh" not in p.lower() and len(p) < 14000, f"welcome is small and static ({len(p)} bytes)")
    check("coinact=vform" in p, "welcome offers code restore")
    p = page("hidA", MAC_A, port=1)
    check("Coin payment is offline" in p, "listener down and no paid time: offline page, no crash")

    # ---- instant: online on the first coin, the whole window priced once when it closes -----------------------------
    def api(path):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{STREAM_PORT}{path}", timeout=15) as r:
                return r.status, json.loads(r.read().decode()), r.headers.get("Access-Control-Allow-Origin")
        except urllib.error.HTTPError as e:
            return e.code, {}, None

    def wait_until(cond, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if cond():
                return True
            time.sleep(0.05)
        return False

    p = page("hidI", MAC_A)
    check('id="live"' in p and "/api/start?sid=" in p and sid_of("hidI") in p, "the welcome page carries the live page (one page, no reloads)")
    roll_write(); nds_client(MAC_A); open(f"{NDS}/calls.log", "w").close(); open(LOG, "w").close()
    # P17 on Endurance as 1 + 1 + 15 pesos: online after the first peso; the window is priced as 10 + 5 + 1 + 1 = 690 min
    set_box(busy=False, coins_at=[0.5, 1.0] + [1.5 + 0.1 * i for i in range(15)])
    t0 = time.time()
    code_, j, cors = api(f"/api/start?sid={sid_of('hidI')}&plan=endurance")
    check(code_ == 200 and j.get("state") in ("starting", "armed") and cors == "*", f"/api/start opens a window for the device that asks: {j}")
    online = wait_until(lambda: os.path.exists(f"{NDS}/{MAC_A.replace(':', '')}") and nds_get(MAC_A)["STATE"] == "Authenticated", 6)
    t_online = time.time() - t0
    check(online and t_online < 3.0, f"online right after the first coin, with no tap (coin at 0.5 s, online at {t_online:.2f} s)")
    r = roll().get(MAC_A, [])
    check(r and r[5] == "15" and r[11] == "endurance" and r[16] == "0", f"first coin recorded as the window's share so far, not final: {r}")
    check(revenue() == "", "no revenue logged before the window closes")
    _, j, _ = api(f"/api/status?sid={sid_of('hidI')}")
    check(j.get("online") is True, f"status says online: {j}")
    check(wait_until(lambda: api(f"/api/status?sid={sid_of('hidI')}")[1].get("final"), 15), "the window closes and settles by itself")
    _, j, _ = api(f"/api/status?sid={sid_of('hidI')}")
    r = roll()[MAC_A]
    check(r[5] == "690" and r[13] == "17" and r[16] == "1" and j.get("fwmin") == 690 and j.get("pulses") == 17,
          f"the whole window priced once: P17 = 690 min (not per coin): roll {r} status {j}")
    check(re.fullmatch(r"[a-z0-9]{4}-[a-z0-9]{4}", j.get("code", "")) and j["code"] == r[0], "the final status carries the restore code")
    check(revenue().strip().split("\n") == [revenue().strip()] and ",endurance,17,690," in revenue(), "revenue logged once, for the whole window: " + revenue())
    check(any(l.startswith("ack ") and sid_of("hidI") in l for l in open(LOG).read().split("\n")), "coins acknowledged on the box after the window settled")
    check(sum(1 for c in calls() if c.startswith("deauth " + MAC_A)) == 1, "exactly one re-grant (at close), not one per coin: " + str(calls()))
    nds = nds_get(MAC_A)
    check(nds["UPRATE"] == "2000" and nds["DOWNRATE"] == "5000" and abs(int(nds["SESSION_END"]) - (int(r[6]) + 690 * 60)) < 120,
          f"granted 690 min from the first coin, with the Endurance caps: {nds}")
    check(any(l.startswith("event coin") for l in open(LOG).read().split("\n")), "the box pushed coin events")
    live = json.load(open(f"{STATE}/{sid_of('hidI')}/live.json"))
    check(live.get("pulses") == 17 and live.get("type") == "end", f"live.json holds the box's last event: {live}")
    p = page("hidI", MAC_A, "connect", "endurance", landing="yes")
    check("Connected" in p and "AUTHCALL" not in p and sum(1 for c in calls() if c.startswith("deauth " + MAC_A)) == 1,
          "a late Connect (no-script flow) confirms without granting again (no interruption)")
    # top-up while online: no early grant, one re-grant when it closes, priced per window
    open(f"{NDS}/calls.log", "w").close()
    set_box(busy=False, coins_at=[0.5, 0.9])
    api(f"/api/start?sid={sid_of('hidI2')}&plan=endurance")
    check(wait_until(lambda: api(f"/api/status?sid={sid_of('hidI2')}")[1].get("final"), 15), "top-up window settles")
    check(roll()[MAC_A][5] == "720" and sum(1 for c in calls() if c.startswith("deauth")) == 1, f"top-up adds this window's price (30 min), one re-grant: {roll()[MAC_A]} {calls()}")
    # the other plan is refused without consent, through the API too
    _, j, _ = api(f"/api/start?sid={sid_of('hidI3')}&plan=hyper")
    check(j.get("error") == "PLAN_MISMATCH" and j.get("plan") == "endurance", f"plan switch needs consent: {j}")
    # someone else's window cannot be read or closed
    page("hidJ", MAC_B, "start", "hyper")
    check(api(f"/api/status?sid={sid_of('hidJ')}")[0] == 403 and api(f"/api/finish?sid={sid_of('hidJ')}")[0] == 403, "another device's window is refused")
    get(f"/finish?sid={sid_of('hidJ')}"); time.sleep(5)
    # a forged or replayed event changes nothing
    d = f"{STATE}/{sid_of('hidI2')}"
    before = open(f"{d}/live.env").read()
    wid = open(f"{d}/wid").read()
    subprocess.run(["sh", LISTENER, "event-line", f"gw1ev:{sid_of('hidI2')}:{wid}:99:coin:50:" + "0" * 64], env=env)
    other = hashlib.sha256(b"x").hexdigest()[:32]
    body = f"gw1ev:{sid_of('hidI2')}:{other}:99:coin:50"
    import hmac as _h
    subprocess.run(["sh", LISTENER, "event-line", body + ":" + _h.new(KEY.encode(), body.encode(), hashlib.sha256).hexdigest()], env=env)
    check(open(f"{d}/live.env").read() == before, "an event with a bad signature, or for another window, is ignored")
    # an older box (no events) and lost events still work, by the signed checks
    for ctlx, label in [({"events": False}, "a box without coin events"), ({"drop_events": True}, "lost coin events")]:
        roll_write(); nds_client(MAC_A)
        set_box(busy=False, coins_at=[0.5], **ctlx)
        hid = "hidK" + label[:3]
        t0 = time.time()
        api(f"/api/start?sid={sid_of(hid)}&plan=hyper")
        ok = wait_until(lambda: nds_get(MAC_A)["STATE"] == "Authenticated", 8)
        check(ok and time.time() - t0 < 4, f"{label}: still online on the first coin ({time.time() - t0:.1f} s)")
        check(wait_until(lambda: api(f"/api/status?sid={sid_of(hid)}")[1].get("final"), 15) and roll()[MAC_A][5] == "6", f"{label}: settles")
    # openNDS refuses the grant: never "online", never final, the coins stay on the box for the portal to finish
    roll_write(); nds_client(MAC_A); open(LOG, "w").close(); open(f"{NDS}/refuse_auth", "w").close(); rev0 = revenue().count("\n")
    set_box(busy=False, coins_at=[0.5])
    api(f"/api/start?sid={sid_of('hidR1')}&plan=hyper")
    check(wait_until(lambda: api(f"/api/status?sid={sid_of('hidR1')}")[1].get("state") == "done", 15), "refused grant: the window still closes")
    _, j, _ = api(f"/api/status?sid={sid_of('hidR1')}")
    check(not j.get("online") and not j.get("final") and nds_get(MAC_A)["STATE"] == "Preauthenticated", f"refused grant: never reported online or final: {j}")
    check(not any(l.startswith("ack ") and sid_of("hidR1") in l for l in open(LOG).read().split("\n")), "refused grant: the coins are not acknowledged on the box")
    os.remove(f"{NDS}/refuse_auth")
    p = page("hidR1", MAC_A, "connect", "hyper", landing="yes")
    check("AUTHCALL" in p and roll()[MAC_A][5] == "6" and revenue().count("\n") - rev0 == 1,
          f"refused grant: Connect on the portal finishes it, credited once: roll {roll().get(MAC_A)}, new revenue lines {revenue().count(chr(10)) - rev0}")
    check(any(l.startswith("ack ") and sid_of("hidR1") in l for l in open(LOG).read().split("\n")), "...and then the coins are acknowledged")
    # the box fails to remove the coins (ACK_INCOMPLETE): the router keeps retrying and never starts a new window over them
    roll_write(); nds_client(MAC_A); open(LOG, "w").close()
    set_box(busy=False, coins_at=[0.5], ack_fail=True)
    api(f"/api/start?sid={sid_of('hidR2')}&plan=hyper")
    check(wait_until(lambda: api(f"/api/status?sid={sid_of('hidR2')}")[1].get("state") == "done", 20), "failed ack: the window closes")
    check(os.path.exists(f"{STATE}/{sid_of('hidR2')}/ackpending"), "failed ack: the router keeps it pending (not trusting a 503 answer)")
    _, j, _ = api(f"/api/start?sid={sid_of('hidR2')}&plan=hyper")
    check(j.get("error") == "ACK_PENDING", f"failed ack: no new window over unacknowledged coins: {j}")
    set_box(busy=False, coins_at=[], ack_fail=False)
    _, j, _ = api(f"/api/start?sid={sid_of('hidR2')}&plan=hyper")
    check(j.get("state") in ("starting", "armed") and not os.path.exists(f"{STATE}/{sid_of('hidR2')}/ackpending"), f"once the box accepts the ack, the next window opens: {j}")
    get(f"/finish?sid={sid_of('hidR2')}")
    wait_until(lambda: api(f"/api/status?sid={sid_of('hidR2')}")[1].get("state") == "done", 15)
    # a window that already has an owner cannot be claimed by another device through the portal API
    open(ARP, "w").write(f"IP address HW type Flags HW address Mask Device\n127.0.0.1 0x1 0x2 {MAC_B} * lo\n")
    check(api(f"/api/start?sid={sid_of('hidR2')}&plan=hyper")[0] == 403, "another device cannot re-start someone else's window")
    open(ARP, "w").write(f"IP address       HW type     Flags       HW address            Mask     Device\n127.0.0.1  0x1  0x2  {MAC_A}  *  lo\n")
    roll_write(); nds_client(MAC_A); open(LOG, "w").close()
    if os.path.exists(REV):
        os.remove(REV)
    set_box(busy=False, coins_at=[])

    # ---- the no-script pages: P17 Endurance = 11 hrs 30 min, recorded and granted by the router itself -------------------
    p = pay("hidA", MAC_A, "endurance", 17)
    check("11 hrs 30 min" in p and "&#8369;17" in p, "result: P17 Endurance = 11 hrs 30 min")
    check(nds_get(MAC_A)["STATE"] == "Authenticated" and roll().get(MAC_A, [""] * 17)[5] == "690", "the router recorded and granted the window without a Connect tap")
    check("you still had" not in p and "added" in p and "Continue" in p, "the result page does not count the recorded window twice")
    st, v = get(f"/verify?sid={sid_of('hidA')}")
    vj = json.loads(v)
    check(vj["ok"] and vj["pulses"] == 17 and vj["minutes"] == 690 and vj["plan"] == "endurance" and len(vj["wid"]) >= 16 and vj["claimed"],
          f"/verify tells what was paid: {v}")
    check(nds_get(MAC_A)["UPRATE"] == "2000" and nds_get(MAC_A)["DOWNRATE"] == "5000", "Endurance grant: 2 Mbit/s up, 5 Mbit/s down")
    p = page("hidA", MAC_A, "connect", "endurance", landing="yes")
    check("Connected" in p and "AUTHCALL" not in p, "Continue confirms without granting again")
    code = code_from(p)
    check(code is not None, "xxxx-xxxx code shown")
    line = roll().get(MAC_A)
    check(line and line[0] == code and line[5] == "690" and line[11] == "endurance" and line[13] == "17" and line[12] == vj["wid"] and line[8] == "0",
          f"roll line holds code, minutes, plan, window id, pesos: {line}")
    check(",endurance,17,690," in revenue() and revenue().count("\n") == 1, "revenue logged once")
    check(any(l.startswith("ack ") and sid_of("hidA") in l for l in open(LOG).read().split("\n")), "coins acknowledged on the box after access")
    check(json.loads(get(f"/verify?sid={sid_of('hidA')}")[1])["claimed"], "/verify reports the window as used")
    check(nds_get(MAC_A)["STATE"] == "Authenticated", "device authenticated")

    # ---- a replayed Connect never credits the window twice ----------------------------------------------------------------
    page("hidA", MAC_A, "connect", "endurance", landing="yes")
    check(roll()[MAC_A][5] == "690" and revenue().count("\n") == 1, "replaying Connect does not add the minutes again")
    check(json.loads(get(f"/ack?sid={sid_of('hidA')}")[1]) == {"success": True, "acked": True}, "/ack is idempotent")
    check("NOT_DONE" in get(f"/ack?sid={sid_of('nobody')}")[1], "/ack refuses a window that has not finished")

    # ---- other devices cannot take these coins ----------------------------------------------------------------------------
    set_box(busy=False, coins_at=[0.5] * 2)
    page("hidX", MAC_B, "start", "hyper"); time.sleep(1.5)
    page("hidX", MAC_B, "finish", "hyper"); time.sleep(2)
    p = page("hidY", MAC_C, "connect", "hyper", landing="yes")
    check("AUTHCALL" not in p and "No paid time found" in p and MAC_C not in roll(), "another device cannot claim someone else's coins")
    check(roll()[MAC_B][5] == "12" and roll()[MAC_B][11] == "hyper", "the coins went to the device that started the window: P2 HyperSpeed = 12 min")
    roll_write(*[",".join(v) for k, v in roll().items() if k != MAC_B])
    nds_client(MAC_B)

    # ---- top-up while connected -------------------------------------------------------------------------------------------
    p = page("hidA2", MAC_A, "start", "hyper", statusvar="authenticated")
    check("still have Endurance time" in p and "Switch to HyperSpeed" in p, "other plan: warned with add-time and switch options")
    p = pay("hidA3", MAC_A, "endurance", 2)
    check("12 hrs" in p and "&#8369;2 = 30 min added" in p, "top-up result shows the new total: " + re.sub(r"\s+", " ", p)[-400:])
    p = page("hidA3", MAC_A, "connect", "endurance", landing="yes", statusvar="authenticated")
    check(code_from(p) == code and roll()[MAC_A][5] == "720" and roll()[MAC_A][13] == "19", "top-up keeps the code and adds to the roll line")
    check(any(re.match(r"auth aa:bb:cc:00:00:01 72[01] 2000 5000 0 0", c) for c in calls()), "top-up re-granted ~12 hr: " + str([c for c in calls() if c.startswith("auth")][-2:]))
    check(",endurance,2,30," in revenue(), "top-up logged")

    # ---- switching plan needs the customer's agreement ------------------------------------------------------------------------
    p = page("hidA4", MAC_A, "start", "hyper", statusvar="authenticated")
    check("still have Endurance time" in p and roll()[MAC_A][11] == "endurance", "without agreement the window does not even start")
    p = pay("hidA4", MAC_A, "hyper", 5, forfeit="yes")
    r = roll()[MAC_A]
    check(r[11] == "hyper" and r[5] == "30" and r[0] != code, f"agreed at Start: the switch replaced the line with a HyperSpeed one: {r}")
    check(",hyper,5,30," in revenue().split("\n")[-2], "switch logged")

    # ---- automatic reconnect after a reboot (openNDS forgets sessions), even with the manager down --------------------------
    nds_client(MAC_A)
    p = page("hidA", MAC_A, port=1)
    check("Welcome back" in p and "AUTHCALL" in p and nds_get(MAC_A)["STATE"] == "Authenticated", "returning device reconnects without a code and without the manager")
    m = re.search(r"AUTHCALL sessiontimeout=(\d+)", p)
    check(m and 29 <= int(m.group(1)) <= 30, "reconnect grants only the time left")

    # ---- pause / resume ------------------------------------------------------------------------------------------------------
    pay("hidP", MAC_C, "endurance", 10)
    nds_client(MAC_C)
    page("hidP", MAC_C, "connect", "endurance", landing="yes")
    p = page("hidP", MAC_C, "pausecheck", "endurance", statusvar="authenticated")
    check("Pause your time" in p and "8 hrs" in p and "only <b>once</b>" in p, "pause confirmation")
    p = page("hidP", MAC_C, "pause", "endurance", statusvar="authenticated")
    check("Time paused" in p and code_from(p) == roll()[MAC_C][0], "paused page shows the code")
    check(roll()[MAC_C][8] == "1" and roll()[MAC_C][9] != "0" and 470 * 60 <= int(roll()[MAC_C][10]) <= 480 * 60, "roll holds the frozen time")
    check(nds_get(MAC_C)["STATE"] == "Preauthenticated", "paused device is disconnected")
    time.sleep(2)
    p = page("hidP9", MAC_C)
    check("Your time is paused" in p and "Resume" in p and "AUTHCALL" not in p, "paused device is asked to Resume, not reconnected silently")
    p = page("hidP9", MAC_C, "connect", "endurance", landing="yes")
    m = re.search(r"AUTHCALL sessiontimeout=(\d+)", p)
    check(m and int(m.group(1)) in (479, 480) and roll()[MAC_C][9] == "0" and roll()[MAC_C][10] == "0", "resume grants exactly the frozen time and clears the pause: " + p[-200:])
    check(int(roll()[MAC_C][6]) + 480 * 60 - time.time() > 470 * 60, "resume moved the start forward by the time spent paused")
    p = page("hidP", MAC_C, "pause", "endurance", statusvar="authenticated")
    check("already used your one pause" in p, "a second pause is refused")
    # top-up while paused
    roll_write(*[",".join(v) for v in roll().values()])
    pay("hidQ", MAC_D, "endurance", 10)
    nds_client(MAC_D)
    page("hidQ", MAC_D, "connect", "endurance", landing="yes")
    page("hidQ", MAC_D, "pause", "endurance", statusvar="authenticated")
    pay("hidQ2", MAC_D, "endurance", 1)       # (a new browser id: the first window's worker may still be a zombie in a container without init)
    m = [c for c in calls() if re.match(r"auth aa:bb:cc:00:00:04 49[45] ", c)]
    check(m and roll()[MAC_D][9] == "0" and nds_get(MAC_D)["STATE"] == "Authenticated", "coins paid while paused resume the session with both amounts: " + str([c for c in calls() if "00:04" in c][-3:]))
    # HyperSpeed cannot pause
    nds_client("aa:bb:cc:00:00:0e")
    pay("hidH", "aa:bb:cc:00:00:0e", "hyper", 5)
    page("hidH", "aa:bb:cc:00:00:0e", "connect", "hyper", landing="yes")
    p = page("hidH", "aa:bb:cc:00:00:0e", "pause", "hyper", statusvar="authenticated")
    check("only available for Endurance" in p, "HyperSpeed cannot pause")

    # ---- restore a session on another device by its code ---------------------------------------------------------------------
    nds_client(MAC_C)
    oldcode = roll()[MAC_C][0]
    nds_client("aa:bb:cc:00:00:0f")
    p = page("hidN", "aa:bb:cc:00:00:0f", "voucher", vcode=oldcode.upper())
    check("Code accepted" in p and roll().get("aa:bb:cc:00:00:0f", [""])[0] == oldcode and MAC_C not in roll(), "code moves the session to the new device")
    p = page("hidN", "aa:bb:cc:00:00:0f", "connect", "endurance", landing="yes")
    check("AUTHCALL" in p and nds_get("aa:bb:cc:00:00:0f")["STATE"] == "Authenticated", "restored device is connected")
    p = page("hidN2", MAC_B, "voucher", vcode="zzzz-9999")
    check("not found" in p, "unknown code rejected")
    for _ in range(10):
        page("hidN2", MAC_B, "voucher", vcode="zzzz-9998")
    p = page("hidN2", MAC_B, "voucher", vcode=oldcode)
    check("Too many wrong tries" in p, "code guessing is rate limited")
    shutil.rmtree(f"{tmp}/flash", ignore_errors=True)

    # ---- time that ran out ------------------------------------------------------------------------------------------------------
    roll_write(f"dead-0000,0,0,0,0,10,{int(time.time()) - 3600},aa:bb:cc:00:00:0d,0,0,0,hyper,w1,5")
    nds_client("aa:bb:cc:00:00:0d")
    p = page("hidE", "aa:bb:cc:00:00:0d")
    check("Insert Coin" in p and "AUTHCALL" not in p, "expired time shows the rates, not a reconnect")
    p = pay("hidE", "aa:bb:cc:00:00:0d", "hyper", 5)
    check("30 min" in p and "you still had" not in p, "expired time is not carried over")
    page("hidE", "aa:bb:cc:00:00:0d", "connect", "hyper", landing="yes")
    check(roll()["aa:bb:cc:00:00:0d"][5] == "30" and roll()["aa:bb:cc:00:00:0d"][0] != "dead-0000", "a new session replaces the expired line")

    # ---- the roll: lock, concurrency, purge ------------------------------------------------------------------------------------
    roll_write()
    os.makedirs(f"{tmp}/roll.lock")
    open(f"{tmp}/roll.lock/ts", "w").write(str(int(time.time()) - 60))
    rc, out = lib('flash_mint aa:bb:cc:00:00:20 w100 hyper 5 30 0 0 0; echo $M_MODE')
    check(rc == 0 and out == "new" and "aa:bb:cc:00:00:20" in roll(), "a stale lock is broken")
    procs2 = [subprocess.Popen(["sh", "-c", f". {LIB}; flash_mint aa:bb:cc:00:00:21 wc{i} hyper 5 30 0 0 0"], env=env) for i in range(5)]
    for pr in procs2:
        pr.wait()
    check(roll()["aa:bb:cc:00:00:21"][5] == "150" and roll()["aa:bb:cc:00:00:21"][13] == "25" and len([l for l in open(ROLL) if "00:21" in l]) == 1,
          "five simultaneous payments from one device are all counted, once each: " + str(roll().get("aa:bb:cc:00:00:21")))
    rc, out = lib('flash_mint aa:bb:cc:00:00:21 wc1 hyper 5 30 0 0 0; echo $M_MODE')
    check(out == "topup" or out == "dup", "mint of a different window id is a top-up")
    rc, out = lib('flash_mint aa:bb:cc:00:00:21 wc4 hyper 5 30 0 0 0; echo $M_MODE')
    roll_write(f"old-0000,0,0,0,0,10,{int(time.time()) - 400000},aa:bb:cc:00:00:30,0,0,0,hyper,w,5",
               f"pau-0000,0,0,0,0,10,{int(time.time()) - 400000},aa:bb:cc:00:00:31,1,{int(time.time()) - 4 * 86400},300,endurance,w,10",
               f"pau-1111,0,0,0,0,10,{int(time.time()) - 400000},aa:bb:cc:00:00:32,1,{int(time.time()) - 3600},300,endurance,w,10",
               f"ok-0000,0,0,0,0,600,{int(time.time())},aa:bb:cc:00:00:33,0,0,0,hyper,w,5")
    lib("flash_purge 72")
    check(sorted(roll()) == ["aa:bb:cc:00:00:32", "aa:bb:cc:00:00:33"], "purge removes old sessions and pauses held longer than the limit: " + str(sorted(roll())))
    check(not os.path.exists(f"{tmp}/roll.lock"), "lock released")
    # a roll line from the paper-voucher theme (11 fields) still reads
    roll_write(f"abcd-1234,0,0,0,0,60,{int(time.time())},aa:bb:cc:00:00:40,0,0,0")
    rc, out = lib('flash_peek aa:bb:cc:00:00:40; echo "$P_STATE $R_PLAN"')
    check(out == "running hyper", "an 11-field voucher roll line is understood: " + out)

    # ---- fair use (HyperSpeed only), limit from the manager ---------------------------------------------------------------------
    roll_write(f"fair-0000,0,0,0,0,600,{int(time.time())},{MAC_A},0,0,0,hyper,w,5",
               f"endu-0000,5000,2000,0,0,600,{int(time.time())},{MAC_B},0,0,0,endurance,w,10")
    nds_client(MAC_A, STATE="Authenticated", DL=900, UL=300, SESSION_END=int(time.time()) + 36000)
    nds_client(MAC_B, STATE="Authenticated", DL=900, UL=300, SESSION_END=int(time.time()) + 36000)
    open(f"{NDS}/calls.log", "w").close()
    subprocess.run(["sh", FAIR, "once"], env=env, timeout=60)
    cl = calls()
    check(any(re.match(r"auth aa:bb:cc:00:00:01 \d+ 1000 2000", c) for c in cl), "HyperSpeed over the limit is slowed to 2 Mbit/s down / 1 Mbit/s up: " + str(cl))
    check(not any("00:02" in c for c in cl if c.startswith(("auth", "deauth"))), "Endurance is never touched")
    f = f"{tmp}/flash/fair/{MAC_A.replace(':', '')}"
    check("PHASE=throttled" in open(f).read(), "phase remembered")
    old = open(f).read()
    open(f, "w").write(re.sub(r"PHASE_SINCE=\d+", f"PHASE_SINCE={int(time.time()) - 400}", old))
    nds_client(MAC_A, STATE="Authenticated", DL=10, UL=0, SESSION_END=int(time.time()) + 36000)
    open(f"{NDS}/calls.log", "w").close()
    subprocess.run(["sh", FAIR, "once"], env=env, timeout=60)
    check("PHASE=normal" in open(f).read() and any(re.match(r"auth aa:bb:cc:00:00:01 \d+ 0 0", c) for c in calls()), "speed restored after the slow phase: " + str(calls()))

    # ---- status page ------------------------------------------------------------------------------------------------------------
    roll_write(f"stat-0000,0,0,0,0,600,{int(time.time())},{MAC_C},0,0,0,endurance,w,10")
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 36000)
    open(f"{NDS}/ip_10.9.9.9", "w").write(MAC_C)
    nds_client("aa:bb:cc:00:00:0b")
    open(f"{NDS}/ip_10.9.9.8", "w").write("aa:bb:cc:00:00:0b")

    def status_page(*args, **kw):
        return subprocess.run(["sh", STATUS, *args], env={**env, **kw}, capture_output=True, text=True, timeout=30)
    r = status_page("status", "10.9.9.9")
    check(r.returncode == 0 and "Time Remaining" in r.stdout and "stat-0000" in r.stdout and "PAUSE SESSION" in r.stdout and "ADD TIME" in r.stdout,
          "status page: time, code, pause and add-time buttons: " + r.stderr[-300:] + r.stdout[-300:])
    r = status_page("status", "10.9.9.9", FLASH_ROLL=f"{tmp}/nothing.txt")
    check(r.returncode == 0 and "Time Remaining" in r.stdout and "PAUSE SESSION" not in r.stdout, "no roll line: still a status page, no pause")
    open(f"{tmp}/bin/libopennds.sh", "w").write(f"#!/bin/sh\n[ \"$1\" = tmpfs ] && {{ mkdir -p {tmp}/nds_tmpfs/ndscids; echo 'gatewayaddress=192.168.1.1:2050' > {tmp}/nds_tmpfs/ndscids/ndsinfo; echo {tmp}/nds_tmpfs; }}\n")
    os.chmod(f"{tmp}/bin/libopennds.sh", 0o755)
    r = status_page("err511", "10.9.9.8", LIBOPENNDS=f"{tmp}/bin/libopennds.sh")
    check("CONTINUE TO LOGIN" in r.stdout, "login-needed page")
    check(status_page("nonsense", "10.9.9.9").returncode != 0, "bad input is refused")

    # ---- griefing: devices that keep opening empty coin windows are put on a cooldown -------------------------------------------
    GM, GOOD = "aa:bb:cc:00:00:55", "aa:bb:cc:00:00:56"
    set_box(busy=False, coins_at=[])
    for i in (1, 2):
        sg = sid_of(f"hidG{i}")
        get(f"/start?sid={sg}&plan=hyper&mac={GM}"); time.sleep(1.2)
        get(f"/finish?sid={sg}")
        check(wait_until(lambda: json.loads(get(f"/status?sid={sg}")[1]).get("state") == "done", 15), f"empty window {i} closes")
    r = json.loads(get(f"/start?sid={sid_of('hidG3')}&plan=hyper&mac={GM}")[1])
    check(r.get("error") == "COOLDOWN" and 1 <= r.get("retry", 0) <= 120, f"two empty windows in a row: a cooldown, with how long: {r}")
    p = page("hidG3", GM, "start", "hyper")
    check("Too many empty tries" in p and "Try again" in p, "the portal explains the cooldown")
    r = json.loads(get(f"/start?sid={sid_of('hidG4')}&plan=hyper&mac={GOOD}")[1])
    check(r.get("state") in ("starting", "armed"), f"another device is not affected: {r}")
    get(f"/finish?sid={sid_of('hidG4')}")
    wait_until(lambda: json.loads(get(f"/status?sid={sid_of('hidG4')}")[1]).get("state") == "done", 15)
    set_box(busy=False, coins_at=[0.4])        # a paid window clears a device's empty count
    sg = sid_of("hidG5"); GP = "aa:bb:cc:00:00:57"
    get(f"/start?sid={sid_of('hidG6')}&plan=hyper&mac={GP}"); time.sleep(1.2); get(f"/finish?sid={sid_of('hidG6')}")
    wait_until(lambda: json.loads(get(f"/status?sid={sid_of('hidG6')}")[1]).get("state") == "done", 15)
    check(not os.path.exists(f"{STATE}/empty/{GP.replace(':', '')}"), "a paid window leaves no empty-window record")
    check(os.path.exists(f"{STATE}/empty/{GM.replace(':', '')}"), "the empty windows were recorded for the device that opened them")

    # ---- the live page in a real browser (headless Chromium): one tap, online on the first coin, no reloads ------------------
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        sync_playwright = None
        print("skipped: playwright not installed (live page browser test)")
    if sync_playwright:
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from urllib.parse import parse_qs, urlparse
        import threading
        roll_write(); nds_client(MAC_A)
        hits = []
        BAD_API = {"on": False}
        HID = {"v": "hidBR"}   # a new browser id per run (in a container without init, a finished worker may linger as a zombie)

        class Portal(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                if self.path.startswith("/hang"):   # an event stream that is held open and never delivers
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.flush()
                    time.sleep(25)
                    return
                if not self.path.startswith("/opennds_preauth/"):   # e.g. the browser asking for /favicon.ico
                    self.send_response(404); self.end_headers(); return
                q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
                hits.append(q.get("coinact", ""))
                html = page(HID["v"], MAC_A, q.get("coinact", ""), q.get("coinplan", ""))
                if BAD_API["on"]:   # the coin API cannot be reached: the page must fall back to the regular pages
                    html = html.replace(f"SP={STREAM_PORT}", "SP=9")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                try:
                    self.wfile.write(html.encode())
                except BrokenPipeError:
                    pass

        psrv = ThreadingHTTPServer(("127.0.0.1", 18121), Portal)
        threading.Thread(target=psrv.serve_forever, daemon=True).start()
        try:
            with sync_playwright() as pw:
                chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
                br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
                pg = br.new_page()
                errors = []
                pg.on("pageerror", lambda e: errors.append(str(e)))
                pg.goto("http://127.0.0.1:18121/opennds_preauth/?fas=ABC")
                set_box(busy=False, coins_at=[0.6, 1.0])
                t0 = time.time()
                pg.click(".coin")
                pg.wait_for_selector("#lon", state="visible", timeout=10000)
                t_on = time.time() - t0
                shown = pg.text_content("#lpes")
                pg.wait_for_selector("#lfin", state="visible", timeout=20000)
                dom = pg.content()
                check(not errors, f"no script errors: {errors}")
                check(hits == [""], f"one page only: no portal reloads after the first ({hits})")
                check(t_on < 3.5, f"the page says online about a second after the first coin ({t_on:.2f} s)")
                check(re.search(r'id="lcode">[a-z0-9]{4}-[a-z0-9]{4}<', dom) and "12 min" in dom and "Continue browsing" in dom,
                      "the final view shows the time and the restore code: " + re.sub(r"\s+", " ", pg.text_content("#live") or "")[:300])
                check(nds_get(MAC_A)["STATE"] == "Authenticated", "and the device really is online")
                # Done closes the window early
                roll_write(); nds_client(MAC_A); hits.clear(); HID["v"] = "hidBR2"
                pg.goto("http://127.0.0.1:18121/opennds_preauth/?fas=ABC")
                set_box(busy=False, coins_at=[0.6])
                pg.click(".coin")
                pg.wait_for_selector("#ldone", state="visible", timeout=10000)
                t0 = time.time()
                pg.click("#ldone")
                pg.wait_for_selector("#lfin", state="visible", timeout=10000)
                check(time.time() - t0 < 4, f"Done closes the window at once ({time.time() - t0:.1f} s, the idle wait is 3 s)")
                # a live stream that is held open but never delivers (seen on a phone): the page must still follow the coins
                roll_write(); nds_client(MAC_A); hits.clear(); HID["v"] = "hidBR4"
                pg.goto("http://127.0.0.1:18121/opennds_preauth/?fas=ABC")
                pg.route("**/stream*", lambda route: route.continue_(url="http://127.0.0.1:18121/hang"))
                set_box(busy=False, coins_at=[1.2, 1.6])
                pg.click(".coin")
                pg.wait_for_timeout(150)
                check(pg.is_visible("#live") and "Getting the coin slot ready" in (pg.text_content("#lsub") or ""),
                      "the tap shows at once that the slot is being armed: " + (pg.text_content("#lsub") or ""))
                pg.wait_for_selector("#lcd", state="visible", timeout=8000)
                check(True, "the countdown starts once the slot is armed")
                pg.wait_for_selector("#lon", state="visible", timeout=10000)
                check(pg.text_content("#lpes").strip() in ("\u20b11", "\u20b12"), "coins show without the stream: " + (pg.text_content("#lpes") or ""))
                pg.wait_for_selector("#lfin", state="visible", timeout=20000)
                # no coin API: the regular pages take over
                BAD_API["on"] = True; hits.clear(); roll_write(); nds_client(MAC_A); HID["v"] = "hidBR3"
                pg.goto("http://127.0.0.1:18121/opennds_preauth/?fas=ABC")
                pg.click(".coin")
                pg.wait_for_timeout(2500)
                check("start" in hits, f"without the coin API, Insert Coin falls back to the regular start page ({hits})")
                BAD_API["on"] = False
                get(f"/finish?sid={sid_of('hidBR3')}")
                br.close()
        finally:
            psrv.shutdown()
            psrv.server_close()

    # ---- a restart in the middle of a window: the coins are still credited ------------------------------------------------------
    import hmac as _hm
    def box_call(action, sid):
        n = json.load(urllib.request.urlopen(f"http://127.0.0.1:{BOX_PORT}/challenge"))["nonce"]
        sig = _hm.new(KEY.encode(), f"gw1:{action}:{sid}:{n}".encode(), hashlib.sha256).hexdigest()
        return json.load(urllib.request.urlopen(f"http://127.0.0.1:{BOX_PORT}/{action}?session={sid}&nonce={n}&sig={sig}"))
    nrec = lambda: sum(1 for l in revenue().split("\n") if ",hyper,11,66,new," in l)
    rsid = sid_of("hidX"); MAC_X = "aa:bb:cc:00:00:99"
    def workers_alive():
        for pf in glob.glob(f"{STATE}/*/pid"):
            try:
                os.kill(int(open(pf).read()), 0)
                if open(f"/proc/{open(pf).read().strip()}/stat").read().split(") ")[-1][0] not in "ZX":
                    return True
            except (OSError, ValueError):
                pass
        return False
    wait_until(lambda: not workers_alive(), 40)   # the fake box's coin script is shared: no earlier window may still be counting
    set_box(coins_at=[0.05 * i for i in range(1, 12)], events=False)
    box_call("arm", rsid); time.sleep(1); box_call("release", rsid); time.sleep(1.2)   # coins on the box, router "crashed" before settling
    os.makedirs(f"{DATA}/open", exist_ok=True)
    open(f"{DATA}/open/{rsid}", "w").write(f"{MAC_X} hyper widxx01 0\n")
    roll_write(); n0 = nrec()
    subprocess.run(["sh", LISTENER, "recover"], env=env, timeout=60)
    rx = roll().get(MAC_X, [])
    check(rx and rx[13] == "11" and rx[16] == "1", f"recover: the coins left on the box are credited to the roll: {rx}")
    check(nrec() - n0 == 1 and not os.path.exists(f"{DATA}/open/{rsid}"), f"recover: logged once, record removed ({nrec() - n0}) {[l for l in revenue().split(chr(10)) if ',hyper,11,66,' in l]}")
    check(box_call("status", rsid)["pulses"] == 0, "recover: the box was acknowledged")
    subprocess.run(["sh", LISTENER, "recover"], env=env, timeout=60)
    check(nrec() - n0 == 1, "recover again does nothing")

    r = subprocess.run(["sh", LISTENER, "reconcile"], env=env, capture_output=True, text=True, timeout=30)
    check(r.returncode == 0 and r.stdout.startswith("RECONCILE OK"), "reconcile: ledger at or below the box's count is fine: " + r.stdout.strip())
    # ---- ledger chain and clock clamp ---------------------------------------------------------------------------------------------
    rc, out = lib("flash_verify")
    check(rc == 0 and out.startswith("OK "), "revenue chain verifies: " + out)
    lines = revenue().strip().split("\n")
    check(all(len(l.split(",")) == 6 for l in lines), "every revenue line carries a hash")
    f0 = lines[0].split(",")
    f0[2] = str(int(f0[2]) + 90)
    open(REV, "w").write("\n".join([",".join(f0)] + lines[1:]) + "\n")
    rc, out = lib("flash_verify")
    check(rc != 0 and out == "BAD 1", "an edited line is detected: " + out)
    r = subprocess.run(["sh", LISTENER, "reconcile"], env=env, capture_output=True, text=True, timeout=30)
    check(r.returncode == 3 and "BADLEDGER" in r.stdout, "reconcile reports a broken ledger: " + r.stdout.strip())
    roll_write(f"CLOCK1,1000,1000,0,0,10,{int(time.time()) + 3000},{MAC_A},0,0,0,hyper,w9,1,10,1,1")
    rc, out = lib(f'roll_parse "$(roll_find_mac {MAC_A})"; roll_calc; echo $R_LEFT')
    check(out == "600", "a clock that jumped back cannot add time: left " + out)

    # ---- terms -------------------------------------------------------------------------------------------------------------------
    check("Terms of Service" in page("hidA", MAC_A, terms="yes"), "terms page")
finally:
    for pr in procs:
        pr.kill()
    for pidfile in glob.glob(f"{STATE}/*/pid"):
        try:
            os.kill(int(open(pidfile).read()), signal.SIGTERM)
        except (OSError, ValueError):
            pass

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
