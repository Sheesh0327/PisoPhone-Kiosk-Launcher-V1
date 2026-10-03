#!/bin/sh
# Stand-in for OpenWrt's uci in tests. Options live in $FAKE_UCI_DIR/<config.section.option> (one file each);
# every `set` is appended to $FAKE_UCI_DIR/calls.log. Supports: -q, get, show, set, commit.
D="${FAKE_UCI_DIR:?}"
[ "$1" = "-q" ] && shift
case "$1" in
  get) [ -r "$D/$2" ] || exit 1; cat "$D/$2" ;;
  set) key="${2%%=*}"; val="${2#*=}"; echo "set $2" >> "$D/calls.log"; case "$key" in *.*.*) printf '%s' "$val" > "$D/$key" ;; *) : > "$D/$key" ;; esac ;;
  show) for f in "$D"/$2.*; do [ -f "$f" ] && printf "%s='%s'\n" "${f##*/}" "$(cat "$f")"; done ;;
  commit) echo "commit $2" >> "$D/calls.log" ;;
  *) exit 1 ;;
esac
