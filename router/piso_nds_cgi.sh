#!/bin/sh
# piso_nds_cgi.sh: Authenticated CGI endpoint for OpenWrt uhttpd (/www/cgi-bin/piso-nds).
# Exposes openNDS management, ARP client lookup, multi-box coordination, and failover epoch.
# Authentication uses one-time nonces and HMAC-SHA256 signatures with the shared gateway_key.

COINSLOT_CONF="${PISO_COINSLOT_CONF:-/etc/coinslot.conf}"
BOXES_CONF="${PISO_BOXES_CONF:-/etc/coinslot.d/boxes.conf}"
EPOCH_CONF="${PISO_EPOCH_CONF:-/etc/coinslot.d/epoch.conf}"
NONCE_DIR="${PISO_NONCE_DIR:-/tmp/piso-cgi-nonces}"
NDSCTL="${NDSCTL:-ndsctl}"

# Response helpers
send_json() {
	status="${2:-200 OK}"
	printf "Status: %s\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n%s\n" "$status" "$1"
	exit 0
}

send_err() {
	status="${2:-400 Bad Request}"
	send_json "{\"ok\":false,\"error\":\"$1\"}" "$status"
}

# Parameter extraction from QUERY_STRING or POST body
get_param() {
	key="$1"; str="${2:-$QUERY_STRING}"
	printf '%s' "$str" | sed -n "s/^.*[?&]${key}=\\([^&]*\\).*/\\1/p" | head -n 1 | sed 's/+/ /g;s/%\([0-9A-Fa-f]\{2\}\)/\\x\1/g' | xargs -0 printf '%b' 2>/dev/null
}

ACTION=$(get_param "action")
NONCE=$(get_param "nonce")
SIG=$(get_param "sig")

# POST body reading if present
BODY=""
if [ "$REQUEST_METHOD" = "POST" ] && [ -n "$CONTENT_LENGTH" ] && [ "$CONTENT_LENGTH" -gt 0 ]; then
	# Limit maximum POST body to 16KB for safety
	if [ "$CONTENT_LENGTH" -gt 16384 ]; then
		send_err "payload_too_large" "413 Payload Too Large"
	fi
	BODY=$(dd bs=1 count="$CONTENT_LENGTH" 2>/dev/null)
	[ -n "$ACTION" ] || ACTION=$(get_param "action" "$BODY")
	[ -n "$NONCE" ] || NONCE=$(get_param "nonce" "$BODY")
	[ -n "$SIG" ] || SIG=$(get_param "sig" "$BODY")
fi

# Load Gateway Key
GW_KEY=""
if [ -r "$COINSLOT_CONF" ]; then
	GW_KEY=$(sed -n "s/^GW_KEY='\{0,1\}\([^']*\)'\{0,1\}\$/\1/p" "$COINSLOT_CONF" 2>/dev/null | head -n 1)
fi

# Clean old nonces (>60s)
mkdir -p "$NONCE_DIR" 2>/dev/null
find "$NONCE_DIR" -type f -mmin +1 -delete 2>/dev/null

# Action: challenge (unauthenticated; gives nonce + current unix time + active epoch)
if [ "$ACTION" = "challenge" ] || [ "$ACTION" = "ping" ]; then
	NOW=$(date +%s)
	EPOCH="1"
	[ -r "$EPOCH_CONF" ] && EPOCH=$(cat "$EPOCH_CONF" 2>/dev/null | tr -dc '0-9')
	EPOCH="${EPOCH:-1}"

	if [ "$ACTION" = "ping" ]; then
		send_json "{\"ok\":true,\"time\":$NOW,\"epoch\":$EPOCH}" "200 OK"
	fi

	# Generate 32-char hex nonce
	RAND_HEX=$(head -c 16 /dev/urandom 2>/dev/null | hexdump -ve '1/1 "%.2x"' 2>/dev/null)
	if [ -z "$RAND_HEX" ] || [ "${#RAND_HEX}" -ne 32 ]; then
		RAND_HEX=$(printf '%s%s' "$NOW" "$$" | sha256sum | awk '{print $1}' | cut -c1-32)
	fi

	echo "$NOW" > "$NONCE_DIR/$RAND_HEX" 2>/dev/null
	send_json "{\"ok\":true,\"nonce\":\"$RAND_HEX\",\"time\":$NOW,\"epoch\":$EPOCH}" "200 OK"
fi

# Authenticate request
if [ -z "$GW_KEY" ]; then
	send_err "gateway_key_not_configured" "500 Internal Server Error"
fi

