#!/bin/sh
# ThemeSpec for openNDS (login_option_enabled '3'): "flash coin", pay for Wi-Fi with coins. Install as
# /usr/lib/opennds/flash_coin.sh next to flash_coin_lib.sh. Same structure as the paper-voucher theme it grew from
# (header / footer / a roll file of sessions / auth_log / auto-reconnect by MAC), except that the "voucher" is a coin
# payment: the local coin-slot manager (coinslot-listener.sh) counts the coins and /verify tells this theme what was paid;
# this theme alone writes the roll and authenticates the device. Plain HTML + ~2 KB of CSS: nothing to download.
#
# Page sequence, driven by the form variable "coinact":
#   (none)    welcome (rates, "Insert Coin") | automatic reconnect | "paused, tap Resume" | coins waiting to be used
#   start     arm the coin slot for the chosen plan, then the waiting page
#   wait      coin window: pesos inserted, time earned, live countdown
#   finish    stop accepting, wait for in-flight coins, then the result
#   connect   (landing=yes) verify the payment, record it on the roll, authenticate the device, tell the manager it is used
#   vform / voucher   restore a session by its code on a new device
# Minutes and speed caps are decided by the manager and the roll, never taken from the browser.

title="flash_coin"
. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"

# The coin-slot session id is a hash of the client's secret openNDS id: other clients cannot guess it.
coinslot_sid() { printf '%s' "$hid" | sha256sum | cut -c1-32; }
# The refresh link needs the base64 characters that are special in a URL percent-encoded.
fas_urlsafe() { printf '%s' "$fas" | sed 's/+/%2B/g; s,/,%2F,g; s/=/%3D/g'; }

fmt_min() {  # 30 -> "30 min", 60 -> "1 hr", 690 -> "11 hr 30 min", 1440 -> "24 hrs"
	_m="${1:-0}"
	if [ "$_m" -lt 60 ]; then echo "$_m min"; return; fi
	_h=$((_m / 60)); _r=$((_m % 60)); _u="hr"; [ "$_h" -gt 1 ] && _u="hrs"
	if [ "$_r" -gt 0 ]; then echo "$_h $_u $_r min"; else echo "$_h $_u"; fi
}
plan_name() { case "$1" in endurance) echo "Endurance" ;; *) echo "HyperSpeed" ;; esac; }

