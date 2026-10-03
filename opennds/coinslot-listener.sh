#!/bin/sh
# Coin-slot manager for OpenNDS (POSIX sh: busybox ash on OpenWrt). One file, several modes:
#
#   coinslot-listener.sh serve              local HTTP listener (socat, 127.0.0.1 only)
#   coinslot-listener.sh handle             one HTTP request on stdin/stdout (started by socat)
#   coinslot-listener.sh worker <sid> <p>   hold one customer's coin window (started by the listener)
#   coinslot-listener.sh stream             live-update listener (socat, guest network): coin counts and "slot is free" pushed to the portal
#   coinslot-listener.sh stream-handle      one live-update connection (started by socat)
#   coinslot-listener.sh fairuse            fair-use watcher loop (HyperSpeed throttle after FAIR_USE_GB)
#   coinslot-listener.sh box                which box this router uses and whether it answers (finds it if it moved)
#   coinslot-listener.sh report [days]      revenue per day and plan
#   coinslot-listener.sh minutes <plan> <pesos>   what an amount buys (handy for checking your rates)
#
# What it does for the portal theme (theme_coinslot.sh):
#   * coin window: arms the box's coin slot, counts coins, extends the wait after every coin;
#   * rates: turns pesos into minutes for the HyperSpeed and Endurance plans (best combination of tiers);
#   * grants: decides minutes and speed caps itself (the browser never supplies them), re-grants time for
#     top-ups and for fair-use throttling by de-authenticating and re-authenticating through ndsctl;
#   * vouchers: every paid session gets a short code that restores the remaining time on any device;
#   * bookkeeping: acknowledges coins on the box only after access was granted, logs revenue.
#
# Local API (GET, JSON unless noted). <sid> = 32 hex chars derived from the client's OpenNDS id, <mac> = client MAC.
#   /info                             settings the portal shows
#   /tiers?plan=hyper|endurance       text lines "pesos minutes" (what the portal prints as rates)
#   /start?sid&plan&mac[&forfeit=1]   start a coin window. While another plan has time left it answers PLAN_MISMATCH,
#                                     unless forfeit=1: the customer then gives that time up when (and only if) they pay
#   /status?sid                       progress: state, pesos, minutes, remaining seconds
#   /finish?sid                       stop accepting now and count what arrived
#   /claim?sid&mac                    what can be granted now (coins, voucher or resume), without changing anything
#   /confirm?sid&mac                  access was granted by openNDS: record it, acknowledge the coins, issue the voucher
#   /apply?sid&mac                    top-up for a connected client: re-grant now, then record it
#   /voucher?sid&mac&code             prepare a grant from a voucher code
#   /resume?sid&mac                   prepare a grant for a returning device that still has paid time
#   /me?mac                           account status for the status page
#   /pause?mac                        pause a connected Endurance session once (needs PAUSE_MIN_PESOS paid)
CONF="${COINSLOT_CONF:-/etc/coinslot.conf}"
UCI="${UCI:-uci}"
# Settings come from UCI (/etc/config/coinslot, section "main", lower-case option names: gw_box, gw_key, ...), which
# LuCI and `uci` tooling understand. The old /etc/coinslot.conf is still read first, so an unmigrated box keeps
# working; any option set in UCI wins. `coinslot-listener.sh migrate` copies the old file into UCI.
SETTINGS="GW_BOX GW_KEY GW_DISCOVER GW_BOX_MAC DISCOVER_PORT DISCOVER_IFACE DISCOVER_COOLDOWN LISTEN_PORT STATE_DIR DATA_DIR
  COIN_FIRST_WAIT_SECONDS COIN_IDLE_WAIT_SECONDS COIN_MAX_SECONDS COIN_POLL_SECONDS STREAM_PORT STREAM_BIND
  STREAM_MAX_CLIENTS STREAM_MAX_SECONDS QUEUE_CLAIM_SECONDS HYPER_TIERS HYPER_PRORATA_MIN ENDURANCE_TIERS
  ENDURANCE_DOWN_KBPS ENDURANCE_UP_KBPS PAUSE_MIN_PESOS PAUSE_MAX_HOURS FAIR_USE_GB FAIR_THROTTLE_DOWN_KBPS
  FAIR_THROTTLE_UP_KBPS FAIR_THROTTLE_MINUTES FAIR_FULL_MINUTES"
[ -r "$CONF" ] && . "$CONF"
if command -v "$UCI" >/dev/null 2>&1; then
  # One `uci show` and one awk for all settings (every request starts this script, and forks are slow on a router).
  # Only the known names above are ever read, never arbitrary ones.
  # (A value containing an apostrophe is skipped: set it in the old coinslot.conf instead.)
  _uci=$("$UCI" -q show coinslot.main 2>/dev/null | awk -F"'" 'NF == 3 && /^coinslot\.main\.[a-z_0-9]+=/ {
    k = $1; sub(/^coinslot\.main\./, "", k); sub(/=$/, "", k); print toupper(k) "\t" $2 }')
  _known=" $(echo $SETTINGS) "
  _nl='
'
  _oifs="$IFS"; IFS="$_nl"
  for _line in $_uci; do
    _name="${_line%%	*}"; _val="${_line#*	}"
    case "$_known" in *" $_name "*) [ -n "$_val" ] && export "$_name=$_val" ;; esac
  done
  IFS="$_oifs"
fi

GW_BOX="${GW_BOX:-192.168.1.10}"
LISTEN_PORT="${LISTEN_PORT:-8099}"
STATE_DIR="${STATE_DIR:-/tmp/coinslot}"
DATA_DIR="${DATA_DIR:-/etc/coinslot.d}"
NDSCTL="${NDSCTL:-ndsctl}"

# Coin window: first wait, wait after each coin, and a hard cap (the box itself caps a session at 120 s).
COIN_FIRST_WAIT_SECONDS="${COIN_FIRST_WAIT_SECONDS:-30}"
COIN_IDLE_WAIT_SECONDS="${COIN_IDLE_WAIT_SECONDS:-15}"
COIN_MAX_SECONDS="${COIN_MAX_SECONDS:-115}"
# How often the worker asks the box for new coins while one customer's window is open (only then; an idle router polls
# nothing). Fractions need `sleep` that accepts them (opkg install coreutils-sleep); BusyBox sleep falls back to 1 s.
COIN_POLL_SECONDS="${COIN_POLL_SECONDS:-0.1}"

# Live updates (Server-Sent Events) for the portal page, on their own port so the guest network can reach only this.
# Each connection can see only its own session, from the device that started it. Bounded: STREAM_MAX_CLIENTS at once,
# STREAM_MAX_SECONDS each. A customer waiting for a busy slot gets QUEUE_CLAIM_SECONDS to tap Start once it is free.
STREAM_PORT="${STREAM_PORT:-8100}"
STREAM_BIND="${STREAM_BIND:-0.0.0.0}"
STREAM_MAX_CLIENTS="${STREAM_MAX_CLIENTS:-8}"
STREAM_MAX_SECONDS="${STREAM_MAX_SECONDS:-600}"
QUEUE_CLAIM_SECONDS="${QUEUE_CLAIM_SECONDS:-30}"

# Plans. Tiers are "pesos:minutes". The best combination of tiers is used for any amount, e.g. Endurance
# 17 pesos = 10 + 5 + 1 + 1 = 8 h + 3 h + 30 min. HyperSpeed pesos that fit no tier (1-4) are paid pro rata.
HYPER_TIERS="${HYPER_TIERS:-5:30 10:60 20:120}"
HYPER_PRORATA_MIN="${HYPER_PRORATA_MIN:-6}"
ENDURANCE_TIERS="${ENDURANCE_TIERS:-1:15 5:180 10:480 20:1440}"
ENDURANCE_DOWN_KBPS="${ENDURANCE_DOWN_KBPS:-5000}"   # 5 Mbit/s
ENDURANCE_UP_KBPS="${ENDURANCE_UP_KBPS:-2000}"       # 2 Mbit/s

