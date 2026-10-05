#!/bin/sh
# PisoPhone router setup: one file that turns a factory-reset OpenWrt router into the whole PisoWiFi system.
#
#   (first set the router's LAN address to 10.0.0.1 by hand: see setup/README.md, step 1)
#   scp -O piso-setup.sh root@10.0.0.1:/root/
#   ssh root@10.0.0.1
#   sed -i 's/\r$//' piso-setup.sh && chmod +x piso-setup.sh && ./piso-setup.sh
#
# Before you run it: the router's LAN address is 10.0.0.1 (set by hand, so the SSH connection is never cut), modem in the
# router's WAN port (internet is needed once, to download packages), the ESP32 coin box flashed with the current firmware
# (or factory reset) and powered on.
#
# What it builds (the router's LAN address is not touched; your SSH session stays open the whole time):
#   PisoKiosk    Wi-Fi, 2.4 + 5 GHz, WPA2, fixed name, HIDDEN (not broadcast). For the rental phones only. Network 10.0.0.0/24 (the router's LAN).
#   PisoCoinBox  hidden Wi-Fi, 2.4 GHz, for the ESP32 coin box only: after pairing, only the box's MAC address may join.
#                The box gets the fixed address 10.0.0.10.
#   PisoWiFi     open Wi-Fi, 2.4 + 5 GHz, for customers (openNDS login page, coin payments). Rename it with:
#                  piso-setup wifi-name "My Shop"
#                Network 192.168.30.0/24, kept apart from the kiosk network.
# Everything is generated here (Wi-Fi password, box admin password, gateway key) and printed once at the end and saved in
# /root/piso-setup-summary.txt. Running the file again is safe: it keeps what it already made.
#
# Other commands (after setup): piso-setup status | wifi-name "<name>" | pair | summary | test-coin | diag | set-password | reconcile | telegram | rotate-box-wifi | handout | lock-admin | unlock-admin
#
# Options:  --dry-run  print the router settings instead of applying them (needs nothing but the uci command)
#           --yes      do not ask for confirmation
# Settings can be overridden from the environment: COUNTRY (default PH) GUEST_SSID BOX_IP GUEST_IP ROOT_PASSWORD KIOSK_PASSWORD BOX_NEW_ADMIN_PASSWORD

VERSION="dev"

COUNTRY="${COUNTRY:-PH}"
KIOSK_SSID="PisoKiosk"                       # fixed, and hidden: only phones provisioned by the coin box page know it
BOX_SSID="PisoCoinBox"                       # fixed: the ESP32 firmware has it built in
BOX_DEFAULT_WIFI="PisoCoinBox@Setup"         # the ESP32 firmware has it built in; setup gives the paired box its own (rotate_box_wifi)
BOX_DEFAULT_ADMIN="Coinslot@Setup"           # the box's admin password until this script changes it
LAN_IP=$(uci -q get network.lan.ipaddr 2> /dev/null | head -n 1 | cut -d/ -f1)   # whatever the router has now: this script never changes it
LAN_IP="${LAN_IP:-10.0.0.1}"
BOX_IP="${BOX_IP:-${LAN_IP%.*}.10}"                                               # the box is always <LAN network>.10
GUEST_IP="${GUEST_IP:-192.168.30.1}"
STREAM_PORT=8100
CONF="${PISO_CONF:-/etc/piso-setup.conf}"   # what this script chose (secrets inside): mode 600
LOG="${PISO_LOG:-/root/piso-setup.log}"
SUMMARY="${PISO_SUMMARY:-/root/piso-setup-summary.txt}"
STATE="${PISO_STATE:-/tmp/piso-setup.state}"
SELF_PATH="/usr/sbin/piso-setup"
PAIR_WAIT=420                                # seconds to wait for the box to join during pairing

DRY=0; ASSUME_YES=0; PISO_ROOT="${PISO_ROOT:-}"

log() { printf '%s\n' "$*"; [ "$DRY" = 1 ] || printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2> /dev/null; }
die() {
	log "ERROR: $*"; [ "$DRY" = 1 ] || echo "FAILED $*" > "$STATE"
	if [ "$NDS_RESTORE" = 1 ]; then log "starting the customer portal (openNDS) again, as it was before this run"; /etc/init.d/opennds start > /dev/null 2>&1; fi
	exit 1
}
# stop_opennds: stop it for the setup. On a re-run it was already serving the guest network: a failed run starts it again.
NDS_RESTORE=0
stop_opennds() {
	[ -x /etc/init.d/opennds ] || return 0
	if [ "$(uci -q get opennds.@opennds[0].gatewayinterface)" = br-guest ] && pgrep -x opennds > /dev/null 2>&1; then NDS_RESTORE=1; fi
	/etc/init.d/opennds stop > /dev/null 2>&1
	sleep 2
}
step() { log ""; log "== $*"; [ "$DRY" = 1 ] || echo "RUNNING $*" > "$STATE"; }

# ---------------------------------------------------------------------------------------------------------------------
# Stored settings
# ---------------------------------------------------------------------------------------------------------------------
conf_get() { [ -r "$CONF" ] && sed -n "s/^$1='\\(.*\\)'\$/\\1/p" "$CONF" | tail -n 1; }
conf_set() {  # conf_set NAME VALUE
	[ "$DRY" = 1 ] && return 0
	touch "$CONF"; chmod 600 "$CONF"
	grep -v "^$1=" "$CONF" > "$CONF.tmp" 2> /dev/null; printf "%s='%s'\n" "$1" "$2" >> "$CONF.tmp"; mv "$CONF.tmp" "$CONF"; chmod 600 "$CONF"
}
rand() {  # rand <length> [hex]: random characters (letters and digits, no look-alikes; or hex)
	if [ "$2" = hex ]; then head -c 8192 /dev/urandom | tr -dc '0-9a-f' | cut -c1-"$1"     # (only tools every BusyBox has)
	else head -c 512 /dev/urandom | tr -dc 'a-hjkmnp-zA-HJ-NP-Z2-9' | cut -c1-"$1"; fi
}
secret() {  # secret NAME LENGTH [hex]: the stored value, or a new random one that is stored
	_v=$(conf_get "$1")
	if [ -z "$_v" ]; then
		_v=$(rand "$2" "$3")
		[ "${#_v}" -eq "$2" ] || die "could not generate a random value for $1"
		conf_set "$1" "$_v"
	fi
	printf '%s' "$_v"
}

# ---------------------------------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------------------------------
radios() { uci -q show wireless | sed -n "s/^wireless\\.\\([^.=]*\\)=wifi-device\$/\\1/p"; }
radio_band() {  # radio_band radioN -> 2g | 5g
	_b=$(uci -q get "wireless.$1.band")
	case "$_b" in 2g | 5g) echo "$_b"; return ;; esac
	case "$(uci -q get "wireless.$1.hwmode")" in 11g) echo 2g; return ;; 11a) echo 5g; return ;; esac
	_c=$(uci -q get "wireless.$1.channel")
	case "$_c" in "" | auto) echo 2g ;; *) if [ "$_c" -le 14 ] 2> /dev/null; then echo 2g; else echo 5g; fi ;; esac
}
wan_ip() { ifstatus wan 2> /dev/null | jsonfilter -e '@["ipv4-address"][0].address' 2> /dev/null; }

preflight() {
	step "Checking the router"
	if [ "$DRY" != 1 ]; then
		[ "$(id -u)" = 0 ] || die "run this as root"
		[ -f /etc/openwrt_release ] || die "this is not an OpenWrt router"
	fi
	command -v uci > /dev/null || die "uci not found"
	R24=""; R5=""
	for r in $(radios); do
		case "$(radio_band "$r")" in 2g) [ -z "$R24" ] && R24="$r" ;; 5g) [ -z "$R5" ] && R5="$r" ;; esac
	done
	[ -n "$R24" ] || die "no 2.4 GHz Wi-Fi radio found: the coin box can only use 2.4 GHz"
	log "Wi-Fi radios: 2.4 GHz = $R24, 5 GHz = ${R5:-none (the 5 GHz networks are skipped)}"
	[ "$DRY" = 1 ] && return 0
	WAN=$(wan_ip)
	[ -n "$WAN" ] || die "the router has no WAN address: plug the modem into the WAN port and wait a minute, then run this again"
	[ "$LAN_IP" = 10.0.0.1 ] || die "the router's LAN address is $LAN_IP, but this setup expects 10.0.0.1. Change it first (LuCI: Network > Interfaces > LAN, or: uci set network.lan.ipaddr=10.0.0.1 && uci commit network && reboot), then log in again at 10.0.0.1 and re-run. See setup/README.md, step 1."
	case "$WAN" in
		"${LAN_IP%.*}".*) die "the modem's network ($WAN) is in the same range as the router's LAN ($LAN_IP). Change the modem's own LAN address (for example to 192.168.100.1) and run this again" ;;
		192.168.30.*) die "the modem's network ($WAN) uses 192.168.30.x, the same as the guest network. Run with GUEST_IP=192.168.31.1" ;;
	esac
	log "WAN address: $WAN"
	if ! ping -c 1 -W 3 1.1.1.1 > /dev/null 2>&1 && ! ping -c 1 -W 3 8.8.8.8 > /dev/null 2>&1; then die "no internet through the WAN port"; fi
	log "Internet: ok"
}

install_packages() {
	step "Installing packages"
	opkg update > /dev/null 2>&1 || opkg update || die "opkg update failed (no internet or DNS?)"
	for p in opennds socat openssl-util curl ca-bundle coreutils-sleep jsonfilter; do
		if ! opkg list-installed 2> /dev/null | grep -q "^$p "; then
			log "installing $p"
			opkg install "$p" > /dev/null 2>&1 || opkg install "$p" || die "could not install $p"
		fi
		# Installing opennds starts it at once with its default settings, which gate the LAN (the kiosk network) and
		# would reject the coin box and the phones: stop it before anything else can fail. It is started at the end.
		[ "$p" = opennds ] && stop_opennds
	done
	opkg list-installed 2> /dev/null | grep -qi '^libmicrohttpd' || opkg install libmicrohttpd-no-ssl > /dev/null 2>&1
	sleep 0.1 2> /dev/null || log "note: coreutils-sleep is not active; the coin check polls once a second"
}

# ---------------------------------------------------------------------------------------------------------------------
# Router settings (one uci batch, printed by --dry-run)
# ---------------------------------------------------------------------------------------------------------------------
uci_batch() {
	KIOSK_PASS="$1"; GUEST_NAME="$2"; BOX_MAC="$3"
	cat << EOT
# --- LAN = the kiosk network (phones by Wi-Fi, the router's wired ports for administration). Its address is left as it is. ---
set dhcp.lan.start='50'
set dhcp.lan.limit='100'
set dhcp.lan.leasetime='12h'

# --- guest network: customers, behind openNDS ------------------------------------------------------------------------
set network.guest_dev=device
set network.guest_dev.type='bridge'
set network.guest_dev.name='br-guest'
set network.guest=interface
set network.guest.proto='static'
set network.guest.device='br-guest'
set network.guest.ipaddr='$GUEST_IP'
set network.guest.netmask='255.255.255.0'
set dhcp.guest=dhcp
set dhcp.guest.interface='guest'
set dhcp.guest.start='50'
set dhcp.guest.limit='200'
set dhcp.guest.leasetime='2h'

# --- Wi-Fi: replace the router's default networks with ours ----------------------------------------------------------
EOT
	# delete every existing Wi-Fi network (highest index first, so the numbering stays valid)
	_n=$(uci -q show wireless | grep -c '=wifi-iface$')
	while [ "$_n" -gt 0 ]; do _n=$((_n - 1)); echo "delete wireless.@wifi-iface[$_n]"; done
	for r in $R24 $R5; do
		_band=$(radio_band "$r")
		echo "set wireless.$r.disabled='0'"
		echo "set wireless.$r.country='$COUNTRY'"
		[ "$_band" = 2g ] && echo "set wireless.$r.htmode='HT20'"
		cat << EOT
set wireless.kiosk_$r=wifi-iface
set wireless.kiosk_$r.device='$r'
set wireless.kiosk_$r.mode='ap'
set wireless.kiosk_$r.network='lan'
set wireless.kiosk_$r.ssid='$KIOSK_SSID'
set wireless.kiosk_$r.hidden='1'
set wireless.kiosk_$r.encryption='psk2'
set wireless.kiosk_$r.key='$KIOSK_PASS'
set wireless.kiosk_$r.isolate='0'
set wireless.guest_$r=wifi-iface
set wireless.guest_$r.device='$r'
set wireless.guest_$r.mode='ap'
set wireless.guest_$r.network='guest'
set wireless.guest_$r.ssid='$GUEST_NAME'
set wireless.guest_$r.encryption='none'
set wireless.guest_$r.isolate='1'
EOT
	done
	cat << EOT
# The coin box network: hidden, 2.4 GHz only, on the kiosk LAN. Until the box is paired anyone with the (published)
# password could join, so pairing is short; afterwards only the box's MAC address is admitted.
set wireless.box_ap=wifi-iface
set wireless.box_ap.device='$R24'
set wireless.box_ap.mode='ap'
set wireless.box_ap.network='lan'
set wireless.box_ap.ssid='$BOX_SSID'
set wireless.box_ap.hidden='1'
set wireless.box_ap.encryption='psk2'
set wireless.box_ap.key='$(box_wifi_key)'
set wireless.box_ap.isolate='0'
EOT
	if [ -n "$BOX_MAC" ]; then
		cat << EOT
set wireless.box_ap.macfilter='allow'
delete wireless.box_ap.maclist
add_list wireless.box_ap.maclist='$BOX_MAC'
delete dhcp.pisocoinbox
set dhcp.pisocoinbox=host
set dhcp.pisocoinbox.name='pisocoinbox'
set dhcp.pisocoinbox.mac='$BOX_MAC'
set dhcp.pisocoinbox.ip='$BOX_IP'
EOT
	fi
	cat << EOT

# --- firewall: guests reach the internet and nothing else; the kiosk LAN is the router's normal LAN ---------------------
# hardware/software flow offloading can send customers' packets past the traffic shaping (the speed caps of each plan)
set firewall.@defaults[0].flow_offloading='0'
set firewall.@defaults[0].flow_offloading_hw='0'
set firewall.guest=zone
set firewall.guest.name='guest'
set firewall.guest.network='guest'
set firewall.guest.input='REJECT'
set firewall.guest.output='ACCEPT'
set firewall.guest.forward='REJECT'
set firewall.guest_wan=forwarding
set firewall.guest_wan.src='guest'
set firewall.guest_wan.dest='wan'
set firewall.guest_dhcp=rule
set firewall.guest_dhcp.name='Guest-DHCP-DNS'
set firewall.guest_dhcp.src='guest'
set firewall.guest_dhcp.proto='udp'
set firewall.guest_dhcp.dest_port='53 67'
set firewall.guest_dhcp.target='ACCEPT'
set firewall.guest_dns_tcp=rule
set firewall.guest_dns_tcp.name='Guest-DNS-TCP'
set firewall.guest_dns_tcp.src='guest'
set firewall.guest_dns_tcp.proto='tcp'
set firewall.guest_dns_tcp.dest_port='53'
set firewall.guest_dns_tcp.target='ACCEPT'
set firewall.guest_stream=rule
set firewall.guest_stream.name='Guest-Coinslot-Stream'
set firewall.guest_stream.src='guest'
set firewall.guest_stream.proto='tcp'
set firewall.guest_stream.dest_port='$STREAM_PORT'
set firewall.guest_stream.target='ACCEPT'

# --- openNDS gates only the guest network -------------------------------------------------------------------------------
set opennds.@opennds[0].enabled='1'
set opennds.@opennds[0].gatewayinterface='br-guest'
set opennds.@opennds[0].gatewayname='$GUEST_NAME'
set opennds.@opennds[0].login_option_enabled='3'
set opennds.@opennds[0].themespec_path='/usr/lib/opennds/flash_coin.sh'
set opennds.@opennds[0].statuspath='/usr/lib/opennds/flash_coin_status.sh'
delete opennds.@opennds[0].users_to_router
add_list opennds.@opennds[0].users_to_router='allow tcp port $STREAM_PORT'
set opennds.@opennds[0].download_unrestricted_bursting='1'
set opennds.@opennds[0].upload_unrestricted_bursting='1'
set opennds.@opennds[0].checkinterval='15'
set opennds.@opennds[0].ratecheckwindow='2'
EOT
}

apply_batch() {  # apply_batch <kiosk pass> <guest name> <box mac or empty>
	uci_batch "$1" "$2" "$3" > /tmp/piso-setup.uci || die "could not build the settings"
	# 'delete' of something that is not there is not an error for us
	uci batch < /tmp/piso-setup.uci > /dev/null 2>&1 || {
		grep -v '^#' /tmp/piso-setup.uci | grep -v '^$' | while read -r _l; do echo "$_l" | uci batch > /dev/null 2>&1 || case "$_l" in delete*) ;; *) log "uci refused: $_l" ;; esac; done
	}
	uci commit || die "uci commit failed"
}

# ---------------------------------------------------------------------------------------------------------------------
# Files
# ---------------------------------------------------------------------------------------------------------------------
extract_payload() {
	step "Installing the portal files"
	_self="$1"
	sed -n 's/^#@@FILE //p' "$_self" | while read -r _dest _mode; do mkdir -p "$(dirname "$PISO_ROOT$_dest")"; : > "$PISO_ROOT$_dest"; chmod "$_mode" "$PISO_ROOT$_dest"; done
	awk -v root="$PISO_ROOT" '/^#@@FILE /{ if (out != "") close(out); out = root $2; next } out != "" { print > out }' "$_self"
	for f in $(sed -n 's/^#@@FILE \([^ ]*\) .*/\1/p' "$_self"); do
		f="$PISO_ROOT$f"
		[ -s "$f" ] || die "could not write $f"
		sed -i 's/\r$//' "$f"
		log "installed $f"
	done
	cp "$_self" "$SELF_PATH" 2> /dev/null || true
	chmod 755 "$SELF_PATH" 2> /dev/null
	ln -sf "$SELF_PATH" /usr/sbin/pisowifi-name 2> /dev/null
}

write_coinslot_conf() {  # write_coinslot_conf <gateway key> <box mac>
	umask 077
	cat > /etc/coinslot.conf << EOT
GW_BOX=$BOX_IP
GW_KEY=$1
GW_BOX_MAC=$2
DISCOVER_IFACE=br-lan
EOT
	umask 022
	chmod 600 /etc/coinslot.conf
}

# ---------------------------------------------------------------------------------------------------------------------
# Coin box: pairing and first setup
# ---------------------------------------------------------------------------------------------------------------------
box_ifname() { iwinfo 2> /dev/null | awk -v s="$BOX_SSID" '$1 != "" && /ESSID: / { gsub(/"/, "", $0); if ($0 ~ "ESSID: " s "$") print $1 }' | head -n 1; }
box_station() {  # MAC of a station joined to the box network
	_if=$(box_ifname); [ -n "$_if" ] || return 1
	iwinfo "$_if" assoclist 2> /dev/null | awk 'toupper($1) ~ /^[0-9A-F][0-9A-F]:[0-9A-F:]+$/ { print toupper($1); exit }'
}
box_up() { curl -s -m 4 "http://$BOX_IP/api/gateway/challenge" 2> /dev/null | grep -q '{'; }

# pair_box: open the box network, wait for the box to join, then admit only its MAC and give it BOX_IP.
# The box network's current key: its own once rotate_box_wifi has run, else the firmware's built-in default.
box_wifi_key() {
	if [ "$(conf_get BOX_WIFI_ROTATED)" = 1 ] && [ -n "$(conf_get BOX_WIFI_PASS_NEW)" ]; then conf_get BOX_WIFI_PASS_NEW; else printf '%s' "$BOX_DEFAULT_WIFI"; fi
}

