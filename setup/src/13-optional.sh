# ---------------------------------------------------------------------------------------------------------------------
# Optional packages: a smoother line for many customers (feature 058)
# ---------------------------------------------------------------------------------------------------------------------
# openNDS is the one package the setup cannot do without (install_packages). The packages below are extras: the setup tries each one, never stops
# because of one, and writes down what happened (etc/coinslot.d/packages.txt: "image|<kind>|<description>|<reason>" and "pkg|<name>|<state>|<reason>",
# states installed, present, builtin, skipped, failed), which "piso-setup status", the summary and the console read.
#   sqm-scripts + kmod-ifb + tc-full   the shaper (all or nothing: a half-installed shaper is taken out again)
#   kmod-sched-cake                    the cake qdisc (without it the console falls back to fq_codel)
#   irqbalance                         spreads network interrupts over the cores
# Each is first looked for on the router (installed, or the module already in the kernel), then dry-run through the package layer, and only then
# installed. On a custom image the kernel's own modules decide: a kernel module package from the feed is not tried (it would not load).
OPT_MIN_FREE_KB=2048   # under this much free flash nothing optional is tried (the installer's own warning level)

opt_status_file() { echo "$PISO_ROOT/etc/coinslot.d/packages.txt"; }

# kernel_has <module>: the kernel has it loaded, built in or on disk.
kernel_has() {
	[ -d "$PISO_ROOT/sys/module/$1" ] && return 0
	ls "$PISO_ROOT"/lib/modules/*/"$1".ko* > /dev/null 2>&1 && return 0
	grep -qs "/$1\.ko" "$PISO_ROOT"/lib/modules/*/modules.builtin 2> /dev/null
}

# opt_note <name> <state> <reason>: one line of the status.
opt_note() { OPT_LINES="${OPT_LINES}pkg|$1|$2|$3
"; }

# opt_blocked <package>: prints why this package cannot be tried now; nothing when it can.
opt_blocked() {
	if [ "$_mgr" = none ]; then echo "no package manager on this router"; return 0; fi
	if [ -n "$_low" ]; then echo "$_low"; return 0; fi
	case "$1" in kmod-*)
		if [ "$_kind" = custom ]; then echo "the kernel of this custom image does not match the package feed (kernel modules from it would not load)"; return 0; fi ;;
	esac
	pkg_available "$1" || echo "not in the package feed"
}

