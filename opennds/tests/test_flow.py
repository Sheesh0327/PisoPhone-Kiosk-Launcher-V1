"""End-to-end test of the OpenNDS coin-slot integration without a router or an ESP32.

Real pieces under test: theme_coinslot.sh (run the way libopennds.sh runs it, helpers stubbed) and
coinslot-listener.sh (handler, worker, fair use, vouchers, revenue). Stand-ins: fakebox.py (the ESP32 gateway API
with the same HMAC/nonce rules), fake_ndsctl.sh (openNDS' ndsctl) and fake_socat.py (socat).
Run with:  python3 opennds/tests/test_flow.py"""
import glob, hashlib, json, os, re, signal, subprocess, sys, tempfile, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
THEME, LISTENER = f"{ROOT}/theme_coinslot.sh", f"{ROOT}/coinslot-listener.sh"
KEY = "test-gateway-key-123456"
BOX_PORT, LISTEN_PORT = 18090, 18099
tmp = tempfile.mkdtemp()
CTL, LOG, STATE, DATA, NDS = f"{tmp}/ctl.json", f"{tmp}/box.log", f"{tmp}/state", f"{tmp}/data", f"{tmp}/nds"
os.makedirs(NDS)
open(LOG, "w").close()
failures = checks = 0
MAC_A, MAC_B, MAC_C = "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:03"


def check(cond, msg):
    global failures, checks
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def set_box(**kw):
    json.dump(kw, open(CTL, "w"))


def nds_client(mac, **kw):
    """Create/overwrite a fake openNDS client."""
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


conf = f"{tmp}/coinslot.conf"
open(conf, "w").write(
    f"GW_BOX=127.0.0.1:{BOX_PORT}\nGW_KEY={KEY}\nSTATE_DIR={STATE}\nDATA_DIR={DATA}\nNDSCTL={HERE}/fake_ndsctl.sh\n"
    "COIN_FIRST_WAIT_SECONDS=4\nCOIN_IDLE_WAIT_SECONDS=3\nCOIN_MAX_SECONDS=12\n"
    "FAIR_USE_KB=1000\nFAIR_THROTTLE_DOWN_KBPS=2000\nFAIR_THROTTLE_UP_KBPS=1000\nFAIR_THROTTLE_MINUTES=5\nFAIR_FULL_MINUTES=2\n")
env = dict(os.environ, FAKEBOX_CTL=CTL, FAKEBOX_LOG=LOG, FAKEBOX_KEY=KEY, COINSLOT_CONF=conf, FAKE_NDS_DIR=NDS,
           NDSCTL=f"{HERE}/fake_ndsctl.sh")
set_box(busy=False, coins_at=[])
procs = [subprocess.Popen([sys.executable, f"{HERE}/fakebox.py", str(BOX_PORT)], env=env),
         subprocess.Popen([sys.executable, f"{HERE}/fake_socat.py", str(LISTEN_PORT), LISTENER], env=env)]
time.sleep(1.0)


def page(hid, mac, coinact="", plan="", landing="", port=LISTEN_PORT, auth_ok="1", vcode="", statusvar="", terms=""):
    e = dict(env, HID=hid, MAC=mac, COINACT=coinact, COINPLAN=plan, LANDING=landing, PORT=str(port), AUTH_OK=auth_ok,
             VCODE=vcode, STATUSVAR=statusvar, TERMS=terms)
    return subprocess.run(["bash", f"{HERE}/theme_harness.sh", THEME], env=e, capture_output=True, text=True, timeout=90).stdout


def listener(*args):
    return subprocess.run(["sh", LISTENER, *args], env=env, capture_output=True, text=True, timeout=60).stdout.strip()


def sid_of(hid):
    return hashlib.sha256(hid.encode()).hexdigest()[:32]


def get(path):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{LISTEN_PORT}{path}", timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def pay(hid, mac, plan, n_coins, coin_times=None):
    """Drive the customer pages: start, wait, finish. Returns the result page."""
    set_box(busy=False, coins_at=coin_times if coin_times is not None else [0.5] * n_coins)
    p = page(hid, mac, "start", plan)
    time.sleep(1.6)
    page(hid, mac, "wait", plan)
    p = page(hid, mac, "finish", plan)
    for _ in range(25):
        if "counting" not in p.lower():
            break
        time.sleep(1)
        p = page(hid, mac, "finish", plan)
    return p


def code_from(p):
    m = re.search(r'class="code">([A-Z2-9]{8})<', p)
    return m.group(1) if m else None