pair_box() {
	step "Pairing the coin box (power on the ESP32 now if it is off; waiting up to $((PAIR_WAIT / 60)) minutes)"
	# A new or factory-reset box only knows the built-in password: open the network with it for the pairing.
	conf_set BOX_WIFI_ROTATED 0
	uci set wireless.box_ap.key="$BOX_DEFAULT_WIFI"
	uci -q delete wireless.box_ap.macfilter; uci -q delete wireless.box_ap.maclist; uci commit wireless
	wifi reload > /dev/null 2>&1
	_t=0; MAC=""
	while [ "$_t" -lt "$PAIR_WAIT" ]; do
		MAC=$(box_station)
		[ -n "$MAC" ] && break
		sleep 5; _t=$((_t + 5))
	done
	[ -n "$MAC" ] || { lock_box_network ""; die "the coin box did not join the hidden $BOX_SSID network. Check that it is powered and has the current firmware (or factory reset it: it must try $BOX_SSID), then run: piso-setup pair"; }
	log "the coin box joined: $MAC"
	lock_box_network "$MAC"
	conf_set BOX_MAC "$MAC"
	BOX_MAC="$MAC"
	_t=0
	while [ "$_t" -lt 180 ]; do box_up && break; sleep 5; _t=$((_t + 5)); done   # it rejoins after the reload and takes the fixed address
	box_up || die "the box joined but does not answer at $BOX_IP (it may still hold an old address lease: power-cycle the box and run: piso-setup status)"
	log "the coin box answers at $BOX_IP"
}

lock_box_network() {  # lock_box_network <mac>: admit only this MAC (none at all when empty: the network is closed)
	uci -q delete wireless.box_ap.maclist
	uci set wireless.box_ap.macfilter='allow'
	[ -n "$1" ] && uci add_list wireless.box_ap.maclist="$1"
	if [ -n "$1" ]; then
		uci -q delete dhcp.pisocoinbox
		uci set dhcp.pisocoinbox=host; uci set dhcp.pisocoinbox.name='pisocoinbox'
		uci set dhcp.pisocoinbox.mac="$1"; uci set dhcp.pisocoinbox.ip="$BOX_IP"
	fi
	uci commit wireless; uci commit dhcp
	/etc/init.d/dnsmasq restart > /dev/null 2>&1
	wifi reload > /dev/null 2>&1
}

box_curl() {  # box_curl <admin password> <path> [curl args]: HTTP status and body in BOXOUT / BOXCODE
	_pw="$1"; _path="$2"; shift 2
	BOXOUT=$(curl -s -m 10 -w '\n%{http_code}' -u "admin:$_pw" -X POST "http://$BOX_IP$_path" "$@" 2> /dev/null)
	BOXCODE=$(printf '%s' "$BOXOUT" | tail -n 1)
	BOXOUT=$(printf '%s' "$BOXOUT" | sed '$d')
}

# provision_box <new admin password> <gateway key>: replaces the default admin password and sets the gateway key.
provision_box() {
	step "Setting up the coin box"
	_new="$1"; _key="$2"; _stored=$(conf_get BOX_ADMIN_PASS)
	if [ -n "$_stored" ]; then _cur="$_stored"; else _cur="$BOX_DEFAULT_ADMIN"; fi
	box_curl "$_cur" /save --data-urlencode "admin_pw=$_new"
	case "$BOXCODE" in
		200 | 302 | 303) log "the box now has its own admin password" ;;
		401)
			# not the default and not what we stored: maybe the box was already set up by hand
			if [ -n "$BOX_ADMIN_PASSWORD" ]; then box_curl "$BOX_ADMIN_PASSWORD" /save --data-urlencode "admin_pw=$_new"; fi
			case "$BOXCODE" in 200 | 302 | 303) ;; *) die "the box refused the admin login (it has a password other than the factory default). Factory reset the box (hold GPIO 2 to ground for 5 seconds, or reflash with an erase) and run: piso-setup pair   -- or run this with BOX_ADMIN_PASSWORD=<its password>" ;; esac ;;
		400) log "the box says the new password is weak: $BOXOUT"; die "the generated password was refused" ;;
		*) die "the box did not accept the setup request (HTTP ${BOXCODE:-none}). Is it at $BOX_IP?" ;;
	esac
	conf_set BOX_ADMIN_PASS "$_new"
	sleep 2
	box_curl "$_new" /api/gateway/config --data-urlencode "key=$_key"
	printf '%s' "$BOXOUT" | grep -q '"configured":true' || die "the box did not accept the gateway key (HTTP $BOXCODE: $BOXOUT)"
	log "the gateway key is set on the box"
}

# rotate_box_wifi: the firmware's built-in Wi-Fi password is public, so once the box is paired (and only its MAC is admitted)
# it is given a random one of its own: stored on the box through its admin API, then set on the router's hidden box
# network. The box loses the network for a moment and rejoins with the new password (within about a minute).
rotate_box_wifi() {
	[ "$(conf_get BOX_WIFI_ROTATED)" = 1 ] && return 0
	step "Giving the coin box its own Wi-Fi password"
	_new=$(secret BOX_WIFI_PASS_NEW 20)
	box_curl "$(conf_get BOX_ADMIN_PASS)" /save --data-urlencode "wifi_pass=$_new"
	case "$BOXCODE" in
		200 | 302 | 303) ;;
		*) log "the box did not take its own Wi-Fi password (HTTP ${BOXCODE:-none}); it keeps the built-in one for now (still locked to its MAC). Run again: piso-setup rotate-box-wifi"; return 0 ;;
	esac
	uci set wireless.box_ap.key="$_new"; uci commit wireless
	conf_set BOX_WIFI_ROTATED 1
	wifi reload > /dev/null 2>&1
	_t=0; sleep "${ROTATE_SETTLE:-10}"
	while [ "$_t" -lt "${ROTATE_WAIT:-240}" ]; do box_up && break; sleep 5; _t=$((_t + 5)); done
	box_up || die "the coin box did not come back after its Wi-Fi password changed. Power-cycle it and run: piso-setup status. If it still does not join, factory reset the box and run: piso-setup pair"
	log "the coin box rejoined with its own Wi-Fi password"
}

# ---------------------------------------------------------------------------------------------------------------------
# Stage 2: pairing the box, services, checks
# ---------------------------------------------------------------------------------------------------------------------
write_summary() {
	cat > "$SUMMARY" << EOT
PisoPhone setup summary ($(date '+%F %T'), setup file version $VERSION)
Keep this file private (it is readable by root only).

Router (SSH / LuCI):   root@$LAN_IP        password: $(if [ "$(conf_get ROOT_PASS_SET)" = 1 ]; then conf_get ROOT_PASS; else echo "NOT SET by this setup (the router keeps its previous one): run piso-setup set-password"; fi)
Kiosk Wi-Fi:           $KIOSK_SSID (HIDDEN)   password: $(conf_get KIOSK_PASS)    (rental phones only; type the name and password on each phone's setup page)
PisoWiFi (customers):  $(conf_get GUEST_NAME)   (open; rename with: piso-setup wifi-name "New Name")
Coin box:              http://$BOX_IP      admin password: $(conf_get BOX_ADMIN_PASS)    hidden Wi-Fi: $BOX_SSID (only MAC $(conf_get BOX_MAC))

Useful commands on the router:
  piso-setup status            health check of every part
  piso-setup wifi-name "Name"  rename the customer Wi-Fi
  piso-setup pair              replace the coin box (re-opens pairing for a few minutes)
  piso-setup summary           show this file again
  logread -e opennds -e coinslot
  cat /etc/coinslot.d/vouchers.txt   customers' paid sessions
EOT
	chmod 600 "$SUMMARY"
}

check_all() {  # prints PASS/FAIL lines, returns the number of failures
	_bad=0
	_ck() { if eval "$2" > /dev/null 2>&1; then log "PASS  $1"; else log "FAIL  $1"; _bad=$((_bad + 1)); fi; }
	_ck "LAN address is $LAN_IP" "ip -4 addr show br-lan | grep -q 'inet $LAN_IP/'"
	_ck "guest network is up" "ip -4 addr show br-guest | grep -q 'inet $GUEST_IP/'"
	_ck "hidden Wi-Fi $KIOSK_SSID is on" "iwinfo | grep -q 'ESSID: \"$KIOSK_SSID\"' || wifi status 2> /dev/null | grep -q '\"ssid\": \"$KIOSK_SSID\"'"
	_ck "Wi-Fi $(conf_get GUEST_NAME) is broadcasting" "iwinfo | grep -q 'ESSID: \"$(conf_get GUEST_NAME)\"'"
	_ck "the coin box has its own Wi-Fi password (not the published default)" "[ \"\$(uci -q get wireless.box_ap.key)\" != '$BOX_DEFAULT_WIFI' ]"
	_ck "hidden Wi-Fi $BOX_SSID is on and locked to the box" "[ \"\$(uci -q get wireless.box_ap.macfilter)\" = allow ] && [ -n \"\$(uci -q get wireless.box_ap.maclist)\" ]"
	_ck "coin box answers at $BOX_IP" box_up
	_ck "coin-slot manager is running" "curl -s -m 4 http://127.0.0.1:8099/info | grep -q fair_kb"
	_ck "coin events receiver is running (instant coins)" "pgrep -f 'coinslot-listener.sh event-reader'"
	_ck "the manager can talk to the box (signed request)" "/usr/bin/coinslot-listener.sh box | grep -qi answers"
	_ck "openNDS is running" "ndsctl status"
	_ck "internet through the WAN" "ping -c 1 -W 3 1.1.1.1 || ping -c 1 -W 3 8.8.8.8"
	return "$_bad"
}

stage2() {
	step "Applying the network settings (the LAN is not restarted, so this SSH session stays open)"
	stop_opennds   # (also on a re-run: no gating of the LAN while the box is paired)
	/etc/init.d/network reload > /dev/null 2>&1
	sleep 5
	wifi reload > /dev/null 2>&1
	/etc/init.d/firewall reload > /dev/null 2>&1
	/etc/init.d/dnsmasq restart > /dev/null 2>&1
	sleep 8
	ip -4 addr show br-guest | grep -q "inet $GUEST_IP/" || die "the guest network did not come up on $GUEST_IP"

	KEY=$(conf_get GW_KEY); NEWPW=$(conf_get BOX_ADMIN_PASS_NEW)
	if [ -n "$(conf_get BOX_MAC)" ] && box_up; then
		log "the coin box is already paired and answers"
		BOX_MAC=$(conf_get BOX_MAC)
	else
		pair_box
	fi
	provision_box "$NEWPW" "$KEY"
	rotate_box_wifi
	write_coinslot_conf "$KEY" "$BOX_MAC"

	step "Starting the services"
	/etc/init.d/coinslot stop > /dev/null 2>&1; /etc/init.d/coinslot disable > /dev/null 2>&1
	/etc/init.d/flash_coin enable; /etc/init.d/flash_coin restart
	[ -r /etc/piso-monitor.conf ] && { /etc/init.d/piso_monitor enable; /etc/init.d/piso_monitor restart; }
	/etc/init.d/opennds enable; /etc/init.d/opennds stop > /dev/null 2>&1; /etc/init.d/opennds start; NDS_RESTORE=0
	sleep 8

	step "Securing the router"
	_rp=$(conf_get ROOT_PASS)
	echo
	echo "  ROUTER (SSH / LuCI) PASSWORD:  $_rp      <-- write this down now"
	echo
	if [ "$ASSUME_YES" != 1 ] && [ -t 0 ]; then
		_try=0
		while :; do
			printf 'Type the password again to confirm you have saved it: '; stty -echo 2> /dev/null; read -r _again; stty echo 2> /dev/null; echo
			[ "$_again" = "$_rp" ] && break
			_try=$((_try + 1))
			[ "$_try" -lt 5 ] || { log "The password was not confirmed, so the router password was left unchanged. Run: piso-setup set-password"; _rp=""; break; }
			echo "That does not match. Look at the line above and try again."
		done
	fi
	if [ -z "$_rp" ]; then conf_set ROOT_PASS_SET 0
	elif printf '%s\n%s\n' "$_rp" "$_rp" | passwd root > /dev/null 2>&1; then log "root password set"; conf_set ROOT_PASS_SET 1
	else log "WARNING: could not set the root password; the router still has its old one"; conf_set ROOT_PASS_SET 0; fi

	step "Checking everything"
	check_all; _f=$?
	write_summary
	_hp=$(write_handout)
	echo; echo "================ SUMMARY (also saved in $SUMMARY) ================"; cat "$SUMMARY"; echo "=================================================================="
	log "A printable sheet with the passwords is in $_hp (copy it off with: scp -O root@$LAN_IP:$_hp .)"
	finish_telegram
	[ "$(conf_get ROOT_PASS_SET)" = 1 ] || { log "INCOMPLETE: the router password was not set. Run: piso-setup set-password"; _f=$((_f + 1)); }
	if [ "$_f" = 0 ]; then echo "DONE all checks passed" > "$STATE"; log ""; log "SETUP COMPLETE. Read $SUMMARY (ssh root@$LAN_IP)."
	else echo "DONE with $_f failed checks (see $LOG)" > "$STATE"; log ""; log "Setup finished, but $_f check(s) failed: see above and $LOG. Run: piso-setup status"; fi
}

# ---------------------------------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------------------------------
cmd_status() {
	[ -r "$STATE" ] && echo "setup: $(cat "$STATE")"
	DRY=1; check_all; _f=$?
	[ "$_f" = 0 ] && echo "All checks passed." || echo "$_f check(s) failed. Log: $LOG"
	return "$_f"
}

cmd_wifi_name() {
	_n="$1"
	[ -n "$_n" ] || { echo "usage: piso-setup wifi-name \"New Name\"" >&2; exit 1; }
	[ "${#_n}" -le 32 ] || { echo "the name can be at most 32 characters" >&2; exit 1; }
	case "$_n" in "$KIOSK_SSID" | "$BOX_SSID") echo "that name is reserved" >&2; exit 1 ;; esac
	case "$_n" in *\'* | *\"* | *\\* | *\$* | *\`*) echo "please avoid quotes, backslashes, \$ and backticks in the name" >&2; exit 1 ;; esac
	for s in $(uci -q show wireless | sed -n 's/^\(wireless\.guest_[^.=]*\)=wifi-iface$/\1/p'); do uci set "$s.ssid=$_n"; done
	uci set opennds.@opennds[0].gatewayname="$_n"
	uci commit wireless; uci commit opennds
	conf_set GUEST_NAME "$_n"
	wifi reload > /dev/null 2>&1
	/etc/init.d/opennds stop > /dev/null 2>&1; /etc/init.d/opennds start > /dev/null 2>&1
	write_summary 2> /dev/null
	echo "Customer Wi-Fi is now called \"$_n\"."
}

# piso-setup test-coin: one real coin window straight against the manager and the box, without the customer portal. It shows
# whether the box counts a coin at all (wiring, acceptor) before looking for portal problems.
cmd_test_coin() {
	_url="${COINSLOT_URL:-http://127.0.0.1:8099}"; _sid=$(rand 32 hex); _mac="aa:bb:cc:00:00:99"
	_get() { curl -s -m 10 "$_url$1" 2> /dev/null; }
	_a=$(_get "/start?sid=$_sid&plan=hyper&mac=$_mac")
	[ -n "$_a" ] || { echo "The coin-slot manager does not answer at $_url (is it running? /etc/init.d/flash_coin start)"; return 1; }
	case "$_a" in
		*'"error":"'*) echo "The manager could not open the coin slot: $_a"; return 1 ;;
		*'"state":"starting"'* | *'"state":"armed"'*) ;;
		*) echo "Unexpected answer from $_url (not the coin-slot manager?): $(printf '%s' "$_a" | head -c 200)"; return 1 ;;
	esac
	echo "The coin slot is armed (the box should beep). Insert a coin now. Waiting up to ${TEST_SECONDS:-30} seconds..."
	_t=0; _last=0
	while [ "$_t" -lt "${TEST_SECONDS:-30}" ]; do
		sleep 1; _t=$((_t + 1))
		_st=$(_get "/status?sid=$_sid")
		_p=$(printf '%s' "$_st" | sed -n 's/.*"pulses":\([0-9]*\).*/\1/p')
		if [ -n "$_p" ] && [ "$_p" -gt "$_last" ]; then echo "  coin detected: $_p peso(s) so far"; _last="$_p"; fi
		case "$_st" in *'"state":"done"'* | *'"state":"error"'*) break ;; esac
	done
	_get "/finish?sid=$_sid" > /dev/null
	_t=0; while [ "$_t" -lt 15 ]; do _st=$(_get "/status?sid=$_sid"); case "$_st" in *'"state":"done"'*) break ;; esac; sleep 1; _t=$((_t + 1)); done
	_p=$(printf '%s' "$_st" | sed -n 's/.*"pulses":\([0-9]*\).*/\1/p')
	if [ "${_p:-0}" -gt 0 ]; then echo "RESULT: the box counted $_p peso(s). The box and the manager work; if the portal does not show it, send the output of: piso-setup diag"
	else echo "RESULT: no coin was counted. The coin acceptor wiring or the box settings need a look (the box armed, so the network and key are fine). Last answer: $_st"; fi
	_get "/ack?sid=$_sid" > /dev/null
	[ "${_p:-0}" -gt 0 ]
}

