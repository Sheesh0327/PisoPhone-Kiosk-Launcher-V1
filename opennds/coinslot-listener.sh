#!/bin/sh
# Coin-slot manager for OpenNDS (POSIX sh: busybox ash on OpenWrt). One file, several modes:
#
#   coinslot-listener.sh serve              local HTTP listener (socat, 127.0.0.1 only)
#   coinslot-listener.sh handle             one HTTP request on stdin/stdout (started by socat)
#   coinslot-listener.sh worker <sid> <p>   hold one customer's coin window (started by the listener)
#   coinslot-listener.sh stream             live-update listener (socat, guest network): coin counts and "slot is free" pushed to the portal
#   coinslot-listener.sh stream-handle      one live-update connection (started by socat)
#   coinslot-listener.sh events             coin events pushed by the box (UDP, EVENT_PORT): written to <window>/live.json
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
#   /verify?sid                       (flash_coin theme) what a finished window collected: pulses, minutes, plan, window id.
#                                     Changes nothing, so it can be asked again after a crash.
#   /ack?sid                          (flash_coin theme) the session file now holds the time: acknowledge the coins on the box
#                                     (retried until it works; asking again is harmless)
#   /me?mac                           account status for the status page
#   (flash=1 on /start: a flash_coin window. Coins are only counted while it is open; when the customer is done (Done, or
#    the idle wait runs out) the total is priced once, recorded on the roll, granted and acknowledged on the box, all
#    without the portal. The device goes online then and not before: a phone that gets internet closes its login page.)
#
# Portal-facing API on the live-update port (STREAM_PORT, guest network; the device is identified by its MAC):
#   /api/start?sid&plan[&forfeit=1]    start a flash_coin window for the device that asks
#   /api/status?sid                    progress, also "online" (granted) and "final" (minutes, code)
#   /api/finish?sid                    close the window now (the customer tapped Done)
#   /stream?sid                        the same progress as Server-Sent Events
#   /pause?mac                        pause a connected Endurance session once (needs PAUSE_MIN_PESOS paid)
CONF="${COINSLOT_CONF:-/etc/coinslot.conf}"
UCI="${UCI:-uci}"
# Settings come from UCI (/etc/config/coinslot, section "main", lower-case option names: gw_box, gw_key, ...), which
# LuCI and `uci` tooling understand. The old /etc/coinslot.conf is still read first, so an unmigrated box keeps
# working; any option set in UCI wins. `coinslot-listener.sh migrate` copies the old file into UCI.
SETTINGS="GW_BOX GW_KEY GW_DISCOVER GW_BOX_MAC DISCOVER_PORT DISCOVER_IFACE DISCOVER_COOLDOWN LISTEN_PORT STATE_DIR DATA_DIR
  COIN_FIRST_WAIT_SECONDS COIN_IDLE_WAIT_SECONDS COIN_MAX_SECONDS COIN_POLL_SECONDS STREAM_PORT STREAM_BIND
  STREAM_MAX_CLIENTS STREAM_MAX_SECONDS EVENT_PORT EVENT_BIND EMPTY_LIMIT EMPTY_WINDOW EMPTY_COOLDOWN HYPER_TIERS HYPER_PRORATA_MIN ENDURANCE_TIERS
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
# STREAM_MAX_SECONDS each.
STREAM_PORT="${STREAM_PORT:-8100}"
STREAM_BIND="${STREAM_BIND:-0.0.0.0}"
STREAM_MAX_CLIENTS="${STREAM_MAX_CLIENTS:-24}"          # up to ~20 guests at once, each with one live stream
STREAM_MAX_SECONDS="${STREAM_MAX_SECONDS:-600}"
# Coin events from the box (UDP): the box tells the router about every coin the moment it is counted, so the router
# does not have to ask it ten times a second. 0 turns them off (the router then asks, as before). The kiosk LAN only:
# the guest firewall zone does not open this port, and every event is signed with the gateway key anyway.
EVENT_PORT="${EVENT_PORT:-8101}"
EVENT_BIND="${EVENT_BIND:-0.0.0.0}"
FLASH_LIB="${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"
# Griefing defense: a device that opens EMPTY_LIMIT coin windows within EMPTY_WINDOW seconds without paying anything is
# refused for EMPTY_COOLDOWN seconds (it would otherwise keep the one coin slot from everybody else).
EMPTY_LIMIT="${EMPTY_LIMIT:-2}"
EMPTY_WINDOW="${EMPTY_WINDOW:-300}"
EMPTY_COOLDOWN="${EMPTY_COOLDOWN:-120}"

# Plans. Tiers are "pesos:minutes". The best combination of tiers is used for any amount, e.g. Endurance
# 17 pesos = 10 + 5 + 1 + 1 = 8 h + 3 h + 30 min. HyperSpeed pesos that fit no tier (1-4) are paid pro rata.
HYPER_TIERS="${HYPER_TIERS:-5:30 10:60 20:120}"
HYPER_PRORATA_MIN="${HYPER_PRORATA_MIN:-6}"
ENDURANCE_TIERS="${ENDURANCE_TIERS:-1:15 5:180 10:480 20:1440}"
ENDURANCE_DOWN_KBPS="${ENDURANCE_DOWN_KBPS:-5000}"   # 5 Mbit/s
ENDURANCE_UP_KBPS="${ENDURANCE_UP_KBPS:-2000}"       # 2 Mbit/s

