#!/bin/sh
# Shared helpers of the "flash coin" portal: the voucher roll (the one file that records every paid session), the
# coin-slot manager client, and the openNDS wrapper. Sourced by flash_coin.sh (the portal theme), flash_coin_status.sh
# (the status page) and flash_fairuse.sh. Install next to them in /usr/lib/opennds/.
#
# The roll is a CSV, one line per device:
#   code,rate_down,rate_up,quota_down,quota_up,time_limit_min,first_punched,mac,pauses_used,paused_at,remaining_at_pause,plan,wid,pesos,wmin,wpesos,wfinal
# The first 11 fields are exactly the voucher roll of the paper-voucher theme this one grew from; plan, wid (the id of
# the coin window that paid, so one window can never be credited twice) and pesos are appended, then the share of the
# last window (wmin minutes, wpesos) and whether that window is final: a window is recorded at its first coin (the device
# goes online at once) and again, priced as a whole, when it closes; the second write replaces the first share. Expiry is always
# first_punched + time_limit*60. A top-up adds to time_limit; a pause freezes remaining_at_pause and a resume moves
# first_punched forward by the time spent paused.
#
# Flash wear: the roll is written only when somebody pays, pauses or resumes (a few times a day), never on a page view.
# The lock lives in /tmp. Set FLASH_ROLL in /etc/flash_coin.conf to move the roll (for example onto a USB stick).

FLASH_CONF="${FLASH_CONF:-/etc/flash_coin.conf}"
[ -r "$FLASH_CONF" ] && . "$FLASH_CONF"
FLASH_ROLL="${FLASH_ROLL:-/etc/coinslot.d/vouchers.txt}"
FLASH_REVENUE="${FLASH_REVENUE:-$(dirname "$FLASH_ROLL")/revenue.csv}"
FLASH_LOCK="${FLASH_LOCK:-/tmp/flash_coin.lock}"
FLASH_TMP="${FLASH_TMP:-/tmp/flash_coin}"
COINSLOT_URL="${COINSLOT_URL:-http://127.0.0.1:8099}"
NDSCTL="${NDSCTL:-ndsctl}"

now_ts() { date +%s; }
lc() { printf '%s' "$1" | tr 'A-Z' 'a-z'; }
mac_key() { printf '%s' "$1" | tr 'A-F' 'a-f' | tr -d ':'; }

# ---------------------------------------------------------------------------
# Coin-slot manager (local listener)
# ---------------------------------------------------------------------------
coinslot() {  # coinslot <path>: JSON/text answer, empty if the manager is not running
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
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p' | head -n 1; }

# flash_info: reads /info into infofirst infoidle infostream pausehours pausepesos fair edown eup fairkb fairdown ...
flash_info() {
	info=$(coinslot /info)
	[ -n "$info" ] || return 1
	infoidle=$(printf '%s' "$info" | jget idle); infofirst=$(printf '%s' "$info" | jget first)
	pausehours=$(printf '%s' "$info" | jget pause_hours); pausepesos=$(printf '%s' "$info" | jget pause_pesos)
	infostream=$(printf '%s' "$info" | jget stream_port); fair=$(printf '%s' "$info" | jget fair_gb)
	edown=$(printf '%s' "$info" | jget e_down); eup=$(printf '%s' "$info" | jget e_up)
	fairkb=$(printf '%s' "$info" | jget fair_kb); fairdown=$(printf '%s' "$info" | jget fair_down)
	fairup=$(printf '%s' "$info" | jget fair_up); fairthr=$(printf '%s' "$info" | jget fair_throttle_min)
	fairfull=$(printf '%s' "$info" | jget fair_full_min)
	return 0
}

# ---------------------------------------------------------------------------
# openNDS
# ---------------------------------------------------------------------------
# nds_do <ndsctl args>: output in NDSOUT; retries while openNDS answers "locked" (it is busy with another request).
nds_do() {
	_i=0
	while [ "$_i" -lt 4 ]; do
		NDSOUT=$("$NDSCTL" "$@" 2>&1)
		case "$NDSOUT" in *locked*) _i=$((_i + 1)); sleep 1 ;; *) return 0 ;; esac
	done
	return 1
}
nds_state() { nds_do json "$1"; printf '%s' "$NDSOUT" | sed -n 's/.*"state": *"\([^"]*\)".*/\1/p' | head -n 1; }  # Authenticated | Preauthenticated | ""
# nds_regrant <mac> <minutes> <up> <down> [up quota] [down quota]: a plain auth of an authenticated client is ignored by
# openNDS, so the client is de-authenticated first (this also makes new speed caps apply to connections already open).
nds_regrant() {
	nds_do deauth "$1"
	nds_do auth "$1" "$2" "$3" "$4" "${5:-0}" "${6:-0}"
	case "$NDSOUT" in *Failed*) return 1 ;; esac
	return 0
}

