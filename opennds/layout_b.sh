#!/bin/sh
# Layout B: the box and the rental phones sit on their own "kiosk" network behind the openNDS router,
# customers use a separate "guest" network that openNDS gates. PRINTS uci commands; it changes nothing itself.
#
#   sh layout_b.sh > /tmp/layout_b.uci     # read it first
#   uci batch < /tmp/layout_b.uci && uci commit && /etc/init.d/network reload && /etc/init.d/firewall reload
#
# Needs OpenWrt 21.02 or newer. Your existing lan network and SSID are not touched; after checking, move the
# customer SSID to the new guest network (or just use the new one) and keep lan for administration.
# Settings (environment): RADIO KIOSK_SSID KIOSK_KEY GUEST_SSID KIOSK_IP GUEST_IP BOX_MAC BOX_IP KIOSK_PORTS
set -e
RADIO="${RADIO:-radio0}"
KIOSK_SSID="${KIOSK_SSID:-PisoKiosk}"
KIOSK_KEY="${KIOSK_KEY:?set KIOSK_KEY: the Wi-Fi password of the kiosk network (8+ characters)}"
GUEST_SSID="${GUEST_SSID:-PisoWiFi}"
KIOSK_IP="${KIOSK_IP:-192.168.20.1}"
GUEST_IP="${GUEST_IP:-192.168.30.1}"
BOX_MAC="${BOX_MAC:?set BOX_MAC: the ESP32 box MAC, e.g. AA:BB:CC:DD:EE:FF}"
BOX_IP="${BOX_IP:-192.168.20.10}"
KIOSK_PORTS="${KIOSK_PORTS:-}"        # optional wired ports for the kiosk network, e.g. "lan3 lan4"
[ "${#KIOSK_KEY}" -ge 8 ] || { echo "KIOSK_KEY must be at least 8 characters" >&2; exit 1; }

cat <<EOT
# --- networks: one bridge per network, so the router can tell the two sides apart --------------------------------
set network.kiosk_dev=device
set network.kiosk_dev.type='bridge'
set network.kiosk_dev.name='br-kiosk'
EOT
for p in $KIOSK_PORTS; do echo "add_list network.kiosk_dev.ports='$p'"; done
cat <<EOT
set network.kiosk=interface
set network.kiosk.proto='static'
set network.kiosk.device='br-kiosk'
set network.kiosk.ipaddr='$KIOSK_IP'
set network.kiosk.netmask='255.255.255.0'
set network.guest_dev=device
set network.guest_dev.type='bridge'
set network.guest_dev.name='br-guest'
set network.guest=interface
set network.guest.proto='static'
set network.guest.device='br-guest'
set network.guest.ipaddr='$GUEST_IP'
set network.guest.netmask='255.255.255.0'

# --- Wi-Fi ---------------------------------------------------------------------------------------------------------
# Kiosk: no client isolation (phones and box must reach each other), WPA2 password.
set wireless.kiosk_ap=wifi-iface
set wireless.kiosk_ap.device='$RADIO'
set wireless.kiosk_ap.mode='ap'
set wireless.kiosk_ap.network='kiosk'
set wireless.kiosk_ap.ssid='$KIOSK_SSID'
set wireless.kiosk_ap.encryption='psk2'
set wireless.kiosk_ap.key='$KIOSK_KEY'
set wireless.kiosk_ap.isolate='0'
# Guest: open, customers cannot see each other; openNDS adds the login page.
set wireless.guest_ap=wifi-iface
set wireless.guest_ap.device='$RADIO'
set wireless.guest_ap.mode='ap'
set wireless.guest_ap.network='guest'
set wireless.guest_ap.ssid='$GUEST_SSID'
set wireless.guest_ap.encryption='none'
set wireless.guest_ap.isolate='1'

# --- addresses -------------------------------------------------------------------------------------------------------
set dhcp.kiosk=dhcp
set dhcp.kiosk.interface='kiosk'
set dhcp.kiosk.start='50'
set dhcp.kiosk.limit='100'
set dhcp.kiosk.leasetime='12h'
set dhcp.guest=dhcp
set dhcp.guest.interface='guest'
set dhcp.guest.start='50'
set dhcp.guest.limit='200'
set dhcp.guest.leasetime='2h'
# The box always gets the same address, so GW_BOX never changes.
add dhcp host
set dhcp.@host[-1].name='pisobox'
set dhcp.@host[-1].mac='$BOX_MAC'
set dhcp.@host[-1].ip='$BOX_IP'

# --- firewall: both sides may reach the internet, neither may reach the other ------------------------------------------
set firewall.kiosk=zone
set firewall.kiosk.name='kiosk'
add_list firewall.kiosk.network='kiosk'
set firewall.kiosk.input='REJECT'
set firewall.kiosk.output='ACCEPT'
set firewall.kiosk.forward='REJECT'
set firewall.kiosk_wan=forwarding
set firewall.kiosk_wan.src='kiosk'
set firewall.kiosk_wan.dest='wan'
set firewall.guest=zone
set firewall.guest.name='guest'
add_list firewall.guest.network='guest'
set firewall.guest.input='REJECT'
set firewall.guest.output='ACCEPT'
set firewall.guest.forward='REJECT'
set firewall.guest_wan=forwarding
set firewall.guest_wan.src='guest'
set firewall.guest_wan.dest='wan'
# Only DHCP and DNS reach the router itself from either side (openNDS adds its own portal rules for guest).
set firewall.kiosk_dhcp=rule
set firewall.kiosk_dhcp.name='Kiosk-DHCP-DNS'
set firewall.kiosk_dhcp.src='kiosk'
set firewall.kiosk_dhcp.proto='udp'
set firewall.kiosk_dhcp.dest_port='53 67'
set firewall.kiosk_dhcp.target='ACCEPT'
set firewall.kiosk_dns_tcp=rule
set firewall.kiosk_dns_tcp.name='Kiosk-DNS-TCP'
set firewall.kiosk_dns_tcp.src='kiosk'
set firewall.kiosk_dns_tcp.proto='tcp'
set firewall.kiosk_dns_tcp.dest_port='53'
set firewall.kiosk_dns_tcp.target='ACCEPT'
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

# --- openNDS gates only the guest network ------------------------------------------------------------------------------
set opennds.@opennds[0].gatewayinterface='br-guest'
# Live coin updates: guests (also before they pay) may reach the router's one read-only stream port, nothing else new.
set firewall.guest_stream=rule
set firewall.guest_stream.name='Guest-Coinslot-Stream'
set firewall.guest_stream.src='guest'
set firewall.guest_stream.proto='tcp'
set firewall.guest_stream.dest_port='${STREAM_PORT:-8100}'
set firewall.guest_stream.target='ACCEPT'
add_list opennds.@opennds[0].users_to_router='allow tcp port ${STREAM_PORT:-8100}'

# --- the coin-slot listener: reach the box on the kiosk side and look for it there if it ever moves --------------
# Put these in /etc/coinslot.conf:  GW_BOX=$BOX_IP   GW_BOX_MAC=$BOX_MAC   DISCOVER_IFACE=br-kiosk
EOT