# Pause: an Endurance session that has paid at least PAUSE_MIN_PESOS can be paused once (kept for PAUSE_MAX_HOURS).
# Bursting (no cap until a client's speed stays above its limit for ~30 s) is openNDS' own feature, see INSTRUCTIONS-SHELL.md.
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
# logmsg <text>: a line in the router log (logread -e coinslot), so a customer who got stuck can be traced afterwards.
logmsg() { logger -t coinslot -- "$*" 2>/dev/null; }
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

sign_msg() {  # sign_msg <message>: hex HMAC-SHA256 with GW_KEY (hmac_init must have run in this shell)
  if [ "$HMAC_MODE" = shell ]; then hmac_hex "$1"
  else printf '%s' "$1" | openssl dgst -sha256 -hmac "$GW_KEY" | awk '{print $NF}'; fi
}

# ack_box <sid>: acknowledge the coins on the box. Succeeds only when the box answered success (call's exit status alone
# does not say that: an error page or a lost reply still exits 0), so a failed ack keeps "ackpending" and is retried.
ack_box() {
  _ar=$(call "$1" ack) && [ "$(printf '%s' "$_ar" | jget success)" = "true" ]
}

call() {
  _sid="$1"; _action="$2"; _extra="$3"
  _nonce=$(http "$(box_base)/challenge" | jget nonce)
  if [ -z "$_nonce" ] && discover_box; then _nonce=$(http "$(box_base)/challenge" | jget nonce); fi
  [ -n "$_nonce" ] || { echo '{"success":false,"error":"NO_NONCE"}'; return 1; }
  BASE=$(box_base)
  [ -n "$HMAC_MODE" ] || hmac_init
  _sig=$(sign_msg "gw1:$_action:$_sid:$_nonce")
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
worker_running() {  # alive, and not a zombie (a finished worker that nobody has reaped yet still answers kill -0)
  _wp=$(cat "$1/pid" 2>/dev/null) && kill -0 "$_wp" 2>/dev/null || return 1
  case "$(sed 's/.*) //' "/proc/$_wp/stat" 2>/dev/null | cut -c1)" in Z | X) return 1 ;; esac
  return 0
}

status_json() {  # status_json <dir>
  read_state "$1"
  _plan=$(cat "$1/plan" 2>/dev/null); _claimed=false; [ -e "$1/claimed" ] && _claimed=true
  _min=0; [ -n "$_plan" ] && [ "${PULSES:-0}" -gt 0 ] && _min=$(minutes_for "$_plan" "$PULSES")
  _on=false; [ -e "$1/online" ] && _on=true
  _fin=false; FINAL_WMIN=0; FINAL_LEFT=0; FINAL_CODE=""; [ -r "$1/final" ] && { . "$1/final"; _fin=true; }
  printf '{"state":"%s","pulses":%s,"minutes":%s,"plan":"%s","remaining":%s,"claimed":%s,"error":"%s","online":%s,"final":%s,"fwmin":%s,"fleft":%s,"code":"%s"}' \
    "$STATE" "${PULSES:-0}" "$_min" "$_plan" "${REMAINING:-0}" "$_claimed" "$ERROR" "$_on" "$_fin" "$FINAL_WMIN" "$FINAL_LEFT" "$FINAL_CODE"
}

uptime_ms() { read -r _u _ < /proc/uptime; _c="${_u#*.}"; _c="${_c#0}"; echo $(( ${_u%.*} * 1000 + ${_c:-0} * 10 )); }

# ---------------------------------------------------------------------------
# flash_coin windows: the whole window is priced once and the device goes online once, when the customer is done paying (flash_coin_lib.sh holds the roll)
# ---------------------------------------------------------------------------
flash_load() {
  [ -n "$FLASH_LOADED" ] && return 0
  [ -r "$FLASH_LIB" ] || return 1
  . "$FLASH_LIB"; FLASH_LOADED=1
}
flash_args() {  # sets F_MAC F_PLAN F_WID F_FORFEIT for the window in $dir
  F_MAC=$(cat "$dir/mac" 2>/dev/null); F_PLAN=$(cat "$dir/plan" 2>/dev/null); F_WID=$(cat "$dir/wid" 2>/dev/null)
  F_FORFEIT=0; [ -e "$dir/fforfeit" ] && F_FORFEIT=1
  [ -n "$F_MAC" ] && valid_plan "$F_PLAN"
}

# note_open: the first coin of a window leaves a small record on flash, kept until the box is acknowledged: if the router
# restarts mid-window, "recover" credits the coins. Nothing is granted yet: the device goes online only when the customer
# is done paying (settle_window), because a phone that gets internet access closes its login page, which would cut off
# a customer who wants to add more coins.
note_open() {
  [ -e "$dir/noted" ] && return 0
  flash_load && flash_args || return 0
  mkdir -p "$DATA_DIR/open" 2>/dev/null && printf '%s %s %s %s\n' "$F_MAC" "$F_PLAN" "$F_WID" "$F_FORFEIT" > "$DATA_DIR/open/$sid" && : > "$dir/noted"
  logmsg "timing ${sid%????????????????????????} first coin pulses=$1 at=$(uptime_ms)"
}

