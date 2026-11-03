# Feature 063: "Counting coins" pull-down-to-refresh reminder and stuck-socket recovery

- **Date:** 2026-11-03
- **State:** draft (waiting for owner instruction to implement)
- **Components touched:** `tools/pisoportal` (`src/page.html`, `src/brand_fil.js`, `src/http.rs`, `tests/test_browser.py`, `tests/test_page_languages.py`, `tests/golden/page_states.json`), `website/manual.html`, `docs/REAL_WORLD_TESTING.md`, `setup/RELEASE` (when releasing router `1.5.36`)

## 1. Intent

When a customer finishes inserting coins on the PisoWiFi captive portal (or the coin timer expires), the portal enters the `"closing"` state (`"Counting coins"`). Although the portal already attempts a 3-second polling reconnect (`cw = setInterval(connect, 3e3)`), customers on mobile captive-portal WebViews (Android CaptivePortalLogin and iOS CNA) sometimes remain stuck on `"Counting coins"` or `"Closing…"`.

This feature:
1. **Fixes the three underlying root causes** of the `"Counting coins"` / `"Closing…"` hang on both the guest page (`tools/pisoportal/src/page.html`) and the router WebSocket server (`tools/pisoportal/src/http.rs`).
2. **Adds a delayed, tappable `"Pull down to refresh if stuck"` reminder** (`#lstuck`) inside `#v-live` when the page has stayed in `"Counting coins"` (`s === "closing"`) for 3 seconds or longer, translated into Filipino on branded portals (`"I-pull down para i-refresh kung na-stuck"`), without altering the immediate (< 3 s) appearance of `"Counting coins"`.

## 2. Root-Cause Analysis

### Root Cause 1: Aggressive 3-second WebSocket replacement (`cw`) exhausts `WS_PER_DEVICE = 3` (`503 Service Unavailable`) and leaks half-open server reader threads for 65 seconds
- In `tools/pisoportal/src/page.html:218`, entering `s === "closing"` starts `cw = setInterval(connect, 3e3)`.
- Every 3 seconds, `connect()` (`page.html:257-264`) strips all event handlers from the existing WebSocket (`o.onopen=o.onmessage=o.onclose=o.onerror=null`), calls `o.close()`, and opens a new `/ws` connection.
- On the router (`tools/pisoportal/src/http.rs:314, 397, 641`), each client MAC is limited to `const WS_PER_DEVICE: usize = 3` concurrent WebSockets (`p.pages.take(mac.clone())`), and `ws_session` blocks in `ws_read_frame` with `const SILENT_MAX: Duration = Duration::from_secs(65)`.
- Furthermore, in `tools/pisoportal/src/http.rs:421-447`, when the writer thread of `ws_session` exits (for example, because `ws_send` failed on a dead client socket), it sets `cl2.store(true, Ordering::Relaxed)` **without shutting down the TCP stream (`conn.s`)**. The reader thread remains blocked inside `s.read_exact` for up to `SILENT_MAX` (65 seconds), holding onto its `_page` slot in `p.pages`!
- Similarly, if a mobile WebView is still completing the WebSocket handshake (`ws.readyState === 0`) when the 3-second `cw` timer fires, `connect()` aborts the in-progress socket (which already took a server slot after TCP `SYN-ACK`, without sending a WebSocket Close frame) and opens another. Once 3 slots are occupied by half-open sockets, the router rejects new `/ws` connections with `503 Service Unavailable`. Because `#v-live` is visible (`display === "block"`), `w.onclose` (`page.html:263`) suppresses `#v-conn` and leaves `"Counting coins"` frozen on screen.

