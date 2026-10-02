#!/bin/bash
# Runs theme_coinslot.sh the way libopennds.sh does, with the libopennds helpers stubbed:
#   header -> (display_terms|landing_page|generate_splash_sequence) -> footer
# Inputs (environment): HID, COINACT, LANDING, FAS, COINSLOT_URL (port of the fake socat), AUTH_OK (1/0).
# Prints the page and, on stdout's last line, "AUTHCALL sessiontimeout=<n> quotas=<..>" if auth_log ran.
THEME="$1"
gatewayname="TestSpot"; gatewayurl="http%3a%2f%2f192.168.1.1%3a2050"; version="test"; client_zone="Zone: br-lan"
originurl="http%3a%2f%2fexample.com"; gatewayfqdn="status.client"; mountpoint=$(mktemp -d); mkdir -p "$mountpoint/ndscids"; : > "$mountpoint/ndscids/ndsinfo"
fas="${FAS:-ABC+/=}"; hid="$HID"; coinact="$COINACT"; landing="$LANDING"; terms=""
configure_log_location() { :; }
auth_log() {
  if [ "${AUTH_OK:-1}" = 1 ]; then ndsstatus="authenticated"; else ndsstatus="failed"; fi
  echo "AUTHCALL sessiontimeout=$sessiontimeout quotas=$quotas"
}
. "$THEME"
COINSLOT_URL="http://127.0.0.1:$PORT"
type header >/dev/null && header
if [ "$landing" = "yes" ]; then landing_page; fi
generate_splash_sequence
