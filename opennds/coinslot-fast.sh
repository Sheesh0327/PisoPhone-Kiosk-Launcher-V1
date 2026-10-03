#!/bin/sh
# Coin-slot manager, MicroPython edition: the same local API, files and behaviour as coinslot-listener.sh, as ONE resident
# process instead of a new shell (plus curl/socat/awk) per request. The shell version stays; use either, never both.
#
#   coinslot-fast.sh serve            local API (127.0.0.1:LISTEN_PORT), live stream (STREAM_PORT), fair-use watcher
#   coinslot-fast.sh minutes <plan> <pesos>      what an amount buys
#   coinslot-fast.sh report [days]               revenue per day and plan
#   coinslot-fast.sh box                         which box this router uses and whether it answers
#   coinslot-fast.sh hmac <message>              (tests) the request signature for GW_KEY
#
# Needs:  opkg install micropython        (Python 3 syntax subset, ~400 KB; no curl, openssl, awk or per-request forks)
# Why it is faster: one process holds the sessions in memory, signs requests with a precomputed key block, talks to the box
# with its own HTTP client, and pushes changes to the stream the moment they happen (no polling between processes).
# Why it is safer: strict input validation (no shell sees user input), request size/time limits, a connection cap, constant
# state in files with the same names and formats as the shell version, the key never logged, a private state directory.
# Settings: the same as the shell version (UCI /etc/config/coinslot, section "main", or the old /etc/coinslot.conf).
# To switch, change the service command in /etc/init.d/coinslot from coinslot-listener.sh to coinslot-fast.sh (see
# INSTRUCTIONS.md "Faster: the MicroPython edition"); the portal theme and the status page work unchanged with either.
MICROPYTHON="${MICROPYTHON:-micropython}"
command -v "$MICROPYTHON" > /dev/null 2>&1 || { echo "micropython not found: opkg install micropython" >&2; exit 1; }
COINSLOT_SELF="$0" exec "$MICROPYTHON" -c "$(sed '1,/^#__PYTHON__$/d' "$0")" "$@"
exit 1
#__PYTHON__
import sys, os, time, json, hashlib
try:
    import asyncio
except ImportError:
    import uasyncio as asyncio

# ----------------------------------------------------------------------------------------------------------------------
# Settings (same names as the shell version)
# ----------------------------------------------------------------------------------------------------------------------
DEFAULTS = {
    "GW_BOX": "192.168.1.10", "GW_KEY": "", "GW_DISCOVER": "1", "GW_BOX_MAC": "", "DISCOVER_PORT": "8888",
    "DISCOVER_IFACE": "", "DISCOVER_COOLDOWN": "30", "LISTEN_PORT": "8099", "STATE_DIR": "/tmp/coinslot",
    "DATA_DIR": "/etc/coinslot.d", "COIN_FIRST_WAIT_SECONDS": "30", "COIN_IDLE_WAIT_SECONDS": "15",
    "COIN_MAX_SECONDS": "115", "COIN_POLL_SECONDS": "0.1", "STREAM_PORT": "8100", "STREAM_BIND": "0.0.0.0",
    "STREAM_MAX_CLIENTS": "16", "STREAM_MAX_SECONDS": "600", "QUEUE_CLAIM_SECONDS": "30",
    "HYPER_TIERS": "5:30 10:60 20:120", "HYPER_PRORATA_MIN": "6", "ENDURANCE_TIERS": "1:15 5:180 10:480 20:1440",
    "ENDURANCE_DOWN_KBPS": "5000", "ENDURANCE_UP_KBPS": "2000", "PAUSE_MIN_PESOS": "10", "PAUSE_MAX_HOURS": "72",
    "FAIR_USE_GB": "5", "FAIR_THROTTLE_DOWN_KBPS": "2000", "FAIR_THROTTLE_UP_KBPS": "1000",
    "FAIR_THROTTLE_MINUTES": "5", "FAIR_FULL_MINUTES": "2",
}
S = dict(DEFAULTS)
HOOKS = {}  # test hooks, read from the settings file only: DISCOVER_CMD, FAIR_USE_KB
UCI = os.getenv("UCI") or "uci"
NDSCTL = os.getenv("NDSCTL") or "ndsctl"
SELF = os.getenv("COINSLOT_SELF") or "coinslot-fast.sh"
_tmpn = 0


def q(s):  # shell-quote (only validated values ever reach a shell; this is the second line of defence)
    return "'" + str(s).replace("'", "'\\''") + "'"


def now():
    return int(time.time())


def read_text(path):
    # Never open() a file that may not exist: in some MicroPython builds a failed open() leaves a half-made file object
    # whose garbage collection closes file descriptor 0, and sockets then reuse that number and break each other.
    try:
        os.stat(path)
    except OSError:
        return None
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return None


def write_text(path, text):  # atomic: a half-written state file is never seen
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.rename(tmp, path)


def exists(path):
    try:
        os.stat(path)
        return True
    except OSError:
        return False


def isdir(path):
    try:
        return bool(os.stat(path)[0] & 0x4000)
    except OSError:
        return False


def mkdirs(path):
    cur = ""
    for part in path.split("/"):
        if not part:
            continue
        cur += "/" + part
        if not isdir(cur):
            try:
                os.mkdir(cur)
            except OSError:
                pass


def rm(path):
    try:
        os.remove(path)
    except OSError:
        pass


def rmtree(path):
    try:
        for n in os.listdir(path):
            p = path + "/" + n
            if isdir(p):
                rmtree(p)
            else:
                rm(p)
        os.rmdir(path)
    except OSError:
        pass


def listdir(path):
    try:
        return os.listdir(path)
    except OSError:
        return []


def kv_parse(text):
    out = {}
    for line in (text or "").split("\n"):
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


async def run(cmd, timeout=30):
    """Run one command line (only built from validated pieces); returns (exit code, output).

    The command runs in the background and the loop keeps serving while it works: ndsctl and friends can take
    hundreds of milliseconds on a router, and a blocking call would freeze every pushed update meanwhile."""
    global _tmpn
    _tmpn += 1
    out = S["STATE_DIR"] + "/.run%d" % _tmpn
    done = out + ".rc"
    os.system("( %s > %s 2>/dev/null; echo $? > %s ) < /dev/null > /dev/null 2>&1 &" % (cmd, q(out), q(done)))
    waited = 0
    delay = 5
    rc = 124  # the shell convention for "timed out"
    while waited < timeout * 1000:
        await asyncio.sleep_ms(delay)
        waited += delay
        code = read_text(done)
        if code and code.endswith("\n"):
            rc = int(code) if code.strip().isdigit() else 1
            break
        delay = min(delay * 2, 40)
    text = read_text(out) or ""
    rm(out)
    rm(done)
    return rc, text


def load_settings():
    for k in S:  # environment first (handy for tests); the settings file and UCI override it
        v = os.getenv(k)
        if v:
            S[k] = v
    conf = os.getenv("COINSLOT_CONF") or "/etc/coinslot.conf"
    text = read_text(conf)
    if text:
        for line in text.split("\n"):
            line = line.strip()
            if not line or line[0] == "#" or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.split(" #")[0].strip() if v.strip()[:1] not in ("'", '"') else v.strip()
            if v[:1] in ("'", '"') and v[-1:] == v[:1] and len(v) >= 2:
                v = v[1:-1]
            if k in S and v != "":
                S[k] = v
            elif k in ("DISCOVER_CMD", "FAIR_USE_KB"):
                HOOKS[k] = v
    os.system("%s -q show coinslot.main > /tmp/.coinslot_uci.%d 2>/dev/null" % (q(UCI), os.getpid() if hasattr(os, "getpid") else 0))
    path = "/tmp/.coinslot_uci.%d" % (os.getpid() if hasattr(os, "getpid") else 0)
    text = read_text(path)
    rm(path)
    for line in (text or "").split("\n"):
        if line.startswith("coinslot.main.") and "=" in line:
            k, v = line[14:].split("=", 1)
            k = k.upper()
            v = v.strip()
            if len(v) >= 2 and v[0] == "'" and v[-1] == "'" and "'" not in v[1:-1]:
                v = v[1:-1]
                if k in S and v != "":
                    S[k] = v