### Root Cause 2: Existing proxy-stall browser test constraint (`test_browser.py:217-321`)
- `tools/pisoportal/tests/test_browser.py:217-321` tests a half-open TCP proxy (`Proxy`) that drops server-to-client frames immediately after `b'"s":"closing"'` while keeping the TCP socket open (`ws.readyState === 1`). It asserts:
  - `check(2.5 <= took <= 5.0, "a stalled socket in 'Counting coins' recovers by itself after about 3 s, no reload")`
  - `check("You are online" in p3.text_content("#fon") and proxy.conns > conns0, "and the final view is the real result, got over a new connection")`
- Therefore, the 3-second `cw` interval **must still call `connect()` to open a fresh connection when no message has arrived from the server for `>= 2500 ms`**, EXCEPT:
  1. It must **not** abort an in-progress connection handshake (`if (ws && ws.readyState === 0 && Date.now() - lc < 5e3) return;`).
  2. If the current socket * did* receive a message within the last 2.5 seconds (`Date.now() - rx < 2500`), it should simply ask the live socket for the state (`send({t:"hello",fas:fas})`) instead of tearing it down.

### Root Cause 3: Dropped `"done"` frame when tapping Done during a brief Wi-Fi blip
- In `tools/pisoportal/src/page.html:179`:
  ```js
  el("ldone").onclick=function(){this.disabled=true;this.textContent="Closing…";send({t:"done"})};
  ```
- `send()` (`page.html:153`) checks `if(ws&&ws.readyState===1)` and silently returns `false` if the WebSocket is reconnecting at the moment the customer taps **Done** (or **Cancel**).
- Even worse, when the WebSocket reconnects a moment later and receives `s === "armed"`, `view(j)` (`page.html:236-242`) never resets `el("ldone").disabled` or `el("ldone").textContent`, leaving the button permanently disabled on `"Closing…"` until the 20-second idle coin timeout expires.

## 3. Invariants Touched & Constraints

- **Money & time safety (Constitution Rule 1):** No coin-counting, credit calculation, or session ledger logic is changed. `"done"` only requests closing the active coin window; the router (`core.rs`) and coin box (`GatewayCoinslot.cpp`) remain the sole authorities on pulses counted.
- **Footprint budgets (Constitution Rule 7):**
  - `tools/pisoportal/src/page.html`: currently ~25.0 KB, must stay `<= 28 KB` (28,672 bytes). The change adds ~260 bytes.
  - `tools/pisoportal/src/brand_fil.js`: currently ~9.0 KB, must stay `<= 14 KB` (14,336 bytes). The change adds ~75 bytes.
- **Unbranded guest page golden snapshots (`test_page_golden.py`):**
  - `test_page_golden.py` snapshots all 18 states 700 ms after state entry. Because `#lstuck` stays `display:none` until 3,000 ms in `s === "closing"`, the visible text and controls at 700 ms in `closing` (and all other 17 states) remain completely identical. Only `page_bytes` and `page_sha256` in `tools/pisoportal/tests/golden/page_states.json` change.
- **Filipino language completeness (`test_page_languages.py`):**
  - Every English string on the branded portal in Filipino mode is checked against the forbidden `ENGLISH` regex (`tools/pisoportal/tests/test_page_languages.py:34-35`). The Filipino translation `"I-pull down para i-refresh kung na-stuck"` contains zero words matching `ENGLISH`.

## 4. Exact Code Blocks to Rewrite

The execution agent must apply the exact rewrites below without guessing.

### 4.1 `tools/pisoportal/src/page.html`

#### Block A — Add `#lstuck` inside `#v-live` (lines 82–93)
**Before (`tools/pisoportal/src/page.html:82-93`):**
```html
<div id="v-live" class="v card">
<span class="chip" id="lplan"></span>
<p id="lsub"></p>
<div class="big" id="lpes"></div>
<p class="mut c" id="lmin"></p>
<div id="lcd"><div class="bar"><i id="lbar" style="width:100%;background:var(--bfg)"></i></div><p class="mut c" id="lleft"></p></div>
<p class="msg w" id="lv" style="display:none">This pays for a voucher code. You are not connected yet.</p>
<p class="mut c" id="lhint" style="display:none" role="status">Coin received. Insert more, or tap Done.</p>
<p class="sub" id="ltop" style="display:none">Page closed? Keep inserting, coins still count.</p>
<button class="btn" id="ldone" type="button" style="display:none">Done</button>
<button class="btn alt" id="lcancel" type="button">Cancel</button>
</div>
```