# ---------------------------------------------------------------------------
# Frame (libopennds.sh calls header() ONCE per request, before display_terms / landing_page /
# generate_splash_sequence, so header() also decides which page to show and whether it reloads itself)
# ---------------------------------------------------------------------------
header() {
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	PAGE=""; REFRESH=""
	if [ "$landing" != "yes" ] && [ "$terms" != "yes" ]; then choose_page; fi
	refreshtag=""
	if [ -n "$REFRESH" ]; then
		refreshtag="<meta http-equiv=\"refresh\" content=\"${REFRESH%% *}; url=/opennds_preauth/?fas=$(fas_urlsafe)&coinact=${REFRESH##* }&coinplan=$coinplan&coinforfeit=$coinforfeit\">"
		case "$PAGE" in wait) refreshtag="<noscript>$refreshtag</noscript>" ;; esac   # with scripts on, these pages update themselves
	fi
	cat << HTML
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-store">
$refreshtag
<title>$gatewayname</title>
<style>
:root{--bg:#0f1715;--card:#16221f;--line:#243630;--fg:#e8f0ec;--mut:#8aa59a;--h:#38ef7d;--e:#4cc9f0;--ok:#38ef7d;--bad:#ff4b4b}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;justify-content:center}
.w{width:100%;max-width:440px;padding:20px 16px 36px}
h1{font-size:19px;margin:0 0 4px;text-align:center;color:var(--ok);text-transform:uppercase;letter-spacing:1px;text-shadow:0 0 10px #38ef7d66}
.sub{color:var(--mut);text-align:center;margin:0 0 18px;font-size:14px}
.plans{display:grid;gap:12px}
.plan input{position:absolute;opacity:0}
.card{display:block;background:var(--card);border:2px solid var(--line);border-radius:16px;padding:14px 16px;cursor:pointer}
.plan input:checked+.card{border-color:var(--c)}
.plan input:focus-visible+.card{outline:2px solid var(--fg)}
.card b{color:var(--c);font-size:17px}
.card small{display:block;color:var(--mut);font-size:13px;margin:2px 0 8px}
.card div{display:flex;justify-content:space-between;padding:5px 0;border-top:1px solid var(--line);font-size:15px}
.card div span:first-child{font-weight:600}
.h{--c:var(--h)}.e{--c:var(--e)}
.note{color:var(--mut);font-size:12.5px;text-align:center;margin:10px 4px 0}
button{font:inherit}
.coin{display:block;width:152px;height:152px;margin:22px auto 6px;border:0;border-radius:50%;font-weight:700;font-size:20px;line-height:1.2;color:#241700;cursor:pointer;background:radial-gradient(circle at 32% 28%,#ffe58a,#f5a300);box-shadow:0 8px 28px #f5a30055}
.coin:active{transform:scale(.97)}
.coin small{display:block;font-weight:500;font-size:12px}
.btn{display:block;width:100%;padding:14px;margin:12px 0 0;border:0;border-radius:12px;font-weight:600;font-size:17px;color:#04140c;background:linear-gradient(135deg,#11998e,#38ef7d);cursor:pointer}
.btn.alt{background:transparent;color:var(--fg);border:1px solid var(--line)}
.big{font-size:34px;font-weight:700;text-align:center;margin:6px 0}
.mut{color:var(--mut);text-align:center;font-size:14px}
.bar{height:8px;background:var(--line);border-radius:4px;overflow:hidden;margin:14px 0}
.bar i{display:block;height:100%;background:var(--h)}
.code{font:700 28px/1.2 ui-monospace,Menlo,Consolas,monospace;letter-spacing:3px;text-align:center;background:var(--card);border:2px dashed var(--line);border-radius:12px;padding:12px;margin:12px 0}
.msg{background:var(--card);border-left:4px solid var(--bad);border-radius:8px;padding:12px 14px;margin:12px 0}
.snd{display:block;margin:10px auto 0;padding:7px 14px;border:1px solid var(--line);border-radius:20px;background:transparent;color:var(--mut);font-size:13px;cursor:pointer}
a{color:var(--mut)}
input[type=text]{width:100%;padding:13px;border-radius:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:600 20px ui-monospace,Menlo,Consolas,monospace;text-align:center;text-transform:uppercase;letter-spacing:3px}
.foot{text-align:center;font-size:12px;color:var(--mut);margin-top:22px}
</style>
</head><body><div class="w">
<h1>$gatewayname</h1>
<script>
/* Any tap that leaves the page shows it was received, and a second tap while the first is still loading is ignored. */
document.addEventListener("submit",function(e){var f=e.target,b=f.querySelector&&f.querySelector("button[type=submit]");
if(f.__t&&Date.now()-f.__t<8000){e.preventDefault();return}f.__t=Date.now();if(b&&f.id!=="coinform"){b.textContent="Please wait \u00b7 Sandali lang";b.style.opacity=".6"}},true);
window.addEventListener("pageshow",function(e){if(e.persisted)location.reload()});
</script>
HTML
}

footer() {
	cat << HTML
<p class="foot"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&terms=yes">Terms</a><br>&#9889; Secure Network</p>
</div></body></html>
HTML
	exit 0
}

# A form that posts back to the portal. $1 label, $2 coinact, $3 css class, $4 "landing" to also set landing=yes.
action_button() {
	_l=""; [ "$4" = "landing" ] && _l="<input type=\"hidden\" name=\"landing\" value=\"yes\">"
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="$2"><input type="hidden" name="coinplan" value="$coinplan"><input type="hidden" name="coinforfeit" value="$coinforfeit">$_l
<button class="btn $3" type="submit">$1</button></form>
HTML
}

# Start a coin window for a given plan; $3 = "forfeit" also confirms giving up the time left on the other plan.
plan_button() {
	_f=""; [ "$3" = "forfeit" ] && _f="<input type=\"hidden\" name=\"coinforfeit\" value=\"yes\">"
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="start"><input type="hidden" name="coinplan" value="$2">$_f
<button class="btn $4" type="submit">$1</button></form>
HTML
}

# ---------------------------------------------------------------------------
# Deciding what to show (runs from header(), before any page content)
# ---------------------------------------------------------------------------
# Sets PAGE: welcome wait counting busy mismatch error result reconnect pausedhome restored voucherform pausecheck paused
# pauseerr unavailable, and REFRESH ("<seconds> <coinact>") when the page should reload itself.
choose_page() {
	sid=$(coinslot_sid); mac="$clientmac"
	case "$coinplan" in endurance) ;; *) coinplan="hyper" ;; esac
	[ "$coinforfeit" = "yes" ] || coinforfeit=""
	# Time that is already paid for needs no coin slot: a device coming back after a reboot or power cut reconnects even
	# if the manager is down.
	if [ -z "$coinact" ]; then
		cst=$(coinslot "/status?sid=$sid")
		if [ "$(printf '%s' "$cst" | jget state)" = "done" ] && [ "$(printf '%s' "$cst" | jget claimed)" = "false" ] &&
			[ "$(printf '%s' "$cst" | jget pulses)" -gt 0 ] 2> /dev/null; then
			PAGE="result"; return          # coins of an earlier window that never became access come first
		fi
		flash_peek "$mac"
		case "$P_STATE" in
			running) if [ "$status" != "authenticated" ]; then PAGE="reconnect"; return; fi ;;
			paused) PAGE="pausedhome"; return ;;
		esac
	fi
	flash_info || { PAGE="unavailable"; return; }

	case "$coinact" in
		start)
			flash_peek "$mac"
			if [ "$P_STATE" = running ] || [ "$P_STATE" = paused ]; then
				if [ "$R_PLAN" != "$coinplan" ] && [ "$coinforfeit" != "yes" ]; then
					PAGE="mismatch"; otherplan="$R_PLAN"; otherleft="$R_LEFT"; return
				fi
			fi
			answer=$(coinslot "/start?sid=$sid&plan=$coinplan&mac=$mac&flash=1${coinforfeit:+&forfeit=1}")
			if [ "$(printf '%s' "$answer" | jget state)" = "error" ]; then
				err=$(printf '%s' "$answer" | jget error)
				case "$err" in
					SLOT_BUSY | COOLDOWN) PAGE="busy"; busyretry=$(printf '%s' "$answer" | jget retry) ;;
					*) PAGE="error" ;;
				esac
				return
			fi
			choose_wait_page ;;
		wait) choose_wait_page ;;
		finish)
			coinslot "/finish?sid=$sid" > /dev/null
			cst=$(coinslot "/status?sid=$sid"); state=$(printf '%s' "$cst" | jget state)
			if [ "$state" = "done" ] || [ "$state" = "none" ] || [ "$state" = "error" ]; then
				PAGE="result"
			else
				PAGE="counting"; REFRESH="2 finish"
			fi ;;
		pausecheck) flash_peek "$mac"; PAGE="pausecheck" ;;
		pause)
			flash_pause "$mac" "${pausepesos:-10}"; prc=$?
			if [ "$prc" = 0 ]; then flash_peek "$mac"; PAGE="paused"; else PAGE="pauseerr"; fi ;;
		vform) PAGE="voucherform" ;;
		voucher)
			flash_restore "$vcode" "$mac"; vrc=$?
			if [ "$vrc" = 0 ]; then flash_peek "$mac"; PAGE="restored"; else PAGE="voucherform"; fi ;;
		*) PAGE="welcome" ;;
	esac
}