def num(name, default=0):
    try:
        return int(S[name])
    except ValueError:
        return default


def fnum(name, default=1.0):
    try:
        return float(S[name])
    except ValueError:
        return default


# ----------------------------------------------------------------------------------------------------------------------
# Validation (nothing from a request is used until it passed one of these)
# ----------------------------------------------------------------------------------------------------------------------
HEX = "0123456789abcdef"
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def all_in(s, alphabet):
    for c in s:
        if c not in alphabet:
            return False
    return True


def valid_sid(s):
    return len(s) == 32 and all_in(s, HEX)


def valid_plan(s):
    return s in ("hyper", "endurance")


def valid_mac(s):
    if len(s) != 17:
        return False
    for i in range(17):
        c = s[i]
        if i % 3 == 2:
            if c != ":":
                return False
        elif c not in "0123456789abcdefABCDEF":
            return False
    return True


def valid_code(s):
    return len(s) == 8 and all_in(s, CODE_ALPHABET)


def norm_mac(s):
    return s.lower()


def mac_key(s):
    return s.lower().replace(":", "")


def key_to_mac(k):
    return ":".join(k[i:i + 2] for i in range(0, 12, 2))


# ----------------------------------------------------------------------------------------------------------------------
# Rates
# ----------------------------------------------------------------------------------------------------------------------
def tiers_for(plan):
    if plan == "hyper":
        t = S["HYPER_TIERS"]
        if S["HYPER_PRORATA_MIN"]:
            t += " 1:" + S["HYPER_PRORATA_MIN"]
        return t
    return S["ENDURANCE_TIERS"]


def minutes_for(plan, pesos):
    tiers = []
    for item in tiers_for(plan).split():
        try:
            a, b = item.split(":")
            tiers.append((int(a), int(b)))
        except ValueError:
            pass
    best = [0] * (pesos + 1)
    for x in range(1, pesos + 1):
        for cost, mins in tiers:
            if 0 < cost <= x and best[x - cost] + mins > best[x]:
                best[x] = best[x - cost] + mins
    return best[pesos]


def plan_up(plan):
    return num("ENDURANCE_UP_KBPS") if plan == "endurance" else 0


def plan_down(plan):
    return num("ENDURANCE_DOWN_KBPS") if plan == "endurance" else 0


# ----------------------------------------------------------------------------------------------------------------------
# Request signing: HMAC-SHA256 with the key block computed once
# ----------------------------------------------------------------------------------------------------------------------
IPAD = OPAD = None


def hexs(b):
    return "".join("%02x" % c for c in b)


def hmac_prepare(key):
    global IPAD, OPAD
    k = key.encode() if isinstance(key, str) else key
    if len(k) > 64:
        k = hashlib.sha256(k).digest()
    k = k + bytes(64 - len(k))
    IPAD = bytes(b ^ 0x36 for b in k)
    OPAD = bytes(b ^ 0x5c for b in k)


def hmac_hex(msg):
    h = hashlib.sha256(IPAD)
    h.update(msg.encode())
    o = hashlib.sha256(OPAD)
    o.update(h.digest())
    return hexs(o.digest())


def hmac_selftest():
    hmac_prepare("key")
    ok = hmac_hex("The quick brown fox jumps over the lazy dog") == "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
    hmac_prepare(S["GW_KEY"])
    return ok


# ----------------------------------------------------------------------------------------------------------------------
# HTTP client for the box (plain http, short timeouts, bounded reads)
# ----------------------------------------------------------------------------------------------------------------------
async def http_get(hostport, path, timeout=8):
    host, _, port = hostport.partition(":")
    w = None
    try:
        r, w = await asyncio.wait_for(asyncio.open_connection(host, int(port or 80)), timeout)
        w.write(("GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n" % (path, host)).encode())
        await w.drain()
        data = b""
        while len(data) < 65536:
            try:
                chunk = await asyncio.wait_for(r.read(2048), timeout)
            except OSError:
                break
            if not chunk:
                break
            data += chunk
        i = data.find(b"\r\n\r\n")
        body = data[i + 4:] if i >= 0 else b""
        return body.decode()
    except Exception as e:
        debug(e)
        return None
    finally:
        if w is not None:
            await aclose(w)


async def aclose(w):
    """Really close a stream, exactly once.

    MicroPython's close() alone does nothing (the socket closes in wait_closed()), and closing the same socket twice
    closes its file descriptor number twice: if a new connection has reused that number by then, the new connection is
    the one that breaks (EBADF on its first write). So every stream is closed once and a second call is a no-op."""
    if w in CLOSED:
        return
    CLOSED.append(w)
    if len(CLOSED) > 64:  # only recent streams can still be around; keep the list small
        del CLOSED[0]
    try:
        w.close()
        await w.wait_closed()
    except Exception:
        pass


CLOSED = []


def jparse(text):
    try:
        v = json.loads(text)
        return v if isinstance(v, dict) else None
    except Exception:
        return None


def box_addr():
    return (read_text(S["STATE_DIR"] + "/box_addr") or "").strip() or S["GW_BOX"]


async def discover_box():
    """Find the box again if it moved (layout A). Probes at most once per DISCOVER_COOLDOWN; accepts one valid answer."""
    if S["GW_DISCOVER"] != "1":
        return False
    last = int(read_text(S["STATE_DIR"] + "/box_probe") or 0)
    if now() - last < num("DISCOVER_COOLDOWN", 30):
        return False
    write_text(S["STATE_DIR"] + "/box_probe", str(now()))
    cmd = HOOKS.get("DISCOVER_CMD")
    if cmd:
        rc, reply = await run(cmd)
    else:
        opt = ""
        iface = S["DISCOVER_IFACE"]
        if iface and all_in(iface, "abcdefghijklmnopqrstuvwxyz0123456789-_."):
            opt = ",so-bindtodevice=" + iface
        rc, reply = await run("printf '{\"type\":\"PISOPHONE_DISCOVER\"}' | socat -T3 - UDP4-DATAGRAM:255.255.255.255:%d,broadcast%s" % (num("DISCOVER_PORT", 8888), opt))
    d = jparse(reply)
    if not d or d.get("type") != "PISOPHONE_ESP32_RESPONSE":
        return False
    ip = str(d.get("ip", ""))
    parts = ip.split(".")
    if len(parts) != 4 or not all(p.isdigit() and int(p) < 256 for p in parts) or not all_in(ip, "0123456789."):
        return False
    want = S["GW_BOX_MAC"]
    if want and mac_key(str(d.get("mac", ""))) != mac_key(want):
        return False
    port = d.get("port")
    if isinstance(port, int) and port not in (0, 80):
        ip = "%s:%d" % (ip, port)
    write_text(S["STATE_DIR"] + "/box_addr", ip)
    return True


async def box_call(sid, action, extra=""):
    """Fetch a one-time nonce, sign, send. Returns the box's JSON as a dict (success false + error on failure)."""
    base = "/api/gateway/"
    nonce = None
    for attempt in (0, 1):
        d = jparse(await http_get(box_addr(), base + "challenge"))
        n = str(d.get("nonce", "")) if d else ""
        if n and all_in(n, HEX):
            nonce = n
            break
        if attempt == 0 and not await discover_box():
            break
    if not nonce:
        return {"success": False, "error": "NO_NONCE"}
    sig = hmac_hex("gw1:%s:%s:%s" % (action, sid, nonce))
    d = jparse(await http_get(box_addr(), "%s%s?session=%s&nonce=%s&sig=%s%s" % (base, action, sid, nonce, sig, extra)))
    return d if d is not None else {"success": False, "error": "NO_ANSWER"}


# ----------------------------------------------------------------------------------------------------------------------
# openNDS (ndsctl)
# ----------------------------------------------------------------------------------------------------------------------
async def nds_json(who):
    if not (valid_mac(who) or (who and all_in(who, "0123456789."))):
        return ""
    return (await run("%s json %s" % (q(NDSCTL), q(who))))[1]