**After:**
```html
<div id="v-live" class="v card">
<span class="chip" id="lplan"></span>
<p id="lsub"></p>
<div class="big" id="lpes"></div>
<p class="mut c" id="lmin"></p>
<div id="lcd"><div class="bar"><i id="lbar" style="width:100%;background:var(--bfg)"></i></div><p class="mut c" id="lleft"></p></div>
<p class="msg w" id="lv" style="display:none">This pays for a voucher code. You are not connected yet.</p>
<p class="mut c" id="lhint" style="display:none" role="status">Coin received. Insert more, or tap Done.</p>
<p class="sub" id="ltop" style="display:none">Page closed? Keep inserting, coins still count.</p>
<p class="sub" id="lstuck" style="display:none;cursor:pointer">Pull down to refresh if stuck</p>
<button class="btn" id="ldone" type="button" style="display:none">Done</button>
<button class="btn alt" id="lcancel" type="button">Cancel</button>
</div>
```

#### Block B — State variables `sw` (stuck-reminder timer) and `dn` (pending Done/Cancel flag) (line 122)
**Before (`tools/pisoportal/src/page.html:122`):**
```javascript
var SS=window.speechSynthesis,SU=window.SpeechSynthesisUtterance,keep=[],voice=null,unl=0,sq=0,vch=false,rt=0,rx=0,lc=0,cw=0;
```

**After:**
```javascript
var SS=window.speechSynthesis,SU=window.SpeechSynthesisUtterance,keep=[],voice=null,unl=0,sq=0,vch=false,rt=0,rx=0,lc=0,cw=0,sw=0,dn=0;
```

#### Block C — `wait(p)` and `#ldone`, `#lcancel`, `#rl`, `#lstuck` handlers (lines 176–182)
**Before (`tools/pisoportal/src/page.html:176-182`):**
```javascript
function wait(p){show("live");put("lplan",pn(p));put("lsub","Getting the coin slot ready");put("lpes","");put("lmin","Please wait, do not insert coins yet");el("lcd").style.display="none";el("ldone").style.display="none";el("lhint").style.display="none"}
var go=function(){var r=document.querySelector("input[name=p]:checked");var p=r?r.value:"endurance";wait(p);startPlan(p,false,vch&&el("mv").checked);later(prime)};
el("go").onclick=go;
el("ldone").onclick=function(){this.disabled=true;this.textContent="Closing…";send({t:"done"})};
el("lcancel").onclick=function(){send({t:"done"})};
el("fagain").onclick=function(){show("idle");again()};
el("rl").onclick=function(){location.reload()};
```

**After:**
```javascript
function wait(p){show("live");put("lplan",pn(p));put("lsub","Getting the coin slot ready");put("lpes","");put("lmin","Please wait, do not insert coins yet");el("lcd").style.display="none";el("ldone").style.display="none";el("lhint").style.display="none";el("lstuck").style.display="none"}
var go=function(){var r=document.querySelector("input[name=p]:checked");var p=r?r.value:"endurance";wait(p);startPlan(p,false,vch&&el("mv").checked);later(prime)};
el("go").onclick=go;
el("ldone").onclick=function(){this.disabled=true;this.textContent="Closing…";dn=1;if(!send({t:"done"}))connect()};
el("lcancel").onclick=function(){dn=1;if(!send({t:"done"}))connect()};
el("fagain").onclick=function(){show("idle");again()};
el("rl").onclick=el("lstuck").onclick=function(){location.reload()};
```

