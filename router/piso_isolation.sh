#!/bin/sh
# Who may talk to whom on the router's own networks, below the layer where the firewall's zones work. Installed as a firewall
# hotplug script: applied again each time the firewall is reloaded; setup, update and `piso-setup wifi-admin` also run it.
# Safe to run any number of times. Three tables:
#
#  piso_guest        Guests do not reach each other. The guest Wi-Fi keeps the clients of one radio apart, but a guest on 2.4 GHz and one
#                    on 5 GHz, or anything behind an access point on a guest port, share the guest bridge. Frames for the router itself
#                    (DHCP, DNS, the portal) are delivered locally and never pass this rule.
#  piso_kiosk        The kiosk network (rental phones): a phone reaches the router (internet, DHCP, DNS) and the coin box, nothing else.
#                    No phone to phone, none to the print server or to computers, and none the other way except the coin box. The
#                    router's own login pages (SSH, LuCI: ports 22, 80, 443, 8080, 8443) do not answer on Wi-Fi at all; a computer on a
#                    LAN cable is not touched. `piso-setup wifi-admin on` lets Wi-Fi in again. Packets from Wi-Fi are also marked so the
#                    firewall (rule Kiosk-Wi-Fi-No-Private) can refuse the modem's network to them.
#  piso_anti_tether  Stops customers from sharing their paid internet via Wi-Fi hotspot or USB/Bluetooth tethering. Outgoing packets to
#                    the guest network have TTL set to 1 (when a phone attempts to forward to a tethered device, TTL decrements to 0 and is
#                    dropped). Packets arriving from tethered devices (TTL 61-63 or 125-127) are dropped at prerouting (before the router
#                    decrements TTL). `piso-setup anti-tether off` disables it.
#
# Needs the kernel's nftables bridge support (kmod-nft-bridge) for bridge isolation. Without it the Wi-Fi isolation of each radio
# stays as it was. `piso-setup diag` shows whether the tables are in place.
CONF="${PISO_CONF:-/etc/piso-setup.conf}"
COIN="${PISO_COINSLOT_CONF:-/etc/coinslot.conf}"
SYS="${PISO_SYS:-/sys/class/net}"
NFT="${PISO_NFT:-nft}"
GUEST_BR=br-guest
LAN_BR=br-lan
MARK=0x00200000                     # also in setup/src/30-router-settings.sh (Kiosk-Wi-Fi-No-Private)
WIFI_PORTS="phy* wlan* wl* ra*"     # the names a Wi-Fi access point port can have, on the router's drivers
ADMIN_PORTS="22 80 443 8080 8443"   # (the router's login pages; the console's own ports are added below)

command -v "$NFT" > /dev/null 2>&1 || exit 0

cget() { sed -n "s/^$2='\\(.*\\)'\$/\\1/p" "$1" 2> /dev/null | tail -n 1; }

# load [family] <table> <rules file>: replaces the table; false when the router cannot load it. The old table is removed in the same nft
# transaction that adds the new one ("table" first, so the delete works when there is none yet): when the load fails, the old
# rules stay in place instead of leaving the network with none.
load() {
	if [ "$#" -ge 3 ]; then
		_fam="$1"; _tbl="$2"; _rf="$3"
	else
		_fam="bridge"; _tbl="$1"; _rf="$2"
	fi
	{ echo "table $_fam $_tbl"; echo "delete table $_fam $_tbl"; cat "$_rf"; } > "$T2" 2> /dev/null || return 1
	"$NFT" -f "$T2" > /dev/null 2>&1
}

fail() { logger -t piso-setup "$1"; }

T=$(mktemp 2> /dev/null) || exit 0
T2=$(mktemp 2> /dev/null) || exit 0
trap 'rm -f "$T" "$T2"' EXIT

# ---- guests -------------------------------------------------------------------------------------------------------------------
if [ -d "$SYS/$GUEST_BR" ]; then
	# the bridge's name in the rule when the router can match it; otherwise the names of its ports (they keep their names when
	# the Wi-Fi restarts)
	cat > "$T" << EOT