# piso-setup diag: everything needed to diagnose a problem, in one block (contains no passwords).
cmd_diag() {
	echo "=== piso-setup diag ($(date '+%F %T'), setup $VERSION) ==="
	cmd_status 2>&1
	echo "--- manager /info"; curl -s -m 5 "${COINSLOT_URL:-http://127.0.0.1:8099}/info"; echo
	echo "--- manager <-> box"; /usr/bin/coinslot-listener.sh box 2>&1 | head -5
	echo "--- ledger vs box"; cmd_reconcile 2>&1
	echo "--- coin windows"; for d in /tmp/coinslot/*/; do [ -d "$d" ] && { echo "$d"; cat "$d/state" "$d/plan" 2>/dev/null; }; done
	echo "--- box neighbour / wifi"; ip neigh show | grep "$BOX_IP"; iwinfo 2> /dev/null | grep -A1 "$BOX_SSID"
	echo "--- firewall tables"; nft list tables 2> /dev/null
	echo "--- openNDS"; uci -q get opennds.@opennds[0].gatewayinterface; ndsctl status 2>&1 | head -12
	echo "--- last coin timings (ms since boot: armed, event, coin, granted, settled)"; logread -e coinslot 2> /dev/null | grep ' timing ' | tail -12
	echo "--- last coin-slot log lines"; logread -e coinslot 2> /dev/null | grep -v ' timing ' | tail -20
	echo "--- setup log"; tail -15 "$LOG" 2> /dev/null
}

# piso-setup reconcile: the box's own coin count against the router's revenue ledger (also checks the ledger chain).
cmd_reconcile() { /usr/bin/coinslot-listener.sh reconcile; }

# piso-setup telegram: connect the Telegram bot (alerts and remote commands). The token comes from @BotFather.
# telegram_connect <token> <site name>: pairs the bot with the first chat that writes to it and starts the monitor.
telegram_connect() {
	_tok="$1"; _site="$2"
	[ -x /usr/bin/piso-monitor.sh ] || die "the monitor is not installed: run the setup first"
	echo "TG_TOKEN='$_tok'" > /etc/piso-monitor.conf; chmod 600 /etc/piso-monitor.conf
	rm -rf /tmp/piso-monitor
	PISO_MONITOR_CONF=/etc/piso-monitor.conf /usr/bin/piso-monitor.sh pair || return 1
	_chat=$(cat /tmp/piso-monitor/paired.chat 2> /dev/null)
	[ -n "$_chat" ] || return 1
	printf 'Is that you (alerts and commands will be accepted only from this chat)? [y/N] '; read -r _a
	case "$_a" in y | Y) ;; *) rm -f /etc/piso-monitor.conf; echo "Cancelled."; return 1 ;; esac
	{ echo "TG_TOKEN='$_tok'"; echo "TG_CHAT='$_chat'"; echo "SITE_NAME='$_site'"; echo "REPORT_HOUR='21'"; echo "#HEALTHCHECK_URL=''"; } > /etc/piso-monitor.conf
	chmod 600 /etc/piso-monitor.conf
	/etc/init.d/piso_monitor enable; /etc/init.d/piso_monitor restart
	sleep 1; /usr/bin/piso-monitor.sh send "connected. Send /help for the commands."
	echo "Done. A message was sent to your Telegram. Optional dead-man switch: set HEALTHCHECK_URL in /etc/piso-monitor.conf (healthchecks.io), then: /etc/init.d/piso_monitor restart"
}

# valid_plain <text>: no quotes, backslashes, $ or backticks (values that end up inside shell or uci quoting)
valid_plain() { case "$1" in *\'* | *\"* | *\\* | *\$* | *\`*) return 1 ;; esac; return 0; }

# piso-setup telegram: connect the Telegram bot (alerts and remote commands). The token comes from @BotFather.
cmd_telegram() {
	[ "$(id -u)" = 0 ] || die "run as root"
	[ -x /usr/bin/piso-monitor.sh ] || die "the monitor is not installed: run the setup first"
	echo "1. In Telegram, talk to @BotFather: /newbot, choose a name, copy the token it gives you."
	printf '2. Paste the token here: '; read -r _tok
	[ -n "$_tok" ] || { echo "No token."; return 1; }
	_def=$(conf_get SITE_NAME); _def="${_def:-PisoPhone}"
	printf '3. Name of this site (shown in every message) [%s]: ' "$_def"; read -r _site; _site="${_site:-$_def}"
	valid_plain "$_site$_tok" || { echo "Please avoid quotes, backslashes, \$ and backticks."; return 1; }
	telegram_connect "$_tok" "$_site"
}

# ask_telegram: during setup (with a terminal only): offer to connect the bot at the end. Stores TG_TOKEN for finish_telegram.
ask_telegram() {
	[ "$ASSUME_YES" != 1 ] && [ -t 0 ] && [ "$DRY" != 1 ] || return 0
	[ ! -r /etc/piso-monitor.conf ] || return 0
	echo
	printf 'Connect Telegram alerts and remote control now? You need a bot token from @BotFather. [y/N] '; read -r _a
	case "$_a" in y | Y | yes) ;; *) return 0 ;; esac
	printf 'Paste the bot token: '; read -r _tok
	[ -n "$_tok" ] && valid_plain "$_tok" || { echo "No usable token: skipped (you can run: piso-setup telegram)."; return 0; }
	conf_set TG_TOKEN "$_tok"
}

# finish_telegram: at the end of the setup, when a token was given: pair the chat and start the monitor.
finish_telegram() {
	_tok=$(conf_get TG_TOKEN); [ -n "$_tok" ] || return 0
	step "Connecting Telegram"
	telegram_connect "$_tok" "$(conf_get SITE_NAME)" || log "Telegram was not connected. Run later: piso-setup telegram"
	conf_set TG_TOKEN ""
}

# --- the printed page for the shop owner ---------------------------------------------------------------------------------------
html_esc() { printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'; }
write_handout() {
	_f="${HANDOUT:-/root/piso-handout.html}"
	_site=$(html_esc "$(conf_get SITE_NAME)"); _guest=$(html_esc "$(conf_get GUEST_NAME)"); _kp=$(html_esc "$(conf_get KIOSK_PASS)")
	_bp=$(html_esc "$(conf_get BOX_ADMIN_PASS)"); _rp=$(html_esc "$(if [ "$(conf_get ROOT_PASS_SET)" = 1 ]; then conf_get ROOT_PASS; else echo "(not set by the setup: run piso-setup set-password)"; fi)")
	umask 077
	cat > "$_f" << EOT
<!doctype html><html><head><meta charset="utf-8"><title>${_site:-PisoPhone} setup sheet</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:720px;margin:2rem auto;padding:0 1rem}h1{margin-bottom:0}
table{border-collapse:collapse;width:100%;margin:1rem 0}td,th{border:1px solid #999;padding:.5rem .7rem;text-align:left}th{background:#eee;width:34%}
code{font:1.05em monospace}.warn{border:2px solid #b00;padding:.6rem 1rem;margin:1rem 0}@media print{body{margin:0}}</style></head><body>
<h1>${_site:-PisoPhone}</h1><p>PisoPhone setup sheet &middot; $(date '+%F')</p>
<div class="warn"><b>Keep this page private.</b> It holds the passwords for the whole system. Store it safely and do not post it.</div>
<table>
<tr><th>Customer Wi-Fi (public)</th><td><code>${_guest}</code> &middot; open, customers pay by coin</td></tr>
<tr><th>Rental-phone Wi-Fi (hidden)</th><td>name <code>${KIOSK_SSID}</code><br>password <code>${_kp}</code><br>Not shown in any Wi-Fi list. Only used when setting up a phone.</td></tr>
<tr><th>Coin box admin page</th><td>address <code>http://${BOX_IP}</code> (on the PisoKiosk network)<br>user <code>admin</code><br>password <code>${_bp}</code><br>This password is also the admin PIN of the rental phones.</td></tr>
<tr><th>Router login (SSH / LuCI)</th><td>address <code>${LAN_IP}</code> (on the PisoKiosk network or a LAN cable)<br>user <code>root</code><br>password <code>${_rp}</code></td></tr>
</table>
<p><b>Setting up a new rental phone:</b> open the coin box admin page, tap <i>Install &amp; Provision</i>, type the PisoKiosk password above on the page, and plug the phone in by USB.</p>
</body></html>
EOT
	umask 022
	chmod 600 "$_f"
	echo "$_f"
}
cmd_handout() { [ "$(id -u)" = 0 ] || die "run as root"; _p=$(write_handout); echo "Printable setup sheet written to: $_p"; echo "Copy it to a computer to print:  scp -O root@$LAN_IP:$_p ."; }

# --- opt-in: keep the rental phones away from the router's admin ports --------------------------------------------------------
# The phones share the router's LAN, so they can reach SSH and LuCI (password protected). lock-admin makes ports 22, 80 and
# 443 answer only to the MAC addresses you name; it undoes itself after 2 minutes unless you confirm, so it cannot lock you out.
this_machine_mac() {  # the MAC address of the computer this SSH session comes from
	_ip="${SSH_CONNECTION%% *}"; [ -n "$_ip" ] || return 0
	ip neigh show "$_ip" 2> /dev/null | awk '{ for (i = 1; i <= NF; i++) if ($i == "lladdr") { print $(i + 1); exit } }'
}
admin_rules_clear() {
	for _s in $(uci -q show firewall | sed -n 's/^firewall\.\(piso_admin_[a-z0-9_]*\)=rule$/\1/p'); do uci -q delete "firewall.$_s"; done
}
cmd_lock_admin() {
	[ "$(id -u)" = 0 ] || die "run as root"
	_macs="$*"
	[ -n "$_macs" ] || { _m=$(this_machine_mac); [ -z "$_m" ] || { _macs="$_m"; echo "Using this computer's address: $_m"; }; }
	[ -n "$_macs" ] || die "name the computer(s) that may administer the router: piso-setup lock-admin aa:bb:cc:dd:ee:ff [more addresses]"
	_i=0
	for _m in $_macs; do
		case "$_m" in [0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]:[0-9a-fA-F][0-9a-fA-F]) ;; *) die "not a MAC address: $_m" ;; esac
	done
	admin_rules_clear
	for _m in $_macs; do
		_i=$((_i + 1)); _n="piso_admin_allow_$_i"
		uci set "firewall.$_n=rule"; uci set "firewall.$_n.name=Router-admin-allow-$_i"
		uci set "firewall.$_n.src=lan"; uci set "firewall.$_n.src_mac=$_m"; uci set "firewall.$_n.proto=tcp"
		uci set "firewall.$_n.dest_port=22 80 443"; uci set "firewall.$_n.target=ACCEPT"
	done
	uci set firewall.piso_admin_block=rule; uci set firewall.piso_admin_block.name='Router-admin-block'
	uci set firewall.piso_admin_block.src=lan; uci set firewall.piso_admin_block.proto=tcp
	uci set firewall.piso_admin_block.dest_port='22 80 443'; uci set firewall.piso_admin_block.target=REJECT
	uci commit firewall
	conf_set ADMIN_MACS "$_macs"
	rm -f /tmp/piso-admin-confirm
	/etc/init.d/firewall reload > /dev/null 2>&1
	# the safety net: back to open after 2 minutes unless confirmed (runs on its own, so it also works if this session is lost)
	setsid sh -c 'sleep "${LOCK_ADMIN_SECONDS:-120}"; [ -e /tmp/piso-admin-confirm ] || { uci -q delete firewall.piso_admin_block; uci commit firewall; /etc/init.d/firewall reload; logger -t piso-setup "lock-admin was not confirmed and has been undone"; }' > /dev/null 2>&1 &
	echo "Locked: only $_macs may reach the router's admin ports (22, 80, 443)."
	echo "Now open a NEW SSH session from that computer to check that you can still log in."
	echo "Then confirm here (or run: piso-setup lock-admin-confirm). Without confirmation the lock undoes itself in 2 minutes."
	if [ -t 0 ]; then
		printf 'Type CONFIRM once the new session works: '; read -r _a
		[ "$_a" = CONFIRM ] && cmd_lock_admin_confirm || echo "Not confirmed: the lock will undo itself."
	fi
}
cmd_lock_admin_confirm() { : > /tmp/piso-admin-confirm; echo "Confirmed: the admin lock stays. Undo it with: piso-setup unlock-admin"; }
cmd_unlock_admin() {
	[ "$(id -u)" = 0 ] || die "run as root"
	admin_rules_clear; uci commit firewall; conf_set ADMIN_MACS ""
	/etc/init.d/firewall reload > /dev/null 2>&1
	echo "The router's admin ports are open to the whole kiosk network again."
}

cmd_set_password() {
	[ "$(id -u)" = 0 ] || die "run as root"
	echo "Choose a new router (SSH / LuCI) password; it is saved in $CONF and shown by: piso-setup summary"
	stty -echo 2> /dev/null; printf 'New password (8+ characters): '; read -r _a; echo; printf 'Again: '; read -r _b; echo; stty echo 2> /dev/null
	[ "$_a" = "$_b" ] && [ "${#_a}" -ge 8 ] || { echo "The passwords differ or are shorter than 8 characters."; return 1; }
	printf '%s\n%s\n' "$_a" "$_a" | passwd root > /dev/null 2>&1 || { echo "Could not set it."; return 1; }
	conf_set ROOT_PASS "$_a"; conf_set ROOT_PASS_SET 1; write_summary; echo "Done."
}

cmd_pair() {
	DRY=0; [ "$(id -u)" = 0 ] || die "run as root"
	conf_set BOX_MAC ""
	pair_box
	write_coinslot_conf "$(conf_get GW_KEY)" "$BOX_MAC"
	provision_box "$(conf_get BOX_ADMIN_PASS_NEW)" "$(conf_get GW_KEY)"
	rotate_box_wifi
	/etc/init.d/flash_coin restart
	write_summary
	log "The new coin box is paired. Check with: piso-setup status"
}

# The router password protects SSH and LuCI, which the kiosk phones' network can reach. Ask for one, or generate one that is
# printed on screen (and in the summary), so nobody is ever locked out of the router.
# ask_name NAME "label" DEFAULT [VALUE]: a Wi-Fi name. Already chosen (a re-run): kept. Otherwise from the environment, or asked
# for (Enter takes the default); with no terminal the default is used.
ask_name() {
	_n="$1"; _label="$2"; _def="$3"; _v="$4"
	ASKED=$(conf_get "$_n"); [ -z "$ASKED" ] || return 0
	if [ -z "$_v" ] && [ "$ASSUME_YES" != 1 ] && [ -t 0 ]; then
		echo
		printf 'Name of the %s Wi-Fi [%s]: ' "$_label" "$_def"; read -r _v
	fi
	_v="${_v:-$_def}"
	[ "${#_v}" -le 32 ] || die "the $_label name can be at most 32 characters"
	case "$_v" in *\'* | *\"* | *\\* | *\$* | *\`*) die "the $_label name may not contain quotes, backslashes, \$ or backticks" ;; esac
	conf_set "$_n" "$_v"; ASKED="$_v"
}

# The public Wi-Fi name (asked once). The kiosk network keeps its fixed, hidden name: the phones are provisioned with it.
choose_names() {
	ask_name GUEST_NAME "public customer" "${GUEST_SSID:-PisoWiFi}" "$GUEST_SSID"; GUEST_NAME="$ASKED"
	[ "$GUEST_NAME" != "$KIOSK_SSID" ] || die "the public Wi-Fi name cannot be $KIOSK_SSID"
	case "$GUEST_NAME" in *"$BOX_SSID"*) die "the public Wi-Fi name may not contain $BOX_SSID (the coin box's hidden network)" ;; esac
}

# ask_password NAME "label" MIN MAX [ENV VALUE]: the password for NAME. Already chosen (a re-run): kept. Otherwise it is
# taken from the environment, or asked for (typed twice, not shown); Enter (or no terminal) leaves it to be generated.
ask_password() {
	_n="$1"; _label="$2"; _min="$3"; _max="$4"; _p="$5"
	[ -z "$(conf_get "$_n")" ] || return 0
	if [ -z "$_p" ] && [ "$ASSUME_YES" != 1 ] && [ -t 0 ]; then
		echo
		echo "Choose the $_label ($_min to $_max characters, no spaces or quotes), or press Enter to have one generated and shown:"
		stty -echo 2> /dev/null; read -r _p; stty echo 2> /dev/null; echo
		if [ -n "$_p" ]; then
			printf 'Type it again: '; stty -echo 2> /dev/null; read -r _p2; stty echo 2> /dev/null; echo
			[ "$_p" = "$_p2" ] || die "the two entries of the $_label differ: run the setup again"
		fi
	fi
	[ -n "$_p" ] || return 0
	[ "${#_p}" -ge "$_min" ] || die "the $_label must be at least $_min characters"
	[ "${#_p}" -le "$_max" ] || die "the $_label can be at most $_max characters"
	case "$_p" in *[[:space:]\'\"\\\$\`]*) die "the $_label may not contain spaces, quotes, backslashes, \$ or backticks" ;; esac
	conf_set "$_n" "$_p"
}

# Every password the system needs is chosen here, once. The coin box's super-admin password is not one of them: the
# firmware keeps it under remote management and it cannot be set from here.
choose_passwords() {
	ask_password ROOT_PASS "router password (SSH and LuCI login)" 8 63 "$ROOT_PASSWORD"
	ask_password KIOSK_PASS "PisoKiosk Wi-Fi password (typed once into each rental phone's setup page)" 8 63 "$KIOSK_PASSWORD"
	ask_password BOX_ADMIN_PASS_NEW "coin box admin password (the box's web page; also the phones' admin PIN)" 8 32 "$BOX_NEW_ADMIN_PASSWORD"
}

# review_choices: what will be applied, before anything is changed (with a terminal only).
review_choices() {
	[ "$DRY" != 1 ] && [ "$ASSUME_YES" != 1 ] && [ -t 0 ] || return 0
	_pw() { if [ -n "$(conf_get "$1")" ]; then echo "chosen by you"; else echo "generated for you (shown at the end)"; fi; }
	echo
	echo "================ PLEASE REVIEW ================"
	echo "  Public Wi-Fi (customers):  $GUEST_NAME  (open, behind the coin payment page)"
	echo "  Rental-phone Wi-Fi:        $KIOSK_SSID  (hidden, 2.4 + 5 GHz)"
	echo "  Coin box network:          $BOX_SSID  (hidden, only the box)"
	echo "  Site name:                 $(conf_get SITE_NAME)"
	echo "  Router address:            $LAN_IP   Country: ${COUNTRY:-PH}"
	echo "  Router password:           $(_pw ROOT_PASS)"
	echo "  Kiosk Wi-Fi password:      $(_pw KIOSK_PASS)"
	echo "  Coin box admin password:   $(_pw BOX_ADMIN_PASS_NEW)"
	echo "  The Wi-Fi networks of this router are replaced; the coin box is paired and set up; SSH stays open."
	echo "==============================================="
	printf 'Apply these settings? [y/N] '; read -r _a
	case "$_a" in y | Y | yes) ;; *) echo "Cancelled. Nothing was changed (answers kept: run the setup again to change them)."; exit 1 ;; esac
}

stage1() {
	preflight
	if [ "$DRY" != 1 ] && [ "$ASSUME_YES" != 1 ] && [ -t 0 ]; then
		echo
		echo "This will set up the router as a PisoPhone system: the Wi-Fi networks are replaced (the LAN address stays $LAN_IP)."
		echo "First a few questions; nothing is changed until you have reviewed your answers."
	fi
	choose_names
	ask_name SITE_NAME "shop / site (printed on the setup sheet, shown in Telegram messages)" "$GUEST_NAME" "$SITE_NAME"
	valid_plain "$ASKED" || die "the site name may not contain quotes, backslashes, \$ or backticks"
	choose_passwords
	review_choices
	ask_telegram
	KIOSK_PASS=$(secret KIOSK_PASS 12)
	secret ROOT_PASS 14 > /dev/null; secret GW_KEY 64 hex > /dev/null; secret BOX_ADMIN_PASS_NEW 16 > /dev/null
	BOX_MAC=$(conf_get BOX_MAC)
	if [ "$DRY" = 1 ]; then uci_batch "<kiosk password>" "$GUEST_NAME" "$BOX_MAC"; return 0; fi

	: > "$LOG"
	install_packages
	extract_payload "$0"
	step "Writing the router settings"
	apply_batch "$KIOSK_PASS" "$GUEST_NAME" "$BOX_MAC"
	stage2
}

main() {
	CMD=""
	while [ $# -gt 0 ]; do
		case "$1" in
			--dry-run) DRY=1 ;;
			--yes | -y) ASSUME_YES=1 ;;
			status | pair | summary | wifi-name | uninstall-info | test-coin | diag | set-password | reconcile | telegram | rotate-box-wifi | handout | lock-admin | lock-admin-confirm | unlock-admin) CMD="$1"; shift; ARG="$1"; ARGS="$*"; break ;;
			-h | --help) sed -n '2,/^# Options:/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
			*) echo "unknown option: $1 (try --help)" >&2; exit 1 ;;
		esac
		shift
	done
	case "$CMD" in
		status) cmd_status ;;
		wifi-name) cmd_wifi_name "$ARG" ;;
		pair) cmd_pair ;;
		test-coin) cmd_test_coin ;;
		diag) cmd_diag ;;
		reconcile) cmd_reconcile ;;
		telegram) cmd_telegram ;;
		handout) cmd_handout ;;
		lock-admin) cmd_lock_admin $ARGS ;;
		lock-admin-confirm) cmd_lock_admin_confirm ;;
		unlock-admin) cmd_unlock_admin ;;
		rotate-box-wifi) DRY=0; [ "$(id -u)" = 0 ] || die "run as root"; conf_set BOX_WIFI_ROTATED 0; rotate_box_wifi ;;
		set-password) cmd_set_password ;;
		summary) cat "$SUMMARY" ;;
		uninstall-info) echo "To undo: sysupgrade -n (factory reset) the router. Nothing else is changed outside the files listed in $0." ;;
		*) stage1 ;;
	esac
}

[ -n "$PISO_SETUP_SOURCE_ONLY" ] && return 0   # tests load the functions only
main "$@"
exit $?

# ---- payload: the portal files (extracted by extract_payload) ----
#@@FILE /usr/bin/coinslot-listener.sh 755
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
#@@FILE /usr/lib/opennds/flash_coin.sh 755
#!/bin/sh
# ThemeSpec for openNDS (login_option_enabled '3'): "flash coin", pay for Wi-Fi with coins. Install as
# /usr/lib/opennds/flash_coin.sh next to flash_coin_lib.sh. Same structure as the paper-voucher theme it grew from
# (header / footer / a roll file of sessions / auth_log / auto-reconnect by MAC), except that the "voucher" is a coin
# payment: the local coin-slot manager (coinslot-listener.sh) counts the coins and /verify tells this theme what was paid;
# this theme alone writes the roll and authenticates the device. Plain HTML + ~2 KB of CSS: nothing to download.
#
# Page sequence, driven by the form variable "coinact":
#   (none)    welcome (rates, "Insert Coin") | automatic reconnect | "paused, tap Resume" | coins waiting to be used
#   start     arm the coin slot for the chosen plan, then the waiting page
#   wait      coin window: pesos inserted, time earned, live countdown
#   finish    stop accepting, wait for in-flight coins, then the result
#   connect   (landing=yes) verify the payment, record it on the roll, authenticate the device, tell the manager it is used
#   vform / voucher   restore a session by its code on a new device
# Minutes and speed caps are decided by the manager and the roll, never taken from the browser.

title="flash_coin"
. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"

# The coin-slot session id is a hash of the client's secret openNDS id: other clients cannot guess it.
coinslot_sid() { printf '%s' "$hid" | sha256sum | cut -c1-32; }
# The refresh link needs the base64 characters that are special in a URL percent-encoded.
fas_urlsafe() { printf '%s' "$fas" | sed 's/+/%2B/g; s,/,%2F,g; s/=/%3D/g'; }

fmt_min() {  # 30 -> "30 min", 60 -> "1 hr", 690 -> "11 hr 30 min", 1440 -> "24 hrs"
	_m="${1:-0}"
	if [ "$_m" -lt 60 ]; then echo "$_m min"; return; fi
	_h=$((_m / 60)); _r=$((_m % 60)); _u="hr"; [ "$_h" -gt 1 ] && _u="hrs"
	if [ "$_r" -gt 0 ]; then echo "$_h $_u $_r min"; else echo "$_h $_u"; fi
}
plan_name() { case "$1" in endurance) echo "Endurance" ;; *) echo "HyperSpeed" ;; esac; }

# ---------------------------------------------------------------------------
# Frame (libopennds.sh calls header() ONCE per request, before display_terms / landing_page /
# generate_splash_sequence, so header() also decides which page to show and whether it reloads itself)
# ---------------------------------------------------------------------------
header() {
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	PAGE=""; REFRESH=""
	if [ "$landing" != "yes" ] && [ "$terms" != "yes" ]; then choose_page; fi
	refreshtag=""
	if [ -n "$REFRESH" ]; then
		refreshtag="<meta http-equiv=\"refresh\" content=\"${REFRESH%% *}; url=/opennds_preauth/?fas=$(fas_urlsafe)&coinact=${REFRESH##* }&coinplan=$coinplan&coinforfeit=$coinforfeit\">"
		case "$PAGE" in wait) refreshtag="<noscript>$refreshtag</noscript>" ;; esac   # with scripts on, these pages update themselves
	fi
	cat << HTML
<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Cache-Control" content="no-store">
$refreshtag
<title>$gatewayname</title>
<style>
:root{--bg:#0f1715;--card:#16221f;--line:#243630;--fg:#e8f0ec;--mut:#8aa59a;--h:#38ef7d;--e:#4cc9f0;--ok:#38ef7d;--bad:#ff4b4b}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;display:flex;justify-content:center}
.w{width:100%;max-width:440px;padding:20px 16px 36px}
h1{font-size:19px;margin:0 0 4px;text-align:center;color:var(--ok);text-transform:uppercase;letter-spacing:1px;text-shadow:0 0 10px #38ef7d66}
.sub{color:var(--mut);text-align:center;margin:0 0 18px;font-size:14px}
.plans{display:grid;gap:12px}
.plan input{position:absolute;opacity:0}
.card{display:block;background:var(--card);border:2px solid var(--line);border-radius:16px;padding:14px 16px;cursor:pointer}
.plan input:checked+.card{border-color:var(--c)}
.plan input:focus-visible+.card{outline:2px solid var(--fg)}
.card b{color:var(--c);font-size:17px}
.card small{display:block;color:var(--mut);font-size:13px;margin:2px 0 8px}
.card div{display:flex;justify-content:space-between;padding:5px 0;border-top:1px solid var(--line);font-size:15px}
.card div span:first-child{font-weight:600}
.h{--c:var(--h)}.e{--c:var(--e)}
.note{color:var(--mut);font-size:12.5px;text-align:center;margin:10px 4px 0}
button{font:inherit}
.coin{display:block;width:152px;height:152px;margin:22px auto 6px;border:0;border-radius:50%;font-weight:700;font-size:20px;line-height:1.2;color:#241700;cursor:pointer;background:radial-gradient(circle at 32% 28%,#ffe58a,#f5a300);box-shadow:0 8px 28px #f5a30055}
.coin:active{transform:scale(.97)}
.coin small{display:block;font-weight:500;font-size:12px}
.btn{display:block;width:100%;padding:14px;margin:12px 0 0;border:0;border-radius:12px;font-weight:600;font-size:17px;color:#04140c;background:linear-gradient(135deg,#11998e,#38ef7d);cursor:pointer}
.btn.alt{background:transparent;color:var(--fg);border:1px solid var(--line)}
.big{font-size:34px;font-weight:700;text-align:center;margin:6px 0}
.mut{color:var(--mut);text-align:center;font-size:14px}
.bar{height:8px;background:var(--line);border-radius:4px;overflow:hidden;margin:14px 0}
.bar i{display:block;height:100%;background:var(--h)}
.code{font:700 28px/1.2 ui-monospace,Menlo,Consolas,monospace;letter-spacing:3px;text-align:center;background:var(--card);border:2px dashed var(--line);border-radius:12px;padding:12px;margin:12px 0}
.msg{background:var(--card);border-left:4px solid var(--bad);border-radius:8px;padding:12px 14px;margin:12px 0}
.snd{display:block;margin:10px auto 0;padding:7px 14px;border:1px solid var(--line);border-radius:20px;background:transparent;color:var(--mut);font-size:13px;cursor:pointer}
a{color:var(--mut)}
input[type=text]{width:100%;padding:13px;border-radius:12px;border:1px solid var(--line);background:var(--card);color:var(--fg);font:600 20px ui-monospace,Menlo,Consolas,monospace;text-align:center;text-transform:uppercase;letter-spacing:3px}
.foot{text-align:center;font-size:12px;color:var(--mut);margin-top:22px}
</style>
</head><body><div class="w">
<h1>$gatewayname</h1>
<script>
/* Any tap that leaves the page shows it was received, and a second tap while the first is still loading is ignored. */
document.addEventListener("submit",function(e){var f=e.target,b=f.querySelector&&f.querySelector("button[type=submit]");
if(f.__t&&Date.now()-f.__t<8000){e.preventDefault();return}f.__t=Date.now();if(b){b.textContent=f.id==="coinform"?"Getting the coin slot ready \u00b7 Sandali lang":"Please wait \u00b7 Sandali lang";b.style.opacity=".6"}},true);
window.addEventListener("pageshow",function(e){if(e.persisted)location.reload()});
</script>
HTML
}