#### Block D — `view(j)` handling of `s === "closing"`, `s === "armed"`, `cw`, `sw`, and `dn` (lines 214–244)
**Before (`tools/pisoportal/src/page.html:214-244`):**
```javascript
function view(j){
var s=j.s;
if(s!=="armed")stopTick();if(s!=="idle"&&s!=="final"&&s!=="paused")tstop();if(s!=="final"){foff(false);clearTimeout(wk);wk=null}
/* a window that stays in "Counting coins" is asked about again by a new connection every 3 s (a phone may have paused the old one) */
if(s==="closing"){if(!cw)cw=setInterval(connect,3e3)}else if(cw){clearInterval(cw);cw=0}
if(s==="idle"||s==="paused"){
 show("idle");el("ldone").disabled=false;el("ldone").textContent="Done";pj=s==="paused";
 var on=el("ion"),ps=pj,tm=j.left>0;
 if(s==="idle"){vch=!!j.vc;vlist(j.vs||[])}else vlist([]);
 el("vchoice").style.display=vch?"block":"none";
 el("snd").style.display=s==="idle"&&j.sd?"block":"none";
 el("hd").style.display=tm?"none":"flex";el("vd").style.display=ps?"none":"block";
 if(tm){on.style.display="block";put("tplan",pn(j.plan));el("tused").style.display=j.plan==="hyper"&&!ps?"inline":"none";put("tused",j.used_mb+" MB used");el("tslow").style.display=j.slow>0&&!ps?"block":"none";
  el("pz").style.display=!ps&&j.pm>0&&!(j.pn>0)?"block":"none";el("rs").style.display=ps?"block":"none";el("rs").disabled=false;el("rs").textContent="Resume time";el("pz").removeAttribute("aria-disabled");
  var cap=ps?"":j.pn>0&&j.pm>0?"Pay \u20b1"+j.pn+" more to unlock a pause":j.pl<1?"No pauses left":j.left<120?"Pause needs 2 min left":j.pw>0?"You can pause again in "+j.pw+" s":j.pl+(j.pl>1?" pauses":" pause")+" left";
  put("pzc",cap);if(cap&&j.pl<1||j.left<120||j.pw>0)el("pz").setAttribute("aria-disabled","true");
  if(ps){tstop();tl=j.left;put("t",clock(tl));put("fleft",clock(tl));el("tbar").style.width="100%";put("chip","Paused");el("chip").className="chip gold";put("tin","Paused, internet almost stopped · time left");el("tbar").style.background="var(--mut)";put("tby","Time is on hold. "+(j.by>0?at(j.by*1e3,"Resume by"):"Resume soon")+"; time not resumed by then is lost.")}else tstart(j.left)}
 else{on.style.display="none";tstop();tt=0}
 put("go",tm?"Add more time":"Insert Coin");el("go").className="btn coin"+(ps?" alt":"");put("gos",tm?"":"Coins add up: every coin adds more time");
 var b=el("ibusy");if(j.cooldown>0){b.style.display="block";b.textContent="Too many empty tries. Wait about "+j.cooldown+" s."}else if(j.busy>0){b.style.display="block";b.textContent="Another customer is paying. Try again in a few seconds."}else b.style.display="none";
 return}
if(s==="starting"){wait(j.plan);el("lv").style.display=j.v?"block":"none";return}
if(s==="armed"){
 show("live");el("lcd").style.display="block";put("lplan",pn(j.plan));put("lsub","Insert coin now");el("lv").style.display=j.v?"block":"none";
 put("lpes","₱"+j.pulses);el("lpes").className="big"+(j.pulses>0?" p":"");put("lmin",j.pulses>0?"= "+fmt(j.minutes)+(j.v?" voucher":" of Wi-Fi"):j.v?"Each coin adds voucher time":"Each coin adds Wi-Fi time");
 if(j.pulses>pes){var d=j.pulses-pes;say(j.pulses+(j.pulses===1?" peso":" pesos"),function(){ding(d)})}else if(!pes&&!j.pulses&&!tk)say("Insert coin now",function(){ding(1)});
 pes=j.pulses;left=j.remaining;total=j.total||FIRST;bar();
 el("ldone").style.display=j.pulses>0?"block":"none";el("lhint").style.display=j.pulses>0?"block":"none";el("ltop").style.display=tl>0?"block":"none";el("lcancel").style.display=j.pulses>0?"none":"block";
 if(!tk)tk=setInterval(function(){if(left>0)left--;bar()},1000);return}
if(s==="closing"){show("live");el("lcd").style.display="none";el("ldone").style.display="none";el("lcancel").style.display="none";el("lhint").style.display="none";el("lv").style.display=j.v?"block":"none";put("lplan",pn(j.plan));put("lsub","Counting coins");put("lpes","₱"+j.pulses);put("lmin","");return}
```

