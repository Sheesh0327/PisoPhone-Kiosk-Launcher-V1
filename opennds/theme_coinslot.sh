#!/bin/sh
# ThemeSpec for openNDS (login_option_enabled '3'): pay for Wi-Fi with coins. Install as
# /usr/lib/opennds/theme_coinslot.sh. libopennds.sh does the portal plumbing (decoding the client's
# parameters, auth_log, the hooks below); this file only supplies the pages and talks to the local
# coin-slot manager (coinslot-listener.sh) on 127.0.0.1. Pages are plain HTML with ~2 KB of inline CSS:
# no images, fonts or scripts to download, so the portal opens instantly even on a captive-portal browser.
#
# Page sequence, driven by the form variable "coinact":
#   (none)    welcome: rates for HyperSpeed and Endurance, "Insert Coin"   (or "welcome back" / status page)
#   start     arm the coin slot for the chosen plan, then the waiting page
#   wait      coin window: pesos inserted, time earned, countdown (reloads itself)
#   finish    stop accepting, wait for in-flight coins, then the result
#   voucher   redeem a voucher code      vform: ask for one
#   landing   (libopennds, landing=yes) grant the time through openNDS
# Minutes and speed caps are always decided by the listener, never taken from the browser.

title="theme_coinslot"
COINSLOT_URL="${COINSLOT_URL:-http://127.0.0.1:8099}"

# ---------------------------------------------------------------------------
# Listener access and small helpers
# ---------------------------------------------------------------------------
coinslot() {  # coinslot <path>: JSON/text answer, empty if the listener is not running
	# socat starts in milliseconds; curl/wget take about half a second just to start on the router (TLS library).
	if command -v socat > /dev/null 2>&1; then
		_h="${COINSLOT_URL#http://}"
		printf 'GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "$1" "${_h%%:*}" |
			socat -t10 -T10 - "TCP:$_h,shut-none" 2> /dev/null | tr -d '\r' | sed '1,/^$/d'
	elif command -v curl > /dev/null 2>&1; then
		curl -sS -m 10 "$COINSLOT_URL$1" 2> /dev/null
	else
		wget -qO- -T 10 "$COINSLOT_URL$1" 2> /dev/null
	fi
}
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p'; }

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
		case "$PAGE" in wait | busy) refreshtag="<noscript>$refreshtag</noscript>" ;; esac   # with scripts on, these pages update themselves
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
:root{--bg:#0b1020;--card:#151b2e;--line:#252d49;--fg:#eef1f8;--mut:#8f99b5;--h:#ffb020;--e:#2fc58f;--ok:#2fc58f;--bad:#ff6b6b}
@media(prefers-color-scheme:light){:root{--bg:#f3f5fb;--card:#fff;--line:#dfe4f0;--fg:#141a2e;--mut:#5d6783}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;justify-content:center}
.w{width:100%;max-width:440px;padding:20px 16px 36px}
h1{font-size:19px;margin:0 0 4px;text-align:center}
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
.btn{display:block;width:100%;padding:14px;margin:12px 0 0;border:0;border-radius:12px;font-weight:600;font-size:17px;color:#06210f;background:var(--ok);cursor:pointer}
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
HTML
}

footer() {
	cat << HTML
<p class="foot"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&terms=yes">Terms</a></p>
</div></body></html>
HTML
	exit 0
}

# A form that posts back to the portal. $1 label, $2 coinact, $3 css class, $4 "landing" to also set landing=yes.
action_button() {
	_l=""; [ "$4" = "landing" ] && _l="<input type=\"hidden\" name=\"landing\" value=\"yes\">"
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="$2"><input type="hidden" name="coinplan" value="$coinplan">$_l
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
# Sets PAGE: welcome wait counting busy mismatch error result status voucherform unavailable
# and REFRESH ("<seconds> <coinact>") when the page should reload itself.
choose_page() {
	sid=$(coinslot_sid); mac="$clientmac"
	info=$(coinslot /info)
	if [ -z "$info" ]; then PAGE="unavailable"; return; fi
	infoidle=$(printf '%s' "$info" | jget idle); infofirst=$(printf '%s' "$info" | jget first); pausehours=$(printf '%s' "$info" | jget pause_hours)
	infostream=$(printf '%s' "$info" | jget stream_port)
	case "$coinplan" in endurance) ;; *) coinplan="hyper" ;; esac

	if [ "$status" = "authenticated" ] && [ -z "$coinact" ]; then PAGE="status"; return; fi

	case "$coinact" in
		start)
			forfeitq=""; [ "$coinforfeit" = "yes" ] && forfeitq="&forfeit=1"
			answer=$(coinslot "/start?sid=$sid&plan=$coinplan&mac=$mac$forfeitq")
			if [ "$(printf '%s' "$answer" | jget state)" = "error" ]; then
				err=$(printf '%s' "$answer" | jget error)
				case "$err" in
					SLOT_BUSY) PAGE="busy"; REFRESH="5 start" ;;
					PLAN_MISMATCH) PAGE="mismatch"; otherplan=$(printf '%s' "$answer" | jget plan); otherleft=$(printf '%s' "$answer" | jget remaining) ;;
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
		pausecheck) PAGE="pausecheck" ;;
		pause)
			pres=$(coinslot "/pause?mac=$mac")
			if [ "$(printf '%s' "$pres" | jget success)" = "true" ]; then PAGE="paused"; else PAGE="pauseerr"; perr=$(printf '%s' "$pres" | jget error); fi ;;
		vform) PAGE="voucherform" ;;
		voucher)
			vgrant=$(coinslot "/voucher?sid=$sid&mac=$mac&code=$vcode")
			verr=$(printf '%s' "$vgrant" | jget error)
			if [ -n "$verr" ] || [ "$(printf '%s' "$vgrant" | jget minutes)" -le 0 ] 2> /dev/null; then PAGE="voucherform"; else PAGE="result"; fi ;;
		*)
			# Coins of an earlier window that never became access come first, then a returning device.
			cst=$(coinslot "/status?sid=$sid")
			if [ "$(printf '%s' "$cst" | jget state)" = "done" ] && [ "$(printf '%s' "$cst" | jget claimed)" = "false" ] &&
				[ "$(printf '%s' "$cst" | jget pulses)" -gt 0 ] 2> /dev/null; then
				PAGE="result"; return
			fi
			resume=$(coinslot "/resume?sid=$sid&mac=$mac")
			if [ "$(printf '%s' "$resume" | jget minutes)" -gt 0 ] 2> /dev/null; then PAGE="result"; else PAGE="welcome"; fi ;;
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
			if [ "$err" = "SLOT_BUSY" ]; then PAGE="busy"; REFRESH="5 start"; else PAGE="error"; fi ;;
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
		status) page_status ;;
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
<p class="sub">Wi-Fi rates &middot; Presyo ng Wi-Fi</p>
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
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=vform">I have a voucher code &middot; May voucher ako</a></p>
<script>
/* Insert Coin also unlocks sound: browsers only allow audio after a tap, and the tap must happen on the page that later
   plays it. So the tap starts the coin window without leaving the page (the waiting view replaces this one and reuses
   the unlocked audio). Without scripts the form simply submits and the waiting page offers a "tap for sound" button. */