# reconcile: the box's own lifetime coin count against the router's revenue ledger. The box also counts coins that went
# to rental phones, so it should be at or above the ledger; a ledger above the box means pesos were credited that the box
# never counted. Prints one line (RECONCILE OK|MISMATCH|NOBOX|BADLEDGER ...); exit status 0 only for OK.
do_reconcile() {
  flash_load || { echo "RECONCILE NOLIB"; return 2; }
  hmac_init
  _v=$(flash_verify) || { echo "RECONCILE BADLEDGER line ${_v#BAD }"; return 3; }
  set -- $_v; _lp="$3"
  _st=$(call "ffffffffffffffffffffffffffffffff" status)
  _bp=$(printf '%s' "$_st" | jget lifetime_pulses)
  case "$_bp" in "" | *[!0-9]*) echo "RECONCILE NOBOX (needs firmware 3.2.1 or later) ledger=$_lp"; return 4 ;; esac
  if [ "$_lp" -gt "$_bp" ]; then echo "RECONCILE MISMATCH ledger=$_lp box=$_bp (ledger is higher than the box counted)"; return 1; fi
  echo "RECONCILE OK ledger=$_lp box=$_bp (the difference of $((_bp - _lp)) went to rental phones)"
}

ack_window() {  # the coins are recorded: remove them from the box (retried; /ack and /start retry it again if needed)
  : > "$dir/claimed"; rm -f "$dir/pending" "$dir/forfeit"; : > "$dir/ackpending"
  for _ in 1 2 3; do ack_box "$sid" && { rm -f "$dir/ackpending" "$DATA_DIR/open/$sid"; return 0; }; sleep 1; done
  return 1
}

# recover: windows that were open when the router stopped (records in $DATA_DIR/open). The box still holds their coins
# until acknowledged: credit them on the roll (the same window id never counts twice), then acknowledge.
do_recover() {
  flash_load || return 0
  hmac_init
  for _f in "$DATA_DIR"/open/*; do
    [ -f "$_f" ] || continue
    sid="${_f##*/}"; valid_sid "$sid" || { rm -f "$_f"; continue; }
    dir="$STATE_DIR/$sid"
    worker_running "$dir" && continue                                    # a live window settles itself
    read -r _m _p _w _fo < "$_f"
    _try=0; _st=""
    while [ "$_try" -lt 6 ]; do
      _st=$(call "$sid" status) && [ "$(printf '%s' "$_st" | jget success)" = true ] && [ "$(printf '%s' "$_st" | jget state)" != armed ] && break
      _st=""; _try=$((_try + 1)); sleep 5
    done
    if [ -z "$_st" ]; then                                               # box silent: try again at the next start, give up after a day
      [ -n "$(find "$_f" -mmin +1440 2>/dev/null)" ] && { logmsg "recover: dropped $sid (box never answered for a day)"; rm -f "$_f"; }
      continue
    fi
    _pu=$(printf '%s' "$_st" | jget pulses)
    case "$_pu" in "" | *[!0-9]*) _pu=0 ;; esac
    if [ "$_pu" -gt 0 ] && valid_plan "$_p"; then
      _min=$(minutes_for "$_p" "$_pu")
      if flash_mint "$_m" "$_w" "$_p" "$_pu" "$_min" "$(plan_up "$_p")" "$(plan_down "$_p")" "${_fo:-0}" 1; then
        logmsg "recover: window ${sid%????????????????????????} credited ($_pu coins, $_min min) after a restart"
        ack_box "$sid" && rm -f "$_f"
      fi
    else
      ack_box "$sid"; rm -f "$_f"                                        # nothing was left on the box
    fi
  done
}

# settle_window <pulses>: the window closed. Price the whole window once (best combination of tiers for the total),
# record it on the roll, grant, then acknowledge the box.
settle_window() {
  flash_load && flash_args || return 0
  _min=$(minutes_for "$F_PLAN" "$1")
  flash_mint "$F_MAC" "$F_WID" "$F_PLAN" "$1" "$_min" "$(plan_up "$F_PLAN")" "$(plan_down "$F_PLAN")" "$F_FORFEIT" 1
  _rc=$?
  [ "$_rc" = 0 ] || { logmsg "window ${sid%????????????????????????} not recorded (rc=$_rc): left for the portal"; return 0; }
  flash_session "$F_MAC" || return 0
  _st=$(nds_state "$F_MAC")
  NDSOUT=""
  if [ "$_st" = Authenticated ]; then                                    # a top-up: online already, extend the session
    nds_regrant "$F_MAC" "$S_MIN" "$S_UP" "$S_DOWN" "$S_QUP" "$S_QDOWN"
  else nds_do auth "$F_MAC" "$S_MIN" "$S_UP" "$S_DOWN" "$S_QUP" "$S_QDOWN"; fi
  if [ "$(nds_state "$F_MAC")" != Authenticated ]; then                  # what openNDS really did, not what it answered
    # The time is safely on the roll, but the device is not online: do not tell the customer so, and keep the coins on
    # the box (no ack). The portal's Connect (verify -> roll -> grant -> ack) or the next visit (reconnect by MAC) finishes it.
    logmsg "window ${sid%????????????????????????} settled on the roll but openNDS refused the grant: $NDSOUT"
    return 0
  fi
  printf 'FINAL_WMIN=%s\nFINAL_LEFT=%s\nFINAL_CODE=%s\n' "$_min" "$S_MIN" "$S_CODE" > "$dir/final.tmp" && mv "$dir/final.tmp" "$dir/final"
  : > "$dir/online"
  logmsg "timing ${sid%????????????????????????} settled pulses=$1 min=$_min left=$S_MIN at=$(uptime_ms)"
  ack_window
}