def jfield(text, name):
    """First "name":"value" (or number) of an ndsctl json text."""
    key = '"%s":' % name
    i = text.find(key)
    if i < 0:
        return ""
    rest = text[i + len(key):].lstrip()
    if rest[:1] == '"':
        j = rest.find('"', 1)
        return rest[1:j] if j > 0 else ""
    j = 0
    while j < len(rest) and rest[j] not in ",}\n ":
        j += 1
    return rest[:j]


async def nds_state(mac):
    return jfield(await nds_json(mac), "state")


async def nds_session_end(mac):
    return jfield(await nds_json(mac), "session_end")


async def nds_counters_kb(mac):
    t = await nds_json(mac)

    def n(v):
        return int(v) if v.isdigit() else 0
    return n(jfield(t, "download_this_session")) + n(jfield(t, "upload_this_session"))


async def nds_auth(mac, minutes, up, down):
    if not valid_mac(mac):
        return False
    out = (await run("%s auth %s %d %d %d 0 0" % (q(NDSCTL), q(mac), int(minutes), int(up), int(down))))[1]
    return "Failed" not in out and "authenticated" in out


async def nds_deauth(mac):
    if valid_mac(mac):
        await run("%s deauth %s" % (q(NDSCTL), q(mac)))


async def nds_regrant(mac, minutes, up, down):
    await nds_deauth(mac)
    return await nds_auth(mac, minutes, up, down)


LOGCHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .:=_-/"


def debug(e):
    if os.getenv("COINSLOT_DEBUG"):
        sys.print_exception(e)


def logmsg(text):
    safe = "".join(c if c in LOGCHARS else "?" for c in str(text))[:160]
    os.system("logger -t coinslot -- %s > /dev/null 2>&1 &" % q(safe))


# ----------------------------------------------------------------------------------------------------------------------
# Vouchers: $DATA_DIR/vouchers/<CODE>  (same file format as the shell version)
# ----------------------------------------------------------------------------------------------------------------------
def vdir():
    return S["DATA_DIR"] + "/vouchers"


VFIELDS = ("PLAN", "EXPIRES", "MAC", "CREATED", "PESOS", "PAUSED", "PAUSE_LEFT", "PAUSE_UNTIL", "PAUSE_USED")


def load_voucher(code):
    if not valid_code(code):
        return None
    t = read_text(vdir() + "/" + code)
    if t is None:
        return None
    d = kv_parse(t)
    v = {"PLAN": d.get("PLAN", ""), "MAC": d.get("MAC", "")}
    for k in VFIELDS:
        if k not in ("PLAN", "MAC"):
            try:
                v[k] = int(d.get(k, "0") or 0)
            except ValueError:
                v[k] = 0
    return v


def save_voucher(code, v):
    mkdirs(vdir())
    write_text(vdir() + "/" + code, "".join("%s=%s\n" % (k, v.get(k, 0 if k not in ("PLAN", "MAC") else "")) for k in VFIELDS))


def voucher_left(v):
    """Seconds of paid time left (0 if none); a paused voucher keeps its time frozen until PAUSE_UNTIL."""
    n = now()
    if v["PAUSED"] == 1 and v["PAUSE_UNTIL"] > n:
        return v["PAUSE_LEFT"]
    if v["PAUSED"] == 1:
        return 0
    return v["EXPIRES"] - n if v["EXPIRES"] > n else 0


def voucher_by_mac(mk):
    for code in listdir(vdir()):
        if not valid_code(code):
            continue
        v = load_voucher(code)
        if v and v["MAC"] == mk and voucher_left(v) > 0:
            return code, v
    return None, None


def new_code():
    while True:
        raw = os.urandom(16)
        code = "".join(CODE_ALPHABET[b % 32] for b in raw[:8])  # 32 symbols: no modulo bias
        if not exists(vdir() + "/" + code):
            return code


def can_pause(v):
    return v["PLAN"] == "endurance" and v["PESOS"] >= num("PAUSE_MIN_PESOS", 10) and v["PAUSE_USED"] != 1 and v["PAUSED"] != 1


# ----------------------------------------------------------------------------------------------------------------------
# Sessions: in memory, written through to $STATE_DIR/<sid>/ with the shell version's file names
# ----------------------------------------------------------------------------------------------------------------------
SESS = {}
MAX_SESSIONS = 400


def sdir(sid):
    return S["STATE_DIR"] + "/" + sid


def get_sess(sid, create=False):
    s = SESS.get(sid)
    if s is not None:
        return s
    d = sdir(sid)
    if isdir(d):
        st = kv_parse(read_text(d + "/state"))
        s = {"state": st.get("STATE", "none"), "pulses": int(st.get("PULSES", "0") or 0), "remaining": int(st.get("REMAINING", "0") or 0),
             "error": st.get("ERROR", ""), "plan": (read_text(d + "/plan") or "").strip(), "mac": (read_text(d + "/mac") or "").strip(),
             "claimed": exists(d + "/claimed"), "stop": False, "task": None, "busy": False, "seen": 0}
        SESS[sid] = s
        return s
    if not create:
        return None
    if len(SESS) >= MAX_SESSIONS:
        for k in list(SESS):  # drop the finished, oldest-first, to make room
            if SESS[k]["task"] is None and SESS[k]["state"] in ("done", "error", "none"):
                del SESS[k]
                if len(SESS) < MAX_SESSIONS:
                    break
        if len(SESS) >= MAX_SESSIONS:
            return None
    mkdirs(d)
    s = {"state": "none", "pulses": 0, "remaining": 0, "error": "", "plan": "", "mac": "", "claimed": False, "stop": False,
         "task": None, "busy": False, "seen": 0}
    SESS[sid] = s
    return s


def put_state(sid, s, state=None, pulses=None, remaining=None, error=None):
    if state is not None:
        s["state"] = state
    if pulses is not None:
        s["pulses"] = pulses
    if remaining is not None:
        s["remaining"] = remaining
    if error is not None:
        s["error"] = error
    s["ver"] = s.get("ver", 0) + 1
    write_text(sdir(sid) + "/state", "STATE=%s\nPULSES=%d\nREMAINING=%d\nERROR=%s\n" % (s["state"], s["pulses"], s["remaining"], s["error"]))


def status_dict(s):
    plan = s["plan"]
    mins = minutes_for(plan, s["pulses"]) if plan and s["pulses"] > 0 else 0
    return {"state": s["state"], "pulses": s["pulses"], "minutes": mins, "plan": plan, "remaining": s["remaining"],
            "claimed": s["claimed"], "error": s["error"]}


def jdump(d):
    return json.dumps(d, separators=(",", ":"))


# ----------------------------------------------------------------------------------------------------------------------
# Worker: arm, count (waiting longer after every coin), always disarm
# ----------------------------------------------------------------------------------------------------------------------
async def nap(sec):
    await asyncio.sleep(sec)


