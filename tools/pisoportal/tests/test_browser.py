#!/usr/bin/env python3
"""The coin page in a real browser (headless Chromium): one page, no reloads, WebSocket only."""
import glob, os, re, shutil, sys, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

MAC_A = "aa:bb:cc:00:00:01"
env = Env(offset=20)
e5 = None
try:
    check(env.start(), "the portal starts")
    env.nds_client(MAC_A)
    url = f"http://127.0.0.1:{env.PP}/?fas=ABC"
    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])
        pg = br.new_page()
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        # the phone's speech engine can freeze the page for a moment when first used: the tap must already have changed the screen
        pg.add_init_script("""(function(){window.__spoke=null;var sp=window.speechSynthesis;if(!sp)return;var orig=sp.speak.bind(sp);
          sp.speak=function(u){if(window.__spoke===null){var l=document.getElementById('v-live');window.__spoke=!!l&&l.style.display==='block';
          var t=Date.now();while(Date.now()-t<400){}}return orig(u)}})()""")
        pg.goto(url)
        pg.wait_for_selector("#v-idle", state="visible", timeout=8000)
        check(pg.is_visible(".coin") and "HyperSpeed" in pg.content() and "Endurance" in pg.content(), "the plan page shows at once with the price list")
        check(not pg.is_visible("#ion"), "nothing paid yet: no online notice")
        env.set_box(busy=False, coins_at=[1.0, 1.6, 2.2])
        pg.click(".coin")
        pg.wait_for_timeout(150)
        check(pg.is_visible("#v-live") and ("Getting the coin slot ready" in (pg.text_content("#lsub") or "") or "Insert coin" in (pg.text_content("#lsub") or "")), "the tap leaves the plan page at once: " + (pg.text_content("#lsub") or ""))
        pg.wait_for_selector("#lcd", state="visible", timeout=8000)
        check("Insert coin" in pg.text_content("#lsub"), "the countdown starts once the slot is armed: " + pg.text_content("#lsub"))
        pg.wait_for_selector("#ldone", state="visible", timeout=8000)
        check(pg.text_content("#lpes").strip() in ("₱1", "₱2", "₱3"), "coins show as the box counts them: " + pg.text_content("#lpes"))
        check(not pg.is_visible("#v-final") and env.nds_get(MAC_A)["STATE"] == "Preauthenticated", "NOT online while coins go in (the phone would close the page)")
        pg.wait_for_function("document.getElementById('lpes').textContent.indexOf('3')>=0", timeout=8000)
        t0 = time.time()
        pg.click("#ldone")
        pg.wait_for_selector("#v-final", state="visible", timeout=10000)
        check(time.time() - t0 < 4, f"Done closes the window at once ({time.time() - t0:.1f} s; the idle wait is 3 s)")
        check("You are online" in pg.text_content("#fon") and re.match(r"0:1[78]:\d\d$", pg.text_content("#fleft").strip()), "the final view says online and shows the live time left: " + pg.text_content("#fleft"))
        check(env.nds_get(MAC_A)["STATE"] == "Authenticated", "and the device really is online")
        check(pg.evaluate("window.__spoke") is True, "the screen changed before the speech engine was touched")
        navs = []
        pg.on("framenavigated", lambda f: navs.append(f.url) if f == pg.main_frame else None)
        pg.wait_for_url(re.compile(r"[?&]w=1"), timeout=8000)
        pg.wait_for_selector("#v-final", state="visible", timeout=8000)
        check("fas=ABC" in pg.url and "You are online" in pg.text_content("#fon"),
              "once online the page loads itself again (a phone's sign-in window checks the internet when a page loads), same address: " + pg.url)
        pg.wait_for_timeout(3500)
        check(len(navs) == 1 and pg.is_visible("#v-final"), f"once only, and it still shows the purchase: {navs}")
        # a second payment on the same plan from the same page (Add more time)
        env.set_box(busy=False, coins_at=[0.5])
        pg.click("#fagain")
        pg.wait_for_selector("#ion", state="visible", timeout=5000)
        check("You are online" in pg.text_content("#ion") and "left" in pg.text_content("#ion"), "Add more time: the plan page shows the time left: " + pg.text_content("#ion"))
        secs = lambda t: sum(int(x) * m for x, m in zip(reversed(t.strip().split(":")), (1, 60, 3600)))
        t1 = secs(pg.text_content("#t"))
        check(pg.text_content(".coin").strip() == "Add more time" and pg.is_visible("#t"), "online: one page with the clock and the menu, the button now says Add more time")
        pg.wait_for_timeout(2300)
        t2 = secs(pg.text_content("#t"))
        check(1 <= t1 - t2 <= 4, f"the clock counts down by itself ({t1} -> {t2} s)")
        pg.reload()
        pg.wait_for_selector("#ion", state="visible", timeout=8000)
        t3 = secs(pg.text_content("#t"))
        check(0 < t3 <= t2, f"a reload keeps counting from the real time, it never resets ({t2} -> {t3} s)")
        # the other plan needs consent
        pg.click("input[value=endurance]", force=True)
        pg.click(".coin")
        pg.wait_for_selector("#v-msg", state="visible", timeout=5000)
        check("You still have" in pg.text_content("#mtext") and "HyperSpeed" in pg.text_content("#mtext"), "switching plans asks first: " + pg.text_content("#mtext"))
        pg.click("text=Add HyperSpeed time")
        pg.wait_for_selector("#v-final", state="visible", timeout=30000)
        check("6 min" in pg.text_content("#fmin") or "₱1" in pg.text_content("#fmin"), "the added window is priced: " + pg.text_content("#fmin"))
        check("w=1" not in pg.url.split("#")[0].split("?", 1)[-1].replace("fas=ABC", ""), "a new purchase starts from a clean address: " + pg.url)
        pg.wait_for_url(re.compile(r"[?&]w=1"), timeout=8000)   # and loads itself again once it is online
        pg.wait_for_selector("#v-final", state="visible", timeout=8000)
        # the portal restarts: the page says so and comes back by itself
        env.stop()
        pg.wait_for_selector("#v-conn", state="visible", timeout=8000)
        env.start()
        pg.wait_for_selector("#ion", state="visible", timeout=10000)
        check("You are online" in pg.text_content("#ion"), "the page reconnects by itself after a restart and shows the time left")
        check(not errors, f"no script errors: {errors}")
        # ---- speech is the default, the beeps are the fallback (stubbed speech engine and sound) ----------------------------------------------
        e5 = Env(offset=25)
        e5.start()
        e5.nds_client(MAC_A)
        e5.nds_client('aa:bb:cc:00:00:02')

        def quiet():
            """No coin window of this device is open any more (a test left one to run out)."""
            wait_until(lambda: e5.admin(f"/admin/status?mac={MAC_A}").get("s") not in ("starting", "armed", "closing"), 20)
            e5.reset_device(MAC_A)
            c = Cust("127.0.0.1", port=e5.PP, reset=True)   # (a new page starts clean: the portal keeps the last result of a device for two minutes)
            c.wait_state("idle", 5)
            c.close()

        def speech_page(mode):
            """A fresh phone: `mode` says what its speech engine does. Records what was spoken and how many beeps were played."""
            ctx = br.new_context()
            pg2 = ctx.new_page()
            pg2.on("pageerror", lambda e: print("PAGE ERROR:", e))
            pg2.add_init_script("""(function(){var mode=%r;window.__spoken=[];window.__beeps=0;window.__cancels=0;
              function Osc(){this.frequency={};this.connect=function(){};this.start=function(){window.__beeps++};this.stop=function(){}}
              function Ctx(){this.state='running';this.currentTime=0;this.destination={};this.resume=function(){return Promise.resolve()};
                this.createOscillator=function(){return new Osc()};
                this.createGain=function(){return {gain:{setValueAtTime:function(){},exponentialRampToValueAtTime:function(){}},connect:function(){}}}}
              window.AudioContext=Ctx;window.webkitAudioContext=Ctx;
              if(mode==='none'){Object.defineProperty(window,'speechSynthesis',{value:undefined,configurable:true});Object.defineProperty(window,'SpeechSynthesisUtterance',{value:undefined,configurable:true});return}
              function U(t){this.text=t}window.SpeechSynthesisUtterance=U;
              var held=[];
              var S={speaking:false,pending:false,paused:false,getVoices:function(){return[]},addEventListener:function(){},resume:function(){},
                cancel:function(){window.__cancels++},
                speak:function(u){if(u.text.trim())window.__spoken.push(u.text);
                  if(mode==='ok'){setTimeout(function(){u.onstart&&u.onstart({})},20);setTimeout(function(){u.onend&&u.onend({})},150)}
                  else if(mode==='error'){setTimeout(function(){u.onerror&&u.onerror({error:'synthesis-failed'})},20)}
                  /* 'silent': the engine takes the utterance and never starts */}};
              Object.defineProperty(window,'speechSynthesis',{value:S,configurable:true})})()""" % mode)
            return ctx, pg2

        def pay_three(pg2):
            quiet()
            e5.set_box(busy=False, coins_at=[1.0, 1.6, 2.2])
            pg2.goto(f"http://127.0.0.1:{e5.PP}/?fas=ABC")
            pg2.wait_for_function("['v-idle','v-final'].some(function(i){return document.getElementById(i).style.display==='block'})", timeout=8000)
            if pg2.is_visible("#v-final"):   # (the portal keeps the last result of a device for two minutes)
                pg2.click("#fagain")
                pg2.wait_for_selector("#v-idle", state="visible", timeout=5000)
            pg2.click(".coin")
            pg2.wait_for_function("document.getElementById('lpes').textContent.indexOf('3')>=0", timeout=12000)

        ctx2, p2 = speech_page("ok")
        pay_three(p2)
        spoken, beeps = p2.evaluate("window.__spoken"), p2.evaluate("window.__beeps")
        check("Insert coin now" in spoken and "3 pesos" in spoken and spoken[-1] == "3 pesos" and beeps == 0, f"speech works: it speaks the phrases and plays no beeps: {spoken} / {beeps} beeps")
        p2.click("#ldone")
        p2.wait_for_selector("#v-final", state="visible", timeout=10000)
        p2.wait_for_timeout(1300)
        check("You are online" in p2.evaluate("window.__spoken") and p2.evaluate("window.__beeps") == 0, "and it says 'You are online'")
        ctx2.close()

        ctx2, p2 = speech_page("none")
        pay_three(p2)
        check(p2.evaluate("window.__beeps") >= 6 and p2.evaluate("window.__spoken") == [], f"a phone that cannot speak gets the beeps: {p2.evaluate('window.__beeps')} oscillator notes")
        ctx2.close()

        ctx2, p2 = speech_page("error")
        pay_three(p2)
        check(p2.evaluate("window.__beeps") >= 6 and len(p2.evaluate("window.__spoken")) >= 3, f"speech that fails falls back to the beeps for that event: {p2.evaluate('window.__spoken')} / {p2.evaluate('window.__beeps')}")
        ctx2.close()

        ctx2, p2 = speech_page("silent")
        quiet()
        e5.set_box(busy=False, coins_at=[0.5])
        p2.goto(f"http://127.0.0.1:{e5.PP}/?fas=ABC")
        p2.wait_for_function("['v-idle','v-final'].some(function(i){return document.getElementById(i).style.display==='block'})", timeout=8000)
        if p2.is_visible("#v-final"):
            p2.click("#fagain")
            p2.wait_for_selector("#v-idle", state="visible", timeout=5000)
        p2.click(".coin")
        p2.wait_for_function("document.getElementById('lsub').textContent.indexOf('Insert coin')>=0", timeout=8000)
        t0 = time.time()
        b0 = p2.evaluate("window.__beeps")
        check(b0 == 0, "an engine that has not started yet has not beeped yet")
        p2.wait_for_function("window.__beeps>0", timeout=4000)
        check(time.time() - t0 < 3.0, f"an engine that never starts is given about a second, then the beeps play ({time.time() - t0:.1f} s)")
        ctx2.close()

        # ---- a page stuck in "Counting coins" asks again by itself, every 3 s ----------------------------------------------------------------
        import socketserver, threading, socket as sk

        class Proxy:
            """Between the phone and the portal. After the server's 'closing' state it keeps reading but passes nothing on to the page for
            that connection: the half-open socket of a phone that paused the page. Later connections pass normally."""
            def __init__(self, port, target):
                self.target, self.arm, self.cut_at, self.conns = target, False, 0, 0
                self.srv = sk.socket()
                self.srv.setsockopt(sk.SOL_SOCKET, sk.SO_REUSEADDR, 1)
                self.srv.bind(("127.0.0.1", port))
                self.srv.listen(20)
                threading.Thread(target=self.loop, daemon=True).start()

            def loop(self):
                while True:
                    try:
                        c, _ = self.srv.accept()
                    except OSError:
                        return
                    threading.Thread(target=self.serve, args=(c,), daemon=True).start()

            def serve(self, c):
                self.conns += 1
                u = sk.create_connection(("127.0.0.1", self.target), source_address=("127.0.0.2", 0))   # (the portal sees the phone as 127.0.0.2)
                threading.Thread(target=self.up, args=(c, u), daemon=True).start()
                try:
                    first = u.recv(4096)
                    while first.startswith(b"HTTP/1.1 101") and b"\r\n\r\n" not in first:   # (the portal writes the head in pieces)
                        first += u.recv(4096)
                    if first and not first.startswith(b"HTTP/1.1 101"):   # an ordinary page or picture: passed through
                        while first:
                            c.sendall(first)
                            first = u.recv(4096)
                        return
                    buf, cut = first, False
                    while buf:
                        if buf.startswith(b"HTTP"):
                            i = buf.find(b"\r\n\r\n")
                            c.sendall(buf[:i + 4])
                            buf = buf[i + 4:]
                        while len(buf) >= 2:
                            n, hdr = buf[1] & 127, 2
                            if n == 126:
                                if len(buf) < 4:
                                    break
                                n, hdr = int.from_bytes(buf[2:4], "big"), 4
                            if len(buf) < hdr + n:
                                break
                            frame, buf = buf[:hdr + n], buf[hdr + n:]
                            if cut:
                                continue
                            c.sendall(frame)
                            if self.arm and b'"s":"closing"' in frame:
                                cut, self.arm, self.cut_at = True, False, time.time()
                        d = u.recv(4096)
                        if not d:
                            break
                        buf += d
                except OSError:
                    pass
                finally:
                    for x in (c, u):
                        try:
                            x.close()
                        except OSError:
                            pass

            def up(self, c, u):
                try:
                    while True:
                        d = c.recv(4096)
                        if not d:
                            break
                        u.sendall(d)
                except OSError:
                    pass
                try:
                    u.close()
                except OSError:
                    pass

        proxy = Proxy(18395, e5.PP)
        quiet()
        e5.reset_device('aa:bb:cc:00:00:02')
        e5.set_box(busy=False, coins_at=[0.4, 0.8])
        ctx2 = br.new_context()
        p3 = ctx2.new_page()
        p3.goto(f"http://127.0.0.1:18395/?fas=ABC")
        p3.wait_for_function("['v-idle','v-final'].some(function(i){return document.getElementById(i).style.display==='block'})", timeout=8000)
        if p3.is_visible("#v-final"):
            p3.click("#fagain")
            p3.wait_for_selector("#v-idle", state="visible", timeout=5000)
        p3.click(".coin")
        p3.wait_for_selector("#ldone", state="visible", timeout=10000)
        proxy.arm = True
        conns0 = proxy.conns
        p3.click("#ldone")
        check(wait_until(lambda: proxy.cut_at > 0, 10), "the page reached 'Counting coins' and its socket then went silent")
        p3.wait_for_function("document.getElementById('lsub').textContent==='Counting coins'", timeout=3000)
        p3.wait_for_timeout(1500)
        check(p3.is_visible("#v-live") and not p3.is_visible("#v-final") and p3.text_content("#lsub") == "Counting coins", "1.5 s into the stall it still says 'Counting coins'")
        p3.wait_for_selector("#v-final", state="visible", timeout=8000)
        took = time.time() - proxy.cut_at
        check(2.5 <= took <= 5.0, f"a stalled socket in 'Counting coins' recovers by itself after about 3 s, no reload ({took:.1f} s)")
        check("You are online" in p3.text_content("#fon") and proxy.conns > conns0, "and the final view is the real result, got over a new connection")
        ctx2.close()
        proxy.srv.close()

        # ---- "Get a voucher code": the choice, the code, the file, and the file brings the code back ------------------------------------------
        quiet()
        e5.set_box(busy=False, coins_at=[0.4, 0.8, 1.2])
        ctx2 = br.new_context(accept_downloads=True)
        p4 = ctx2.new_page()
        errs = []
        p4.on("pageerror", lambda e: errs.append(str(e)))
        p4.goto(f"http://127.0.0.1:{e5.PP}/?fas=ABC")
        p4.wait_for_function("['v-idle','v-final'].some(function(i){return document.getElementById(i).style.display==='block'})", timeout=8000)
        if p4.is_visible("#v-final"):
            p4.click("#fagain")
            p4.wait_for_selector("#v-idle", state="visible", timeout=5000)
        p4.wait_for_selector("#vchoice", state="visible", timeout=5000)
        check(p4.is_checked("#mn") and "Use now" in p4.text_content("#vchoice") and "Get a voucher code" in p4.text_content("#vchoice"), "the idle view offers the choice, 'Use now' is the default")
        check(not p4.is_visible("#vlist"), "no voucher list yet")
        p4.click("label[for=mv]")
        check(p4.is_visible(".mh") and "not connected" in p4.text_content("#vchoice"), "choosing a voucher says you are not connected after paying")
        p4.click(".coin")
        p4.wait_for_selector("#ldone", state="visible", timeout=10000)
        check(p4.is_visible("#lv") and "voucher code" in p4.text_content("#lv"), "while paying, the page says the coins buy a voucher code")
        p4.wait_for_function("document.getElementById('lpes').textContent.indexOf('3')>=0", timeout=8000)
        check("voucher" in p4.text_content("#lmin"), "and the amount is shown as a voucher: " + p4.text_content("#lmin"))
        with p4.expect_download(timeout=10000) as dl_info:
            p4.click("#ldone")
            p4.wait_for_selector("#fv", state="visible", timeout=10000)
        dl = dl_info.value
        code = p4.text_content("#vcode").strip()
        check(re.fullmatch(r"[0-9A-Z]{5}-[0-9A-Z]{5}", code) is not None, f"the final view shows the code: {code}")
        check(not p4.is_visible("#fn") and "Nothing is connected yet" in p4.text_content("#fv") and "Have a voucher?" in p4.text_content("#fv") and e5.nds_get(MAC_A)["STATE"] == "Preauthenticated",
              "it says clearly that nothing is connected and how to use the code")
        check(dl.suggested_filename == f"TechNet-PisoWiFi-voucher-{code[:5]}.html", f"the file was offered by itself: {dl.suggested_filename}")
        path = os.path.join(env.tmp, dl.suggested_filename)   # (opened later from the phone's storage, under its own name)
        dl.save_as(path)
        body = open(path, encoding="utf-8").read()
        check(len(body) < 2000 and "http" in body and f"?v={code}" in body and "<script" not in body and "src=" not in body.replace("\n", " "), f"a small self-contained file ({len(body)} bytes): {body[:160]}")
        check(p4.eval_on_selector("#vcode", "e=>getComputedStyle(e).userSelect") in ("all", "text"), "the code can be selected")
        with p4.expect_download(timeout=5000) as d2:
            p4.click("#vsave")
        check(d2.value.suggested_filename == dl.suggested_filename, "the 'Save voucher file' button offers it again")
        check(p4.is_visible("#vsave") and "screenshot" in p4.text_content("#fv"), "and a hint to take a screenshot is on screen")
        p4.click("#vcopy")
        check(p4.text_content("#vcopy") in ("Copied", "Press and hold the code to copy it"), "the Copy button answers: " + p4.text_content("#vcopy"))
        # the file, opened later from the phone's storage, goes to the portal with the code in the address (customer on the Wi-Fi, not connected)
        quiet()   # (later than the two minutes the portal shows a device its last result for: the page opens on the start view)
        fp = ctx2.new_page()
        fp.goto("file://" + path)
        fp.wait_for_url(re.compile(r"127\.0\.0\.1:%d/" % e5.PP), timeout=8000)
        fp.wait_for_selector("#v-idle", state="visible", timeout=8000)
        check(fp.input_value("#vc") == code and fp.is_visible("#vc"), f"the portal fills the code in: {fp.input_value('#vc')}")
        check("v=" not in fp.url, f"and clears it from the address bar: {fp.url}")
        check(e5.nds_get(MAC_A)["STATE"] == "Preauthenticated", "pre-input only: nothing was redeemed yet")
        fp.click("#vb")
        fp.wait_for_function("document.getElementById('vm').textContent.indexOf('Added')>=0", timeout=8000)
        check(wait_until(lambda: e5.nds_get(MAC_A)["STATE"] == "Authenticated", 5), "the customer taps Redeem and is online with the minutes the coins bought")
        fp.close()
        # a visit with no 'fas' at all and a bad code in the address does no harm
        bp = ctx2.new_page()
        bp.goto(f"http://127.0.0.1:{e5.PP}/?v=%3Cb%3E")
        bp.wait_for_selector("#v-idle", state="visible", timeout=8000)
        check(bp.input_value("#vc") == "" and "v=" not in bp.url, "a visit without 'fas' and with a bad code works; nothing is filled in")
        bp.close()
        # the first page (still open) lists nothing now: the voucher was used
        p4.wait_for_selector("#v-idle", state="visible", timeout=8000)   # (the open page follows the device: online now, the code view gone)
        check(not p4.is_visible("#vlist") and p4.is_visible("#ion"), "a used voucher is not listed, and the page shows the time now running")
        check(not errs, f"no script errors: {errs}")
        ctx2.close()
        br.close()
finally:
    if e5:
        e5.close()
    env.close()
finish()