case "$NONCE" in
	"" | *[!0-9a-fA-F]* | ?????????????????????????????????*)
		send_err "invalid_nonce" "401 Unauthorized"
		;;
esac

NONCE_FILE="$NONCE_DIR/$NONCE"
if [ ! -f "$NONCE_FILE" ]; then
	send_err "nonce_expired_or_used" "401 Unauthorized"
fi

# Read creation time & consume nonce immediately to block replays
NONCE_TIME=$(cat "$NONCE_FILE" 2>/dev/null)
rm -f "$NONCE_FILE" 2>/dev/null
NOW=$(date +%s)
if [ $((NOW - NONCE_TIME)) -gt 35 ] || [ $((NOW - NONCE_TIME)) -lt -5 ]; then
	send_err "nonce_timeout" "401 Unauthorized"
fi

# Compute expected HMAC-SHA256 signature
# Canonical payload: nonce|action|param|key
PARAM=$(get_param "mac")
[ -n "$PARAM" ] || PARAM=$(get_param "ip")
[ -n "$PARAM" ] || PARAM=$(get_param "slot")
SIGN_PAYLOAD="${NONCE}|${ACTION}|${PARAM}|${GW_KEY}"
EXPECTED_SIG=$(printf '%s' "$SIGN_PAYLOAD" | sha256sum | awk '{print $1}')

if [ "$SIG" != "$EXPECTED_SIG" ]; then
	send_err "signature_mismatch" "403 Forbidden"
fi

