#!/bin/sh
# piso-monitor.sh: Telegram alerts and remote commands for a PisoPhone site (BusyBox ash, curl).
#
#   piso-monitor.sh run            the service: checks every minute, listens for commands (long polling)
#   piso-monitor.sh tick           one round of checks (what "run" does every minute)
#   piso-monitor.sh pair           wait for the first message sent to the bot and remember that chat as the owner
#   piso-monitor.sh send "text"    send a message to the owner
#
# Settings (/etc/piso-monitor.conf, written by "piso-setup telegram"):  TG_TOKEN  TG_CHAT  SITE_NAME  REPORT_HOUR
#   HEALTHCHECK_URL (optional: pinged every minute, a service such as healthchecks.io alerts when the pings stop)
#
# Alerts: the router restarted, the coin box does not answer for 5 minutes (and when it is back), the portal program does not
# answer (it is restarted, at most once in 10 minutes), little free flash, the revenue ledger and the box disagree or the ledger
# was edited, a device keeps opening empty coin windows, a daily revenue report. Every alert is also written to alerts.txt (the
# last 20 lines) in the data folder, which the console and the setup program's Check-up show: a shop without Telegram still sees
# them. This script runs on every router with the portal; with no Telegram settings it sends nothing and only checks and writes.
# Commands, only from the owner's chat: /status /report [days] /reconcile /diag /restart /reboot /help.
# Nothing here can change prices, passwords or Wi-Fi settings.

CONF="${PISO_MONITOR_CONF:-/etc/piso-monitor.conf}"
# shellcheck source=/dev/null
[ -r "$CONF" ] && . "$CONF"
TG_API="${TG_API:-https://api.telegram.org}"
S="${MON_STATE:-/tmp/piso-monitor}"
PORTAL="${PORTAL:-/usr/bin/pisoportal}"
PORTAL_SERVICE="${PORTAL_SERVICE:-/etc/init.d/pisoportal}"
NDSCTL="${NDSCTL:-ndsctl}"
SETUP="${PISO_SETUP:-/usr/sbin/piso-setup}"
SITE_NAME="${SITE_NAME:-PisoPhone}"
REPORT_HOUR="${REPORT_HOUR:-21}"
BOX_DOWN_AFTER="${BOX_DOWN_AFTER:-5}"        # minutes without an answer
POLL_SECONDS="${POLL_SECONDS:-25}"
TICK_SECONDS="${TICK_SECONDS:-60}"
LOGREAD="${LOGREAD:-logread}"
ALERTS_FILE="${ALERTS_FILE:-/etc/coinslot.d/alerts.txt}"
COINSLOT_CONF="${COINSLOT_CONF:-/etc/coinslot.conf}"
[ -n "$PORTAL_PORT" ] || PORTAL_PORT=$(sed -n "s/^PORTAL_PORT=['\"]\{0,1\}\([0-9]*\).*/\1/p" "$COINSLOT_CONF" 2> /dev/null | head -n 1)
PING_URL="${PING_URL:-http://127.0.0.1:${PORTAL_PORT:-2080}/ping}"
PING_TIMEOUT="${PING_TIMEOUT:-5}"
PING_FAILS_TO_RESTART="${PING_FAILS_TO_RESTART:-3}"   # failed pings, a minute apart, before the portal is restarted
RESTART_GAP="${RESTART_GAP:-600}"                      # seconds: the portal is restarted at most once in this time
DF="${DF:-df}"
mkdir -p "$S" 2> /dev/null

now() { date +%s; }
say() { logger -t piso-monitor -- "$*" 2> /dev/null; }

