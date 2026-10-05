#!/bin/sh
# Fair-use watcher of the "flash coin" portal (HyperSpeed only): once a connected HyperSpeed device has moved more than
# the limit (default 5 GB), its speed is cut for a few minutes, then restored for a few minutes, and so on until the
# session ends. Endurance is already speed-capped and is never touched. The limits come from the coin-slot manager
# (/info), so there is one place to change them. Also removes sessions that ran out long ago from the roll.
#
#   flash_fairuse.sh          run forever (started by the flash_coin init script)
#   flash_fairuse.sh once     one pass (tests)
. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"
FAIR_DIR="$FLASH_TMP/fair"

fair_file() { echo "$FAIR_DIR/$1"; }
fair_init() { [ -e "$(fair_file "$1")" ] || { mkdir -p "$FAIR_DIR"; printf 'USED_KB=0\nOFFSET_KB=0\nPHASE=normal\nPHASE_SINCE=0\n' > "$(fair_file "$1")"; }; }
fair_save() { printf 'USED_KB=%s\nOFFSET_KB=%s\nPHASE=%s\nPHASE_SINCE=%s\n' "$2" "$3" "$4" "$5" > "$(fair_file "$1").tmp" && mv "$(fair_file "$1").tmp" "$(fair_file "$1")"; }

# kB moved so far by a client in this openNDS session (download + upload)
nds_kb() {
	nds_do json "$1"
	_d=$(printf '%s' "$NDSOUT" | sed -n 's/.*"download_this_session": *"\([0-9]*\)".*/\1/p' | head -n 1)
	_u=$(printf '%s' "$NDSOUT" | sed -n 's/.*"upload_this_session": *"\([0-9]*\)".*/\1/p' | head -n 1)
	echo $(( ${_d:-0} + ${_u:-0} ))
}

# fair_flip <mac> <used kb> <phase> <down kbps> <up kbps>: re-grant the rest of the paid session with new speed caps.
fair_flip() {
	_mac="$1"; _used="$2"; _phase="$3"
	flash_peek "$_mac" && [ "$P_STATE" = running ] || return 1
	_min=$(left_min "$R_LEFT")
	nds_regrant "$_mac" "$_min" "$5" "$4" "$R_QUP" "$R_QDOWN" || return 1
	fair_save "$(mac_key "$_mac")" "$_used" "$(nds_kb "$_mac")" "$_phase" "$(now_ts)"   # the counters restart (or not): remember the reading
}

# One pass over all connected clients.
fair_tick() {
	flash_info || return 0
	_limit="${fairkb:-5242880}"
	"$NDSCTL" json 2> /dev/null | awk -F'"' '
		/"mac":/ { mac = $4 } /"state":/ { st = $4 } /"download_this_session":/ { dl = $4 }
		/"upload_this_session":/ { print mac, st, dl, $4 }' | while read -r mac st dl ul; do
		[ "$st" = "Authenticated" ] || continue
		flash_peek "$mac" || continue
		[ "$P_STATE" = running ] && [ "$R_PLAN" = hyper ] || continue
		mk=$(mac_key "$mac")
		fair_init "$mk"; . "$(fair_file "$mk")"
		cur=$(( ${dl:-0} + ${ul:-0} ))
		[ "$cur" -ge "$OFFSET_KB" ] || OFFSET_KB=0
		total=$(( USED_KB + cur - OFFSET_KB ))
		n=$(now_ts)
		if [ "$total" -lt "$_limit" ]; then fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"; continue; fi
		if [ "$PHASE" = "normal" ] && [ $(( n - PHASE_SINCE )) -ge $(( ${fairfull:-2} * 60 )) ]; then
			fair_flip "$mac" "$total" throttled "${fairdown:-2000}" "${fairup:-1000}"
		elif [ "$PHASE" = "throttled" ] && [ $(( n - PHASE_SINCE )) -ge $(( ${fairthr:-5} * 60 )) ]; then
			fair_flip "$mac" "$total" normal "$R_DOWN" "$R_UP"
		else
			fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"
		fi
	done
}

mkdir -p "$FAIR_DIR"
if [ "$1" = once ]; then fair_tick; flash_purge "${pausehours:-72}"; exit 0; fi
while :; do
	fair_tick
	flash_info && flash_purge "${pausehours:-72}"
	sleep 60
done