install_optional_packages() {
	step "Optional packages (a smoother line for many customers)"
	OPT_LINES=""
	_img=$(conf_get IMAGE_TYPE); _kind="${_img%%|*}"; _mgr=$(pkg_manager); _low=""
	_free=$(pkg_free_kb)
	case "$_free" in "" | *[!0-9]*) ;; *) [ "$_free" -ge "$OPT_MIN_FREE_KB" ] || _low="only $_free KB of flash is free, and this step needs $OPT_MIN_FREE_KB KB" ;; esac
	_tries=1; [ "$_kind" = official ] && _tries=2   # (an official image gets one more try, like openNDS)

	# --- the shaper: what is still missing of sqm-scripts, kmod-ifb and tc-full
	_need=""; _core_ok=1; _why=""
	if pkg_installed sqm-scripts; then opt_note sqm-scripts present "already installed"; else _need="sqm-scripts"; fi
	if pkg_installed kmod-ifb; then opt_note kmod-ifb present "already installed"
	elif kernel_has ifb; then opt_note kmod-ifb builtin "the kernel already has it"
	else _need="$_need kmod-ifb"; fi
	_tc=""
	if pkg_installed tc-full; then opt_note tc-full present "already installed"
	elif [ -z "$(opt_blocked tc-full)" ]; then _tc="tc-full"; _need="$_need tc-full"
	else opt_note tc-full skipped "$(opt_blocked tc-full) (sqm-scripts brings the tc it needs)"; fi
	for _p in $_need; do
		[ "$_p" = tc-full ] && continue
		_why=$(opt_blocked "$_p")
		[ -n "$_why" ] && { _core_ok=0; break; }
	done
	if [ -n "$_need" ] && [ "$_core_ok" = 1 ]; then
		# shellcheck disable=SC2086 # one argument per package
		if ! pkg_dry_run $_need; then _core_ok=0; _why="the package manager's trial install (dry run) failed: it does not fit this image"; fi
	fi
	if [ -n "$_need" ] && [ "$_core_ok" = 1 ]; then
		_t=0; _done=1
		# shellcheck disable=SC2086 # one argument per package
		while [ "$_t" -lt "$_tries" ]; do _t=$((_t + 1)); pkg_install $_need > /dev/null 2>&1 && { _done=0; break; }; done
		if [ "$_done" = 0 ]; then
			for _p in $_need; do opt_note "$_p" installed "added by this setup"; done
		else
			for _p in $_need; do pkg_remove "$_p" > /dev/null 2>&1; opt_note "$_p" failed "the install did not finish, so it was taken out again"; done
			_core_ok=0
		fi
	elif [ -n "$_need" ]; then
		for _p in $_need; do opt_note "$_p" skipped "$_why"; done
	fi

	# --- cake: without it the console uses fq_codel
	if pkg_installed kmod-sched-cake; then opt_note kmod-sched-cake present "already installed"
	elif kernel_has sch_cake; then opt_note kmod-sched-cake builtin "the kernel already has it"
	elif [ "$_core_ok" = 0 ]; then opt_note kmod-sched-cake skipped "it is only useful with the shaper, which is not installed"
	else
		_why=$(opt_blocked kmod-sched-cake)
		if [ -n "$_why" ]; then opt_note kmod-sched-cake skipped "$_why"
		elif ! pkg_dry_run kmod-sched-cake; then opt_note kmod-sched-cake skipped "the package manager's trial install (dry run) failed: it does not fit this image"
		elif pkg_install kmod-sched-cake > /dev/null 2>&1; then opt_note kmod-sched-cake installed "added by this setup"
		else pkg_remove kmod-sched-cake > /dev/null 2>&1; opt_note kmod-sched-cake failed "the install did not finish"; fi
	fi

	# --- irqbalance
	if pkg_installed irqbalance; then opt_note irqbalance present "already installed"
	else
		_why=$(opt_blocked irqbalance)
		if [ -n "$_why" ]; then opt_note irqbalance skipped "$_why"
		elif ! pkg_dry_run irqbalance; then opt_note irqbalance skipped "the package manager's trial install (dry run) failed: it does not fit this image"
		elif pkg_install irqbalance > /dev/null 2>&1; then opt_note irqbalance installed "added by this setup"
		else pkg_remove irqbalance > /dev/null 2>&1; opt_note irqbalance failed "the install did not finish"; fi
	fi
	opt_write
	return 0
}

# opt_write: the status file (image line first); a failed write is only noted.
opt_write() {
	_f=$(opt_status_file)
	[ "$DRY" = 1 ] && return 0
	mkdir -p "$(dirname "$_f")" 2> /dev/null
	# shellcheck disable=SC2015 # the message is for a failed write or move, whichever it was
	{ printf 'image|%s\n' "$(conf_get IMAGE_TYPE)"; printf '%s' "$OPT_LINES"; } > "$_f.tmp" 2> /dev/null && mv "$_f.tmp" "$_f" 2> /dev/null || log "could not write $_f"
}

# optional_report: the lines for the summary and "piso-setup status".
optional_report() {
	_f=$(opt_status_file)
	[ -r "$_f" ] || return 0
	echo "Smooth-line packages (optional):"
	sed -n 's/^pkg|\([^|]*\)|\([^|]*\)|\(.*\)$/\1|\2|\3/p' "$_f" | while IFS='|' read -r _n _s _r; do
		case "$_s" in
			installed) _w="installed" ;; present) _w="already installed" ;; builtin) _w="already in the kernel" ;;
			failed) _w="not installed (failed)" ;; *) _w="skipped" ;;
		esac
		case "$_s" in installed | present | builtin) printf '  %-16s %s\n' "$_n" "$_w" ;; *) printf '  %-16s %s: %s\n' "$_n" "$_w" "$_r" ;; esac
	done
}