table bridge piso_guest {
	chain forward {
		type filter hook forward priority -200; policy accept;
		meta ibrname "$GUEST_BR" counter drop comment "guests do not reach each other"
	}
}
EOT
	if ! load piso_guest "$T"; then
		_ports=""
		for _f in "$SYS/$GUEST_BR/brif"/*; do
			[ -e "$_f" ] || continue
			_ports="${_ports:+$_ports,}\"${_f##*/}\""
		done
		if [ -n "$_ports" ]; then
			cat > "$T" << EOT
table bridge piso_guest {
	chain forward {
		type filter hook forward priority -200; policy accept;
		iifname { $_ports } oifname { $_ports } counter drop comment "guests do not reach each other"
	}
}
EOT
			load piso_guest "$T" || fail "guest isolation is NOT active: this router cannot load a bridge firewall rule (install kmod-nft-bridge); guests on different radios can still reach each other"
		fi
	fi
else
	"$NFT" delete table bridge piso_guest > /dev/null 2>&1
fi

# ---- anti-tethering (TTL) -----------------------------------------------------------------------------------------------------
ANTI_TETHER=$(cget "$CONF" ANTI_TETHER)
if [ -d "$SYS/$GUEST_BR" ] && [ "$ANTI_TETHER" != off ]; then
	cat > "$T" << EOT
table inet piso_anti_tether {
	chain prerouting {
		type filter hook prerouting priority -150; policy accept;
		iifname "$GUEST_BR" meta nfproto ipv4 ip ttl { 61, 62, 63, 125, 126, 127 } counter drop comment "drop packets from tethered clients"
	}
	chain postrouting {
		type filter hook postrouting priority 300; policy accept;
		oifname "$GUEST_BR" meta nfproto ipv4 counter ip ttl set 1
	}
}
EOT
	load inet piso_anti_tether "$T" || fail "anti-tethering is NOT active: this router cannot load the inet firewall rule (install nftables)"
else
	"$NFT" delete table inet piso_anti_tether > /dev/null 2>&1
fi