try:
    # ---- rates engine -------------------------------------------------------------------------------------------
    rates = {("hyper", 5): 30, ("hyper", 10): 60, ("hyper", 20): 120, ("hyper", 3): 18, ("hyper", 17): 102,
             ("endurance", 1): 15, ("endurance", 2): 30, ("endurance", 5): 180, ("endurance", 10): 480,
             ("endurance", 20): 1440, ("endurance", 17): 690, ("endurance", 25): 1620, ("endurance", 30): 1920}
    for (plan, pesos), minutes in rates.items():
        check(listener("minutes", plan, str(pesos)) == str(minutes), f"{plan} {pesos} pesos = {minutes} min")

    # ---- welcome page ---------------------------------------------------------------------------------------------
    nds_client(MAC_A)
    p = page("hidA", MAC_A)
    check("HyperSpeed" in p and "Endurance" in p and "Insert Coin" in p, "welcome shows both plans and Insert Coin")
    for needle in ["&#8369;5</span><span>30 min", "&#8369;10</span><span>1 hr", "&#8369;20</span><span>2 hrs",
                   "&#8369;1</span><span>15 min", "&#8369;5</span><span>3 hrs", "&#8369;10</span><span>8 hrs", "&#8369;20</span><span>24 hrs"]:
        check(needle in p, f"rate row present: {needle}")
    check("up to 5 Mbps down / 2 Mbps up" in p.lower() or "Up to 5 Mbps down / 2 Mbps up" in p, "Endurance speed caps shown")
    check('value="hyper" checked' in p, "HyperSpeed preselected")
    check("refresh" not in p.lower(), "welcome does not auto-refresh")
    check(len(p) < 6500, f"welcome page is small ({len(p)} bytes)")

    # ---- Endurance: 17 pesos accumulate to 11 hrs 30 min -----------------------------------------------------------
    p = pay("hidA", MAC_A, "endurance", 17)
    check("11 hrs 30 min" in p and "&#8369;17" in p, "result: P17 Endurance = 11 hrs 30 min")
    check("AUTHCALL" not in p, "no access before Connect")
    p = page("hidA", MAC_A, "connect", "endurance", landing="yes")
    check("AUTHCALL sessiontimeout=690 quotas=690 2000 5000 0 0" in p, "Endurance caps 2 Mbps up / 5 Mbps down in the grant: " + p[-400:])
    code = code_from(p)
    check(code is not None, "voucher code shown after paying")
    check(any(l.startswith("ack ") and sid_of("hidA") in l for l in open(LOG).read().split("\n")), "coins acknowledged on the box after access")
    check("endurance,17,690,new" in open(f"{DATA}/revenue.csv").read(), "revenue logged")
    check(nds_get(MAC_A)["STATE"] == "Authenticated", "client is authenticated")

    # ---- other customers cannot take these coins ------------------------------------------------------------------
    set_box(busy=False, coins_at=[0.5] * 2)
    page("hidX", MAC_B, "start", "hyper"); time.sleep(1.5)
    page("hidX", MAC_B, "finish", "hyper"); time.sleep(2)
    p = page("hidY", MAC_B, "connect", "hyper", landing="yes")
    check("AUTHCALL" not in p and "could not start your session" in p, "a different client id cannot claim someone else's coins")
    nds_client(MAC_B)

    # ---- status page and top-up ------------------------------------------------------------------------------------
    p = page("hidA", MAC_A, statusvar="authenticated")
    check("You are connected" in p and "11 hr" in p and code in p and "Add time" in p, "status page: time left, voucher, top-up button")
    p = page("hidA2", MAC_A, "start", "hyper", statusvar="authenticated")
    check("still have Endurance time" in p, "other plan refused while time is left")
    p = pay("hidA3", MAC_A, "endurance", 2)
    check("&#8369;2 = 30 min + 11 hrs" in p and "Add time" in p, "top-up result keeps the remaining time: " + re.sub(r"\s+", " ", p)[-500:])
    p = page("hidA3", MAC_A, "connect", "endurance", landing="yes", statusvar="authenticated")
    check("Connected" in p and code_from(p) == code, "top-up keeps the same voucher code")
    check(any(re.match(r"auth aa:bb:cc:00:00:01 72[01] 2000 5000 0 0", c) for c in calls()), "top-up re-granted ~12 hr with Endurance caps: " + str([c for c in calls() if c.startswith("auth")]))
    check("endurance,2,30,topup" in open(f"{DATA}/revenue.csv").read(), "top-up logged as revenue")

    # ---- voucher on another device, time moves ---------------------------------------------------------------------
    nds_client(MAC_B)
    p = page("hidB", MAC_B, "vform"); check("voucher code" in p.lower(), "voucher form")
    p = page("hidB", MAC_B, "voucher", vcode="ZZZZZZZZ")
    check("not accepted" in p, "wrong code rejected")
    p = page("hidB", MAC_B, "voucher", vcode=code)
    check("Time left on your voucher" in p, "valid code accepted: " + re.sub(r"\s+", " ", p)[-300:])
    p = page("hidB", MAC_B, "connect", "endurance", landing="yes")
    check("AUTHCALL sessiontimeout=7" in p, "voucher grants the remaining ~12 hr (" + (re.search(r"sessiontimeout=\d+", p) or [""])[0] + ")")
    check(any(c == "deauth aa:bb:cc:00:00:01" for c in calls()), "the old device is disconnected (time moved)")

    # ---- a disconnected device with paid time is welcomed back ------------------------------------------------------
    nds_client(MAC_B)  # B drops off
    p = page("hidB", MAC_B)
    check("Time left on your voucher" in p, "returning device is offered its remaining time")

    # ---- HyperSpeed: no caps, then fair use -------------------------------------------------------------------------
    nds_client(MAC_C)
    p = pay("hidC", MAC_C, "hyper", 10)
    check("1 hr" in p, "Hyper 10 pesos = 1 hr")
    p = page("hidC", MAC_C, "connect", "hyper", landing="yes")
    check("AUTHCALL sessiontimeout=60 quotas=60 0 0 0 0" in p, "HyperSpeed has no speed caps")
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check(any(re.match(r"auth aa:bb:cc:00:00:03 5\d 1000 2000 0 0", c) for c in calls()), "over the limit: throttled (1 Mbps up / 2 Mbps down): " + str([c for c in calls() if "00:03" in c]))
    fair = open(f"{STATE}/fair/aabbcc000003").read()
    check("PHASE=throttled" in fair, "fair-use phase recorded")
    f = open(f"{STATE}/fair/aabbcc000003").read().replace("PHASE_SINCE=" + re.search(r"PHASE_SINCE=(\d+)", fair).group(1), f"PHASE_SINCE={int(time.time()) - 400}")
    open(f"{STATE}/fair/aabbcc000003", "w").write(f)
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check("PHASE=normal" in open(f"{STATE}/fair/aabbcc000003").read(), "after 5 minutes the throttle lifts (intermittent)")
    f = open(f"{STATE}/fair/aabbcc000003").read()
    open(f"{STATE}/fair/aabbcc000003", "w").write(re.sub(r"PHASE_SINCE=\d+", f"PHASE_SINCE={int(time.time()) - 200}", f))
    nds_client(MAC_C, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=900, UL=300)
    listener("fairuse-once")
    check("PHASE=throttled" in open(f"{STATE}/fair/aabbcc000003").read(), "and it throttles again after the 2 minute break")
    me = json.loads(get(f"/me?mac={MAC_C}")[1])
    check(me["plan"] == "hyper" and me["throttled"] is True, "status reports the fair-use slowdown")

    # ---- Endurance is never fair-use throttled -------------------------------------------------------------------------
    before = len(calls())
    nds_client(MAC_B, STATE="Authenticated", SESSION_END=int(time.time()) + 3000, DL=9999999, UL=9999999)
    listener("fairuse-once")
    check(not any("00:02" in c and c.startswith("auth") for c in calls()[before:]), "Endurance clients are not throttled")

    # ---- zero coins, busy slot, offline -----------------------------------------------------------------------------
    nds_client(MAC_C)
    set_box(busy=False, coins_at=[])
    page("hidD", MAC_C, "start", "hyper"); time.sleep(1.5)
    for _ in range(25):
        p = page("hidD", MAC_C, "finish", "hyper")
        if "No coins detected" in p: break
        time.sleep(1)
    check("No coins detected" in p, "no coins, no access")
    set_box(busy=True, coins_at=[])
    p = page("hidE", MAC_C, "start", "hyper")
    for _ in range(5):
        if "Coin slot is busy" in p: break
        time.sleep(0.5); p = page("hidE", MAC_C, "start", "hyper")
    check("Coin slot is busy" in p and 'content="5; url=' in p and "coinact=start" in p, "busy page retries start")
    p = page("hidF", MAC_C, port=9)
    check("offline" in p.lower(), "listener down: friendly offline page")

    # ---- window extension: a coin restarts the short wait ---------------------------------------------------------
    set_box(busy=False, coins_at=[2.5])  # first wait is 4 s: this coin arrives late, the 3 s idle wait then applies
    t0 = time.time()
    page("hidG", MAC_C, "start", "hyper")
    for _ in range(30):
        st = json.loads(get(f"/status?sid={sid_of('hidG')}")[1])
        if st["state"] == "done": break
        time.sleep(0.5)
    took = time.time() - t0
    check(st["pulses"] == 1 and 5.0 <= took <= 12.0, f"window extended after a coin (took {took:.1f}s, pulses {st['pulses']})")

    # ---- reports and hardening ---------------------------------------------------------------------------------------
    rep = listener("report", "7")
    check("endurance" in rep and "hyper" in rep and "Total last 7 day(s): PHP" in rep, "revenue report: " + rep.replace("\n", " | "))
    check(get("/status?sid=abc;rm")[0] == 400, "bad sid rejected")
    check(get("/status?sid=" + "g" * 32)[0] == 400, "non-hex sid rejected")
    check(get(f"/start?sid={'a' * 32}&plan=evil&mac={MAC_A}")[0] == 400, "bad plan rejected")
    check(get(f"/claim?sid={'a' * 32}&mac=zz")[0] == 400, "bad mac rejected")
    check(get("/nothing?sid=" + "a" * 32)[0] == 404, "unknown path 404")
    for _ in range(11):
        get(f"/voucher?sid={'b' * 32}&mac={MAC_B}&code=ZZZZZZZZ")
    check("TOO_MANY_TRIES" in get(f"/voucher?sid={'b' * 32}&mac={MAC_B}&code=ZZZZZZZZ")[1], "voucher guessing is rate limited")
    p = page("hidA", MAC_A, terms="yes")
    check("Terms of Service" in p, "terms page")
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
