#!/bin/sh
# Coin-slot manager for OpenNDS. One file, three modes (POSIX sh: busybox ash on OpenWrt):
#
#   coinslot-listener.sh serve              run the local listener (socat, 127.0.0.1 only)
#   coinslot-listener.sh handle             one HTTP request on stdin/stdout (started by socat)
#   coinslot-listener.sh worker <sid> <s>   hold one customer's coin window (started by the listener)
#
# Why a background worker: an OpenNDS ThemeSpec runs once per page load, but the customer needs
# 30-60 seconds to insert coins. The worker arms the box's coin slot, keeps counting, and writes
# progress to a state file; the theme page just reads that file every couple of seconds.
#
# Local API (GET, answers JSON). <sid> is 32 hex chars derived from the client's OpenNDS id.
#   /info                 {"rate":10,"window":60}
#   /start?sid=           start (or resume) a coin window         -> status
#   /status?sid=          current progress                        -> status
#   /finish?sid=          stop accepting now, count what arrived  -> status
#   /claim?sid=           coins ready to be turned into time      -> {"pulses":n,"minutes":m}
#   /confirm?sid=         access was granted: acknowledge coins on the box and close the session
# status = {"state":"none|starting|armed|done|error","pulses":n,"remaining":s,"claimed":bool,"error":"CODE"}
CONF="${COINSLOT_CONF:-/etc/coinslot.conf}"
[ -r "$CONF" ] && . "$CONF"
GW_BOX="${GW_BOX:-192.168.1.10}"
WIFI_MINUTES_PER_COIN="${WIFI_MINUTES_PER_COIN:-10}"
COIN_WINDOW_SECONDS="${COIN_WINDOW_SECONDS:-60}"
LISTEN_PORT="${LISTEN_PORT:-8099}"
STATE_DIR="${STATE_DIR:-/tmp/coinslot}"
SELF="$0"

# ---------------------------------------------------------------------------
# Talking to the box (see docs/overhaul/gateway-coinslot-api.md)
# ---------------------------------------------------------------------------
BASE="http://$GW_BOX/api/gateway"
if command -v curl >/dev/null 2>&1; then
  http() { curl -sS -m 8 "$1" 2>/dev/null; }          # no -f: error answers carry a JSON body
else
  http() { wget -qO- -T 8 "$1" 2>/dev/null; }
fi
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p'; }

# call <sid> <action> [extra query]: fetch a one-time nonce, sign, send; prints the box's JSON.
call() {
  _sid="$1"; _action="$2"; _extra="$3"
  _nonce=$(http "$BASE/challenge" | jget nonce)
  [ -n "$_nonce" ] || { echo '{"success":false,"error":"NO_NONCE"}'; return 1; }
  _sig=$(printf 'gw1:%s:%s:%s' "$_action" "$_sid" "$_nonce" | openssl dgst -sha256 -hmac "$GW_KEY" | awk '{print $NF}')
  http "$BASE/$_action?session=$_sid&nonce=$_nonce&sig=$_sig$_extra"
}

# ---------------------------------------------------------------------------
# Per-customer state: $STATE_DIR/<sid>/{state,stop,pid,claimed,ackpending}
# ---------------------------------------------------------------------------
valid_sid() { case "$1" in ""|*[!0-9a-f]*) return 1 ;; esac; [ "${#1}" -eq 32 ]; }

# write_state <dir> <state> <pulses> <remaining> <error>   (atomic: write a temp file, then rename)
write_state() {
  printf 'STATE=%s\nPULSES=%s\nREMAINING=%s\nERROR=%s\n' "$2" "$3" "$4" "$5" > "$1/state.tmp" && mv "$1/state.tmp" "$1/state"
}

# read_state <dir>: sets STATE PULSES REMAINING ERROR (state "none" if the customer has no session)
read_state() {
  STATE=none; PULSES=0; REMAINING=0; ERROR=""
  [ -r "$1/state" ] && . "$1/state"
}

worker_running() {
  [ -r "$1/pid" ] && kill -0 "$(cat "$1/pid")" 2>/dev/null
}

status_json() {  # status_json <dir>
  read_state "$1"
  claimed=false; [ -e "$1/claimed" ] && claimed=true
  printf '{"state":"%s","pulses":%s,"remaining":%s,"claimed":%s,"error":"%s"}' \
    "$STATE" "${PULSES:-0}" "${REMAINING:-0}" "$claimed" "$ERROR"
}