footer() {
	cat << HTML
<p class="foot"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&terms=yes">Terms</a><br>&#9889; Secure Network</p>
</div></body></html>
HTML
	exit 0
}

# A form that posts back to the portal. $1 label, $2 coinact, $3 css class, $4 "landing" to also set landing=yes.
action_button() {
	_l=""; [ "$4" = "landing" ] && _l="<input type=\"hidden\" name=\"landing\" value=\"yes\">"
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="$2"><input type="hidden" name="coinplan" value="$coinplan"><input type="hidden" name="coinforfeit" value="$coinforfeit">$_l
<button class="btn $3" type="submit">$1</button></form>
HTML
}

# Start a coin window for a given plan; $3 = "forfeit" also confirms giving up the time left on the other plan.
plan_button() {
	_f=""; [ "$3" = "forfeit" ] && _f="<input type=\"hidden\" name=\"coinforfeit\" value=\"yes\">"
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="start"><input type="hidden" name="coinplan" value="$2">$_f
<button class="btn $4" type="submit">$1</button></form>
HTML
}

# ---------------------------------------------------------------------------
# Deciding what to show (runs from header(), before any page content)
# ---------------------------------------------------------------------------
# Sets PAGE: welcome wait counting busy mismatch error result reconnect pausedhome restored voucherform pausecheck paused
# pauseerr unavailable, and REFRESH ("<seconds> <coinact>") when the page should reload itself.
choose_page() {
	sid=$(coinslot_sid); mac="$clientmac"
	case "$coinplan" in endurance) ;; *) coinplan="hyper" ;; esac
	[ "$coinforfeit" = "yes" ] || coinforfeit=""
	# Time that is already paid for needs no coin slot: a device coming back after a reboot or power cut reconnects even
	# if the manager is down.
	if [ -z "$coinact" ]; then
		cst=$(coinslot "/status?sid=$sid")
		if [ "$(printf '%s' "$cst" | jget state)" = "done" ] && [ "$(printf '%s' "$cst" | jget claimed)" = "false" ] &&
			[ "$(printf '%s' "$cst" | jget pulses)" -gt 0 ] 2> /dev/null; then
			PAGE="result"; return          # coins of an earlier window that never became access come first
		fi
		flash_peek "$mac"
		case "$P_STATE" in
			running) if [ "$status" != "authenticated" ]; then PAGE="reconnect"; return; fi ;;
			paused) PAGE="pausedhome"; return ;;
		esac
	fi
	flash_info || { PAGE="unavailable"; return; }

	case "$coinact" in
		start)
			flash_peek "$mac"
			if [ "$P_STATE" = running ] || [ "$P_STATE" = paused ]; then
				if [ "$R_PLAN" != "$coinplan" ] && [ "$coinforfeit" != "yes" ]; then
					PAGE="mismatch"; otherplan="$R_PLAN"; otherleft="$R_LEFT"; return
				fi
			fi
			answer=$(coinslot "/start?sid=$sid&plan=$coinplan&mac=$mac&flash=1${coinforfeit:+&forfeit=1}")
			if [ "$(printf '%s' "$answer" | jget state)" = "error" ]; then
				err=$(printf '%s' "$answer" | jget error)
				case "$err" in
					SLOT_BUSY | COOLDOWN) PAGE="busy"; busyretry=$(printf '%s' "$answer" | jget retry) ;;
					*) PAGE="error" ;;
				esac
				return
			fi
			choose_wait_page ;;
		wait) choose_wait_page ;;
		finish)
			coinslot "/finish?sid=$sid" > /dev/null
			cst=$(coinslot "/status?sid=$sid"); state=$(printf '%s' "$cst" | jget state)
			if [ "$state" = "done" ] || [ "$state" = "none" ] || [ "$state" = "error" ]; then
				PAGE="result"
			else
				PAGE="counting"; REFRESH="2 finish"
			fi ;;
		pausecheck) flash_peek "$mac"; PAGE="pausecheck" ;;
		pause)
			flash_pause "$mac" "${pausepesos:-10}"; prc=$?
			if [ "$prc" = 0 ]; then flash_peek "$mac"; PAGE="paused"; else PAGE="pauseerr"; fi ;;
		vform) PAGE="voucherform" ;;
		voucher)
			flash_restore "$vcode" "$mac"; vrc=$?
			if [ "$vrc" = 0 ]; then flash_peek "$mac"; PAGE="restored"; else PAGE="voucherform"; fi ;;
		*) PAGE="welcome" ;;
	esac
}

choose_wait_page() {
	cst=$(coinslot "/status?sid=$sid")
	state=$(printf '%s' "$cst" | jget state)
	case "$state" in
		starting | armed) PAGE="wait"; REFRESH="2 wait"; coinplan=$(printf '%s' "$cst" | jget plan) ;;
		done) PAGE="result" ;;
		error)
			err=$(printf '%s' "$cst" | jget error)
			# The coin slot was in use when the window tried to arm: the busy page tells the customer when it is free.
			if [ "$err" = "SLOT_BUSY" ]; then PAGE="busy"; busyretry=10; else PAGE="error"; fi ;;
		*) PAGE="welcome" ;;
	esac
}

# ---------------------------------------------------------------------------
# Page content (header() has already been printed by libopennds.sh)
# ---------------------------------------------------------------------------
generate_splash_sequence() {
	case "$PAGE" in
		wait) page_wait ;;
		counting) page_counting ;;
		busy) page_busy ;;
		mismatch) page_mismatch ;;
		error) page_error ;;
		result) page_result ;;
		reconnect) page_reconnect ;;
		pausedhome) page_pausedhome ;;
		restored) page_restored ;;
		voucherform) page_voucherform ;;
		pausecheck) page_pausecheck ;;
		paused) page_paused ;;
		pauseerr) page_pauseerr ;;
		unavailable) page_unavailable ;;
		*) page_welcome ;;
	esac
	footer
}

# Rates of one plan from the manager: lines "pesos minutes".
tier_rows() {
	coinslot "/tiers?plan=$1" | while read -r _p _m; do
		[ -n "$_p" ] && echo "<div><span>&#8369;$_p</span><span>$(fmt_min "$_m")</span></div>"
	done
}

page_welcome() {
	hchk=""; echk=""; [ "$coinplan" = "endurance" ] && echk="checked" || hchk="checked"
	edown=$(($(printf '%s' "$info" | jget e_down) / 1000)); eup=$(($(printf '%s' "$info" | jget e_up) / 1000))
	fair=$(printf '%s' "$info" | jget fair_gb); pausepesos=$(printf '%s' "$info" | jget pause_pesos)
	cat << HTML
<p class="sub">$(if [ "$status" = authenticated ]; then echo "You are connected &middot; add time &middot; Dagdagan ang oras"; else echo "Wi-Fi rates &middot; Presyo ng Wi-Fi"; fi)</p>
<form id="coinform" action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas">
<input type="hidden" name="coinact" value="start">
<div class="plans">
<label class="plan h"><input type="radio" name="coinplan" value="hyper" $hchk>
<span class="card"><b>&#9889; HyperSpeed</b><small>Full speed, no limits &middot; Walang limit ang bilis</small>
$(tier_rows hyper)
</span></label>
<label class="plan e"><input type="radio" name="coinplan" value="endurance" $echk>
<span class="card"><b>&#9203; Endurance</b><small>Up to $edown Mbps down / $eup Mbps up &middot; Mas matagal<br>Pause once on &#8369;$pausepesos+ &middot; I-pause isang beses</small>
$(tier_rows endurance)
</span></label>
</div>
<button class="coin" type="submit">Insert Coin<small>Maglagay ng barya</small></button>
</form>
<p class="note">Coins add up &middot; Nag-iipon ang oras. HyperSpeed may slow down after $fair GB (fair use).</p>
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=vform">I have a code (restore my time) &middot; May code ako</a></p>
<div id="live" style="display:none">
<p class="sub" id="lsub"></p>
<div class="big" id="lpes">&#8369;0</div>
<p class="mut" id="lmin"></p>
<div id="lcd" style="display:none"><div class="bar"><i id="lbar" style="width:100%"></i></div><p class="mut" id="lleft"></p></div>
<div class="msg" id="lon" style="display:none;border-left-color:var(--ok)"><b>&#10003; You're online &middot; Nakakonekta ka na</b><br><span id="lont"></span></div>
<div id="lmis" style="display:none"></div>
<div id="lfin" style="display:none"><p class="mut">Your code restores your time on any device:</p><div class="code" id="lcode"></div>
<a class="btn" style="text-decoration:none;text-align:center" href="http://$gatewayfqdn/?$randquery">Continue browsing</a></div>
<button class="btn" type="button" id="ldone" style="display:none">Done &middot; Connect me now &middot; Tapos na</button>
<a class="btn alt" id="lagain" style="display:none;text-decoration:none;text-align:center" href="/opennds_preauth/?fas=$(fas_urlsafe)">Try again</a>
</div>
<script>
/* One live page: Insert Coin talks to the router's small coin API (port $infostream) instead of loading portal pages.
   The coins show the moment the box counts them; the window's total is priced once, and the device goes online once, when
   the customer is done (Done, or the timer runs out). If the API cannot be reached, the regular pages take over (also used without scripts). */
(function(){
var f=document.getElementById("coinform"),SP=${infostream:-0},SID="$sid",FIRST=${infofirst:-30},IDLE=${infoidle:-15},
A=window.AudioContext||window.webkitAudioContext;
if(!f||!SP||!window.fetch||!window.JSON||!window.FormData||!window.URLSearchParams)return;
var base="http://"+location.hostname+":"+SP,ctx=null,es=null,pt=null,tk=null,pes=0,on=false,fin=false,armed=false,left=0,tot=FIRST,plan="hyper";
function el(i){return document.getElementById(i)}
function show(i,v){var e=el(i);if(e)e.style.display=v?"":"none"}
function put(i,t){var e=el(i);if(e&&e.textContent!==t)e.textContent=t}
function pn(p){return p==="endurance"?"Endurance":"HyperSpeed"}
function fmt(m){m=+m||0;if(m<60)return m+" min";var h=Math.floor(m/60),r=m%60;return h+(h>1?" hrs":" hr")+(r?" "+r+" min":"")}
function say(t){try{if(window.speechSynthesis){speechSynthesis.cancel();var u=new SpeechSynthesisUtterance(t);u.lang="en-US";speechSynthesis.speak(u)}}catch(e){}}
function tone(f0,t,d){var o=ctx.createOscillator(),g=ctx.createGain();o.type="triangle";o.frequency.value=f0;
g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.35,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+d);
o.connect(g);g.connect(ctx.destination);o.start(t);o.stop(t+d+.05)}
function ding(n){try{if(!ctx||ctx.state!=="running")return;var t=ctx.currentTime;n=Math.min(n,4);for(var i=0;i<n;i++){tone(988,t+i*.22,.12);tone(1319,t+i*.22+.1,.3)}}catch(e){}}
function legacy(fq){var q=new URLSearchParams(new FormData(f));q.set("coinplan",plan);if(fq)q.set("coinforfeit","yes");location.href=f.action+"?"+q.toString()}
function portal(){location.href=f.action+"?fas="+encodeURIComponent(f.elements.fas.value)}
function stop(){if(es){es.close();es=null}if(pt){clearInterval(pt);pt=null}if(tk){clearInterval(tk);tk=null}}
function bar(){put("lleft",left+"s left · the timer restarts with every coin");var b=el("lbar");if(b)b.style.width=Math.max(0,Math.min(100,100*left/tot))+"%"}
function busy(j,cool){stop();show("lcd",0);show("ldone",0);
put("lsub",cool?"Too many empty tries \u00b7 Sobrang daming walang barya":"Coin slot in use \u00b7 Ginagamit ang coin slot");put("lpes","");
put("lmin",(cool?"Please wait about ":"Another customer is paying. Try again in about ")+(+j.retry||15)+" seconds \u00b7 Subukan ulit mamaya.");show("lagain",1)}
function err(e){stop();show("lcd",0);show("ldone",0);put("lsub","Coin payment is not available right now");put("lpes","");put("lmin","Please try again or ask the attendant ("+e+").");show("lagain",1)}
function upd(j){
if(fin)return;
if(j.state==="error"&&!j.online){if(j.error==="SLOT_BUSY")return busy(j,false);return err(j.error)}
var p=+j.pulses||0;
if(j.state==="armed"){
if(!armed){armed=true;put("lsub",pn(plan)+" · Insert coin(s) now · Maglagay ng barya");show("lcd",1);
try{navigator.vibrate&&navigator.vibrate(80)}catch(e){}if(!p)say("Insert coin now")}
left=Math.max(+j.remaining||0,0);tot=p>0?IDLE:FIRST;bar();
if(!tk)tk=setInterval(function(){if(left>0)left--;bar()},1000)}
if(armed||p>0){put("lpes","₱"+p);put("lmin","= "+fmt(j.minutes)+" of Wi-Fi"+(p>0?" · add more coins, then tap Done":""))}
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p;
if(p>0&&!j.final)show("ldone",1);
if(j.final){fin=true;stop();show("lcd",0);show("ldone",0);show("lmis",0);put("lsub","Thank you! · Salamat!");
put("lpes",fmt(j.fleft));put("lmin","of Wi-Fi time left · ₱"+p+" = "+fmt(j.fwmin));put("lont","Enjoy browsing.");
show("lon",1);put("lcode",j.code||"");show("lfin",1);setTimeout(function(){say("You are online")},900);
try{navigator.vibrate&&navigator.vibrate([100,60,100])}catch(e){}return}
if(j.state==="done"||j.state==="none"){stop();
if(!p){show("lcd",0);show("ldone",0);put("lsub","No coins detected · Walang nabayaran");put("lpes","₱0");
put("lmin","You were not charged.");show("lagain",1)}else portal()}}
function poll(){fetch(base+"/api/status?sid="+SID,{cache:"no-store"}).then(function(r){return r.json()}).then(upd).catch(function(){})}
function watch(){
if(window.EventSource){try{es=new EventSource(base+"/stream?sid="+SID+"&mode=wait");
es.addEventListener("status",function(m){try{upd(JSON.parse(m.data))}catch(e){}});
es.onerror=function(){if(es){es.close();es=null}}}catch(e){es=null}}
/* The status is also asked for once a second, whatever the live stream does: some phone browsers hold a stream open
   without delivering anything, which looked like a page that only updates when it is reloaded. */
if(!pt)pt=setInterval(poll,1000);poll()}
function start(fq){
f.style.display="none";var n=document.querySelectorAll(".note"),s0=f.previousElementSibling,i;
for(i=0;i<n.length;i++)n[i].style.display="none";if(s0)s0.style.display="none";
show("live",1);show("lagain",0);put("lsub",pn(plan)+" · Getting the coin slot ready · Sandali lang");put("lpes","₱0");
put("lmin","Please wait. Do not insert coins yet · huwag pa maglagay ng barya.");
var gone=false,wd=setTimeout(function(){gone=true;legacy(fq)},10000);   /* no answer in 10 s: the regular pages take over */
fetch(base+"/api/start?sid="+SID+"&plan="+plan+(fq?"&forfeit=1":""),{cache:"no-store"}).then(function(r){return r.json()}).then(function(j){
clearTimeout(wd);if(gone)return;
if(j.state==="error"&&j.error==="PLAN_MISMATCH")return mismatch(j);
if(j.state==="error"&&(j.error==="SLOT_BUSY"||j.error==="COOLDOWN"))return busy(j,j.error==="COOLDOWN");
if(j.state==="error"||j.error)return legacy(fq);
upd(j);watch()}).catch(function(){clearTimeout(wd);if(!gone)legacy(fq)})}
function mismatch(j){var o=pn(j.plan),w=pn(plan);
put("lsub","You still have "+o+" time ("+fmt(Math.round((+j.remaining||0)/60))+")");put("lpes","");
put("lmin","Add more "+o+" time, or switch to "+w+": the time you have left is given up when you pay.");
var m=el("lmis");m.innerHTML='<button class="btn" type="button" id="mk">Add '+o+' time</button><button class="btn alt" type="button" id="ms">Switch to '+w+' · lose my time</button>';
show("lmis",1);el("mk").onclick=function(){show("lmis",0);plan=j.plan;start(false)};el("ms").onclick=function(){show("lmis",0);start(true)}}
el("ldone").onclick=function(){this.disabled=true;this.textContent="Closing · Sandali lang";
fetch(base+"/api/finish?sid="+SID,{cache:"no-store"}).then(function(r){return r.json()}).then(upd).catch(function(){})};
/* Sound and speech are switched on by a tap, but starting them (the phone's speech engine in particular) can freeze the page
   for a second or more: the screen changes and the slot is asked first, and they are prepared after the next paint. */
function prime(){
try{window.speechSynthesis&&speechSynthesis.speak(new SpeechSynthesisUtterance(""))}catch(x){}
try{if(A){ctx=window.__ctx=window.__ctx||new A();ctx.resume();var o=ctx.createOscillator(),g=ctx.createGain();g.gain.value=.04;
o.frequency.value=880;o.connect(g);g.connect(ctx.destination);o.start();o.stop(ctx.currentTime+.05)}}catch(x){}}
f.addEventListener("submit",function(e){e.preventDefault();
var r=f.querySelector("input[name=coinplan]:checked");plan=r?r.value:"hyper";start(false);
(window.requestAnimationFrame||setTimeout)(function(){setTimeout(prime,0)})})})();
</script>
HTML
}