# Pause: an Endurance session that has paid at least PAUSE_MIN_PESOS can be paused once (kept for PAUSE_MAX_HOURS).
# Bursting (no cap until a client's speed stays above its limit for ~30 s) is openNDS' own feature, see INSTRUCTIONS.md.
PAUSE_MIN_PESOS="${PAUSE_MIN_PESOS:-10}"
PAUSE_MAX_HOURS="${PAUSE_MAX_HOURS:-72}"

# Fair use (HyperSpeed only): after FAIR_USE_GB of traffic the connection is slowed for FAIR_THROTTLE_MINUTES,
# then released for FAIR_FULL_MINUTES, and so on until the session ends.
FAIR_USE_GB="${FAIR_USE_GB:-5}"
FAIR_THROTTLE_DOWN_KBPS="${FAIR_THROTTLE_DOWN_KBPS:-2000}"
FAIR_THROTTLE_UP_KBPS="${FAIR_THROTTLE_UP_KBPS:-1000}"
FAIR_THROTTLE_MINUTES="${FAIR_THROTTLE_MINUTES:-5}"
FAIR_FULL_MINUTES="${FAIR_FULL_MINUTES:-2}"

# Finding the box when it moves (layout A: the box gets its address from the modem, not from this router).
# GW_BOX stays the first choice; if the box does not answer, the listener asks for it on the LAN and remembers
# the answer in $STATE_DIR/box_addr. GW_DISCOVER=0 turns this off; GW_BOX_MAC (optional) only accepts that box.
GW_DISCOVER="${GW_DISCOVER:-1}"
GW_BOX_MAC="${GW_BOX_MAC:-}"
DISCOVER_PORT="${DISCOVER_PORT:-8888}"
DISCOVER_IFACE="${DISCOVER_IFACE:-}"      # e.g. wan or eth1; empty = let the routing table choose
DISCOVER_COOLDOWN="${DISCOVER_COOLDOWN:-30}"

SELF="$0"

# nap <seconds>: sleep that may be fractional. nap_init (workers and live streams only) checks once whether this
# `sleep` can: BusyBox sleep cannot, and then everything polls once a second.
NAP_FRAC=0
nap_init() {
  case "$COIN_POLL_SECONDS" in "" | *[!0-9.]*) COIN_POLL_SECONDS=1 ;; esac
  if sleep 0.01 2>/dev/null; then NAP_FRAC=1; else COIN_POLL_SECONDS=1; fi
}
nap() { if [ "$NAP_FRAC" = 1 ]; then sleep "$1"; else sleep 1; fi; }

# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------
now() { date +%s; }
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p'; }

# http <url>: GET a plain http:// URL and print the body (error answers carry a JSON body too). socat (needed anyway)
# starts in milliseconds; curl and wget take about half a second just to start on the router (TLS library), which made
# every box call and every portal page slow.
if command -v socat >/dev/null 2>&1; then
  http() {
    _h="${1#http://}"; _p="/${_h#*/}"; _h="${_h%%/*}"
    case "$_h" in *:*) ;; *) _h="$_h:80" ;; esac
    printf 'GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "$_p" "${_h%%:*}" |
      socat -t8 -T8 - "TCP:$_h,shut-none" 2>/dev/null | tr -d '\r' | sed '1,/^$/d'
  }
elif command -v curl >/dev/null 2>&1; then
  http() { curl -sS -m 8 "$1" 2>/dev/null; }          # no -f: error answers carry a JSON body
else
  http() { wget -qO- -T 8 "$1" 2>/dev/null; }
fi

valid_sid() { case "$1" in "" | *[!0-9a-f]*) return 1 ;; esac; [ "${#1}" -eq 32 ]; }
valid_plan() { case "$1" in hyper | endurance) return 0 ;; esac; return 1; }
valid_mac() { case "$1" in "" | *[!0-9a-fA-F:]*) return 1 ;; esac; [ "${#1}" -eq 17 ]; }
norm_mac() { printf '%s' "$1" | tr 'A-F' 'a-f'; }                 # aa:bb:cc:dd:ee:ff (what ndsctl uses)
mac_key() { printf '%s' "$1" | tr 'A-F' 'a-f' | tr -d ':'; }      # aabbccddeeff (file names)
valid_code() { case "$1" in "" | *[!A-HJ-NP-Z2-9]*) return 1 ;; esac; [ "${#1}" -eq 8 ]; }

# ---------------------------------------------------------------------------
# Rates
# ---------------------------------------------------------------------------
tiers_for() {
  case "$1" in
    hyper) printf '%s' "$HYPER_TIERS"; [ -n "$HYPER_PRORATA_MIN" ] && printf ' 1:%s' "$HYPER_PRORATA_MIN" ;;
    endurance) printf '%s' "$ENDURANCE_TIERS" ;;
  esac
}

# minutes_for <plan> <pesos>: most minutes obtainable for that many pesos (unbounded knapsack over the tiers).
minutes_for() {
  awk -v n="$2" -v tiers="$(tiers_for "$1")" 'BEGIN {
    nt = split(tiers, t, " ")
    for (i = 1; i <= nt; i++) { split(t[i], p, ":"); c[i] = p[1] + 0; m[i] = p[2] + 0 }
    b[0] = 0
    for (x = 1; x <= n; x++) {
      b[x] = 0
      for (i = 1; i <= nt; i++) if (c[i] > 0 && c[i] <= x && b[x - c[i]] + m[i] > b[x]) b[x] = b[x - c[i]] + m[i]
    }
    print b[n] + 0
  }'
}

plan_up() { case "$1" in endurance) echo "$ENDURANCE_UP_KBPS" ;; *) echo 0 ;; esac; }
plan_down() { case "$1" in endurance) echo "$ENDURANCE_DOWN_KBPS" ;; *) echo 0 ;; esac; }

# ---------------------------------------------------------------------------
# The box (gateway API, see docs/api/gateway-coinslot.md)
# ---------------------------------------------------------------------------
# call <sid> <action> [extra query]: fetch a one-time nonce, sign, send; prints the box's JSON.
box_addr() { cat "$STATE_DIR/box_addr" 2>/dev/null || printf '%s' "$GW_BOX"; }
box_base() { printf 'http://%s/api/gateway' "$(box_addr)"; }

# discover_box: broadcast the box's own discovery probe, accept one valid answer, remember where it came from.
# DISCOVER_CMD is only for tests: it prints what a box would answer.
discover_box() {
  [ "$GW_DISCOVER" = 1 ] || return 1
  mkdir -p "$STATE_DIR"
  _last=$(cat "$STATE_DIR/box_probe" 2>/dev/null || echo 0)
  [ $(( $(now) - _last )) -ge "$DISCOVER_COOLDOWN" ] || return 1     # at most one probe per cooldown
  now > "$STATE_DIR/box_probe"
  if [ -n "$DISCOVER_CMD" ]; then _reply=$(eval "$DISCOVER_CMD" 2>/dev/null)
  else
    _opt=""; [ -n "$DISCOVER_IFACE" ] && _opt=",so-bindtodevice=$DISCOVER_IFACE"
    _reply=$(printf '{"type":"PISOPHONE_DISCOVER"}' | socat -T3 - "UDP4-DATAGRAM:255.255.255.255:$DISCOVER_PORT,broadcast$_opt" 2>/dev/null)
  fi
  [ "$(printf '%s' "$_reply" | jget type)" = PISOPHONE_ESP32_RESPONSE ] || return 1
  _ip=$(printf '%s' "$_reply" | jget ip)
  _mac=$(printf '%s' "$_reply" | jget mac)
  case "$_ip" in "" | *[!0-9.]*) return 1 ;; esac
  [ "$(printf '%s' "$_ip" | awk -F. 'NF==4 && $1<256 && $2<256 && $3<256 && $4<256 {print "ok"}')" = ok ] || return 1
  if [ -n "$GW_BOX_MAC" ] && [ "$(mac_key "$_mac")" != "$(mac_key "$GW_BOX_MAC")" ]; then return 1; fi
  _port=$(printf '%s' "$_reply" | jget port)
  case "$_port" in "" | 80 | *[!0-9]*) ;; *) _ip="$_ip:$_port" ;; esac
  printf '%s' "$_ip" > "$STATE_DIR/box_addr"
  return 0
}