# ---------------------------------------------------------------------------
# Roll: lock, parse, find, write
# ---------------------------------------------------------------------------
roll_lock() {
	mkdir -p "$(dirname "$FLASH_ROLL")" 2> /dev/null
	_w=0
	while ! mkdir "$FLASH_LOCK" 2> /dev/null; do
		_t=$(cat "$FLASH_LOCK/ts" 2> /dev/null)
		if [ -n "$_t" ] && [ $(($(now_ts) - _t)) -ge 10 ]; then rm -rf "$FLASH_LOCK"; continue; fi   # left by a killed process
		_w=$((_w + 1))
		if [ "$_w" -ge 6 ]; then
			# a lock with no timestamp is one whose owner died between mkdir and writing it
			if [ -z "$_t" ]; then rm -rf "$FLASH_LOCK"; mkdir "$FLASH_LOCK" 2> /dev/null && { now_ts > "$FLASH_LOCK/ts"; return 0; }; fi
			return 1
		fi
		sleep 1
	done
	now_ts > "$FLASH_LOCK/ts" 2> /dev/null
	return 0
}
roll_unlock() { rm -rf "$FLASH_LOCK" 2> /dev/null; }

# roll_parse <line>: sets R_CODE R_DOWN R_UP R_QDOWN R_QUP R_TL R_FP R_MAC R_PU R_PA R_RP R_PLAN R_WID R_PESOS
roll_parse() {
	IFS=, read -r R_CODE R_DOWN R_UP R_QDOWN R_QUP R_TL R_FP R_MAC R_PU R_PA R_RP R_PLAN R_WID R_PESOS R_WMIN R_WP R_WF << EOF
$1
EOF
	R_DOWN="${R_DOWN:-0}"; R_UP="${R_UP:-0}"; R_QDOWN="${R_QDOWN:-0}"; R_QUP="${R_QUP:-0}"; R_TL="${R_TL:-0}"; R_FP="${R_FP:-0}"
	R_PU="${R_PU:-0}"; R_PA="${R_PA:-0}"; R_RP="${R_RP:-0}"; R_PLAN="${R_PLAN:-hyper}"; R_PESOS="${R_PESOS:-0}"
	R_WMIN="${R_WMIN:-0}"; R_WP="${R_WP:-0}"; R_WF="${R_WF:-1}"
}
roll_line() {  # the current R_* values as a roll line
	printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s' "$R_CODE" "$R_DOWN" "$R_UP" "$R_QDOWN" "$R_QUP" "$R_TL" "$R_FP" "$R_MAC" "$R_PU" "$R_PA" "$R_RP" "$R_PLAN" "$R_WID" "$R_PESOS" "$R_WMIN" "$R_WP" "$R_WF"
}
roll_find_mac() { [ -f "$FLASH_ROLL" ] && awk -F, -v m="$(lc "$1")" 'tolower($8)==m {print; exit}' "$FLASH_ROLL"; }
roll_find_code() { [ -f "$FLASH_ROLL" ] && awk -F, -v c="$(lc "$1")" 'tolower($1)==c {print; exit}' "$FLASH_ROLL"; }
# roll_put: replace (or append) the line of R_CODE with the current R_* values. Atomic: temp file in the same directory, then mv.
roll_put() {
	[ -f "$FLASH_ROLL" ] || : > "$FLASH_ROLL"
	RL_CODE="$R_CODE" RL_LINE="$(roll_line)" awk -F, 'BEGIN{c=ENVIRON["RL_CODE"]; l=ENVIRON["RL_LINE"]}
		$1==c { if (!d) print l; d=1; next } { print } END { if (!d) print l }' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" && mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"
}
roll_del() {  # roll_del <code>
	[ -f "$FLASH_ROLL" ] || return 0
	RL_CODE="$1" awk -F, '$1!=ENVIRON["RL_CODE"]' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" && mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"
}
new_code() {  # xxxx-xxxx over a-z0-9, not already on the roll
	while :; do
		_c=$(head -c 256 /dev/urandom | tr -dc 'a-z0-9' | cut -c1-8)
		if [ "${#_c}" -eq 8 ]; then
			_c="$(printf '%s' "$_c" | cut -c1-4)-$(printf '%s' "$_c" | cut -c5-8)"
			[ -n "$(roll_find_code "$_c")" ] || { echo "$_c"; return; }
		fi
	done
}