(function(){var f=document.getElementById("coinform"),A=window.AudioContext||window.webkitAudioContext,first=${infofirst:-30};
if(!f||!A||!window.fetch||!window.URLSearchParams||!window.FormData)return;
/* Show the waiting screen at once (same look as the real one); the router's answer replaces it a moment later, or shows
   the busy / error page instead. */
function instant(){var r=f.querySelector("input[name=coinplan]:checked"),nm=r&&r.value==="endurance"?"Endurance":"HyperSpeed",
sub=f.previousElementSibling,d=document.createElement("div"),n=document.querySelectorAll(".note"),i;
if(sub)sub.textContent=nm+" \u00b7 Getting the coin slot ready \u00b7 Sandali lang";
f.style.display="none";for(i=0;i<n.length;i++)n[i].style.display="none";
d.innerHTML='<div class="big">&#8369;0</div><p class="mut">Please wait a moment. <b>Do not insert coins yet</b> &middot; huwag pa maglagay ng barya.</p>';
f.parentNode.insertBefore(d,f.nextSibling)}
f.addEventListener("submit",function(e){e.preventDefault();
try{window.speechSynthesis&&speechSynthesis.speak(new SpeechSynthesisUtterance(""))}catch(x){}
try{var c=window.__ctx=window.__ctx||new A();c.resume();var o=c.createOscillator(),g=c.createGain();g.gain.value=.04;o.frequency.value=880;o.connect(g);g.connect(c.destination);o.start();o.stop(c.currentTime+.05)}catch(x){}
var url=f.action+"?"+new URLSearchParams(new FormData(f)).toString();instant();
fetch(url,{cache:"no-store"}).then(function(r){return r.text()})
.then(function(t){document.open();document.write(t);document.close()}).catch(function(){location.href=url})})})();
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
function poll(){var x=new XMLHttpRequest();x.open("GET",url);x.onload=function(){
var d=new DOMParser().parseFromString(x.responseText,"text/html"),n=d.getElementById("wait");
if(!n){location.replace(url);return}
var w=document.getElementById("wait");w.innerHTML=n.innerHTML;
var p=parseInt((d.getElementById("pes").textContent||"").replace(/[^0-9]/g,""),10)||0;
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p};x.send()}
function say(t){try{if(window.speechSynthesis){speechSynthesis.cancel();var u=new SpeechSynthesisUtterance(t);u.lang="en-US";speechSynthesis.speak(u)}}catch(e){}}
function fmt(m){if(m<60)return m+" min";var h=Math.floor(m/60),r=m%60;return h+(h>1?" hrs":" hr")+(r?" "+r+" min":"")}
function show(j){if(j.state==="done"||j.state==="error"||j.state==="none"){location.replace(url);return}
var sb=document.getElementById("sub"),cd=document.getElementById("cd");
if(j.state==="armed"&&cd&&cd.style.display==="none"){cd.style.display="";if(sb)sb.innerHTML="$(plan_name "$coinplan") &middot; Insert coin(s) now &middot; Maglagay ng barya";try{navigator.vibrate&&navigator.vibrate(80)}catch(e){}say("Insert coin now")}
var p=+j.pulses||0,t=p>0?$infoidle:$infofirst,e=document.getElementById("pes"),btn=document.querySelector("#wait button.btn");
if(!e)return;e.textContent="\u20b1"+p;document.getElementById("mins").textContent=fmt(+j.minutes||0);
document.getElementById("left").textContent=Math.max(+j.remaining||0,0);
document.getElementById("bar").style.width=Math.max(0,Math.min(100,100*(+j.remaining||0)/t))+"%";
if(btn){btn.textContent=p>0?"Connect now":"Cancel";btn.className=p>0?"btn":"btn alt"}
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p}
var sp=${infostream:-0},es=null,got=false;
if(window.EventSource&&sp){try{es=new EventSource("http://"+location.hostname+":"+sp+"/stream?sid=$sid&mode=wait");
es.addEventListener("status",function(m){got=true;try{show(JSON.parse(m.data))}catch(e){}});
es.onerror=function(){if(!got){es.close();es=null;setInterval(poll,2000)}}}catch(e){es=null}}
if(!es)setInterval(poll,2000)})();
</script>
HTML
}

