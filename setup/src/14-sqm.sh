# ---------------------------------------------------------------------------------------------------------------------
# SQM: keeping a customer's download from filling the line (feature 058)
# ---------------------------------------------------------------------------------------------------------------------
# The owner's line speed is saved by the console in etc/coinslot.d/settings.txt (SQM_ON, SQM_DOWN_MBPS, SQM_UP_MBPS, SQM_PERCENT, default 95).
# "piso-setup sqm apply" turns it into one SQM queue of our own (sqm.piso_wan, other queues are left alone) on the WAN; the console reads
# etc/coinslot.d/sqm.txt ("key|value": installed, qdisc, active, wan, limit_down_kbps, limit_up_kbps, reason).
#   piso-setup sqm apply     set or remove the queue from the saved settings
#   piso-setup sqm install   (re)try the optional packages now, when no customer is online
#   piso-setup sqm status    write the status file
#   piso-setup sqm test      measure the line (only when no customer is online); the result is tmp/piso-speedtest.json
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

# ---- the speed test (group 5b): "piso-setup sqm test" measures the line and writes tmp/piso-speedtest.json, nothing else -------------
# Data only: curl throws every byte away. Four streams each way, about 250 MB at most per direction, 15 seconds at most per transfer.
# SQM is stopped while it measures (its saved settings are never touched) and started again however the test ends.
SQM_TEST_HOSTS="${PISO_SPEEDTEST_HOSTS:-speed.cloudflare.com}"   # the one place the servers are named; the next one is tried when a host does not answer
sqm_test_file() { echo "$PISO_ROOT/tmp/piso-speedtest.json"; }

# sqm_test_write <state> <down Mbps> <up Mbps> <host> <cpu percent> <reason>: the file the console reads (the router's own words, no quotes in them).
sqm_test_write() {
	_cpu="${5:-0}"; _lim=false; [ "$_cpu" -ge 90 ] 2> /dev/null && _lim=true
	printf '{"state":"%s","down":%s,"up":%s,"host":"%s","cpu":%s,"cpu_limited":%s,"time":%s,"reason":"%s"}\n' "$1" "${2:-null}" "${3:-null}" "$4" "$_cpu" "$_lim" "$(date +%s)" "$6" \
		> "$(sqm_test_file).tmp" 2> /dev/null && mv "$(sqm_test_file).tmp" "$(sqm_test_file)" 2> /dev/null
}

# sqm_cpu_sample: "total idle" jiffies from the first line of /proc/stat.
sqm_cpu_sample() { awk '/^cpu /{ t = 0; for (i = 2; i <= 9; i++) t += $i; print t, $5 + $6; exit }' "$PISO_ROOT/proc/stat" 2> /dev/null; }

