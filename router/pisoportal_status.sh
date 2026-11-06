#!/bin/sh
# openNDS "statuspath" script: what a guest sees at the openNDS status address (http://status.client, or the gateway
# address on port 2050). Redirects guests to the active portal/FAS page (/ on the portal port). openNDS calls:
#   pisoportal_status.sh status <client ip> <b64 query>     a guest that is logged in
#   pisoportal_status.sh err511 <client ip>                 a guest that is not
# and serves whatever this prints. It changes nothing.
status="$1"; clientip="$2"
case "$clientip" in "" | *[!0-9.]*) exit 1 ;; esac
CONF="${COINSLOT_CONF:-/etc/coinslot.conf}"
EPOCH_CONF="${PISO_EPOCH_CONF:-/etc/coinslot.d/epoch.conf}"

conf_get() { sed -n "s/^$1='\\{0,1\\}\\([^']*\\)'\\{0,1\\}\$/\\1/p" "$CONF" 2> /dev/null | head -n 1; }

# Determine active portal address:
# 1. Active FAS host from epoch.conf if offloaded to ESP32-C3
# 2. openNDS uci fasremoteip
# 3. Fall back to local PORTAL_BIND in coinslot.conf
host=""
if [ -r "$EPOCH_CONF" ]; then
	host=$(sed -n "s/^ACTIVE_FAS_IP='\\{0,1\\}\\([^']*\\)'\\{0,1\\}\$/\\1/p" "$EPOCH_CONF" 2>/dev/null | head -n 1)
fi
if [ -z "$host" ]; then
	host=$(uci -q get opennds.@opennds[0].fasremoteip 2>/dev/null)
fi
if [ -z "$host" ]; then
	host=$(conf_get PORTAL_BIND)
fi
port=$(uci -q get opennds.@opennds[0].fasport 2>/dev/null)
port="${port:-$(conf_get PORTAL_PORT)}"
port="${port:-2080}"

case "$host$port" in *[!0-9.]*) exit 1 ;; esac
[ -n "$host" ] && [ -n "$port" ] || exit 1

case "$status" in
	status | err511) ;;
	*) exit 1 ;;
esac

url="http://$host:$port/"
cat << HTML
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-store">
<meta http-equiv="refresh" content="0;url=$url">
<title>Wi-Fi</title>
</head><body style="font-family:sans-serif;text-align:center;padding:2em">
<p><a href="$url">Continue &middot; Magpatuloy</a></p>
<script>location.replace("$url")</script>
</body></html>
HTML