**After:**
```javascript
function view(j){
var s=j.s;
if(s!=="armed"){stopTick();dn=0}if(s!=="idle"&&s!=="final"&&s!=="paused")tstop();if(s!=="final"){foff(false);clearTimeout(wk);wk=null}
/* a window that stays in "Counting coins" is asked about again every 3 s (a new connection when the old one has been quiet, without cutting one still opening) */
if(s==="closing"){if(!cw)cw=setInterval(function(){if(ws&&ws.readyState===0&&Date.now()-lc<5e3)return;if(!ws||ws.readyState!==1||Date.now()-rx>=2500)connect();else send({t:"hello",fas:fas})},3e3);if(!sw)sw=setTimeout(function(){el("lstuck").style.display="block"},3e3)}else{if(cw){clearInterval(cw);cw=0}if(sw){clearTimeout(sw);sw=0}el("lstuck").style.display="none"}
if(s==="idle"||s==="paused"){
 show("idle");el("ldone").disabled=false;el("ldone").textContent="Done";pj=s==="paused";
 var on=el("ion"),ps=pj,tm=j.left>0;
 if(s==="idle"){vch=!!j.vc;vlist(j.vs||[])}else vlist([]);
 el("vchoice").style.display=vch?"block":"none";
 el("snd").style.display=s==="idle"&&j.sd?"block":"none";
 el("hd").style.display=tm?"none":"flex";el("vd").style.display=ps?"none":"block";
 if(tm){on.style.display="block";put("tplan",pn(j.plan));el("tused").style.display=j.plan==="hyper"&&!ps?"inline":"none";put("tused",j.used_mb+" MB used");el("tslow").style.display=j.slow>0&&!ps?"block":"none";
  el("pz").style.display=!ps&&j.pm>0&&!(j.pn>0)?"block":"none";el("rs").style.display=ps?"block":"none";el("rs").disabled=false;el("rs").textContent="Resume time";el("pz").removeAttribute("aria-disabled");
  var cap=ps?"":j.pn>0&&j.pm>0?"Pay \u20b1"+j.pn+" more to unlock a pause":j.pl<1?"No pauses left":j.left<120?"Pause needs 2 min left":j.pw>0?"You can pause again in "+j.pw+" s":j.pl+(j.pl>1?" pauses":" pause")+" left";
  put("pzc",cap);if(cap&&j.pl<1||j.left<120||j.pw>0)el("pz").setAttribute("aria-disabled","true");
  if(ps){tstop();tl=j.left;put("t",clock(tl));put("fleft",clock(tl));el("tbar").style.width="100%";put("chip","Paused");el("chip").className="chip gold";put("tin","Paused, internet almost stopped · time left");el("tbar").style.background="var(--mut)";put("tby","Time is on hold. "+(j.by>0?at(j.by*1e3,"Resume by"):"Resume soon")+"; time not resumed by then is lost.")}else tstart(j.left)}
 else{on.style.display="none";tstop();tt=0}
 put("go",tm?"Add more time":"Insert Coin");el("go").className="btn coin"+(ps?" alt":"");put("gos",tm?"":"Coins add up: every coin adds more time");
 var b=el("ibusy");if(j.cooldown>0){b.style.display="block";b.textContent="Too many empty tries. Wait about "+j.cooldown+" s."}else if(j.busy>0){b.style.display="block";b.textContent="Another customer is paying. Try again in a few seconds."}else b.style.display="none";
 return}
if(s==="starting"){wait(j.plan);el("lv").style.display=j.v?"block":"none";return}
if(s==="armed"){
 show("live");if(dn)send({t:"done"});else{el("ldone").disabled=false;el("ldone").textContent="Done"}
 el("lcd").style.display="block";put("lplan",pn(j.plan));put("lsub","Insert coin now");el("lv").style.display=j.v?"block":"none";
 put("lpes","₱"+j.pulses);el("lpes").className="big"+(j.pulses>0?" p":"");put("lmin",j.pulses>0?"= "+fmt(j.minutes)+(j.v?" voucher":" of Wi-Fi"):j.v?"Each coin adds voucher time":"Each coin adds Wi-Fi time");
 if(j.pulses>pes){var d=j.pulses-pes;say(j.pulses+(j.pulses===1?" peso":" pesos"),function(){ding(d)})}else if(!pes&&!j.pulses&&!tk)say("Insert coin now",function(){ding(1)});
 pes=j.pulses;left=j.remaining;total=j.total||FIRST;bar();
 el("ldone").style.display=j.pulses>0?"block":"none";el("lhint").style.display=j.pulses>0?"block":"none";el("ltop").style.display=tl>0?"block":"none";el("lcancel").style.display=j.pulses>0?"none":"block";
 if(!tk)tk=setInterval(function(){if(left>0)left--;bar()},1000);return}
if(s==="closing"){show("live");el("lcd").style.display="none";el("ldone").style.display="none";el("lcancel").style.display="none";el("lhint").style.display="none";el("ltop").style.display="none";el("lv").style.display=j.v?"block":"none";put("lplan",pn(j.plan));put("lsub","Counting coins");put("lpes","₱"+j.pulses);put("lmin","");return}
```
*(Note: In `s === "mismatch"` on line 253, `var o=pn(j.plan),sw=plan,n=pn(sw);` uses a local `var sw` inside that block; to avoid shadowing the outer `sw` timer variable inside `view(j)`, rename that local variable in `s === "mismatch"` from `sw` to `sp`: `if(s==="mismatch"){var o=pn(j.plan),sp=plan,n=pn(sp);msg("You still have "+fsec(j.left)+" of "+o+" time. Add more "+o+" time, or switch to "+n+" and give up the time you have left.",[["Add "+o+" time",function(){startPlan(j.plan,false)}],["Switch to "+n+" · lose my time",function(){startPlan(sp,true)},1]]);return}`.)*