choose_wait_page() {
	cst=$(coinslot "/status?sid=$sid")
	state=$(printf '%s' "$cst" | jget state)
	case "$state" in
		starting | armed) PAGE="wait"; REFRESH="2 wait"; coinplan=$(printf '%s' "$cst" | jget plan) ;;
		done) PAGE="result" ;;
		error)
			err=$(printf '%s' "$cst" | jget error)
			# The coin slot was in use when the window tried to arm: the busy page tells the customer when it is free.
			if [ "$err" = "SLOT_BUSY" ]; then PAGE="busy"; busyretry=10; else PAGE="error"; fi ;;
		*) PAGE="welcome" ;;
	esac
}

# ---------------------------------------------------------------------------
# Page content (header() has already been printed by libopennds.sh)
# ---------------------------------------------------------------------------
generate_splash_sequence() {
	case "$PAGE" in
		wait) page_wait ;;
		counting) page_counting ;;
		busy) page_busy ;;
		mismatch) page_mismatch ;;
		error) page_error ;;
		result) page_result ;;
		reconnect) page_reconnect ;;
		pausedhome) page_pausedhome ;;
		restored) page_restored ;;
		voucherform) page_voucherform ;;
		pausecheck) page_pausecheck ;;
		paused) page_paused ;;
		pauseerr) page_pauseerr ;;
		unavailable) page_unavailable ;;
		*) page_welcome ;;
	esac
	footer
}

# Rates of one plan from the manager: lines "pesos minutes".
tier_rows() {
	coinslot "/tiers?plan=$1" | while read -r _p _m; do
		[ -n "$_p" ] && echo "<div><span>&#8369;$_p</span><span>$(fmt_min "$_m")</span></div>"
	done
}

