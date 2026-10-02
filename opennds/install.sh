#!/bin/sh
# One-step installer for the coin-slot integration on an OpenWrt router that already runs openNDS.
#
#   sh install.sh --box 192.168.1.10 [--admin-pass <box admin password>] [--rate 10] [--window 60] [--new-key]
#
# It does everything: installs openNDS if it is missing, installs packages, generates a high-entropy gateway key, stores that key ON THE BOX
# (through its admin login) and in /etc/coinslot.conf, installs the theme and the listener, switches openNDS
# to the theme, enables and starts the services, and checks the router can reach the box through the gateway API.
# Re-running keeps your existing key and settings unless you pass --new-key (or new --box/--rate/--window values).
set -e

BOX=""; ADMIN_USER="admin"; ADMIN_PASS=""; RATE=""; WINDOW=""; NEWKEY=0
while [ $# -gt 0 ]; do
	case "$1" in
		--box) BOX="$2"; shift 2 ;;
		--admin-user) ADMIN_USER="$2"; shift 2 ;;
		--admin-pass) ADMIN_PASS="$2"; shift 2 ;;
		--rate) RATE="$2"; shift 2 ;;
		--window) WINDOW="$2"; shift 2 ;;
		--new-key) NEWKEY=1; shift ;;
		-h | --help) sed -n '2,11p' "$0"; exit 0 ;;
		*) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
	esac
done

# Test hooks (not needed on a router): ROOT prefixes install paths; NO_PKG / NO_UCI / NO_SERVICE skip those steps.
ROOT="${ROOT:-}"
CONF="$ROOT/etc/coinslot.conf"
HERE=$(cd "$(dirname "$0")" && pwd)
die() { echo "ERROR: $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || [ -n "$ROOT" ] || die "run as root"

# Settings already on this router are the defaults for a re-run.
if [ -r "$CONF" ]; then
	# shellcheck disable=SC1090
	. "$CONF"
	[ -n "$BOX" ] || BOX="$GW_BOX"
	[ -n "$RATE" ] || RATE="$WIFI_MINUTES_PER_COIN"
	[ -n "$WINDOW" ] || WINDOW="$COIN_WINDOW_SECONDS"
	[ "$NEWKEY" = 1 ] || KEY="$GW_KEY"
fi
[ -n "$BOX" ] || die "tell me where the box is: --box <ip or hostname>"
RATE="${RATE:-10}"; WINDOW="${WINDOW:-60}"
case "$RATE" in "" | *[!0-9]*) die "--rate must be a whole number of minutes" ;; esac
case "$WINDOW" in "" | *[!0-9]*) die "--window must be a whole number of seconds" ;; esac
[ "$WINDOW" -ge 5 ] && [ "$WINDOW" -le 120 ] || die "--window must be between 5 and 120 seconds"

# --- packages -------------------------------------------------------------------------------------------
if [ -z "$NO_PKG" ]; then
	opkg update >/dev/null || die "opkg update failed (does the router have internet access?)"
	if [ ! -f /usr/lib/opennds/libopennds.sh ]; then
		echo "openNDS is not installed: installing it (with its own dependencies)..."
		opkg install opennds || die "could not install opennds"
	fi
	# openNDS serves its portal through libmicrohttpd. The opennds package normally depends on it, but an
	# openNDS installed by hand may not have it, so make sure one variant is present.
	if ! opkg list-installed 2>/dev/null | grep -q '^libmicrohttpd'; then
		echo "libmicrohttpd (openNDS web server library) is missing: installing it..."
		opkg install libmicrohttpd-no-ssl || opkg install libmicrohttpd || die "could not install libmicrohttpd"
	fi
	echo "Installing packages (socat, openssl-util, curl)..."
	opkg install socat openssl-util curl || die "could not install socat/openssl-util/curl"
fi
[ -n "$ROOT" ] || [ -f /usr/lib/opennds/libopennds.sh ] || die "openNDS (libopennds.sh) not found: install opennds first"
for tool in sha256sum awk sed grep date; do  # busybox provides these on OpenWrt; the theme and listener use them
	command -v "$tool" >/dev/null 2>&1 || die "required tool missing: $tool"