page_wait() {
	pesos=$(printf '%s' "$cst" | jget pulses); mins=$(printf '%s' "$cst" | jget minutes); left=$(printf '%s' "$cst" | jget remaining)
	total="$infofirst"; [ "${pesos:-0}" -gt 0 ] && total="$infoidle"
	pct=$(( ${left:-0} * 100 / ${total:-30} )); [ "$pct" -gt 100 ] && pct=100; [ "$pct" -lt 0 ] && pct=0
	# The coin acceptor only takes coins once the box has armed it: until then say so and show no countdown.
	_ready=yes; [ "$(printf '%s' "$cst" | jget state)" = "starting" ] && _ready=no
	echo '<div id="wait">'
	if [ "$_ready" = yes ]; then
		echo "<p class=\"sub\" id=\"sub\">$(plan_name "$coinplan") &middot; Insert coin(s) now &middot; Maglagay ng barya</p>"
		_cd=""
	else
		echo "<p class=\"sub\" id=\"sub\">$(plan_name "$coinplan") &middot; Getting the coin slot ready &middot; Sandali lang</p>"
		_cd=' style="display:none"'
	fi
	cat << HTML
<div class="big" id="pes">&#8369;${pesos:-0}</div>
<p class="mut">= <span id="mins">$(fmt_min "${mins:-0}")</span> of Wi-Fi</p>
<div id="cd"$_cd><div class="bar"><i id="bar" style="width:${pct}%"></i></div>
<p class="mut"><span id="left">${left:-0}</span>s left &middot; the timer restarts with every coin</p></div>
HTML
	if [ "${pesos:-0}" -gt 0 ]; then action_button "Connect now" finish; else action_button "Cancel" finish alt; fi
	echo '</div>'
	# Live updates and a coin sound (Web Audio: no sound files to download). Browsers only allow sound after a tap, so a
	# small button turns it on. Without scripts the page falls back to a plain reload (see header()).
	waiturl="/opennds_preauth/?fas=$(fas_urlsafe)&coinact=wait&coinplan=$coinplan"
	cat << HTML
<button class="snd" id="snd" type="button">&#128276; Tap for coin sound</button>
<script>
(function(){
var url="$waiturl",pes=${pesos:-0},ctx=null,b=document.getElementById("snd"),A=window.AudioContext||window.webkitAudioContext;
function tone(f,t,d){var o=ctx.createOscillator(),g=ctx.createGain();o.type="triangle";o.frequency.value=f;
g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.35,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+d);
o.connect(g);g.connect(ctx.destination);o.start(t);o.stop(t+d+.05)}
function ding(n){if(!ctx||ctx.state!=="running")return;var t=ctx.currentTime;n=Math.min(n,4);document.body.setAttribute("data-dings",(+document.body.getAttribute("data-dings")||0)+1);
for(var i=0;i<n;i++){tone(988,t+i*.22,.12);tone(1319,t+i*.22+.1,.3)}}
function on(){if(!A)return;ctx=ctx||window.__ctx||new A();window.__ctx=ctx;ctx.resume();
b.style.display="none";ding(1)}
b.onclick=on;
try{if(A){ctx=window.__ctx||new A();window.__ctx=ctx;ctx.resume();setTimeout(function(){if(ctx.state==="running")b.style.display="none"},50)}}catch(e){}
/* Update fields in place and only when they changed: replacing the panel (or the button's text) would swallow a tap
   that is in progress. */
function put(id,t){var e=document.getElementById(id);if(e&&e.textContent!==t)e.textContent=t}
function poll(){var x=new XMLHttpRequest();x.open("GET",url+"&_="+Date.now());x.onload=function(){
var d=new DOMParser().parseFromString(x.responseText,"text/html"),n=d.getElementById("wait");
if(!n||!d.getElementById("pes")){location.replace(url);return}
var g=function(i){var e=d.getElementById(i);return e?e.textContent:""},cd=document.getElementById("cd"),dc=d.getElementById("cd"),sb=document.getElementById("sub"),ds=d.getElementById("sub"),
btn=document.querySelector("#wait button.btn"),db=d.querySelector("#wait button.btn");
put("pes",g("pes"));put("mins",g("mins"));put("left",g("left"));
var bar=document.getElementById("bar"),db2=d.getElementById("bar");if(bar&&db2&&bar.style.width!==db2.style.width)bar.style.width=db2.style.width;
if(cd&&dc&&cd.style.display!==dc.style.display)cd.style.display=dc.style.display;
if(sb&&ds&&sb.innerHTML!==ds.innerHTML)sb.innerHTML=ds.innerHTML;
if(btn&&db&&!btn.form.__t&&btn.textContent!==db.textContent){btn.textContent=db.textContent;btn.className=db.className}
var p=parseInt(g("pes").replace(/[^0-9]/g,""),10)||0;
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p};x.send()}
function say(t){try{if(window.speechSynthesis){speechSynthesis.cancel();var u=new SpeechSynthesisUtterance(t);u.lang="en-US";speechSynthesis.speak(u)}}catch(e){}}
function fmt(m){if(m<60)return m+" min";var h=Math.floor(m/60),r=m%60;return h+(h>1?" hrs":" hr")+(r?" "+r+" min":"")}
function show(j){if(j.state==="done"||j.state==="error"||j.state==="none"){location.replace(url);return}
var sb=document.getElementById("sub"),cd=document.getElementById("cd");
if(j.state==="armed"&&cd&&cd.style.display==="none"){cd.style.display="";if(sb)sb.innerHTML="$(plan_name "$coinplan") &middot; Insert coin(s) now &middot; Maglagay ng barya";try{navigator.vibrate&&navigator.vibrate(80)}catch(e){}say("Insert coin now")}
var p=+j.pulses||0,t=p>0?$infoidle:$infofirst,e=document.getElementById("pes"),btn=document.querySelector("#wait button.btn");
if(!e)return;put("pes","\u20b1"+p);put("mins",fmt(+j.minutes||0));put("left",""+Math.max(+j.remaining||0,0));
var w=Math.max(0,Math.min(100,100*(+j.remaining||0)/t))+"%",bar=document.getElementById("bar");if(bar&&bar.style.width!==w)bar.style.width=w;
var want=p>0?"Connect now":"Cancel";if(btn&&!btn.form.__t&&btn.textContent!==want){btn.textContent=want;btn.className=p>0?"btn":"btn alt"}
if(p>pes){ding(p-pes);say(p+(p===1?" peso":" pesos"))}pes=p}
var sp=${infostream:-0},es=null,got=false,polling=false,last=0;
function fallback(){if(es){es.close();es=null}if(!polling){polling=true;setInterval(poll,2000)}}
if(window.EventSource&&sp){try{es=new EventSource("http://"+location.hostname+":"+sp+"/stream?sid=$sid&mode=wait");
es.addEventListener("status",function(m){got=true;last=Date.now();try{show(JSON.parse(m.data))}catch(e){}});
es.onerror=function(){if(!got||es.readyState===2)fallback();else setTimeout(function(){if(es&&Date.now()-last>8000)fallback()},8000)}}catch(e){es=null}}
if(!es)fallback()})();
</script>
HTML
}

page_counting() {
	echo '<p class="big">Counting coins&hellip;</p><p class="mut">One moment &middot; sandali lang</p>'
}

page_busy() {
	if [ "$err" = COOLDOWN ]; then
		echo "<div class=\"msg\"><b>Too many empty tries &middot; Sobrang daming walang barya</b><br>Please wait about ${busyretry:-60} seconds before starting again &middot; Maghintay muna ng ${busyretry:-60} segundo.</div>"
		action_button "Try again" start
		return
	fi
	cat << HTML
<div class="msg"><b>Coin slot in use &middot; Ginagamit ang coin slot</b><br>Another customer is paying right now. Please try again in about ${busyretry:-15} seconds &middot; Subukan ulit pagkalipas ng ${busyretry:-15} segundo.</div>
HTML
	action_button "Try again" start
}

page_mismatch() {
	newplan="$coinplan"
	cat << HTML
<div class="msg"><b>You still have $(plan_name "$otherplan") time ($(fmt_min $(( ${otherleft:-0} / 60 )))).</b><br>
Add more $(plan_name "$otherplan") time, or switch to $(plan_name "$newplan"). <b>If you switch, the time you have left is forfeited</b> as soon as you pay.</div>
HTML
	plan_button "Add $(plan_name "$otherplan") time" "$otherplan" "" ""
	plan_button "Switch to $(plan_name "$newplan") &middot; lose my time" "$newplan" forfeit "alt"
}

page_error() {
	echo "<div class=\"msg\"><b>Coin payment is not available right now.</b><br>Please try again or ask the attendant. ($err)</div>"
	action_button "Try again" start alt
}

page_unavailable() {
	echo '<div class="msg"><b>Coin payment is offline.</b><br>Please ask the attendant, or try again in a moment &middot; Pakisabihan ang attendant.</div>'
	cat << HTML
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas"><button class="btn alt" type="submit">Try again</button></form>
HTML
}

page_voucherform() {
	if [ -n "$vcode" ]; then
		case "$vrc" in
			3) _m="That code has run out of time." ;;
			7) _m="This device already has its own time. Use it up first, or use the code on another device." ;;
			8) _m="Too many wrong tries. Please wait a few minutes." ;;
			5) _m="The system is busy. Please try again in a moment." ;;
			*) _m="That code was not found. Check it and try again." ;;
		esac
		echo "<div class=\"msg\"><b>$_m</b></div>"
	fi
	cat << HTML
<p class="sub">Restore your time with your code &middot; Ilagay ang code</p>
<form action="/opennds_preauth/" method="get"><input type="hidden" name="fas" value="$fas"><input type="hidden" name="coinact" value="voucher">
<input type="text" name="vcode" maxlength="9" autocomplete="off" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="abcd-1234" oninput="this.value=this.value.toLowerCase()">
<button class="btn" type="submit">Use code</button></form>
<p class="note"><a href="/opennds_preauth/?fas=$(fas_urlsafe)">Back</a></p>
HTML
}

# Coins counted, waiting to be used: what was paid, what the device still had, and the button that connects it.
page_result() {
	ver=$(coinslot "/verify?sid=$sid")
	if [ "$(printf '%s' "$ver" | jget ok)" != "true" ]; then
		echo '<p class="big">No coins detected</p><p class="mut">You were not charged &middot; Walang nabayaran</p>'
		coinplan="hyper"; action_button "Try again" "" alt
		return
	fi
	vplan=$(printf '%s' "$ver" | jget plan); vpulses=$(printf '%s' "$ver" | jget pulses); vmin=$(printf '%s' "$ver" | jget minutes)
	leftmin=0; label="Connect"; forfeitnote=""; vwid=$(printf '%s' "$ver" | jget wid)
	flash_peek "$mac"
	if [ -n "$vwid" ] && [ "$R_WID" = "$vwid" ] && [ "$P_STATE" = running ]; then
		# The router already recorded this window (granted when the window closed): show the total, add nothing again.
		cat << HTML
<p class="sub">$(plan_name "$vplan") &middot; Thank you! &middot; Salamat!</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">of Wi-Fi time left &middot; &#8369;$vpulses = $(fmt_min "$vmin") added</p>
HTML
		coinplan="$vplan"; action_button "Continue" connect "" landing
		return
	fi
	if [ "$P_STATE" = running ] || [ "$P_STATE" = paused ]; then
		if [ "$R_PLAN" = "$vplan" ]; then
			leftmin=$(left_min "$R_LEFT"); label="Add time"
			[ "$P_STATE" = paused ] && label="Resume"
		else
			forfeitnote="<p class=\"mut\">Your time on the other plan ($(plan_name "$R_PLAN")) is replaced by this one.</p>"
			coinforfeit="yes"
		fi
	fi
	what="&#8369;$vpulses = $(fmt_min "$vmin")"
	[ "$leftmin" -gt 0 ] && what="$what + $(fmt_min "$leftmin") you still had"
	cat << HTML
<p class="sub">$(plan_name "$vplan") &middot; Thank you! &middot; Salamat!</p>
<div class="big">$(fmt_min $((vmin + leftmin)))</div>
<p class="mut">$what</p>
$forfeitnote
HTML
	coinplan="$vplan"; action_button "$label" connect "" landing
}

# A returning device with time left (after a reboot or power cut): connected again without asking for anything.
page_reconnect() {
	flash_session "$mac"; src=$?
	if [ "$src" = 0 ]; then
		grant_access
		if [ "$ndsstatus" = "authenticated" ]; then
			originurl=$(printf "${originurl//%/\\x}")
			cat << HTML
<p class="sub">Welcome back &middot; Welcome ulit</p>
<div class="big">$(fmt_min "$S_MIN")</div>
<p class="mut">left on this device. You were reconnected automatically.</p>
<a class="btn" style="text-decoration:none;text-align:center" href="$originurl">Continue browsing</a>
HTML
			return
		fi
	fi
	PAGE="welcome"; page_welcome
}

page_pausedhome() {
	cat << HTML
<p class="sub">Your time is paused &middot; Naka-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">is saved for you. Tap Resume to continue.</p>
HTML
	coinplan="$R_PLAN"; action_button "Resume" connect "" landing
}

page_restored() {
	label="Connect"; [ "$P_STATE" = paused ] && label="Resume"
	cat << HTML
<p class="sub">Code accepted &middot; Tanggap ang code</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">$(plan_name "$R_PLAN") time moved to this device.</p>
HTML
	coinplan="$R_PLAN"; action_button "$label" connect "" landing
}

# Authenticate the device for what flash_session (S_*) found. A connected device is de-authenticated and granted again
# (openNDS ignores a plain auth of an authenticated client); anyone else goes through the normal auth_log.
grant_access() {
	sessiontimeout="$S_MIN"; upload_rate="$S_UP"; download_rate="$S_DOWN"; upload_quota="$S_QUP"; download_quota="$S_QDOWN"
	quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"
	userinfo="$title - $S_CODE"
	if [ "$(nds_state "$mac")" = "Authenticated" ]; then
		if nds_regrant "$mac" "$S_MIN" "$S_UP" "$S_DOWN" "$S_QUP" "$S_QDOWN"; then ndsstatus="authenticated"; else ndsstatus="failed"; fi
	else
		auth_log
	fi
}

# libopennds.sh calls check_authenticated() before generate_splash_sequence().
check_authenticated() { return 0; }

# libopennds.sh calls landing_page() when the form was submitted with landing=yes: the device taps Connect / Add time / Resume.
landing_page() {
	originurl=$(printf "${originurl//%/\\x}")
	gatewayurl=$(printf "${gatewayurl//%/\\x}")
	configure_log_location
	. $mountpoint/ndscids/ndsinfo

	sid=$(coinslot_sid); mac="$clientmac"
	forfeit=0; [ "$coinforfeit" = "yes" ] && forfeit=1
	ver=$(coinslot "/verify?sid=$sid"); paid=no; mrc=0
	if [ "$(printf '%s' "$ver" | jget ok)" = "true" ]; then
		paid=yes
		# Record the payment first (idempotent: the same coin window is never credited twice), then grant access.
		flash_mint "$mac" "$(printf '%s' "$ver" | jget wid)" "$(printf '%s' "$ver" | jget plan)" "$(printf '%s' "$ver" | jget pulses)" \
			"$(printf '%s' "$ver" | jget minutes)" "$(printf '%s' "$ver" | jget up)" "$(printf '%s' "$ver" | jget down)" "$forfeit"
		mrc=$?
	fi
	if [ "$mrc" = 6 ]; then
		cat << HTML
<div class="msg"><b>You still have time on the other plan.</b><br>Using these coins means switching plan: <b>the time you have left is forfeited</b>. Your coins stay safe until you choose.</div>
HTML
		coinplan="$(printf '%s' "$ver" | jget plan)"; coinforfeit="yes"
		action_button "Switch plan &middot; lose my old time" connect "" landing
		coinforfeit=""; action_button "Not now" "" alt
		footer
	fi
	src=5
	[ "$mrc" = 0 ] && { flash_session "$mac"; src=$?; }
	if [ "$src" = 0 ]; then
		# The router usually has done all of this already (the window settled and was granted when it closed):
		# then nothing is granted again, so the connection is not interrupted.
		if [ "$paid" = yes ] && { [ "$M_MODE" = dup ] || [ "$M_MODE" = update ]; } && [ "$(nds_state "$mac")" = "Authenticated" ] &&
			[ "$(printf '%s' "$ver" | jget claimed)" = "true" ]; then
			ndsstatus="authenticated"
		else
			grant_access
		fi
		if [ "$ndsstatus" = "authenticated" ]; then
			# Only after access was really granted is the payment marked as used on the box.
			[ "$paid" = yes ] && coinslot "/ack?sid=$sid" > /dev/null
			cat << HTML
<p class="sub">$(plan_name "$S_PLAN") &middot; Connected &middot; Nakakonekta na</p>
<div class="big">$(fmt_min "$S_MIN")</div>
<p class="mut">of Wi-Fi time. Enjoy! &middot; Salamat!</p>
<p class="mut">Save your code. It restores your time on any device:</p>
<div class="code">$S_CODE</div>
<a class="btn" style="text-decoration:none;text-align:center" href="http://$gatewayfqdn/?$randquery">Continue</a>
<script>try{var A=window.AudioContext||window.webkitAudioContext,c=new A();c.resume();setTimeout(function(){if(c.state==="running"){[523,659,784,1047].forEach(function(f,i){var o=c.createOscillator(),g=c.createGain(),t=c.currentTime+i*.12;o.type="triangle";o.frequency.value=f;g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(.3,t+.01);g.gain.exponentialRampToValueAtTime(.0001,t+.3);o.connect(g);g.connect(c.destination);o.start(t);o.stop(t+.35)})}},60)}catch(e){}</script>
HTML
			footer
		fi
	fi
	if [ "$src" = 2 ] || [ "$src" = 3 ]; then
		echo '<div class="msg"><b>No paid time found for this device.</b><br>Insert a coin to buy time, or restore your time with your code.</div>'
		coinplan="hyper"; action_button "Back" "" alt
	else
		echo '<div class="msg"><b>We could not start your session.</b><br>Your coins are safe. Tap Try again; if it keeps failing, ask the attendant.</div>'
		coinplan="${S_PLAN:-hyper}"; action_button "Try again" connect "" landing
	fi
	footer
}