#### Block E — Re-send pending `"done"` on `w.onopen` (line 261)
**Before (`tools/pisoportal/src/page.html:261`):**
```javascript
 w.onopen=function(){if(ws!==w)return;rx=Date.now();send({t:"hello",fas:fas})};
```

**After:**
```javascript
 w.onopen=function(){if(ws!==w)return;rx=Date.now();send({t:"hello",fas:fas});if(dn)send({t:"done"})};
```

---

### 4.2 `tools/pisoportal/src/brand_fil.js`

**Before (`tools/pisoportal/src/brand_fil.js:48-51`):**
```javascript
"Insert coin now":"Magpasok na ng barya",
"Counting coins":"Binibilang ang mga barya",
"Each coin adds voucher time":"Bawat barya ay nagdaragdag ng voucher time",
```

**After:**
```javascript
"Insert coin now":"Magpasok na ng barya",
"Counting coins":"Binibilang ang mga barya",
"Pull down to refresh if stuck":"I-pull down para i-refresh kung na-stuck",
"Each coin adds voucher time":"Bawat barya ay nagdaragdag ng voucher time",
```

---

### 4.3 `tools/pisoportal/src/http.rs`

#### Block A — Raise `WS_PER_DEVICE` from `3` to `6` (line 314)
**Before (`tools/pisoportal/src/http.rs:312-315`):**
```rust
/// Connections per client address, and open coin pages per device.
const CONN_PER_IP: usize = 8;
const WS_PER_DEVICE: usize = 3;
```