# Execute Authenticated Action
case "$ACTION" in
	auth | grant)
		MAC=$(get_param "mac" | tr '[:upper:]' '[:lower:]')
		MINS=$(get_param "mins")
		UP=$(get_param "up"); UP="${UP:-0}"
		DOWN=$(get_param "down"); DOWN="${DOWN:-0}"
		QUP=$(get_param "qup"); QUP="${QUP:-0}"
		QDOWN=$(get_param "qdown"); QDOWN="${QDOWN:-0}"

		case "$MAC" in
			[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]) ;;
			*) send_err "invalid_mac" "400 Bad Request" ;;
		esac
		case "$MINS" in
			"" | *[!0-9]*) send_err "invalid_minutes" "400 Bad Request" ;;
		esac

		# If already authenticated, deauth first so openNDS takes updated time/caps
		STATE=$("$NDSCTL" json "$MAC" 2>/dev/null | grep -o '"state":"[^"]*"' | cut -d'"' -f4)
		if [ "$STATE" = "Authenticated" ]; then
			"$NDSCTL" deauth "$MAC" >/dev/null 2>&1
		fi

		# ndsctl auth <mac> <session minutes> <upload kbit/s> <download kbit/s> <upload quota kB> <download quota kB> <custom>
		OUT=$("$NDSCTL" auth "$MAC" "$MINS" "$UP" "$DOWN" "$QUP" "$QDOWN" "piso-nds" 2>&1)
		if printf '%s' "$OUT" | grep -qi "failed"; then
			send_err "auth_failed: $OUT" "500 Internal Server Error"
		fi

		send_json "{\"ok\":true,\"action\":\"auth\",\"mac\":\"$MAC\",\"mins\":$MINS}" "200 OK"
		;;

	deauth)
		MAC=$(get_param "mac" | tr '[:upper:]' '[:lower:]')
		case "$MAC" in
			[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]) ;;
			*) send_err "invalid_mac" "400 Bad Request" ;;
		esac
		"$NDSCTL" deauth "$MAC" >/dev/null 2>&1
		send_json "{\"ok\":true,\"action\":\"deauth\",\"mac\":\"$MAC\"}" "200 OK"
		;;

	arp)
		IP=$(get_param "ip")
		case "$IP" in
			"" | *[!0-9.]*) send_err "invalid_ip" "400 Bad Request" ;;
		esac
		MAC=$(awk -v ip="$IP" '$1 == ip && $4 ~ /^[0-9a-fA-F:]+$/ { print tolower($4); exit }' /proc/net/arp 2>/dev/null)
		if [ -z "$MAC" ] || [ "$MAC" = "00:00:00:00:00:00" ]; then
			send_err "mac_not_found" "404 Not Found"
		fi
		send_json "{\"ok\":true,\"ip\":\"$IP\",\"mac\":\"$MAC\"}" "200 OK"
		;;

	status)
		NDS_JSON=$("$NDSCTL" json 2>/dev/null)
		EPOCH=$(cat "$EPOCH_CONF" 2>/dev/null | tr -dc '0-9')
		EPOCH="${EPOCH:-1}"
		NOW=$(date +%s)
		send_json "{\"ok\":true,\"time\":$NOW,\"epoch\":$EPOCH,\"nds\":$NDS_JSON}" "200 OK"
		;;

	boxes)
		if [ ! -r "$BOXES_CONF" ]; then
			send_json "{\"ok\":true,\"boxes\":[]}" "200 OK"
		fi
		JSON_ITEMS=""
		while IFS=: read -r b_slot b_mac b_ip b_name b_role b_status || [ -n "$b_slot" ]; do
			case "$b_slot" in "#"* | "") continue ;; esac
			[ -z "$JSON_ITEMS" ] || JSON_ITEMS="$JSON_ITEMS,"
			JSON_ITEMS="$JSON_ITEMS{\"slot\":$b_slot,\"mac\":\"$b_mac\",\"ip\":\"$b_ip\",\"name\":\"$b_name\",\"role\":\"$b_role\",\"status\":\"$b_status\"}"
		done < "$BOXES_CONF"
		send_json "{\"ok\":true,\"boxes\":[$JSON_ITEMS]}" "200 OK"
		;;

	set-role)
		SLOT=$(get_param "slot")
		ROLE=$(get_param "role")
		case "$SLOT" in 1 | 2 | 3 | 4) ;; *) send_err "invalid_slot" "400 Bad Request" ;; esac
		case "$ROLE" in wifi | phone | hub) ;; *) send_err "invalid_role" "400 Bad Request" ;; esac

		TMP_B="$BOXES_CONF.tmp.$$"
		if [ -r "$BOXES_CONF" ]; then
			awk -v slot="$SLOT" -v role="$ROLE" -F: 'BEGIN { OFS=":" } $1 == slot { $5 = role } { print }' "$BOXES_CONF" > "$TMP_B" && mv "$TMP_B" "$BOXES_CONF"
		fi
		send_json "{\"ok\":true,\"slot\":$SLOT,\"role\":\"$ROLE\"}" "200 OK"
		;;

	epoch)
		SET_EPOCH=$(get_param "set")
		if [ -n "$SET_EPOCH" ]; then
			case "$SET_EPOCH" in *[!0-9]*) send_err "invalid_epoch" "400 Bad Request" ;; esac
			echo "$SET_EPOCH" > "$EPOCH_CONF" 2>/dev/null
			send_json "{\"ok\":true,\"epoch\":$SET_EPOCH}" "200 OK"
		fi
		CUR_EPOCH=$(cat "$EPOCH_CONF" 2>/dev/null | tr -dc '0-9')
		CUR_EPOCH="${CUR_EPOCH:-1}"
		send_json "{\"ok\":true,\"epoch\":$CUR_EPOCH}" "200 OK"
		;;

	sqm)
		DOWN=$(get_param "down")
		UP=$(get_param "up")
		ENABLE=$(get_param "enable")
		if [ "$ENABLE" = "on" ] || [ "$ENABLE" = "off" ]; then
			uci set sqm.@sqm[0].enabled="$([ "$ENABLE" = "on" ] && echo 1 || echo 0)" 2>/dev/null
		fi
		[ -z "$DOWN" ] || uci set sqm.@sqm[0].download="$DOWN" 2>/dev/null
		[ -z "$UP" ] || uci set sqm.@sqm[0].upload="$UP" 2>/dev/null
		uci commit sqm 2>/dev/null
		/etc/init.d/sqm reload >/dev/null 2>&1
		send_json "{\"ok\":true,\"action\":\"sqm\"}" "200 OK"
		;;

	telegram)
		TOKEN=$(get_param "token")
		CHAT=$(get_param "chat")
		HOUR=$(get_param "hour")
		CONF_TG="/etc/piso-monitor.conf"
		[ -r "$CONF_TG" ] || touch "$CONF_TG"
		[ -z "$TOKEN" ] || sed -i "/^TG_TOKEN=/d" "$CONF_TG"
		[ -z "$TOKEN" ] || echo "TG_TOKEN='$TOKEN'" >> "$CONF_TG"
		[ -z "$CHAT" ] || sed -i "/^TG_CHAT=/d" "$CONF_TG"
		[ -z "$CHAT" ] || echo "TG_CHAT='$CHAT'" >> "$CONF_TG"
		[ -z "$HOUR" ] || sed -i "/^REPORT_HOUR=/d" "$CONF_TG"
		[ -z "$HOUR" ] || echo "REPORT_HOUR='$HOUR'" >> "$CONF_TG"
		send_json "{\"ok\":true,\"action\":\"telegram\"}" "200 OK"
		;;

	*)
		send_err "unknown_action: $ACTION" "400 Bad Request"
		;;
esac