page_welcome() {
	hchk=""; echk=""; [ "$coinplan" = "endurance" ] && echk="checked" || hchk="checked"
	edown=$(($(printf '%s' "$info" | jget e_down) / 1000)); eup=$(($(printf '%s' "$info" | jget e_up) / 1000))
	fair=$(printf '%s' "$info" | jget fair_gb); pausepesos=$(printf '%s' "$info" | jget pause_pesos)
	cat << HTML
<p class="sub">$(if [ "$status" = authenticated ]; then echo "You are connected &middot; add time &middot; Dagdagan ang oras"; else echo "Wi-Fi rates &middot; Presyo ng Wi-Fi"; fi)</p>
<form id="coinform" action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="start">
<div class="plans">
<label class="plan h"><input type="radio" name="coinplan" value="hyper" $hchk>
<span class="card"><b>&#9889; HyperSpeed</b><small>Full speed, no limits &middot; Walang limit ang bilis</small>
$(tier_rows hyper)
</span></label>
<label class="plan e"><input type="radio" name="coinplan" value="endurance" $echk>
<span class="card"><b>&#9203; Endurance</b><small>Up to $edown Mbps down / $eup Mbps up &middot; Mas matagal<br>Pause once on &#8369;$pausepesos+ &middot; I-pause isang beses</small>
$(tier_rows endurance)
</span></label>
</div>
<button class="coin" type="submit">Insert Coin<small>Maglagay ng barya</small></button>
</form>
<p class="note">Coins add up &middot; Nag-iipon ang oras. HyperSpeed may slow down after $fair GB (fair use).</p>
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=vform">I have a code (restore my time) &middot; May code ako</a></p>
<div id="live" style="display:none">
<p class="sub" id="lsub"></p>
<div class="big" id="lpes">&#8369;0</div>
<p class="mut" id="lmin"></p>
<div id="lcd" style="display:none"><div class="bar"><i id="lbar" style="width:100%"></i></div><p class="mut" id="lleft"></p></div>
<div class="msg" id="lon" style="display:none;border-left-color:var(--ok)"><b>&#10003; You're online &middot; Nakakonekta ka na</b><br><span id="lont">Add more coins now for more time.</span></div>
<div id="lmis" style="display:none"></div>
<div id="lfin" style="display:none"><p class="mut">Your code restores your time on any device:</p><div class="code" id="lcode"></div>
<a class="btn" style="text-decoration:none;text-align:center" href="http://$gatewayfqdn/?$randquery">Continue browsing</a></div>
<button class="btn alt" type="button" id="ldone" style="display:none">Done &middot; Tapos na</button>
<a class="btn alt" id="lagain" style="display:none;text-decoration:none;text-align:center" href="/opennds_preauth/?fas=$(fas_urlsafe)">Try again</a>
</div>
<script>
/* One live page: Insert Coin talks to the router's small coin API (port $infostream) instead of loading portal pages.
   The coins show the moment the box counts them, the device is online on the first coin, and the window's total is
   priced once when it closes. If the API cannot be reached, the regular pages take over (also used without scripts). */
(function(){
var f=document.getElementById("coinform"),SP=${infostream:-0},SID="$sid",FIRST=${infofirst:-30},IDLE=${infoidle:-15},
A=window.AudioContext||window.webkitAudioContext;
if(!f||!SP||!window.fetch||!window.JSON||!window.FormData||!window.URLSearchParams)return;
var base="http://"+location.hostname+":"+SP,ctx=null,es=null,pt=null,tk=null,pes=0,on=false,fin=false,armed=false,left=0,tot=FIRST,plan="hyper";
function el(i){return document.getElementById(i)}
function show(i,v){var e=el(i);if(e)e.style.display=v?"":"none"}
function put(i,t){var e=el(i);if(e&&e.textContent!==t)e.textContent=t}
function pn(p){return p==="endurance"?"Endurance":"HyperSpeed"}
function fmt(m){m=+m||0;if(m<60)return m+" min";var h=Math.floor(m/60),r=m%60;return h+(h>1?" hrs":" hr")+(r?" "+r+" min":"")}
function say(t){try{if(window.speechSynthesis){speechSynthesis.cancel();var u=new SpeechSynthesisUtterance(t);u.lang="en-US";speechSynthesis.speak(u)}}catch(e){}}
function tone(f0,t,d){var o=ctx.createOscillator(),g=ctx.createGain();o.type="triangle";o.frequency.value=f0;
g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.35,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+d);
o.connect(g);g.connect(ctx.destination);o.start(t);o.stop(t+d+.05)}
function ding(n){try{if(!ctx||ctx.state!=="running")return;var t=ctx.currentTime;n=Math.min(n,4);for(var i=0;i<n;i++){tone(988,t+i*.22,.12);tone(1319,t+i*.22+.1,.3)}}catch(e){}}
function legacy(fq){var q=new URLSearchParams(new FormData(f));q.set("coinplan",plan);if(fq)q.set("coinforfeit","yes");location.href=f.action+"?"+q.toString()}
function portal(){location.href=f.action+"?fas="+encodeURIComponent(f.elements.fas.value)}
function stop(){if(es){es.close();es=null}if(pt){clearInterval(pt);pt=null}if(tk){clearInterval(tk);tk=null}}
function bar(){put("lleft",left+"s left · the timer restarts with every coin");var b=el("lbar");if(b)b.style.width=Math.max(0,Math.min(100,100*left/tot))+"%"}
function busy(j,cool){stop();show("lcd",0);show("ldone",0);
put("lsub",cool?"Too many empty tries \u00b7 Sobrang daming walang barya":"Coin slot in use \u00b7 Ginagamit ang coin slot");put("lpes","");
put("lmin",(cool?"Please wait about ":"Another customer is paying. Try again in about ")+(+j.retry||15)+" seconds \u00b7 Subukan ulit mamaya.");show("lagain",1)}
function err(e){stop();show("lcd",0);show("ldone",0);put("lsub","Coin payment is not available right now");put("lpes","");put("lmin","Please try again or ask the attendant ("+e+").");show("lagain",1)}
function upd(j){
if(fin)return;
if(j.state==="error"&&!j.online){if(j.error==="SLOT_BUSY")return busy(j,false);return err(j.error)}
var p=+j.pulses||0;
if(j.state==="armed"){
if(!armed){armed=true;put("lsub",pn(plan)+" · Insert coin(s) now · Maglagay ng barya");show("lcd",1);
try{navigator.vibrate&&navigator.vibrate(80)}catch(e){}if(!p)say("Insert coin now")}
left=Math.max(+j.remaining||0,0);tot=p>0?IDLE:FIRST;bar();
if(!tk)tk=setInterval(function(){if(left>0)left--;bar()},1000)}
if(armed||p>0){put("lpes","₱"+p);put("lmin","= "+fmt(j.minutes)+" of Wi-Fi")}
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p;
if(j.online&&!on){on=true;show("lon",1);show("ldone",1);setTimeout(function(){say("You are online")},900);
try{navigator.vibrate&&navigator.vibrate([100,60,100])}catch(e){}}
if(j.final){fin=true;stop();show("lcd",0);show("ldone",0);show("lmis",0);put("lsub","Thank you! · Salamat!");
put("lpes",fmt(j.fleft));put("lmin","of Wi-Fi time left · ₱"+p+" = "+fmt(j.fwmin));put("lont","Enjoy browsing.");
show("lon",1);put("lcode",j.code||"");show("lfin",1);return}
if(j.state==="done"||j.state==="none"){stop();
if(!p){show("lcd",0);show("ldone",0);put("lsub","No coins detected · Walang nabayaran");put("lpes","₱0");
put("lmin","You were not charged.");show("lagain",1)}else portal()}}
function poll(){fetch(base+"/api/status?sid="+SID,{cache:"no-store"}).then(function(r){return r.json()}).then(upd).catch(function(){})}
function watch(){
if(window.EventSource){try{es=new EventSource(base+"/stream?sid="+SID+"&mode=wait");
es.addEventListener("status",function(m){try{upd(JSON.parse(m.data))}catch(e){}});
es.onerror=function(){if(es){es.close();es=null}if(!fin&&!pt)pt=setInterval(poll,1000)}}catch(e){es=null}}
if(!es&&!pt)pt=setInterval(poll,1000)}
function start(fq){
f.style.display="none";var n=document.querySelectorAll(".note"),s0=f.previousElementSibling,i;
for(i=0;i<n.length;i++)n[i].style.display="none";if(s0)s0.style.display="none";
show("live",1);show("lagain",0);put("lsub",pn(plan)+" · Getting the coin slot ready · Sandali lang");put("lpes","₱0");
put("lmin","Please wait. Do not insert coins yet · huwag pa maglagay ng barya.");
fetch(base+"/api/start?sid="+SID+"&plan="+plan+(fq?"&forfeit=1":""),{cache:"no-store"}).then(function(r){return r.json()}).then(function(j){
if(j.state==="error"&&j.error==="PLAN_MISMATCH")return mismatch(j);
if(j.state==="error"&&(j.error==="SLOT_BUSY"||j.error==="COOLDOWN"))return busy(j,j.error==="COOLDOWN");
if(j.state==="error"||j.error)return legacy(fq);
upd(j);watch()}).catch(function(){legacy(fq)})}
function mismatch(j){var o=pn(j.plan),w=pn(plan);
put("lsub","You still have "+o+" time ("+fmt(Math.round((+j.remaining||0)/60))+")");put("lpes","");
put("lmin","Add more "+o+" time, or switch to "+w+": the time you have left is given up when you pay.");
var m=el("lmis");m.innerHTML='<button class="btn" type="button" id="mk">Add '+o+' time</button><button class="btn alt" type="button" id="ms">Switch to '+w+' · lose my time</button>';
show("lmis",1);el("mk").onclick=function(){show("lmis",0);plan=j.plan;start(false)};el("ms").onclick=function(){show("lmis",0);start(true)}}
el("ldone").onclick=function(){this.disabled=true;this.textContent="Closing · Sandali lang";
fetch(base+"/api/finish?sid="+SID,{cache:"no-store"}).then(function(r){return r.json()}).then(upd).catch(function(){})};
f.addEventListener("submit",function(e){e.preventDefault();
try{window.speechSynthesis&&speechSynthesis.speak(new SpeechSynthesisUtterance(""))}catch(x){}
try{if(A){ctx=window.__ctx=window.__ctx||new A();ctx.resume();var o=ctx.createOscillator(),g=ctx.createGain();g.gain.value=.04;
o.frequency.value=880;o.connect(g);g.connect(ctx.destination);o.start();o.stop(ctx.currentTime+.05)}}catch(x){}
var r=f.querySelector("input[name=coinplan]:checked");plan=r?r.value:"hyper";start(false)})})();
</script>
HTML
}