async def worker(sid):
    s = SESS[sid]
    poll = max(fnum("COIN_POLL_SECONDS", 0.1), 0.02)
    first, idle, mx = num("COIN_FIRST_WAIT_SECONDS", 30), num("COIN_IDLE_WAIT_SECONDS", 15), num("COIN_MAX_SECONDS", 115)
    armed = False
    pulses = 0
    try:
        started = now()
        cap = started + mx
        deadline = started + first
        ans = await box_call(sid, "arm", "&duration=%d" % (first + 3))
        if ans.get("success") is not True:
            err = str(ans.get("error", "NO_ANSWER"))
            put_state(sid, s, "error", 0, 0, err)
            logmsg("window %s could not arm: %s" % (sid[:8], err))
            return
        armed = True
        # The box ignores coin pulses while the acceptor settles after power-on (ready_in_ms): the customer is invited
        # to insert coins, and the countdown starts, only after that.
        settle = ans.get("ready_in_ms", 0)
        if not isinstance(settle, int) or isinstance(settle, bool) or settle < 0 or settle > 5000:
            settle = -1  # not a settling time this software knows: do not wait on it
        if settle < 0:
            r = await box_call(sid, "release")
            armed = r.get("success") is not True and r.get("error") == "NO_ANSWER"
            put_state(sid, s, "error", 0, 0, "BAD_SETTLE_TIME")
            return
        if settle > 0:
            await asyncio.sleep_ms(settle)
            deadline = now() + first
            cap = now() + mx
            await box_call(sid, "arm", "&duration=%d" % (first + 3))  # the box's own timer starts from here too
        pulses = int(ans.get("pulses", 0) or 0)
        last = pulses
        put_state(sid, s, "armed", pulses, deadline - now(), "")
        while now() < deadline and not s["stop"]:
            await nap(poll)
            st = await box_call(sid, "status")
            if st.get("success") is not True:
                continue  # a missed poll must not end the window early
            pulses = int(st.get("pulses", 0) or 0)
            if pulses > last:  # a coin: restart the short wait, within the hard cap
                last = pulses
                deadline = min(now() + idle, cap)
                await box_call(sid, "arm", "&duration=%d" % (deadline - now() + 3))
            rem = deadline - now()
            if pulses != s["pulses"] or rem != s["remaining"]:
                put_state(sid, s, "armed", pulses, rem, "")
            if st.get("state") != "armed":
                break  # the box ended it
        for _ in range(3):  # one lost packet must not leave the acceptor powered
            r = await box_call(sid, "release")
            if r.get("success") is True or r.get("error") != "NO_ANSWER":
                break
            await nap(1)
        armed = False
        drain_end = now() + 30  # in-flight coins: the box reports "idle" once it has drained
        while now() < drain_end:
            st = await box_call(sid, "status")
            if st.get("success") is True:
                pulses = int(st.get("pulses", 0) or 0)
                if st.get("state") == "idle":
                    break
            await nap(poll)
        put_state(sid, s, "done", pulses, 0, "")
    except Exception as e:  # never leave the acceptor powered or the customer waiting
        debug(e)
        try:
            if armed:
                await box_call(sid, "release")
        except Exception:
            pass
        put_state(sid, s, "done" if pulses else "error", pulses, 0, "" if pulses else "INTERNAL")
        logmsg("worker %s failed: %s" % (sid[:8], type(e).__name__))
    finally:
        s["task"] = None


# ----------------------------------------------------------------------------------------------------------------------
# Grants
# ----------------------------------------------------------------------------------------------------------------------
async def build_grant(sid, mac):
    """What can be granted now (coins, voucher or resume), without changing anything. Returns a dict or None."""
    s = get_sess(sid)
    if s is None:
        return None
    d = sdir(sid)
    mk = mac_key(mac)
    g = {"KIND": "", "PLAN": "", "PULSES": 0, "NEW_MIN": 0, "LEFT_MIN": 0, "TOTAL_MIN": 0, "MODE": "auth", "CODE": "", "OLDMAC": "",
         "PAUSED": 0, "FORFEIT": 0, "OLDCODE": ""}
    grant = kv_parse(read_text(d + "/grant"))
    if s["state"] == "done" and s["pulses"] > 0 and not s["claimed"]:
        g["KIND"], g["PLAN"], g["PULSES"] = "coins", s["plan"], s["pulses"]
        if not valid_plan(g["PLAN"]):
            return None
        g["NEW_MIN"] = minutes_for(g["PLAN"], g["PULSES"])
        code, v = voucher_by_mac(mk)
        g["CODE"] = code or ""
        fo = (read_text(d + "/forfeit") or "").strip()
        if fo:
            g["FORFEIT"], g["OLDCODE"], g["CODE"] = 1, fo if valid_code(fo) else "", ""
        if await nds_state(mac) == "Authenticated":  # connected: this is a top-up
            g["MODE"] = "topup"
            end = await nds_session_end(mac)
            end = int(end) if end.isdigit() else 0
            if end > now() and g["FORFEIT"] != 1:
                g["LEFT_MIN"] = (end - now() + 59) // 60
        elif g["CODE"]:  # time left from an earlier session
            v = load_voucher(g["CODE"])
            if v and v["PLAN"] == g["PLAN"] and voucher_left(v) > 0:
                g["LEFT_MIN"] = (voucher_left(v) + 59) // 60
        g["TOTAL_MIN"] = g["NEW_MIN"] + g["LEFT_MIN"]
    elif grant and not s["claimed"]:
        g["KIND"], g["PLAN"] = grant.get("KIND", ""), grant.get("PLAN", "")
        g["TOTAL_MIN"] = int(grant.get("MINUTES", "0") or 0)
        g["CODE"], g["OLDMAC"] = grant.get("CODE", ""), grant.get("OLDMAC", "")
        g["PAUSED"] = int(grant.get("PAUSEDFLAG", "0") or 0)
        if not valid_plan(g["PLAN"]):
            return None
    else:
        return None
    g["UP"], g["DOWN"] = plan_up(g["PLAN"]), plan_down(g["PLAN"])
    return g


def grant_json(g):
    return jdump({"kind": g["KIND"], "pulses": g["PULSES"], "minutes": g["TOTAL_MIN"], "added": g["NEW_MIN"], "plan": g["PLAN"],
                  "mode": g["MODE"], "voucher": g["CODE"], "up": g["UP"], "down": g["DOWN"], "paused": g["PAUSED"], "forfeit": g["FORFEIT"]})


def save_pending(sid, g):
    write_text(sdir(sid) + "/pending", "".join("%s=%s\n" % (k, g[k]) for k in sorted(g)))


def load_pending(sid):
    s = get_sess(sid)
    if s is None or s["claimed"]:
        return None
    t = read_text(sdir(sid) + "/pending")
    if t is None:
        return None
    d = kv_parse(t)
    g = {}
    for k, v in d.items():
        g[k] = int(v) if k in ("PULSES", "NEW_MIN", "LEFT_MIN", "TOTAL_MIN", "UP", "DOWN", "PAUSED", "FORFEIT") and v.lstrip("-").isdigit() else v
    return g if g.get("PLAN") in ("hyper", "endurance") else None


# fair-use files: $STATE_DIR/fair/<mackey>: USED_KB OFFSET_KB PHASE PHASE_SINCE
def fair_file(mk):
    return S["STATE_DIR"] + "/fair/" + mk


def fair_load(mk):
    d = kv_parse(read_text(fair_file(mk)))
    return {"USED_KB": int(d.get("USED_KB", "0") or 0), "OFFSET_KB": int(d.get("OFFSET_KB", "0") or 0), "PHASE": d.get("PHASE", "normal"),
            "PHASE_SINCE": int(d.get("PHASE_SINCE", "0") or 0)}


def fair_save(mk, used, offset, phase, since):
    mkdirs(S["STATE_DIR"] + "/fair")
    write_text(fair_file(mk), "USED_KB=%d\nOFFSET_KB=%d\nPHASE=%s\nPHASE_SINCE=%d\n" % (used, offset, phase, since))


def fair_init(mk):
    if not exists(fair_file(mk)):
        fair_save(mk, 0, 0, "normal", 0)


async def finalize(sid, mac, g):
    """Record a grant that openNDS has accepted: voucher, revenue line, then acknowledge the coins on the box."""
    d = sdir(sid)
    mk = mac_key(mac)
    n = now()
    code = g["CODE"]
    v = load_voucher(code) if code else None
    if v is None:
        code = new_code()
        v = {"PLAN": "", "EXPIRES": 0, "MAC": "", "CREATED": n, "PESOS": 0, "PAUSED": 0, "PAUSE_LEFT": 0, "PAUSE_UNTIL": 0, "PAUSE_USED": 0}
    expires = n + g["TOTAL_MIN"] * 60
    v["PLAN"], v["MAC"], v["PESOS"], v["EXPIRES"], v["PAUSED"] = g["PLAN"], mk, v["PESOS"] + g["PULSES"], expires, 0
    save_voucher(code, v)
    s = get_sess(sid)
    s["claimed"] = True
    write_text(d + "/claimed", "")
    rm(d + "/pending")
    rm(d + "/forfeit")
    kind = "topup" if g["MODE"] == "topup" else "new"
    if g["FORFEIT"] == 1:  # the old plan's time (and its voucher) is gone
        kind = "switch"
        if g.get("OLDCODE") and valid_code(g["OLDCODE"]):
            rm(vdir() + "/" + g["OLDCODE"])
        if g["MODE"] != "topup":
            rm(fair_file(mk))
    if g["KIND"] == "coins":
        mkdirs(S["DATA_DIR"])
        with open(S["DATA_DIR"] + "/revenue.csv", "a") as f:
            f.write("%d,%s,%d,%d,%s\n" % (n, g["PLAN"], g["PULSES"], g["NEW_MIN"], kind))
        write_text(d + "/ackpending", "")
        for _ in range(3):
            r = await box_call(sid, "ack")
            if r.get("success") is True:
                rm(d + "/ackpending")
                break
            await nap(1)
    elif g["OLDMAC"] and g["OLDMAC"] != mk:
        old = key_to_mac(g["OLDMAC"]) if len(g["OLDMAC"]) == 12 and all_in(g["OLDMAC"], HEX) else ""
        if old and await nds_state(old) == "Authenticated":
            await nds_deauth(old)  # the time moves to this device
    fair_init(mk)
    g["CODE"], g["EXPIRES"] = code, expires


