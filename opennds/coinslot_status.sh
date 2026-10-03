#!/bin/sh
# Status page for connected customers: replaces openNDS' default client_params.sh (what a customer sees at
# http://<router>/ once online). openNDS runs it through its "statuspath" option:
#
#   uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/coinslot_status.sh'
#
# openNDS calls:  coinslot_status.sh status <client ip> <b64 query>    the page for a connected client
#                 coinslot_status.sh err511 <client ip>                "tap Continue to log in" for everybody else
# and serves whatever this prints as the page. It shows what the coin-slot manager knows (time left, plan, data used,
# voucher code); if the manager is not running it falls back to the session end openNDS knows. It changes nothing.
# There is deliberately no Logout button: paid time keeps running while a device is logged out.
status="$1"; clientip="$2"
COINSLOT_URL="${COINSLOT_URL:-http://127.0.0.1:8099}"
NDSCTL="${NDSCTL:-ndsctl}"

case "$clientip" in "" | *[!0-9.]*) exit 1 ;; esac

jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p' | head -n 1; }
esc() { sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/"/\&quot;/g'; }
coinslot() {  # coinslot <path>: answer of the manager (socat starts much faster than curl on a router), empty if down
	if command -v socat > /dev/null 2>&1; then
		_h="${COINSLOT_URL#http://}"
		printf 'GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "$1" "${_h%%:*}" |
			socat -t5 -T5 - "TCP:$_h,shut-none" 2> /dev/null | tr -d '\r' | sed '1,/^$/d'
	else
		wget -qO- -T 5 "$COINSLOT_URL$1" 2> /dev/null
	fi
}
fmt_min() {  # 30 -> "30 min", 60 -> "1 hr", 690 -> "11 hr 30 min"
	_m="${1:-0}"
	if [ "$_m" -lt 60 ]; then echo "$_m min"; return; fi
	_h=$((_m / 60)); _r=$((_m % 60)); _u="hr"; [ "$_h" -gt 1 ] && _u="hrs"
	if [ "$_r" -gt 0 ]; then echo "$_h $_u $_r min"; else echo "$_h $_u"; fi
}

nds=$("$NDSCTL" json "$clientip" 2> /dev/null)
urldecode() {  # %XX -> character (plain shell: dash's printf has no \x, so the byte goes in as octal)
	_s="$1"; _o=""
	while [ -n "$_s" ]; do
		case "$_s" in
			%[0-9A-Fa-f][0-9A-Fa-f]*)
				_hh="${_s#%}"; _hh="${_hh%"${_hh#??}"}"; _v=$(( 0x$_hh ))
				_o="$_o$(printf "\\$(( _v >> 6 ))$(( (_v >> 3) & 7 ))$(( _v & 7 ))")"; _s="${_s#???}" ;;
			*) _c="${_s%"${_s#?}"}"; _o="$_o$_c"; _s="${_s#?}" ;;
		esac
	done
	printf '%s' "$_o"
}
gwname=$(printf '%s' "$nds" | jget gatewayname)
gwname=$(urldecode "${gwname:-Wi-Fi}" | esc)
gwaddr=$(printf '%s' "$nds" | jget gatewayaddress)
[ -n "$gwaddr" ] || gwaddr=$(printf '%s' "$nds" | jget gatewayfqdn)
url="http://$gwaddr"

page_start() {
	cat << HTML
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-store">
<title>$gwname</title>
<style>
:root{--bg:#0b1020;--card:#151b2e;--line:#252d49;--fg:#eef1f8;--mut:#8f99b5;--h:#ffb020;--e:#2fc58f;--ok:#2fc58f;--bad:#ff6b6b}
@media(prefers-color-scheme:light){:root{--bg:#f3f5fb;--card:#fff;--line:#dfe4f0;--fg:#141a2e;--mut:#5d6783}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;justify-content:center}
.w{width:100%;max-width:440px;padding:20px 16px 36px}
h1{font-size:19px;margin:0 0 4px;text-align:center}
.sub{color:var(--mut);text-align:center;margin:0 0 18px;font-size:14px}
button{font:inherit}
.btn{display:block;width:100%;padding:14px;margin:12px 0 0;border:0;border-radius:12px;font-weight:600;font-size:17px;color:#06210f;background:var(--ok);cursor:pointer}
.btn.alt{background:transparent;color:var(--fg);border:1px solid var(--line)}
.big{font-size:34px;font-weight:700;text-align:center;margin:6px 0}
.mut{color:var(--mut);text-align:center;font-size:14px}
.code{font:700 28px/1.2 ui-monospace,Menlo,Consolas,monospace;letter-spacing:3px;text-align:center;background:var(--card);border:2px dashed var(--line);border-radius:12px;padding:12px;margin:12px 0}
.msg{background:var(--card);border-left:4px solid var(--bad);border-radius:8px;padding:12px 14px;margin:12px 0}
a{color:var(--mut)}
.foot{text-align:center;font-size:12px;color:var(--mut);margin-top:22px}
</style>
</head><body><div class="w">
<h1>$gwname</h1>
HTML
}
page_end() { echo '</div></body></html>'; }

link_button() { echo "<a class=\"btn $3\" style=\"text-align:center;text-decoration:none\" href=\"$url$1\">$2</a>"; }

case "$status" in
	status)
		state=$(printf '%s' "$nds" | jget state)
		mac=$(printf '%s' "$nds" | jget mac)
		page_start
		if [ "$state" != "Authenticated" ]; then
			echo '<p class="sub">You are not connected &middot; Hindi ka pa nakakonekta</p>'
			link_button /login "Connect &middot; Kumonekta"
			page_end; exit 0
		fi
		me=$(coinslot "/me?mac=$mac")
		left=$(printf '%s' "$me" | jget remaining); plan=$(printf '%s' "$me" | jget plan)
		code=$(printf '%s' "$me" | jget voucher); thr=$(printf '%s' "$me" | jget throttled)
		if [ -z "$left" ]; then   # manager not answering: use what openNDS knows
			end=$(printf '%s' "$nds" | jget session_end)
			case "$end" in "" | *[!0-9]*) left=0 ;; *) left=$(( end - $(date +%s) )) ;; esac
		fi
		[ "${left:-0}" -lt 0 ] && left=0
		kb=$(( $(printf '%s' "$nds" | jget download_this_session | sed 's/[^0-9].*//; s/^$/0/') + $(printf '%s' "$nds" | jget upload_this_session | sed 's/[^0-9].*//; s/^$/0/') ))
		case "$plan" in endurance) pname="Endurance" ;; hyper) pname="HyperSpeed" ;; *) pname="" ;; esac
		cat << HTML
