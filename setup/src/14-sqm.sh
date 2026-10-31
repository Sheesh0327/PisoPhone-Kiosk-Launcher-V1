# ---------------------------------------------------------------------------------------------------------------------
# SQM: keeping a customer's download from filling the line (feature 056)
# ---------------------------------------------------------------------------------------------------------------------
# The owner's line speed is saved by the console in etc/coinslot.d/settings.txt (SQM_ON, SQM_DOWN_MBPS, SQM_UP_MBPS, SQM_PERCENT, default 95).
# "piso-setup sqm apply" turns it into one SQM queue of our own (sqm.piso_wan, other queues are left alone) on the WAN; the console reads
# etc/coinslot.d/sqm.txt ("key|value": installed, qdisc, active, wan, limit_down_kbps, limit_up_kbps, reason).
#   piso-setup sqm apply     set or remove the queue from the saved settings
#   piso-setup sqm install   (re)try the optional packages now, when no customer is online
#   piso-setup sqm status    write the status file
sqm_dir() { echo "$PISO_ROOT/etc/coinslot.d"; }
sqm_init() { echo "$PISO_ROOT/etc/init.d/sqm"; }
sqm_setting() { sed -n "s/^$1=\\(.*\\)\$/\\1/p" "$(sqm_dir)/settings.txt" 2> /dev/null | tail -n 1 | tr -d ' \r'; }

# sqm_wan_dev: the device the WAN really uses now (pppoe-wan on a PPPoE line).
sqm_wan_dev() {
	_d=$(ifstatus wan 2> /dev/null | jsonfilter -e '@.l3_device' 2> /dev/null)
	[ -n "$_d" ] || _d=$(ifstatus wan 2> /dev/null | jsonfilter -e '@.device' 2> /dev/null)
	echo "$_d"
}

# sqm_active <device> <qdisc>: the queue is on the device.
sqm_active() { tc qdisc show dev "$1" 2> /dev/null | grep -q "$2"; }

# sqm_kbps <Mbps> <percent>: the limit in kbit/s.
sqm_kbps() { awk -v m="$1" -v p="$2" 'BEGIN { printf "%d", m * 1000 * p / 100 }'; }

# sqm_number_ok <value> <min> <max>: a number with at most one decimal place, inside the range.
sqm_number_ok() {
	case "$1" in "" | *[!0-9.]* | .* | *. | *.*.* | *.[0-9][0-9]*) return 1 ;; esac
	awk -v v="$1" -v lo="$2" -v hi="$3" 'BEGIN { exit (v + 0 >= lo && v + 0 <= hi) ? 0 : 1 }'
}

# sqm_status <installed> <qdisc> <active> <wan> <down kbps> <up kbps> <reason>: the file the console reads.
sqm_status() {
	[ "$DRY" = 1 ] && return 0
	mkdir -p "$(sqm_dir)" 2> /dev/null
	printf 'installed|%s\nqdisc|%s\nactive|%s\nwan|%s\nlimit_down_kbps|%s\nlimit_up_kbps|%s\nreason|%s\n' "$1" "$2" "$3" "$4" "$5" "$6" "$7" > "$(sqm_dir)/sqm.txt.tmp" 2> /dev/null \
		&& mv "$(sqm_dir)/sqm.txt.tmp" "$(sqm_dir)/sqm.txt" 2> /dev/null
}

# sqm_qdisc: cake when the kernel or an installed package has it, else fq_codel.
sqm_qdisc() { if kernel_has sch_cake || pkg_installed kmod-sched-cake; then echo cake; else echo fq_codel; fi; }