# ---------------------------------------------------------------------------
# Coin events from the box (GatewayEvent.h): one signed UDP line per change, written to <window>/live.json (and
# live.env for the worker). Only the running total matters, so a repeated or lost line does no harm.
# ---------------------------------------------------------------------------
event_line() {
  _l="$1"
  case "$_l" in gw1ev:*) ;; *) return 0 ;; esac
  _l="${_l%$(printf '\r')}"; _esig="${_l##*:}"; _body="${_l%:*}"
  _r="${_body#gw1ev:}"                                    # <session>:<wid>:<seq>:<type>:<pulses>; fixed fields from the right
  _epulses="${_r##*:}"; _r="${_r%:*}"
  _etype="${_r##*:}"; _r="${_r%:*}"
  _eseq="${_r##*:}"; _r="${_r%:*}"
  _ewid="${_r##*:}"; _esid="${_r%:*}"
  valid_sid "$_esid" || return 0
  case "$_ewid" in "" | *[!0-9a-f]*) return 0 ;; esac
  case "$_eseq" in "" | *[!0-9]*) return 0 ;; esac
  case "$_epulses" in "" | *[!0-9]*) return 0 ;; esac
  case "$_etype" in ready | coin | end) ;; *) return 0 ;; esac
  case "$_esig" in "" | *[!0-9a-f]*) return 0 ;; esac
  _ed="$STATE_DIR/$_esid"
  [ -d "$_ed" ] || return 0
  [ "$(cat "$_ed/wid" 2>/dev/null)" = "$_ewid" ] || return 0              # not the window that is open now
  [ "$(sign_msg "$_body")" = "$_esig" ] || { logmsg "coin event with a bad signature ignored"; return 0; }
  LIVE_SEQ=0; LIVE_PULSES=0; LIVE_TYPE=""
  [ -r "$_ed/live.env" ] && . "$_ed/live.env"
  [ "$_eseq" -gt "${LIVE_SEQ:-0}" ] || return 0                           # a repeat (the box sends every line twice)
  [ "$_epulses" -ge "${LIVE_PULSES:-0}" ] || _epulses="$LIVE_PULSES"
  _at=$(uptime_ms)
  printf 'LIVE_SEQ=%s\nLIVE_TYPE=%s\nLIVE_PULSES=%s\nLIVE_AT=%s\n' "$_eseq" "$_etype" "$_epulses" "$_at" > "$_ed/live.env.tmp" &&
    mv "$_ed/live.env.tmp" "$_ed/live.env"
  printf '{"seq":%s,"type":"%s","pulses":%s,"at":%s}\n' "$_eseq" "$_etype" "$_epulses" "$_at" > "$_ed/live.json.tmp" &&
    mv "$_ed/live.json.tmp" "$_ed/live.json"
  logmsg "timing ${_esid%????????????????????????} event $_etype pulses=$_epulses at=$_at"
}
do_events() {
  case "$EVENT_PORT" in "" | 0) echo "coin events are off (EVENT_PORT=0)"; exec sleep 2147483647 ;; esac
  mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
  # socat stays the service's main process: stopping the service stops the reader with it (it reads to end of input).
  exec socat -u "UDP4-RECV:$EVENT_PORT,bind=$EVENT_BIND,reuseaddr" "EXEC:$SELF event-reader"
}
event_reader() { hmac_init; while read -r line; do event_line "$line"; done; }

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
  trap 'release; write_state "$dir" "done" "${pulses:-0}" 0 ""; exit 0' INT TERM HUP
  pulses=0
  # Coin events: ask the box to push this window's coins (it answers "events":true if it can). The router then only
  # checks with a signed status call once a second, as a safety net; without events it asks every COIN_POLL_SECONDS.
  wid=$(cat "$dir/wid" 2>/dev/null); evx=""
  case "$EVENT_PORT" in "" | 0) ;; *) [ -n "$wid" ] && evx="&wid=$wid&evport=$EVENT_PORT" ;; esac
  tps=1; [ "$NAP_FRAC" = 1 ] && tps=$(awk -v p="$COIN_POLL_SECONDS" 'BEGIN { t = int(1 / p + 0.5); if (t < 1) t = 1; print t }')

  started=$(now)
  cap=$(( started + COIN_MAX_SECONDS ))
  deadline=$(( started + COIN_FIRST_WAIT_SECONDS ))
  answer=$(call "$sid" arm "&duration=$(( COIN_FIRST_WAIT_SECONDS + 3 ))$evx")
  if [ "$(printf '%s' "$answer" | jget success)" != "true" ]; then
    err=$(printf '%s' "$answer" | jget error)
    write_state "$dir" error 0 0 "${err:-NO_ANSWER}"
    logmsg "window ${sid%????????????????????????} could not arm: ${err:-NO_ANSWER}"
    return 1
  fi
  armed=1
  events=0; [ -n "$evx" ] && [ "$(printf '%s' "$answer" | jget events)" = true ] && events=1
  pull_every=1; [ "$events" = 1 ] && pull_every="$tps"
  logmsg "timing ${sid%????????????????????????} armed events=$events at=$(uptime_ms)"
  # The box ignores coin pulses while the acceptor settles after power-on (ready_in_ms): the customer is invited to
  # insert coins, and the countdown starts, only after that.
  settle=$(printf '%s' "$answer" | jget ready_in_ms)
  case "$settle" in "" | *[!0-9]*) settle=0 ;; esac
  if [ "$settle" -gt 5000 ]; then                         # not a settling time this software knows: do not wait on it
    release
    write_state "$dir" error 0 0 "BAD_SETTLE_TIME"
    return 1
  fi
  if [ "$settle" -gt 0 ]; then
    nap "$(( settle / 1000 )).$(printf '%03d' $(( settle % 1000 )))"
    deadline=$(( $(now) + COIN_FIRST_WAIT_SECONDS ))
    cap=$(( $(now) + COIN_MAX_SECONDS ))
    call "$sid" arm "&duration=$(( COIN_FIRST_WAIT_SECONDS + 3 ))$evx" > /dev/null    # the box's own timer starts from here too
  fi
  pulses=$(printf '%s' "$answer" | jget pulses); pulses="${pulses:-0}"
  last="$pulses"; shown=""; tick=0; boxstate=armed
  write_state "$dir" armed "$pulses" "$(( deadline - $(now) ))" ""
  [ "$pulses" -gt 0 ] && [ -e "$dir/flash" ] && note_open "$pulses"

  while [ "$(now)" -lt "$deadline" ] && [ ! -e "$dir/stop" ]; do
    nap "$COIN_POLL_SECONDS"; tick=$((tick + 1))
    new="$last"
    if [ "$events" = 1 ] && [ -r "$dir/live.env" ]; then                  # pushed by the box: no network call needed
      . "$dir/live.env"
      [ "${LIVE_PULSES:-0}" -gt "$new" ] && new="$LIVE_PULSES"
      [ "$LIVE_TYPE" = end ] && boxstate=idle
    fi
    if [ $(( tick % pull_every )) = 0 ]; then                             # the signed check (every tick without events)
      if st=$(call "$sid" status) && [ "$(printf '%s' "$st" | jget success)" = "true" ]; then
        sp=$(printf '%s' "$st" | jget pulses); [ "${sp:-0}" -gt "$new" ] && new="$sp"
        boxstate=$(printf '%s' "$st" | jget state)
      fi                                                                  # a missed poll must not end the window early
    fi
    pulses="$new"
    if [ "$pulses" -gt "$last" ]; then                    # a coin: show it, grant, then restart the short wait (within the cap)
      last="$pulses"
      deadline=$(( $(now) + COIN_IDLE_WAIT_SECONDS ))
      [ "$deadline" -gt "$cap" ] && deadline="$cap"
      write_state "$dir" armed "$pulses" "$(( deadline - $(now) ))" ""; shown="$pulses/$(( deadline - $(now) ))"
      logmsg "timing ${sid%????????????????????????} coin pulses=$pulses at=$(uptime_ms)"
      [ -e "$dir/flash" ] && note_open "$pulses"
      call "$sid" arm "&duration=$(( deadline - $(now) + 3 ))$evx" >/dev/null
    fi
    rem=$(( deadline - $(now) ))
    [ "$pulses/$rem" = "$shown" ] || { write_state "$dir" armed "$pulses" "$rem" ""; shown="$pulses/$rem"; }   # only on change
    [ "$boxstate" = "armed" ] || break   # the box ended it
  done

  release
  drain_end=$(( $(now) + 30 ))   # in-flight coins: the box reports "idle" (or pushes "end") once it has drained
  tick=0
  while [ "$(now)" -lt "$drain_end" ]; do
    if [ "$events" = 1 ] && [ -r "$dir/live.env" ]; then
      . "$dir/live.env"
      [ "${LIVE_PULSES:-0}" -gt "$pulses" ] && pulses="$LIVE_PULSES"
      [ "$LIVE_TYPE" = end ] && break
    fi
    if [ $(( tick % pull_every )) = 0 ]; then
      st=$(call "$sid" status) && {
        sp=$(printf '%s' "$st" | jget pulses); [ "${sp:-0}" -gt "$pulses" ] && pulses="$sp"
        [ "$(printf '%s' "$st" | jget state)" = "idle" ] && break
      }
    fi
    nap "$COIN_POLL_SECONDS"; tick=$((tick + 1))
  done
  [ "${pulses:-0}" -gt 0 ] && [ -e "$dir/flash" ] && settle_window "$pulses"
  _wm=$(cat "$dir/mac" 2>/dev/null)
  if [ -n "$_wm" ]; then if [ "${pulses:-0}" -gt 0 ]; then clear_empty "$_wm"; else note_empty "$_wm"; fi; fi
  write_state "$dir" "done" "${pulses:-0}" 0 ""
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
    for _ in 1 2 3; do ack_box "$1" && { rm -f "$_dir/ackpending"; break; }; sleep 1; done
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
reply_cors() {  # reply_cors <status line> <json>: for the portal page, which runs on openNDS' own port
  printf 'HTTP/1.1 %s\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: %s\r\n\r\n%s' \
    "$1" "${#2}" "$2"
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
      reply "200 OK" "{\"first\":$COIN_FIRST_WAIT_SECONDS,\"idle\":$COIN_IDLE_WAIT_SECONDS,\"max\":$COIN_MAX_SECONDS,\"fair_gb\":$FAIR_USE_GB,\"e_down\":$ENDURANCE_DOWN_KBPS,\"e_up\":$ENDURANCE_UP_KBPS,\"pause_pesos\":$PAUSE_MIN_PESOS,\"pause_hours\":$PAUSE_MAX_HOURS,\"stream_port\":$STREAM_PORT,\"fair_kb\":${FAIR_USE_KB:-$(( FAIR_USE_GB * 1024 * 1024 ))},\"fair_down\":$FAIR_THROTTLE_DOWN_KBPS,\"fair_up\":$FAIR_THROTTLE_UP_KBPS,\"fair_throttle_min\":$FAIR_THROTTLE_MINUTES,\"fair_full_min\":$FAIR_FULL_MINUTES}"
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
      # flash_coin: time left on the other plan (on the roll) is given up only with the customer's agreement (forfeit=1).
      if [ "$(qget flash)" = 1 ] && [ -n "$mac" ] && [ "$(qget forfeit)" != 1 ] && flash_load && flash_peek "$mac" &&
        { [ "$P_STATE" = running ] || [ "$P_STATE" = paused ]; } && [ "$R_PLAN" != "$plan" ]; then
        reply "200 OK" "{\"state\":\"error\",\"error\":\"PLAN_MISMATCH\",\"plan\":\"$R_PLAN\",\"remaining\":$R_LEFT}"; return
      fi
      if [ -e "$dir/ackpending" ]; then          # an earlier grant could not be acknowledged on the box
        if ack_box "$sid" && rm -f "$dir/ackpending"; then :; else
          reply "200 OK" '{"state":"error","error":"ACK_PENDING"}'; return
        fi
      fi
      if [ -n "$mac" ]; then
        _cd=$(cooldown_left "$mac")
        if [ "$_cd" -gt 0 ]; then
          logmsg "start refused for $mac: cooldown ${_cd}s after empty windows"
          reply "200 OK" "{\"state\":\"error\",\"error\":\"COOLDOWN\",\"retry\":$_cd}"; return
        fi
      fi
      # One client per coin window: while any other window is open, every other request is refused (no waiting line).
      if other_window_open "$sid"; then reply "200 OK" "{\"state\":\"error\",\"error\":\"SLOT_BUSY\",\"retry\":$BUSY_RETRY}"; return; fi
      rm -f "$dir/stop" "$dir/claimed" "$dir/state" "$dir/grant" "$dir/pending" "$dir/forfeit" "$dir/flash" "$dir/fforfeit" \
        "$dir/online" "$dir/egrant" "$dir/final" "$dir/live.env" "$dir/live.json"
      if [ "$(qget flash)" = 1 ]; then : > "$dir/flash"; [ "$(qget forfeit)" = 1 ] && : > "$dir/fforfeit"; fi
      [ -n "$forfeitcode" ] && printf '%s' "$forfeitcode" > "$dir/forfeit"
      printf '%s' "$plan" > "$dir/plan"
      _wid=$(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null); [ -n "$_wid" ] || _wid="$(now)$$"   # names this window: a coin is never credited twice
      printf '%s' "$_wid" > "$dir/wid"
      write_state "$dir" starting 0 "$COIN_FIRST_WAIT_SECONDS" ""
      # The worker must not inherit the socket (it would hold the connection open): detach its fds.
      logmsg "start ${sid%????????????????????????} plan=$plan"
      ( "$SELF" worker "$sid" </dev/null >/dev/null 2>&1 & )
      reply "200 OK" "$(status_json "$dir")" ;;
    /status)
      reply "200 OK" "$(status_json "$dir")" ;;
    /finish)
      worker_running "$dir" && : > "$dir/stop"
      logmsg "finish ${sid%????????????????????????}"
      reply "200 OK" "$(status_json "$dir")" ;;
    /verify)
      read_state "$dir"
      _plan=$(cat "$dir/plan" 2>/dev/null); _wid=$(cat "$dir/wid" 2>/dev/null); _claimed=false; [ -e "$dir/claimed" ] && _claimed=true
      if [ "$STATE" = "done" ] && [ "${PULSES:-0}" -gt 0 ] && valid_plan "$_plan"; then
        reply "200 OK" "{\"ok\":true,\"pulses\":$PULSES,\"minutes\":$(minutes_for "$_plan" "$PULSES"),\"plan\":\"$_plan\",\"wid\":\"$_wid\",\"claimed\":$_claimed,\"up\":$(plan_up "$_plan"),\"down\":$(plan_down "$_plan")}"
      else
        reply "200 OK" "{\"ok\":false,\"state\":\"$STATE\",\"pulses\":${PULSES:-0}}"
      fi ;;
    /ack)
      read_state "$dir"
      [ "$STATE" = "done" ] || { reply "200 OK" "$(err_json NOT_DONE)"; return; }
      if [ -e "$dir/claimed" ] && [ ! -e "$dir/ackpending" ]; then reply "200 OK" '{"success":true,"acked":true}'; return; fi
      : > "$dir/claimed"; rm -f "$dir/pending" "$dir/forfeit"
      _acked=true
      if [ "${PULSES:-0}" -gt 0 ]; then
        : > "$dir/ackpending"; _acked=false
        for _ in 1 2 3; do ack_box "$sid" && { rm -f "$dir/ackpending"; _acked=true; break; }; sleep 1; done
      fi
      logmsg "ack ${sid%????????????????????????} acked=$_acked"
      reply "200 OK" "{\"success\":true,\"acked\":$_acked}" ;;
    /claim)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      if build_grant "$sid" "$mac"; then save_pending "$dir"; logmsg "claim ${sid%????????????????????????} $mac ${G_TOTAL_MIN}min mode=$G_MODE"; reply "200 OK" "$(grant_json)"
      else logmsg "claim ${sid%????????????????????????} $mac: nothing to grant"; reply "200 OK" '{"kind":"","pulses":0,"minutes":0,"added":0,"plan":"","mode":"auth","voucher":"","up":0,"down":0}'; fi ;;
    /confirm | /apply)
      [ -n "$mac" ] || { reply "400 Bad Request" "$(err_json INVALID_MAC)"; return; }
      mkdir -p "$dir"
      mkdir "$dir/lock" 2>/dev/null || { reply "409 Conflict" "$(err_json BUSY)"; return; }
      trap 'rmdir "$dir/lock" 2>/dev/null' EXIT
      if ! load_pending "$dir" && ! build_grant "$sid" "$mac"; then logmsg "confirm ${sid%????????????????????????} $mac: nothing to grant"; reply "200 OK" "$(err_json NOTHING_TO_GRANT)"; return; fi
      if [ "$path" = "/apply" ]; then
        [ "$G_MODE" = "topup" ] || { reply "200 OK" "$(err_json NOT_CONNECTED)"; return; }
        nds_regrant "$mac" "$G_TOTAL_MIN" "$G_UP" "$G_DOWN" || { logmsg "top-up ${sid%????????????????????????} $mac: openNDS refused the re-grant"; reply "200 OK" "$(err_json REGRANT_FAILED)"; return; }
        [ "$G_PLAN" = "hyper" ] && { fair_init "$(mac_key "$mac")"; . "$(fair_file "$(mac_key "$mac")")"; [ "$G_FORFEIT" = 1 ] && USED_KB=0; fair_save "$(mac_key "$mac")" "$USED_KB" "$(nds_counters_kb "$mac")" normal 0; }
      fi
      finalize "$sid" "$mac"
      logmsg "granted ${sid%????????????????????????} $mac ${G_TOTAL_MIN}min plan=$G_PLAN mode=$G_MODE"
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
# Live updates for the portal page (Server-Sent Events). Read-only: it reports this customer's own coin window, or
# their place in the waiting line; it never arms, grants or changes anything.
# ---------------------------------------------------------------------------
sse() { printf 'event: %s\ndata: %s\n\n' "$1" "$2"; }
# Empty windows per device: $STATE_DIR/empty/<mackey> holds the end time of each one; a paid window clears them.
note_empty() {  # note_empty <mac>
  mkdir -p "$STATE_DIR/empty"; _ef="$STATE_DIR/empty/$(mac_key "$1")"
  now >> "$_ef"
  _recent=$(awk -v t=$(( $(now) - EMPTY_WINDOW )) '$1 > t' "$_ef" | wc -l)
  [ "$_recent" -ge "$EMPTY_LIMIT" ] && logmsg "alert grief: $1 opened $_recent empty coin windows in ${EMPTY_WINDOW}s"
  return 0
}
clear_empty() { rm -f "$STATE_DIR/empty/$(mac_key "$1")"; }
# cooldown_left <mac>: seconds this device must still wait (0 = may start)
cooldown_left() {
  _ef="$STATE_DIR/empty/$(mac_key "$1")"; [ -r "$_ef" ] || { echo 0; return; }
  _n=$(now)
  awk -v t=$(( _n - EMPTY_WINDOW )) -v lim="$EMPTY_LIMIT" -v cd="$EMPTY_COOLDOWN" -v n="$_n" '
    $1 > t { c++; last = $1 } END { left = (c >= lim) ? last + cd - n : 0; print (left > 0 ? left : 0) }' "$_ef"
}