<p class="sub">You are connected &middot; Nakakonekta ka na</p>
<div class="big" id="t">$(fmt_min $(( ${left:-0} / 60 )))</div>
<p class="mut">${pname:+$pname &middot; }time left &middot; natitirang oras</p>
HTML
		[ "$thr" = "true" ] && echo '<div class="msg"><b>Fair use:</b> speed is reduced for a few minutes because of heavy use. It speeds up again automatically.</div>'
		[ "$plan" = "hyper" ] && echo "<p class=\"mut\">Data used: $(( kb / 1024 )) MB</p>"
		[ -n "$code" ] && echo "<p class=\"mut\">Your voucher code (use it to reconnect any device):</p><div class=\"code\">$code</div>"
		link_button /opennds_auth/ "Add time &middot; Dagdagan"
		echo "<script>var s=${left:-0},e=document.getElementById(\"t\");setInterval(function(){if(s>0)s--;var h=Math.floor(s/3600),m=Math.floor(s%3600/60);e.textContent=(h?h+\" hr \":\"\")+m+\" min \"+(s%60)+\" s\"},1000)</script>"
		page_end ;;
	err511)
		page_start
		echo '<p class="sub">Wi-Fi login &middot; Mag-login sa Wi-Fi</p>'
		link_button /login "Continue &middot; Magpatuloy"
		page_end ;;
	*) exit 1 ;;
esac