page_counting() {
	echo '<p class="big">Counting coins&hellip;</p><p class="mut">One moment &middot; sandali lang</p>'
}

page_busy() {
	_fq=""; [ "$coinforfeit" = "yes" ] && _fq="<input type=\"hidden\" name=\"coinforfeit\" value=\"yes\">"
	cat << HTML
<div class="msg"><b id="bzh">Coin slot is busy</b><br><span id="bzt">Someone else is paying. Keep this page open: it tells you the moment the slot is free &middot; May ibang nagbabayad, sasabihan ka namin kapag libre na.</span></div>
<div id="rdy" style="display:none"><form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="start"><input type="hidden" name="coinplan" value="$coinplan">$_fq
<button class="btn" type="submit">Start &middot; insert coin(s) now</button></form></div>
<script>
(function(){
var sp=${infostream:-0},url="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=start&coinplan=$coinplan&coinforfeit=$coinforfeit",
h=document.getElementById("bzh"),t=document.getElementById("bzt"),r=document.getElementById("rdy"),got=false,es=null,tick=null;
function again(){setTimeout(function(){location.replace(url)},5000)}
if(!window.EventSource||!sp){again();return}
function done(){if(es)es.close();if(tick)clearInterval(tick)}
try{es=new EventSource("http://"+location.hostname+":"+sp+"/stream?sid=$sid&mode=queue")}catch(e){again();return}
es.addEventListener("queue",function(m){got=true;var p=+JSON.parse(m.data).pos||0;
t.textContent=p>1?"You are number "+p+" in line. Keep this page open \u00b7 Pang-"+p+" ka sa pila.":"You are next. Keep this page open \u00b7 Ikaw na ang susunod."});
es.addEventListener("ready",function(m){got=true;var c=+JSON.parse(m.data).claim||30;h.textContent="Coin slot is ready!";
t.textContent="Tap Start within "+c+" seconds \u00b7 Pindutin ang Start.";r.style.display="block";document.title="Coin slot ready";
try{navigator.vibrate&&navigator.vibrate([200,100,200])}catch(e){}
try{window.speechSynthesis&&speechSynthesis.speak(new SpeechSynthesisUtterance("The coin slot is ready. Tap start."))}catch(e){}
tick=setInterval(function(){c--;if(c>0)t.textContent="Tap Start within "+c+" seconds \u00b7 Pindutin ang Start."},1000)});
es.addEventListener("expired",function(){done();r.style.display="none";h.textContent="Your turn passed";t.textContent="The slot was held for you but not used. Tap below to get back in line.";
r.innerHTML='<a class="btn" style="text-align:center;text-decoration:none" href="'+url+'">Try again</a>';r.style.display="block"});
es.addEventListener("started",function(){done();location.replace(url.replace("coinact=start","coinact=wait"))});
es.addEventListener("unsupported",function(){done();again()});
es.onerror=function(){if(!got){done();again()}}})();
</script>
HTML
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
	[ -n "$vcode" ] && echo '<div class="msg"><b>That code was not accepted.</b><br>Check it and try again, or it may have expired.</div>'
	cat << HTML
<p class="sub">Enter your voucher code &middot; Ilagay ang voucher code</p>
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas"><input type="hidden" name="coinact" value="voucher">
<input type="text" name="vcode" maxlength="8" autocomplete="off" autocapitalize="characters" placeholder="ABCD2345">
<button class="btn" type="submit">Use code</button></form>
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)">Back</a></p>
HTML
}

