#!/bin/sh
# Coin-slot gateway client for routers (POSIX sh: OpenWrt/busybox, needs curl or wget, and openssl).
#
#   gateway_pay.sh <session> [duration_seconds] [--ack]
#
# Arms the box's coin slot, counts coins until the duration passes, always disarms, then prints one
# line on stdout:   PULSES=<n> MINUTES=<n>        (exit 0)      or   ERROR=<code>   (exit 1)
# With --ack the coins are cleared from the box afterwards; otherwise they stay queued until acked.
# Progress goes to stderr. Configure with the environment (or edit the defaults below):
#   GW_BOX=192.168.1.50  GW_KEY=<gateway key set on the box>
BOX="${GW_BOX:-kioskmanager.local}"
KEY="${GW_KEY:?set GW_KEY to the gateway key}"
SESSION="$1"
DURATION="${2:-60}"
ACK=0
[ "$3" = "--ack" ] || [ "$2" = "--ack" ] && ACK=1
[ "$2" = "--ack" ] && DURATION=60

# The session id ends up in a URL and a shell command line: allow only the box's own alphabet.
case "$SESSION" in
  ""|*[!A-Za-z0-9._:-]*) echo "ERROR=INVALID_SESSION"; exit 1 ;;
esac
[ "${#SESSION}" -le 64 ] || { echo "ERROR=INVALID_SESSION"; exit 1; }
case "$DURATION" in ""|*[!0-9]*) DURATION=60 ;; esac

BASE="http://$BOX/api/gateway"
if command -v curl >/dev/null 2>&1; then
  http() { curl -sS -m 8 "$1" 2>/dev/null; }   # no -f: error answers carry a JSON body we want
else
  http() { wget -qO- -T 8 "$1" 2>/dev/null; }
fi

# jget <field>: pull a value out of the flat JSON the box returns.
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p'; }

# call <action> [extra query]: fetch a one-time nonce, sign, send. Prints the JSON answer.
call() {
  action="$1"; extra="$2"
  nonce=$(http "$BASE/challenge" | jget nonce)
  [ -n "$nonce" ] || { echo '{"success":false,"error":"NO_NONCE"}'; return 1; }
  sig=$(printf 'gw1:%s:%s:%s' "$action" "$SESSION" "$nonce" | openssl dgst -sha256 -hmac "$KEY" | awk '{print $NF}')
  http "$BASE/$action?session=$SESSION&nonce=$nonce&sig=$sig$extra"
}

ARMED=0
disarm() {
  [ "$ARMED" = 1 ] || return
  ARMED=0
  for _ in 1 2 3; do  # one lost packet must not leave the acceptor powered
    call release >/dev/null && break
    sleep 1
  done
}
# Disarm on every way out: normal end, Ctrl+C, kill, closed connection.
trap 'disarm; exit 1' INT TERM HUP PIPE
trap 'disarm' EXIT

answer=$(call arm "&duration=$DURATION")
if [ "$(printf '%s' "$answer" | jget success)" != "true" ]; then
  err=$(printf '%s' "$answer" | jget error)
  echo "ERROR=${err:-NO_ANSWER}"
  exit 1
fi
ARMED=1
RATE=$(printf '%s' "$answer" | jget minutes_per_coin)
echo "armed for ${DURATION}s, session $SESSION" >&2

END=$(( $(date +%s) + DURATION ))
LAST=-1
while [ "$(date +%s)" -lt "$END" ]; do
  sleep 1
  st=$(call status) || continue          # a missed poll must not end the session early
  [ "$(printf '%s' "$st" | jget success)" = "true" ] || continue
  P=$(printf '%s' "$st" | jget pulses)
  [ "$P" != "$LAST" ] && { LAST="$P"; echo "  $P coin(s) so far" >&2; }
  [ "$(printf '%s' "$st" | jget state)" = "armed" ] || break   # the box ended the session itself
done

disarm
# Wait (up to ~15 s) for in-flight coins to be counted: state becomes idle.
TOTAL=0
i=0
while [ "$i" -lt 30 ]; do
  st=$(call status) && {
    TOTAL=$(printf '%s' "$st" | jget pulses)
    [ "$(printf '%s' "$st" | jget state)" = "idle" ] && break
  }
  i=$((i + 1)); sleep 0.5 2>/dev/null || sleep 1
done
[ -n "$TOTAL" ] || TOTAL=0

[ "$ACK" = 1 ] && [ "$TOTAL" -gt 0 ] && call ack >/dev/null
echo "PULSES=$TOTAL MINUTES=$(( TOTAL * ${RATE:-0} ))"