page_pausecheck() {
	cat << HTML
<p class="sub">Pause your time &middot; I-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">would be saved. You can pause only <b>once</b> and must resume within ${pausehours:-72} hours. Your device disconnects until you resume.</p>
HTML
	action_button "Yes, pause my time" pause
	action_button "Keep using Wi-Fi" "" alt
}

page_paused() {
	cat << HTML
<p class="sub">Time paused &middot; Naka-pause ang oras</p>
<div class="big">$(fmt_min "$(left_min "$R_LEFT")")</div>
<p class="mut">saved. To continue, join this Wi-Fi again and tap <b>Resume</b> (or use your code on any device):</p>
<div class="code">$R_CODE</div>
HTML
}

page_pauseerr() {
	case "$prc" in
		7) m="You already used your one pause." ;;
		9) m="Pause is only available for Endurance time of &#8369;${pausepesos:-10} or more." ;;
		5) m="The system is busy. Please try again." ;;
		*) m="Could not pause right now. Please try again." ;;
	esac
	echo "<div class=\"msg\"><b>$m</b></div>"
	action_button "Back" "" alt
}

display_terms() {
	cat << HTML
<div class="msg" style="border-color:var(--mut)"><b>Terms of Service</b><br>
Access is paid for in coins and lasts for the time shown when you connect. Coins are not refundable once a session has started.
HyperSpeed may be slowed temporarily after heavy use (fair use). Do not misuse the connection. The owners may end a session at any time.</div>
<button class="btn alt" type="button" onclick="history.go(-1)">Back</button>
HTML
	footer
}

#### end of functions ####

#################################################
# Main entry point of this Theme: parameters set here override those in libopennds.sh
#################################################

randquery="$(date | sha256sum | awk '{printf "%s", $1}')"

# Session length and speed are set per customer in landing_page() from the manager's grant.
sessiontimeout="0"
upload_rate="0"
download_rate="0"
upload_quota="0"
download_quota="0"
quotas="$sessiontimeout $upload_rate $download_rate $upload_quota $download_quota"

ndscustomparams=""
ndscustomimages=""
ndscustomfiles=""
ndsparamlist="$ndsparamlist $ndscustomparams $ndscustomimages $ndscustomfiles"

# Extra form variables used by this theme (names kept distinct: the parser matches them as substrings).
additionalthemevars="coinact coinplan coinforfeit vcode"
fasvarlist="$fasvarlist $additionalthemevars"

userinfo="$title"
#@@FILE /usr/lib/opennds/flash_coin_lib.sh 755
#!/bin/sh
# Shared helpers of the "flash coin" portal: the voucher roll (the one file that records every paid session), the
# coin-slot manager client, and the openNDS wrapper. Sourced by flash_coin.sh (the portal theme), flash_coin_status.sh
# (the status page) and flash_fairuse.sh. Install next to them in /usr/lib/opennds/.
#
# The roll is a CSV, one line per device:
#   code,rate_down,rate_up,quota_down,quota_up,time_limit_min,first_punched,mac,pauses_used,paused_at,remaining_at_pause,plan,wid,pesos,wmin,wpesos,wfinal
# The first 11 fields are exactly the voucher roll of the paper-voucher theme this one grew from; plan, wid (the id of
# the coin window that paid, so one window can never be credited twice) and pesos are appended, then the share of the
# last window (wmin minutes, wpesos) and whether that window is final: a window is recorded once, priced as a whole, when it
# closes (the library also accepts an interim, non-final write for the same window, which a later write replaces). Expiry is always
# first_punched + time_limit*60. A top-up adds to time_limit; a pause freezes remaining_at_pause and a resume moves
# first_punched forward by the time spent paused.
#
# Flash wear: the roll is written only when somebody pays, pauses or resumes (a few times a day), never on a page view.
# The lock lives in /tmp. Set FLASH_ROLL in /etc/flash_coin.conf to move the roll (for example onto a USB stick).

FLASH_CONF="${FLASH_CONF:-/etc/flash_coin.conf}"
[ -r "$FLASH_CONF" ] && . "$FLASH_CONF"
FLASH_ROLL="${FLASH_ROLL:-/etc/coinslot.d/vouchers.txt}"
FLASH_REVENUE="${FLASH_REVENUE:-$(dirname "$FLASH_ROLL")/revenue.csv}"
FLASH_LOCK="${FLASH_LOCK:-/tmp/flash_coin.lock}"
FLASH_TMP="${FLASH_TMP:-/tmp/flash_coin}"
COINSLOT_URL="${COINSLOT_URL:-http://127.0.0.1:8099}"
NDSCTL="${NDSCTL:-ndsctl}"

now_ts() { date +%s; }
lc() { printf '%s' "$1" | tr 'A-Z' 'a-z'; }
mac_key() { printf '%s' "$1" | tr 'A-F' 'a-f' | tr -d ':'; }

# ---------------------------------------------------------------------------
# Coin-slot manager (local listener)
# ---------------------------------------------------------------------------
coinslot() {  # coinslot <path>: JSON/text answer, empty if the manager is not running
	# socat starts in milliseconds; curl/wget take about half a second just to start on the router (TLS library).
	if command -v socat > /dev/null 2>&1; then
		_h="${COINSLOT_URL#http://}"
		printf 'GET %s HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "$1" "${_h%%:*}" |
			socat -t10 -T10 - "TCP:$_h,shut-none" 2> /dev/null | tr -d '\r' | sed '1,/^$/d'
	elif command -v curl > /dev/null 2>&1; then
		curl -sS -m 10 "$COINSLOT_URL$1" 2> /dev/null
	else
		wget -qO- -T 10 "$COINSLOT_URL$1" 2> /dev/null
	fi
}
jget() { sed -n 's/.*"'"$1"'" *: *"\{0,1\}\([^",}]*\).*/\1/p' | head -n 1; }

# flash_info: reads /info into infofirst infoidle infostream pausehours pausepesos fair edown eup fairkb fairdown ...
flash_info() {
	info=$(coinslot /info)
	[ -n "$info" ] || return 1
	infoidle=$(printf '%s' "$info" | jget idle); infofirst=$(printf '%s' "$info" | jget first)
	pausehours=$(printf '%s' "$info" | jget pause_hours); pausepesos=$(printf '%s' "$info" | jget pause_pesos)
	infostream=$(printf '%s' "$info" | jget stream_port); fair=$(printf '%s' "$info" | jget fair_gb)
	edown=$(printf '%s' "$info" | jget e_down); eup=$(printf '%s' "$info" | jget e_up)
	fairkb=$(printf '%s' "$info" | jget fair_kb); fairdown=$(printf '%s' "$info" | jget fair_down)
	fairup=$(printf '%s' "$info" | jget fair_up); fairthr=$(printf '%s' "$info" | jget fair_throttle_min)
	fairfull=$(printf '%s' "$info" | jget fair_full_min)
	return 0
}

# ---------------------------------------------------------------------------
# openNDS
# ---------------------------------------------------------------------------
# nds_do <ndsctl args>: output in NDSOUT; retries while openNDS answers "locked" (it is busy with another request).
nds_do() {
	_i=0
	while [ "$_i" -lt 4 ]; do
		NDSOUT=$("$NDSCTL" "$@" 2>&1)
		case "$NDSOUT" in *locked*) _i=$((_i + 1)); sleep 1 ;; *) return 0 ;; esac
	done
	return 1
}
nds_state() { nds_do json "$1"; printf '%s' "$NDSOUT" | sed -n 's/.*"state": *"\([^"]*\)".*/\1/p' | head -n 1; }  # Authenticated | Preauthenticated | ""
# nds_regrant <mac> <minutes> <up> <down> [up quota] [down quota]: a plain auth of an authenticated client is ignored by
# openNDS, so the client is de-authenticated first (this also makes new speed caps apply to connections already open).
nds_regrant() {
	nds_do deauth "$1"
	nds_do auth "$1" "$2" "$3" "$4" "${5:-0}" "${6:-0}"
	case "$NDSOUT" in *Failed*) return 1 ;; esac
	return 0
}

# ---------------------------------------------------------------------------
# Roll: lock, parse, find, write
# ---------------------------------------------------------------------------
roll_lock() {
	mkdir -p "$(dirname "$FLASH_ROLL")" 2> /dev/null
	_w=0
	while ! mkdir "$FLASH_LOCK" 2> /dev/null; do
		_t=$(cat "$FLASH_LOCK/ts" 2> /dev/null)
		if [ -n "$_t" ] && [ $(($(now_ts) - _t)) -ge 10 ]; then rm -rf "$FLASH_LOCK"; continue; fi   # left by a killed process
		_w=$((_w + 1))
		if [ "$_w" -ge 6 ]; then
			# a lock with no timestamp is one whose owner died between mkdir and writing it
			if [ -z "$_t" ]; then rm -rf "$FLASH_LOCK"; mkdir "$FLASH_LOCK" 2> /dev/null && { now_ts > "$FLASH_LOCK/ts"; return 0; }; fi
			return 1
		fi
		sleep 1
	done
	now_ts > "$FLASH_LOCK/ts" 2> /dev/null
	return 0
}
roll_unlock() { rm -rf "$FLASH_LOCK" 2> /dev/null; }

# roll_parse <line>: sets R_CODE R_DOWN R_UP R_QDOWN R_QUP R_TL R_FP R_MAC R_PU R_PA R_RP R_PLAN R_WID R_PESOS
roll_parse() {
	IFS=, read -r R_CODE R_DOWN R_UP R_QDOWN R_QUP R_TL R_FP R_MAC R_PU R_PA R_RP R_PLAN R_WID R_PESOS R_WMIN R_WP R_WF << EOF
$1
EOF
	R_DOWN="${R_DOWN:-0}"; R_UP="${R_UP:-0}"; R_QDOWN="${R_QDOWN:-0}"; R_QUP="${R_QUP:-0}"; R_TL="${R_TL:-0}"; R_FP="${R_FP:-0}"
	R_PU="${R_PU:-0}"; R_PA="${R_PA:-0}"; R_RP="${R_RP:-0}"; R_PLAN="${R_PLAN:-hyper}"; R_PESOS="${R_PESOS:-0}"
	R_WMIN="${R_WMIN:-0}"; R_WP="${R_WP:-0}"; R_WF="${R_WF:-1}"
}
roll_line() {  # the current R_* values as a roll line
	printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s' "$R_CODE" "$R_DOWN" "$R_UP" "$R_QDOWN" "$R_QUP" "$R_TL" "$R_FP" "$R_MAC" "$R_PU" "$R_PA" "$R_RP" "$R_PLAN" "$R_WID" "$R_PESOS" "$R_WMIN" "$R_WP" "$R_WF"
}
roll_find_mac() { [ -f "$FLASH_ROLL" ] && awk -F, -v m="$(lc "$1")" 'tolower($8)==m {print; exit}' "$FLASH_ROLL"; }
roll_find_code() { [ -f "$FLASH_ROLL" ] && awk -F, -v c="$(lc "$1")" 'tolower($1)==c {print; exit}' "$FLASH_ROLL"; }
# roll_put: replace (or append) the line of R_CODE with the current R_* values. Atomic: temp file in the same directory, then mv.
roll_put() {
	[ -f "$FLASH_ROLL" ] || : > "$FLASH_ROLL"
	RL_CODE="$R_CODE" RL_LINE="$(roll_line)" awk -F, 'BEGIN{c=ENVIRON["RL_CODE"]; l=ENVIRON["RL_LINE"]}
		$1==c { if (!d) print l; d=1; next } { print } END { if (!d) print l }' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" && mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"
}
roll_del() {  # roll_del <code>
	[ -f "$FLASH_ROLL" ] || return 0
	RL_CODE="$1" awk -F, '$1!=ENVIRON["RL_CODE"]' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" && mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"
}
new_code() {  # xxxx-xxxx over a-z0-9, not already on the roll
	while :; do
		_c=$(head -c 256 /dev/urandom | tr -dc 'a-z0-9' | cut -c1-8)
		if [ "${#_c}" -eq 8 ]; then
			_c="$(printf '%s' "$_c" | cut -c1-4)-$(printf '%s' "$_c" | cut -c5-8)"
			[ -n "$(roll_find_code "$_c")" ] || { echo "$_c"; return; }
		fi
	done
}

# After roll_parse: R_END (expiry epoch of a running session), R_LEFT (seconds of paid time left; the frozen amount when
# paused). Returns 0 if there is any time left.
roll_calc() {
	R_END=$((R_FP + R_TL * 60))
	if [ "$R_PA" != 0 ]; then R_LEFT="$R_RP"; else R_LEFT=$((R_END - $(now_ts))); fi
	[ "$R_LEFT" -lt 0 ] && R_LEFT=0
	[ "$R_LEFT" -gt $((R_TL * 60)) ] && R_LEFT=$((R_TL * 60))   # a clock that jumped back can never add time
	[ "$R_LEFT" -gt 0 ]
}
left_min() { echo $(((${1:-0} + 59) / 60)); }

# ---------------------------------------------------------------------------
# Paying: flash_mint
# ---------------------------------------------------------------------------
# flash_mint <mac> <wid> <plan> <pulses> <minutes> <up> <down> <forfeit 0|1> [final 1|0]
# Records a verified coin payment: <pulses> and <minutes> are the window's whole total so far, never a delta. Writing the
# same window again replaces its earlier share (an interim write, then the whole window priced once at the end); a final window is never changed again. Revenue is logged once, when the window is final.
# Sets M_CODE and M_MODE (new | topup | switch | update | dup). Returns 0, 5 (roll busy), 6 (the device has live time on
# the other plan and did not agree to give it up).
flash_mint() {
	m_mac=$(lc "$1"); m_wid="$2"; m_plan="$3"; m_pulses="$4"; m_min="$5"; m_up="$6"; m_down="$7"; m_forfeit="$8"; m_final="${9:-1}"
	roll_lock || return 5
	_n=$(now_ts); M_MODE=new; M_CODE=""
	_line=$(roll_find_mac "$m_mac")
	if [ -n "$_line" ]; then
		roll_parse "$_line"
		if [ -n "$m_wid" ] && [ "$R_WID" = "$m_wid" ]; then
			if [ "$R_WF" = 1 ]; then M_MODE=dup; M_CODE="$R_CODE"; roll_unlock; return 0; fi   # credited and closed already
			_dm=$((m_min - R_WMIN)); _dp=$((m_pulses - R_WP))                                    # the same window, more coins
			R_TL=$((R_TL + _dm)); R_PESOS=$((R_PESOS + _dp)); [ "$R_PA" != 0 ] && R_RP=$((R_RP + _dm * 60))
			R_WMIN="$m_min"; R_WP="$m_pulses"; R_WF="$m_final"
			roll_put
			M_MODE=update; M_CODE="$R_CODE"
			[ "$m_final" = 1 ] && flash_revenue "$_n" "$m_plan" "$m_pulses" "$m_min" window
			roll_unlock
			return 0
		fi
		if roll_calc; then
			if [ "$R_PLAN" != "$m_plan" ]; then
				if [ "$m_forfeit" != 1 ]; then roll_unlock; return 6; fi
				roll_del "$R_CODE"; rm -f "$FLASH_TMP/fair/$(mac_key "$m_mac")"; M_MODE=switch
			else
				M_MODE=topup
			fi
		else
			roll_del "$R_CODE"      # ran out: this payment starts a new session
		fi
	fi
	if [ "$M_MODE" = topup ]; then
		R_TL=$((R_TL + m_min)); R_PESOS=$((R_PESOS + m_pulses)); R_WID="$m_wid"
		[ "$R_PA" != 0 ] && R_RP=$((R_RP + m_min * 60))
	else
		R_CODE=$(new_code); R_DOWN="$m_down"; R_UP="$m_up"; R_QDOWN=0; R_QUP=0; R_TL="$m_min"; R_FP="$_n"; R_MAC="$m_mac"
		R_PU=0; R_PA=0; R_RP=0; R_PLAN="$m_plan"; R_WID="$m_wid"; R_PESOS="$m_pulses"
	fi
	R_WMIN="$m_min"; R_WP="$m_pulses"; R_WF="$m_final"
	roll_put
	M_CODE="$R_CODE"
	[ "$m_final" = 1 ] && flash_revenue "$_n" "$m_plan" "$m_pulses" "$m_min" "$M_MODE"
	roll_unlock
	return 0
}
# Each revenue line ends with a short hash over the previous line's hash and its own text, so edited, removed or inserted
# lines are detectable (flash_verify). The first line chains from "0".
flash_chain() {  # flash_chain <prev> <line>
	printf '%s|%s' "$1" "$2" | sha256sum | cut -c1-12
}
flash_revenue() {  # flash_revenue <time> <plan> <pesos> <minutes> <kind>
	mkdir -p "$(dirname "$FLASH_REVENUE")" 2> /dev/null
	_rl="$1,$2,$3,$4,$5"
	_prev=$(tail -n 1 "$FLASH_REVENUE" 2> /dev/null | awk -F, '{print $6}'); [ -n "$_prev" ] || _prev=0
	echo "$_rl,$(flash_chain "$_prev" "$_rl")" >> "$FLASH_REVENUE"
}
# flash_verify: prints "OK <lines> <pesos>" or "BAD <line number>". Lines without a hash (older versions) are skipped.
flash_verify() {
	[ -r "$FLASH_REVENUE" ] || { echo "OK 0 0"; return 0; }
	_prev=0; _i=0; _sum=0
	while IFS= read -r _l; do
		_i=$((_i + 1))
		case "$_l" in *,*,*,*,*,*) ;; *) continue ;; esac
		_h="${_l##*,}"; _t="${_l%,*}"
		[ "$(flash_chain "$_prev" "$_t")" = "$_h" ] || { echo "BAD $_i"; return 1; }
		_prev="$_h"; _p=$(echo "$_t" | cut -d, -f3); _sum=$((_sum + _p))
	done < "$FLASH_REVENUE"
	echo "OK $_i $_sum"
}

# ---------------------------------------------------------------------------
# Granting access
# ---------------------------------------------------------------------------
# flash_session <mac>: what the device may use right now. Sets S_MIN (minutes), S_UP S_DOWN S_QUP S_QDOWN, S_CODE, S_PLAN.
# A paused session is resumed here (first_punched moves forward by the time spent paused). Returns 0, 2 (nothing on the
# roll), 3 (ran out; the line is deleted), 5 (roll busy).
flash_session() {
	roll_lock || return 5
	_line=$(roll_find_mac "$1")
	if [ -z "$_line" ]; then roll_unlock; return 2; fi
	roll_parse "$_line"
	if ! roll_calc; then roll_del "$R_CODE"; roll_unlock; return 3; fi
	if [ "$R_PA" != 0 ]; then
		R_FP=$((R_FP + $(now_ts) - R_PA)); R_PA=0; R_RP=0
		roll_put
	fi
	S_MIN=$(left_min "$R_LEFT"); S_UP="$R_UP"; S_DOWN="$R_DOWN"; S_QUP="$R_QUP"; S_QDOWN="$R_QDOWN"; S_CODE="$R_CODE"; S_PLAN="$R_PLAN"
	roll_unlock
	return 0
}

# flash_peek <mac>: read-only look at a device's line (no lock, no changes). Sets the R_* values plus R_END R_LEFT and
# P_STATE = none | running | paused | expired. Returns 1 when the device has no line.
flash_peek() {
	P_STATE=none
	_line=$(roll_find_mac "$1")
	[ -n "$_line" ] || return 1
	roll_parse "$_line"
	if roll_calc; then
		if [ "$R_PA" != 0 ]; then P_STATE=paused; else P_STATE=running; fi
	else P_STATE=expired; fi
	return 0
}