# sqm_test_phase <down|up> <host>: four streams at once. Sets SQM_T_MBPS (empty when nothing arrived) and SQM_T_CPU (the busiest phase so far);
# returns 1 with SQM_T_WHY when a customer connects or the 80 second limit passes.
sqm_test_phase() {
	_dir="$PISO_ROOT/tmp/piso-speedtest.$$"; rm -rf "$_dir"; mkdir -p "$_dir"; _pids=""; _i=0; _b=62500000
	_w='%{size_download} %{size_upload} %{time_total}\n'
	_c0=$(sqm_cpu_sample)
	while [ $_i -lt 4 ]; do
		if [ "$1" = down ]; then curl -s -o /dev/null -m 15 -w "$_w" "https://$2/__down?bytes=$_b" > "$_dir/$_i" 2> /dev/null &
		else head -c $_b /dev/zero | curl -s -o /dev/null -m 15 -X POST -T - -w "$_w" "https://$2/__up" > "$_dir/$_i" 2> /dev/null &
		fi
		_pids="$_pids $!"; _i=$((_i + 1))
	done
	SQM_T_WHY=""
	while :; do
		if [ "$(guests_online)" -gt 0 ] 2> /dev/null; then SQM_T_WHY="a customer connected, so the test stopped"; break; fi
		_alive=0; for _p in $_pids; do kill -0 "$_p" 2> /dev/null && _alive=1; done
		[ $_alive = 0 ] && break
		if [ "$(date +%s)" -ge "$SQM_T_END" ]; then SQM_T_WHY="the test took too long and was stopped"; break; fi
		sleep 1
	done
	# shellcheck disable=SC2086 # one argument per process id
	[ -n "$SQM_T_WHY" ] && kill $_pids 2> /dev/null
	wait
	SQM_T_MBPS=$(cat "$_dir"/* 2> /dev/null | awk '{ b += $1 + $2; if ($3 > t) t = $3 } END { if (t > 0 && b > 0) printf "%.1f", b * 8 / t / 1000000 }')
	_c=$(echo "$_c0 $(sqm_cpu_sample)" | awk '{ dt = $3 - $1; if (dt > 0) printf "%d", 100 * (dt - ($4 - $2)) / dt; else print 0 }')
	[ "${_c:-0}" -gt "${SQM_T_CPU:-0}" ] && SQM_T_CPU=$_c
	rm -rf "$_dir"
	[ -z "$SQM_T_WHY" ]
}

cmd_sqm_test() {
	if [ "$(guests_online)" -gt 0 ] 2> /dev/null; then
		echo "BUSY: customers are online. Try again at a quiet moment." >&2
		return 3
	fi
	_lock="$PISO_ROOT/tmp/piso-speedtest.lock"; mkdir -p "$PISO_ROOT/tmp"
	if ! mkdir "$_lock" 2> /dev/null; then
		if kill -0 "$(cat "$_lock/pid" 2> /dev/null)" 2> /dev/null; then echo "BUSY: a speed test is already running." >&2; return 3; fi
		rm -rf "$_lock"; mkdir "$_lock" 2> /dev/null || { echo "BUSY: a speed test is already running." >&2; return 3; }
	fi
	echo $$ > "$_lock/pid"
	_stopped=0
	if [ -x "$(sqm_init)" ] && { [ "$(sqm_setting SQM_ON)" = 1 ] || sqm_active "$(sqm_wan_dev)" "$(sqm_qdisc)"; }; then _stopped=1; fi
	trap '[ "$_stopped" = 1 ] && "$(sqm_init)" start > /dev/null 2>&1; rm -rf "$_lock"' EXIT
	trap 'exit 1' HUP INT TERM
	[ "$_stopped" = 1 ] && "$(sqm_init)" stop > /dev/null 2>&1
	SQM_T_END=$(($(date +%s) + 80)); SQM_T_CPU=0; _down=""; _up=""; _host=""; _why=""
	sqm_test_write running "" "" "" 0 ""
	for _h in $SQM_TEST_HOSTS; do
		sqm_test_phase down "$_h" || { _why="$SQM_T_WHY"; break; }
		if [ -n "$SQM_T_MBPS" ]; then _down="$SQM_T_MBPS"; _host="$_h"; break; fi
	done
	if [ -n "$_down" ] && [ -z "$_why" ]; then
		sqm_test_phase up "$_host" || _why="$SQM_T_WHY"
		_up="$SQM_T_MBPS"
	fi
	if [ -z "$_why" ] && [ -z "$_down" ]; then _why="no speed test server answered (is the router online?)"; fi
	if [ -z "$_why" ] && [ -z "$_up" ]; then _why="the download was measured but the upload could not be"; fi
	if [ -n "$_why" ]; then
		sqm_test_write failed "" "" "$_host" "$SQM_T_CPU" "$_why"
		echo "The speed test did not finish: $_why." >&2
		return 1
	fi
	sqm_test_write done "$_down" "$_up" "$_host" "$SQM_T_CPU" ""
	echo "Download $_down Mbps, upload $_up Mbps (via $_host)."
	return 0
}

cmd_sqm() {
	case "$1" in
		apply) cmd_sqm_apply ;;
		install) cmd_sqm_install ;;
		status) cmd_sqm_status ;;
		test) cmd_sqm_test ;;
		*) echo "usage: piso-setup sqm apply|install|status|test" >&2; return 1 ;;
	esac
}