# tg_send <text>: to the owner's chat. Long texts are cut to what one Telegram message holds.
tg_send() {
	[ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT" ] || return 1
	_t=$(printf '%s: %s' "$SITE_NAME" "$1" | cut -c1-3800)
	curl -s -m 20 -o /dev/null -w '%{http_code}' --data-urlencode "chat_id=$TG_CHAT" --data-urlencode "text=$_t" \
		"$TG_API/bot$TG_TOKEN/sendMessage" | grep -q '^200$'
}

# note <text>: one line in alerts.txt (the last 20 are kept), whether or not Telegram is set up.
note() {
	[ -n "$ALERTS_FILE" ] || return 0
	{ tail -n 19 "$ALERTS_FILE" 2> /dev/null; printf '%s %s\n' "$(date '+%F %H:%M')" "$(printf '%s' "$1" | tr '\n' ' ')"; } > "$ALERTS_FILE.tmp" 2> /dev/null && mv "$ALERTS_FILE.tmp" "$ALERTS_FILE" 2> /dev/null
	return 0
}

# alert <key> <cooldown seconds> <text>: a new alert for the same key at most once per cooldown. It is written to alerts.txt at
# once; it is sent to Telegram (when set up) every tick until Telegram takes it.
alert() {
	_f="$S/alert.$1"; _n=$(now); _cd="$2"; shift 2
	if [ ! -r "$_f" ] || [ $((_n - $(cat "$_f"))) -ge "$_cd" ]; then
		echo "$_n" > "$_f"; note "$*"; printf '%s' "$*" > "$_f.tg"
	fi
	if [ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT" ] && [ -s "$_f.tg" ] && tg_send "$(cat "$_f.tg")"; then rm -f "$_f.tg"; fi
	return 0
}

# A modem plugged in after the setup whose network overlaps the router's own (the setup only checks it while the modem is there): hourly,
# one alert a day with the advice. An older router without the command prints something else and is ignored.
check_wan() {
	_f="$S/lastwan"; _n=$(now)
	[ -r "$_f" ] && [ $((_n - $(cat "$_f"))) -lt 3600 ] && return 0
	echo "$_n" > "$_f"
	_o=$("$SETUP" wan-clash 2> /dev/null)
	case "$_o" in "the modem's network"*) alert wan 86400 "$_o" ;; esac
	return 0
}

box_ok() { "$PORTAL" box > /dev/null 2>&1; }

check_box() {
	_fails=$(cat "$S/boxfails" 2> /dev/null); _fails=${_fails:-0}
	if box_ok; then
		if [ "$_fails" -ge "$BOX_DOWN_AFTER" ]; then note "the coin box is back."; tg_send "the coin box is back."; rm -f "$S/alert.boxdown"; fi
		echo 0 > "$S/boxfails"
	else
		_fails=$((_fails + 1)); echo "$_fails" > "$S/boxfails"
		[ "$_fails" -ge "$BOX_DOWN_AFTER" ] && alert boxdown 21600 "the coin box has not answered for $_fails minutes (power, Wi-Fi or the box itself)."
	fi
}

# The portal program itself: /ping with a time limit. After PING_FAILS_TO_RESTART failures in a row it is restarted (procd only
# restarts one that died; this catches one that hangs), at most once in RESTART_GAP seconds. A coin window that was open is settled
# from its record when the portal starts. One alert when it is restarted, one note when it answers again.
portal_ok() { [ "$(curl -s -m "$PING_TIMEOUT" "$PING_URL" 2> /dev/null)" = ok ]; }

check_portal() {
	_fails=$(cat "$S/pingfails" 2> /dev/null); _fails=${_fails:-0}
	if portal_ok; then
		if [ -e "$S/portal.down" ]; then rm -f "$S/portal.down"; note "the portal answers again."; tg_send "the portal answers again."; fi
		echo 0 > "$S/pingfails"; return 0
	fi
	_fails=$((_fails + 1)); echo "$_fails" > "$S/pingfails"
	[ "$_fails" -ge "$PING_FAILS_TO_RESTART" ] || return 0
	_last=$(cat "$S/portal.restarted" 2> /dev/null); _last=${_last:-0}
	if [ $(($(now) - _last)) -ge "$RESTART_GAP" ]; then
		now > "$S/portal.restarted"; : > "$S/portal.down"
		"$PORTAL_SERVICE" restart > /dev/null 2>&1
		alert portal 3600 "the portal program did not answer for $_fails minutes and was restarted (a coin window that was open is settled from its record)."
	fi
}

# Free flash (the router's /overlay), at most once an hour: an alert under 1 MB and another under 256 KB, each at most once a day.
# The portal keeps recording coins whatever the flash holds; this only tells the owner in time.
check_space() {
	_f="$S/lastspace"; _n=$(now)
	[ -r "$_f" ] && [ $((_n - $(cat "$_f"))) -lt 3600 ] && return 0
	echo "$_n" > "$_f"
	_free=$($DF -k /overlay 2> /dev/null | awk 'NR == 2 { print $4 }')
	case "$_free" in "" | *[!0-9]*) return 0 ;; esac
	[ "$_free" -lt 1024 ] && alert flash1m 86400 "the router has less than 1 MB of free flash ($_free KB). Old months of the sales file can be downloaded and removed with the setup program's Owner tools."
	[ "$_free" -lt 256 ] && alert flash256 86400 "the router has less than 256 KB of free flash ($_free KB): download and remove the oldest months of sales now."
	return 0
}