page_wait() {
	pesos=$(printf '%s' "$cst" | jget pulses); mins=$(printf '%s' "$cst" | jget minutes); left=$(printf '%s' "$cst" | jget remaining)
	total="$infofirst"; [ "${pesos:-0}" -gt 0 ] && total="$infoidle"
	pct=$(( ${left:-0} * 100 / ${total:-30} )); [ "$pct" -gt 100 ] && pct=100; [ "$pct" -lt 0 ] && pct=0
	# The coin acceptor only takes coins once the box has armed it: until then say so and show no countdown.
	_ready=yes; [ "$(printf '%s' "$cst" | jget state)" = "starting" ] && _ready=no
	echo '<div id="wait">'
	if [ "$_ready" = yes ]; then
		echo "<p class=\"sub\" id=\"sub\">$(plan_name "$coinplan") &middot; Insert coin(s) now &middot; Maglagay ng barya</p>"
		_cd=""
	else
		echo "<p class=\"sub\" id=\"sub\">$(plan_name "$coinplan") &middot; Getting the coin slot ready &middot; Sandali lang</p>"
		_cd=' style="display:none"'
	fi
	cat << HTML
<div class="big" id="pes">&#8369;${pesos:-0}</div>
<p class="mut">= <span id="mins">$(fmt_min "${mins:-0}")</span> of Wi-Fi</p>
<div id="cd"$_cd><div class="bar"><i id="bar" style="width:${pct}%"></i></div>
<p class="mut"><span id="left">${left:-0}</span>s left &middot; the timer restarts with every coin</p></div>
HTML
	if [ "${pesos:-0}" -gt 0 ]; then action_button "Connect now" finish; else action_button "Cancel" finish alt; fi
	echo '</div>'
	# Live updates and a coin sound (Web Audio: no sound files to download). Browsers only allow sound after a tap, so a
	# small button turns it on. Without scripts the page falls back to a plain reload (see header()).
	waiturl="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=wait&coinplan=$coinplan"
	cat << HTML
<button class="snd" id="snd" type="button">&#128276; Tap for coin sound</button>
<script>
(function(){
var url="$waiturl",pes=${pesos:-0},ctx=null,b=document.getElementById("snd"),A=window.AudioContext||window.webkitAudioContext;
function tone(f,t,d){var o=ctx.createOscillator(),g=ctx.createGain();o.type="triangle";o.frequency.value=f;
g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.35,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+d);
o.connect(g);g.connect(ctx.destination);o.start(t);o.stop(t+d+.05)}
function ding(n){if(!ctx||ctx.state!=="running")return;var t=ctx.currentTime;n=Math.min(n,4);document.body.setAttribute("data-dings",(+document.body.getAttribute("data-dings")||0)+1);
for(var i=0;i<n;i++){tone(988,t+i*.22,.12);tone(1319,t+i*.22+.1,.3)}}
function on(){if(!A)return;ctx=ctx||window.__ctx||new A();window.__ctx=ctx;ctx.resume();
b.style.display="none";ding(1)}
b.onclick=on;
try{if(A){ctx=window.__ctx||new A();window.__ctx=ctx;ctx.resume();setTimeout(function(){if(ctx.state==="running")b.style.display="none"},50)}}catch(e){}
/* Update fields in place and only when they changed: replacing the panel (or the button's text) would swallow a tap
   that is in progress. */
function put(id,t){var e=document.getElementById(id);if(e&&e.textContent!==t)e.textContent=t}
function poll(){var x=new XMLHttpRequest();x.open("GET",url);x.onload=function(){
var d=new DOMParser().parseFromString(x.responseText,"text/html"),n=d.getElementById("wait");
if(!n||!d.getElementById("pes")){location.replace(url);return}
var g=function(i){var e=d.getElementById(i);return e?e.textContent:""},cd=document.getElementById("cd"),dc=d.getElementById("cd"),sb=document.getElementById("sub"),ds=d.getElementById("sub"),
btn=document.querySelector("#wait button.btn"),db=d.querySelector("#wait button.btn");
put("pes",g("pes"));put("mins",g("mins"));put("left",g("left"));
var bar=document.getElementById("bar"),db2=d.getElementById("bar");if(bar&&db2&&bar.style.width!==db2.style.width)bar.style.width=db2.style.width;
if(cd&&dc&&cd.style.display!==dc.style.display)cd.style.display=dc.style.display;
if(sb&&ds&&sb.innerHTML!==ds.innerHTML)sb.innerHTML=ds.innerHTML;
if(btn&&db&&!btn.form.__t&&btn.textContent!==db.textContent){btn.textContent=db.textContent;btn.className=db.className}
var p=parseInt(g("pes").replace(/[^0-9]/g,""),10)||0;
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p};x.send()}
function say(t){try{if(window.speechSynthesis){speechSynthesis.cancel();var u=new SpeechSynthesisUtterance(t);u.lang="en-US";speechSynthesis.speak(u)}}catch(e){}}
function fmt(m){if(m<60)return m+" min";var h=Math.floor(m/60),r=m%60;return h+(h>1?" hrs":" hr")+(r?" "+r+" min":"")}
function show(j){if(j.state==="done"||j.state==="error"||j.state==="none"){location.replace(url);return}
var sb=document.getElementById("sub"),cd=document.getElementById("cd");
if(j.state==="armed"&&cd&&cd.style.display==="none"){cd.style.display="";if(sb)sb.innerHTML="$(plan_name "$coinplan") &middot; Insert coin(s) now &middot; Maglagay ng barya";try{navigator.vibrate&&navigator.vibrate(80)}catch(e){}say("Insert coin now")}
var p=+j.pulses||0,t=p>0?$infoidle:$infofirst,e=document.getElementById("pes"),btn=document.querySelector("#wait button.btn");
if(!e)return;put("pes","\u20b1"+p);put("mins",fmt(+j.minutes||0));put("left",""+Math.max(+j.remaining||0,0));
var w=Math.max(0,Math.min(100,100*(+j.remaining||0)/t))+"%",bar=document.getElementById("bar");if(bar&&bar.style.width!==w)bar.style.width=w;
var want=p>0?"Connect now":"Cancel";if(btn&&!btn.form.__t&&btn.textContent!==want){btn.textContent=want;btn.className=p>0?"btn":"btn alt"}
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p}
var sp=${infostream:-0},es=null,got=false,polling=false,last=0;
function fallback(){if(es){es.close();es=null}if(!polling){polling=true;setInterval(poll,2000)}}
if(window.EventSource&&sp){try{es=new EventSource("http://"+location.hostname+":"+sp+"/stream?sid=$sid&mode=wait");
es.addEventListener("status",function(m){got=true;last=Date.now();try{show(JSON.parse(m.data))}catch(e){}});
es.onerror=function(){if(!got||es.readyState===2)fallback();else setTimeout(function(){if(es&&Date.now()-last>8000)fallback()},8000)}}catch(e){es=null}}
if(!es)fallback()})();
</script>
HTML
}

