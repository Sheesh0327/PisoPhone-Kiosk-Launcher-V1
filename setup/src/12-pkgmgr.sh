# ---------------------------------------------------------------------------------------------------------------------
# Package manager: opkg, apk or none, and what kind of image this is (feature 057)
# ---------------------------------------------------------------------------------------------------------------------
# The installer never calls opkg or apk by name: it uses pkg_* below. The layer never passes a flag that skips a signature,
# a checksum, a certificate or a dependency; the router's own configured feeds are the only source (check_security_rules.sh).
# Test hooks: PISO_ROOT (a copy of the router's /etc), PISO_FIT_PROBE (the package the kernel-fit probe tries, default kmod-ifb).

# pkg_manager: opkg, apk or none. With both, the one that lists the base system (busybox) is the router's own.
pkg_manager() {
	_o=0; _a=0
	command -v opkg > /dev/null 2>&1 && _o=1
	command -v apk > /dev/null 2>&1 && _a=1
	if [ "$_o$_a" = 11 ]; then
		if [ -n "$(opkg list-installed busybox 2> /dev/null)" ]; then echo opkg
		elif apk info -e busybox > /dev/null 2>&1; then echo apk
		else echo opkg; fi
	elif [ "$_o" = 1 ]; then echo opkg
	elif [ "$_a" = 1 ]; then echo apk
	else echo none; fi
}

# pkg_name <package>: the name this manager knows it by (one place for a package that is called differently).
pkg_name() {
	case "$1" in
		*) echo "$1" ;;
	esac
}

pkg_update() {
	case "$(pkg_manager)" in
		opkg) opkg update ;;
		apk) apk update ;;
		*) return 1 ;;
	esac
}

# pkg_installed <package>: it is installed now.
pkg_installed() {
	_p=$(pkg_name "$1")
	case "$(pkg_manager)" in
		opkg) opkg list-installed 2> /dev/null | grep -q "^$_p " ;;
		apk) apk info -e "$_p" > /dev/null 2>&1 ;;
		*) return 1 ;;
	esac
}

# pkg_available <package>: the manager knows it. opkg info exits 0 even for an unknown package, so its output is read; and it also answers for
# an installed package, so pkg_dry_run is the real test of whether an install would work.
pkg_available() {
	_p=$(pkg_name "$1")
	case "$(pkg_manager)" in
		opkg) opkg info "$_p" 2> /dev/null | grep -q '^Package:' ;;
		apk) [ -n "$(apk search -e "$_p" 2> /dev/null)" ] ;;
		*) return 1 ;;
	esac
}

# pkg_dry_run <package>...: the manager's own simulation says an install would work (nothing is changed).
pkg_dry_run() {
	case "$(pkg_manager)" in
		opkg) opkg install --noaction "$@" > /dev/null 2>&1 ;;
		apk) apk add --simulate "$@" > /dev/null 2>&1 ;;
		*) return 1 ;;
	esac
}

pkg_install() {
	case "$(pkg_manager)" in
		opkg) opkg install "$@" ;;
		apk) apk add "$@" ;;
		*) return 1 ;;
	esac
}

pkg_remove() {
	case "$(pkg_manager)" in
		opkg) opkg remove "$@" ;;
		apk) apk del "$@" ;;
		*) return 1 ;;
	esac
}

# pkg_free_kb: the free KB of the writable flash (/overlay), else of /; empty when unknown.
pkg_free_kb() {
	_f=$(df -k /overlay 2> /dev/null | awk 'NR==2 {print $4}')
	[ -n "$_f" ] || _f=$(df -k / 2> /dev/null | awk 'NR==2 {print $4}')
	echo "$_f"
}

# ---- what kind of image this is ---------------------------------------------------------------------------------------
# The kind comes from facts on the router, never from its model name: the distribution id in /etc/openwrt_release, the hosts of the
# configured package feeds, and a dry run of one kernel module (a feed that does not fit this image's kernel cannot install its kmods).
# It is a label for how careful the installer is (feature 056); the probes still decide each package. It is not a claim of support.

# pkg_fork_site <distribution id>: the feed domain of a known fork (the one list of forks).
pkg_fork_site() {
	case "$1" in
		ImmortalWrt) echo immortalwrt.org ;;
	esac
}

# pkg_feed_hosts: the host of every active feed line (opkg and apk files), one per line.
pkg_feed_hosts() {
	for _f in "$PISO_ROOT"/etc/opkg/distfeeds.conf "$PISO_ROOT"/etc/opkg/customfeeds.conf "$PISO_ROOT"/etc/apk/repositories.d/*.list "$PISO_ROOT"/etc/apk/repositories; do
		[ -r "$_f" ] || continue
		sed -n 's/^[^#]*:\/\/\([^\/ ]*\).*/\1/p' "$_f" | sed 's/.*@//; s/:[0-9]*$//'
	done | sort -u
}

# pkg_image_type: one line, "<official|fork|custom>|<description>|<reason>". Run it after pkg_update (the probe needs the package lists).
pkg_image_type() {
	_rel="$PISO_ROOT/etc/openwrt_release"
	_id=$(sed -n "s/^DISTRIB_ID=['\"]\{0,1\}\([^'\"]*\).*/\1/p" "$_rel" 2> /dev/null | head -n 1)
	_desc=$(sed -n "s/^DISTRIB_DESCRIPTION=['\"]\{0,1\}\([^'\"]*\).*/\1/p" "$_rel" 2> /dev/null | head -n 1)
	_desc="${_desc:-unknown image}"
	[ -n "$_id" ] || { echo "custom|$_desc|no release file"; return 0; }
	if [ "$_id" = OpenWrt ]; then _kind=official; _dom=openwrt.org; else _kind=fork; _dom=$(pkg_fork_site "$_id"); fi
	[ -n "$_dom" ] || { echo "custom|$_desc|$_id is not a distribution this setup knows"; return 0; }
	[ "$(pkg_manager)" != none ] || { echo "custom|$_desc|no package manager"; return 0; }
	_hosts=$(pkg_feed_hosts)
	[ -n "$_hosts" ] || { echo "custom|$_desc|no package feeds are configured"; return 0; }
	for _h in $_hosts; do
		case "$_h" in
			"$_dom" | *."$_dom") ;;
			*) echo "custom|$_desc|a package feed comes from $_h, not $_dom"; return 0 ;;
		esac
	done
	pkg_dry_run "${PISO_FIT_PROBE:-kmod-ifb}" || { echo "custom|$_desc|the package feed does not offer ${PISO_FIT_PROBE:-kmod-ifb} for this image (it does not fit the kernel, or the package lists are old)"; return 0; }
	echo "$_kind|$_desc|feeds from $_dom, kernel fit ok"
}
