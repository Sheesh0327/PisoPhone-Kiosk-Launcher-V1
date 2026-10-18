#!/bin/sh
# Guests must not see each other. The guest Wi-Fi already keeps the clients of one radio apart, but a guest on 2.4 GHz and one on
# 5 GHz, or anything behind an access point plugged into a guest port, share the guest bridge. This puts one rule on the bridge:
# nothing is passed from one port of br-guest to another. A guest still reaches the router itself (DHCP, DNS, the portal): frames
# for the router are delivered locally and never pass through this rule. Installed as a firewall hotplug script, so it is applied
# again each time the firewall is reloaded; setup and update also run it once. Safe to run any number of times.
#
# Needs the kernel's nftables bridge support (kmod-nft-bridge). Without it nothing is changed and the reason is logged; the
# Wi-Fi isolation of each radio stays as it was. `piso-setup diag` shows whether the rule is in place.
BRIDGE="${PISO_GUEST_BRIDGE:-br-guest}"
TABLE=piso_guest

command -v nft > /dev/null 2>&1 || exit 0
ip link show "$BRIDGE" > /dev/null 2>&1 || exit 0

nft delete table bridge "$TABLE" > /dev/null 2>&1
if nft -f - << EOF
table bridge $TABLE {
	chain forward {
		type filter hook forward priority -200; policy accept;
		meta ibrname "$BRIDGE" counter drop comment "guests do not reach each other"
	}
}
EOF
then
	exit 0
fi
logger -t piso-setup "guest isolation is NOT active: this router cannot load a bridge firewall rule (install kmod-nft-bridge); guests on different radios can still reach each other"
exit 0
