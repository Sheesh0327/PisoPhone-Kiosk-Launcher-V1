#!/bin/sh
# One HTTP request per connection, started by socat (see the listener line below).
#   GET /pay?session=<id>&duration=<seconds>   -> runs gateway_pay.sh, answers {"pulses":n,"minutes":n}
#
# Run the listener on the router (LAN side only; it has no login of its own):
#   export GW_BOX=192.168.1.50 GW_KEY=<gateway key>
#   socat TCP-LISTEN:8099,bind=127.0.0.1,reuseaddr,fork EXEC:/root/gateway_socat_handler.sh
# Then e.g. from the OpenNDS binauth script:  curl -s "http://127.0.0.1:8099/pay?session=$MAC&duration=60"
DIR=$(dirname "$0")

reply() {  # reply <status line> <json>
  printf 'HTTP/1.1 %s\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: %s\r\n\r\n%s' \
    "$1" "${#2}" "$2"
}

read -r method target _
while read -r line; do [ "$line" = "$(printf '\r')" ] || [ -z "$line" ] && break; done   # skip headers

[ "$method" = "GET" ] || { reply "405 Method Not Allowed" '{"error":"METHOD"}'; exit 0; }
case "$target" in /pay\?*) ;; *) reply "404 Not Found" '{"error":"NOT_FOUND"}'; exit 0 ;; esac

query="${target#*\?}"
session=""; duration=60
for pair in $(printf '%s' "$query" | tr '&' ' '); do
  case "$pair" in
    session=*) session="${pair#session=}" ;;
    duration=*) duration="${pair#duration=}" ;;
  esac
done
# gateway_pay.sh validates again; refuse early so nothing odd reaches a command line.
case "$session" in ""|*[!A-Za-z0-9._:-]*) reply "400 Bad Request" '{"error":"INVALID_SESSION"}'; exit 0 ;; esac

out=$("$DIR/gateway_pay.sh" "$session" "$duration" --ack 2>/dev/null)
case "$out" in
  PULSES=*)
    pulses=${out#PULSES=}; pulses=${pulses%% *}
    minutes=${out##*MINUTES=}
    reply "200 OK" "{\"pulses\":$pulses,\"minutes\":$minutes}" ;;
  ERROR=SLOT_BUSY) reply "409 Conflict" '{"error":"SLOT_BUSY"}' ;;
  ERROR=*) reply "502 Bad Gateway" "{\"error\":\"${out#ERROR=}\"}" ;;
  *) reply "502 Bad Gateway" '{"error":"NO_ANSWER"}' ;;
esac