# HMAC-SHA256 with only the shell's printf and sha256sum: starting openssl takes about 0.6 s on the router, and every
# box poll is signed. hmac_init checks the result against a known value and keeps openssl as the fallback.
HMAC_MODE=""
hmac_pads() {  # hmac_pads <key>: sets IPAD_F and OPAD_F (printf formats of the key block xor 0x36 / 0x5c)
  _k="$1"; _n=0; _kb=""
  if [ "${#_k}" -gt 64 ]; then                           # a long key is hashed first
    _hx=$(printf '%s' "$_k" | sha256sum); _hx="${_hx%% *}"
    while [ -n "$_hx" ]; do _kb="$_kb $(( 0x${_hx%"${_hx#??}"} ))"; _hx="${_hx#??}"; _n=$((_n + 1)); done
  else
    while [ -n "$_k" ]; do _c="${_k%"${_k#?}"}"; _k="${_k#?}"; _kb="$_kb $(printf '%d' "'$_c")"; _n=$((_n + 1)); done
  fi
  IPAD_F=""; OPAD_F=""; _i=0
  set -- $_kb
  while [ "$_i" -lt 64 ]; do
    _b=0; if [ "$_i" -lt "$_n" ]; then _b="$1"; shift; fi
    _x=$(( _b ^ 54 )); IPAD_F="$IPAD_F\\$(( _x >> 6 ))$(( (_x >> 3) & 7 ))$(( _x & 7 ))"
    _x=$(( _b ^ 92 )); OPAD_F="$OPAD_F\\$(( _x >> 6 ))$(( (_x >> 3) & 7 ))$(( _x & 7 ))"
    _i=$((_i + 1))
  done
}
hmac_hex() {  # hmac_hex <message>: hex digest (needs hmac_pads first)
  _in=$( { printf "$IPAD_F"; printf '%s' "$1"; } | sha256sum ); _h="${_in%% *}"; HF=""
  while [ -n "$_h" ]; do _x=$(( 0x${_h%"${_h#??}"} )); HF="$HF\\$(( _x >> 6 ))$(( (_x >> 3) & 7 ))$(( _x & 7 ))"; _h="${_h#??}"; done
  _out=$( { printf "$OPAD_F"; printf "$HF"; } | sha256sum ); printf '%s' "${_out%% *}"
}
hmac_init() {
  HMAC_MODE=openssl
  hmac_pads key
  [ "$(hmac_hex 'The quick brown fox jumps over the lazy dog')" = f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8 ] || return 0
  hmac_pads "$GW_KEY"; HMAC_MODE=shell
}

call() {
  _sid="$1"; _action="$2"; _extra="$3"
  _nonce=$(http "$(box_base)/challenge" | jget nonce)
  if [ -z "$_nonce" ] && discover_box; then _nonce=$(http "$(box_base)/challenge" | jget nonce); fi
  [ -n "$_nonce" ] || { echo '{"success":false,"error":"NO_NONCE"}'; return 1; }
  BASE=$(box_base)
  [ -n "$HMAC_MODE" ] || hmac_init
  _msg="gw1:$_action:$_sid:$_nonce"
  if [ "$HMAC_MODE" = shell ]; then _sig=$(hmac_hex "$_msg")
  else _sig=$(printf '%s' "$_msg" | openssl dgst -sha256 -hmac "$GW_KEY" | awk '{print $NF}'); fi
  http "$BASE/$_action?session=$_sid&nonce=$_nonce&sig=$_sig$_extra"
}

# do_box: which box does this router use, and does it answer? (also the operator's quick check)
do_box() {
  if [ -n "$(http "$(box_base)/challenge" | jget nonce)" ] || { discover_box && [ -n "$(http "$(box_base)/challenge" | jget nonce)" ]; }; then
    echo "box $(box_addr) answers"
  else
    echo "box $(box_addr) does not answer"; return 1
  fi
}

# do_migrate: copy every setting of the old /etc/coinslot.conf into UCI, then keep the old file as .migrated.
do_migrate() {
  [ -r "$CONF" ] || { echo "no $CONF: nothing to migrate"; return 0; }
  command -v "$UCI" >/dev/null 2>&1 || { echo "uci not found" >&2; return 1; }
  [ -e /etc/config/coinslot ] || [ -n "$COINSLOT_UCI_TEST" ] || : > /etc/config/coinslot
  "$UCI" -q get coinslot.main >/dev/null 2>&1 || "$UCI" set coinslot.main=coinslot
  for _name in $SETTINGS; do
    _v=$( ( . "$CONF"; eval "printf '%s' \"\${$_name}\"" ) )
    [ -n "$_v" ] && "$UCI" set "coinslot.main.$(printf '%s' "$_name" | tr 'A-Z' 'a-z')=$_v"
  done
  "$UCI" commit coinslot && mv "$CONF" "$CONF.migrated" && echo "settings moved to UCI; old file kept as $CONF.migrated"
}

# ---------------------------------------------------------------------------
# openNDS (ndsctl)
# ---------------------------------------------------------------------------
nds_field() { sed -n 's/.*"'"$1"'":"\([^"]*\)".*/\1/p' | head -n 1; }
nds_json() { "$NDSCTL" json "$1" 2>/dev/null; }
nds_state() { nds_json "$1" | nds_field state; }                       # Authenticated | Preauthenticated | (empty)
nds_session_end() { nds_json "$1" | nds_field session_end; }
nds_counters_kb() {                                                     # download+upload this session, in kB
  _j=$(nds_json "$1")
  _d=$(printf '%s' "$_j" | nds_field download_this_session); _u=$(printf '%s' "$_j" | nds_field upload_this_session)
  echo $(( ${_d:-0} + ${_u:-0} ))
}
# nds_auth <mac> <minutes> <up kbps> <down kbps>: only works for a de-authenticated (pre-authenticated) client.
nds_auth() {
  _out=$("$NDSCTL" auth "$1" "$2" "$3" "$4" 0 0 2>&1)
  case "$_out" in *Failed*) return 1 ;; *authenticated*) return 0 ;; esac
  return 1
}
nds_regrant() { "$NDSCTL" deauth "$1" >/dev/null 2>&1; nds_auth "$@"; }

# ---------------------------------------------------------------------------
# Vouchers: the durable record of paid time ($DATA_DIR/vouchers/<CODE>: PLAN EXPIRES MAC CREATED PESOS)
# ---------------------------------------------------------------------------
VOUCHER_DIR() { echo "$DATA_DIR/vouchers"; }

new_code() {
  while :; do
    _c=$(head -c 256 /dev/urandom | tr -dc 'A-HJ-NP-Z2-9' | cut -c1-8)
    [ "${#_c}" -eq 8 ] && [ ! -e "$(VOUCHER_DIR)/$_c" ] && { echo "$_c"; return; }
  done
}

# load_voucher <code>: sets PLAN EXPIRES MAC CREATED PESOS PAUSED PAUSE_LEFT PAUSE_UNTIL PAUSE_USED
# (defaults when unknown, return 1).
load_voucher() {
  PLAN=""; EXPIRES=0; MAC=""; CREATED=0; PESOS=0; PAUSED=0; PAUSE_LEFT=0; PAUSE_UNTIL=0; PAUSE_USED=0
  valid_code "$1" && [ -r "$(VOUCHER_DIR)/$1" ] || return 1
  . "$(VOUCHER_DIR)/$1"
}

