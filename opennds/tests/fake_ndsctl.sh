#!/bin/sh
# Stand-in for openNDS' ndsctl in tests. State lives in $FAKE_NDS_DIR/<mackey> files (shell-sourceable):
#   STATE=Authenticated|Preauthenticated  SESSION_END=<epoch>  DL=<kB>  UL=<kB>  UPRATE  DOWNRATE
# Every command is appended to $FAKE_NDS_DIR/calls.log. Auth only works on a Preauthenticated client, like the real one.
D="${FAKE_NDS_DIR:?}"
key() { printf '%s' "$1" | tr 'A-F' 'a-f' | tr -d ':'; }
load() { STATE=""; SESSION_END=0; DL=0; UL=0; UPRATE=0; DOWNRATE=0; [ -r "$D/$1" ] && . "$D/$1"; }
save() { printf 'STATE=%s\nSESSION_END=%s\nDL=%s\nUL=%s\nUPRATE=%s\nDOWNRATE=%s\n' "$STATE" "$SESSION_END" "$DL" "$UL" "$UPRATE" "$DOWNRATE" > "$D/$1"; }
client_json() {  # client_json <mackey> <mac>
  load "$1"
  _dr="$DOWNRATE"; [ "$_dr" = 0 ] && _dr=null
  printf '  "gatewayname":"Test%%3CSpot","gatewayaddress":"192.168.1.1:2050",\n  "mac":"%s",\n  "session_start":"0",\n  "session_end":"%s",\n  "last_active":"0",\n  "token":"t",\n  "state":"%s",\n  "custom":"none",\n  "download_rate_limit_threshold":"%s",\n  "download_this_session":"%s",\n  "download_session_avg":"0.00",\n  "upload_this_session":"%s",\n  "upload_session_avg":"0.00"\n' "$2" "$SESSION_END" "$STATE" "$_dr" "$DL" "$UL"
}
# the status page asks by client IP: $D/ip_<ip> names the MAC (tests create it)
if [ "$1" = json ] && [ -r "$D/ip_$2" ]; then set -- json "$(cat "$D/ip_$2")"; fi
echo "$*" >> "$D/calls.log"
case "$1" in
  json)
    if [ -n "$2" ]; then
      k=$(key "$2")
      if [ -r "$D/$k" ]; then printf '{\n'; client_json "$k" "$2"; printf '}\n'; else printf '{}\n'; fi
    else
      printf '{"client_list_length":"x","clients":{\n'
      first=1
      for f in "$D"/*; do
        case "$f" in *.log) continue ;; esac
        [ -f "$f" ] || continue
        k=$(basename "$f"); mac=$(printf '%s' "$k" | sed 's/\(..\)\(..\)\(..\)\(..\)\(..\)\(..\)/\1:\2:\3:\4:\5:\6/')
        [ "$first" = 1 ] || printf ',\n'; first=0
        printf '"%s":{\n' "$mac"; client_json "$k" "$mac"; printf '}'
      done
      printf '\n}}\n'
    fi ;;
  auth)
    k=$(key "$2"); load "$k"
    if [ "$STATE" = "Preauthenticated" ]; then
      STATE=Authenticated; SESSION_END=$(( $(date +%s) + $3 * 60 )); UPRATE="$4"; DOWNRATE="$5"
      [ "${FAKE_NDS_KEEP_COUNTERS:-0}" = 1 ] || { DL=0; UL=0; }
      save "$k"; echo "Client $2 authenticated."
    else echo "Failed to authenticate client $2."; fi ;;
  deauth)
    k=$(key "$2"); load "$k"
    if [ -n "$STATE" ]; then STATE=Preauthenticated; save "$k"; echo "Client $2 deauthenticated."; else echo "Client $2 not found."; fi ;;
esac