page_result() {
	claim=$(coinslot "/claim?sid=$sid&mac=$mac")
	kind=$(printf '%s' "$claim" | jget kind); cmin=$(printf '%s' "$claim" | jget minutes); cadd=$(printf '%s' "$claim" | jget added)
	cplan=$(printf '%s' "$claim" | jget plan); cpes=$(printf '%s' "$claim" | jget pulses); cmode=$(printf '%s' "$claim" | jget mode)
	if [ "${cmin:-0}" -le 0 ] 2> /dev/null; then
		echo '<p class="big">No coins detected</p><p class="mut">You were not charged &middot; Walang nabayaran</p>'
		coinplan="hyper"; action_button "Try again" "" alt
		return
	fi
	label="Connect"; [ "$cmode" = "topup" ] && label="Add time"
	cpaused=$(printf '%s' "$claim" | jget paused); [ "$cpaused" = "1" ] && label="Resume"
	case "$kind" in
		coins) what="&#8369;$cpes = $(fmt_min "$cadd")"; [ "$cmin" -gt "$cadd" ] && what="$what + $(fmt_min $((cmin - cadd))) you still had" ;;
		*) what="Time left on your voucher"; [ "$cpaused" = "1" ] && what="Your paused time is ready" ;;
	esac
	cat << HTML
<p class="sub">$(plan_name "$cplan") &middot; Thank you! &middot; Salamat!</p>
<div class="big">$(fmt_min "$cmin")</div>
<p class="mut">$what</p>
HTML
	[ "$(printf '%s' "$claim" | jget forfeit)" = "1" ] && echo '<p class="mut">Your previous time on the other plan is replaced by this one.</p>'
	coinplan="$cplan"; action_button "$label" connect "" landing
}

# Connected client opening the portal address: account status with a live countdown and a top-up button.
page_status() {
	me=$(coinslot "/me?mac=$mac")
	left=$(printf '%s' "$me" | jget remaining); splan=$(printf '%s' "$me" | jget plan); code=$(printf '%s' "$me" | jget voucher)
	thr=$(printf '%s' "$me" | jget throttled); used=$(printf '%s' "$me" | jget used_mb); canpause=$(printf '%s' "$me" | jget can_pause)
	[ "${left:-0}" -lt 0 ] && left=0
	coinplan="${splan:-hyper}"
	cat << HTML
<p class="sub">You are connected &middot; Nakakonekta ka na</p>
<div class="big" id="t">$(fmt_min $(( ${left:-0} / 60 )))</div>
<p class="mut">$(plan_name "$splan") &middot; time left &middot; natitirang oras</p>
HTML
	[ "$thr" = "true" ] && echo '<div class="msg"><b>Fair use:</b> speed is reduced for a few minutes because of heavy use. It speeds up again automatically.</div>'
	[ "$splan" = "hyper" ] && echo "<p class=\"mut\">Data used: ${used:-0} MB</p>"
	[ -n "$code" ] && echo "<p class=\"mut\">Your voucher code (use it to reconnect any device):</p><div class=\"code\">$code</div>"
	action_button "Add time &middot; Dagdagan" start
	[ "$canpause" = "true" ] && action_button "Pause my time (once)" pausecheck alt
	cat << HTML
<script>var s=${left:-0},e=document.getElementById("t");setInterval(function(){if(s>0)s--;var h=Math.floor(s/3600),m=Math.floor(s%3600/60);e.textContent=(h?h+" hr ":"")+m+" min "+(s%60)+" s"},1000)</script>
HTML
}