check_ledger() {  # at most once an hour
	_f="$S/lastreconcile"; _n=$(now)
	[ -r "$_f" ] && [ $((_n - $(cat "$_f"))) -lt 3600 ] && return 0
	echo "$_n" > "$_f"
	_o=$("$PORTAL" reconcile 2>&1); _rc=$?
	case "$_rc" in
		1) alert ledger 21600 "REVENUE MISMATCH: $_o
(If the box's revenue was just collected, its count restarted; this is normally noticed by itself. If the alert stays, run on the router: piso-setup reconcile rebase)" ;;
		3) alert ledger 21600 "REVENUE LEDGER WAS CHANGED: $_o" ;;
	esac
}

check_grief() {  # "alert grief:" lines the portal writes to the system log
	touch "$S/grief.seen"
	$LOGREAD -e coinslot 2> /dev/null | grep 'alert grief:' | tail -n 10 | while IFS= read -r _l; do
		grep -qxF "$_l" "$S/grief.seen" && continue
		printf '%s\n' "$_l" >> "$S/grief.seen"
		alert grief 900 "${_l#*coinslot: }"
	done
	[ "$(wc -l < "$S/grief.seen")" -gt 100 ] && { tail -n 50 "$S/grief.seen" > "$S/grief.tmp" && mv "$S/grief.tmp" "$S/grief.seen"; }
}

check_report() {  # the daily report, once, from REPORT_HOUR on
	_day=$(date +%F); [ "$(cat "$S/lastreport" 2> /dev/null)" = "$_day" ] && return 0
	[ "$(date +%H | sed 's/^0//')" -ge "$REPORT_HOUR" ] || return 0
	echo "$_day" > "$S/lastreport"
	tg_send "daily report $_day
$("$PORTAL" report 1 2>&1)"
}

do_tick() {
	[ -e "$S/booted" ] || { : > "$S/booted"; note "the router started (or this monitor did)."; tg_send "the router started (or this monitor did)."; }
	check_portal; check_box; check_space; check_wan; check_ledger; check_grief; check_report
	[ -n "$HEALTHCHECK_URL" ] && curl -fsS -m 10 -o /dev/null "$HEALTHCHECK_URL" 2> /dev/null
	return 0
}

# ---- commands ------------------------------------------------------------------------------------------------------------
cmd_status_text() {
	echo "uptime: $(uptime 2> /dev/null | sed 's/^ *//')"
	echo "memory free: $(awk '/MemAvailable/ {printf "%d MB", $2/1024}' /proc/meminfo 2> /dev/null)"
	box_ok && echo "box: answers" || echo "box: DOES NOT ANSWER"
	echo "guests online: $("$NDSCTL" json 2> /dev/null | grep -c '"state":"Authenticated"')"
	"$PORTAL" reconcile 2>&1 | head -2
}