cmd_sqm_apply() {
	_inst=0; [ -x "$(sqm_init)" ] && _inst=1
	_on=$(sqm_setting SQM_ON); _dn=$(sqm_setting SQM_DOWN_MBPS); _up=$(sqm_setting SQM_UP_MBPS); _pc=$(sqm_setting SQM_PERCENT); _pc="${_pc:-95}"
	_qd=$(sqm_qdisc)
	if [ "$_on" != 1 ]; then   # off (or never set): our queue goes, other queues stay
		if [ "$_inst" = 1 ]; then
			uci -q delete sqm.piso_wan > /dev/null 2>&1; uci commit sqm > /dev/null 2>&1
			"$(sqm_init)" stop > /dev/null 2>&1
		fi
		sqm_status "$_inst" "$_qd" 0 "" "" "" "switched off"
		echo "SQM is off."
		return 0
	fi
	if [ "$_inst" != 1 ]; then
		sqm_status 0 "" 0 "" "" "" "the SQM package is not installed on this router"
		echo "SQM is not installed on this router (try: piso-setup sqm install)." >&2
		return 1
	fi
	if ! sqm_number_ok "$_dn" 0.1 10000 || ! sqm_number_ok "$_up" 0.1 10000 || ! sqm_number_ok "$_pc" 50 100; then
		sqm_status 1 "$_qd" 0 "" "" "" "the saved line speed or margin is not a usable number"
		echo "The saved speeds must be from 0.1 to 10000 Mbps and the margin from 50 to 100 percent. Nothing was changed." >&2
		return 1
	fi
	_dev=$(sqm_wan_dev)
	if [ -z "$_dev" ]; then
		sqm_status 1 "$_qd" 0 "" "" "" "the router has no WAN device right now"
		echo "The router has no WAN device right now. Nothing was changed." >&2
		return 1
	fi
	_dk=$(sqm_kbps "$_dn" "$_pc"); _uk=$(sqm_kbps "$_up" "$_pc")
	uci -q delete sqm.piso_wan > /dev/null 2>&1
	if [ "$_qd" = cake ]; then
		_script="layer_cake.qos"
		_extra="set sqm.piso_wan.qdisc_advanced='1'
set sqm.piso_wan.qdisc_really_really_advanced='1'
set sqm.piso_wan.iqdisc_opts='nat dual-dsthost ingress'
set sqm.piso_wan.eqdisc_opts='nat dual-srchost'
set sqm.piso_wan.squash_dscp='1'
set sqm.piso_wan.squash_ingress='1'"
	else
		_script="simple.qos"; _extra=""
	fi
	uci batch << EOT
set sqm.piso_wan=queue
set sqm.piso_wan.enabled='1'
set sqm.piso_wan.interface='$_dev'
set sqm.piso_wan.download='$_dk'
set sqm.piso_wan.upload='$_uk'
set sqm.piso_wan.qdisc='$_qd'
set sqm.piso_wan.script='$_script'
set sqm.piso_wan.linklayer='ethernet'
set sqm.piso_wan.overhead='18'
$_extra
EOT
	uci commit sqm
	"$(sqm_init)" enable > /dev/null 2>&1
	"$(sqm_init)" restart > /dev/null 2>&1
	if sqm_active "$_dev" "$_qd"; then
		sqm_status 1 "$_qd" 1 "$_dev" "$_dk" "$_uk" ""
		echo "SQM ($_qd) is limiting $_dev to $_dk kbit/s down and $_uk kbit/s up."
	else
		sqm_status 1 "$_qd" 0 "$_dev" "$_dk" "$_uk" "SQM was set but its queue is not on $_dev (see: logread -e sqm)"
		echo "SQM was set, but its queue is not on $_dev yet (see: logread -e sqm)." >&2
	fi
	return 0
}

cmd_sqm_install() {
	if [ "$(guests_online)" -gt 0 ] 2> /dev/null; then
		echo "BUSY: customers are online. Try again at a quiet moment." >&2
		return 3
	fi
	DRY=0
	pkg_update > /dev/null 2>&1 || { echo "The package lists could not be updated (no internet, or the package sources do not answer). Nothing was changed." >&2; return 1; }
	pkg_image_record
	install_optional_packages
	optional_report
	cmd_sqm_apply > /dev/null 2>&1
	return 0
}

cmd_sqm_status() {
	_inst=0; [ -x "$(sqm_init)" ] && _inst=1
	_dev=$(sqm_wan_dev); _qd=$(sqm_qdisc); _act=0
	[ "$_inst" = 1 ] && [ -n "$_dev" ] && sqm_active "$_dev" "$_qd" && _act=1
	sqm_status "$_inst" "$_qd" "$_act" "$_dev" "" "" ""
	cat "$(sqm_dir)/sqm.txt" 2> /dev/null
}

cmd_sqm() {
	case "$1" in
		apply) cmd_sqm_apply ;;
		install) cmd_sqm_install ;;
		status) cmd_sqm_status ;;
		*) echo "usage: piso-setup sqm apply|install|status" >&2; return 1 ;;
	esac
}
