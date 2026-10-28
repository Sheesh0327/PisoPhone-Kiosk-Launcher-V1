#!/bin/sh
# PisoPhone router installer: the one line to type on a factory-reset OpenWrt router (logged in by SSH as root).
#
#   wget -qO- https://pisophone.pages.dev/install.sh | sh                  set up this router
#   wget -qO- https://pisophone.pages.dev/install.sh | sh -s update        new software only (an installed router: it
#                                                                           installs the release the owner SIGNED, like
#                                                                           "piso-setup update"; with --branch the
#                                                                           unsigned setup file of that branch, for testing)
#   wget -qO- https://pisophone.pages.dev/install.sh | sh -s -- --branch beta   the setup file of another branch (testing)
#   ... | sh -s -- --local <folder>   a first setup without the router's internet: use the setup file (with its .sha256) in that
#                               folder instead of downloading it (setup/pisophone_setup.py copies it there; an update ignores it)
#   ... | sh -s -- --yes        no questions (moves the router to 10.0.0.1 by itself; the setup runs unattended, its
#                               passwords from ROOT_PASSWORD, KIOSK_PASSWORD, BOX_NEW_ADMIN_PASSWORD or generated). Used by
#                               setup/pisophone_setup.py, which does the whole router setup from your computer
#
# It downloads the current setup file (setup/piso-setup.sh) to /tmp (or /root when memory is short), checks it against its published sha256 and that it is
# complete, moves a router still on its factory address to 10.0.0.1 (after asking: the SSH session then drops and you log in
# again at 10.0.0.1), and runs the setup. No file copying, no line-ending fixes, no chmod.
# Everything is inside main(), called on the last line: a download cut short runs nothing.

# The setup file comes from the PisoPhone website (Cloudflare Pages, built from this repository; it works whether the
# repository is public or private):
# main's from the production site, any other branch's from that branch's preview site (beta: beta.pisophone.pages.dev).
SITE_HOST="${PISO_SITE_HOST:-pisophone.pages.dev}"
# where the setup file is saved: in memory (/tmp) when the router has at least 24000 KB free (the rule updates use, setup/src/96-space-backup.sh),
# else on flash (/root). It is deleted after a good install, so the 1.6 MB never stays on flash. PISO_INSTALL_DIR overrides both.
DIR="${PISO_INSTALL_DIR:-}"
TTY="${PISO_TTY:-/dev/tty}"
LAN_TARGET=10.0.0.1
NETWORK_INIT="${PISO_NETWORK_INIT:-/etc/init.d/network}"
SELF_PATH="${PISO_SELF_PATH:-/usr/sbin/piso-setup}"

# mem_available_kb: free memory in KB (0 when unknown); PISO_MEM_KB is the test hook
mem_available_kb() {
	if [ -n "$PISO_MEM_KB" ]; then echo "$PISO_MEM_KB"; return; fi
	sed -n 's/^MemAvailable: *\([0-9]*\) kB.*/\1/p' /proc/meminfo 2> /dev/null | head -n 1 | sed 's/^$/0/'
}

# pick_dir: sets DIR (unless PISO_INSTALL_DIR gave it) and IN_RAM=1 when the copy goes to /tmp. Memory unknown counts as enough.
pick_dir() {
	IN_RAM=""
	if [ -n "$DIR" ]; then return 0; fi
	_m=$(mem_available_kb); _m="${_m:-0}"
	if [ "$_m" -gt 0 ] 2> /dev/null && [ "$_m" -lt 24000 ]; then DIR="${PISO_ROOT:-}/root"; else DIR="${PISO_ROOT:-}/tmp"; IN_RAM=1; fi
}

say() { printf '%s\n' "$*"; }
fail() { say "ERROR: $*"; exit 1; }

# fetch <url> <file>: OpenWrt's wget (uclient-fetch) does https out of the box; curl is used if wget is missing
fetch() {
	if command -v wget > /dev/null 2>&1; then
		wget -q -T 60 -O "$2" "$1"
	elif command -v curl > /dev/null 2>&1; then
		curl -fsS -m 120 -o "$2" "$1"
	else
		return 1
	fi
}