# ---------------------------------------------------------------------------
# Worker: arm, count, always disarm
# ---------------------------------------------------------------------------
do_worker() {
  sid="$1"; window="$2"; dir="$STATE_DIR/$sid"
  echo $$ > "$dir/pid"
  armed=0
  release() {
    [ "$armed" = 1 ] || return 0
    armed=0
    for _ in 1 2 3; do call "$sid" release >/dev/null && break; sleep 1; done  # one lost packet must not leave the acceptor powered
  }
  trap 'release; write_state "$dir" done "${pulses:-0}" 0 ""; exit 0' INT TERM HUP
  pulses=0

  answer=$(call "$sid" arm "&duration=$window")
  if [ "$(printf '%s' "$answer" | jget success)" != "true" ]; then
    err=$(printf '%s' "$answer" | jget error)
    write_state "$dir" error 0 0 "${err:-NO_ANSWER}"
    return 1
  fi
  armed=1
  end=$(( $(date +%s) + window ))
  write_state "$dir" armed "$(printf '%s' "$answer" | jget pulses)" "$window" ""

  while [ "$(date +%s)" -lt "$end" ] && [ ! -e "$dir/stop" ]; do
    sleep 1
    st=$(call "$sid" status) || continue                  # a missed poll must not end the window early
    [ "$(printf '%s' "$st" | jget success)" = "true" ] || continue
    pulses=$(printf '%s' "$st" | jget pulses)
    write_state "$dir" armed "$pulses" "$(( end - $(date +%s) ))" ""
    [ "$(printf '%s' "$st" | jget state)" = "armed" ] || break   # the box ended it (its own session cap)
  done

  release
  # In-flight coins: the box reports "idle" once it has drained.
  i=0
  while [ "$i" -lt 30 ]; do
    st=$(call "$sid" status) && {
      pulses=$(printf '%s' "$st" | jget pulses)
      [ "$(printf '%s' "$st" | jget state)" = "idle" ] && break
    }
    i=$((i + 1)); sleep 1
  done
  write_state "$dir" done "${pulses:-0}" 0 ""
}

# ---------------------------------------------------------------------------
# HTTP handler (one request per connection)
# ---------------------------------------------------------------------------
reply() {  # reply <status line> <json>
  printf 'HTTP/1.1 %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: %s\r\n\r\n%s' \
    "$1" "${#2}" "$2"
}

do_handle() {
  read -r method target _
  while read -r line; do [ -z "${line%$(printf '\r')}" ] && break; done     # skip the headers
  [ "$method" = "GET" ] || { reply "405 Method Not Allowed" '{"error":"METHOD"}'; return; }

  path="${target%%\?*}"; query=""
  case "$target" in *\?*) query="${target#*\?}" ;; esac
  sid=""
  for pair in $(printf '%s' "$query" | tr '&' ' '); do
    case "$pair" in sid=*) sid="${pair#sid=}" ;; esac
  done

  if [ "$path" = "/info" ]; then
    reply "200 OK" "{\"rate\":$WIFI_MINUTES_PER_COIN,\"window\":$COIN_WINDOW_SECONDS}"
    return
  fi
  valid_sid "$sid" || { reply "400 Bad Request" '{"error":"INVALID_SID"}'; return; }
  dir="$STATE_DIR/$sid"

  case "$path" in
    /start)
      mkdir -p "$dir"
      if worker_running "$dir"; then reply "200 OK" "$(status_json "$dir")"; return; fi
      read_state "$dir"
      # Coins of a finished window that have not been turned into access yet are never discarded.
      if [ "$STATE" = "done" ] && [ "${PULSES:-0}" -gt 0 ] && [ ! -e "$dir/claimed" ]; then
        reply "200 OK" "$(status_json "$dir")"; return
      fi
      if [ -e "$dir/ackpending" ]; then          # an earlier grant could not be acknowledged on the box
        if call "$sid" ack >/dev/null && rm -f "$dir/ackpending"; then :; else
          reply "503 Service Unavailable" '{"state":"error","pulses":0,"remaining":0,"claimed":false,"error":"ACK_PENDING"}'; return
        fi
      fi
      rm -f "$dir/stop" "$dir/claimed" "$dir/state"
      write_state "$dir" starting 0 "$COIN_WINDOW_SECONDS" ""
      # The worker must not inherit the socket (it would hold the connection open): detach its fds.
      ( "$SELF" worker "$sid" "$COIN_WINDOW_SECONDS" </dev/null >/dev/null 2>&1 & )
      reply "200 OK" "$(status_json "$dir")" ;;
    /status)
      reply "200 OK" "$(status_json "$dir")" ;;
    /finish)
      worker_running "$dir" && : > "$dir/stop"
      reply "200 OK" "$(status_json "$dir")" ;;
    /claim)
      read_state "$dir"
      if [ "$STATE" = "done" ] && [ ! -e "$dir/claimed" ] && [ "${PULSES:-0}" -gt 0 ]; then
        reply "200 OK" "{\"pulses\":$PULSES,\"minutes\":$(( PULSES * WIFI_MINUTES_PER_COIN ))}"
      else
        reply "200 OK" '{"pulses":0,"minutes":0}'
      fi ;;
    /confirm)
      read_state "$dir"
      if [ "$STATE" = "done" ] && [ ! -e "$dir/claimed" ] && [ "${PULSES:-0}" -gt 0 ]; then
        : > "$dir/claimed"
        : > "$dir/ackpending"
        for _ in 1 2 3; do call "$sid" ack >/dev/null && { rm -f "$dir/ackpending"; break; }; sleep 1; done
      fi
      reply "200 OK" "$(status_json "$dir")" ;;
    *) reply "404 Not Found" '{"error":"NOT_FOUND"}' ;;
  esac
}

case "$1" in
  serve)
    mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
    find "$STATE_DIR" -mindepth 1 -maxdepth 1 -type d -mmin +120 -exec rm -rf {} + 2>/dev/null   # forget old sessions
    exec socat "TCP-LISTEN:$LISTEN_PORT,bind=127.0.0.1,reuseaddr,fork" "EXEC:$SELF handle" ;;
  handle) do_handle ;;
  worker) valid_sid "$2" && do_worker "$2" "$3" ;;
  *) echo "usage: $0 serve | handle | worker <sid> <seconds>" >&2; exit 2 ;;
esac