**After:**
```rust
/// Connections per client address, and open coin pages per device.
const CONN_PER_IP: usize = 8;
const WS_PER_DEVICE: usize = 6;
```

#### Block B — Shut down TCP socket when writer thread exits so reader thread unblocks immediately (lines 445–447)
**Before (`tools/pisoportal/src/http.rs:445-447`):**
```rust
        }
        cl2.store(true, Ordering::Relaxed);
    });
```

**After:**
```rust
        }
        cl2.store(true, Ordering::Relaxed);
        if let Ok(s) = w2.lock() {
            let _ = s.shutdown(Shutdown::Both);
        }
    });
```

---

### 4.4 `tools/pisoportal/tests/golden/page_states.json`
Update lines 4–5 (`page_bytes` and `page_sha256`) to match the exact byte length and SHA-256 of `tools/pisoportal/src/page.html` after replacing `%FIRST%` with `20`. All 18 state snapshots in `states` remain untouched.

---

### 4.5 `tools/pisoportal/tests/test_browser.py` and `tools/pisoportal/tests/test_page_languages.py`

#### `tools/pisoportal/tests/test_browser.py` (around lines 313–321)
In the `"Counting coins"` proxy stall test (`p3`), verify:
1. At 1.5 s into `"Counting coins"`, `#lstuck` is still hidden (`not p3.is_visible("#lstuck")`).
2. After `#v-final` arrives, `#lstuck` is hidden (`not p3.is_visible("#lstuck")`).
3. Also add a focused check (or extend the proxy stall check before closing `ctx2`) where a page stays in `s === "closing"` for `> 3.1 s` (e.g., via `p3.evaluate` or a paused closing state) and confirm `#lstuck` becomes visible (`p3.is_visible("#lstuck")`) with text `"Pull down to refresh if stuck"`, and hides again once `s === "final"` arrives.

#### `tools/pisoportal/tests/test_page_languages.py` (around line 147)
After the loop over `{**G.CASES, **EXTRA}`, open a page in `G.CASES["closing"]` under the Filipino context (`ctx`), wait `3200 ms` (`pg.wait_for_timeout(3200)`), and assert:
- `pg.is_visible("#lstuck")`
- `"I-pull down para i-refresh kung na-stuck" in pg.inner_text("#lstuck")`
- `not [l for l in lines(pg) if ENGLISH.search(l)]`

---

### 4.6 `website/manual.html` and `docs/REAL_WORLD_TESTING.md`

#### `website/manual.html` (line 377)
**Before (`website/manual.html:377`):**
```html
<tr><td>The payment page stays on "Counting coins"</td><td>It fixes itself: after 3 seconds the page asks the router again, and keeps asking every 3 seconds, so a phone that paused the page gets its result without a reload. The page also checks its connection every few seconds, and again when the phone wakes or the Wi-Fi comes back.</td></tr>
```

**After:**
```html
<tr><td>The payment page stays on "Counting coins"</td><td>It fixes itself: after 3 seconds the page asks the router again, and keeps asking every 3 seconds, so a phone that paused the page gets its result without a reload. After 3 seconds on "Counting coins" a <b>Pull down to refresh if stuck</b> line also appears (customers can pull down or tap it to reload).</td></tr>
```