ask() {  # ask <question>: true for y/Y (read from the terminal: this script itself arrives on stdin); --yes: always
	if [ -n "$ASSUME_YES" ]; then say "$1 yes (--yes)"; return 0; fi
	printf '%s [y/N] ' "$1"
	_a=""
	read -r _a < "$TTY" || return 1
	case "$_a" in y | Y | yes | YES) return 0 ;; esac
	return 1
}

# site_for <branch>: the website of a branch (Cloudflare's branch alias: lowercase, other characters become '-', 28 at most)
site_for() {
	if [ "$1" = main ]; then echo "https://$SITE_HOST"; return 0; fi
	_alias=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]/-/g' | cut -c1-28 | sed 's/^-*//; s/-*$//')
	echo "https://$_alias.$SITE_HOST"
}

lan_ip() { uci -q get network.lan.ipaddr 2> /dev/null | head -n 1 | cut -d/ -f1; }

wan_ip() {
	ubus call network.interface.wan status 2> /dev/null | jsonfilter -e '@["ipv4-address"][0].address' 2> /dev/null
}

# move_lan: the setup expects the router at 10.0.0.1. Changing it cuts this SSH session, so it is done last, after asking.
move_lan() {
	_lan="$1"
	_wan=$(wan_ip)
	case "$_wan" in
		10.0.0.*) fail "the modem gives this router the address $_wan, in the 10.0.0.x range the kiosk network needs. Change the modem's own LAN address (for example to 192.168.100.1), then run this again." ;;
	esac
	say ""
	say "This router is at $_lan. PisoPhone needs it at $LAN_TARGET (the kiosk network is 10.0.0.x)."
	if [ -z "$ASSUME_YES" ]; then
		say "Changing it ends this SSH session. Afterwards: unplug and replug your computer's network cable (or wait a minute),"
		say "then log in again and start the setup:"
		say ""
		say "    ssh root@$LAN_TARGET"
		say "    sh $DIR/piso-setup.sh"
		say ""
		if [ -n "$IN_RAM" ]; then say "(The setup file is kept in the router's memory. If the router restarts before that, run the one-line installer again.)"; say ""; fi
	fi
	ask "Move the router to $LAN_TARGET now?" || fail "nothing was changed. Run this again when you are ready (the setup file is kept in $DIR)."
	if ! uci set network.lan.ipaddr="$LAN_TARGET" || ! uci commit network; then fail "could not change the LAN address"; fi
	if [ -n "$ASSUME_YES" ]; then
		say "Moving to $LAN_TARGET in 3 seconds (this connection ends)."
	else
		say "Moving to $LAN_TARGET in 3 seconds. Log in again with: ssh root@$LAN_TARGET   then run: sh $DIR/piso-setup.sh"
	fi
	# (the dropped SSH connection must not stop it half way)
	(trap '' HUP; sleep 3; "$NETWORK_INIT" restart) > /dev/null 2>&1 &
	exit 0
}

