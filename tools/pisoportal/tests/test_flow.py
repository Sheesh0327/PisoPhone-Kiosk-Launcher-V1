#!/usr/bin/env python3
"""End-to-end tests of pisoportal: customers over WebSocket, a fake coin box, a fake openNDS."""
import hashlib, hmac, json, os, signal, socket, time, urllib.error, urllib.request
from harness import *

MAC_A, MAC_B, MAC_C, MAC_D = "aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02", "aa:bb:cc:00:00:03", "aa:bb:cc:00:00:04"
env = Env(PAUSE_GAP_SECONDS=2, PAUSE_MAX=2, PAUSE_MIN_PESOS=3)


def gget(ip, path, headers=None):
    """A request to the portal from one device's own address (the portal tells devices apart by it)."""
    import http.client
    c = http.client.HTTPConnection("127.0.0.1", env.PP, timeout=10, source_address=(ip, 0))
    c.request("GET", path, headers=headers or {})
    r = c.getresponse()
    return r.status, r.read().decode(), dict(r.getheaders())
try:
    check(env.start(), "the portal starts")
    env.nds_client(MAC_A)
    env.nds_client(MAC_B)
    env.nds_client(MAC_C)
    env.nds_client(MAC_D)

    # ---- the page ------------------------------------------------------------------------------------------------------------
    t = time.time()
    code, body, hdr = env.get("/?fas=abc")
    page_ms = (time.time() - t) * 1000
    check(code == 200 and "TechNet" in body and "PisoWifi" in body and "Test Spot" not in body and "HyperSpeed" in body and "&#8369;10" in body and "11 hrs 30 min" not in body, "the splash page carries the TechNet PisoWifi brand (not the configurable name) and the price list")
    check("1 day" not in body and "24 hrs" in body, "tiers are listed in the page (Endurance 20 pesos = 24 hrs)")
    check(len(body) < 27500, f"the page is small ({len(body)} bytes; it goes over the shop Wi-Fi once, then a 304)")
    check("Get a voucher code" in body and "Use now" in body and "Save voucher file" in body and "Have a voucher?" in body and 't:"ping"' in body,
          "the page carries the voucher choice, the voucher code view, the voucher entry and the heartbeat")
    check(page_ms < 100, f"the page comes back fast ({page_ms:.1f} ms)")
    etag = hdr.get("ETag")
    code2, body2, _ = env.get("/", {"If-None-Match": etag})
    check(code2 == 304 and body2 == "", "a returning phone gets 304 and downloads nothing")
    check(env.get("/generate_204")[0] == 200 and env.get("/ping")[1] == "ok", "any other address is the page; ping answers")

    code_s, body_s, _ = env.get("/status")
    check(code_s == 200 and body_s == body and "time left" in body and "TechNet" in body and "WebSocket" in body,
          "there is one page: /status (an old bookmark) is the same page as / and carries the live countdown")
    import urllib.request
    with urllib.request.urlopen(f"http://127.0.0.1:{env.PP}/brand.png", timeout=10) as r:
        logo, logo_type, logo_cache = r.read(), r.headers.get("Content-Type"), r.headers.get("Cache-Control", "")
    check(logo[:8] == b"\x89PNG\r\n\x1a\n" and logo_type == "image/png" and "max-age" in logo_cache and len(logo) < 12000,
          f"the brand logo is served as a small cached PNG ({len(logo)} bytes)")
    req = urllib.request.Request(f"http://127.0.0.1:{env.PP}/brand.png", method="HEAD")
    with urllib.request.urlopen(req, timeout=10) as r:
        check(r.read() == b"" and int(r.headers["Content-Length"]) == len(logo), "HEAD of the logo has its length and no body")

    with urllib.request.urlopen(f"http://127.0.0.1:{env.PP}/mascot.png", timeout=10) as r:
        mascot, mascot_type = r.read(), r.headers.get("Content-Type")
    check(mascot[:8] == b"\x89PNG\r\n\x1a\n" and mascot_type == "image/png" and len(mascot) < 20000 and 'src="/mascot.png"' in body,
          f"the TechNet mascot is served as a small PNG and the page shows it ({len(mascot)} bytes)")

    # ---- a payment: P17 on Endurance (1 + 1 + 15 pesos), online only when the customer is done ----------------------------------
    env.set_box(busy=False, coins_at=[0.5, 1.0] + [1.5 + 0.1 * i for i in range(15)])
    a = Cust("127.0.0.1")
    check(a.ok, "websocket connects for a known device")
    s = a.wait_state("idle", 5)
    check(s is not None and s["left"] == 0 and s["online"] is False, f"first view: idle, nothing paid: {s}")
    # ---- feature 033: the spinning wheel is gone from the customer page, in every state ----------------------------------------------
    check("wh" not in s, f"033: the idle view carries no wheel flag: {s}")
    page_code, page, _h = gget("127.0.0.1", "/")
    check(page_code == 200 and "Spin the wheel" not in page and 'id="wh"' not in page and "/wheel" not in page, "033: the customer page has no Spin button and no odds text")
    wcode, wbody, _h = gget("127.0.0.1", "/wheel")
    check(wcode == 200 and wbody == page, "033: /wheel is no route any more: the portal answers it with the ordinary customer page")
    check(gget("127.0.0.1", "/wheel/state")[1] == page, "033: /wheel/state is no route any more")
    import http.client
    hc = http.client.HTTPConnection("127.0.0.1", env.PP, timeout=10, source_address=("127.0.0.1", 0))
    hc.request("POST", "/wheel/spin?id=x&bet=1", headers={"X-Piso": "1"})
    check(hc.getresponse().status == 405, "033: a spin is refused: there is no wheel to spin")
    hc.close()
    # ---- feature 034: the mini-game is gone --------------------------------------------------------------------------------------------
    check("gm" not in s, f"034: the idle view carries no game field: {s}")
    check("Play a mini-game" not in page and 'id="gm"' not in page and "/game" not in page and "Mini-game" not in page, "034: the customer page has no game button and no time-left text")
    check(gget("127.0.0.1", "/game")[1] == page and gget("127.0.0.1", "/game/")[1] == page, "034: /game is no route any more: the portal answers it with the ordinary customer page")
    check(gget("127.0.0.1", "/game/tick")[1] == page and gget("127.0.0.1", "/game/tick?s=1")[1] == page, "034: /game/tick is no route any more: no JSON, no minutes counted")
    hc = http.client.HTTPConnection("127.0.0.1", env.PP, timeout=10, source_address=("127.0.0.1", 0))
    hc.request("POST", "/game/tick", headers={"X-Piso": "1"})
    check(hc.getresponse().status == 405, "034: a POST to the old tick address is refused")
    hc.close()
    check(not any(f.startswith("game") for f in os.listdir(env.data)), f"034: the router keeps no game record: {os.listdir(env.data)}")
    t0 = time.time()
    a.send({"t": "start", "plan": "endurance"})
    armed = a.wait_state("armed", 8)
    check(armed is not None and armed["plan"] == "endurance", f"the slot arms: {armed}")
    got = a.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 2, 8)
    check(got is not None, "coins show on the page as the box counts them")
    check(env.nds_get(MAC_A)["STATE"] == "Preauthenticated" and MAC_A not in env.roll(), "NOT online while coins are still going in: " + str(env.roll()))
    check(os.listdir(f"{env.data}/open") != [], "the open window is noted on flash for crash recovery")
    fin = a.wait_state("final", 25)
    check(fin is not None and fin["pulses"] == 17 and fin["minutes"] == 690 and fin["online"] is True, f"closes by itself and settles: {fin}")
    check("wh" not in fin, f"033: the final view carries no wheel flag: {fin}")
    r = env.roll().get(MAC_A)
    check(r and r[5] == "690" and r[13] == "17" and r[11] == "endurance" and r[16] == "1" and r[1] == "5000" and r[2] == "2000", f"the roll holds the whole window priced once: {r}")
    check(env.revenue().count("\n") == 1 and ",endurance,17,690,new," in env.revenue(), "revenue logged once: " + env.revenue())
    rc, out = env.cli("verify")
    check(rc == 0 and out.startswith("OK 1 17"), "the ledger chain verifies: " + out)
    calls = [c for c in env.nds_calls() if c.startswith("auth " + MAC_A)]
    check(len(calls) == 1 and not any(c.startswith("deauth") for c in env.nds_calls()), "exactly one grant: " + str(env.nds_calls()))
    # a reload shortly after paying shows the time really left, never the whole purchase again
    time.sleep(2.2)
    a2 = Cust("127.0.0.1")
    v2 = a2.wait(lambda m: m.get("s") in ("final", "idle"), 5)
    a2.close()
    check(v2 is not None and v2["left"] > 0 and v2["left"] < fin["left"], f"a reload counts down from the real time, not the purchase: {fin['left']} -> {v2 and v2['left']}")
    nd = env.nds_get(MAC_A)
    check(nd["STATE"] == "Authenticated" and nd["UPRATE"] == "2000" and nd["DOWNRATE"] == "5000" and abs(int(nd["SESSION_END"]) - (int(r[6]) + 690 * 60)) < 120, f"granted 690 min with the Endurance caps: {nd}")
    # openNDS forgets its sessions (an update restarts it, or it crashes): a customer with paid time is let on again by the portal
    # itself within a tick, without opening the page again
    env.nds_client(MAC_A, STATE="Preauthenticated")
    check(wait_until(lambda: env.nds_get(MAC_A)["STATE"] == "Authenticated", 8), "openNDS forgot a paying customer: the portal puts the device back online by itself")
    check(any(l.startswith("ack ") for l in env.boxlines()) and os.listdir(f"{env.data}/open") == [], "coins acknowledged on the box, the flash record removed")
    check(any(l.startswith("event coin") for l in env.boxlines()), "the box pushed coin events")
    lines = env.boxlines()
    rel = next(i for i, l in enumerate(lines) if l.startswith("release "))
    sid = lines[rel].split()[1]
    n_status = sum(1 for l in lines[rel:] if l == f"status {sid}")
    check(n_status <= 6, f"while the box drains, it is asked at most every 300 ms ({n_status} status calls)")
    a.close()

    # ---- a top-up on the same plan, then the other plan (refused without consent, then switched) -------------------------------------
    env.clear_nds_log()
    env.set_box(busy=False, coins_at=[0.3, 0.6])
    a = Cust("127.0.0.1", reset=True)
    s = a.wait_state("idle", 5)
    check(s is not None and s["left"] > 600 * 60 and s["plan"] == "endurance" and s["online"] is True, f"a returning page shows the time left: {s}")
    a.mark()
    arms = sum(l.startswith("arm ") for l in env.boxlines())
    a.send({"t": "start", "plan": "hyper"})
    mm = a.wait_state("mismatch", 5)
    check(mm is not None and mm["plan"] == "endurance" and mm["left"] > 0, f"the other plan needs consent: {mm}")
    check(sum(l.startswith("arm ") for l in env.boxlines()) == arms, "and the coin slot is not armed for it")
    a.mark()
    a.send({"t": "start", "plan": "endurance"})
    fin = a.wait_state("final", 25)
    check(fin is not None and fin["pulses"] == 2 and fin["minutes"] == 30, f"top-up window: {fin}")
    r = env.roll().get(MAC_A)
    check(r and r[5] == "720" and r[13] == "19", f"top-up adds this window's price to the time left: {r}")
    check(",endurance,2,30,topup," in env.revenue(), "top-up logged")
    cl = env.nds_calls()
    check(sum(c.startswith("deauth " + MAC_A) for c in cl) == 1 and sum(c.startswith("auth " + MAC_A) for c in cl) == 1, "one re-grant when it closed: " + str(cl))
    env.set_box(busy=False, coins_at=[0.3, 0.5, 0.7, 0.9, 1.1])
    a.mark()
    a.send({"t": "start", "plan": "hyper", "forfeit": True})
    fin = a.wait(lambda m: m.get("s") == "final" and m.get("plan") == "hyper", 25)
    check(fin is not None and fin["pulses"] == 5 and fin["minutes"] == 30, f"switching with consent: {fin}")
    r = env.roll().get(MAC_A)
    check(r and r[11] == "hyper" and r[5] == "30" and r[13] == "5", f"the old time was given up: {r}")
    check(",hyper,5,30,switch," in env.revenue(), "switch logged")
    rc, out = env.cli("verify")
    check(rc == 0 and out.startswith("OK 3 24"), "ledger chain still verifies: " + out)
    a.close()

    # ---- a top-up by a customer who is online already keeps the window open between coins ---------------------------------------------------
    # (in this test the first coin must come within 4 s, and each later one within 3 s of the one before; the shop's real values are 30 s and 15 s)
    env.set_box(busy=False, coins_at=[0.3, 2.5, 4.8, 7.0])   # the window lasts 7 s here, longer than the 4 s of the first wait
    a = Cust("127.0.0.1", reset=True)
    s = a.wait_state("idle", 5)
    check(s is not None and s["online"] is True and s["left"] > 0, f"the customer is online with time left: {s}")
    a.mark()
    a.send({"t": "start", "plan": "hyper"})
    fin = a.wait_state("final", 30)
    check(fin is not None and fin["pulses"] == 4, f"one window took all four coins, over 7 seconds (each coin gives the next one time): {fin}")
    a.close()
    time.sleep(1.5)

    # ---- Done ends the window early; one client at a time; the other devices are turned away -------------------------------------
    env.set_box(busy=False, coins_at=[0.4])
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    t0 = time.time()
    b.send({"t": "start", "plan": "hyper"})
    b.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 1, 8)
    c = Cust("127.0.0.3", reset=True)
    c.wait_state("idle", 5)
    c.mark()
    c.send({"t": "start", "plan": "hyper"})
    busy = c.wait_state("busy", 5)
    check(busy is not None and busy["retry"] == 7, f"another device is refused while a window is open: {busy}")
    c2 = Cust("127.0.0.3", reset=True)
    s2 = c2.wait_state("idle", 5)
    check(s2 is not None and s2["busy"] == 7, f"and a page opened meanwhile says the slot is busy: {s2}")
    c2.close()
    t1 = time.time()
    b.mark()
    b.send({"t": "done"})
    fin = b.wait_state("final", 10)
    check(fin is not None and fin["pulses"] == 1 and time.time() - t1 < 4, f"Done closes the window at once ({time.time() - t1:.1f} s, the idle wait is 3 s): {fin}")
    c.mark()
    c.send({"t": "done"})   # a device that does not own the window cannot close it
    check(b.last().get("s") == "final", "another device cannot close someone else's window")
    check(wait_until(lambda: c.last().get("s") == "idle" and c.last().get("busy", 1) == 0, 5), "the slot is free again for the next customer")
    b.close()

    # ---- empty windows: no charge, and a device that keeps opening them is put in a cooldown -----------------------------------------
    env.set_box(busy=False, coins_at=[])
    before = env.revenue()
    for i in range(2):
        c.mark()
        c.send({"t": "start", "plan": "hyper"})
        c.wait(lambda m: m.get("s") == "armed", 8)
        c.send({"t": "done"})
        e = c.wait_state("empty", 10)
        check(e is not None, f"an empty window ends with nothing charged ({i + 1})")
        time.sleep(0.2)
    check(env.revenue() == before and MAC_C not in env.roll(), "nothing was recorded for empty windows")
    c.mark()
    c.send({"t": "start", "plan": "hyper"})
    cd = c.wait_state("cooldown", 5)
    check(cd is not None and 0 < cd["retry"] <= 20, f"after two empty windows the device waits: {cd}")
    check("alert grief:" in env.log_text() and MAC_C in env.log_text().split("alert grief:")[-1], "the router log carries the alert line the monitor reads")
    c.close()

    # ---- the box is busy (a rental phone holds the slot), a box that does not answer --------------------------------------------------
    env.set_box(busy=True, coins_at=[])
    d = Cust("127.0.0.2", reset=True)
    d.wait_state("idle", 5)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    er = d.wait_state("error", 10)
    check(er is not None and er["e"] == "SLOT_BUSY", f"the slot is busy on the box: {er}")
    env.set_box(busy=False, coins_at=[])
    d.close()

    # ---- openNDS refuses the grant: the payment is safe on the roll, the device is told it is not online yet, the next visit finishes it --
    env.reset_device(MAC_B)
    open(f"{env.nds}/refuse_auth", "w").close()
    env.set_box(busy=False, coins_at=[0.3, 0.6])
    rev0 = env.revenue().count("\n")
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "hyper"})
    fin = b.wait_state("final", 25)
    check(fin is not None and fin["online"] is False and fin["pulses"] == 2, f"a refused grant is never reported as online: {fin}")
    check(env.roll().get(MAC_B, [""] * 17)[5] == "12" and env.revenue().count("\n") - rev0 == 1, "but the payment is recorded once")
    check(env.nds_get(MAC_B)["STATE"] != "Authenticated", "and the device is not online")
    b.mark()
    os.remove(f"{env.nds}/refuse_auth")
    on = b.wait(lambda m: m.get("s") == "final" and m.get("online") is True, 8)
    check(on is not None and env.nds_get(MAC_B)["STATE"] == "Authenticated" and on["pulses"] == 2,
          f"the router keeps trying: once openNDS takes it, the open page turns to online by itself (no reload): {on}")
    check("is online after" in env.log_text(), "and the router log says so")
    b.close()
    b = Cust("127.0.0.2", reset=True)
    s = b.wait_state("idle", 5)
    check(s is not None and s["online"] is True and wait_until(lambda: env.nds_get(MAC_B)["STATE"] == "Authenticated", 5), f"the next visit puts it online from the roll: {s}")
    b.close()

    # ---- a failed acknowledgement: no new window until the coins are settled with the box -------------------------------------------------
    env.reset_device(MAC_D)
    env.set_box(busy=False, coins_at=[0.3], ack_fail=True)
    d = Cust("127.0.0.4", reset=True)
    d.wait_state("idle", 5)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    fin = d.wait_state("final", 25)
    check(fin is not None and fin["online"] is True and fin["pulses"] == 1, f"the customer is online even though the box could not remove the coins: {fin}")
    rev1 = env.revenue()
    recs = os.listdir(f"{env.data}/open")
    check(len(recs) == 2 and any(r.endswith(".rec") for r in recs), f"the window stays noted (recorded, not acknowledged): {recs}")
    time.sleep(2.6)   # (the page is told at once, but the router asks the box to acknowledge three times, a second apart: the window is over after that)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    er = d.wait_state("error", 20)
    check(er is not None and er["e"] == "ACK_PENDING", f"no new window while coins are unacknowledged: {er}")
    env.set_box(busy=False, coins_at=[], ack_fail=False)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    ar = d.wait_state("armed", 20)
    rec_files = os.listdir(f"{env.data}/open")   # (since 021 the window that just opened has its own record from the moment it is armed)
    check(ar is not None and len(rec_files) == 1 and not any(r.endswith(".rec") for r in rec_files), f"once the box acknowledges, the old coins are settled and the next window opens (only its own record is on flash: {rec_files})")
    check(env.revenue() == rev1, "and the earlier window was not credited again")
    d.send({"t": "done"})
    d.wait_state("empty", 10)
    d.close()

    # ---- the page is told it is online as soon as the device is let on, not after the box has answered the acknowledgement -----------------
    env.reset_device(MAC_D)
    env.set_box(busy=False, coins_at=[0.3], ack_delay=3.0)
    e = Cust("127.0.0.4", reset=True)
    e.wait_state("idle", 5)
    e.mark()
    e.send({"t": "start", "plan": "hyper"})
    e.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 1, 10)
    e.mark()
    t_done = time.time()
    e.send({"t": "done"})
    fin = e.wait_state("final", 12)
    waited = time.time() - t_done
    check(fin is not None and fin["online"] is True and waited < 2.5, f"Done to online takes {waited:.1f} s even though the box needs 3 s to acknowledge: {fin}")
    time.sleep(3.5)   # (let the acknowledgement finish before the next test)
    e.close()
    env.set_box(busy=False, coins_at=[], ack_delay=0)

    # ---- the box signs its answers: an unsigned or wrongly signed answer is not trusted, whatever it says -----------------------------------
    # (the box has signed many answers by now, so the router no longer takes an unsigned one: someone else is answering in its place)
    env.reset_device(MAC_D)
    check(os.path.exists(f"{env.data}/box_signed"), "the router noted that this box signs its answers")
    for mode in ("bad", "none"):
        env.set_box(busy=False, coins_at=[0.3], sign=mode)
        d = Cust("127.0.0.4", reset=True)
        d.wait_state("idle", 5)
        d.mark()
        d.send({"t": "start", "plan": "hyper"})
        er = d.wait_state("error", 10)
        check(er is not None and er["e"] == "NO_ANSWER", f"an answer with {mode} signature is refused: {er}")
        d.close()
    env.set_box(busy=False, coins_at=[0.3], sign="good")
    d = Cust("127.0.0.4", reset=True)
    d.wait_state("idle", 5)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    fin = d.wait_state("final", 25)
    check(fin is not None and fin["online"] is True and fin["pulses"] == 1, f"and a properly signed answer works as before: {fin}")
    d.close()
    env.set_box(busy=False, coins_at=[])

    # ---- pause and resume: the clock stops, the device stays on at a crawl (1 kbit/s), Resume brings the time and the speed back --------------------------------------
    env.reset_device(MAC_D)
    env.set_box(busy=False, coins_at=[0.3])
    d = Cust("127.0.0.4", reset=True)
    d.wait_state("idle", 5)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    fin = d.wait_state("final", 25)
    check(fin is not None and fin["online"] is True, f"a paid device is online before it pauses: {fin}")
    env.set_box(busy=False, coins_at=[])
    # a pause is a perk of paying enough (PAUSE_MIN_PESOS, 3 here, 10 by default): 1 peso paid is not enough, and the page is told how much is missing
    n = d.count()
    d.send({"t": "pause"})
    r = d.wait_msg("pz", n)
    check(r and r.get("e") == "pesos" and r["w"] == 2, f"one coin does not unlock a pause; 2 pesos more are missing: {r}")
    d.mark()
    d.send({"t": "hello", "fas": "", "reset": True})   # the page is opened again
    idle = d.wait(lambda m: m.get("s") == "idle", 5) or {}
    check(idle.get("s") == "idle" and idle.get("pn") == 2 and idle.get("pl") == 2, f"and the page is told what is still needed: {idle}")
    check(env.nds_get(MAC_D)["DOWNRATE"] == "0", "and the refused pause changed nothing: the device keeps its full speed")
    env.set_box(busy=False, coins_at=[0.3, 0.6])   # two more coins in the same session: 3 pesos paid in all
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    fin = d.wait_state("final", 25)
    check(fin is not None and fin["online"] is True, f"topping up works: {fin}")
    env.set_box(busy=False, coins_at=[])
    d.mark()
    d.send({"t": "hello", "fas": "", "reset": True})
    idle = d.wait(lambda m: m.get("s") == "idle" and m.get("pn") == 0, 5)
    check(idle is not None, f"after 3 pesos in all the pause is unlocked: {idle}")
    n = d.count()
    d.mark()
    d.send({"t": "pause"})
    r = d.wait_msg("pz", n)
    p = d.wait_state("paused", 5)
    check(r and r.get("ok") == 1 and p is not None and p["pl"] == 1 and p["pm"] == 2 and 1000 < p["left"] <= 1080, f"Pause stops the clock and counts the pause: {r} {p}")
    nd = env.nds_get(MAC_D)
    check(nd["STATE"] == "Authenticated" and nd["UPRATE"] == "1" and nd["DOWNRATE"] == "1", f"a paused device is not taken off: it stays on at 1 kbit/s: {nd}")
    check(nd["QUP"] == "2048" and nd["QDOWN"] == "2048", f"with 2 MB of data for the whole pause (openNDS takes it off when that is used): {nd}")
    check(86000 < int(nd["SESSION_END"]) - time.time() <= 86460, "and openNDS keeps it for as long as the paused time is kept (a day), then ends the session itself")
    left0 = p["left"]
    d.send({"t": "hello", "fas": "", "reset": False})   # the page reloads while paused
    time.sleep(2.2)
    d.send({"t": "ping"})
    p2 = d.last()
    nd2 = env.nds_get(MAC_D)
    check("wh" not in p and "wh" not in p2, f"033: the paused view carries no wheel flag: {p} {p2}")
    check(p2.get("s") == "paused" and p2["left"] == left0 and nd2["STATE"] == "Authenticated" and nd2["DOWNRATE"] == "1",
          f"a reloaded page stays paused, the clock is stopped, and the fair-use timer (every second here) neither resumes it nor speeds it up: {p2} {nd2}")
    n = d.count()
    d.send({"t": "pause"})
    r = d.wait_msg("pz", n)
    check(r and r.get("e") == "already", f"pausing twice is refused: {r}")
    n = d.count()
    d.mark()
    d.send({"t": "resume"})
    r = d.wait_msg("pz", n)
    on = d.wait(lambda m: m.get("s") == "idle" and m.get("online") is True, 5)
    check(r and r.get("ok") == 1 and on is not None and left0 - 5 <= on["left"] <= left0 and on["pl"] == 1,
          f"Resume gives the same time back and the device is online again: {r} {on}")
    check(wait_until(lambda: env.nds_get(MAC_D)["STATE"] == "Authenticated" and env.nds_get(MAC_D)["DOWNRATE"] == "0", 5), "and it is back at the plan's own speed (HyperSpeed: no cap), not at a crawl")
    ndr = env.nds_get(MAC_D)
    check(ndr["QUP"] == "0" and ndr["QDOWN"] == "0", f"and the paused data allowance is gone: {ndr}")
    n = d.count()
    d.send({"t": "pause"})
    r = d.wait_msg("pz", n)
    check(r and r.get("e") == "wait" and r["w"] >= 1, f"pausing right after a resume is refused for a moment: {r}")
    time.sleep(2.3)
    for want in ("ok", "limit"):
        n = d.count()
        if want == "ok":
            open(f"{env.nds}/refuse_auth", "w").close()   # openNDS refuses the slow grant: the pause must still work, the device is simply off
        d.send({"t": "pause"})
        r = d.wait_msg("pz", n)
        if want == "ok":
            check(r and r.get("ok") == 1, f"the second pause (the last allowed) works: {r}")
            check(env.nds_get(MAC_D)["STATE"] == "Preauthenticated", "and when openNDS refuses the slow grant the device is off, as a pause always was")
            os.remove(f"{env.nds}/refuse_auth")
            n = d.count()
            d.send({"t": "resume"})
            d.wait_msg("pz", n)
            check(wait_until(lambda: env.nds_get(MAC_D)["STATE"] == "Authenticated", 5), "and Resume puts it back on")
            time.sleep(2.3)
        else:
            check(r and r.get("e") == "limit", f"no pauses left: {r}")
    n = d.count()
    d.send({"t": "resume"})
    r = d.wait_msg("pz", n)
    check(r and r.get("e") == "none", f"resuming when not paused does nothing: {r}")
    d.close()

    # ---- voucher batches the owner made in earlier releases (printed slips) still work: spent once, wrong tries are slowed ---------------------
    codes = legacy_batch(env.data, minutes=60, pesos=10, plan="hyper", count=2, days=30)
    c1, c2 = codes
    check(len(c1) == 11 and c1[5] == "-" and c1 != c2, f"two different codes, written as ABCDE-FGHJK: {c1} {c2}")
    check(env.admin("/admin/voucher/list")["batches"][0]["count"] == 2 and env.admin("/admin/voucher/new?minutes=60").get("error") == "NOT_FOUND"
          and env.admin("/admin/voucher/codes?batch=x").get("error") == "NOT_FOUND", "the shell can list vouchers; making batches and reading codes are gone")
    rev0 = env.revenue()
    env.reset_device(MAC_C)
    c = Cust("127.0.0.3", reset=True)
    c.wait_state("idle", 5)
    n = c.count()
    c.send({"t": "redeem", "c": c1.lower().replace("-", " ")})
    r = c.wait_msg("rd", n)
    check(r and r.get("ok") == 1 and r["min"] == 60, f"a typed code (lower case, no dash) is accepted: {r}")
    check(wait_until(lambda: env.nds_get(MAC_C)["STATE"] == "Authenticated", 5), "the device is online with the voucher's time")
    row = env.roll().get(MAC_C)
    check(row is not None and row[5] == "60" and row[11] == "hyper", f"the roll holds the 60 minutes: {row}")
    new = env.revenue()[len(rev0):]
    check(",hyper,10,60,voucher," in new, f"a printed slip of an earlier release is booked as before (it was sold for cash elsewhere): {new!r}")
    check(env.cli("verify")[1].startswith("OK"), f"the ledger chain holds with a voucher line in it: {env.cli('verify')}")
    n = c.count()
    c.send({"t": "redeem", "c": c1})
    r = c.wait_msg("rd", n)
    check(r and r.get("e") == "used", f"a code works once: {r}")
    replies = []
    for i in range(5):
        n = c.count()
        c.send({"t": "redeem", "c": "ZZZZZ-ZZZZ" + str(i)})
        replies.append(c.wait_msg("rd", n))
    check(all(r and r.get("e") in ("bad", "wait") for r in replies) and replies[0]["e"] == "bad" and replies[3]["e"] == "bad",
          f"wrong codes are refused: {replies}")
    # (the wait is counted down from the moment of the fifth try: a second may already have passed when the answer is made)
    check(58 <= (replies[4].get("w") or 0) <= 60, f"the fifth wrong try starts a one-minute wait: {replies[4]}")
    n = c.count()
    c.send({"t": "redeem", "c": c2})
    r = c.wait_msg("rd", n)
    check(r and r.get("e") == "wait" and r["w"] > 30, f"after five wrong tries even the right code waits: {r}")
    c.close()
    # another device is not slowed
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    n = b.count()
    b.send({"t": "redeem", "c": c2})
    r = b.wait_msg("rd", n)
    check(r and r.get("ok") == 1, f"the other device redeems the second code at once: {r}")
    b.close()
    env.reset_device(MAC_B)
    env.reset_device(MAC_C)

    # ---- "Get a voucher code": the coins buy ONE voucher, nothing is connected, the money is booked like any coin payment -----------------------
    env.reset_device(MAC_B)
    env.set_box(busy=False, coins_at=[0.3, 0.6, 0.9])
    rev0 = env.revenue()
    env.clear_nds_log()
    b = Cust("127.0.0.2", reset=True)
    idle = b.wait_state("idle", 5)
    check(idle is not None and idle["vc"] is True and idle["vs"] == [] and any(m.get("t") == "info" and m.get("vc") is True for _, m in b.msgs),
          f"the page is told the voucher choice is on, and the device has no voucher yet: {idle}")
    b.mark()
    b.send({"t": "start", "plan": "hyper", "mode": "voucher"})
    arm = b.wait_state("armed", 8)
    check(arm is not None and arm.get("v") is True, f"the window knows it pays for a voucher: {arm}")
    fin = b.wait_state("final", 25)
    check(fin is not None and fin["pulses"] == 3 and fin["minutes"] == 18 and fin["online"] is False and fin["left"] == 0 and len(fin.get("vcode", "")) == 11
          and abs(fin["vexp"] - (time.time() + 30 * 86400)) < 300, f"the window settles into a voucher worth exactly the minutes bought, valid 30 days: {fin}")
    vcode = fin["vcode"]
    check(env.nds_get(MAC_B)["STATE"] == "Preauthenticated" and MAC_B not in env.roll() and not any(c.startswith("auth ") for c in env.nds_calls()),
          "NOT connected and no time granted: " + str(env.nds_calls()))
    new = env.revenue()[len(rev0):]
    check(new.count("\n") == 1 and ",hyper,3,18,vbuy," in new, f"the coins are booked once, as income, when the window settles: {new!r}")
    rc, out = env.cli("verify")
    check(rc == 0 and out.startswith("OK"), "the ledger chain holds: " + out)
    check(any(l.startswith("ack ") for l in env.boxlines()) and os.listdir(f"{env.data}/open") == [], "the box was acknowledged")
    # the code is not stored in the clear anywhere on the router
    plain = vcode.replace("-", "")
    leaks = [f for f in os.listdir(env.data) if os.path.isfile(f"{env.data}/{f}") and (plain.encode() in open(f"{env.data}/{f}", "rb").read() or vcode.encode() in open(f"{env.data}/{f}", "rb").read())]
    check(not leaks, f"no file in the data folder holds the code: {leaks}")
    batches = [l.split(",") for l in open(f"{env.data}/voucher_batches.txt").read().split("\n") if l]
    mine = [x for x in batches if x[3] == "18"]
    check(len(mine) == 1 and mine[0][2] == "1" and int(mine[0][6]) - int(mine[0][8]) == 30 * 86400 and mine[0][11] == MAC_B and len(mine[0][12]) == 32,
          f"one batch of one code, 30 days, remembering the device and the coin window: {mine}")
    b.close()
    # a repeated hello from the same page, and a new page of the same device, both get the final state again
    b = Cust("127.0.0.2")
    s = b.wait(lambda m: m.get("s") in ("final", "idle"), 5)
    check(s is not None and s["s"] == "final" and s.get("vcode") == vcode, f"a reopened page gets the final state with the code: {s}")
    b.mark()
    b.send({"t": "hello", "fas": ""})
    s = b.wait_state("final", 3)
    check(s is not None and s.get("vcode") == vcode and s["pulses"] == 3, f"a repeated hello is answered with the current (final) state: {s}")
    n = b.count()
    b.send({"t": "ping"})
    check(b.wait_msg("pong", n, 3) is not None, "a ping is answered (the page's heartbeat)")
    # the status page of the device lists the voucher it bought, for 30 days, when the result is gone
    b.mark()
    b.send({"t": "hello", "fas": "", "reset": True})
    s = b.wait_state("idle", 5)
    check(s is not None and [v["c"] for v in s["vs"]] == [vcode] and s["vs"][0]["m"] == 18 and s["vs"][0]["p"] == "hyper" and s["vs"][0]["x"] == fin["vexp"],
          f"the idle page lists the unredeemed voucher, so a closed page loses nothing: {s and s['vs']}")
    other = Cust("127.0.0.3", reset=True)
    s3 = other.wait_state("idle", 5)
    check(s3 is not None and s3["vs"] == [], "and only that device sees it")
    other.close()
    # redeeming it: time is added, no revenue row (time use, not income), the code is spent
    rev1 = env.revenue()
    n = b.count()
    b.send({"t": "redeem", "c": vcode})
    r = b.wait_msg("rd", n)
    check(r and r.get("ok") == 1 and r["min"] == 18, f"the voucher is redeemed: {r}")
    check(wait_until(lambda: env.nds_get(MAC_B)["STATE"] == "Authenticated", 5), "and the device is online")
    row = env.roll().get(MAC_B)
    check(row is not None and row[5] == "18" and row[11] == "hyper", f"with exactly the minutes the coins bought: {row}")
    check(env.revenue() == rev1, "redeeming adds NO revenue row")
    n = b.count()
    b.send({"t": "redeem", "c": vcode})
    check((b.wait_msg("rd", n) or {}).get("e") == "used", "a voucher is single use")
    s = b.wait(lambda m: m.get("s") == "idle" and m.get("left", 0) > 0, 5)
    check(s is not None and s["vs"] == [], "and it leaves the device's list")
    b.close()
    rc, out = env.cli("verify")
    check(rc == 0 and out.startswith("OK"), "the ledger still verifies after redeeming: " + out)
    # a voucher bought while the device has time on the other plan needs no question (nothing is mixed now); redeeming it is refused with the old message
    env.set_box(busy=False, coins_at=[0.3, 0.6])
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "endurance", "mode": "voucher"})
    fin2 = b.wait_state("final", 25)
    check(fin2 is not None and fin2.get("vcode") and fin2["minutes"] == 30 and fin2["plan"] == "endurance", f"other-plan voucher while online: no mismatch question: {fin2}")
    n = b.count()
    b.send({"t": "redeem", "c": fin2["vcode"]})
    check((b.wait_msg("rd", n) or {}).get("e") == "plan", "redeeming it on top of the other plan gives the old message")
    b.close()
    env.reset_device(MAC_B)
    env.reset_device(MAC_C)

    # ---- forged and replayed coin events change nothing; an older box (no events) and lost events still work -----------------------------
    env.reset_device(MAC_B)
    env.set_box(busy=False, coins_at=[1.5])
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "hyper"})
    b.wait_state("armed", 8)
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    body = "gw1ev:" + "0" * 32 + ":" + "1" * 32 + ":99:coin:50"
    u.sendto((body + ":" + hmac.new(KEY.encode(), body.encode(), hashlib.sha256).hexdigest()).encode(), ("127.0.0.1", EVENT_PORT))   # right signature, another window
    u.sendto((body + ":" + "0" * 64).encode(), ("127.0.0.1", EVENT_PORT))                                                              # bad signature
    u.sendto(b"junk", ("127.0.0.1", EVENT_PORT))
    time.sleep(0.5)
    check(b.last().get("pulses", 0) == 0, f"forged events are ignored: {b.last()}")
    fin = b.wait_state("final", 25)
    check(fin is not None and fin["pulses"] == 1, f"only the real coin counted: {fin}")
    b.close()
    for ctlx, label in [({"events": False}, "a box without coin events"), ({"drop_events": True}, "lost coin events")]:
        env.reset_device(MAC_B)
        env.set_box(busy=False, coins_at=[0.5], **ctlx)
        b = Cust("127.0.0.2", reset=True)
        b.wait_state("idle", 5)
        b.mark()
        b.send({"t": "start", "plan": "hyper"})
        fin = b.wait_state("final", 30)
        check(fin is not None and fin["pulses"] == 1 and fin["online"] is True, f"{label}: still counted by the signed checks: {fin}")
        b.close()

    # ---- a page that asks for a fresh start never sees the previous result first --------------------------------------------------------
    # The portal sent a page its view from a thread started before the page's hello was read, so a change of state in between sent
    # the old result first (feature 025). Here the change is made on purpose: a result is waiting for the address, a page connects but
    # waits with its hello, another page connects (the state changes), and only then does the first page ask for a fresh start.
    env.reset_device(MAC_B)
    env.set_box(busy=False, coins_at=[0.5])
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "hyper"})
    fin = b.wait_state("final", 30)
    check(fin is not None and fin["pulses"] == 1, f"a result is waiting for the address: {fin}")
    b.close()
    slow = Cust("127.0.0.2", send_hello=False)
    other = Cust("127.0.0.1", reset=True)
    other.wait_state("idle", 5)
    time.sleep(0.6)
    check(slow.ok and slow.states() == [], f"a page that has not said hello is sent no view, least of all the old result: {slow.states()}")
    slow.hello(reset=True)
    first = slow.wait(lambda m: m.get("t") == "state", 5)
    check(first is not None and first["s"] == "idle", f"after hello with reset the first view is its own: {first}")
    slow.close()
    other.close()

    b = Cust("127.0.0.2", reset=True)
    first = b.wait(lambda m: True, 5)
    check(first is not None and first["s"] == "idle", f"a fresh page's first view is its own, not the last result: {first}")
    b.close()

    # ---- the page can be closed in the middle of a window: the result is waiting when it comes back ----------------------------------------
    env.reset_device(MAC_B)
    env.set_box(busy=False, coins_at=[0.4, 0.8])
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "hyper"})
    b.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 1, 8)
    b.close()
    time.sleep(8)
    b = Cust("127.0.0.2")
    s = b.wait(lambda m: m.get("s") in ("final", "idle"), 5)
    check(s is not None and s["s"] == "final" and s["pulses"] == 2 and s["online"] is True, f"a sleeping or closed phone loses nothing: {s}")
    b.close()

    # ---- the router restarts in the middle of a window: the coins are credited once, from the flash record ----------------------------------
    env.reset_device(MAC_C)
    e2 = Env(offset=10, EMPTY_COOLDOWN="1")   # a second, clean environment for the restart tests (own box, own data)
    try:
        e2.start()
        e2.nds_client(MAC_C)
        e2.set_box(busy=False, coins_at=[0.3, 0.5, 0.7])
        c = Cust("127.0.0.3", port=e2.PP, reset=True)
        c.wait_state("idle", 5)
        c.send({"t": "start", "plan": "hyper"})
        c.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 3, 8)
        sid = [f for f in os.listdir(f"{e2.data}/open")][0]
        e2.portal.send_signal(signal.SIGKILL)   # power cut / crash
        e2.portal.wait()
        e2.portal = None
        c.close()
        check(e2.nds_get(MAC_C)["STATE"] == "Preauthenticated" and e2.roll() == {}, "nothing was granted or recorded before the crash")
        e2.box_call("release", sid)             # the box's own timer ended the window
        time.sleep(1.2)
        e2.start()
        check(wait_until(lambda: MAC_C in e2.roll(), 10), "after the restart the coins the box still holds are credited")
        r = e2.roll()[MAC_C]
        check(r[13] == "3" and r[5] == "18" and e2.revenue().count("\n") == 1, f"once, priced for the whole window: {r} / {e2.revenue()}")
        check(wait_until(lambda: os.listdir(f"{e2.data}/open") == [], 10), "and acknowledged on the box")
        check(any(l.startswith("ack ") for l in e2.boxlines()), "the box saw the acknowledgement")
        e2.stop()
        e2.start()
        time.sleep(2)
        check(e2.revenue().count("\n") == 1 and e2.roll()[MAC_C][5] == "18", "a second restart does not credit it again")
        # a device with paid time that openNDS forgot is put back online when its page opens
        e2.nds_client(MAC_C)
        c = Cust("127.0.0.3", port=e2.PP, reset=True)
        s = c.wait_state("idle", 5)
        check(s is not None and s["online"] is True and s["left"] > 0 and wait_until(lambda: e2.nds_get(MAC_C)["STATE"] == "Authenticated", 5), f"reconnect by MAC after a restart: {s}")
        c.close()
        # recovering the same window twice never credits twice (a recorded window is only acknowledged)
        check(e2.cli("verify")[1].startswith("OK 1 3"), "ledger intact")
    finally:
        e2.close()

    # ---- the voucher choice can be switched off: the page then behaves exactly as before ---------------------------------------------------
    e3 = Env(offset=30, VOUCHER_CHOICE="off")
    try:
        e3.start()
        e3.nds_client(MAC_C)
        c = Cust("127.0.0.3", port=e3.PP, reset=True)
        s = c.wait_state("idle", 5)
        check(s is not None and s["vc"] is False and any(m.get("t") == "info" and m.get("vc") is False for _, m in c.msgs), f"choice off: the page is told there is no choice: {s}")
        e3.set_box(busy=False, coins_at=[0.3, 0.6])
        c.mark()
        c.send({"t": "start", "plan": "hyper", "mode": "voucher"})   # (an old or hand-made page asking anyway)
        fin = c.wait_state("final", 25)
        check(fin is not None and fin["online"] is True and "vcode" not in fin and MAC_C in e3.roll(), f"choice off: a voucher request is ignored, the coins buy time as before: {fin}")
        check(",hyper,2,12,new," in e3.revenue() and "vbuy" not in e3.revenue(), "and the ledger row is a normal one")
        c.close()
    finally:
        e3.close()
    # a setting the owner changed in the console (DATA_DIR/settings.txt) wins over the conf file's value
    e3 = Env(offset=30, VOUCHER_CHOICE="on")
    try:
        open(f"{e3.data}/settings.txt", "w").write("VOUCHER_CHOICE=off\n")
        e3.start()
        c = Cust("127.0.0.3", port=e3.PP, reset=True)
        s = c.wait_state("idle", 5)
        check(s is not None and s["vc"] is False, f"the console's choice wins over the conf file: {s}")
        c.close()
    finally:
        e3.close()

    # ---- the router restarts in the middle of a voucher window: the voucher is made once, the coins booked once --------------------------------
    e4 = Env(offset=50, EMPTY_COOLDOWN="1")
    try:
        e4.start()
        e4.nds_client(MAC_C)
        e4.set_box(busy=False, coins_at=[0.3, 0.5, 0.7, 0.9])
        c = Cust("127.0.0.3", port=e4.PP, reset=True)
        c.wait_state("idle", 5)
        c.send({"t": "start", "plan": "hyper", "mode": "voucher"})
        c.wait(lambda m: m.get("s") == "armed" and m.get("pulses", 0) >= 4, 8)
        sid = [f for f in os.listdir(f"{e4.data}/open")][0]
        check(open(f"{e4.data}/open/{sid}").read().split()[4] == "1", "the record on flash says it is a voucher window")
        e4.portal.send_signal(signal.SIGKILL)
        e4.portal.wait()
        e4.portal = None
        c.close()
        e4.box_call("release", sid)
        time.sleep(1.2)
        e4.start()
        check(wait_until(lambda: os.path.exists(f"{e4.data}/voucher_batches.txt"), 10), "after the restart the coins the box still holds make the voucher")
        check(wait_until(lambda: os.listdir(f"{e4.data}/open") == [], 10), "and the box is acknowledged")
        check(e4.roll() == {} and e4.nds_get(MAC_C)["STATE"] == "Preauthenticated", "nothing is connected")
        e4.stop()
        e4.start()
        time.sleep(2)
        check(e4.revenue().count("\n") == 1 and ",hyper,4,24,vbuy," in e4.revenue() and open(f"{e4.data}/voucher_batches.txt").read().count("\n") == 1,
              "a second restart makes no second voucher and no second row: " + e4.revenue())
        c = Cust("127.0.0.3", port=e4.PP, reset=True)
        s = c.wait_state("idle", 5)
        check(s is not None and len(s["vs"]) == 1 and s["vs"][0]["m"] == 24, f"the buyer's page lists it: {s}")
        c.close()
        rc, out = e4.cli("reconcile")
        check(rc == 0 and out.startswith("RECONCILE OK") and "ledger=4 " in out, "the coins of a voucher are part of the reconcile with the box: " + out)
    finally:
        e4.close()

    # ---- RT-07: a second late round of the same window is credited, not taken for the first one again --------------------------------------
    # The window was recorded (marker "recorded 0"); the box then counts 3 pesos the router never saw, and the acknowledgement keeps failing.
    # Round one credits 3; later the box holds 5 in all: the 2 new pesos are a second late round and must be credited too.
    e5 = Env(offset=15, EMPTY_COOLDOWN="1")
    try:
        sid5 = "0123456789abcdef0123456789abcdef"
        os.makedirs(f"{e5.data}/open")
        open(f"{e5.data}/open/{sid5}", "w").write(f"{MAC_C} hyper widlate 0 0\n")
        open(f"{e5.data}/open/{sid5}.rec", "w").write("recorded 0\n")
        e5.box_call("arm", sid5)
        e5.box_call("release", sid5)
        e5.set_box(busy=False, ack_fail=True, extra=3)
        e5.nds_client(MAC_C)
        e5.start()
        check(wait_until(lambda: MAC_C in e5.roll() and e5.roll()[MAC_C][13] == "3", 10), "RT-07: round one: the 3 late pesos are credited: " + str(e5.roll()))
        e5.set_box(busy=False, ack_fail=True, extra=5)
        check(wait_until(lambda: MAC_C in e5.roll() and e5.roll()[MAC_C][13] == "5", 10),
              "RT-07: round two: the 2 pesos counted later are credited too (not dropped as a duplicate of round one): " + str(e5.roll()))
        time.sleep(2.5)
        check(e5.roll()[MAC_C][13] == "5" and e5.revenue().count("\n") == 2, "RT-07: and nothing is credited twice while the acknowledgement keeps failing: " + e5.revenue())
        e5.set_box(busy=False, extra=5)   # the box takes the acknowledgement at last
        check(wait_until(lambda: os.listdir(f"{e5.data}/open") == [], 10), "RT-07: the window is acknowledged and cleared in the end")
        check(e5.roll()[MAC_C][13] == "5" and e5.revenue().count("\n") == 2, "RT-07: with the books unchanged by the acknowledgement")
    finally:
        e5.close()

    # ---- RT-04: a ledger row cut short by a power failure is a torn tail, not "LEDGER WAS CHANGED"; the next row starts on its own line -----------
    e6 = Env(offset=55, EMPTY_COOLDOWN="1")
    try:
        t6 = int(time.time()) - 5   # (a recent time: an old file would be rolled into its month's segment by the first sale, see test_ledger_roll below)
        rl = f"{t6},hyper,5,30,new"
        h1 = hashlib.sha256(f"0|{rl}".encode()).hexdigest()[:12]
        open(f"{e6.data}/revenue.csv", "w").write(f"{rl},{h1}\n" + f"{t6 + 1},hyper,3,18,new,ab")   # the second row was cut inside its hash
        rc, out = e6.cli("verify")
        check(rc == 0 and out.startswith("OK 1 5") and "torn_tail=true" in out, "RT-04: a torn last row is skipped and reported, not BAD: " + out)
        rc, out = e6.cli("reconcile")
        check("BADLEDGER" not in out, "RT-04: and the reconcile does not raise a false alert: " + out)
        e6.start()
        e6.nds_client(MAC_C)
        e6.set_box(busy=False, coins_at=[0.3, 0.6])
        c = Cust("127.0.0.3", port=e6.PP, reset=True)
        c.wait_state("idle", 5)
        c.send({"t": "start", "plan": "hyper"})
        fin = c.wait_state("final", 25)
        check(fin is not None and fin["online"] is True, f"RT-04: a payment after the torn tail still works: {fin}")
        c.close()
        rows = e6.revenue().split("\n")
        check(rows[1] == f"{t6 + 1},hyper,3,18,new,ab" and rows[2].count(",") == 5 and ",hyper,2,12,new," in rows[2],
              "RT-04: the new row is on a line of its own, after the fragment: " + repr(e6.revenue()))
        rc, out = e6.cli("verify")
        check(rc == 0 and out.startswith("OK 3 7") and "torn_tail" not in out, "RT-04: the chain holds and the tail is whole again: " + out)
    finally:
        e6.close()
    # ---- feature 032: a ledger over its 1 MB budget is pruned at the month roll and the box still agrees with the total ---------------------------
    e7 = Env(offset=57, EMPTY_COOLDOWN="1")
    try:
        import datetime
        def chain(prev, rl):
            return hashlib.sha256(f"{prev}|{rl}".encode()).hexdigest()[:12]
        prev, total = "0", 0
        for m in range(1, 6):   # Jan..May 2026, 9000 rows each (about 400 KB a month); May is the current file, the first sale rolls it
            t0 = int(datetime.datetime(2026, m, 10, tzinfo=datetime.timezone.utc).timestamp())
            lines = []
            if m > 1:
                rl = f"{t0},{prev},{total},0,carry"
                prev = chain(prev, rl)
                lines.append(f"{rl},{prev}")
            for n in range(9000):
                pesos = n % 5 + 1
                rl = f"{t0 + n},hyper,{pesos},{pesos * 6},topup"
                prev = chain(prev, rl)
                lines.append(f"{rl},{prev}")
                total += pesos
            name = "revenue.csv" if m == 5 else f"revenue-2026-{m:02d}.csv"
            open(f"{e7.data}/{name}", "w").write("\n".join(lines) + "\n")
        size0 = sum(os.path.getsize(f"{e7.data}/{f}") for f in os.listdir(e7.data) if f.startswith("revenue"))
        check(size0 > 1024 * 1024, f"feature 032: the seeded ledger is over the 1 MB budget: {size0} bytes")
        e7.set_box(busy=False, coins_at=[0.3, 0.6], lifetime_base=total)   # the box counted exactly what the ledger holds
        rc, out = e7.cli("verify", "all")
        check(rc == 0 and out == f"OK {5 * 9000 + 4} {total}", "feature 032: before: the whole ledger verifies: " + out)
        rc, out = e7.cli("reconcile")
        check(rc == 0 and out.startswith(f"RECONCILE OK ledger={total} box={total}"), "feature 032: before the prune, the ledger and the box agree: " + out)
        rc, out = e7.cli("ledger")
        check(rc == 0 and out == f"Sales record: 5 months of detail kept, none deleted. Lifetime total: {total} pesos.", "feature 032: the owner is told the months kept and the total: " + out)
        e7.start()
        e7.nds_client(MAC_C)
        c = Cust("127.0.0.3", port=e7.PP, reset=True)
        c.wait_state("idle", 5)
        c.send({"t": "start", "plan": "hyper"})
        fin = c.wait_state("final", 25)
        check(fin is not None and fin["online"] is True, f"feature 032: a sale on the first day of a new month still works: {fin}")
        c.close()
        pruned = f"{e7.data}/revenue-pruned.txt"
        check(wait_until(lambda: os.path.exists(pruned), 5), "feature 032: the months deleted on purpose are written down")
        left = sorted(f for f in os.listdir(e7.data) if f.startswith("revenue-2026"))
        check(len(left) < 5 and "revenue-2026-01.csv" not in left, "feature 032: the oldest months are gone, the newest kept: " + str(left))
        size1 = sum(os.path.getsize(f"{e7.data}/{f}") for f in os.listdir(e7.data) if f.startswith("revenue-2") or f == "revenue.csv")
        check(size1 <= 1024 * 1024, f"feature 032: the ledger is inside 1 MB again: {size1} bytes")
        rc, out = e7.cli("verify", "all")
        check(rc == 0 and out.startswith("OK ") and out.split()[2] == str(total + 2), "feature 032: after: it still verifies and the total moved only by the sale: " + out)
        rc, out = e7.cli("reconcile")
        check(rc == 0 and out.startswith(f"RECONCILE OK ledger={total + 2} box={total + 2}"), "feature 032: after the prune, the ledger still agrees with the box: " + out)
        rc, out = e7.cli("ledger")
        check(rc == 0 and out == f"Sales record: 3 months of detail kept (the space only allows 2 of the 3 closed months you wanted), 3 months deleted on purpose (their totals are kept). Lifetime total: {total + 2} pesos.", "feature 032: and after: months kept, months deleted, the same lifetime total: " + out)
    finally:
        e7.close()
    # ---- fair use: HyperSpeed is slowed after the limit, released later; Endurance is never touched -----------------------------------------
    env.reset_device(MAC_D)
    env.set_box(busy=False, coins_at=[0.3, 0.5, 0.7, 0.9, 1.1])
    d = Cust("127.0.0.4", reset=True)
    d.wait_state("idle", 5)
    d.mark()
    d.send({"t": "start", "plan": "hyper"})
    fin = d.wait_state("final", 25)
    check(fin is not None and fin["online"] is True, "a HyperSpeed customer is online")
    d.close()
    env.clear_nds_log()
    nd = env.nds_get(MAC_D)
    env.nds_client(MAC_D, STATE="Authenticated", SESSION_END=nd["SESSION_END"], DL=900, UL=300)
    ok = wait_until(lambda: any(c.startswith("auth " + MAC_D) and " 1000 2000 " in c for c in env.nds_calls()), 8)
    check(ok, "after the limit the connection is slowed (2000 kbit/s down, 1000 up): " + str(env.nds_calls()[-6:]))
    # the portal stores the counters just after the slowdown is granted: ask again until they are there
    v = None
    for _ in range(10):
        d2 = Cust("127.0.0.4", reset=True)
        v = d2.wait_state("idle", 5)
        d2.close()
        if v is not None and v.get("used_mb", 0) >= 1:
            break
        time.sleep(0.3)
    check(v is not None and v["plan"] == "hyper" and v["used_mb"] >= 1 and "slow" in v, f"the status view carries data used and the fair-use slowdown: {v}")
    ok = wait_until(lambda: any(c.startswith("auth " + MAC_D) and " 0 0 0 0" in c for c in env.nds_calls()), 8)
    check(ok, "and released again after the throttle period")
    env.reset_device(MAC_A)

    # ---- the operator's tools -------------------------------------------------------------------------------------------------------------
    rc, out = env.cli("box")
    check(rc == 0 and "answers" in out, "box: " + out)
    rc, out = env.cli("reconcile")
    check(rc == 0 and out.startswith("RECONCILE OK"), "reconcile: " + out)
    # the box's revenue was collected: its counter restarts, which is not a mismatch (and not one for ever)
    acked = int(env.box_call("status", "f" * 32)["lifetime_pulses"]) - 1000
    env.set_box(busy=False, coins_at=[], lifetime_base=3 - acked)
    rc, out = env.cli("reconcile")
    check(rc == 0 and "restarted" in out and "RECONCILE OK ledger=0 box=0" in out, "a collected box (its count restarted) is compared from then on: " + out)
    rc, out = env.cli("reconcile")
    check(rc == 0 and out.startswith("RECONCILE OK ledger=0 box=0"), "and stays fine: " + out)
    # a payment the router recorded but the box never counted is a mismatch
    env.reset_device(MAC_B)
    env.set_box(busy=False, coins_at=[0.3], lifetime_base=3 - acked, uncounted=1)
    b = Cust("127.0.0.2", reset=True)
    b.wait_state("idle", 5)
    b.mark()
    b.send({"t": "start", "plan": "hyper"})
    fin = b.wait_state("final", 25)
    b.close()
    rc, out = env.cli("reconcile")
    check(fin is not None and fin["pulses"] == 1 and rc == 1 and "RECONCILE MISMATCH ledger=1 box=0" in out,
          f"the router recording more than the box counted is reported: {fin} " + out)
    rc, out = env.cli("reconcile", "rebase")
    check(rc == 0 and "rebase" in out and "RECONCILE OK ledger=0 box=0" in out, "after a look, 'reconcile rebase' compares from now on: " + out)
    env.set_box(busy=False, coins_at=[])
    rc, out = env.cli("report", "7")
    check(rc == 0 and "PHP" in out and "Total last 7 day(s): PHP" in out and "hyper" in out and "endurance" in out, "report: " + out)
    led = open(f"{env.data}/revenue.csv").read()
    first = led.split("\n")[0].split(",")
    first[2] = str(int(first[2]) + 90)
    open(f"{env.data}/revenue.csv", "w").write(",".join(first) + "\n" + "\n".join(led.split("\n")[1:]))
    rc, out = env.cli("verify")
    check(rc != 0 and out == "BAD 1", "an edited ledger line is detected: " + out)
    rc, out = env.cli("reconcile")
    check(rc == 3 and "BADLEDGER" in out, "reconcile reports a broken ledger: " + out)
    open(f"{env.data}/revenue.csv", "w").write(led)
    env.stop()
    rc, out = env.cli("box")
    check(rc == 0, "box check works without the portal running")
    env.start()
    st = env.admin(f"/admin/start?mac={MAC_B}&plan=hyper")
    check(st.get("s") in ("starting", "armed"), f"the local admin interface opens a window (piso-setup test-coin): {st}")
    time.sleep(1)
    check(env.admin(f"/admin/status?mac={MAC_B}").get("s") == "armed", "and reports it")
    env.admin(f"/admin/finish?mac={MAC_B}")
    check(env.admin("/admin/status?mac=zz").get("error") == "NOT_FOUND", "bad requests are refused")

    # ---- robustness -------------------------------------------------------------------------------------------------------------------------
    x = Cust("127.0.0.9")
    check(not x.ok and "403" in x.head, "a device the router does not know gets no coin page: " + x.head[:20])
    g = socket.create_connection(("127.0.0.1", PORTAL_PORT)); g.send(b"\x00\x01garbage\r\n\r\n"); g.close()
    w = Cust("127.0.0.2", reset=True)
    w.send_raw(0x81, b"not json at all")
    w.send_raw(0x81, b'{"t":"start","plan":"nonsense"}')
    w.send_raw(0x82, b"\x00" * 40)
    w.send_raw(0x81, '{"t":"hello","fas":"%a\u00e9%\u00e9"}'.encode())   # raw UTF-8; once a crash: '%' before multi-byte characters
    time.sleep(0.5)
    check(env.get("/ping")[1] == "ok", "garbage and bad messages do not take the portal down")
    w.close()
    u = Cust("127.0.0.2", reset=True)
    u.s.send(bytes([0x81, 0x02]) + b"hi")   # unmasked: not allowed from a client (RFC 6455)
    check(wait_until(lambda: u.closed, 3), "an unmasked frame ends the connection")
    check(env.get("/ping")[1] == "ok", "and the portal carries on")

    # ---- what a guest network can throw at it ----------------------------------------------------------------------------------------------
    code, body, hdr = env.get("/")
    check(hdr.get("X-Content-Type-Options") == "nosniff" and hdr.get("X-Frame-Options") == "DENY", f"the page is sent with safe headers: {hdr}")
    try:
        with urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORTAL_PORT}/", method="HEAD"), timeout=5) as resp:
            head_ok = resp.status == 200 and resp.read() == b"" and int(resp.headers["Content-Length"]) == len(body.encode())
    except OSError:
        head_ok = False
    check(head_ok, "HEAD answers without a body")
    try:
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORTAL_PORT}/", data=b"x", method="POST"), timeout=5)
        post = "accepted"
    except urllib.error.HTTPError as e:
        post = e.code
    except OSError as e:
        post = str(e)
    check(post == 405, f"POST is refused: {post}")
    o = Cust("127.0.0.2", origin="http://evil.example")
    check(not o.ok and "403" in o.head, "a coin page opened from another site's page is refused: " + o.head[:30])
    o = Cust("127.0.0.2", origin=f"http://127.0.0.1:{PORTAL_PORT}", reset=True)
    check(o.ok, "the portal's own page connects")
    o.close()
    g = socket.create_connection(("127.0.0.1", PORTAL_PORT))
    g.settimeout(5)
    g.send(b"GET / HTTP/1.1\r\nX: " + b"a" * 9000)
    try:
        closed = g.recv(100) == b""
    except OSError:
        closed = True
    check(closed, "an oversized request head is cut off")
    g.close()
    hold = [socket.create_connection(("127.0.0.1", PORTAL_PORT), source_address=("127.0.0.5", 0)) for _ in range(8)]
    extra = socket.create_connection(("127.0.0.1", PORTAL_PORT), source_address=("127.0.0.5", 0))
    extra.settimeout(3)
    try:
        refused = extra.recv(10) == b""
    except OSError:
        refused = True
    check(refused, "one guest cannot hold more than 8 connections")
    t = time.time()
    check(env.get("/ping")[1] == "ok" and time.time() - t < 1, "while everybody else is still served at once")
    for h in hold + [extra]:
        h.close()
finally:
    env.close()
finish()