#### `docs/REAL_WORLD_TESTING.md` (after line 370, before `## 10.`)
Append hardware check **RS72**:
```markdown
- [ ] **RS72** (router 1.5.36, feature 063, not tried yet) On a real router and a real phone (Android and iPhone): (a) Pay ₱1 and tap **Done**: during a normal `< 2 s` close, `"Counting coins"` finishes into `"You are online"` without flashing `"Pull down to refresh if stuck"`. (b) Delay or hold the `"Counting coins"` screen for more than 3 seconds (or test over a weak Wi-Fi signal): after 3 seconds `"Pull down to refresh if stuck"` appears under `"Counting coins"` (or `"I-pull down para i-refresh kung na-stuck"` when Filipino is selected on a branded portal), and tapping it or pulling down reloads the page cleanly to the final online state.
```

## 5. Success Criteria

1. **Immediate `"Counting coins"` unchanged (< 3 s):** Entering `s === "closing"` hides `#lstuck` initially, so normal coin closes (`< 3 s`) and all 18 golden states in `tools/pisoportal/tests/test_page_golden.py` have identical visible text and controls.
2. **3-second reminder appears and is tappable:** If the portal stays in `s === "closing"` for `>= 3 s`, `#lstuck` (`"Pull down to refresh if stuck"`) becomes visible (`display: block`), tapping `#lstuck` reloads the page (`location.reload()`), and leaving `s === "closing"` clears the timer and hides `#lstuck`.
3. **Filipino translation on branded portals:** On a branded portal in Filipino mode, `#lstuck` renders as `"I-pull down para i-refresh kung na-stuck"` with zero English words flagged by `test_page_languages.py`.
4. **Root causes of stuck `"Counting coins"` / `"Closing…"` resolved:**
   - `cw` does not abort an in-progress WebSocket handshake (`readyState === 0` for `< 5 s`), reconnects when no server frame has arrived for `>= 2.5 s` (preserving the 3 s proxy stall recovery in `test_browser.py`), and sends `{t:"hello",fas:fas}` when the socket is live and active.
   - Tapping **Done** (`#ldone`) or **Cancel** (`#lcancel`) while the WebSocket is disconnected sets `dn = 1`, triggers `connect()`, and sends `{t:"done"}` as soon as the socket opens; if `s === "armed"` arrives while `dn === 0`, `#ldone` is re-enabled and reset to `"Done"`.
   - In `tools/pisoportal/src/http.rs`, `WS_PER_DEVICE` is `6` and `ws_session`'s writer thread calls `shutdown(Shutdown::Both)` on exit so half-open reader threads release their per-MAC slot immediately instead of blocking for 65 seconds.
5. **Footprint budgets respected:** `tools/pisoportal/src/page.html` `<= 28 KB` and `tools/pisoportal/src/brand_fil.js` `<= 14 KB`.
6. **Documentation & hardware checklist updated:** `website/manual.html` (chapter 13 troubleshooting row) and `docs/REAL_WORLD_TESTING.md` (`RS72`) are updated and pass `python scripts/tests/test_manual.py` and `python scripts/check_links.py`.
7. **All quality & release verification gates pass:** `cargo test` in `tools/pisoportal`, `test_page_golden.py`, `test_page_languages.py`, `test_browser.py`, `check_security_rules.sh`, `check_links.py`, `check_vendored.py`, and `verify_release.py` pass with 0 failures.

## 6. Out of Scope
- Changes to ESP32 coin box firmware (`esp32_firmware/`) or Android app (`app/`).
- Changing the 20-second coin insertion timeout or the 800 ms box coin-settling window.

## 7. Owner Decisions
- **Release vehicle:** Because this feature modifies `tools/pisoportal/src/page.html`, `tools/pisoportal/src/brand_fil.js`, and `tools/pisoportal/src/http.rs`, `scripts/verify_release.py` requires bumping `setup/RELEASE` from `1.5.35` to `1.5.36` and building the router programs via the `beta` branch (`router-program.yml`) before merging to `main` (per `CLAUDE.md` and Feature 060's `check_router_program.py`).