# ----------------------------------------------------------------------------------------------------------------------
# Waiting line for a busy coin slot (in memory: a ticket nobody refreshed for 6 s is dropped)
# ----------------------------------------------------------------------------------------------------------------------
QUEUE = {}
QSEQ = 0


def q_prune():
    n = now()
    for k in list(QUEUE):
        if n - QUEUE[k]["seen"] > 6:
            del QUEUE[k]


def q_order():
    return sorted(QUEUE, key=lambda k: QUEUE[k]["seq"])


def q_gate(sid):
    """May this customer start now? Not while somebody else is first in line; starting leaves the line."""
    q_prune()
    order = q_order()
    if order and order[0] != sid:
        return False
    QUEUE.pop(sid, None)
    return True


# ----------------------------------------------------------------------------------------------------------------------
# Revenue report
# ----------------------------------------------------------------------------------------------------------------------
async def do_report(days):
    text = read_text(S["DATA_DIR"] + "/revenue.csv")
    if text is None:
        return "No payments recorded yet."
    tz = (await run("date +%z"))[1].strip() or "+0000"
    sign = -1 if tz[:1] == "-" else 1
    off = sign * (int(tz[1:3]) * 3600 + int(tz[3:5]) * 60) if len(tz) >= 5 and tz[1:5].isdigit() else 0
    cutoff = now() - days * 86400
    rows, total = {}, 0
    for line in text.split("\n"):
        p = line.split(",")
        if len(p) < 3 or not p[0].isdigit() or int(p[0]) < cutoff:
            continue
        t = time.gmtime(int(p[0]) + off)
        k = ("%04d-%02d-%02d" % (t[0], t[1], t[2]), p[1])
        r = rows.get(k, [0, 0])
        r[0] += int(p[2]) if p[2].isdigit() else 0
        r[1] += 1
        rows[k] = r
        total += int(p[2]) if p[2].isdigit() else 0
    out = ["%s  %-10s  PHP %-6d  %d payment(s)" % (k[0], k[1], rows[k][0], rows[k][1]) for k in sorted(rows)]
    out.append("Total last %d day(s): PHP %d" % (days, total))
    return "\n".join(out)