save_voucher() {  # save_voucher <code>: writes the voucher variables set by load_voucher
  mkdir -p "$(VOUCHER_DIR)"
  printf 'PLAN=%s\nEXPIRES=%s\nMAC=%s\nCREATED=%s\nPESOS=%s\nPAUSED=%s\nPAUSE_LEFT=%s\nPAUSE_UNTIL=%s\nPAUSE_USED=%s\n' \
    "$PLAN" "$EXPIRES" "$MAC" "$CREATED" "$PESOS" "$PAUSED" "$PAUSE_LEFT" "$PAUSE_UNTIL" "$PAUSE_USED" > "$(VOUCHER_DIR)/$1.tmp" &&
    mv "$(VOUCHER_DIR)/$1.tmp" "$(VOUCHER_DIR)/$1"
}

# voucher_live (after load_voucher): sets V_LEFT (seconds of paid time left) and returns 0 if there is any. A paused
# voucher keeps its time frozen until PAUSE_UNTIL.
voucher_live() {
  _n=$(now); V_LEFT=0
  if [ "$PAUSED" = 1 ] && [ "$PAUSE_UNTIL" -gt "$_n" ]; then V_LEFT="$PAUSE_LEFT"; return 0; fi
  [ "$PAUSED" = 1 ] && return 1
  [ "$EXPIRES" -gt "$_n" ] && { V_LEFT=$(( EXPIRES - _n )); return 0; }
  return 1
}