# After roll_parse: R_END (expiry epoch of a running session), R_LEFT (seconds of paid time left; the frozen amount when
# paused). Returns 0 if there is any time left.
roll_calc() {
	R_END=$((R_FP + R_TL * 60))
	if [ "$R_PA" != 0 ]; then R_LEFT="$R_RP"; else R_LEFT=$((R_END - $(now_ts))); fi
	[ "$R_LEFT" -lt 0 ] && R_LEFT=0
	[ "$R_LEFT" -gt 0 ]
}
left_min() { echo $(((${1:-0} + 59) / 60)); }

# ---------------------------------------------------------------------------
# Paying: flash_mint
# ---------------------------------------------------------------------------
# flash_mint <mac> <wid> <plan> <pulses> <minutes> <up> <down> <forfeit 0|1> [final 1|0]
# Records a verified coin payment: <pulses> and <minutes> are the window's whole total so far, never a delta. Writing the
# same window again replaces its earlier share (the early grant at the first coin, then the whole window priced once at
# the end); a final window is never changed again. Revenue is logged once, when the window is final.
# Sets M_CODE and M_MODE (new | topup | switch | update | dup). Returns 0, 5 (roll busy), 6 (the device has live time on
# the other plan and did not agree to give it up).
flash_mint() {
	m_mac=$(lc "$1"); m_wid="$2"; m_plan="$3"; m_pulses="$4"; m_min="$5"; m_up="$6"; m_down="$7"; m_forfeit="$8"; m_final="${9:-1}"
	roll_lock || return 5
	_n=$(now_ts); M_MODE=new; M_CODE=""
	_line=$(roll_find_mac "$m_mac")
	if [ -n "$_line" ]; then
		roll_parse "$_line"
		if [ -n "$m_wid" ] && [ "$R_WID" = "$m_wid" ]; then
			if [ "$R_WF" = 1 ]; then M_MODE=dup; M_CODE="$R_CODE"; roll_unlock; return 0; fi   # credited and closed already
			_dm=$((m_min - R_WMIN)); _dp=$((m_pulses - R_WP))                                    # the same window, more coins
			R_TL=$((R_TL + _dm)); R_PESOS=$((R_PESOS + _dp)); [ "$R_PA" != 0 ] && R_RP=$((R_RP + _dm * 60))
			R_WMIN="$m_min"; R_WP="$m_pulses"; R_WF="$m_final"
			roll_put
			M_MODE=update; M_CODE="$R_CODE"
			[ "$m_final" = 1 ] && flash_revenue "$_n" "$m_plan" "$m_pulses" "$m_min" window
			roll_unlock
			return 0
		fi
		if roll_calc; then
			if [ "$R_PLAN" != "$m_plan" ]; then
				if [ "$m_forfeit" != 1 ]; then roll_unlock; return 6; fi
				roll_del "$R_CODE"; rm -f "$FLASH_TMP/fair/$(mac_key "$m_mac")"; M_MODE=switch
			else
				M_MODE=topup
			fi
		else
			roll_del "$R_CODE"      # ran out: this payment starts a new session
		fi
	fi
	if [ "$M_MODE" = topup ]; then
		R_TL=$((R_TL + m_min)); R_PESOS=$((R_PESOS + m_pulses)); R_WID="$m_wid"
		[ "$R_PA" != 0 ] && R_RP=$((R_RP + m_min * 60))
	else
		R_CODE=$(new_code); R_DOWN="$m_down"; R_UP="$m_up"; R_QDOWN=0; R_QUP=0; R_TL="$m_min"; R_FP="$_n"; R_MAC="$m_mac"
		R_PU=0; R_PA=0; R_RP=0; R_PLAN="$m_plan"; R_WID="$m_wid"; R_PESOS="$m_pulses"
	fi
	R_WMIN="$m_min"; R_WP="$m_pulses"; R_WF="$m_final"
	roll_put
	M_CODE="$R_CODE"
	[ "$m_final" = 1 ] && flash_revenue "$_n" "$m_plan" "$m_pulses" "$m_min" "$M_MODE"
	roll_unlock
	return 0
}
flash_revenue() {  # flash_revenue <time> <plan> <pesos> <minutes> <kind>
	mkdir -p "$(dirname "$FLASH_REVENUE")" 2> /dev/null
	echo "$1,$2,$3,$4,$5" >> "$FLASH_REVENUE"
}