# flash_pause <mac> <min pesos>: returns 0 ok, 2 no session, 5 busy, 6 already paused, 7 the one pause was used,
# 9 not eligible (Endurance sessions that paid at least <min pesos> only).
flash_pause() {
	roll_lock || return 5
	_line=$(roll_find_mac "$1")
	[ -n "$_line" ] || { roll_unlock; return 2; }
	roll_parse "$_line"
	if ! roll_calc; then roll_unlock; return 2; fi
	if [ "$R_PA" != 0 ]; then roll_unlock; return 6; fi
	if [ "$R_PU" -ge 1 ]; then roll_unlock; return 7; fi
	if [ "$R_PLAN" != endurance ] || [ "$R_PESOS" -lt "${2:-10}" ]; then roll_unlock; return 9; fi
	R_PU=$((R_PU + 1)); R_PA=$(now_ts); R_RP="$R_LEFT"
	roll_put
	roll_unlock
	nds_do deauth "$1"
	return 0
}

# flash_restore <code> <mac>: move a session to this device by its code. Returns 0, 1 (not a code), 2 (unknown), 3 (ran
# out), 5 busy, 7 this device already has time of its own, 8 too many wrong tries.
flash_restore() {
	_c=$(lc "$1"); _m=$(lc "$2")
	case "$_c" in ????-????) ;; *) return 1 ;; esac
	case "$_c" in *[!a-z0-9-]*) return 1 ;; esac
	mkdir -p "$FLASH_TMP"; _f="$FLASH_TMP/fails"; _n=$(now_ts)
	if [ -r "$_f" ] && [ "$(awk -v t=$((_n - 600)) '$1 > t' "$_f" | wc -l)" -ge 10 ]; then return 8; fi
	roll_lock || return 5
	_line=$(roll_find_code "$_c")
	if [ -z "$_line" ]; then roll_unlock; echo "$_n" >> "$_f"; return 2; fi
	roll_parse "$_line"
	if ! roll_calc; then roll_del "$R_CODE"; roll_unlock; return 3; fi
	if [ "$(lc "$R_MAC")" != "$_m" ]; then
		_keep="$R_CODE"; _old="$R_MAC"
		_own=$(roll_find_mac "$_m")
		if [ -n "$_own" ]; then
			roll_parse "$_own"
			if roll_calc; then roll_unlock; return 7; fi
			roll_del "$R_CODE"
		fi
		roll_parse "$(roll_find_code "$_keep")"
		R_MAC="$_m"; roll_put
		roll_unlock
		[ "$(nds_state "$_old")" = "Authenticated" ] && nds_do deauth "$_old"
		return 0
	fi
	roll_unlock
	return 0
}

# flash_purge <pause hours>: delete sessions that ran out more than two days ago and paused ones left longer than the limit.
flash_purge() {
	[ -f "$FLASH_ROLL" ] || return 0
	roll_lock || return 1
	awk -F, -v n="$(now_ts)" -v ph="${1:-72}" '{ if ($10 != "" && $10 != 0) { if ($10 + ph * 3600 < n) next } else if ($7 + $6 * 60 + 172800 < n) next; print }' "$FLASH_ROLL" > "$FLASH_ROLL.tmp" &&
		{ cmp -s "$FLASH_ROLL.tmp" "$FLASH_ROLL" && rm -f "$FLASH_ROLL.tmp" || mv "$FLASH_ROLL.tmp" "$FLASH_ROLL"; }
	roll_unlock
}
#@@FILE /usr/lib/opennds/flash_coin_status.sh 755
#!/bin/sh
#Copyright (C) BlueWave Projects and Services 2015-2025
#This software is released under the GNU GPL license.
#
# Status page of the "flash coin" portal: what a connected customer sees at http://<router>/ (openNDS "statuspath").
# It is the green status page of the paper-voucher theme, reading the same roll as flash_coin.sh through
# flash_coin_lib.sh. Install as /usr/lib/opennds/flash_coin_status.sh:
#   uci set opennds.@opennds[0].statuspath='/usr/lib/opennds/flash_coin_status.sh'
# A paused customer is disconnected (the paused time is kept on the roll) and resumes from the portal, so this page
# only offers Pause; Resume is a button on the portal page.
status=$1
clientip=$2
b64query=$3

. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"
max_pauses=1

do_ndsctl () {
	local timeout=4

	for tic in $(seq $timeout); do
		ndsstatus="ready"
		ndsctlout=$(eval ndsctl "$ndsctlcmd")

		for keyword in $ndsctlout; do

			if [ $keyword = "locked" ]; then
				ndsstatus="busy"
				sleep 1
				break
			fi
		done

		if [ "$ndsstatus" = "ready" ]; then
			break
		fi
	done
}

# Pause: the roll keeps the frozen time (flash_pause in flash_coin_lib.sh does the work and disconnects the device).
do_pause () {
	flash_info > /dev/null 2>&1
	flash_pause "$mac" "${pausepesos:-10}"
}
# Sets roll_state (none|running|paused|expired) and, when there is a line, pauses_used / pause_ok / plan / pesos / code.
read_pause_state () {
	pauses_used=0; pause_ok=0; roll_plan=""; roll_code=""; roll_state="none"
	flash_peek "$mac" || return 0
	roll_state="$P_STATE"; roll_plan="$R_PLAN"; roll_code="$R_CODE"; pauses_used="$R_PU"
	flash_info > /dev/null 2>&1
	[ "$P_STATE" = running ] && [ "$R_PLAN" = endurance ] && [ "$R_PESOS" -ge "${pausepesos:-10}" ] && [ "$R_PU" -lt "$max_pauses" ] && pause_ok=1
	return 0
}

get_client_zone () {
	failcheck=$(echo "$clientif" | grep "get_client_interface")

	if [ -z $failcheck ]; then
		client_if=$(echo "$clientif" | awk '{printf $1}')
		client_meshnode=$(echo "$clientif" | awk '{printf $2}' | awk -F ':' '{print $1$2$3$4$5$6}')
		local_mesh_if=$(echo "$clientif" | awk '{printf $3}')

		if [ ! -z "$client_meshnode" ]; then
			client_zone="MeshZone: $client_meshnode"
		else
			client_zone="LocalZone: $client_if"
		fi
	else
		client_zone=""
	fi
}