# other_window_open <sid>: a coin window of another customer is open on this router (its worker is alive). The box only
# reports the slot as taken once that worker has armed it, so the router must not rely on the box's answer alone.
# Sets BUSY_RETRY: about how many seconds until it is done.
other_window_open() {
  BUSY_RETRY=10
  for _d in "$STATE_DIR"/*/; do
    [ -r "$_d/state" ] || continue
    case "$_d" in */"$1"/) continue ;; esac
    _wst=$(sed -n 's/^STATE=//p' "$_d/state")
    case "$_wst" in
      armed) worker_running "$_d" || continue ;;
      starting)
        worker_running "$_d" || { [ $(( $(now) - $(date -r "$_d/state" +%s 2>/dev/null || echo 0) )) -lt 10 ] || continue; } ;;
      *) continue ;;
    esac
    _rem=$(sed -n 's/^REMAINING=//p' "$_d/state"); case "$_rem" in "" | *[!0-9]*) _rem=10 ;; esac
    BUSY_RETRY=$(( _rem + 3 )); return 0
  done
  return 1
}
peer_mac() {  # peer_mac <ip>: MAC the router has for that guest address
  case "$1" in "" | *[!0-9.]*) return ;; esac
  awk -v ip="$1" '$1 == ip { print tolower($4) }' "${ARP_FILE:-/proc/net/arp}"
}

