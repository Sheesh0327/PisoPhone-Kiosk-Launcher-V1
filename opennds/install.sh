#!/bin/sh
# Installs the coin-slot integration on an OpenWrt router that already runs openNDS.
# Run from this directory:   sh install.sh
set -e
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
[ -f /usr/lib/opennds/libopennds.sh ] || { echo "openNDS (libopennds.sh) not found: install opennds first"; exit 1; }

echo "Installing packages (socat, openssl-util, curl)..."
opkg update >/dev/null && opkg install socat openssl-util curl

install -m 0755 theme_coinslot.sh /usr/lib/opennds/theme_coinslot.sh
install -m 0755 coinslot-listener.sh /usr/bin/coinslot-listener.sh
install -m 0755 coinslot.init /etc/init.d/coinslot
if [ ! -f /etc/coinslot.conf ]; then
	install -m 0600 coinslot.conf /etc/coinslot.conf
	echo "Created /etc/coinslot.conf: edit GW_BOX and GW_KEY before starting."
else
	echo "Keeping your existing /etc/coinslot.conf"
fi

# Tell openNDS to use the theme (login_option_enabled 3 = ThemeSpec).
uci set opennds.@opennds[0].login_option_enabled='3'
uci set opennds.@opennds[0].themespec_path='/usr/lib/opennds/theme_coinslot.sh'
uci commit opennds

/etc/init.d/coinslot enable
echo
echo "Next: edit /etc/coinslot.conf, then run:"
echo "  /etc/init.d/coinslot restart && /etc/init.d/opennds restart"