page_counting() {
	echo '<p class="big">Counting coins&hellip;</p><p class="mut">One moment &middot; sandali lang</p>'
}

page_busy() {
	if [ "$err" = COOLDOWN ]; then
		echo "<div class=\"msg\"><b>Too many empty tries &middot; Sobrang daming walang barya</b><br>Please wait about ${busyretry:-60} seconds before starting again &middot; Maghintay muna ng ${busyretry:-60} segundo.</div>"
		action_button "Try again" start
		return
	fi
	cat << HTML
<div class="msg"><b>Coin slot in use &middot; Ginagamit ang coin slot</b><br>Another customer is paying right now. Please try again in about ${busyretry:-15} seconds &middot; Subukan ulit pagkalipas ng ${busyretry:-15} segundo.</div>
HTML
	action_button "Try again" start
}

page_mismatch() {
	newplan="$coinplan"
	cat << HTML
<div class="msg"><b>You still have $(plan_name "$otherplan") time ($(fmt_min $(( ${otherleft:-0} / 60 )))).</b><br>
Add more $(plan_name "$otherplan") time, or switch to $(plan_name "$newplan"). <b>If you switch, the time you have left is forfeited</b> as soon as you pay.</div>
HTML
	plan_button "Add $(plan_name "$otherplan") time" "$otherplan" "" ""
	plan_button "Switch to $(plan_name "$newplan") &middot; lose my time" "$newplan" forfeit "alt"
}

page_error() {
	echo "<div class=\"msg\"><b>Coin payment is not available right now.</b><br>Please try again or ask the attendant. ($err)</div>"
	action_button "Try again" start alt
}

page_unavailable() {
	echo '<div class="msg"><b>Coin payment is offline.</b><br>Please ask the attendant, or try again in a moment &middot; Pakisabihan ang attendant.</div>'
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas"><button class="btn alt" type="submit">Try again</button></form>
HTML
}

page_voucherform() {
	if [ -n "$vcode" ]; then
		case "$vrc" in
			3) _m="That code has run out of time." ;;
			7) _m="This device already has its own time. Use it up first, or use the code on another device." ;;
			8) _m="Too many wrong tries. Please wait a few minutes." ;;
			5) _m="The system is busy. Please try again in a moment." ;;
			*) _m="That code was not found. Check it and try again." ;;
		esac
		echo "<div class=\"msg\"><b>$_m</b></div>"
	fi
	cat << HTML