# ---------------------------------------------------------------------------
# Granting access
# ---------------------------------------------------------------------------
# flash_session <mac>: what the device may use right now. Sets S_MIN (minutes), S_UP S_DOWN S_QUP S_QDOWN, S_CODE, S_PLAN.
# A paused session is resumed here (first_punched moves forward by the time spent paused). Returns 0, 2 (nothing on the
# roll), 3 (ran out; the line is deleted), 5 (roll busy).
flash_session() {
	roll_lock || return 5
	_line=$(roll_find_mac "$1")
	if [ -z "$_line" ]; then roll_unlock; return 2; fi
	roll_parse "$_line"
	if ! roll_calc; then roll_del "$R_CODE"; roll_unlock; return 3; fi
	if [ "$R_PA" != 0 ]; then
		R_FP=$((R_FP + $(now_ts) - R_PA)); R_PA=0; R_RP=0
		roll_put
	fi
	S_MIN=$(left_min "$R_LEFT"); S_UP="$R_UP"; S_DOWN="$R_DOWN"; S_QUP="$R_QUP"; S_QDOWN="$R_QDOWN"; S_CODE="$R_CODE"; S_PLAN="$R_PLAN"
	roll_unlock
	return 0
}

# flash_peek <mac>: read-only look at a device's line (no lock, no changes). Sets the R_* values plus R_END R_LEFT and
# P_STATE = none | running | paused | expired. Returns 1 when the device has no line.
flash_peek() {
	P_STATE=none
	_line=$(roll_find_mac "$1")
	[ -n "$_line" ] || return 1
	roll_parse "$_line"
	if roll_calc; then
		if [ "$R_PA" != 0 ]; then P_STATE=paused; else P_STATE=running; fi
	else P_STATE=expired; fi
	return 0
}

# flash_pause <mac> <min pesos>: returns 0 ok, 2 no session, 5 busy, 6 already paused, 7 the one pause was used,
# 9 not eligible (Endurance sessions that paid at least <min pesos> only).
flash_pause() {
	roll_lock || return 5
	_line=$(roll_find_mac "$1")
	[ -n "$_line" ] || { roll_unlock; return 2; }
	roll_parse "$_line"
	if ! roll_calc; then roll_unlock; return 2; fi
	if [ "$R_PA" != 0 ]; then roll_unlock; return 6; fi
	if [ "$R_PU" -ge 1 ]; then roll_unlock; return 7; fi
	if [ "$R_PLAN" != endurance ] || [ "$R_PESOS" -lt "${2:-10}" ]; then roll_unlock; return 9; fi
	R_PU=$((R_PU + 1)); R_PA=$(now_ts); R_RP="$R_LEFT"
	roll_put
	roll_unlock
	nds_do deauth "$1"
	return 0
}

# flash_restore <code> <mac>: move a session to this device by its code. Returns 0, 1 (not a code), 2 (unknown), 3 (ran
# out), 5 busy, 7 this device already has time of its own, 8 too many wrong tries.
flash_restore() {
	_c=$(lc "$1"); _m=$(lc "$2")
	case "$_c" in ????-????) ;; *) return 1 ;; esac
	case "$_c" in *[!a-z0-9-]*) return 1 ;; esac
	mkdir -p "$FLASH_TMP"; _f="$FLASH_TMP/fails"; _n=$(now_ts)
	if [ -r "$_f" ] && [ "$(awk -v t=$((_n - 600)) '$1 > t' "$_f" | wc -l)" -ge 10 ]; then return 8; fi
	roll_lock || return 5
	_line=$(roll_find_code "$_c")
	if [ -z "$_line" ]; then roll_unlock; echo "$_n" >> "$_f"; return 2; fi
	roll_parse "$_line"
	if ! roll_calc; then roll_del "$R_CODE"; roll_unlock; return 3; fi
	if [ "$(lc "$R_MAC")" != "$_m" ]; then
		_keep="$R_CODE"; _old="$R_MAC"
		_own=$(roll_find_mac "$_m")
		if [ -n "$_own" ]; then
			roll_parse "$_own"
			if roll_calc; then roll_unlock; return 7; fi
			roll_del "$R_CODE"
		fi
		roll_parse "$(roll_find_code "$_keep")"
		R_MAC="$_m"; roll_put
		roll_unlock
		[ "$(nds_state "$_old")" = "Authenticated" ] && nds_do deauth "$_old"
		return 0
	fi
	roll_unlock
	return 0
}

# flash_purge <pause hours>: delete sessions that ran out more than two days ago and paused ones left longer than the limit.
flash_purge() {
	[ -f "$FLASH_ROLL" ] || return 0
	roll_lock || return 1
	awk -F, -v n="$(now_ts)" -v ph="${1:-72}" '{ if ($10 != "" && $10 != 0) { if ($10 + ph * 3600 < n) next } else if ($7 + $6 * 60 + 172800 < n) next; print }' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" &&
		{ cmp -s "$FLASH_ROLL.tmp" "$FLASH_ROLL" && rm -f "$FLASH_ROLL.tmp" || mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"; }
	roll_unlock
}