# ---- rental phones ------------------------------------------------------------------------------------------------------------
if [ -d "$SYS/$LAN_BR" ]; then
	BOX_MAC=$(cget "$COIN" GW_BOX_MAC | tr 'A-F' 'a-f')
	BOX_IP=$(cget "$COIN" GW_BOX)
	LAN_IP=$(cget "$COIN" CONSOLE_BIND)
	# a console bound to every address (the default) names no address: use the router's own address on the LAN bridge
	case "$LAN_IP" in "" | 0.0.0.0) LAN_IP=$("${PISO_IP:-ip}" -4 -o addr show dev "$LAN_BR" 2> /dev/null | awk '{sub(/\/.*/, "", $4); print $4; exit}') ;; esac
	WIFI_ADMIN=$(cget "$CONF" WIFI_ADMIN)
	# the console is the admin page (and the coin box's page one port up): offered on a LAN cable only, like the login pages
	CONSOLE_PORT=$(cget "$COIN" CONSOLE_PORT)
	case "$CONSOLE_PORT" in "" | *[!0-9]*) CONSOLE_PORT=8081 ;; esac
	ADMIN_PORTS=$(awk -v s="$ADMIN_PORTS $CONSOLE_PORT $((CONSOLE_PORT + 1))" 'BEGIN { n = split(s, a, " "); for (i = 1; i <= n; i++) if (a[i] != "" && !seen[a[i]]++) out = (out ? out ", " : "") a[i]; print out }')
	CONSOLE_PORTS=$(awk -v s="$CONSOLE_PORT $((CONSOLE_PORT + 1))" 'BEGIN { n = split(s, a, " "); for (i = 1; i <= n; i++) if (a[i] != "" && a[i] != 80 && a[i] != 443 && !seen[a[i]]++) out = (out ? out ", " : "") a[i]; print out }')
	case "$BOX_MAC" in [0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]:[0-9a-f][0-9a-f]) ;; *) BOX_MAC="" ;; esac
	case "$BOX_IP" in *[!0-9.]*) BOX_IP="" ;; esac
	case "$LAN_IP" in *[!0-9.]*) LAN_IP="" ;; esac

	write_kiosk() {  # write_kiosk <ibr|ports|empty> [ports_csv]
		_km="$1"; _kp="$2"
		_in=""
		if [ "$_km" = ibr ]; then _in="meta ibrname \"$LAN_BR\" "; fi
		{
			echo "table bridge piso_kiosk {"
			echo "	chain forward {"
			echo "		type filter hook forward priority -200; policy accept;"
			# the coin box talks to phones and phones to the coin box (ARP included); everything else on a LAN Wi-Fi port is dropped
			if [ "$_km" != empty ]; then
				if [ -n "$BOX_MAC" ]; then
					echo "		${_in}ether saddr $BOX_MAC accept"
					echo "		${_in}ether daddr $BOX_MAC accept"
				fi
				[ -z "$BOX_IP" ] || echo "		${_in}arp daddr ip $BOX_IP accept"
				if [ "$_km" = ibr ]; then
					for _p in $WIFI_PORTS; do
						echo "		meta ibrname \"$LAN_BR\" iifname \"$_p\" counter drop comment \"a Wi-Fi client reaches the router and the coin box only\""
						echo "		meta ibrname \"$LAN_BR\" oifname \"$_p\" counter drop"
					done
				else
					echo "		iifname { $_kp } counter drop comment \"a Wi-Fi client reaches the router and the coin box only\""
					echo "		oifname { $_kp } counter drop"
				fi
			fi
			echo "	}"
			echo "	chain input {"
			echo "		type filter hook input priority -200; policy accept;"
			# only LAN Wi-Fi is blocked from admin pages; guest bridge (br-guest) must still reach port-80 portal redirects
			if [ "$_km" != empty ] && [ "$WIFI_ADMIN" != on ]; then
				if [ "$_km" = ibr ]; then
					for _p in $WIFI_PORTS; do
						if [ -n "$LAN_IP" ]; then
							echo "		meta ibrname \"$LAN_BR\" iifname \"$_p\" ip daddr $LAN_IP tcp dport { $ADMIN_PORTS } counter drop comment \"the router's login pages are not offered on Wi-Fi\""
						else
							echo "		meta ibrname \"$LAN_BR\" iifname \"$_p\" tcp dport { $CONSOLE_PORTS } counter drop comment \"the console is not offered on Wi-Fi\""
						fi
					done
				else
					if [ -n "$LAN_IP" ]; then
						echo "		iifname { $_kp } ip daddr $LAN_IP tcp dport { $ADMIN_PORTS } counter drop comment \"the router's login pages are not offered on Wi-Fi\""
					else
						echo "		iifname { $_kp } tcp dport { $CONSOLE_PORTS } counter drop comment \"the console is not offered on Wi-Fi\""
					fi
				fi
			fi
			echo "	}"
			echo "	chain prerouting {"
			echo "		type filter hook prerouting priority -200; policy accept;"
			if [ "$_km" != empty ]; then
				if [ "$_km" = ibr ]; then
					for _p in $WIFI_PORTS; do
						if [ -n "$BOX_MAC" ]; then
							echo "		meta ibrname \"$LAN_BR\" iifname \"$_p\" ether saddr != $BOX_MAC meta mark set meta mark | $MARK"
						else
							echo "		meta ibrname \"$LAN_BR\" iifname \"$_p\" meta mark set meta mark | $MARK"
						fi
					done
				else
					if [ -n "$BOX_MAC" ]; then
						echo "		iifname { $_kp } ether saddr != $BOX_MAC meta mark set meta mark | $MARK"
					else
						echo "		iifname { $_kp } meta mark set meta mark | $MARK"
					fi
				fi
			fi
			echo "	}"
			echo "}"
		} > "$T"
	}

	write_kiosk ibr
	if ! load piso_kiosk "$T"; then
		_lan_wifi=""
		for _f in "$SYS/$LAN_BR/brif"/*; do
			[ -e "$_f" ] || continue
			_p="${_f##*/}"
			[ ! -e "$SYS/$GUEST_BR/brif/$_p" ] || continue
			case "$_p" in
				phy* | wlan* | wl* | ra*) _lan_wifi="${_lan_wifi:+$_lan_wifi, }\"$_p\"" ;;
			esac
		done
		if [ -n "$_lan_wifi" ]; then
			write_kiosk ports "$_lan_wifi"
		else
			write_kiosk empty
		fi
		load piso_kiosk "$T" || fail "phone isolation is NOT active: this router cannot load a bridge firewall rule (install kmod-nft-bridge); rental phones can still reach each other and the router's login pages"
	fi
fi
exit 0