main() {
	branch=main
	ASSUME_YES=""
	LOCAL=""
	while [ $# -gt 0 ]; do
		case "$1" in
			--branch) [ -n "$2" ] || fail "--branch needs a name"; branch="$2"; shift 2 ;;
			--yes) ASSUME_YES=1; shift ;;
			--local) [ -n "$2" ] || fail "--local needs a folder"; LOCAL="$2"; shift 2 ;;
			*) break ;;
		esac
	done
	case "$branch" in *[!A-Za-z0-9._/-]* | "") fail "not a branch name: $branch" ;; esac
	[ "$(id -u)" = 0 ] || [ -n "$PISO_TEST_NONROOT" ] || fail "run this as root on the router (ssh root@<router address>)"
	command -v uci > /dev/null 2>&1 || fail "this is not an OpenWrt router (no uci command)"
	command -v opkg > /dev/null 2>&1 || fail "this router has no opkg (OpenWrt 25.x and snapshots install packages with apk). This setup supports OpenWrt 23.05 and 24.10 (see docs/ROUTERS.md). Nothing was changed."

	# a router that already has the setup updates only with what the owner's key signed (version, downgrade and staged-rollout
	# checks included): this download's checksum comes from the same website, so it would prove nothing about who made the file
	if [ "$1" = update ] && [ "$branch" = main ] && [ -x "$SELF_PATH" ] && grep -q 'self-update' "$SELF_PATH" 2> /dev/null; then
		say "This router already has the setup: installing the newest release the owner signed."
		exec "$SELF_PATH" self-update
	fi
	if [ "$1" = update ]; then say "NOTE: the setup file of branch '$branch' is not signed by the owner. Use it for testing only."; fi

	pick_dir
	base="$(site_for "$branch")/setup"
	# the setup file carries a portal program built for one CPU family: mipsel is piso-setup.sh (as it always was), the others have their own
	file=piso-setup.sh
	if [ -r "${PISO_ROOT:-}/etc/openwrt_release" ]; then
		pkg=$(sed -n "s/^DISTRIB_ARCH=['\"]\{0,1\}\([^'\"]*\).*/\1/p" "${PISO_ROOT:-}/etc/openwrt_release" | head -n 1)
		case "$pkg" in
			mipsel_*) ;;
			mips_*) file=piso-setup-mips.sh ;;
			aarch64_*) file=piso-setup-aarch64.sh ;;
			arm_cortex-a7_neon-vfpv4 | arm_cortex-a15_neon-vfpv4 | arm_cortex-a9_neon | arm_cortex-a9_vfpv3-d16) file=piso-setup-armv7.sh ;;
			x86_64) file=piso-setup-x86_64.sh ;;
			"") ;;
			*) fail "this router's CPU (\"$pkg\") has no PisoPhone portal program built yet. Nothing was changed. Tell the owner which router this is." ;;
		esac
	fi
	tmp="$DIR/.piso-setup.download.$$"
	trap 'rm -f "$tmp" "$tmp.sha256"' EXIT
	if [ -n "$LOCAL" ] && [ "$1" != update ]; then
		# a first setup from the setup program on a computer: it copied the setup file (and its checksum) here; nothing is downloaded
		say "Using the PisoPhone setup the computer copied to this router ($LOCAL)..."
		[ -f "$LOCAL/$file" ] && [ -f "$LOCAL/$file.sha256" ] || fail "--local: $LOCAL has no $file with its checksum. Nothing was changed."
		if ! cp "$LOCAL/$file" "$tmp" || ! cp "$LOCAL/$file.sha256" "$tmp.sha256"; then fail "could not copy the setup file from $LOCAL (is the router's memory full?)"; fi
	else
		say "Downloading the PisoPhone setup ($branch)..."
		fetch "$base/$file" "$tmp" || fail "could not download $base/$file (is the router online? the modem must be in the WAN port)"
		fetch "$base/$file.sha256" "$tmp.sha256" || fail "could not download the checksum of the setup file"
	fi
	want=$(cut -c1-64 < "$tmp.sha256")
	have=$(sha256sum "$tmp" | cut -c1-64)
	if [ "${#want}" != 64 ] || [ "$want" != "$have" ]; then fail "the downloaded setup file is damaged or incomplete (checksum differs). Run this again."; fi
	head -n 1 "$tmp" | grep -q '^#!/bin/sh' || fail "the download is not the setup file"
	grep -q '^# ---- payload: the portal files' "$tmp" || fail "the downloaded setup file has no portal program in it"
	sh -n "$tmp" 2> /dev/null || fail "the downloaded setup file does not read as a shell script"
	if ! chmod 755 "$tmp" || ! mv "$tmp" "$DIR/piso-setup.sh"; then fail "could not save $DIR/piso-setup.sh (is the router's flash full?)"; fi
	say "Saved $DIR/piso-setup.sh ($(sed -n "s/^PISO_RELEASE='\\(.*\\)'\$/release \\1/p" "$DIR/piso-setup.sh" | head -n 1))."

	lan=$(lan_ip)
	if [ "$1" != update ] && [ -n "$lan" ] && [ "$lan" != "$LAN_TARGET" ]; then
		move_lan "$lan"
	fi
	rm -f "$tmp.sha256"
	cd "$DIR" || fail "cannot enter $DIR"
	if [ -n "$ASSUME_YES" ]; then
		# unattended: the setup asks nothing (and there may be no terminal at all)
		exec sh ./piso-setup.sh --yes "$@" < /dev/null
	fi
	# the setup asks questions: its answers come from the terminal, not from this script's pipe
	exec sh ./piso-setup.sh "$@" < "$TTY"
}

main "$@"