handle_command() {  # handle_command <chat> <text>
	if [ "$1" != "$TG_CHAT" ]; then say "ignored a message from chat $1"; return 0; fi
	set -f   # the words of the message, split on purpose; never file names
	# shellcheck disable=SC2086
	set -- $2
	set +f
	_cmd="${1%%@*}"; _arg="$2"
	case "$_cmd" in
		/status | /start) tg_send "$(cmd_status_text)" ;;
		/report) case "$_arg" in "" | *[!0-9]*) _arg=1 ;; esac; tg_send "$("$PORTAL" report "$_arg" 2>&1)" ;;
		/reconcile) tg_send "$("$PORTAL" reconcile 2>&1)" ;;
		/diag) tg_send "$("$SETUP" diag 2>&1 | tail -c 3600)" ;;
		/update) tg_send "looking for an update the owner signed (the router, then the coin box); the result follows."; { "$SETUP" box-update > /dev/null 2>&1; "$SETUP" self-update > /dev/null 2>&1; } & ;;   # (each tells you itself; the router's update restarts this monitor, so it goes last)
		/restart) "$PORTAL_SERVICE" restart > /dev/null 2>&1; note "portal restarted by the owner."; tg_send "portal restarted (a coin window that was open is settled from its record)." ;;
		/reboot)
			if [ "$_arg" = confirm ] && [ -r "$S/reboot.ask" ] && [ $(($(now) - $(cat "$S/reboot.ask"))) -lt 120 ]; then
				tg_send "rebooting the router now."; rm -f "$S/reboot.ask"; sleep 2; reboot
			else
				now > "$S/reboot.ask"; tg_send "this reboots the router and takes about 2 minutes. Send  /reboot confirm  within 2 minutes to do it."
			fi ;;
		/help | *) tg_send "/status  /report [days]  /reconcile  /diag  /update  /restart  /reboot" ;;
	esac
}

# poll_updates <long poll seconds>: one getUpdates round; every message goes through handle_command.
poll_updates() {
	[ -n "$TG_TOKEN" ] || { sleep "$1"; return 0; }
	_off=$(cat "$S/offset" 2> /dev/null); _off=${_off:-0}
	_r=$(curl -s -m $(($1 + 10)) "$TG_API/bot$TG_TOKEN/getUpdates?timeout=$1&offset=$_off&allowed_updates=%5B%22message%22%5D") || { sleep 5; return 0; }
	printf '%s' "$_r" | grep -q '"ok":true' || { sleep 5; return 0; }
	printf '%s' "$_r" | awk 'BEGIN { RS = "{\"update_id\":" } NR > 1 {
		id = $0; sub(/[^0-9].*/, "", id)
		chat = ""; text = ""
		if (match($0, /"chat":\{"id":-?[0-9]+/)) { chat = substr($0, RSTART + 13, RLENGTH - 13) }
		if (match($0, /"text":"[^"]*"/)) { text = substr($0, RSTART + 8, RLENGTH - 9) }
		print id "\t" chat "\t" text }' | while IFS="$(printf '\t')" read -r _id _chat _text; do
		echo $((_id + 1)) > "$S/offset"
		[ -n "$_text" ] && handle_command "$_chat" "$_text"
	done
}

do_pair() {
	[ -n "$TG_TOKEN" ] || { echo "TG_TOKEN is not set in $CONF"; return 1; }
	echo "Open your bot in Telegram and send it any message (for example /start). Waiting up to 2 minutes..."
	_t=0
	while [ "$_t" -lt 12 ]; do
		_r=$(curl -s -m 20 "$TG_API/bot$TG_TOKEN/getUpdates?timeout=10&allowed_updates=%5B%22message%22%5D")
		_chat=$(printf '%s' "$_r" | sed -n 's/.*"chat":{"id":\(-\{0,1\}[0-9]*\).*/\1/p' | head -n 1)
		_name=$(printf '%s' "$_r" | sed -n 's/.*"chat":{[^}]*"first_name":"\([^"]*\)".*/\1/p' | head -n 1)
		if [ -n "$_chat" ]; then
			echo "Got a message from chat $_chat ${_name:+($_name)}."
			printf '%s' "$_chat" > "$S/paired.chat"
			return 0
		fi
		_t=$((_t + 1))
	done
	echo "No message arrived."; return 1
}

do_run() {
	say "started"
	_last=0
	while :; do
		poll_updates "$POLL_SECONDS"
		_n=$(now)
		if [ $((_n - _last)) -ge "$TICK_SECONDS" ]; then _last="$_n"; do_tick; fi
	done
}

case "$1" in
	run) do_run ;;
	tick) do_tick ;;
	pair) do_pair ;;
	send) shift; tg_send "$*" ;;
	note) shift; note "$*" ;;             # for the setup program and tests
	poll) poll_updates "${2:-1}" ;;       # for tests: one round
	*) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
