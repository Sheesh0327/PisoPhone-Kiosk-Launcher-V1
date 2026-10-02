#!/bin/bash
# Runs theme_coinslot.sh the way libopennds.sh does, with the libopennds helpers stubbed:
#   header -> (display_terms | landing_page | generate_splash_sequence) -> footer
# Inputs (environment): HID MAC COINACT COINPLAN VCODE LANDING TERMS STATUSVAR AUTH_OK PORT FAS
# auth_log behaves like the real one: it asks (the fake) ndsctl to authenticate the client.
THEME="$1"
gatewayname="TestSpot"; gatewayurl="http%3a%2f%2f192.168.1.1%3a2050"; version="test"; client_zone="Zone: br-lan"
originurl="http%3a%2f%2fexample.com"; gatewayfqdn="status.client"; mountpoint=$(mktemp -d); mkdir -p "$mountpoint/ndscids"; : > "$mountpoint/ndscids/ndsinfo"
fas="${FAS:-ABC+/=}"; hid="$HID"; clientmac="$MAC"; coinact="$COINACT"; coinplan="$COINPLAN"; vcode="$VCODE"; coinforfeit="$FORFEIT"
landing="$LANDING"; terms="$TERMS"; status="$STATUSVAR"
configure_log_location() { :; }
auth_log() {
  if [ "${AUTH_OK:-1}" = 1 ]; then
    "$NDSCTL" auth "$clientmac" "$sessiontimeout" "$upload_rate" "$download_rate" 0 0 > /dev/null; ndsstatus="authenticated"
  else ndsstatus="failed"; fi
  echo "<!--AUTHCALL sessiontimeout=$sessiontimeout quotas=$quotas-->"
}
. "$THEME"
COINSLOT_URL="http://127.0.0.1:$PORT"
type header > /dev/null && header
[ "$terms" = "yes" ] && display_terms
[ "$landing" = "yes" ] && landing_page
check_authenticated
generate_splash_sequence