htmlentityencode() {
	entitylist="
		s/\"/\&quot;/g
		s/>/\&gt;/g
		s/</\&lt;/g
		s/%/\&#37;/g
		s/'/\&#39;/g
		s/\`/\&#96;/g
	"
	local buffer="$1"

	for entity in $entitylist; do
		entityencoded=$(echo "$buffer" | sed "$entity")
		buffer=$entityencoded
	done

	entityencoded=$(echo "$buffer" | awk '{ gsub(/\$/, "\\&#36;"); print }')
}

parse_variables() {
	# Only ever assign into variables this script actually reads downstream.
	# The previous version did `eval $var=...` where $var came straight from
	# the URL's query-string key - an attacker-controlled key (not just the
	# value) being eval'd is arbitrary command execution on the router. This
	# whitelist can't be talked into assigning, let alone running, anything
	# outside these two names.
	for var in $queryvarlist; do
		case "$var" in
			advanced|action) : ;;
			*) continue ;;
		esac

		evalstr=$(echo "$query" | awk -F"$var=" '{print $2}' | awk -F', ' '{print $1}')
		evalstr=$(printf "${evalstr//%/\\x}")

		htmlentityencode "$evalstr"
		evalstr=$entityencoded

		if [ -z "$evalstr" ]; then
			continue
		fi

		case "$var" in
			advanced) advanced=$evalstr ;;
			action) action=$evalstr ;;
		esac
		evalstr=""
	done
	query=""
}

parse_parameters() {
	if [ "$status" = "status" ]; then
		ndsctlcmd="json $clientip"
		do_ndsctl

		if [ "$ndsstatus" = "ready" ]; then
			param_str=$ndsctlout

			for param in gatewayname gatewayaddress gatewayfqdn mac version ip client_type clientif session_start session_end \
				last_active token state upload_rate_limit_threshold download_rate_limit_threshold \
				upload_packet_rate upload_bucket_size download_packet_rate download_bucket_size \
				upload_quota download_quota upload_this_session download_this_session upload_session_avg download_session_avg
			do
				val=$(echo "$param_str" | grep "\"$param\":" | awk -F'"' '{printf "%s", $4}')

				if [ "$val" = "null" ]; then
					val="Unlimited"
				fi

				if [ -z "$val" ]; then
					eval $param=$(echo "Unavailable")
				else
					eval $param=$(echo "\"$val\"")
				fi
			done

			gatewayname_dec=$(printf "${gatewayname//%/\\x}")
			htmlentityencode "$gatewayname_dec"
			gatewaynamehtml=$entityencoded

			get_client_zone

			sessionstart=$(date -d @$session_start)

			if [ "$session_end" = "Unlimited" ]; then
				sessionend=$session_end
			else
				sessionend=$(date -d @$session_end)
			fi

			lastactive=$(date -d @$last_active)
		fi
	else
		mountpoint=$(${LIBOPENNDS:-/usr/lib/opennds/libopennds.sh} tmpfs)
		. $mountpoint/ndscids/ndsinfo
	fi
}

header() {
	header="<!DOCTYPE html>
		<html>
		<head>
		<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\">
		<meta http-equiv=\"Pragma\" content=\"no-cache\">
		<meta http-equiv=\"Expires\" content=\"0\">
		<meta charset=\"utf-8\">
		<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
		<link rel=\"shortcut icon\" href=\"$url/$imagepath\" type=\"image/x-icon\">
		<title>$gatewaynamehtml Client Session Status</title>
		<style>
			:root { --bg: #0f1715; --surface: #16221f; --primary: #38ef7d; --primary-grad: linear-gradient(135deg, #11998e 0%, #38ef7d 100%); --text: #e0e0e0; --error: #ff4b4b; --mono: 'Cascadia Mono', Consolas, 'SF Mono', Menlo, 'JetBrains Mono', 'Fira Code', ui-monospace, monospace; }
			body { font-family: system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 20px; display: flex; justify-content: center; align-items: center; min-height: 90vh; }
			.offset { width: 100%; max-width: 480px; background: var(--surface); padding: 30px; border-radius: 16px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); border: 1px solid rgba(56, 239, 125, 0.1); }
			.title { color: var(--primary); font-size: 1.5rem; font-weight: 800; text-align: center; text-transform: uppercase; text-shadow: 0 0 10px rgba(56, 239, 125, 0.4); margin-bottom: 5px; }
			.subtitle { color: rgba(255,255,255,0.6); font-size: 0.9rem; text-align: center; margin-bottom: 25px; letter-spacing: 1px; }
			.insert { background: rgba(0,0,0,0.4); border: 1px solid rgba(255,255,255,0.05); border-radius: 8px; padding: 20px; margin-bottom: 20px; }
			.stat-line { display: flex; justify-content: space-between; border-bottom: 1px solid rgba(255,255,255,0.05); padding: 10px 0; font-size: 0.85rem; align-items: center; }
			.stat-line:last-child { border-bottom: none; }
			.stat-label { color: rgba(255,255,255,0.5); font-weight: 600; text-transform: uppercase; }
			.stat-value { color: var(--primary); font-family: var(--mono); font-weight: bold; text-align: right; }
			.timer-box { background: rgba(0,0,0,0.6); border: 1px solid rgba(56, 239, 125, 0.2); border-radius: 8px; padding: 15px; text-align: center; margin-bottom: 20px; }
			.timer-val { font-size: clamp(1.5rem, 7vw, 2.2rem); font-family: var(--mono); font-weight: 900; color: var(--primary); text-shadow: 0 0 15px rgba(56, 239, 125, 0.5); margin-top: 5px; white-space: nowrap; }
			input[type=\"submit\"], input[type=\"button\"], button { width: 100%; padding: 14px; background: var(--primary-grad); color: #000; border: none; border-radius: 8px; cursor: pointer; font-weight: 800; font-size: 1rem; text-transform: uppercase; margin-top: 10px; transition: 0.3s; }
			input[type=\"submit\"]:hover, input[type=\"button\"]:hover { opacity: 0.9; transform: translateY(-2px); box-shadow: 0 4px 15px rgba(56, 239, 125, 0.3); }
			.checkbox-container { display: flex; align-items: center; justify-content: center; gap: 10px; margin: 15px 0; color: #ccc; font-size: 0.9rem; }
			input[type=\"checkbox\"] { accent-color: var(--primary); width: 18px; height: 18px; cursor: pointer; }
			hr { border: 0; border-top: 1px solid rgba(255,255,255,0.1); margin: 25px 0; }
			.pause-banner { background: rgba(255,255,255,0.03); border: 1px solid rgba(56, 239, 125, 0.15); border-radius: 8px; padding: 12px 15px; margin-bottom: 15px; font-size: 0.85rem; color: rgba(255,255,255,0.7); text-align: center; }
			.pause-banner.is-paused { border-color: rgba(255, 75, 75, 0.3); color: var(--error); font-weight: 600; }
			.pause-notice { text-align: center; font-size: 0.8rem; color: rgba(255,255,255,0.5); margin-bottom: 10px; }
			input.pause-btn { background: transparent; border: 1px solid rgba(255,255,255,0.15); color: rgba(255,255,255,0.7); }
			input.pause-btn:hover { opacity: 1; border-color: var(--primary); color: var(--primary); box-shadow: none; transform: none; }
			input.resume-btn { background: var(--primary-grad); color: #000; }
		</style>
		</head>
		<body>
		<div class=\"offset\">
		<div class=\"title\">⚡ Session Status ⚡</div>
		<div class=\"subtitle\">$gatewaynamehtml</div>
		<div class=\"insert\">
	"
	echo "$header"
}

footer() {
	year=$(date +'%Y')
	echo "
		<div style=\"text-align: center; margin-top: 20px; font-size: 0.75rem; color: rgba(255,255,255,0.3); border-top: 1px solid rgba(255,255,255,0.05); padding-top: 20px;\">
			<div style=\"font-weight: 800; letter-spacing: 2px; color: var(--primary); text-shadow: 0 0 8px rgba(56, 239, 125, 0.3); margin-bottom: 5px; text-transform: uppercase;\">
				$gatewaynamehtml
			</div>
			<span>Sys.Ver $version // $year</span>
		</div>
		</div>
		</body>
		</html>
	"
}

body() {
	if [ "$ndsstatus" = "busy" ]; then
		pagebody="
			<div style=\"text-align: center; color: var(--primary); font-size: 1.1rem; font-weight: bold; margin-bottom: 20px;\">⚙️ SYSTEM BUSY</div>
			<div style=\"color: #aaa; text-align: center; margin-bottom: 20px;\">The portal is currently processing requests. Please refresh.</div>
			<form>
				<input type=\"button\" VALUE=\"REFRESH STATUS\" onClick=\"history.go(0);return true;\">
			</form>
		"
	elif [ "$status" = "status" ]; then

		if [ "$upload_rate_limit_threshold" = "Unlimited" ] || [ "$upload_packet_rate" = "Unlimited" ]; then
			upload_packet_rate="(Not Checked)"
			upload_bucket_size="(Not Set)"
		fi

		if [ "$download_rate_limit_threshold" = "Unlimited" ] || [ "$download_packet_rate" = "Unlimited" ]; then
			download_packet_rate="(Not Checked)"
			download_bucket_size="(Not Set)"
		fi

		checked="$advanced"

		# ── Pause block ──────────────────────────────────────────────────────
		pauses_left=$(( max_pauses - pauses_used ))
		[ $pauses_left -lt 0 ] && pauses_left=0
		if [ -n "$pause_notice" ]; then
			pause_notice_html="<div class=\"pause-notice\">$pause_notice</div>"
		else
			pause_notice_html=""
		fi

		if [ "$roll_state" = "none" ]; then
			pause_block=""
		elif [ "$pause_ok" = "1" ]; then
			pause_block="
				$pause_notice_html
				<div class=\"pause-banner\">Pauses remaining: $pauses_left / $max_pauses</div>
				<form action=\"$url/\" method=\"get\">
					<input type=\"hidden\" name=\"action\" value=\"pause\">
					<input type=\"submit\" class=\"pause-btn\" value=\"PAUSE SESSION\" >
				</form>
			"
		elif [ "$roll_plan" = "endurance" ] && [ "$pauses_left" -le 0 ]; then
			pause_block="
				$pause_notice_html
				<div class=\"pause-banner\">No pauses remaining ($max_pauses / $max_pauses used)</div>
			"
		else
			pause_block="$pause_notice_html"
		fi

		buttons="
			</div>
			<form action=\"$url/opennds_auth/\" method=\"get\">
				<input type=\"submit\" value=\"ADD TIME\" >
			</form>
			<form action=\"$url/opennds_deny/\" method=\"get\">
				<input type=\"submit\" class=\"pause-btn\" value=\"DISCONNECT\" >
			</form>
			<hr>
			<form action=\"$url/\" method=\"get\">
				<div class=\"checkbox-container\">
					<input type=\"checkbox\" id=\"adv\" value=\"checked\" name=\"advanced\" $checked >
					<label for=\"adv\" style=\"cursor: pointer;\">Show Advanced Diagnostics</label>
				</div>
				<input type=\"submit\" value=\"Refresh Status\" >
			</form>
		"

		# Time left comes from the roll (the paid session), not from openNDS' session_end: a top-up or a fair-use re-grant
		# never shows a different time here.
		if [ "$roll_state" = "running" ]; then end_epoch="$R_END"; else end_epoch="$session_end"; fi
		script_block="
			<div class=\"timer-box\">
				<div style=\"font-size: 0.8rem; color: #888; text-transform: uppercase; font-weight: bold;\">Time Remaining</div>
				<div id=\"live-timer\" class=\"timer-val\">CALCULATING...</div>
			</div>
			<script>
				var endEpoch = '$end_epoch';
				var timerEl = document.getElementById('live-timer');
				var endNum = parseInt(endEpoch, 10);

				if (endEpoch === 'Unlimited') {
					if (timerEl) timerEl.innerHTML = 'UNLIMITED';
				} else if (endEpoch === '' || endEpoch === 'Unavailable' || isNaN(endNum) || endNum <= 0) {
					if (timerEl) timerEl.innerHTML = '—';
				} else {
					var end = endNum * 1000;
					var x = setInterval(function() {
						var now = new Date().getTime();
						var dist = end - now;
						if (dist < 0) {
							clearInterval(x);
							if(timerEl) timerEl.innerHTML = 'EXPIRED';
							setTimeout(function(){ location.reload(); }, 2000);
						} else {
							var d = Math.floor(dist / (1000 * 60 * 60 * 24));
							var h = Math.floor((dist % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
							var m = Math.floor((dist % (1000 * 60 * 60)) / (1000 * 60));
							var s = Math.floor((dist % (1000 * 60)) / 1000);
							var timeStr = '';
							if (d > 0) timeStr += d + 'D ';
							if (d > 0 || h > 0) timeStr += h + 'H ';
							timeStr += (m < 10 ? '0'+m : m) + 'm ' + (s < 10 ? '0'+s : s) + 's';
							if(timerEl) timerEl.innerHTML = timeStr;
						}
					}, 1000);
				}
			</script>
		"
		plan_line=""
		[ -n "$roll_plan" ] && plan_line="<div class=\"stat-line\"><span class=\"stat-label\">Plan</span><span class=\"stat-value\">$roll_plan</span></div>"
		code_line=""
		[ -n "$roll_code" ] && code_line="<div class=\"stat-line\"><span class=\"stat-label\">Your Code</span><span class=\"stat-value\">$roll_code</span></div>"

		if [ "$advanced" = "checked" ]; then
			pagebody="
				$script_block
				$plan_line
				$code_line
				<div class=\"stat-line\"><span class=\"stat-label\">IP Address</span><span class=\"stat-value\">$ip</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">MAC Address</span><span class=\"stat-value\">$mac</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Client Type</span><span class=\"stat-value\">$client_type</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Interface</span><span class=\"stat-value\">$clientif</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Session Start</span><span class=\"stat-value\">$sessionstart</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">Session End</span><span class=\"stat-value\">$sessionend</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL Limit</span><span class=\"stat-value\">$download_rate_limit_threshold Kb/s</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL Limit</span><span class=\"stat-value\">$upload_rate_limit_threshold Kb/s</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL This Session</span><span class=\"stat-value\">$download_this_session KB</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL This Session</span><span class=\"stat-value\">$upload_this_session KB</span></div>
			"
		else
			pagebody="
				$script_block
				$plan_line
				$code_line
				<div class=\"stat-line\"><span class=\"stat-label\">IP Address</span><span class=\"stat-value\">$ip</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">MAC Address</span><span class=\"stat-value\">$mac</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">DL This Session</span><span class=\"stat-value\">$download_this_session KB</span></div>
				<div class=\"stat-line\"><span class=\"stat-label\">UL This Session</span><span class=\"stat-value\">$upload_this_session KB</span></div>
			"
		fi

		pagebody="$pagebody$pause_block$buttons"

	elif [ "$status" = "err511" ]; then

		pagebody="
			<div style=\"text-align: center; color: var(--error); font-size: 1.2rem; font-weight: bold; margin-bottom: 20px;\">❌ CONNECTION REQUIRED</div>
			<div style=\"color: #aaa; text-align: center; margin-bottom: 20px;\">To access the internet, click or tap the Continue button to log in.</div>
			<form action=\"$url/login\" method=\"get\" target=\"_blank\">
			<input type=\"submit\" value=\"CONTINUE TO LOGIN\" >
			</form>
		"

	else
		exit 1
	fi

	echo "$pagebody"
}

if [ -z "$clientip" ]; then
	exit 1
fi

${LIBOPENNDS:-/usr/lib/opennds/libopennds.sh} download "/usr/lib/opennds/download_resources.sh" "" "" "0" "" &>/dev/null

if [ -e "/etc/opennds/htdocs/ndsremote/logo.png" ]; then
	imagepath="ndsremote/logo.png"
else
	imagepath="images/splash.jpg"
fi

if [ "$status" = "status" ] || [ "$status" = "err511" ]; then
	parse_parameters

	if [ -z "$gatewayfqdn" ] || [ "$gatewayfqdn" = "disable" ] || [ "$gatewayfqdn" = "disabled" ]; then
		url="http://$gatewayaddress"
	else
		url="http://$gatewayfqdn"
	fi

	querystr=""

	if [ ! -z "$b64query" ]; then
		ndsctlcmd="b64decode $b64query"
		do_ndsctl
		querystr=$ndsctlout

		querystr=${querystr:1:1024}
		queryvarlist=""

		for element in $querystr; do
			htmlentityencode "$element"
			element=$entityencoded
			varname=$(echo "$element" | awk -F'=' '$2!="" {printf "%s", $1}')
			queryvarlist="$queryvarlist $varname"
		done

		query=$querystr
		parse_variables
	fi

	# Handle a Pause button click (action=pause in the query string).
	pause_notice=""
	if [ "$ndsstatus" = "ready" ] && [ "$status" = "status" ]; then
		if [ "$action" = "pause" ]; then
			do_pause
			case $? in
				0) pause_notice="Session paused. Join this Wi-Fi again and tap Resume to continue." ;;
				2) pause_notice="Nothing to pause." ;;
				5) pause_notice="System busy - please try again." ;;
				6) pause_notice="Already paused." ;;
				7) pause_notice="No pauses remaining." ;;
				9) pause_notice="Pause is only available for Endurance time of enough pesos." ;;
			esac
		fi
	fi

	# Always read the roll fresh, so a page reload still shows the right button.
	if [ "$status" = "status" ] && [ "$ndsstatus" = "ready" ]; then
		read_pause_state
	fi

	header
	body
	footer
	exit 0
else
	exit 1
fi
#@@FILE /usr/lib/opennds/flash_fairuse.sh 755
#!/bin/sh
# Fair-use watcher of the "flash coin" portal (HyperSpeed only): once a connected HyperSpeed device has moved more than
# the limit (default 5 GB), its speed is cut for a few minutes, then restored for a few minutes, and so on until the
# session ends. Endurance is already speed-capped and is never touched. The limits come from the coin-slot manager
# (/info), so there is one place to change them. Also removes sessions that ran out long ago from the roll.
#
#   flash_fairuse.sh          run forever (started by the flash_coin init script)
#   flash_fairuse.sh once     one pass (tests)
. "${FLASH_LIB:-/usr/lib/opennds/flash_coin_lib.sh}"
FAIR_DIR="$FLASH_TMP/fair"

fair_file() { echo "$FAIR_DIR/$1"; }
fair_init() { [ -e "$(fair_file "$1")" ] || { mkdir -p "$FAIR_DIR"; printf 'USED_KB=0\nOFFSET_KB=0\nPHASE=normal\nPHASE_SINCE=0\n' > "$(fair_file "$1")"; }; }
fair_save() { printf 'USED_KB=%s\nOFFSET_KB=%s\nPHASE=%s\nPHASE_SINCE=%s\n' "$2" "$3" "$4" "$5" > "$(fair_file "$1").tmp" && mv "$(fair_file "$1").tmp" "$(fair_file "$1")"; }

# kB moved so far by a client in this openNDS session (download + upload)
nds_kb() {
	nds_do json "$1"
	_d=$(printf '%s' "$NDSOUT" | sed -n 's/.*"download_this_session": *"\([0-9]*\)".*/\1/p' | head -n 1)
	_u=$(printf '%s' "$NDSOUT" | sed -n 's/.*"upload_this_session": *"\([0-9]*\)".*/\1/p' | head -n 1)
	echo $(( ${_d:-0} + ${_u:-0} ))
}

# fair_flip <mac> <used kb> <phase> <down kbps> <up kbps>: re-grant the rest of the paid session with new speed caps.
fair_flip() {
	_mac="$1"; _used="$2"; _phase="$3"
	flash_peek "$_mac" && [ "$P_STATE" = running ] || return 1
	_min=$(left_min "$R_LEFT")
	nds_regrant "$_mac" "$_min" "$5" "$4" "$R_QUP" "$R_QDOWN" || return 1
	fair_save "$(mac_key "$_mac")" "$_used" "$(nds_kb "$_mac")" "$_phase" "$(now_ts)"   # the counters restart (or not): remember the reading
}

# One pass over all connected clients.
fair_tick() {
	flash_info || return 0
	_limit="${fairkb:-5242880}"
	"$NDSCTL" json 2> /dev/null | awk -F'"' '
		/"mac":/ { mac = $4 } /"state":/ { st = $4 } /"download_this_session":/ { dl = $4 }
		/"upload_this_session":/ { print mac, st, dl, $4 }' | while read -r mac st dl ul; do
		[ "$st" = "Authenticated" ] || continue
		flash_peek "$mac" || continue
		[ "$P_STATE" = running ] && [ "$R_PLAN" = hyper ] || continue
		mk=$(mac_key "$mac")
		fair_init "$mk"; . "$(fair_file "$mk")"
		cur=$(( ${dl:-0} + ${ul:-0} ))
		[ "$cur" -ge "$OFFSET_KB" ] || OFFSET_KB=0
		total=$(( USED_KB + cur - OFFSET_KB ))
		n=$(now_ts)
		if [ "$total" -lt "$_limit" ]; then fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"; continue; fi
		if [ "$PHASE" = "normal" ] && [ $(( n - PHASE_SINCE )) -ge $(( ${fairfull:-2} * 60 )) ]; then
			fair_flip "$mac" "$total" throttled "${fairdown:-2000}" "${fairup:-1000}"
		elif [ "$PHASE" = "throttled" ] && [ $(( n - PHASE_SINCE )) -ge $(( ${fairthr:-5} * 60 )) ]; then
			fair_flip "$mac" "$total" normal "$R_DOWN" "$R_UP"
		else
			fair_save "$mk" "$total" "$OFFSET_KB" "$PHASE" "$PHASE_SINCE"
		fi
	done
}

mkdir -p "$FAIR_DIR"
if [ "$1" = once ]; then fair_tick; flash_purge "${pausehours:-72}"; exit 0; fi
while :; do
	fair_tick
	flash_info && flash_purge "${pausehours:-72}"
	sleep 60
done
#@@FILE /usr/bin/piso-monitor.sh 755
#!/bin/sh
# piso-monitor.sh: Telegram alerts and remote commands for a PisoPhone site (BusyBox ash, curl).
#
#   piso-monitor.sh run            the service: checks every minute, listens for commands (long polling)
#   piso-monitor.sh tick           one round of checks (what "run" does every minute)
#   piso-monitor.sh pair           wait for the first message sent to the bot and remember that chat as the owner
#   piso-monitor.sh send "text"    send a message to the owner
#
# Settings (/etc/piso-monitor.conf, written by "piso-setup telegram"):  TG_TOKEN  TG_CHAT  SITE_NAME  REPORT_HOUR
#   HEALTHCHECK_URL (optional: pinged every minute, a service such as healthchecks.io alerts when the pings stop)
#
# Alerts: the router restarted, the coin box does not answer for 5 minutes (and when it is back), the revenue ledger and
# the box disagree or the ledger was edited, a device keeps opening empty coin windows, a daily revenue report.
# Commands, only from the owner's chat: /status /report [days] /reconcile /diag /restart /reboot /help.
# Nothing here can change prices, passwords or Wi-Fi settings.

CONF="${PISO_MONITOR_CONF:-/etc/piso-monitor.conf}"
[ -r "$CONF" ] && . "$CONF"
TG_API="${TG_API:-https://api.telegram.org}"
S="${MON_STATE:-/tmp/piso-monitor}"
LISTENER="${LISTENER:-/usr/bin/coinslot-listener.sh}"
SETUP="${PISO_SETUP:-/usr/sbin/piso-setup}"
SITE_NAME="${SITE_NAME:-PisoPhone}"
REPORT_HOUR="${REPORT_HOUR:-21}"
BOX_DOWN_AFTER="${BOX_DOWN_AFTER:-5}"        # minutes without an answer
POLL_SECONDS="${POLL_SECONDS:-25}"
TICK_SECONDS="${TICK_SECONDS:-60}"
LOGREAD="${LOGREAD:-logread}"
mkdir -p "$S" 2> /dev/null

now() { date +%s; }
say() { logger -t piso-monitor -- "$*" 2> /dev/null; }

# tg_send <text>: to the owner's chat. Long texts are cut to what one Telegram message holds.
tg_send() {
	[ -n "$TG_TOKEN" ] && [ -n "$TG_CHAT" ] || return 1
	_t=$(printf '%s: %s' "$SITE_NAME" "$1" | cut -c1-3800)
	curl -s -m 20 -o /dev/null -w '%{http_code}' --data-urlencode "chat_id=$TG_CHAT" --data-urlencode "text=$_t" \
		"$TG_API/bot$TG_TOKEN/sendMessage" | grep -q '^200$'
}

# alert <key> <cooldown seconds> <text>: sends at most once per cooldown for the same key.
alert() {
	_f="$S/alert.$1"; _n=$(now)
	if [ -r "$_f" ] && [ $((_n - $(cat "$_f"))) -lt "$2" ]; then return 0; fi
	shift; _cd="$1"; shift
	tg_send "$*" && echo "$_n" > "$_f"
}

box_ok() { "$LISTENER" box > /dev/null 2>&1; }

check_box() {
	_fails=$(cat "$S/boxfails" 2> /dev/null); _fails=${_fails:-0}
	if box_ok; then
		if [ "$_fails" -ge "$BOX_DOWN_AFTER" ]; then tg_send "the coin box is back."; rm -f "$S/alert.boxdown"; fi
		echo 0 > "$S/boxfails"
	else
		_fails=$((_fails + 1)); echo "$_fails" > "$S/boxfails"
		[ "$_fails" -ge "$BOX_DOWN_AFTER" ] && alert boxdown 21600 "the coin box has not answered for $_fails minutes (power, Wi-Fi or the box itself)."
	fi
}

check_ledger() {  # at most once an hour
	_f="$S/lastreconcile"; _n=$(now)
	[ -r "$_f" ] && [ $((_n - $(cat "$_f"))) -lt 3600 ] && return 0
	echo "$_n" > "$_f"
	_o=$("$LISTENER" reconcile 2>&1); _rc=$?
	case "$_rc" in
		1) alert ledger 21600 "REVENUE MISMATCH: $_o" ;;
		3) alert ledger 21600 "REVENUE LEDGER WAS CHANGED: $_o" ;;
	esac
}

check_grief() {  # "alert grief:" lines the coin manager writes to the system log
	touch "$S/grief.seen"
	$LOGREAD -e coinslot 2> /dev/null | grep 'alert grief:' | tail -n 10 | while IFS= read -r _l; do
		grep -qxF "$_l" "$S/grief.seen" && continue
		printf '%s\n' "$_l" >> "$S/grief.seen"
		alert grief 900 "${_l#*coinslot: }"
	done
	[ "$(wc -l < "$S/grief.seen")" -gt 100 ] && { tail -n 50 "$S/grief.seen" > "$S/grief.tmp" && mv "$S/grief.tmp" "$S/grief.seen"; }
}

check_report() {  # the daily report, once, from REPORT_HOUR on
	_day=$(date +%F); [ "$(cat "$S/lastreport" 2> /dev/null)" = "$_day" ] && return 0
	[ "$(date +%H | sed 's/^0//')" -ge "$REPORT_HOUR" ] || return 0
	echo "$_day" > "$S/lastreport"
	tg_send "daily report $_day
$("$LISTENER" report 1 2>&1)"
}

do_tick() {
	[ -e "$S/booted" ] || { : > "$S/booted"; tg_send "the router started (or this monitor did)."; }
	check_box; check_ledger; check_grief; check_report
	[ -n "$HEALTHCHECK_URL" ] && curl -fsS -m 10 -o /dev/null "$HEALTHCHECK_URL" 2> /dev/null
	return 0
}

# ---- commands ------------------------------------------------------------------------------------------------------------
cmd_status_text() {
	echo "uptime: $(uptime 2> /dev/null | sed 's/^ *//')"
	echo "memory free: $(awk '/MemAvailable/ {printf "%d MB", $2/1024}' /proc/meminfo 2> /dev/null)"
	box_ok && echo "box: answers" || echo "box: DOES NOT ANSWER"
	echo "guests online: $(ndsctl clients 2> /dev/null | grep -c '^client_id')"
	"$LISTENER" reconcile 2>&1 | head -2
}

handle_command() {  # handle_command <chat> <text>
	_c="$1"; set -- $2
	_cmd="${1%%@*}"; _arg="$2"
	if [ "$_c" != "$TG_CHAT" ]; then say "ignored a message from chat $_c"; return 0; fi
	case "$_cmd" in
		/status | /start) tg_send "$(cmd_status_text)" ;;
		/report) case "$_arg" in "" | *[!0-9]*) _arg=1 ;; esac; tg_send "$("$LISTENER" report "$_arg" 2>&1)" ;;
		/reconcile) tg_send "$("$LISTENER" reconcile 2>&1)" ;;
		/diag) tg_send "$("$SETUP" diag 2>&1 | tail -c 3600)" ;;
		/restart) /etc/init.d/coinslot restart > /dev/null 2>&1; tg_send "coin manager restarted." ;;
		/reboot)
			if [ "$_arg" = confirm ] && [ -r "$S/reboot.ask" ] && [ $(($(now) - $(cat "$S/reboot.ask"))) -lt 120 ]; then
				tg_send "rebooting the router now."; rm -f "$S/reboot.ask"; sleep 2; reboot
			else
				now > "$S/reboot.ask"; tg_send "this reboots the router and takes about 2 minutes. Send  /reboot confirm  within 2 minutes to do it."
			fi ;;
		/help | *) tg_send "/status  /report [days]  /reconcile  /diag  /restart  /reboot" ;;
	esac
}

# poll_updates <long poll seconds>: one getUpdates round; every message goes through handle_command.
poll_updates() {
	[ -n "$TG_TOKEN" ] || { sleep "$1"; return 0; }
	_off=$(cat "$S/offset" 2> /dev/null); _off=${_off:-0}
	_r=$(curl -s -m $(($1 + 10)) "$TG_API/bot$TG_TOKEN/getUpdates?timeout=$1&offset=$_off&allowed_updates=%5B%22message%22%5D") || { sleep 5; return 0; }
	printf '%s' "$_r" | grep -q '"ok":true' || { sleep 5; return 0; }
	printf '%s' "$_r" | awk 'BEGIN { RS = "{\"update_id\":" } NR > 1 {
		id = $0; sub(/[^0-9].*/, "", id)
		chat = ""; text = ""
		if (match($0, /"chat":\{"id":-?[0-9]+/)) { chat = substr($0, RSTART + 13, RLENGTH - 13) }
		if (match($0, /"text":"[^"]*"/)) { text = substr($0, RSTART + 8, RLENGTH - 9) }
		print id "\t" chat "\t" text }' | while IFS="$(printf '\t')" read -r _id _chat _text; do
		echo $((_id + 1)) > "$S/offset"
		[ -n "$_text" ] && handle_command "$_chat" "$_text"
	done
}

do_pair() {
	[ -n "$TG_TOKEN" ] || { echo "TG_TOKEN is not set in $CONF"; return 1; }
	echo "Open your bot in Telegram and send it any message (for example /start). Waiting up to 2 minutes..."
	_t=0
	while [ "$_t" -lt 12 ]; do
		_r=$(curl -s -m 20 "$TG_API/bot$TG_TOKEN/getUpdates?timeout=10&allowed_updates=%5B%22message%22%5D")
		_chat=$(printf '%s' "$_r" | sed -n 's/.*"chat":{"id":\(-\{0,1\}[0-9]*\).*/\1/p' | head -n 1)
		_name=$(printf '%s' "$_r" | sed -n 's/.*"chat":{[^}]*"first_name":"\([^"]*\)".*/\1/p' | head -n 1)
		if [ -n "$_chat" ]; then
			echo "Got a message from chat $_chat ${_name:+($_name)}."
			printf '%s' "$_chat" > "$S/paired.chat"
			return 0
		fi
		_t=$((_t + 1))
	done
	echo "No message arrived."; return 1
}

do_run() {
	say "started"
	_last=0
	while :; do
		poll_updates "$POLL_SECONDS"
		_n=$(now)
		if [ $((_n - _last)) -ge "$TICK_SECONDS" ]; then _last="$_n"; do_tick; fi
	done
}

case "$1" in
	run) do_run ;;
	tick) do_tick ;;
	pair) do_pair ;;
	send) shift; tg_send "$*" ;;
	poll) poll_updates "${2:-1}" ;;       # for tests: one round
	*) sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
#@@FILE /etc/init.d/piso_monitor 755
#!/bin/sh /etc/rc.common
# OpenWrt service for the Telegram monitor (procd). Installed as /etc/init.d/piso_monitor; started once
# "piso-setup telegram" has written /etc/piso-monitor.conf.
START=98
USE_PROCD=1

start_service() {
	[ -r /etc/piso-monitor.conf ] || return 0
	rm -rf /tmp/piso-monitor
	procd_open_instance monitor
	procd_set_param command /usr/bin/piso-monitor.sh run
	procd_set_param respawn
	procd_set_param stderr 1
	procd_close_instance
}
#@@FILE /etc/init.d/flash_coin 755
#!/bin/sh /etc/rc.common
# OpenWrt service for the "flash coin" portal (procd). Installed as /etc/init.d/flash_coin; use it INSTEAD OF
# /etc/init.d/coinslot (both would start the listener on the same port). Four supervised processes: the local coin-slot
# manager the portal talks to, the live-update stream for the guests' pages, the receiver of the box's coin events, and
# the HyperSpeed fair-use watcher.
START=99
USE_PROCD=1

start_service() {
	mkdir -p /etc/coinslot.d

	procd_open_instance listener
	procd_set_param command /usr/bin/coinslot-listener.sh serve
	procd_set_param respawn
	procd_set_param stderr 1
	procd_close_instance

	procd_open_instance stream
	procd_set_param command /usr/bin/coinslot-listener.sh stream
	procd_set_param respawn
	procd_set_param stderr 1
	procd_close_instance

	procd_open_instance events
	procd_set_param command /usr/bin/coinslot-listener.sh events
	procd_set_param respawn
	procd_set_param stderr 1
	procd_close_instance

	procd_open_instance fairuse
	procd_set_param command /usr/lib/opennds/flash_fairuse.sh
	procd_set_param respawn
	procd_set_param stderr 1
	procd_close_instance
}