# voucher_by_mac <mackey>: code of the voucher with time left (running or paused) bound to that device, if any.
voucher_by_mac() {
  for _f in "$(VOUCHER_DIR)"/*; do
    [ -f "$_f" ] || continue
    case "$_f" in *.tmp) continue ;; esac
    if grep -q "^MAC=$1\$" "$_f"; then
      load_voucher "$(basename "$_f")" && voucher_live && { basename "$_f"; return 0; }
    fi
  done
  return 1
}

can_pause() {  # after load_voucher: eligible for the one-time pause?
  [ "$PLAN" = "endurance" ] && [ "$PESOS" -ge "$PAUSE_MIN_PESOS" ] && [ "$PAUSE_USED" != 1 ] && [ "$PAUSED" != 1 ]
}

# ---------------------------------------------------------------------------
# Per-customer state: $STATE_DIR/<sid>/{state,plan,mac,stop,pid,claimed,grant,ackpending}
# ---------------------------------------------------------------------------
write_state() {  # write_state <dir> <state> <pulses> <remaining> <error>   (atomic)
  printf 'STATE=%s\nPULSES=%s\nREMAINING=%s\nERROR=%s\n' "$2" "$3" "$4" "$5" > "$1/state.tmp" && mv "$1/state.tmp" "$1/state"
}
read_state() {  # sets STATE PULSES REMAINING ERROR ("none" if the customer has no session)
  STATE=none; PULSES=0; REMAINING=0; ERROR=""
  [ -r "$1/state" ] && . "$1/state"
}
worker_running() { [ -r "$1/pid" ] && kill -0 "$(cat "$1/pid")" 2>/dev/null; }

status_json() {  # status_json <dir>
  read_state "$1"
  _plan=$(cat "$1/plan" 2>/dev/null); _claimed=false; [ -e "$1/claimed" ] && _claimed=true
  _min=0; [ -n "$_plan" ] && [ "${PULSES:-0}" -gt 0 ] && _min=$(minutes_for "$_plan" "$PULSES")
  printf '{"state":"%s","pulses":%s,"minutes":%s,"plan":"%s","remaining":%s,"claimed":%s,"error":"%s"}' \
    "$STATE" "${PULSES:-0}" "$_min" "$_plan" "${REMAINING:-0}" "$_claimed" "$ERROR"
}

# ---------------------------------------------------------------------------
# Worker: arm, count (waiting longer after every coin), always disarm
# ---------------------------------------------------------------------------
do_worker() {
  sid="$1"; dir="$STATE_DIR/$sid"
  nap_init
  echo $$ > "$dir/pid"
  armed=0
  release() {
    [ "$armed" = 1 ] || return 0
    armed=0
    for _ in 1 2 3; do call "$sid" release >/dev/null && break; sleep 1; done  # one lost packet must not leave the acceptor powered
  }
  trap 'release; write_state "$dir" done "${pulses:-0}" 0 ""; exit 0' INT TERM HUP
  pulses=0

  started=$(now)
  cap=$(( started + COIN_MAX_SECONDS ))
  deadline=$(( started + COIN_FIRST_WAIT_SECONDS ))
  answer=$(call "$sid" arm "&duration=$(( COIN_FIRST_WAIT_SECONDS + 3 ))")
  if [ "$(printf '%s' "$answer" | jget success)" != "true" ]; then
    err=$(printf '%s' "$answer" | jget error)
    write_state "$dir" error 0 0 "${err:-NO_ANSWER}"
    return 1
  fi
  armed=1
  pulses=$(printf '%s' "$answer" | jget pulses); pulses="${pulses:-0}"
  last="$pulses"; shown=""
  write_state "$dir" armed "$pulses" "$(( deadline - $(now) ))" ""

  while [ "$(now)" -lt "$deadline" ] && [ ! -e "$dir/stop" ]; do
    nap "$COIN_POLL_SECONDS"
    st=$(call "$sid" status) || continue                  # a missed poll must not end the window early
    [ "$(printf '%s' "$st" | jget success)" = "true" ] || continue
    pulses=$(printf '%s' "$st" | jget pulses)
    if [ "$pulses" -gt "$last" ]; then                    # a coin: restart the short wait, within the hard cap
      last="$pulses"
      deadline=$(( $(now) + COIN_IDLE_WAIT_SECONDS ))
      [ "$deadline" -gt "$cap" ] && deadline="$cap"
      call "$sid" arm "&duration=$(( deadline - $(now) + 3 ))" >/dev/null
    fi
    rem=$(( deadline - $(now) ))
    [ "$pulses/$rem" = "$shown" ] || { write_state "$dir" armed "$pulses" "$rem" ""; shown="$pulses/$rem"; }   # only on change
    [ "$(printf '%s' "$st" | jget state)" = "armed" ] || break   # the box ended it
  done

  release
  drain_end=$(( $(now) + 30 ))   # in-flight coins: the box reports "idle" once it has drained
  while [ "$(now)" -lt "$drain_end" ]; do
    st=$(call "$sid" status) && {
      pulses=$(printf '%s' "$st" | jget pulses)
      [ "$(printf '%s' "$st" | jget state)" = "idle" ] && break
    }
    nap "$COIN_POLL_SECONDS"
  done
  write_state "$dir" done "${pulses:-0}" 0 ""
}

# ---------------------------------------------------------------------------
# Grants
# ---------------------------------------------------------------------------
# build_grant <sid> <mac>: works out what can be granted right now. Sets
#   G_KIND coins|voucher|resume   G_PLAN   G_PULSES   G_NEW_MIN   G_LEFT_MIN (time kept from before)
#   G_TOTAL_MIN   G_MODE auth|topup   G_CODE (existing voucher of this device)   G_UP   G_DOWN
# Returns 1 when there is nothing to grant.
build_grant() {
  _dir="$STATE_DIR/$1"; _mac="$2"; _mk=$(mac_key "$2")
  G_PAUSED=0; G_FORFEIT=0; G_OLDCODE=""
  G_KIND=""; G_PLAN=""; G_PULSES=0; G_NEW_MIN=0; G_LEFT_MIN=0; G_TOTAL_MIN=0; G_MODE=auth; G_CODE=""; G_OLDMAC=""
  read_state "$_dir"
  if [ "$STATE" = "done" ] && [ "${PULSES:-0}" -gt 0 ] && [ ! -e "$_dir/claimed" ]; then
    G_KIND=coins; G_PLAN=$(cat "$_dir/plan" 2>/dev/null); G_PULSES="$PULSES"
    valid_plan "$G_PLAN" || return 1
    G_NEW_MIN=$(minutes_for "$G_PLAN" "$G_PULSES")
    _code=$(voucher_by_mac "$_mk") && G_CODE="$_code"
    # Switching plan: the customer agreed to give up the time left on the other plan (it is dropped when they pay).
    if [ -r "$_dir/forfeit" ]; then G_FORFEIT=1; G_OLDCODE=$(cat "$_dir/forfeit"); G_CODE=""; fi
    if [ "$(nds_state "$_mac")" = "Authenticated" ]; then                 # connected: this is a top-up
      G_MODE=topup
      _end=$(nds_session_end "$_mac"); _n=$(now)
      case "$_end" in "" | null | *[!0-9]*) _end=0 ;; esac
      [ "$_end" -gt "$_n" ] && [ "$G_FORFEIT" != 1 ] && G_LEFT_MIN=$(( (_end - _n + 59) / 60 ))
    elif [ -n "$G_CODE" ]; then                                           # time left from an earlier session
      load_voucher "$G_CODE"
      voucher_live && [ "$PLAN" = "$G_PLAN" ] && G_LEFT_MIN=$(( (V_LEFT + 59) / 60 ))
    fi
    G_TOTAL_MIN=$(( G_NEW_MIN + G_LEFT_MIN ))
  elif [ -r "$_dir/grant" ] && [ ! -e "$_dir/claimed" ]; then
    . "$_dir/grant"           # KIND PLAN MINUTES CODE OLDMAC PAUSEDFLAG
    G_KIND="$KIND"; G_PLAN="$PLAN"; G_TOTAL_MIN="$MINUTES"; G_CODE="$CODE"; G_OLDMAC="$OLDMAC"; G_PAUSED="${PAUSEDFLAG:-0}"
    valid_plan "$G_PLAN" || return 1
  else
    return 1
  fi
  G_UP=$(plan_up "$G_PLAN"); G_DOWN=$(plan_down "$G_PLAN")
  return 0
}

grant_json() {
  printf '{"kind":"%s","pulses":%s,"minutes":%s,"added":%s,"plan":"%s","mode":"%s","voucher":"%s","up":%s,"down":%s,"paused":%s,"forfeit":%s}' \
    "$G_KIND" "$G_PULSES" "$G_TOTAL_MIN" "$G_NEW_MIN" "$G_PLAN" "$G_MODE" "$G_CODE" "$G_UP" "$G_DOWN" "$G_PAUSED" "$G_FORFEIT"
}

# The grant is worked out when the customer taps Connect (/claim) and remembered, because by the time openNDS
# has authenticated the client and /confirm runs, the client already looks "connected": recomputing then would
# mistake a new session for a top-up and count the fresh time twice.
save_pending() {
  printf 'G_KIND=%s\nG_PLAN=%s\nG_PULSES=%s\nG_NEW_MIN=%s\nG_LEFT_MIN=%s\nG_TOTAL_MIN=%s\nG_MODE=%s\nG_CODE=%s\nG_OLDMAC=%s\nG_UP=%s\nG_DOWN=%s\nG_PAUSED=%s\nG_FORFEIT=%s\nG_OLDCODE=%s\n' \
    "$G_KIND" "$G_PLAN" "$G_PULSES" "$G_NEW_MIN" "$G_LEFT_MIN" "$G_TOTAL_MIN" "$G_MODE" "$G_CODE" "$G_OLDMAC" "$G_UP" "$G_DOWN" "$G_PAUSED" "$G_FORFEIT" "$G_OLDCODE" > "$1/pending.tmp" &&
    mv "$1/pending.tmp" "$1/pending"
}
load_pending() { [ -r "$1/pending" ] && [ ! -e "$1/claimed" ] && . "$1/pending"; }

# finalize <sid> <mac>: record a grant that openNDS has accepted (call build_grant first, under the sid lock).
finalize() {
  _dir="$STATE_DIR/$1"; _mac="$2"; _mk=$(mac_key "$2"); _n=$(now)
  _code="$G_CODE"
  if [ -z "$_code" ] || ! load_voucher "$_code"; then _code=$(new_code); load_voucher "$_code"; CREATED="$_n"; fi
  _expires=$(( _n + G_TOTAL_MIN * 60 ))
  PLAN="$G_PLAN"; MAC="$_mk"; PESOS=$(( PESOS + G_PULSES )); EXPIRES="$_expires"
  PAUSED=0                                         # granting time always ends a pause
  save_voucher "$_code"
  : > "$_dir/claimed"; rm -f "$_dir/pending" "$_dir/forfeit"
  _kind=new; [ "$G_MODE" = topup ] && _kind=topup
  if [ "$G_FORFEIT" = 1 ]; then              # the old plan's time (and its voucher) is gone
    _kind=switch
    [ -n "$G_OLDCODE" ] && rm -f "$(VOUCHER_DIR)/$G_OLDCODE"
    [ "$G_MODE" = topup ] || rm -f "$(fair_file "$_mk")"
  fi
  if [ "$G_KIND" = "coins" ]; then
    mkdir -p "$DATA_DIR"
    echo "$_n,$G_PLAN,$G_PULSES,$G_NEW_MIN,$_kind" >> "$DATA_DIR/revenue.csv"
    : > "$_dir/ackpending"
    for _ in 1 2 3; do call "$1" ack >/dev/null && { rm -f "$_dir/ackpending"; break; }; sleep 1; done
  elif [ -n "$G_OLDMAC" ] && [ "$G_OLDMAC" != "$_mk" ]; then
    _old=$(printf '%s' "$G_OLDMAC" | sed 's/\(..\)\(..\)\(..\)\(..\)\(..\)\(..\)/\1:\2:\3:\4:\5:\6/')
    [ "$(nds_state "$_old")" = "Authenticated" ] && "$NDSCTL" deauth "$_old" >/dev/null 2>&1   # the time moves to this device
  fi
  fair_init "$_mk"
  G_CODE="$_code"
  G_EXPIRES="$_expires"
}

# ---------------------------------------------------------------------------
# Fair use (HyperSpeed): $STATE_DIR/fair/<mackey>: USED_KB OFFSET_KB PHASE PHASE_SINCE
# ---------------------------------------------------------------------------
fair_file() { echo "$STATE_DIR/fair/$1"; }
fair_init() { [ -e "$(fair_file "$1")" ] || { mkdir -p "$STATE_DIR/fair"; printf 'USED_KB=0\nOFFSET_KB=0\nPHASE=normal\nPHASE_SINCE=0\n' > "$(fair_file "$1")"; }; }
fair_save() { printf 'USED_KB=%s\nOFFSET_KB=%s\nPHASE=%s\nPHASE_SINCE=%s\n' "$2" "$3" "$4" "$5" > "$(fair_file "$1").tmp" && mv "$(fair_file "$1").tmp" "$(fair_file "$1")"; }

# fair_flip <mac> <used kb> <phase> <down kbps> <up kbps>: re-grant the rest of the session with new speed caps.
fair_flip() {
  _mac="$1"; _used="$2"; _phase="$3"
  _end=$(nds_session_end "$_mac"); _n=$(now)
  case "$_end" in "" | null | *[!0-9]*) return 1 ;; esac
  _min=$(( (_end - _n + 59) / 60 ))
  [ "$_min" -ge 1 ] || return 1
  nds_regrant "$_mac" "$_min" "$5" "$4" || return 1
  fair_save "$(mac_key "$_mac")" "$_used" "$(nds_counters_kb "$_mac")" "$_phase" "$_n"   # counters restart or not: remember the reading
}

# One pass over all connected clients.
fair_tick() {
  _limit="${FAIR_USE_KB:-$(( FAIR_USE_GB * 1024 * 1024 ))}"   # FAIR_USE_KB is a test hook
  "$NDSCTL" json 2>/dev/null | awk -F'"' '
    /"mac":/ { mac = $4 } /"state":/ { st = $4 } /"download_this_session":/ { dl = $4 }
    /"upload_this_session":/ { print mac, st, dl, $4 }' | while read -r mac st dl ul; do
    [ "$st" = "Authenticated" ] || continue
    mk=$(mac_key "$mac")
    code=$(voucher_by_mac "$mk") || continue
    load_voucher "$code"
    [ "$PLAN" = "hyper" ] || continue
    fair_init "$mk"; . "$(fair_file "$mk")"
    cur=$(( ${dl:-0} + ${ul:-0} ))
    [ "$cur" -ge "$OFFSET_KB" ] || OFFSET_KB=0
    total=$(( USED_KB + cur - OFFSET_KB ))
    n=$(now)
    if [ "$total" -lt "$_limit" ]; then fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"; USED_KB="$total"; continue; fi
    if [ "$PHASE" = "normal" ] && [ $(( n - PHASE_SINCE )) -ge $(( FAIR_FULL_MINUTES * 60 )) ]; then
      fair_flip "$mac" "$total" throttled "$FAIR_THROTTLE_DOWN_KBPS" "$FAIR_THROTTLE_UP_KBPS"
    elif [ "$PHASE" = "throttled" ] && [ $(( n - PHASE_SINCE )) -ge $(( FAIR_THROTTLE_MINUTES * 60 )) ]; then
      fair_flip "$mac" "$total" normal 0 0
    else
      fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"
    fi
  done
}

# purge_vouchers: delete vouchers that ran out more than two days ago (a paused one is kept until its pause ends).
purge_vouchers() {
  _cut=$(( $(now) - 172800 ))
  for _f in "$(VOUCHER_DIR)"/*; do
    [ -f "$_f" ] || continue
    case "$_f" in *.tmp) continue ;; esac
    load_voucher "$(basename "$_f")" || continue
    [ "$EXPIRES" -lt "$_cut" ] && { [ "$PAUSED" != 1 ] || [ "$PAUSE_UNTIL" -lt "$_cut" ]; } && rm -f "$_f"
  done
}

do_fairuse() {
  mkdir -p "$STATE_DIR/fair"
  while :; do
    fair_tick
    purge_vouchers
    sleep 60
  done
}

# ---------------------------------------------------------------------------
# Revenue report
# ---------------------------------------------------------------------------
do_report() {
  _days="${1:-7}"
  [ -r "$DATA_DIR/revenue.csv" ] || { echo "No payments recorded yet."; return 0; }
  _tz=$(date +%z); _sign=1; case "$_tz" in -*) _sign=-1 ;; esac
  _h=${_tz#?}; _hh=${_h%??}; _mm=${_h#??}; _off=$(( _sign * ( ${_hh#0} * 3600 + ${_mm#0} * 60 ) ))
  awk -F, -v off="$_off" -v days="$_days" -v now="$(now)" '
    function civil(z,   era, doe, yoe, y, doy, mp, d, m) {   # days since 1970-01-01 -> y-m-d
      z += 719468; era = int(z / 146097); doe = z - era * 146097
      yoe = int((doe - int(doe/1460) + int(doe/36524) - int(doe/146096)) / 365); y = yoe + era * 400
      doy = doe - (365*yoe + int(yoe/4) - int(yoe/100)); mp = int((5*doy + 2) / 153)
      d = doy - int((153*mp + 2) / 5) + 1; m = mp < 10 ? mp + 3 : mp - 9; if (m <= 2) y++
      return sprintf("%04d-%02d-%02d", y, m, d)
    }
    $1 >= now - days * 86400 { day = civil(int(($1 + off) / 86400)); k = day "," $2; p[k] += $3; n[k]++; tot += $3 }
    END { for (k in p) { split(k, a, ","); printf "%s  %-10s  PHP %-6d  %d payment(s)\n", a[1], a[2], p[k], n[k] | "sort"; }
          close("sort"); printf "Total last %d day(s): PHP %d\n", days, tot }' "$DATA_DIR/revenue.csv"
}

# ---------------------------------------------------------------------------
# HTTP handler (one request per connection)
# ---------------------------------------------------------------------------
reply() {  # reply <status line> <body> [content type]
  printf 'HTTP/1.1 %s\r\nContent-Type: %s\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: %s\r\n\r\n%s' \
    "$1" "${3:-application/json}" "${#2}" "$2"
}
err_json() { printf '{"error":"%s"}' "$1"; }

qget() {  # qget <name>: value of a query parameter (already restricted to safe characters by the callers)
  for _p in $(printf '%s' "$QUERY" | tr '&' ' '); do
    case "$_p" in "$1"=*) printf '%s' "${_p#"$1"=}"; return ;; esac
  done
}

do_handle() {
  read -r method target _
  while read -r line; do [ -z "${line%$(printf '\r')}" ] && break; done     # skip the headers
  [ "$method" = "GET" ] || { reply "405 Method Not Allowed" "$(err_json METHOD)"; return; }

  path="${target%%\?*}"; QUERY=""
  case "$target" in *\?*) QUERY="${target#*\?}" ;; esac

  case "$path" in
    /info)
      reply "200 OK" "{\"first\":$COIN_FIRST_WAIT_SECONDS,\"idle\":$COIN_IDLE_WAIT_SECONDS,\"max\":$COIN_MAX_SECONDS,\"fair_gb\":$FAIR_USE_GB,\"e_down\":$ENDURANCE_DOWN_KBPS,\"e_up\":$ENDURANCE_UP_KBPS,\"pause_pesos\":$PAUSE_MIN_PESOS,\"pause_hours\":$PAUSE_MAX_HOURS,\"stream_port\":$STREAM_PORT}"
      return ;;
    /tiers)
      plan=$(qget plan); valid_plan "$plan" || { reply "400 Bad Request" "$(err_json INVALID_PLAN)"; return; }
      out=""
      for t in $(case "$plan" in hyper) echo "$HYPER_TIERS" ;; endurance) echo "$ENDURANCE_TIERS" ;; esac); do
        out="$out${t%%:*} ${t##*:}
"
      done
      reply "200 OK" "$out" "text/plain"
      return ;;
    /report)
      d=$(qget days); case "$d" in "" | *[!0-9]*) d=7 ;; esac
      reply "200 OK" "$(do_report "$d")" "text/plain"
      return ;;
    /me)
      mac=$(qget mac); valid_mac "$mac" || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      mac=$(norm_mac "$mac"); mk=$(mac_key "$mac")
      state=$(nds_state "$mac"); active=false; remaining=0; plan=""; code=""; throttled=false; usedmb=0; canpause=false
      if code=$(voucher_by_mac "$mk"); then
        load_voucher "$code"; voucher_live; plan="$PLAN"; remaining="$V_LEFT"
        [ "$state" = "Authenticated" ] && can_pause && canpause=true
        if [ -r "$(fair_file "$mk")" ]; then
          . "$(fair_file "$mk")"; [ "$PHASE" = "throttled" ] && throttled=true
          cur=$(nds_counters_kb "$mac"); usedmb=$(( (USED_KB + cur - OFFSET_KB) / 1024 ))
        fi
      else code=""; fi
      [ "$state" = "Authenticated" ] && active=true
      reply "200 OK" "{\"active\":$active,\"plan\":\"$plan\",\"remaining\":$remaining,\"voucher\":\"$code\",\"throttled\":$throttled,\"used_mb\":$usedmb,\"fair_mb\":$(( FAIR_USE_GB * 1024 )),\"can_pause\":$canpause}"
      return ;;
    /pause)
      # One-time pause of a connected Endurance session that paid at least PAUSE_MIN_PESOS: the remaining time is
      # frozen on the voucher (for PAUSE_MAX_HOURS) and the device is disconnected until it taps Resume.
      mac=$(qget mac); valid_mac "$mac" || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      mac=$(norm_mac "$mac"); mk=$(mac_key "$mac"); n=$(now)
      [ "$(nds_state "$mac")" = "Authenticated" ] || { reply "200 OK" "$(err_json NOT_CONNECTED)"; return; }
      code=$(voucher_by_mac "$mk") || { reply "200 OK" "$(err_json NO_SESSION)"; return; }
      load_voucher "$code"
      [ "$PLAN" = "endurance" ] && [ "$PESOS" -ge "$PAUSE_MIN_PESOS" ] || { reply "200 OK" "$(err_json NOT_ELIGIBLE)"; return; }
      [ "$PAUSE_USED" != 1 ] || { reply "200 OK" "$(err_json ALREADY_USED)"; return; }
      end=$(nds_session_end "$mac"); case "$end" in "" | null | *[!0-9]*) end=$EXPIRES ;; esac
      left=$(( end - n )); [ "$left" -gt 0 ] || { reply "200 OK" "$(err_json NO_SESSION)"; return; }
      PAUSED=1; PAUSE_LEFT="$left"; PAUSE_UNTIL=$(( n + PAUSE_MAX_HOURS * 3600 )); PAUSE_USED=1; EXPIRES="$n"
      save_voucher "$code"
      "$NDSCTL" deauth "$mac" >/dev/null 2>&1
      reply "200 OK" "{\"success\":true,\"left\":$left,\"until\":$PAUSE_UNTIL,\"voucher\":\"$code\"}"
      return ;;
  esac

  sid=$(qget sid)
  valid_sid "$sid" || { reply "400 Bad Request" "$(err_json INVALID_SID)"; return; }
  dir="$STATE_DIR/$sid"
  mac=$(qget mac)
  if [ -n "$mac" ]; then valid_mac "$mac" || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }; mac=$(norm_mac "$mac"); fi

  case "$path" in
    /start)
      plan=$(qget plan); valid_plan "$plan" || { reply "400 Bad Request" "$(err_json INVALID_PLAN)"; return; }
      mkdir -p "$dir"
      [ -n "$mac" ] && printf '%s' "$mac" > "$dir/mac"     # the live stream only talks to this device
      if worker_running "$dir"; then reply "200 OK" "$(status_json "$dir")"; return; fi
      read_state "$dir"
      # Coins of a finished window that have not been turned into access yet are never discarded.
      if [ "$STATE" = "done" ] && [ "${PULSES:-0}" -gt 0 ] && [ ! -e "$dir/claimed" ]; then
        reply "200 OK" "$(status_json "$dir")"; return
      fi
      # Time left on another plan must not be mixed with this plan's speed rules.
      # Time left on another plan: refuse, unless the customer chose to switch and give that time up (forfeit=1).
      forfeitcode=""
      if [ -n "$mac" ] && code=$(voucher_by_mac "$(mac_key "$mac")") && load_voucher "$code" && [ "$PLAN" != "$plan" ]; then
        if [ "$(qget forfeit)" = "1" ]; then forfeitcode="$code"
        else
          voucher_live
          reply "200 OK" "{\"state\":\"error\",\"error\":\"PLAN_MISMATCH\",\"plan\":\"$PLAN\",\"remaining\":$V_LEFT}"; return
        fi
      fi
      if [ -e "$dir/ackpending" ]; then          # an earlier grant could not be acknowledged on the box
        if call "$sid" ack >/dev/null && rm -f "$dir/ackpending"; then :; else
          reply "200 OK" '{"state":"error","error":"ACK_PENDING"}'; return
        fi
      fi
      q_gate "$sid" || { reply "200 OK" '{"state":"error","error":"SLOT_BUSY"}'; return; }   # someone is queued ahead
      rm -f "$dir/stop" "$dir/claimed" "$dir/state" "$dir/grant" "$dir/pending" "$dir/forfeit"
      [ -n "$forfeitcode" ] && printf '%s' "$forfeitcode" > "$dir/forfeit"
      printf '%s' "$plan" > "$dir/plan"
      write_state "$dir" starting 0 "$COIN_FIRST_WAIT_SECONDS" ""
      # The worker must not inherit the socket (it would hold the connection open): detach its fds.
      ( "$SELF" worker "$sid" </dev/null >/dev/null 2>&1 & )
      reply "200 OK" "$(status_json "$dir")" ;;
    /status)
      reply "200 OK" "$(status_json "$dir")" ;;
    /finish)
      worker_running "$dir" && : > "$dir/stop"
      reply "200 OK" "$(status_json "$dir")" ;;
    /claim)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      if build_grant "$sid" "$mac"; then save_pending "$dir"; reply "200 OK" "$(grant_json)"
      else reply "200 OK" '{"kind":"","pulses":0,"minutes":0,"added":0,"plan":"","mode":"auth","voucher":"","up":0,"down":0}'; fi ;;
    /confirm | /apply)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      mkdir -p "$dir"
      mkdir "$dir/lock" 2>/dev/null || { reply "409 Conflict" "$(err_json BUSY)"; return; }
      trap 'rmdir "$dir/lock" 2>/dev/null' EXIT
      if ! load_pending "$dir" && ! build_grant "$sid" "$mac"; then reply "200 OK" "$(err_json NOTHING_TO_GRANT)"; return; fi
      if [ "$path" = "/apply" ]; then
        [ "$G_MODE" = "topup" ] || { reply "200 OK" "$(err_json NOT_CONNECTED)"; return; }
        nds_regrant "$mac" "$G_TOTAL_MIN" "$G_UP" "$G_DOWN" || { reply "200 OK" "$(err_json REGRANT_FAILED)"; return; }
        [ "$G_PLAN" = "hyper" ] && { fair_init "$(mac_key "$mac")"; . "$(fair_file "$(mac_key "$mac")")"; [ "$G_FORFEIT" = 1 ] && USED_KB=0; fair_save "$(mac_key "$mac")" "$USED_KB" "$(nds_counters_kb "$mac")" normal 0; }
      fi
      finalize "$sid" "$mac"
      reply "200 OK" "{\"success\":true,\"voucher\":\"$G_CODE\",\"minutes\":$G_TOTAL_MIN,\"plan\":\"$G_PLAN\",\"expires\":$G_EXPIRES,\"mode\":\"$G_MODE\"}" ;;
    /voucher)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      code=$(qget code | tr 'a-z' 'A-Z')
      # Guess protection: after 10 wrong codes in 10 minutes, no more tries for 10 minutes.
      mkdir -p "$STATE_DIR"; fails="$STATE_DIR/voucher-fails"; n=$(now)
      if [ -r "$fails" ]; then
        recent=$(awk -v t=$(( n - 600 )) '$1 > t' "$fails" | wc -l)
        [ "$recent" -ge 10 ] && { reply "200 OK" "$(err_json TOO_MANY_TRIES)"; return; }
      fi
      if ! load_voucher "$code" || ! voucher_live; then
        echo "$n" >> "$fails"
        reply "200 OK" "$(err_json INVALID_CODE)"; return
      fi
      [ "$(nds_state "$mac")" = "Authenticated" ] && { reply "200 OK" "$(err_json ALREADY_CONNECTED)"; return; }
      mkdir -p "$dir"; rm -f "$dir/claimed" "$dir/pending"
      printf 'KIND=voucher\nPLAN=%s\nMINUTES=%s\nCODE=%s\nOLDMAC=%s\nPAUSEDFLAG=%s\n' "$PLAN" "$(( (V_LEFT + 59) / 60 ))" "$code" "$MAC" "$PAUSED" > "$dir/grant"
      build_grant "$sid" "$mac" && reply "200 OK" "$(grant_json)" || reply "200 OK" "$(err_json INVALID_CODE)" ;;
    /resume)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      [ "$(nds_state "$mac")" = "Authenticated" ] && { reply "200 OK" '{"minutes":0}'; return; }
      if code=$(voucher_by_mac "$(mac_key "$mac")"); then
        load_voucher "$code"; voucher_live; mkdir -p "$dir"; rm -f "$dir/claimed" "$dir/pending"
        printf 'KIND=resume\nPLAN=%s\nMINUTES=%s\nCODE=%s\nOLDMAC=\nPAUSEDFLAG=%s\n' "$PLAN" "$(( (V_LEFT + 59) / 60 ))" "$code" "$PAUSED" > "$dir/grant"
        build_grant "$sid" "$mac" && { reply "200 OK" "$(grant_json)"; return; }
      fi
      reply "200 OK" '{"minutes":0}' ;;
    *) reply "404 Not Found" "$(err_json NOT_FOUND)" ;;
  esac
}


# ---------------------------------------------------------------------------
# Waiting line for a busy coin slot: $STATE_DIR/queue/<uptime centiseconds>_<sid>. The customer's open live stream keeps
# its ticket fresh; a ticket nobody refreshed for 6 s is dropped. Only the first in line is told "ready".
# ---------------------------------------------------------------------------
q_seq() {
  read -r _up _ < /proc/uptime 2>/dev/null; _up="${_up%.*}${_up#*.}"
  case "$_up" in "" | *[!0-9]*) _up=$(( $(now) * 100 )) ;; esac
  while case "$_up" in 0?*) true ;; *) false ;; esac; do _up="${_up#0}"; done
  printf '%012d' "$_up"
}
q_prune() {  # q_prune <now>
  for _f in "$STATE_DIR/queue"/*; do
    [ -f "$_f" ] || continue
    read -r _ts _ < "$_f"
    case "$_ts" in "" | *[!0-9]*) continue ;; esac
    [ $(( $1 - _ts )) -gt 6 ] && rm -f "$_f"
  done
}
q_pos() {  # q_pos <sid>: place in line, 0 if absent
  _i=0
  for _f in "$STATE_DIR/queue"/*; do
    [ -f "$_f" ] || continue
    _i=$((_i + 1))
    case "$_f" in *_"$1") echo "$_i"; return ;; esac
  done
  echo 0
}
# q_gate <sid>: may this customer start now? Not while somebody else is first in line; starting leaves the line.
q_gate() {
  [ -d "$STATE_DIR/queue" ] || return 0
  q_prune "$(now)"
  for _f in "$STATE_DIR/queue"/*; do
    [ -f "$_f" ] || continue
    case "$_f" in *_"$1") rm -f "$_f"; return 0 ;; esac
    return 1
  done
  return 0
}

# ---------------------------------------------------------------------------
# Live updates for the portal page (Server-Sent Events). Read-only: it reports this customer's own coin window, or
# their place in the waiting line; it never arms, grants or changes anything.
# ---------------------------------------------------------------------------
sse() { printf 'event: %s\ndata: %s\n\n' "$1" "$2"; }
peer_mac() {  # peer_mac <ip>: MAC the router has for that guest address
  case "$1" in "" | *[!0-9.]*) return ;; esac
  awk -v ip="$1" '$1 == ip { print tolower($4) }' "${ARP_FILE:-/proc/net/arp}"
}

stream_wait() {
  _ticks=$(( STREAM_MAX_SECONDS * TPS )); _t=0; _quiet=0; _prev=""
  while [ "$_t" -lt "$_ticks" ]; do
    read_state "$dir"
    _cur="$STATE|$PULSES|$REMAINING|$ERROR"
    if [ "$_cur" != "$_prev" ]; then
      _prev="$_cur"; _quiet=0
      sse status "$(status_json "$dir")"
      case "$STATE" in done | error | none) return ;; esac
    elif [ "$_quiet" -ge $(( 5 * TPS )) ]; then printf ': ping\n\n'; _quiet=0
    fi
    nap "$COIN_POLL_SECONDS"; _t=$((_t + 1)); _quiet=$((_quiet + 1))
  done
}

stream_queue() {
  mkdir -p "$STATE_DIR/queue"
  _t=""
  for _f in "$STATE_DIR/queue"/*_"$sid"; do [ -f "$_f" ] && _t="$_f"; done
  [ -n "$_t" ] || _t="$STATE_DIR/queue/$(q_seq)_$sid"
  _readyat=0; _last=""; _end=$(( $(now) + STREAM_MAX_SECONDS ))
  : >> "$_t"
  while :; do
    _n=$(now)
    [ "$_n" -lt "$_end" ] || { rm -f "$_t"; return; }
    if [ ! -e "$_t" ]; then
      read_state "$dir"
      case "$STATE" in starting | armed) sse started '{}'; return ;; esac    # taken by /start: the customer is in
      [ "$_readyat" = 0 ] || { sse expired '{}'; return; }
      _t="$STATE_DIR/queue/$(q_seq)_$sid"                                   # dropped by mistake: back in line
    fi
    printf '%s %s\n' "$_n" "$_readyat" > "$_t"
    q_prune "$_n"
    _pos=$(q_pos "$sid")
    if [ "$_readyat" = 0 ]; then
      if [ "$_pos" = 1 ]; then
        _st=$(call "$sid" status)
        case "$(printf '%s' "$_st" | jget slot_free)" in
          true) _readyat="$_n"; sse ready "{\"claim\":$QUEUE_CLAIM_SECONDS}"; _last=ready ;;
          false) ;;
          *) if [ "$(printf '%s' "$_st" | jget success)" = true ]; then       # an older box cannot say: page falls back
               rm -f "$_t"; sse unsupported '{}'; return
             fi ;;
        esac
      fi
      if [ "$_last" != "$_pos" ] && [ "$_last" != ready ]; then _last="$_pos"; sse queue "{\"pos\":$_pos}"; fi
    elif [ $(( _n - _readyat )) -ge "$QUEUE_CLAIM_SECONDS" ]; then
      rm -f "$_t"; sse expired '{}'; return
    fi
    printf ': ping\n\n'                                                     # also notices a closed page
    nap 0.3
  done
}

do_stream() {
  nap_init
  TPS=1; [ "$NAP_FRAC" = 1 ] && TPS=10
  read -r method target _
  while read -r line; do [ -z "${line%$(printf '\r')}" ] && break; done
  [ "$method" = "GET" ] || { reply "405 Method Not Allowed" "$(err_json METHOD)"; return; }
  path="${target%%\?*}"; QUERY=""
  case "$target" in *\?*) QUERY="${target#*\?}" ;; esac
  [ "$path" = "/stream" ] || { reply "404 Not Found" "$(err_json NOT_FOUND)"; return; }
  sid=$(qget sid)
  valid_sid "$sid" || { reply "400 Bad Request" "$(err_json INVALID_SID)"; return; }
  dir="$STATE_DIR/$sid"
  # Only the device that started this session (same MAC the router sees for the connecting address) may listen.
  _owner=$(cat "$dir/mac" 2>/dev/null); _peer=$(peer_mac "$SOCAT_PEERADDR")
  [ -n "$_owner" ] && [ "$_owner" = "$_peer" ] || { reply "403 Forbidden" "$(err_json FORBIDDEN)"; return; }
  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n'
  printf 'retry: 3000\n\n'
  case "$(qget mode)" in
    queue) stream_queue ;;
    *) stream_wait ;;
  esac
}

case "$1" in
  serve)
    mkdir -p "$STATE_DIR" "$DATA_DIR/vouchers" && chmod 700 "$STATE_DIR"
    find "$STATE_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9a-f]*' -mmin +120 -exec rm -rf {} + 2>/dev/null   # forget old sessions
    exec socat "TCP-LISTEN:$LISTEN_PORT,bind=127.0.0.1,reuseaddr,fork" "EXEC:$SELF handle" ;;
  stream)
    mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
    exec socat "TCP-LISTEN:$STREAM_PORT,bind=$STREAM_BIND,reuseaddr,fork,max-children=$STREAM_MAX_CLIENTS" "EXEC:$SELF stream-handle" ;;
  stream-handle) do_stream ;;
  handle) do_handle ;;
  worker) valid_sid "$2" && do_worker "$2" ;;
  fairuse) do_fairuse ;;
  box) do_box ;;
  hmac) hmac_init; echo "$HMAC_MODE"; [ "$HMAC_MODE" = shell ] && hmac_hex "$2"; echo ;;       # for tests: signs with GW_KEY
  migrate) do_migrate ;;
  fairuse-once) fair_tick ;;
  purge) purge_vouchers ;;
  minutes) minutes_for "$2" "$3" ;;
  report) do_report "$2" ;;
  *) echo "usage: $0 serve | stream | handle | worker <sid> | fairuse | report [days] | minutes <plan> <pesos>" >&2; exit 2 ;;
esac