stream_wait() {
  _ticks=$(( STREAM_MAX_SECONDS * TPS )); _t=0; _quiet=0; _prev=""
  while [ "$_t" -lt "$_ticks" ]; do
    read_state "$dir"
    _o=0; [ -e "$dir/online" ] && _o=1; _f=0; [ -e "$dir/final" ] && _f=1
    _cur="$STATE|$PULSES|$REMAINING|$ERROR|$_o|$_f"
    if [ "$_cur" != "$_prev" ]; then
      _prev="$_cur"; _quiet=0
      sse status "$(status_json "$dir")"
      case "$STATE" in done | error | none) return ;; esac
    elif [ "$_quiet" -ge $(( 5 * TPS )) ]; then printf ': ping\n\n'; _quiet=0
    fi
    nap "$COIN_POLL_SECONDS"; _t=$((_t + 1)); _quiet=$((_quiet + 1))
  done
}

# The portal page's own API (flash_coin): the device is the one the router sees at the connecting address, never a MAC
# the page could make up. Starting goes through the local listener (same checks as the portal's /start).
do_api() {
  sid=$(qget sid)
  valid_sid "$sid" || { reply_cors "400 Bad Request" "$(err_json INVALID_SID)"; return; }
  dir="$STATE_DIR/$sid"; _peer=$(peer_mac "$SOCAT_PEERADDR")
  [ -n "$_peer" ] || { reply_cors "403 Forbidden" "$(err_json FORBIDDEN)"; return; }
  _own=$(cat "$dir/mac" 2>/dev/null)
  if [ -n "$_own" ] || [ "$path" != /api/start ]; then           # only /api/start may claim a window nobody owns yet
    [ "$_own" = "$_peer" ] || { reply_cors "403 Forbidden" "$(err_json FORBIDDEN)"; return; }
  fi
  case "$path" in
    /api/start)
      _pl=$(qget plan); valid_plan "$_pl" || { reply_cors "400 Bad Request" "$(err_json INVALID_PLAN)"; return; }
      _fq=""; [ "$(qget forfeit)" = 1 ] && _fq="&forfeit=1"
      _ans=$(http "http://127.0.0.1:$LISTEN_PORT/start?sid=$sid&plan=$_pl&mac=$_peer&flash=1$_fq")
      reply_cors "200 OK" "${_ans:-$(err_json NO_ANSWER)}" ;;
    /api/status) reply_cors "200 OK" "$(status_json "$dir")" ;;
    /api/finish)
      worker_running "$dir" && : > "$dir/stop"
      reply_cors "200 OK" "$(status_json "$dir")" ;;
  esac
}