# libopennds.sh calls check_authenticated() before generate_splash_sequence(): the status page replaces its text.
check_authenticated() { return 0; }

# libopennds.sh calls landing_page() when the form was submitted with landing=yes.
landing_page() {
	originurl=$(printf "${originurl//%/\\x}")
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	configure_log_location
	. $mountpoint/ndscids/ndsinfo

	sid=$(coinslot_sid); mac="$clientmac"
	claim=$(coinslot "/claim?sid=$sid&mac=$mac")
	cmin=$(printf '%s' "$claim" | jget minutes); cmode=$(printf '%s' "$claim" | jget mode); cplan=$(printf '%s' "$claim" | jget plan)
	cup=$(printf '%s' "$claim" | jget up); cdown=$(printf '%s' "$claim" | jget down)

	granted=""
	if [ "${cmin:-0}" -gt 0 ] 2> /dev/null; then
		if [ "$cmode" = "topup" ]; then
			done_=$(coinslot "/apply?sid=$sid&mac=$mac")
			[ "$(printf '%s' "$done_" | jget success)" = "true" ] && granted="$done_"
		else
			# Session length and speed caps come from the manager, never from the browser.
			sessiontimeout="$cmin"; upload_rate="${cup:-0}"; download_rate="${cdown:-0}"
			quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"
			userinfo="$userinfo, plan=$cplan, minutes=$cmin"
			auth_log
			# Only after access was really granted does the manager record it and acknowledge the coins.
			[ "$ndsstatus" = "authenticated" ] && granted=$(coinslot "/confirm?sid=$sid&mac=$mac")
		fi
	fi

	if [ -n "$granted" ]; then
		code=$(printf '%s' "$granted" | jget voucher)
		cat << HTML
<p class="sub">$(plan_name "$cplan") &middot; Connected &middot; Nakakonekta na</p>
<div class="big">$(fmt_min "$cmin")</div>
<p class="mut">of Wi-Fi time. Enjoy! &middot; Salamat!</p>
<p class="mut">Save your voucher code. It restores your time on any device:</p>
<div class="code">$code</div>
<a class="btn alt" style="text-decoration:none;text-align:center" href="http://$gatewayfqdn/?$randquery">Continue</a>
<script>try{var A=window.AudioContext||window.webkitAudioContext,c=new A();c.resume();setTimeout(function(){if(c.state==="running"){[523,659,784,1047].forEach(function(f,i){var o=c.createOscillator(),g=c.createGain(),t=c.currentTime+i*.12;o.type="triangle";o.frequency.value=f;g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.3,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+.3);o.connect(g);g.connect(c.destination);o.start(t);o.stop(t+.35)})}},60)}catch(e){}</script>
HTML
	else
		echo '<div class="msg"><b>We could not start your session.</b><br>Your coins are safe. Tap Try again; if it keeps failing, ask the attendant.</div>'
		coinplan="${cplan:-hyper}"; action_button "Try again" start alt
	fi
	footer
}

page_pausecheck() {
	me=$(coinslot "/me?mac=$mac"); left=$(printf '%s' "$me" | jget remaining)
	cat << HTML
<p class="sub">Pause your time &middot; I-pause ang oras</p>
<div class="big">$(fmt_min $(( ${left:-0} / 60 )))</div>
<p class="mut">would be saved. You can pause only <b>once</b> and must resume within $(( ${pausehours:-72} )) hours. Your device disconnects until you resume.</p>
HTML
	action_button "Yes, pause my time" pause
	action_button "Keep using Wi-Fi" "" alt
}

page_paused() {
	pleft=$(printf '%s' "$pres" | jget left); pcode=$(printf '%s' "$pres" | jget voucher)
	cat << HTML
<p class="sub">Time paused &middot; Naka-pause ang oras</p>
<div class="big">$(fmt_min $(( (${pleft:-0} + 59) / 60 )))</div>
<p class="mut">saved. To continue, join this Wi-Fi again and tap <b>Resume</b> (or use your voucher code on any device):</p>
<div class="code">$pcode</div>
HTML
}

page_pauseerr() {
	case "$perr" in
		ALREADY_USED) m="You already used your one pause." ;;
		NOT_ELIGIBLE) m="Pause is only available for Endurance time of &#8369;10 or more." ;;
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