<p class="sub">Restore your time with your code &middot; Ilagay ang code</p>
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas"><input type="hidden" name="coinact" value="voucher">
<input type="text" name="vcode" maxlength="9" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="abcd-1234" oninput="this.value=this.value.toLowerCase()">
<button class="btn" type="submit">Use code</button></form>
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)">Back</a></p>
HTML
}

# Coins counted, waiting to be used: what was paid, what the device still had, and the button that connects it.
page_result() {
	ver=$(coinslot "/verify?sid=$sid")
	if [ "$(printf '%s' "$ver" | jget ok)" != "true" ]; then
		echo '<p class="big">No coins detected</p><p class="mut">You were not charged &middot; Walang nabayaran</p>'
		coinplan="hyper"; action_button "Try again" "" alt
		return
	fi
	vplan=$(printf '%s' "$ver" | jget plan); vpulses=$(printf '%s' "$ver" | jget pulses); vmin=$(printf '%s' "$ver" | jget minutes)
	leftmin=0; label="Connect"; forfeitnote=""; vwid=$(printf '%s' "$ver" | jget wid)
	flash_peek "$mac"
	if [ -n "$vwid" ] && [ "$R_WID" = "$vwid" ] && [ "$P_STATE" = running ]; then
		# The router already recorded this window (online on the first coin): show the total, add nothing again.
		cat << HTML
<p class="sub">$(plan_name "$vplan") &middot; Thank you! &middot; Salamat!</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">of Wi-Fi time left &middot; &#8369;$vpulses = $(fmt_min "$vmin") added</p>
HTML
		coinplan="$vplan"; action_button "Continue" connect "" landing
		return
	fi
	if [ "$P_STATE" = running ] || [ "$P_STATE" = paused ]; then
		if [ "$R_PLAN" = "$vplan" ]; then
			leftmin=$(left_min "$R_LEFT"); label="Add time"
			[ "$P_STATE" = paused ] && label="Resume"
		else
			forfeitnote="<p class=\"mut\">Your time on the other plan ($(plan_name "$R_PLAN")) is replaced by this one.</p>"
			coinforfeit="yes"
		fi
	fi
	what="&#8369;$vpulses = $(fmt_min "$vmin")"
	[ "$leftmin" -gt 0 ] && what="$what + $(fmt_min "$leftmin") you still had"
	cat << HTML
<p class="sub">$(plan_name "$vplan") &middot; Thank you! &middot; Salamat!</p>
<div class="big">$(fmt_min $((vmin + leftmin)))</div>
<p class="mut">$what</p>
$forfeitnote
HTML
	coinplan="$vplan"; action_button "$label" connect "" landing
}

# A returning device with time left (after a reboot or power cut): connected again without asking for anything.
page_reconnect() {
	flash_session "$mac"; src=$?
	if [ "$src" = 0 ]; then
		grant_access
		if [ "$ndsstatus" = "authenticated" ]; then
			originurl=$(printf "${originurl//%/\\x}")
			cat << HTML
<p class="sub">Welcome back &middot; Welcome ulit</p>
<div class="big">$(fmt_min "$S_MIN")</div>
<p class="mut">left on this device. You were reconnected automatically.</p>
<a class="btn" style="text-decoration:none;text-align:center" href="$originurl">Continue browsing</a>
HTML
			return
		fi
	fi
	PAGE="welcome"; page_welcome
}

page_pausedhome() {
	cat << HTML
<p class="sub">Your time is paused &middot; Naka-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">is saved for you. Tap Resume to continue.</p>
HTML
	coinplan="$R_PLAN"; action_button "Resume" connect "" landing
}

page_restored() {
	label="Connect"; [ "$P_STATE" = paused ] && label="Resume"
	cat << HTML
<p class="sub">Code accepted &middot; Tanggap ang code</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">$(plan_name "$R_PLAN") time moved to this device.</p>
HTML
	coinplan="$R_PLAN"; action_button "$label" connect "" landing
}

# Authenticate the device for what flash_session (S_*) found. A connected device is de-authenticated and granted again
# (openNDS ignores a plain auth of an authenticated client); anyone else goes through the normal auth_log.
grant_access() {
	sessiontimeout="$S_MIN"; upload_rate="$S_UP"; download_rate="$S_DOWN"; upload_quota="$S_QUP"; download_quota="$S_QDOWN"
	quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"
	userinfo="$title - $S_CODE"
	if [ "$(nds_state "$mac")" = "Authenticated" ]; then
		if nds_regrant "$mac" "$S_MIN" "$S_UP" "$S_DOWN" "$S_QUP" "$S_QDOWN"; then ndsstatus="authenticated"; else ndsstatus="failed"; fi
	else
		auth_log
	fi
}

# libopennds.sh calls check_authenticated() before generate_splash_sequence().
check_authenticated() { return 0; }