do_stream() {
  nap_init
  TPS=1; [ "$NAP_FRAC" = 1 ] && TPS=10
  read -r method target _
  while read -r line; do [ -z "${line%$(printf '\r')}" ] && break; done
  [ "$method" = "GET" ] || { reply "405 Method Not Allowed" "$(err_json METHOD)"; return; }
  path="${target%%\?*}"; QUERY=""
  case "$target" in *\?*) QUERY="${target#*\?}" ;; esac
  case "$path" in /api/start | /api/status | /api/finish) do_api; return ;; esac
  [ "$path" = "/stream" ] || { reply "404 Not Found" "$(err_json NOT_FOUND)"; return; }
  sid=$(qget sid)
  valid_sid "$sid" || { reply "400 Bad Request" "$(err_json INVALID_SID)"; return; }
  dir="$STATE_DIR/$sid"
  # Only the device that started this session (same MAC the router sees for the connecting address) may listen.
  _owner=$(cat "$dir/mac" 2>/dev/null); _peer=$(peer_mac "$SOCAT_PEERADDR")
  [ -n "$_owner" ] && [ "$_owner" = "$_peer" ] || { reply "403 Forbidden" "$(err_json FORBIDDEN)"; return; }
  printf 'HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n'
  printf 'retry: 3000\n\n'
  stream_wait
}

case "$1" in
  serve)
    mkdir -p "$STATE_DIR" "$DATA_DIR/vouchers" && chmod 700 "$STATE_DIR"
    find "$STATE_DIR" -mindepth 1 -maxdepth 1 -type d -name '[0-9a-f]*' -mmin +120 -exec rm -rf {} + 2>/dev/null   # forget old sessions
    [ -z "$(ls "$DATA_DIR/open" 2>/dev/null)" ] || { "$SELF" recover >/dev/null 2>&1 & }
    exec socat "TCP-LISTEN:$LISTEN_PORT,bind=127.0.0.1,reuseaddr,fork" "EXEC:$SELF handle" ;;
  stream)
    mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR"
    exec socat "TCP-LISTEN:$STREAM_PORT,bind=$STREAM_BIND,reuseaddr,fork,max-children=$STREAM_MAX_CLIENTS" "EXEC:$SELF stream-handle" ;;
  stream-handle) do_stream ;;
  events) do_events ;;
  event-reader) event_reader ;;
  event-line) hmac_init; event_line "$2" ;;                                        # for tests: one event line
  handle) do_handle ;;
  worker) valid_sid "$2" && do_worker "$2" ;;
  recover) do_recover ;;
  reconcile) do_reconcile ;;
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