# ----------------------------------------------------------------------------------------------------------------------
# HTTP server: strict, bounded, one request per connection
# ----------------------------------------------------------------------------------------------------------------------
STATUS_TEXT = {200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed", 409: "Conflict", 503: "Service Unavailable"}
QCHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:._-"


def reply_bytes(code, body, ctype="application/json"):
    b = body.encode()
    return ("HTTP/1.1 %d %s\r\nContent-Type: %s\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: %d\r\n\r\n"
            % (code, STATUS_TEXT.get(code, "OK"), ctype, len(b))).encode() + b


def err(code, name):
    return code, jdump({"error": name})


def parse_query(qs):
    out = {}
    if not qs:
        return out
    for part in qs.split("&"):
        k, _, v = part.partition("=")
        if k and all_in(k, "abcdefghijklmnopqrstuvwxyz_") and len(v) <= 64 and all_in(v, QCHARS):
            out[k] = v
        else:
            return None  # anything unexpected invalidates the request
    return out


async def read_request(r):
    line = await asyncio.wait_for(r.readline(), 5)
    if not line or len(line) > 1024:
        return None
    parts = line.decode().strip().split(" ")
    if len(parts) != 3:
        return None
    hdr = {}
    for _ in range(40):  # only a few headers matter (WebSocket handshake); the rest are skipped within a bound
        h = await asyncio.wait_for(r.readline(), 5)
        if h in (b"\r\n", b"\n"):
            return parts[0], parts[1], hdr
        if not h or len(h) > 2048:
            return None
        k, _, v = h.decode().partition(":")
        k = k.strip().lower()
        if k in ("upgrade", "sec-websocket-key", "sec-websocket-version", "origin", "host"):
            hdr[k] = v.strip()
    return None


async def start_window(sid, plan, mac, forfeit_ok):
    """Open a coin window (idempotent while one is running). Returns the status dict, or an error dict."""
    s = get_sess(sid, create=True)
    if s is None:
        return {"error": "BUSY"}
    if mac:
        s["mac"] = mac
        write_text(sdir(sid) + "/mac", mac)  # the live stream only talks to this device
    if s["task"] is not None:
        return status_dict(s)
    if s["state"] == "done" and s["pulses"] > 0 and not s["claimed"]:  # coins not turned into access yet are never discarded
        return status_dict(s)
    forfeit = ""
    if mac:
        code, v = voucher_by_mac(mac_key(mac))
        if code and v["PLAN"] != plan:
            if forfeit_ok:
                forfeit = code
            else:
                return {"state": "error", "error": "PLAN_MISMATCH", "plan": v["PLAN"], "remaining": voucher_left(v)}
    d = sdir(sid)
    if exists(d + "/ackpending"):  # an earlier grant could not be acknowledged on the box
        r = await box_call(sid, "ack")
        if r.get("success") is True:
            rm(d + "/ackpending")
        else:
            return {"state": "error", "error": "ACK_PENDING"}
    if not q_gate(sid):  # someone is queued ahead
        return {"state": "error", "error": "SLOT_BUSY"}
    for f in ("claimed", "state", "grant", "pending", "forfeit"):
        rm(d + "/" + f)
    s["claimed"], s["stop"], s["plan"] = False, False, plan
    if forfeit:
        write_text(d + "/forfeit", forfeit)
    write_text(d + "/plan", plan)
    put_state(sid, s, "starting", 0, num("COIN_FIRST_WAIT_SECONDS", 30), "")
    logmsg("start %s plan=%s" % (sid[:8], plan))
    s["task"] = asyncio.create_task(worker(sid))
    return status_dict(s)


def finish_window(sid):
    s = get_sess(sid)
    if s is not None and s["task"] is not None:
        s["stop"] = True
    logmsg("finish %s" % sid[:8])
    return status_dict(s) if s else NONE_STATUS


NONE_STATUS = {"state": "none", "pulses": 0, "minutes": 0, "plan": "", "remaining": 0, "claimed": False, "error": ""}


async def api(path, qd):
    """Route one local API call. Returns (status code, body[, content type])."""
    if path == "/info":
        return 200, jdump({"first": num("COIN_FIRST_WAIT_SECONDS"), "idle": num("COIN_IDLE_WAIT_SECONDS"), "max": num("COIN_MAX_SECONDS"),
                           "fair_gb": num("FAIR_USE_GB"), "e_down": num("ENDURANCE_DOWN_KBPS"), "e_up": num("ENDURANCE_UP_KBPS"),
                           "pause_pesos": num("PAUSE_MIN_PESOS"), "pause_hours": num("PAUSE_MAX_HOURS"), "stream_port": num("STREAM_PORT")})
    if path == "/tiers":
        plan = qd.get("plan", "")
        if not valid_plan(plan):
            return err(400, "INVALID_PLAN")
        tiers = S["HYPER_TIERS"] if plan == "hyper" else S["ENDURANCE_TIERS"]
        return 200, "".join("%s %s\n" % tuple(t.split(":")) for t in tiers.split() if ":" in t), "text/plain"
    if path == "/report":
        d = qd.get("days", "7")
        return 200, await do_report(int(d) if d.isdigit() else 7), "text/plain"
    if path == "/me":
        mac = qd.get("mac", "")
        if not valid_mac(mac):
            return err(400, "INVALID_MAC")
        mac = norm_mac(mac)
        mk = mac_key(mac)
        state = await nds_state(mac)
        active, remaining, plan, code, thr, used, cp = False, 0, "", "", False, 0, False
        c, v = voucher_by_mac(mk)
        if c:
            code, plan, remaining = c, v["PLAN"], voucher_left(v)
            cp = state == "Authenticated" and can_pause(v)
            if exists(fair_file(mk)):
                f = fair_load(mk)
                thr = f["PHASE"] == "throttled"
                used = (f["USED_KB"] + await nds_counters_kb(mac) - f["OFFSET_KB"]) // 1024
        active = state == "Authenticated"
        return 200, jdump({"active": active, "plan": plan, "remaining": remaining, "voucher": code, "throttled": thr, "used_mb": used,
                           "fair_mb": num("FAIR_USE_GB") * 1024, "can_pause": cp})
    if path == "/pause":
        mac = qd.get("mac", "")
        if not valid_mac(mac):
            return err(400, "INVALID_MAC")
        mac = norm_mac(mac)
        mk = mac_key(mac)
        n = now()
        if await nds_state(mac) != "Authenticated":
            return 200, jdump({"error": "NOT_CONNECTED"})
        code, v = voucher_by_mac(mk)
        if not code:
            return 200, jdump({"error": "NO_SESSION"})
        if not (v["PLAN"] == "endurance" and v["PESOS"] >= num("PAUSE_MIN_PESOS", 10)):
            return 200, jdump({"error": "NOT_ELIGIBLE"})
        if v["PAUSE_USED"] == 1:
            return 200, jdump({"error": "ALREADY_USED"})
        end = await nds_session_end(mac)
        end = int(end) if end.isdigit() else v["EXPIRES"]
        left = end - n
        if left <= 0:
            return 200, jdump({"error": "NO_SESSION"})
        v["PAUSED"], v["PAUSE_LEFT"], v["PAUSE_UNTIL"], v["PAUSE_USED"], v["EXPIRES"] = 1, left, n + num("PAUSE_MAX_HOURS", 72) * 3600, 1, n
        save_voucher(code, v)
        await nds_deauth(mac)
        return 200, jdump({"success": True, "left": left, "until": v["PAUSE_UNTIL"], "voucher": code})

    sid = qd.get("sid", "")
    if not valid_sid(sid):
        return err(400, "INVALID_SID")
    mac = qd.get("mac", "")
    if mac:
        if not valid_mac(mac):
            return err(400, "INVALID_MAC")
        mac = norm_mac(mac)
    if path == "/start":
        plan = qd.get("plan", "")
        if not valid_plan(plan):
            return err(400, "INVALID_PLAN")
        res = await start_window(sid, plan, mac, qd.get("forfeit") == "1")
        return (503, jdump(res)) if res.get("error") == "BUSY" and "state" not in res else (200, jdump(res))
    s = get_sess(sid)
    if path == "/status":
        return 200, jdump(status_dict(s) if s else NONE_STATUS)
    if path == "/finish":
        return 200, jdump(finish_window(sid))
    if path == "/claim":
        if not mac:
            return err(400, "INVALID_MAC")
        g = await build_grant(sid, mac)
        if g:
            save_pending(sid, g)
            logmsg("claim %s %s %dmin mode=%s" % (sid[:8], mac, g["TOTAL_MIN"], g["MODE"]))
            return 200, grant_json(g)
        logmsg("claim %s %s: nothing to grant" % (sid[:8], mac))
        return 200, '{"kind":"","pulses":0,"minutes":0,"added":0,"plan":"","mode":"auth","voucher":"","up":0,"down":0}'
    if path in ("/confirm", "/apply"):
        if not mac:
            return err(400, "INVALID_MAC")
        s = get_sess(sid, create=True)
        if s is None:
            return 503, jdump({"error": "BUSY"})
        if s["busy"]:
            return err(409, "BUSY")
        s["busy"] = True
        try:
            g = load_pending(sid) or await build_grant(sid, mac)
            if not g:
                logmsg("confirm %s %s: nothing to grant" % (sid[:8], mac))
                return 200, jdump({"error": "NOTHING_TO_GRANT"})
            if path == "/apply":
                if g["MODE"] != "topup":
                    return 200, jdump({"error": "NOT_CONNECTED"})
                if not await nds_regrant(mac, g["TOTAL_MIN"], g["UP"], g["DOWN"]):
                    logmsg("top-up %s %s: openNDS refused the re-grant" % (sid[:8], mac))
                    return 200, jdump({"error": "REGRANT_FAILED"})
                if g["PLAN"] == "hyper":
                    mk = mac_key(mac)
                    fair_init(mk)
                    f = fair_load(mk)
                    fair_save(mk, 0 if g["FORFEIT"] == 1 else f["USED_KB"], await nds_counters_kb(mac), "normal", 0)
            await finalize(sid, mac, g)
            logmsg("granted %s %s %dmin plan=%s mode=%s" % (sid[:8], mac, g["TOTAL_MIN"], g["PLAN"], g["MODE"]))
            return 200, jdump({"success": True, "voucher": g["CODE"], "minutes": g["TOTAL_MIN"], "plan": g["PLAN"], "expires": g["EXPIRES"], "mode": g["MODE"]})
        finally:
            s["busy"] = False
    if path == "/voucher":
        if not mac:
            return err(400, "INVALID_MAC")
        code = qd.get("code", "").upper()
        fails = S["STATE_DIR"] + "/voucher-fails"
        n = now()
        recent = [int(x) for x in (read_text(fails) or "").split() if x.isdigit() and int(x) > n - 600]
        if len(recent) >= 10:  # guess protection: 10 wrong codes in 10 minutes -> wait
            return 200, jdump({"error": "TOO_MANY_TRIES"})
        v = load_voucher(code)
        if v is None or voucher_left(v) <= 0:
            with open(fails, "a") as f:
                f.write("%d\n" % n)
            return 200, jdump({"error": "INVALID_CODE"})
        if await nds_state(mac) == "Authenticated":
            return 200, jdump({"error": "ALREADY_CONNECTED"})
        s = get_sess(sid, create=True)
        if s is None:
            return 503, jdump({"error": "BUSY"})
        d = sdir(sid)
        s["claimed"] = False
        rm(d + "/claimed")
        rm(d + "/pending")
        write_text(d + "/grant", "KIND=voucher\nPLAN=%s\nMINUTES=%d\nCODE=%s\nOLDMAC=%s\nPAUSEDFLAG=%d\n" % (v["PLAN"], (voucher_left(v) + 59) // 60, code, v["MAC"], v["PAUSED"]))
        g = await build_grant(sid, mac)
        return (200, grant_json(g)) if g else (200, jdump({"error": "INVALID_CODE"}))
    if path == "/resume":
        if not mac:
            return err(400, "INVALID_MAC")
        if await nds_state(mac) == "Authenticated":
            return 200, '{"minutes":0}'
        code, v = voucher_by_mac(mac_key(mac))
        if code:
            s = get_sess(sid, create=True)
            if s is None:
                return 503, jdump({"error": "BUSY"})
            d = sdir(sid)
            s["claimed"] = False
            rm(d + "/claimed")
            rm(d + "/pending")
            write_text(d + "/grant", "KIND=resume\nPLAN=%s\nMINUTES=%d\nCODE=%s\nOLDMAC=\nPAUSEDFLAG=%d\n" % (v["PLAN"], (voucher_left(v) + 59) // 60, code, v["PAUSED"]))
            g = await build_grant(sid, mac)
            if g:
                return 200, grant_json(g)
        return 200, '{"minutes":0}'
    return err(404, "NOT_FOUND")


CONNS = 0
MAX_API_CONNS = 64


async def handle_api(r, w):
    global CONNS
    if CONNS >= MAX_API_CONNS:
        try:
            w.write(reply_bytes(503, jdump({"error": "BUSY"})))
            await w.drain()
        except Exception:
            pass
        await aclose(w)
        return
    CONNS += 1
    try:
        req = await read_request(r)
        if req is None:
            out = reply_bytes(400, jdump({"error": "BAD_REQUEST"}))
        elif req[0] != "GET":
            out = reply_bytes(405, jdump({"error": "METHOD"}))
        else:
            path, _, qs = req[1].partition("?")
            qd = parse_query(qs)
            if qd is None:
                out = reply_bytes(400, jdump({"error": "BAD_REQUEST"}))
            else:
                res = await api(path, qd)
                out = reply_bytes(res[0], res[1], res[2] if len(res) > 2 else "application/json")
        w.write(out)
        await w.drain()
    except Exception as e:
        debug(e)
        try:  # a failed request is answered, never left hanging
            w.write(reply_bytes(503, jdump({"error": "INTERNAL"})))
            await w.drain()
        except Exception:
            pass
    finally:
        CONNS -= 1
        await aclose(w)


# ----------------------------------------------------------------------------------------------------------------------
# Live stream (Server-Sent Events): read-only, only for the device that started the session
# ----------------------------------------------------------------------------------------------------------------------
STREAMS = 0


def peer_ip(w):
    try:
        a = w.get_extra_info("peername")
        if isinstance(a, (bytes, bytearray)) and len(a) >= 8:  # MicroPython: raw sockaddr_in
            return "%d.%d.%d.%d" % (a[4], a[5], a[6], a[7])
        if isinstance(a, tuple):
            return str(a[0])
    except Exception:
        pass
    return ""


def arp_mac(ip):
    if not ip or not all_in(ip, "0123456789."):
        return ""
    t = read_text(os.getenv("ARP_FILE") or "/proc/net/arp") or ""
    for line in t.split("\n")[1:]:
        p = line.split()
        if len(p) >= 4 and p[0] == ip:
            return p[3].lower()
    return ""


def sse(event, data):
    return ("event: %s\ndata: %s\n\n" % (event, data)).encode()


# One set of pushers serves both transports: emit(event, dict) writes either an SSE event or a WebSocket frame.
async def push_status(emit, sid, stop_when_done):
    prev, quiet = None, 0
    end = now() + num("STREAM_MAX_SECONDS", 600)
    while now() < end:
        s = get_sess(sid)
        cur = (s["state"], s["pulses"], s["remaining"], s["error"]) if s else ("none", 0, 0, "")
        if cur != prev:
            prev, quiet = cur, 0
            await emit("status", status_dict(s) if s else NONE_STATUS)
            if stop_when_done and cur[0] in ("done", "error", "none"):
                return
        else:
            quiet += 1
            if quiet >= 100:  # ~5 s: keep the connection alive and notice a closed page
                await emit(None, None)
                quiet = 0
        await nap(0.05)


async def push_queue(emit, sid, tick):
    """Tell a customer who found the slot busy their place in line, then that it is theirs (first come, first served)."""
    global QSEQ
    t = QUEUE.get(sid)
    if t is None:
        QSEQ += 1
        t = QUEUE[sid] = {"seq": QSEQ, "seen": now(), "ready": 0}
    last = None
    end = now() + num("STREAM_MAX_SECONDS", 600)
    claim = num("QUEUE_CLAIM_SECONDS", 30)
    while True:
        n = now()
        if n >= end:
            QUEUE.pop(sid, None)
            return
        if QUEUE.get(sid) is not t:
            s = get_sess(sid)
            if s and s["state"] in ("starting", "armed"):  # taken by /start: the customer is in
                await emit("started", {})
                return
            if t["ready"]:
                await emit("expired", {})
                return
            QSEQ += 1
            t = QUEUE[sid] = {"seq": QSEQ, "seen": n, "ready": 0}  # dropped by mistake: back in line
        t["seen"] = n
        q_prune()
        order = q_order()
        pos = order.index(sid) + 1 if sid in order else 0
        if not t["ready"]:
            if pos == 1:
                st = await box_call(sid, "status")
                free = st.get("slot_free")
                if free is True:
                    t["ready"] = n
                    await emit("ready", {"claim": claim})
                    last = "ready"
                elif free is None and st.get("success") is True:  # an older box cannot say: the page falls back
                    QUEUE.pop(sid, None)
                    await emit("unsupported", {})
                    return
            if last != pos and last != "ready":
                last = pos
                await emit("queue", {"pos": pos})
        elif n - t["ready"] >= claim:
            QUEUE.pop(sid, None)
            await emit("expired", {})
            return
        await tick()
        await nap(0.3)


def owner_ok(sid, w):
    """Only the device that started this session (the MAC the router sees for the connecting address) may listen."""
    s = get_sess(sid)
    owner = s["mac"] if s else ""
    return bool(owner) and owner == arp_mac(peer_ip(w))


async def handle_stream(r, w):
    global STREAMS
    if STREAMS >= num("STREAM_MAX_CLIENTS", 16):
        try:
            w.write(reply_bytes(503, jdump({"error": "BUSY"})))
            await w.drain()
        except Exception:
            pass
        await aclose(w)
        return
    STREAMS += 1
    try:
        req = await read_request(r)
        if req is None or req[0] != "GET":
            w.write(reply_bytes(405 if req else 400, jdump({"error": "BAD_REQUEST"})))
            await w.drain()
            return
        path, _, qs = req[1].partition("?")
        qd = parse_query(qs) or {}
        sid = qd.get("sid", "")
        if path not in ("/stream", "/ws") or not valid_sid(sid):
            w.write(reply_bytes(404 if path not in ("/stream", "/ws") else 400, jdump({"error": "NOT_FOUND"})))
            await w.drain()
            return
        if path == "/ws":
            await handle_ws(r, w, sid, qd, req[2])
            return
        if not owner_ok(sid, w):
            w.write(reply_bytes(403, jdump({"error": "FORBIDDEN"})))
            await w.drain()
            return
        w.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\nretry: 3000\n\n")
        await w.drain()

        async def emit(event, data):
            w.write(sse(event, jdump(data)) if event else b": ping\n\n")
            await w.drain()

        async def tick():
            w.write(b": ping\n\n")  # also notices a closed page
            await w.drain()
        if qd.get("mode") == "queue":
            await push_queue(emit, sid, tick)
        else:
            await push_status(emit, sid, True)
    except Exception as e:
        debug(e)
    finally:
        STREAMS -= 1
        await aclose(w)


# ----------------------------------------------------------------------------------------------------------------------
# WebSocket (RFC 6455, text frames only): one channel per page for pushes AND two commands, start and finish.
# Same guard as the stream, plus: the MAC in the URL must be the connecting device's, the page must come from this
# router (Origin = Host), commands are limited to start/finish with a rate limit, frames are small and unfragmented.
# ----------------------------------------------------------------------------------------------------------------------
WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
try:
    import binascii
    hashlib.sha1
    WS_OK = True
except Exception:
    WS_OK = False


def ws_frame(text, op=0x81):
    b = text.encode() if isinstance(text, str) else text
    n = len(b)
    return bytes([op, n]) + b if n < 126 else bytes([op, 126, n >> 8, n & 255]) + b


async def ws_read(r):
    """One client frame -> (opcode, payload). Anything unexpected (unmasked, fragmented, huge) reads as a close."""
    h = await r.readexactly(2)
    fin, op, masked, n = h[0] & 0x80, h[0] & 0x0f, h[1] & 0x80, h[1] & 0x7f
    if not (fin and masked) or op == 0 or n == 127:
        return 8, b""
    if n == 126:
        e = await r.readexactly(2)
        n = (e[0] << 8) | e[1]
    if n > 512:
        return 8, b""
    mask = await r.readexactly(4)
    data = await r.readexactly(n) if n else b""
    return op, bytes(data[i] ^ mask[i & 3] for i in range(n))


def hostname(h):
    h = h.split("//")[-1].split("/")[0]
    return h.rsplit(":", 1)[0] if ":" in h else h


async def handle_ws(r, w, sid, qd, hdr):
    mac = norm_mac(qd.get("mac", ""))
    org = hdr.get("origin")
    s = get_sess(sid)
    if (not WS_OK or not valid_mac(mac) or arp_mac(peer_ip(w)) != mac or (s and s["mac"] and s["mac"] != mac)
            or (org and hostname(org) != hostname(hdr.get("host", "-"))) or hdr.get("upgrade", "").lower() != "websocket"
            or hdr.get("sec-websocket-version") != "13" or not hdr.get("sec-websocket-key")):
        w.write(reply_bytes(403, jdump({"error": "FORBIDDEN"})))
        await w.drain()
        return
    w.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n"
             % binascii.b2a_base64(hashlib.sha1((hdr["sec-websocket-key"] + WS_GUID).encode()).digest()).decode().strip()).encode())
    await w.drain()

    async def emit(event, data):
        w.write(ws_frame(jdump(dict(data, e=event)) if event else b"", 0x89 if not event else 0x81))
        await w.drain()
        if event == "status" and data.get("error") == "SLOT_BUSY" and tasks["queue"] is None:  # the slot was taken: wait in line
            tasks["queue"] = asyncio.create_task(push_queue(emit, sid, tick))

    async def tick():
        pass
    tasks = {"status": None, "queue": None}
    tasks["status"] = asyncio.create_task(push_status(emit, sid, False))
    sent = []
    try:
        while True:
            op, data = await asyncio.wait_for(ws_read(r), 70)
            if op == 8:
                break
            if op == 9:  # ping -> pong
                w.write(ws_frame(data, 0x8A))
                await w.drain()
                continue
            if op != 1:
                continue
            n = now()
            sent = [t for t in sent if t > n - 10]
            sent.append(n)
            cmd = jparse(data.decode()) if len(sent) <= 10 else None
            if not cmd:
                break  # not JSON, or more than 10 commands in 10 s
            t = cmd.get("t")
            if t == "start" and valid_plan(str(cmd.get("plan", ""))):
                res = await start_window(sid, cmd["plan"], mac, cmd.get("forfeit") == 1)
                await emit("error" if res.get("error") else "status", res)
            elif t == "finish":
                await emit("status", finish_window(sid))
            elif t == "ping":
                await emit("pong", {})
            else:
                break
    except Exception as e:
        debug(e)
    finally:
        for task in tasks.values():
            if task is not None:
                task.cancel()
        QUEUE.pop(sid, None) if tasks["queue"] is not None and not (get_sess(sid) or {}).get("task") else None


# ----------------------------------------------------------------------------------------------------------------------
# Fair use + housekeeping (every 60 s)
# ----------------------------------------------------------------------------------------------------------------------
async def fair_flip(mac, used, phase, down, up):
    end = await nds_session_end(mac)
    if not end.isdigit():
        return False
    mins = (int(end) - now() + 59) // 60
    if mins < 1 or not await nds_regrant(mac, mins, up, down):
        return False
    fair_save(mac_key(mac), used, await nds_counters_kb(mac), phase, now())
    return True


async def fair_tick():
    limit = int(HOOKS.get("FAIR_USE_KB") or 0) or num("FAIR_USE_GB", 5) * 1024 * 1024  # FAIR_USE_KB is a test hook
    text = (await run("%s json" % q(NDSCTL)))[1]
    mac = st = dl = None
    for line in text.split("\n"):
        line = line.strip()
        if '"mac":' in line:
            mac = jfield(line, "mac")
        elif '"state":' in line:
            st = jfield(line, "state")
        elif '"download_this_session":' in line:
            dl = jfield(line, "download_this_session")
        elif '"upload_this_session":' in line:
            ul = jfield(line, "upload_this_session")
            if not (mac and valid_mac(mac)) or st != "Authenticated":
                continue
            mk = mac_key(mac)
            code, v = voucher_by_mac(mk)
            if not code or v["PLAN"] != "hyper":
                continue
            fair_init(mk)
            f = fair_load(mk)
            cur = (int(dl) if dl and dl.isdigit() else 0) + (int(ul) if ul.isdigit() else 0)
            off = f["OFFSET_KB"] if cur >= f["OFFSET_KB"] else 0
            total = f["USED_KB"] + cur - off
            n = now()
            if total < limit:
                fair_save(mk, total, off, f["PHASE"], f["PHASE_SINCE"])
            elif f["PHASE"] == "normal" and n - f["PHASE_SINCE"] >= num("FAIR_FULL_MINUTES", 2) * 60:
                await fair_flip(mac, total, "throttled", num("FAIR_THROTTLE_DOWN_KBPS"), num("FAIR_THROTTLE_UP_KBPS"))
            elif f["PHASE"] == "throttled" and n - f["PHASE_SINCE"] >= num("FAIR_THROTTLE_MINUTES", 5) * 60:
                await fair_flip(mac, total, "normal", 0, 0)
            else:
                fair_save(mk, total, off, f["PHASE"], f["PHASE_SINCE"])


def purge_vouchers():
    cut = now() - 172800
    for code in listdir(vdir()):
        v = load_voucher(code)
        if v and v["EXPIRES"] < cut and (v["PAUSED"] != 1 or v["PAUSE_UNTIL"] < cut):
            rm(vdir() + "/" + code)


def forget_old_sessions():
    base = S["STATE_DIR"]
    cut = now() - 7200
    for name in listdir(base):
        if valid_sid(name) and name not in SESS:
            try:
                if os.stat(base + "/" + name)[8] < cut:
                    rmtree(base + "/" + name)
            except OSError:
                pass
    for k in list(SESS):
        s = SESS[k]
        if s["task"] is None and s["state"] in ("done", "error", "none") and s.get("seen", 0) == 0 and len(SESS) > MAX_SESSIONS // 2:
            del SESS[k]


async def housekeeping():
    while True:
        try:
            await fair_tick()
            purge_vouchers()
            forget_old_sessions()
        except Exception as e:
            logmsg("housekeeping: %s" % type(e).__name__)
        await nap(60)


# ----------------------------------------------------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------------------------------------------------
async def serve():
    mkdirs(S["STATE_DIR"])
    mkdirs(vdir())
    os.system("chmod 700 %s 2>/dev/null" % q(S["STATE_DIR"]))
    if not S["GW_KEY"]:
        print("coinslot: GW_KEY is not set", file=sys.stderr)
    s1 = await asyncio.start_server(handle_api, "127.0.0.1", num("LISTEN_PORT", 8099))
    s2 = await asyncio.start_server(handle_stream, S["STREAM_BIND"], num("STREAM_PORT", 8100))
    asyncio.create_task(housekeeping())
    while True:
        await nap(3600)


def main():
    args = sys.argv[1:]
    cmd = args[0] if args else ""
    load_settings()
    if cmd in ("minutes",) and len(args) == 3 and valid_plan(args[1]) and args[2].isdigit():
        print(minutes_for(args[1], int(args[2])))
        return
    if not hmac_selftest():
        print("coinslot: SHA-256 self-test failed on this MicroPython build; use coinslot-listener.sh", file=sys.stderr)
        sys.exit(1)
    if cmd == "hmac" and len(args) == 2:
        print("shell")
        print(hmac_hex(args[1]))
        return
    mkdirs(S["STATE_DIR"])
    if cmd == "serve":
        asyncio.run(serve())
    elif cmd == "fairuse-once":  # tests: one pass of the fair-use watcher
        asyncio.run(fair_tick())
    elif cmd == "purge":
        purge_vouchers()
    elif cmd == "report":
        d = args[1] if len(args) > 1 else "7"
        print(asyncio.run(do_report(int(d) if d.isdigit() else 7)))
    elif cmd == "box":
        async def chk():
            d = jparse(await http_get(box_addr(), "/api/gateway/challenge"))
            if not (d and d.get("nonce")) and await discover_box():
                d = jparse(await http_get(box_addr(), "/api/gateway/challenge"))
            ok = bool(d and d.get("nonce"))
            print("box %s %s" % (box_addr(), "answers" if ok else "does not answer"))
            return ok
        if not asyncio.run(chk()):
            sys.exit(1)
    else:
        print("usage: coinslot-fast.sh serve | minutes <plan> <pesos> | report [days] | box", file=sys.stderr)
        sys.exit(2)


main()