# libopennds.sh calls landing_page() when the form was submitted with landing=yes: the device taps Connect / Add time / Resume.
landing_page() {
	originurl=$(printf "${originurl//%/\\x}")
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	configure_log_location
	. $mountpoint/ndscids/ndsinfo

	sid=$(coinslot_sid); mac="$clientmac"
	forfeit=0; [ "$coinforfeit" = "yes" ] && forfeit=1
	ver=$(coinslot "/verify?sid=$sid"); paid=no; mrc=0
	if [ "$(printf '%s' "$ver" | jget ok)" = "true" ]; then
		paid=yes
		# Record the payment first (idempotent: the same coin window is never credited twice), then grant access.
		flash_mint "$mac" "$(printf '%s' "$ver" | jget wid)" "$(printf '%s' "$ver" | jget plan)" "$(printf '%s' "$ver" | jget pulses)" \
			"$(printf '%s' "$ver" | jget minutes)" "$(printf '%s' "$ver" | jget up)" "$(printf '%s' "$ver" | jget down)" "$forfeit"
		mrc=$?
	fi
	if [ "$mrc" = 6 ]; then
		cat << HTML
<div class="msg"><b>You still have time on the other plan.</b><br>Using these coins means switching plan: <b>the time you have left is forfeited</b>. Your coins stay safe until you choose.</div>
HTML
		coinplan="$(printf '%s' "$ver" | jget plan)"; coinforfeit="yes"
		action_button "Switch plan &middot; lose my old time" connect "" landing
		coinforfeit=""; action_button "Not now" "" alt
		footer
	fi
	src=5
	[ "$mrc" = 0 ] && { flash_session "$mac"; src=$?; }
	if [ "$src" = 0 ]; then
		# The router usually has done all of this already (online on the first coin, the window settled when it closed):
		# then nothing is granted again, so the connection is not interrupted.
		if [ "$paid" = yes ] && { [ "$M_MODE" = dup ] || [ "$M_MODE" = update ]; } && [ "$(nds_state "$mac")" = "Authenticated" ] &&
			[ "$(printf '%s' "$ver" | jget claimed)" = "true" ]; then
			ndsstatus="authenticated"
		else
			grant_access
		fi
		if [ "$ndsstatus" = "authenticated" ]; then
			# Only after access was really granted is the payment marked as used on the box.
			[ "$paid" = yes ] && coinslot "/ack?sid=$sid" > /dev/null
			cat << HTML
<p class="sub">$(plan_name "$S_PLAN") &middot; Connected &middot; Nakakonekta na</p>
<div class="big">$(fmt_min "$S_MIN")</div>
<p class="mut">of Wi-Fi time. Enjoy! &middot; Salamat!</p>
<p class="mut">Save your code. It restores your time on any device:</p>
<div class="code">$S_CODE</div>
<a class="btn" style="text-decoration:none;text-align:center" href="http://$gatewayfqdn/?$randquery">Continue</a>
<script>try{var A=window.AudioContext||window.webkitAudioContext,c=new A();c.resume();setTimeout(function(){if(c.state==="running"){[523,659,784,1047].forEach(function(f,i){var o=c.createOscillator(),g=c.createGain(),t=c.currentTime+i*.12;o.type="triangle";o.frequency.value=f;g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.3,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+.3);o.connect(g);g.connect(c.destination);o.start(t);o.stop(t+.35)})}},60)}catch(e){}</script>
HTML
			footer
		fi
	fi
	if [ "$src" = 2 ] || [ "$src" = 3 ]; then
		echo '<div class="msg"><b>No paid time found for this device.</b><br>Insert a coin to buy time, or restore your time with your code.</div>'
		coinplan="hyper"; action_button "Back" "" alt
	else
		echo '<div class="msg"><b>We could not start your session.</b><br>Your coins are safe. Tap Try again; if it keeps failing, ask the attendant.</div>'
		coinplan="${S_PLAN:-hyper}"; action_button "Try again" connect "" landing
	fi
	footer
}

page_pausecheck() {
	cat << HTML
<p class="sub">Pause your time &middot; I-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">would be saved. You can pause only <b>once</b> and must resume within ${pausehours:-72} hours. Your device disconnects until you resume.</p>
HTML
	action_button "Yes, pause my time" pause
	action_button "Keep using Wi-Fi" "" alt
}

page_paused() {
	cat << HTML
<p class="sub">Time paused &middot; Naka-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">saved. To continue, join this Wi-Fi again and tap <b>Resume</b> (or use your code on any device):</p>
<div class="code">$R_CODE</div>
HTML
}

page_pauseerr() {
	case "$prc" in
		7) m="You already used your one pause." ;;
		9) m="Pause is only available for Endurance time of &#8369;${pausepesos:-10} or more." ;;
		5) m="The system is busy. Please try again." ;;
		*) m="Could not pause right now. Please try again." ;;
	esac
	echo "<div class=\"msg\"><b>$m</b></div>"
	action_button "Back" "" alt
}

display_terms() {
	cat << HTML
<div class="msg" style="border-color:var(--mut)"><b>Terms of Service</b><br>
Access is paid for in coins and lasts for the time shown when you connect. Coins are not refundable once a session has started.
HyperSpeed may be slowed temporarily after heavy use (fair use). Do not misuse the connection. The owners may end a session at any time.</div>
<button class="btn alt" type="button" onclick="history.go(-1)">Back</button>
HTML
	footer
}

#### end of functions ####

#################################################
# Main entry point of this Theme: parameters set here override those in libopennds.sh
#################################################

randquery="$(date | sha256sum | awk '{printf "%s", $1}')"

# Session length and speed are set per customer in landing_page() from the manager's grant.
sessiontimeout="0"
upload_rate="0"
download_rate="0"
upload_quota="0"
download_quota="0"
quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"

ndscustomparams=""
ndscustomimages=""
ndscustomfiles=""
ndsparamlist="$ndsparamlist $ndscustomparams $ndscustomimages $ndscustomfiles"

# Extra form variables used by this theme (names kept distinct: the parser matches them as substrings).
additionalthemevars="coinact coinplan coinforfeit vcode"
fasvarlist="$fasvarlist $additionalthemevars"

userinfo="$title"