done
command -v curl >/dev/null 2>&1 || die "curl is required"
command -v openssl >/dev/null 2>&1 || die "openssl is required"

# --- key: 256 random bits (64 hex characters) -----------------------------------------------------------
if [ -z "$KEY" ] || [ "$NEWKEY" = 1 ]; then
	KEY=$(openssl rand -hex 32 2>/dev/null) || KEY=""
	# Fallback if openssl cannot generate it: hex-encode 32 random bytes (od is optional on OpenWrt).
	[ "${#KEY}" -eq 64 ] || { command -v od >/dev/null 2>&1 && KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'); }
	[ "${#KEY}" -eq 64 ] || die "could not generate a key"
	KEY_IS_NEW=1
fi

# --- store the key on the box (it only has to be reachable and the admin login known) ---------------------
if [ -n "$KEY_IS_NEW" ] || [ -n "$ADMIN_PASS" ]; then
	if [ -z "$ADMIN_PASS" ]; then
		printf "Admin password of the box (%s): " "$ADMIN_USER"
		stty -echo 2>/dev/null || true
		read -r ADMIN_PASS
		stty echo 2>/dev/null || true
		echo
	fi
	echo "Setting the gateway key on the box ($BOX)..."
	answer=$(curl -sS -m 15 -u "$ADMIN_USER:$ADMIN_PASS" "http://$BOX/api/gateway/config" --data-urlencode "key=$KEY" 2>&1) ||
		die "could not reach the box at $BOX: $answer"
	if ! printf '%s' "$answer" | grep -Eq '"configured" *: *true'; then
		case "$answer" in
			*401* | *Unauthorized* | *Too\ many*) die "the box refused the admin login (wrong password? five wrong tries lock it for a minute)" ;;
			*) die "unexpected answer from the box: $answer" ;;
		esac
	fi
else
	echo "Keeping the key already on this router (use --new-key to rotate it)."
fi

# --- files ------------------------------------------------------------------------------------------------
mkdir -p "$ROOT/usr/lib/opennds" "$ROOT/usr/bin" "$ROOT/etc/init.d"
install -m 0755 "$HERE/theme_coinslot.sh" "$ROOT/usr/lib/opennds/theme_coinslot.sh"
install -m 0755 "$HERE/coinslot-listener.sh" "$ROOT/usr/bin/coinslot-listener.sh"
install -m 0755 "$HERE/coinslot.init" "$ROOT/etc/init.d/coinslot"

umask 077   # the config holds the key: readable by root only
cat > "$CONF" << EOT
# Written by opennds/install.sh. Re-run the installer to change values (or edit and restart the coinslot service).
GW_BOX=$BOX
GW_KEY=$KEY
WIFI_MINUTES_PER_COIN=$RATE
COIN_WINDOW_SECONDS=$WINDOW
LISTEN_PORT=${LISTEN_PORT:-8099}
STATE_DIR=${STATE_DIR:-/tmp/coinslot}
EOT
chmod 600 "$CONF"
echo "Wrote $CONF"

# --- openNDS: use the theme (login_option_enabled 3 = ThemeSpec) -----------------------------------------
if [ -z "$NO_UCI" ]; then
	uci set opennds.@opennds[0].login_option_enabled='3'
	uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
	uci commit opennds
fi

# --- services ---------------------------------------------------------------------------------------------
if [ -z "$NO_SERVICE" ]; then
	/etc/init.d/coinslot enable
	/etc/init.d/coinslot restart
	/etc/init.d/opennds restart
	sleep 2
	# The listener answers locally, and the box answers a challenge only when its key is set.
	curl -sS -m 5 "http://127.0.0.1:${LISTEN_PORT:-8099}/info" | grep -q '"rate"' || die "the coin-slot listener is not answering on 127.0.0.1"
	curl -sS -m 5 "http://$BOX/api/gateway/challenge" | grep -q '"nonce"' || die "the box does not answer the gateway API (is its firmware current, and is the key set?)"
fi

echo
echo "Done. Wi-Fi rate: 1 coin = $RATE minutes, coin window $WINDOW s, box $BOX."
echo "Test with one phone: join the Wi-Fi, open the portal, insert a coin."
echo "Watch it with:  logread -f -e opennds -e coinslot"
